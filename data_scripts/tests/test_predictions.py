"""Offline tests for predictions.py (no network, no real Postgres, no real
model file needed).

predictions.py does not exist yet - this file defines its contract (TDD). It
ports the feature-engineering logic validated earlier against the real data
(see model/wind_solar_power_features.py / wind_shear_veer_turbulence.py,
which are being pruned - this is now the only place that logic survives), plus
a new prediction-writing layer that did not exist before.

predictions.py is expected to reuse ingestion.upsert_dataframe for the DB
write (not duplicate it) - the two modules are "self contained" with respect
to the pruned EDA scripts, not with respect to each other.

Run from the data_scripts/ folder:
    pip install -r requirements-dev.txt
    pytest -v tests/test_predictions.py
"""

import unittest
from datetime import datetime, timezone

import numpy as np
import pandas as pd
import sqlalchemy as sa

import predictions as pred


def make_engine() -> sa.Engine:
    return sa.create_engine("sqlite:///:memory:")


# --------------------------------------------------------------------------- #
# Imputation - ported from wind_solar_power_features.py / wind_shear_veer_turbulence.py
# --------------------------------------------------------------------------- #

class ImputationTests(unittest.TestCase):
    def test_speed_loglog_fills_missing_with_plausible_value(self):
        # location 0: known pairs where v120 ~= 1.15 * v80 (a stand-in shear
        # relationship); one missing v120 to be reconstructed from v80=20.
        df = pd.DataFrame({
            "location_id": [0, 0, 0, 0],
            "wind_speed_80m": [10.0, 15.0, 20.0, 20.0],
            "wind_speed_120m": [11.5, 17.25, np.nan, 23.0],
        })
        out = pred.impute_speed_loglog(df, "wind_speed_120m", "wind_speed_80m")
        self.assertTrue(np.isfinite(out.iloc[2]))
        # Should land near the known 1.15x relationship, not an arbitrary value.
        self.assertAlmostEqual(out.iloc[2], 23.0, delta=2.0)
        # Known values are untouched.
        self.assertEqual(out.iloc[0], 11.5)

    def test_direction_veer_handles_0_360_wraparound(self):
        # location 0: known pairs show a consistent +15 degree veer, including
        # across the wraparound (350 -> 365%360=5).
        df = pd.DataFrame({
            "location_id": [0, 0, 0],
            "wind_direction_80m": [10.0, 350.0, 100.0],
            "wind_direction_120m": [25.0, 5.0, np.nan],
        })
        out = pred.impute_direction_veer(df, "wind_direction_120m", "wind_direction_80m")
        self.assertAlmostEqual(out.iloc[2], 115.0, delta=1.0)  # 100 + 15, not a wraparound artifact

    def test_temperature_harmonic_recovers_seasonal_pattern(self):
        # Two years of a pure summer-hot/winter-cold sinusoid, one missing
        # mid-summer value to reconstruct.
        time = pd.date_range("2023-01-01", periods=24 * 365 * 2, freq="h", tz="UTC").tz_localize(None)
        doy = time.dayofyear
        temp = 15 + 10 * np.sin(2 * np.pi * (doy - 80) / 365.25)
        df = pd.DataFrame({"location_id": 0, "time": time, "temperature_120m": temp})
        missing_idx = df.index[(df["time"].dt.month == 7) & (df["time"].dt.day == 1) & (df["time"].dt.year == 2023)]
        df.loc[missing_idx, "temperature_120m"] = np.nan

        out = pred.impute_temperature_harmonic(df, "temperature_120m")
        true_value = temp[missing_idx[0]]
        self.assertAlmostEqual(out.loc[missing_idx[0]], true_value, delta=2.0)


# --------------------------------------------------------------------------- #
# Power-output proxies - physically-capped, not raw unbounded formulas
# --------------------------------------------------------------------------- #

class WindPowerProxyTests(unittest.TestCase):
    def test_below_cut_in_is_zero(self):
        df = pd.DataFrame({"wind_speed_120m": [2.0 * 3.6], "temperature_120m": [15.0]})  # 2 m/s
        out = pred.add_wind_power_proxy(df)
        self.assertEqual(out["wind_power_output_proxy"].iloc[0], 0.0)

    def test_above_cutout_is_zero(self):
        df = pd.DataFrame({"wind_speed_120m": [30.0 * 3.6], "temperature_120m": [15.0]})  # 30 m/s
        out = pred.add_wind_power_proxy(df)
        self.assertEqual(out["wind_power_output_proxy"].iloc[0], 0.0)

    def test_output_plateaus_at_rated_speed_not_unbounded_cubic(self):
        df = pd.DataFrame({
            "wind_speed_120m": [13.0 * 3.6, 20.0 * 3.6],  # both above rated (12.5 m/s), below cutout
            "temperature_120m": [15.0, 15.0],
        })
        out = pred.add_wind_power_proxy(df)
        # 20 m/s must NOT produce (20/13)^3 ~= 3.6x the power - it should be capped near the same value.
        ratio = out["wind_power_output_proxy"].iloc[1] / out["wind_power_output_proxy"].iloc[0]
        self.assertLess(ratio, 1.1)

    def test_output_increases_with_speed_in_normal_operating_range(self):
        df = pd.DataFrame({
            "wind_speed_120m": [5.0 * 3.6, 10.0 * 3.6],  # both between cut-in and rated
            "temperature_120m": [15.0, 15.0],
        })
        out = pred.add_wind_power_proxy(df)
        self.assertGreater(out["wind_power_output_proxy"].iloc[1], out["wind_power_output_proxy"].iloc[0])


class SolarPowerProxyTests(unittest.TestCase):
    def test_zero_irradiance_is_zero_output(self):
        df = pd.DataFrame({"shortwave_radiation": [0.0], "temperature_2m": [20.0]})
        out = pred.add_solar_power_proxy(df)
        self.assertEqual(out["solar_power_output_proxy"].iloc[0], 0.0)

    def test_hot_panel_derates_output_below_raw_irradiance(self):
        df = pd.DataFrame({"shortwave_radiation": [900.0], "temperature_2m": [40.0]})
        out = pred.add_solar_power_proxy(df)
        self.assertLess(out["solar_power_output_proxy"].iloc[0], 900.0)

    def test_never_negative(self):
        df = pd.DataFrame({"shortwave_radiation": [900.0], "temperature_2m": [55.0]})  # extreme heat
        out = pred.add_solar_power_proxy(df)
        self.assertGreaterEqual(out["solar_power_output_proxy"].iloc[0], 0.0)


# --------------------------------------------------------------------------- #
# Calendar features - UTC throughout by explicit decision: join convenience
# against the other UTC-keyed tables beats the minor accuracy cost of not
# converting to Central time (which would blur hour/day-of-week around DST
# transitions anyway). Related DST handling for sequence models: model/model.md S3.8.
# --------------------------------------------------------------------------- #

class CalendarFeatureTests(unittest.TestCase):
    def test_hour_day_of_week_month_are_utc(self):
        df = pd.DataFrame({"time": [pd.Timestamp("2025-07-04 23:30:00")]})  # UTC, no tz-conversion expected
        out = pred.add_calendar_features(df)
        self.assertEqual(out["hour"].iloc[0], 23)
        self.assertEqual(out["month"].iloc[0], 7)

    def test_us_holiday_flag(self):
        df = pd.DataFrame({"time": [pd.Timestamp("2025-07-04 12:00:00"), pd.Timestamp("2025-07-05 12:00:00")]})
        out = pred.add_calendar_features(df)
        self.assertEqual(out["is_us_holiday"].tolist(), [1, 0])


# --------------------------------------------------------------------------- #
# load_forecast_dam lookup - zone is ERCOT's weather-zone scheme
# (north/south/west/houston/system_total), NOT the LZ_ settlement-point
# naming used for electrical_price. Regression test for a real bug: this
# function was once called with location="LZ_NORTH" (DEFAULT_LOCATION),
# which matches zero rows against the zone column and silently produced an
# all-NaN load_forecast_dam_north_mwh feature.
# --------------------------------------------------------------------------- #

class LoadLoadForecastTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE load_forecast_dam (interval_start_utc TEXT, zone TEXT, "
                "load_forecast_mwh REAL, publish_time_utc TEXT, PRIMARY KEY (interval_start_utc, zone))"
            ))
            conn.execute(sa.text(
                "INSERT INTO load_forecast_dam VALUES "
                "('2026-03-02T05:00:00', 'north', 45000.0, '2026-03-01T14:30:00'), "
                "('2026-03-02T05:00:00', 'south', 12000.0, '2026-03-01T14:30:00')"
            ))

    def test_lowercase_zone_name_matches_rows(self):
        out = pred.load_load_forecast(self.engine, "north",
                                      datetime(2026, 3, 2), datetime(2026, 3, 3))
        self.assertEqual(len(out), 1)
        self.assertEqual(out["load_forecast_dam_north_mwh"].iloc[0], 45000.0)

    def test_lz_north_settlement_point_name_matches_nothing(self):
        out = pred.load_load_forecast(self.engine, "LZ_NORTH",
                                      datetime(2026, 3, 2), datetime(2026, 3, 3))
        self.assertEqual(len(out), 0)


# --------------------------------------------------------------------------- #
# Prediction output + write
# --------------------------------------------------------------------------- #

class PredictTests(unittest.TestCase):
    def test_predict_prices_output_schema(self):
        times = pd.to_datetime(["2026-03-02T05:00:00"])
        generated_at = datetime(2026, 3, 1, 21, 0, tzinfo=timezone.utc)

        out = pred.predict_prices(np.array([42.0]), times, location="LZ_NORTH",
                                   model_version="xgboost_baseline_full_2026-03-01", generated_at=generated_at)

        self.assertEqual(list(out.columns),
                         ["interval_start_utc", "location", "predicted_price", "model_version", "generated_at"])
        self.assertEqual(out["predicted_price"].iloc[0], 42.0)
        self.assertEqual(out["location"].iloc[0], "LZ_NORTH")

    def test_load_model_rejects_unknown_file_type(self):
        with self.assertRaises(ValueError):
            pred.load_model("some_model.pkl")


# --------------------------------------------------------------------------- #
# Exogenous inputs shared by the XGBoost and LSTM feature builders
# --------------------------------------------------------------------------- #

class LoadExogenousTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text("CREATE TABLE fuel_price (period TEXT, henry_hub_price_usd_mmbtu REAL)"))
            conn.execute(sa.text("CREATE TABLE wind (location_id INTEGER, time TEXT, wind_speed_80m REAL, "
                                 "wind_speed_120m REAL, temperature_120m REAL)"))
            conn.execute(sa.text("CREATE TABLE solar (location_id INTEGER, time TEXT, "
                                 "shortwave_radiation REAL, temperature_2m REAL)"))
            conn.execute(sa.text("CREATE TABLE load_forecast_dam (interval_start_utc TEXT, zone TEXT, "
                                 "load_forecast_mwh REAL)"))
            # Thursday + Friday published; nothing over the weekend or after.
            conn.execute(sa.text("INSERT INTO fuel_price VALUES ('2026-02-26T00:00:00', 3.0), "
                                 "('2026-02-27T00:00:00', 3.5)"))

    def test_gas_lag_is_carried_past_the_last_published_day(self):
        # A day-ahead run always targets a day with no gas row yet - it must
        # get the last published price, not NaN.
        out = pred.load_exogenous(self.engine, datetime(2026, 2, 27), datetime(2026, 3, 2, 23))
        by_day = out.groupby(out["time"].dt.date)["henry_hub_price_usd_mmbtu_lag1d"].first()
        self.assertEqual(by_day.tolist(), [3.0, 3.5, 3.5, 3.5])

    def test_stale_gas_is_not_carried_forever(self):
        out = pred.load_exogenous(self.engine, datetime(2026, 3, 4), datetime(2026, 3, 8, 23))
        by_day = out.groupby(out["time"].dt.date)["henry_hub_price_usd_mmbtu_lag1d"].first()
        self.assertEqual(by_day.iloc[0], 3.5)
        self.assertTrue(np.isnan(by_day.iloc[-1]))

    def test_one_row_per_hour_even_with_no_source_data(self):
        out = pred.load_exogenous(self.engine, datetime(2026, 3, 2), datetime(2026, 3, 2, 23))
        self.assertEqual(len(out), 24)
        self.assertTrue(out["wind_power_output_proxy_total"].isna().all())


class WritePredictionsTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE predicted_price ("
                "interval_start_utc TEXT, location TEXT, predicted_price REAL, "
                "model_version TEXT, generated_at TEXT, "
                "PRIMARY KEY (interval_start_utc, location, model_version, generated_at))"
            ))

    def test_two_runs_for_the_same_hour_both_survive(self):
        # This is the whole point of including generated_at in the key: a
        # day-ahead prediction and a same-day revision for the same target
        # hour must NOT overwrite each other, so accuracy-over-time can be
        # tracked per run, not just per target hour.
        df1 = pd.DataFrame({
            "interval_start_utc": ["2026-03-02T05:00:00"], "location": ["LZ_NORTH"],
            "predicted_price": [40.0], "model_version": ["v1"], "generated_at": ["2026-03-01T21:00:00"],
        })
        df2 = pd.DataFrame({
            "interval_start_utc": ["2026-03-02T05:00:00"], "location": ["LZ_NORTH"],
            "predicted_price": [41.0], "model_version": ["v1"], "generated_at": ["2026-03-02T12:00:00"],
        })
        pred.write_predictions(self.engine, df1)
        pred.write_predictions(self.engine, df2)

        with self.engine.begin() as conn:
            rows = pd.read_sql("SELECT * FROM predicted_price", conn)
        self.assertEqual(len(rows), 2)
        self.assertEqual(sorted(rows["predicted_price"].tolist()), [40.0, 41.0])


if __name__ == "__main__":
    unittest.main()
