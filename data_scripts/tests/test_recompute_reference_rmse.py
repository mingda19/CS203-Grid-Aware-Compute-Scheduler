"""Offline tests for recompute_reference_rmse.py. No network, no real Postgres.

Run from the data_scripts/ folder:
    pytest -v tests/test_recompute_reference_rmse.py
"""

import unittest
from datetime import datetime, timezone

import numpy as np
import pandas as pd
import sqlalchemy as sa

import ingestion as ing
import recompute_reference_rmse as rrr

LOCATION = "LZ_NORTH"


def make_db(days: int = 10) -> sa.Engine:
    engine = sa.create_engine("sqlite:///:memory:")
    hours = pd.date_range("2026-10-01", periods=days * 24, freq="h")
    rng = np.random.default_rng(0)
    actual = 30 + 10 * rng.random(len(hours))

    price = pd.DataFrame({"interval_start_utc": [t.isoformat() for t in hours], "location": LOCATION,
                          "spp_usd_mwh": actual})

    rows = []
    for kind, bias in (("lstm", 2.0), ("lear", 1.0), ("xgb", 5.0)):
        predicted = actual + bias + rng.normal(0, 0.5, len(hours))
        rows.append(pd.DataFrame({
            "interval_start_utc": [t.isoformat() for t in hours], "location": LOCATION,
            "predicted_price": predicted, "model_version": f"{kind}_y0_full",
            "generated_at": [t.isoformat() for t in hours], "confidence_score": 50.0,
        }))
    predicted_price = pd.concat(rows, ignore_index=True)

    with engine.begin() as conn:
        price.to_sql("electrical_price", conn, index=False)
        predicted_price.to_sql("predicted_price", conn, index=False)
        conn.execute(sa.text(
            "CREATE TABLE rmse_reference (model_kind TEXT, target TEXT, hour_utc INTEGER, rmse REAL, "
            "n_samples INTEGER, computed_through TEXT, updated_at TEXT, "
            "PRIMARY KEY (model_kind, target, hour_utc))"
        ))
    return engine


class LoadSettledResidualsTests(unittest.TestCase):
    def test_joins_prediction_to_actual_across_every_model_version(self):
        engine = make_db()
        df = rrr.load_settled_residuals(engine, LOCATION, datetime(2026, 1, 1, tzinfo=timezone.utc))
        self.assertEqual(set(df["model_version"]), {"lstm_y0_full", "lear_y0_full", "xgb_y0_full"})
        self.assertEqual(len(df), 10 * 24 * 3)

    def test_since_cutoff_excludes_older_rows(self):
        engine = make_db(days=10)
        cutoff = pd.Timestamp("2026-10-06", tz="UTC").to_pydatetime()
        df = rrr.load_settled_residuals(engine, LOCATION, cutoff)
        self.assertTrue((df["time"] >= pd.Timestamp("2026-10-06")).all())


class RecomputeRmseBySlotTests(unittest.TestCase):
    def test_groups_by_kind_target_and_hour_from_model_version(self):
        engine = make_db()
        residuals = rrr.load_settled_residuals(engine, LOCATION, datetime(2026, 1, 1, tzinfo=timezone.utc))
        rows = rrr.recompute_rmse_by_slot(residuals, min_samples=1)
        self.assertEqual(set(rows["model_kind"]), {"lstm", "lear", "xgb"})
        self.assertEqual(set(rows["target"]), {"y0"})
        self.assertEqual(sorted(rows["hour_utc"].unique()), list(range(24)))
        # lear's synthetic bias (1.0) is smaller than lstm's (2.0) and xgb's (5.0) - its RMSE should be lowest.
        mean_rmse = rows.groupby("model_kind")["rmse"].mean()
        self.assertLess(mean_rmse["lear"], mean_rmse["lstm"])
        self.assertLess(mean_rmse["lstm"], mean_rmse["xgb"])

    def test_below_min_samples_is_dropped(self):
        engine = make_db(days=1)  # 1 sample per hour per model
        residuals = rrr.load_settled_residuals(engine, LOCATION, datetime(2026, 1, 1, tzinfo=timezone.utc))
        rows = rrr.recompute_rmse_by_slot(residuals, min_samples=5)
        self.assertTrue(rows.empty)

    def test_unparseable_model_version_is_skipped_not_fatal(self):
        residuals = pd.DataFrame({
            "time": pd.date_range("2026-10-01", periods=6, freq="h"),
            "model_version": ["noUnderscoreAtAll"] * 6, "predicted": [1.0] * 6, "actual": [1.0] * 6,
        })
        rows = rrr.recompute_rmse_by_slot(residuals, min_samples=1)
        self.assertTrue(rows.empty)

    def test_empty_input_returns_empty_not_an_error(self):
        empty = pd.DataFrame(columns=["time", "model_version", "predicted", "actual"])
        rows = rrr.recompute_rmse_by_slot(empty)
        self.assertTrue(rows.empty)


class UpsertBehaviorTests(unittest.TestCase):
    def test_running_twice_upserts_in_place_not_append(self):
        engine = make_db()
        residuals = rrr.load_settled_residuals(engine, LOCATION, datetime(2026, 1, 1, tzinfo=timezone.utc))
        rows = rrr.recompute_rmse_by_slot(residuals, min_samples=1)

        ing.upsert_dataframe(engine, "rmse_reference", rows, ["model_kind", "target", "hour_utc"])
        with engine.begin() as conn:
            first_count = conn.execute(sa.text("SELECT COUNT(*) FROM rmse_reference")).scalar_one()

        ing.upsert_dataframe(engine, "rmse_reference", rows, ["model_kind", "target", "hour_utc"])
        with engine.begin() as conn:
            second_count = conn.execute(sa.text("SELECT COUNT(*) FROM rmse_reference")).scalar_one()

        self.assertEqual(first_count, 24 * 3)  # 24 hours x 3 kinds, one target (y0)
        self.assertEqual(second_count, first_count)  # same rows overwritten, not duplicated


if __name__ == "__main__":
    unittest.main()
