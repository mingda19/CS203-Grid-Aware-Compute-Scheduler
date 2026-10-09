"""Offline tests for the LSTM serving path (model/lstm_model.py as used by
predictions.py). No network, no real Postgres, no trained model file: each
test builds a tiny randomly-initialised artifact and an in-memory SQLite DB
with the same table shapes predictions.py reads.

Run from the data_scripts/ folder:
    pip install -r requirements-dev.txt
    pytest -v tests/test_lstm.py
"""

import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd
import sqlalchemy as sa

import predictions as pred

lstm = pred._lstm_module()  # imports torch - after xgboost, see predictions._lstm_module
import torch  # noqa: E402

TARGET_DAY = pd.Timestamp("2026-03-02")
TARGET_TIMES = pd.date_range(TARGET_DAY, periods=24, freq="h")
HISTORY_START = TARGET_DAY - pd.Timedelta(hours=lstm.PAST_WINDOW)
EXOG_COLS = ["load_forecast_dam_north_mwh", "wind_power_output_proxy_total",
             "solar_power_output_proxy_total", "henry_hub_price_usd_mmbtu_lag1d"]


def make_artifact(seed: int = 0, quantiles=None) -> dict:
    """A small untrained model wrapped in a complete artifact."""
    torch.manual_seed(seed)
    cfg = {
        "kind": "lstm", "past_window": lstm.PAST_WINDOW, "horizon": lstm.HORIZON,
        "exog_cols": list(EXOG_COLS), "calendar_cols": list(lstm.CALENDAR_COLS),
        "price_clip": (0.0, 500.0), "target_transform": "signed_log1p",
        "trained_on": "train", "train_end": "2026-01-31 23:00:00", "best_epoch": 1, "metrics": {},
    }
    cfg["scalers"] = {name: (0.0, 1.0) for name in lstm.past_feature_names(cfg)}
    cfg["scalers"]["load_forecast_dam_north_mwh"] = (15000.0, 3000.0)
    cfg["arch"] = {"hidden": 8, "layers": 2, "dropout": 0.0, "n_past_feats": len(lstm.past_feature_names(cfg)),
                   "n_future_feats": len(lstm.future_feature_names(cfg)),
                   "quantiles": list(quantiles or lstm.DEFAULT_QUANTILES)}
    model = lstm.LSTMForecaster(cfg["arch"]["n_past_feats"], cfg["arch"]["n_future_feats"], hidden=8, layers=2,
                                dropout=0.0, quantiles=cfg["arch"]["quantiles"])
    cfg["state_dict"] = model.state_dict()
    return cfg


def make_db(price_after_cutoff: float | None = None, drop_load_hours: int = 0) -> sa.Engine:
    """History window + target day for every source build_sequence reads.
    `price_after_cutoff` adds prices for the target day itself (which a
    day-ahead run must never use); `drop_load_hours` removes that many hours
    of the target day's load forecast."""
    engine = sa.create_engine("sqlite:///:memory:")
    hours = pd.date_range(HISTORY_START, TARGET_TIMES.max(), freq="h")
    iso = [t.isoformat() for t in hours]
    rng = np.random.default_rng(0)

    price = pd.DataFrame({"interval_start_utc": iso, "location": "LZ_NORTH",
                          "spp_usd_mwh": 30 + 10 * rng.random(len(hours))})
    is_future = hours >= TARGET_DAY
    price = price[~is_future] if price_after_cutoff is None else price.assign(
        spp_usd_mwh=np.where(is_future, price_after_cutoff, price["spp_usd_mwh"]))

    weather = pd.DataFrame({"location_id": 0, "time": iso})
    wind = weather.assign(wind_speed_80m=25.0, wind_speed_120m=30 + rng.random(len(hours)), temperature_120m=12.0)
    solar = weather.assign(shortwave_radiation=400 * rng.random(len(hours)), temperature_2m=18.0)
    load = pd.DataFrame({"interval_start_utc": iso, "zone": "north",
                         "load_forecast_mwh": 14000 + 2000 * rng.random(len(hours))})
    if drop_load_hours:
        load = load.iloc[:-drop_load_hours]
    days = pd.date_range(HISTORY_START - pd.Timedelta(days=3), TARGET_DAY, freq="D")
    gas = pd.DataFrame({"period": [d.isoformat() for d in days], "henry_hub_price_usd_mmbtu": 3.0})

    with engine.begin() as conn:
        for table, df in [("electrical_price", price), ("wind", wind), ("solar", solar),
                          ("load_forecast_dam", load), ("fuel_price", gas)]:
            df.to_sql(table, conn, index=False)
    return engine


class BuildSequenceTests(unittest.TestCase):
    def test_shapes_match_the_artifact(self):
        artifact = make_artifact()
        past, future = pred.build_sequence(make_db(), TARGET_TIMES, "LZ_NORTH", artifact)
        self.assertEqual(tuple(past.shape), (1, 168, artifact["arch"]["n_past_feats"]))
        self.assertEqual(tuple(future.shape), (1, 24, artifact["arch"]["n_future_feats"]))
        self.assertTrue(torch.isfinite(past).all() and torch.isfinite(future).all())

    def test_prices_after_the_cutoff_do_not_change_the_inputs(self):
        # Same DB, except one also holds (absurd) prices for the target day.
        # A day-ahead forecast must be identical either way.
        artifact = make_artifact()
        clean = pred.build_sequence(make_db(), TARGET_TIMES, "LZ_NORTH", artifact)
        leaky = pred.build_sequence(make_db(price_after_cutoff=9999.0), TARGET_TIMES, "LZ_NORTH", artifact)
        self.assertTrue(torch.equal(clean[0], leaky[0]))
        self.assertTrue(torch.equal(clean[1], leaky[1]))

    def test_price_is_never_queried_past_the_cutoff(self):
        loaded = []
        original = pred.load_price_history

        def spy(*args, **kwargs):
            df = original(*args, **kwargs)
            loaded.append(df)
            return df

        pred.load_price_history = spy
        try:
            pred.build_sequence(make_db(price_after_cutoff=9999.0), TARGET_TIMES, "LZ_NORTH", make_artifact())
        finally:
            pred.load_price_history = original
        self.assertEqual(len(loaded), 1)
        self.assertEqual(loaded[0]["time"].max(), TARGET_DAY - pd.Timedelta(hours=1))
        self.assertEqual(len(loaded[0]), 168)

    def test_missing_input_raises_and_names_it(self):
        with self.assertRaises(lstm.MissingInputError) as ctx:
            pred.build_sequence(make_db(drop_load_hours=5), TARGET_TIMES, "LZ_NORTH", make_artifact())
        self.assertIn("load_forecast_dam_north_mwh (target day): 5h", str(ctx.exception))

    def test_unknown_exogenous_column_is_reported(self):
        artifact = make_artifact()
        artifact["exog_cols"] = artifact["exog_cols"] + ["battery_soc_mwh"]
        with self.assertRaises(ValueError) as ctx:
            pred.build_sequence(make_db(), TARGET_TIMES, "LZ_NORTH", artifact)
        self.assertIn("battery_soc_mwh", str(ctx.exception))


class ArtifactTests(unittest.TestCase):
    def test_round_trip_gives_identical_predictions(self):
        artifact = make_artifact(seed=1)
        sequence = pred.build_sequence(make_db(), TARGET_TIMES, "LZ_NORTH", artifact)
        before = lstm.predict_quantiles(lstm.build_model(artifact), *sequence, artifact)

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "lstm_test.pt"
            lstm.save_artifact(path, artifact)
            kind, model = pred.load_model(path)

        self.assertEqual(kind, "lstm")
        self.assertFalse(model.training)
        self.assertEqual(model.artifact["exog_cols"], EXOG_COLS)
        after = lstm.predict_quantiles(model, *sequence, model.artifact)
        np.testing.assert_array_equal(before, after)


class QuantileTests(unittest.TestCase):
    def test_quantiles_are_monotonic(self):
        # An untrained head has no reason to emit ordered quantiles on its own.
        artifact = make_artifact(seed=2)
        model = lstm.build_model(artifact)
        model.artifact = artifact
        rng = np.random.default_rng(3)
        past = rng.normal(size=(16, 168, artifact["arch"]["n_past_feats"])).astype(np.float32)
        future = rng.normal(size=(16, 24, artifact["arch"]["n_future_feats"])).astype(np.float32)

        with torch.no_grad():
            raw = model(torch.from_numpy(past), torch.from_numpy(future)).numpy()
        self.assertTrue((np.diff(raw, axis=-1) < 0).any(), "test needs at least one crossing to be meaningful")

        preds = lstm.predict_quantiles(model, past, future, artifact)
        self.assertTrue((np.diff(preds, axis=-1) >= 0).all())

        p10, p50, p90 = pred.lstm_predict(model, (past[:1], future[:1]))
        self.assertTrue((p10 <= p50).all() and (p50 <= p90).all())
        self.assertEqual(p50.shape, (24,))

    def test_point_model_has_no_interval(self):
        artifact = make_artifact(quantiles=[0.5])
        model = lstm.build_model(artifact)
        model.artifact = artifact
        sequence = pred.build_sequence(make_db(), TARGET_TIMES, "LZ_NORTH", artifact)
        p10, p50, p90 = pred.lstm_predict(model, sequence)
        self.assertIsNone(p10)
        self.assertIsNone(p90)
        self.assertEqual(p50.shape, (24,))


class TransformTests(unittest.TestCase):
    def test_signed_log_round_trips_negative_and_spike_prices(self):
        prices = np.array([-25.0, 0.0, 3.5, 60.0, 5000.0])
        np.testing.assert_allclose(lstm.signed_expm1(lstm.signed_log1p(prices)), prices, rtol=1e-9, atol=1e-9)


class RunPredictionsTests(unittest.TestCase):
    def test_lstm_run_writes_24_rows_under_its_own_model_version(self):
        engine = make_db()
        with engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE predicted_price ("
                "interval_start_utc TEXT, location TEXT, predicted_price REAL, "
                "model_version TEXT, generated_at TEXT, "
                "PRIMARY KEY (interval_start_utc, location, model_version, generated_at))"
            ))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "lstm_train.pt"
            lstm.save_artifact(path, make_artifact())
            result = pred.run_predictions(engine, model_path=path, target_date=TARGET_DAY.date())

        self.assertEqual(result.rows_written, 24)
        with engine.begin() as conn:
            rows = pd.read_sql("SELECT * FROM predicted_price", conn)
        self.assertEqual(len(rows), 24)
        self.assertEqual(set(rows["model_version"]), {"lstm_train"})
        self.assertTrue(np.isfinite(rows["predicted_price"]).all())


if __name__ == "__main__":
    unittest.main()
