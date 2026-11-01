"""Offline tests for predict_lstm.py/predict_lear.py's shared machinery in
predictions.py: load_model_from_blob, build_lear_frame, run_predictions_from_supabase,
and load_reference_rmse's graceful degradation. No network, no real Postgres.

Run from the data_scripts/ folder:
    pytest -v tests/test_predict_split.py
"""

import io
import unittest

import numpy as np
import pandas as pd
import sqlalchemy as sa

import model_store as store
import predictions as pred
import test_lstm  # sibling test module - reuses its make_db()/make_artifact() for the LSTM half

lstm = pred._lstm_module()
lear = pred._lear_module()


def add_model_artifacts_table(engine: sa.Engine) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text(
            "CREATE TABLE model_artifacts ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " model_kind TEXT NOT NULL, target TEXT NOT NULL, model_version TEXT NOT NULL,"
            " format TEXT NOT NULL, blob BLOB NOT NULL, trained_through TEXT NOT NULL,"
            " valid_from_date TEXT NOT NULL, valid_to_date TEXT NOT NULL,"
            " metrics TEXT NOT NULL DEFAULT '{}', is_active BOOLEAN NOT NULL DEFAULT 0,"
            " created_at TEXT NOT NULL DEFAULT (datetime('now')))"
        ))
        conn.execute(sa.text(
            "CREATE UNIQUE INDEX model_artifacts_one_active ON model_artifacts (model_kind, target) "
            "WHERE is_active"
        ))


def save_and_promote(engine, *, kind, target, blob, fmt):
    return store.save_model_artifact(
        engine, model_kind=kind, target=target, blob=blob, format=fmt, model_version=f"{kind}_{target}_test",
        trained_through="2026-01-31 23:00:00", valid_from_date="2026-02-01", valid_to_date="2026-10-31",
        metrics={"mae": 1.0}, promote=True,
    )


class LoadModelFromBlobTests(unittest.TestCase):
    def test_torch_blob_round_trips_as_lstm(self):
        artifact = test_lstm.make_artifact()
        buf = io.BytesIO()
        lstm.save_artifact(buf, artifact)
        kind, model = pred.load_model_from_blob(buf.getvalue(), "torch")
        self.assertEqual(kind, "lstm")
        self.assertEqual(model.artifact["exog_cols"], artifact["exog_cols"])

    def test_joblib_blob_round_trips_as_lear(self):
        rng = np.random.default_rng(0)
        index = pd.date_range("2025-01-01", periods=60 * 24, freq="h")
        wind, solar, gas = lear.EXOG_COLS
        frame = pd.DataFrame({
            lear.RAW_PRICE_COL: 20 + rng.normal(0, 2, len(index)), wind: rng.uniform(0, 9000, len(index)),
            solar: rng.uniform(0, 5000, len(index)), gas: 3.0, "is_us_holiday": 0,
        }, index=index)
        model = lear.LEARForecaster(windows=(28,), n_jobs=1)
        model.predict_day(frame, index.max().normalize())
        artifact = model.to_artifact(target="y0")
        buf = io.BytesIO()
        lear.save_artifact(buf, artifact)
        kind, restored = pred.load_model_from_blob(buf.getvalue(), "joblib")
        self.assertEqual(kind, "lear")
        self.assertEqual(restored.artifact["target"], "y0")

    def test_unknown_format_raises(self):
        with self.assertRaises(ValueError):
            pred.load_model_from_blob(b"junk", "pickle")


class RunPredictionsFromSupabaseLstmTests(unittest.TestCase):
    def test_serves_the_active_lstm_artifact(self):
        engine = test_lstm.make_db()
        add_model_artifacts_table(engine)
        with engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE predicted_price ("
                "interval_start_utc TEXT, location TEXT, predicted_price REAL, "
                "model_version TEXT, generated_at TEXT, confidence_score REAL, "
                "PRIMARY KEY (interval_start_utc, location, model_version, generated_at))"
            ))
        artifact = test_lstm.make_artifact()
        buf = io.BytesIO()
        lstm.save_artifact(buf, artifact)
        save_and_promote(engine, kind="lstm", target="y0", blob=buf.getvalue(), fmt="torch")

        anchor_date = (test_lstm.TARGET_DAY - pd.Timedelta(hours=pred.LEAD_HOURS["y0"])).date()
        results = pred.run_predictions_from_supabase(engine, "lstm", ["y0"], anchor_date=anchor_date)

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].rows_written, 24)
        self.assertEqual(results[0].model_version, "lstm_y0_test")
        with engine.begin() as conn:
            rows = pd.read_sql("SELECT * FROM predicted_price", conn)
        self.assertEqual(len(rows), 24)
        self.assertTrue(np.isfinite(rows["predicted_price"]).all())

    def test_horizon_with_no_active_row_is_skipped_not_fatal(self):
        engine = test_lstm.make_db()
        add_model_artifacts_table(engine)
        with engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE predicted_price ("
                "interval_start_utc TEXT, location TEXT, predicted_price REAL, "
                "model_version TEXT, generated_at TEXT, confidence_score REAL, "
                "PRIMARY KEY (interval_start_utc, location, model_version, generated_at))"
            ))
        # Nothing ever saved/promoted - this is a brand-new deployment before update.py's first run.
        results = pred.run_predictions_from_supabase(engine, "lstm", ["y0"], anchor_date=test_lstm.TARGET_DAY.date())
        self.assertEqual(results, [])


class BuildLearFrameTests(unittest.TestCase):
    def test_frame_has_everything_lear_needs_and_no_future_price(self):
        rng = np.random.default_rng(1)
        wind, solar, gas = lear.EXOG_COLS
        index = pd.date_range("2025-06-01", periods=150 * 24, freq="h")
        engine = sa.create_engine("sqlite:///:memory:")
        price = pd.DataFrame({"interval_start_utc": [t.isoformat() for t in index], "location": "LZ_NORTH",
                              "spp_usd_mwh": 20 + rng.normal(0, 2, len(index))})
        anchor = index.max().normalize() - pd.Timedelta(days=5)
        target_times = pd.date_range(anchor + pd.Timedelta(hours=48), periods=24, freq="h")
        price = price[pd.to_datetime(price["interval_start_utc"]) < anchor]  # nothing from the anchor day on

        weather = pd.DataFrame({"location_id": 0, "time": [t.isoformat() for t in index]})
        wind_df = weather.assign(wind_speed_80m=25.0, wind_speed_120m=30.0, temperature_120m=12.0)
        solar_df = weather.assign(shortwave_radiation=400.0, temperature_2m=18.0)
        gas_days = pd.date_range(index.min() - pd.Timedelta(days=10), index.max(), freq="D")
        gas_df = pd.DataFrame({"period": [d.isoformat() for d in gas_days], "henry_hub_price_usd_mmbtu": 3.0})
        with engine.begin() as conn:
            for table, df in [("electrical_price", price), ("wind", wind_df), ("solar", solar_df),
                              ("fuel_price", gas_df)]:
                df.to_sql(table, conn, index=False)

        artifact = {"lead_hours": 48, "windows": (28, 56), "exog_cols": list(lear.EXOG_COLS)}
        frame = pred.build_lear_frame(engine, target_times, "LZ_NORTH", artifact)
        self.assertIn(lear.RAW_PRICE_COL, frame.columns)
        for col in lear.EXOG_COLS:
            self.assertIn(col, frame.columns)
        self.assertTrue(frame.loc[anchor:, lear.RAW_PRICE_COL].isna().all())


class ReferenceRmseTests(unittest.TestCase):
    def test_none_when_table_is_missing(self):
        engine = sa.create_engine("sqlite:///:memory:")  # no rmse_reference table at all
        self.assertIsNone(pred.load_reference_rmse(engine, "lstm", "y0"))

    def test_reads_a_complete_row_from_the_table(self):
        engine = sa.create_engine("sqlite:///:memory:")
        with engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE rmse_reference (model_kind TEXT, target TEXT, hour_utc INTEGER, rmse REAL)"
            ))
            conn.execute(sa.text(
                "INSERT INTO rmse_reference (model_kind, target, hour_utc, rmse) VALUES (:k, :t, :h, :r)"
            ), [{"k": "lear", "t": "y0", "h": h, "r": 5.0 + h} for h in range(24)])
        ref = pred.load_reference_rmse(engine, "lear", "y0")
        self.assertEqual(ref, [5.0 + h for h in range(24)])

    def test_incomplete_row_is_none(self):
        engine = sa.create_engine("sqlite:///:memory:")
        with engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE rmse_reference (model_kind TEXT, target TEXT, hour_utc INTEGER, rmse REAL)"
            ))
            conn.execute(sa.text(
                "INSERT INTO rmse_reference (model_kind, target, hour_utc, rmse) VALUES ('lstm', 'y0', 0, 1.0)"
            ))
        self.assertIsNone(pred.load_reference_rmse(engine, "lstm", "y0"))


if __name__ == "__main__":
    unittest.main()
