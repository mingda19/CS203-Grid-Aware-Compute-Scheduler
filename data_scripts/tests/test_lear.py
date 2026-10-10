"""Offline tests for the LEAR forecaster (model/lear_model.py). No network,
no database: every test but the last runs on a small synthetic hourly frame.
The last one needs model/train.csv + val.csv and is skipped without them.

Run from the data_scripts/ folder:
    pip install -r requirements-dev.txt
    pytest -v tests/test_lear.py
"""

import sys
import unittest

import numpy as np
import pandas as pd

import predictions as pred

if str(pred.MODEL_DIR) not in sys.path:
    sys.path.insert(0, str(pred.MODEL_DIR))
import lear_model as lear  # noqa: E402

N_DAYS = 120
FIRST_DAY = pd.Timestamp("2025-01-01")
TARGET_DAY = FIRST_DAY + pd.Timedelta(days=N_DAYS - 1)
WINDOWS = (28, 56)
LOAD, WIND, SOLAR, GAS = lear.EXOG_COLS


def make_frame(seed: int = 0) -> pd.DataFrame:
    """N_DAYS of hourly data with a daily price shape driven by load and wind."""
    rng = np.random.default_rng(seed)
    index = pd.date_range(FIRST_DAY, periods=N_DAYS * 24, freq="h")
    hour, day = index.hour.to_numpy(), np.arange(len(index)) // 24
    load = 15000 + 3000 * np.sin(2 * np.pi * (hour - 15) / 24) + rng.normal(0, 300, len(index))
    wind = rng.uniform(0, 9000, len(index))
    solar = np.clip(6000 * np.sin(np.pi * (hour - 13) / 11), 0, None) * rng.uniform(0.5, 1, len(index))
    price = 20 + 0.004 * (load - 15000) - 0.001 * wind + rng.normal(0, 2, len(index))
    return pd.DataFrame({lear.RAW_PRICE_COL: price, LOAD: load, WIND: wind, SOLAR: solar,
                         GAS: 3 + 0.01 * day, "is_us_holiday": 0}, index=index)


def make_ramp_frame() -> pd.DataFrame:
    """Every value says where it came from: day index x 100 + hour (+ an offset per column)."""
    frame = make_frame()
    ramp = (np.arange(len(frame)) // 24) * 100 + frame.index.hour.to_numpy()
    frame[lear.RAW_PRICE_COL], frame[LOAD], frame[WIND], frame[SOLAR] = ramp, ramp + 0.25, ramp + 0.5, ramp + 0.75
    frame[GAS] = (np.arange(len(frame)) // 24).astype(float)
    return frame


def forecaster(**kwargs) -> lear.LEARForecaster:
    return lear.LEARForecaster(**{"windows": WINDOWS, **kwargs})


class TestTransform(unittest.TestCase):

    def test_round_trip(self):
        x = np.array([-250.0, -6.09, 0.0, 17.66, 24.91, 38.97, 9011.25])
        median, scale = lear.fit_transform_params(x)
        np.testing.assert_allclose(lear.inverse_transform(lear.transform(x, median, scale), median, scale), x,
                                   rtol=0, atol=1e-9)

    def test_params_are_median_and_scaled_mad(self):
        median, scale = lear.fit_transform_params([1.0, 2.0, 3.0, 4.0, 100.0])
        self.assertEqual(median, 3.0)
        self.assertAlmostEqual(scale, 1.4826 * 1.0)

    def test_zero_mad_falls_back_to_a_usable_scale(self):
        mostly_zero = np.array([0.0] * 8 + [50.0, 60.0])
        self.assertGreater(lear.fit_transform_params(mostly_zero)[1], 0)
        self.assertEqual(lear.fit_transform_params(np.zeros(5)), (0.0, 1.0))


class TestDesignMatrix(unittest.TestCase):

    def setUp(self):
        self.design = lear.build_design(make_ramp_frame(), TARGET_DAY)
        self.features = pd.DataFrame(self.design.X, index=self.design.days, columns=self.design.names)

    def test_layout(self):
        counts = pd.Series(self.design.groups).value_counts()
        self.assertEqual(self.design.X.shape[1], 96 + 72 + 72 + 24 + 1 + 8)
        for k in (1, 2, 3, 7):
            self.assertEqual(counts[f"price D-{k}"], 24)
        for col in (LOAD, WIND):
            self.assertEqual([counts[f"{col} D-{k}"] for k in (0, 1, 7)], [24, 24, 24])
        self.assertEqual(counts[f"{SOLAR} D-0"], 24)
        self.assertNotIn(f"{SOLAR} D-1", counts.index)
        self.assertEqual(self.design.days[-1], TARGET_DAY)

    def test_price_lags_hold_exactly_the_lagged_day(self):
        for day_index in (N_DAYS - 1, 60, 7):
            row = self.features.iloc[day_index]
            for k in (1, 2, 3, 7):
                got = row[[f"price_d{k}_h{h:02d}" for h in range(24)]].to_numpy()
                np.testing.assert_array_equal(got, (day_index - k) * 100 + np.arange(24))

    def test_exogenous_profiles_are_aligned(self):
        row = self.features.iloc[-1]
        for col, shift in ((LOAD, 0.25), (WIND, 0.5)):
            for k in (0, 1, 7):
                got = row[[f"{col}_d{k}_h{h:02d}" for h in range(24)]].to_numpy()
                np.testing.assert_array_equal(got, (N_DAYS - 1 - k) * 100 + np.arange(24) + shift)
        self.assertEqual(row[f"{GAS}_d0"], N_DAYS - 1)
        weekday = [row[f"dow_{d}"] for d in range(7)]
        self.assertEqual((sum(weekday), weekday[TARGET_DAY.dayofweek]), (1, 1))

    def test_targets_are_that_days_prices_and_blank_from_the_cutoff(self):
        np.testing.assert_array_equal(self.design.Y[60], 60 * 100 + np.arange(24))
        self.assertTrue(np.isnan(self.design.Y[-1]).all())
        self.assertTrue(np.isnan(self.features.iloc[0].filter(like="price_d1")).all())  # no day before the first

    def test_solar_lags_are_optional(self):
        design = lear.build_design(make_ramp_frame(), TARGET_DAY, solar_lags=True)
        self.assertEqual(design.X.shape[1], self.design.X.shape[1] + 48)


class TestForecast(unittest.TestCase):

    def test_returns_24_finite_prices(self):
        out = forecaster().predict_day(make_frame(), TARGET_DAY)
        self.assertEqual(out.shape, (24,))
        self.assertTrue(np.isfinite(out).all())

    def test_forecast_tracks_the_daily_shape(self):
        frame = make_frame()
        actual = frame.loc[TARGET_DAY:, lear.RAW_PRICE_COL].to_numpy()
        out = forecaster().predict_day(frame, TARGET_DAY)
        self.assertLess(np.abs(out - actual).mean(), 5.0)
        self.assertGreater(np.corrcoef(out, actual)[0, 1], 0.5)

    def test_price_from_the_target_day_on_is_never_read(self):
        frame = make_frame()
        planted = frame.copy()
        planted.loc[TARGET_DAY:, lear.RAW_PRICE_COL] = 99999.0
        blank = frame.copy()
        blank.loc[TARGET_DAY:, lear.RAW_PRICE_COL] = np.nan
        clean = forecaster().predict_day(frame, TARGET_DAY)
        np.testing.assert_array_equal(forecaster().predict_day(planted, TARGET_DAY), clean)
        np.testing.assert_array_equal(forecaster().predict_day(blank, TARGET_DAY), clean)

    def test_planted_price_before_the_cutoff_does_change_the_forecast(self):
        """The cutoff test above would pass for a model that ignored price altogether."""
        frame = make_frame()
        planted = frame.copy()
        planted.loc[TARGET_DAY - pd.Timedelta(days=1):TARGET_DAY - pd.Timedelta(hours=1), lear.RAW_PRICE_COL] = 500.0
        self.assertFalse(np.array_equal(forecaster().predict_day(planted, TARGET_DAY),
                                        forecaster().predict_day(frame, TARGET_DAY)))

    def test_deterministic(self):
        frame = make_frame()
        first = forecaster().predict_day(frame, TARGET_DAY)
        np.testing.assert_array_equal(forecaster().predict_day(frame, TARGET_DAY), first)
        model = forecaster()
        model.predict_day(frame, TARGET_DAY - pd.Timedelta(days=3))
        np.testing.assert_array_equal(model.predict_day(frame, TARGET_DAY), first)  # no state carried over

    def test_forecast_is_the_average_of_the_windows(self):
        frame = make_frame()
        each = [forecaster(windows=(w,)).predict_day(frame, TARGET_DAY) for w in WINDOWS]
        np.testing.assert_allclose(forecaster().predict_day(frame, TARGET_DAY), np.mean(each, axis=0))

    def test_window_longer_than_the_history_is_skipped(self):
        frame = make_frame()
        model = forecaster(windows=(28, 56, 1092))
        out = model.predict_day(frame, TARGET_DAY)
        self.assertEqual(model.windows_used, [28, 56])
        np.testing.assert_array_equal(out, forecaster().predict_day(frame, TARGET_DAY))

    def test_shortest_window_is_required(self):
        with self.assertRaises(lear.MissingInputError):
            forecaster(windows=(1092, 1456)).predict_day(make_frame(), TARGET_DAY)

    def test_missing_price_history_raises(self):
        frame = make_frame()
        frame.loc[TARGET_DAY - pd.Timedelta(days=1), lear.RAW_PRICE_COL] = np.nan
        with self.assertRaises(lear.MissingInputError):
            forecaster().predict_day(frame, TARGET_DAY)

    def test_missing_exogenous_group_is_dropped_not_fatal(self):
        frame = make_frame()
        no_column = forecaster()
        self.assertTrue(np.isfinite(no_column.predict_day(frame.drop(columns=[WIND]), TARGET_DAY)).all())
        self.assertFalse(any(WIND in name for name in no_column.fitted["names"]))

        frame.loc[TARGET_DAY + pd.Timedelta(hours=5), LOAD] = np.nan
        gap = forecaster()
        self.assertTrue(np.isfinite(gap.predict_day(frame, TARGET_DAY)).all())
        self.assertEqual(gap.dropped_groups, [f"{LOAD} D-0"])
        self.assertIn(f"{LOAD}_d1_h00", gap.fitted["names"])

    def test_recal_every_reuses_the_calibration_between_refits(self):
        frame = make_frame()
        model = forecaster(recal_every=7)
        model.predict_day(frame, TARGET_DAY - pd.Timedelta(days=2))
        calibration = model.fitted
        model.predict_day(frame, TARGET_DAY)
        self.assertIs(model.fitted, calibration)
        self.assertTrue(np.isfinite(model.predict_day(frame, TARGET_DAY)).all())

    def test_inputs_far_outside_the_calibration_range_stay_bounded(self):
        """Unclipped, one wild input can push sinh() to astronomically large prices."""
        frame = make_frame()
        frame.loc[TARGET_DAY:, [LOAD, WIND, SOLAR]] *= 1e6
        out = forecaster().predict_day(frame, TARGET_DAY)
        self.assertLess(np.abs(out).max(), 1e4)


class TestHorizon(unittest.TestCase):

    def test_two_days_ahead_uses_no_price_after_d_minus_2(self):
        frame = make_frame()
        planted = frame.copy()
        planted.loc[TARGET_DAY - pd.Timedelta(days=1):, lear.RAW_PRICE_COL] = 99999.0
        np.testing.assert_array_equal(forecaster(horizon_days=2).predict_day(planted, TARGET_DAY),
                                      forecaster(horizon_days=2).predict_day(frame, TARGET_DAY))

    def test_two_days_ahead_feature_set(self):
        self.assertEqual(lear.price_day_offsets(1), [1, 2, 3, 7])
        self.assertEqual(lear.price_day_offsets(2), [2, 3, 4, 7])
        notes = []
        design = lear.build_design(make_ramp_frame(), TARGET_DAY, horizon_days=2, log=notes.append)
        groups = set(design.groups)
        self.assertNotIn("price D-1", groups)
        self.assertNotIn(f"{LOAD} D-0", groups)  # the DAM load forecast only exists one day ahead
        self.assertTrue({f"{LOAD} D-1", f"{LOAD} D-7", f"{WIND} D-0", f"{GAS} D-1"} <= groups)
        self.assertTrue(any(LOAD in note for note in notes))


@unittest.skipUnless((pred.MODEL_DIR / "train.csv").exists() and (pred.MODEL_DIR / "val.csv").exists(),
                     "needs model/train.csv and model/val.csv")
class TestHoldoutSanity(unittest.TestCase):

    def test_holdout_mae_is_finite(self):
        """Report only, no threshold: LEAR next to naive D-1 on the hold-out
        period, recalibrating weekly to keep the test short."""
        parts = [pd.read_csv(pred.MODEL_DIR / f"{name}.csv", parse_dates=["time"]) for name in ("train", "val")]
        frame = pd.concat(parts).set_index("time").asfreq("h")
        price = frame[lear.RAW_PRICE_COL]
        days = [d for d in pd.date_range("2026-02-01", price.index.max().floor("D"), freq="D")
                if price.reindex(pd.date_range(d, periods=24, freq="h")).notna().all()]
        model = lear.LEARForecaster(recal_every=7, n_jobs=4)
        actual = np.array([price.reindex(pd.date_range(d, periods=24, freq="h")).to_numpy() for d in days])
        naive = np.array([price.reindex(pd.date_range(d, periods=24, freq="h") - pd.Timedelta(days=1)).to_numpy()
                          for d in days])
        forecast = np.array([model.predict_day(frame, d) for d in days])
        lear_mae, naive_mae = np.abs(forecast - actual).mean(), np.abs(naive - actual).mean()
        print(f"\nHold-out {days[0].date()}..{days[-1].date()} ({len(days)} days): "
              f"LEAR MAE {lear_mae:.2f}, naive D-1 MAE {naive_mae:.2f} $/MWh")
        self.assertTrue(np.isfinite(forecast).all())
        self.assertTrue(np.isfinite(lear_mae))


if __name__ == "__main__":
    unittest.main()
