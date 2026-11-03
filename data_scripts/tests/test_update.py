"""Offline tests for update.py's continuous-learning orchestration. No
network, no real Postgres, no real LSTM training (that's exercised by
model/train_lstm.py's own CLI directly - see train_lstm.py's module
docstring's --warm-start example - and by the direct subprocess smoke-test
below, which uses tiny parameters). LEAR is fast enough to exercise for
real, end to end, including through update.run().

Run from the data_scripts/ folder:
    pytest -v tests/test_update.py
"""

import unittest
from unittest.mock import Mock, patch
import subprocess
from types import SimpleNamespace
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pandas as pd
import sqlalchemy as sa

import model_store as store
import predictions as pred
import update

LOCATION = pred.DEFAULT_LOCATION
TODAY = datetime.now(timezone.utc).date()  # matches update.run()'s own real_yesterday computation


class FirstPromotionTests(unittest.TestCase):
    def test_initial_candidate_must_have_a_finite_validation_score(self):
        artifact = {"train_end": "2026-01-31", "windows": (14,), "exog_cols": []}
        lear = SimpleNamespace(save_artifact=Mock(), LEARForecaster=Mock())
        for mae in (float("nan"), float("inf"), 2.0):
            with self.subTest(mae=mae), \
                 patch.object(store, "load_active_artifact", return_value=None), \
                 patch.object(update, "tune_lear_candidate", return_value=artifact), \
                 patch.object(pred, "_lear_module", return_value=lear), \
                 patch.object(update, "settled_actuals"), \
                 patch.object(update, "build_lear_history"), \
                 patch.object(update, "score_lear", return_value=mae), \
                 patch.object(store, "save_model_artifact") as save, \
                 patch.object(store, "prune_old_artifacts") as prune:
                promoted, _ = update.tune_and_maybe_promote(
                    object(), "lear", "y0", date(2026, 2, 1), date(2026, 2, 5), dry_run=False)
                self.assertEqual(bool(promoted), bool(np.isfinite(mae)))
                if np.isfinite(mae):
                    save.assert_called_once()
                else:
                    save.assert_not_called()
                    prune.assert_not_called()

    def test_weekly_job_fails_when_reference_refresh_fails(self):
        with patch("sys.argv", ["update.py"]), patch.object(update.ing, "make_engine"), \
             patch.object(update, "run"), patch.object(update.subprocess, "run",
                 side_effect=subprocess.CalledProcessError(1, "recompute_reference_rmse.py")) as refresh:
            with self.assertRaises(SystemExit) as stopped:
                update.main()
            self.assertEqual(stopped.exception.code, 1)
            self.assertTrue(refresh.call_args.kwargs["check"])


def make_raw_tables(engine: sa.Engine, start: pd.Timestamp, end: pd.Timestamp, seed: int = 0) -> None:
    """electrical_price/wind/solar/fuel_price covering [start, end] - enough
    for both build_lstm_training_csv (needs 168h lookback) and
    build_lear_history (needs its windows' lookback - tests below use small
    windows so this doesn't have to span years)."""
    rng = np.random.default_rng(seed)
    hours = pd.date_range(start, end, freq="h")
    price = pd.DataFrame({"interval_start_utc": [t.isoformat() for t in hours], "location": LOCATION,
                          "spp_usd_mwh": 30 + 10 * np.sin(np.arange(len(hours)) / 24) + rng.normal(0, 2, len(hours))})
    weather = pd.DataFrame({"location_id": 0, "time": [t.isoformat() for t in hours]})
    wind = weather.assign(wind_speed_80m=25.0, wind_speed_120m=30 + rng.random(len(hours)), temperature_120m=12.0)
    solar = weather.assign(shortwave_radiation=400 * rng.random(len(hours)), temperature_2m=18.0)
    days = pd.date_range(start.normalize() - pd.Timedelta(days=10), end, freq="D")
    gas = pd.DataFrame({"period": [d.isoformat() for d in days], "henry_hub_price_usd_mmbtu": 3.0})
    with engine.begin() as conn:
        for table, df in [("electrical_price", price), ("wind", wind), ("solar", solar), ("fuel_price", gas)]:
            df.to_sql(table, conn, index=False)


def add_support_tables(engine: sa.Engine) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text(
            "CREATE TABLE model_artifacts ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " model_kind TEXT NOT NULL, target TEXT NOT NULL, model_version TEXT NOT NULL,"
            " format TEXT NOT NULL, blob BLOB NOT NULL, trained_through TEXT NOT NULL,"
            " valid_from_date TEXT NOT NULL, valid_to_date TEXT NOT NULL,"
            " metrics TEXT NOT NULL DEFAULT '{}', is_active BOOLEAN NOT NULL DEFAULT 0,"
            " created_at TEXT NOT NULL)"
        ))
        conn.execute(sa.text(
            "CREATE UNIQUE INDEX model_artifacts_one_active ON model_artifacts (model_kind, target) "
            "WHERE is_active"
        ))
        conn.execute(sa.text(
            "CREATE TABLE validation_state (target TEXT PRIMARY KEY, validation_start_date TEXT NOT NULL, "
            "original_start_date TEXT NOT NULL, last_checked_date TEXT, updated_at TEXT)"
        ))
        conn.execute(sa.text(
            "CREATE TABLE predicted_price (interval_start_utc TEXT, location TEXT, predicted_price REAL, "
            "model_version TEXT, generated_at TEXT, confidence_score REAL, "
            "PRIMARY KEY (interval_start_utc, location, model_version, generated_at))"
        ))


def seed_validation_state(engine: sa.Engine, target: str, validation_start: date) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text(
            "INSERT INTO validation_state (target, validation_start_date, original_start_date) "
            "VALUES (:target, :start, :start)"
        ), {"target": target, "start": validation_start.isoformat()})


class ValidationStateTests(unittest.TestCase):
    def test_round_trips_through_read_advance_and_mark_checked(self):
        engine = sa.create_engine("sqlite:///:memory:")
        add_support_tables(engine)
        seed_validation_state(engine, "y0", date(2026, 2, 1))

        state = update.read_validation_state(engine, "y0")
        self.assertEqual(state.validation_start_date, date(2026, 2, 1))
        self.assertIsNone(state.last_checked_date)

        update.mark_checked(engine, "y0", date(2026, 5, 1))
        self.assertEqual(update.read_validation_state(engine, "y0").last_checked_date, date(2026, 5, 1))

        update.advance_validation_start(engine, "y0", date(2026, 2, 2), date(2026, 5, 2))
        state = update.read_validation_state(engine, "y0")
        self.assertEqual(state.validation_start_date, date(2026, 2, 2))
        self.assertEqual(state.last_checked_date, date(2026, 5, 2))

    def test_missing_row_raises_a_clear_error(self):
        engine = sa.create_engine("sqlite:///:memory:")
        add_support_tables(engine)
        with self.assertRaises(RuntimeError):
            update.read_validation_state(engine, "y1")


class BuildLstmTrainingCsvTests(unittest.TestCase):
    def test_csv_has_train_csv_s_columns_and_requested_range(self):
        engine = sa.create_engine("sqlite:///:memory:")
        make_raw_tables(engine, pd.Timestamp("2026-01-01"), pd.Timestamp("2026-04-10"))
        start, end = pd.Timestamp("2026-02-01"), pd.Timestamp("2026-04-01")

        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "train.csv"
            update.build_lstm_training_csv(engine, start, end + pd.Timedelta(hours=23), out)
            df = pd.read_csv(out, parse_dates=["time"])

        expected_cols = {"time", "lz_north_price", "lz_north_price_winsorized", "lag_72h", "lag_96h",
                         "roll_mean_24h", "roll_std_24h", "roll_mean_168h", "roll_std_168h", "hour",
                         "day_of_week", "month", "is_us_holiday", "henry_hub_price_usd_mmbtu_lag2d",
                         "henry_hub_price_usd_mmbtu_lag3d", "wind_power_output_proxy_total",
                         "solar_power_output_proxy_total"}
        self.assertEqual(set(df.columns), expected_cols)
        self.assertEqual(df["time"].min(), start)
        self.assertEqual(df["time"].max(), end + pd.Timedelta(hours=23))
        self.assertTrue(df["lz_north_price"].notna().all())


class BuildLearHistoryTests(unittest.TestCase):
    def test_covers_the_lookback_and_requested_range(self):
        engine = sa.create_engine("sqlite:///:memory:")
        make_raw_tables(engine, pd.Timestamp("2026-01-01"), pd.Timestamp("2026-04-10"))
        lear = pred._lear_module()
        start, end = pd.Timestamp("2026-03-01"), pd.Timestamp("2026-03-10") + pd.Timedelta(hours=23)

        frame = update.build_lear_history(engine, start, end, windows=(14, 28), exog_cols=list(lear.EXOG_COLS))

        self.assertLessEqual(frame.index.min(), start - pd.Timedelta(days=28 + 7))
        self.assertEqual(frame.index.max(), end)
        for col in [lear.RAW_PRICE_COL, *lear.EXOG_COLS, "is_us_holiday"]:
            self.assertIn(col, frame.columns)


class EndToEndLearTests(unittest.TestCase):
    """Real LEAR fitting throughout - fast enough (small windows) to run as
    part of the normal suite, and the only part of update.py that doesn't
    need a subprocess, so it's the one exercised fully end to end here."""

    def setUp(self):
        self.lear = pred._lear_module()
        self._real_windows = self.lear.WINDOWS
        # Each window must be >= lear_model.MIN_TRAIN_DAYS (28) - a window
        # shorter than that can never have enough rows to fit at all.
        self.lear.WINDOWS = (28, 56)  # small (vs. the real (56, 84, 1092, 1456)), for test speed - see tearDown
        update.WINDOW_DAYS = 60

    def tearDown(self):
        self.lear.WINDOWS = self._real_windows

    def _engine(self, validation_start: date, history_days: int = 150):
        engine = sa.create_engine("sqlite:///:memory:")
        add_support_tables(engine)
        start = pd.Timestamp(validation_start) - pd.Timedelta(days=history_days)
        # Headroom past validation_start for scoring's target+lead_hours reach
        # (tests with an explicit score_through need only a little; the
        # run()-based tests compute score_through from the real wall clock,
        # so the data has to reach real "yesterday" regardless of how far
        # that is past validation_start).
        end = max(pd.Timestamp(validation_start) + pd.Timedelta(days=40), pd.Timestamp(TODAY))
        make_raw_tables(engine, start, end)
        seed_validation_state(engine, "y0", validation_start)
        return engine

    def test_first_run_trains_from_scratch_and_always_promotes(self):
        validation_start = date(2026, 1, 1)
        engine = self._engine(validation_start)
        score_through = validation_start + timedelta(days=5)

        promoted, mae = update.tune_and_maybe_promote(engine, "lear", "y0", validation_start, score_through,
                                                       dry_run=False)
        self.assertTrue(promoted)  # nothing active yet - always promotes on a first run
        self.assertTrue(np.isfinite(mae))

        row = store.load_active_artifact(engine, "lear", "y0")
        self.assertIsNotNone(row)
        self.assertEqual(row["metrics"]["mae"], mae)
        self.assertEqual(pd.Timestamp(row["valid_from_date"]).date(), validation_start)
        self.assertEqual(pd.Timestamp(row["valid_to_date"]).date(), score_through)

    def test_dry_run_scores_but_never_writes(self):
        validation_start = date(2026, 1, 1)
        engine = self._engine(validation_start)
        update.tune_and_maybe_promote(engine, "lear", "y0", validation_start, validation_start + timedelta(days=5),
                                      dry_run=True)
        self.assertIsNone(store.load_active_artifact(engine, "lear", "y0"))

    def test_fold_in_returns_false_with_no_active_artifact(self):
        validation_start = date(2026, 1, 1)
        engine = self._engine(validation_start)
        self.assertFalse(update.fold_in_is_safe(engine, "lear", "y0", validation_start,
                                                validation_start + timedelta(days=5)))

    def test_full_run_promotes_and_leaves_cursor_in_a_defined_state(self):
        """run() with kinds=["lear"] only - set(kinds) != set(KINDS), so by
        design (module docstring: both kinds must agree) the cursor must NOT
        advance even if LEAR alone would call it safe. Unlike the other
        tests in this class, run() computes its own score_through from the
        REAL wall clock (update.run's module docstring), so - unlike those -
        this one anchors validation_start relative to TODAY, not a fixed
        2026 date, so the synthetic data actually reaches real "yesterday"."""
        validation_start = TODAY - timedelta(days=60)
        engine = self._engine(validation_start, history_days=150)
        update.run(engine, targets=["y0"], kinds=["lear"], dry_run=False)

        self.assertIsNotNone(store.load_active_artifact(engine, "lear", "y0"))
        state = update.read_validation_state(engine, "y0")
        self.assertEqual(state.validation_start_date, validation_start)  # unchanged - only one kind ran
        self.assertIsNotNone(state.last_checked_date)

    def test_second_call_the_same_day_is_a_no_op(self):
        validation_start = TODAY - timedelta(days=60)
        engine = self._engine(validation_start, history_days=150)
        update.run(engine, targets=["y0"], kinds=["lear"], dry_run=False)
        first_version = store.load_active_artifact(engine, "lear", "y0")["model_version"]

        update.run(engine, targets=["y0"], kinds=["lear"], dry_run=False)  # same day - last_checked_date matches
        self.assertEqual(store.load_active_artifact(engine, "lear", "y0")["model_version"], first_version)


class EndToEndLstmTests(unittest.TestCase):
    """Real train_lstm.py subprocess calls, with tiny parameters (small
    window, 1 epoch) so this stays fast enough for the normal suite - these
    exercise tune_lstm_candidate's actual command-line wiring (--warm-start,
    --window-days, --refresh-preprocessing), not just the reasoning behind
    it. Calls tune_lstm_candidate directly rather than going through
    tune_and_maybe_promote/run(), since scoring an LSTM many times over a
    validation window has no speed concerns of its own to exercise here -
    this class is specifically about the tuning subprocess."""

    def setUp(self):
        update.WINDOW_DAYS = 20
        update.LSTM_MAX_FINE_TUNE_EPOCHS = 1

    def _engine_and_bounds(self):
        engine = sa.create_engine("sqlite:///:memory:")
        train_end = date(2026, 3, 1)
        start = pd.Timestamp(train_end) - timedelta(days=update.WINDOW_DAYS + 10)  # +168h/24 lookback headroom
        end = pd.Timestamp(train_end) + timedelta(days=5)
        make_raw_tables(engine, start, end)
        return engine, train_end

    def test_trains_from_scratch_with_no_active_row(self):
        engine, train_end = self._engine_and_bounds()
        artifact = update.tune_lstm_candidate(engine, "y0", train_end, active_row=None)
        self.assertEqual(artifact["target"], "y0")
        self.assertEqual(artifact["lead_hours"], pred.LEAD_HOURS["y0"])
        self.assertIsNone(artifact["warm_started_from"])
        self.assertEqual(artifact["preprocessing_refreshed_at"], artifact["train_end"])

    def test_warm_start_freezes_preprocessing_by_default(self):
        engine, train_end = self._engine_and_bounds()
        first = update.tune_lstm_candidate(engine, "y0", train_end, active_row=None)
        buf_format = pred._lstm_module()
        import io
        buf = io.BytesIO()
        buf_format.save_artifact(buf, first)
        active_row = {"blob": buf.getvalue(), "format": "torch"}

        second_train_end = train_end + timedelta(days=3)
        second = update.tune_lstm_candidate(engine, "y0", second_train_end, active_row)
        self.assertIsNotNone(second["warm_started_from"])
        self.assertEqual(second["price_clip"], first["price_clip"])  # frozen (point 7), not re-derived
        self.assertEqual(second["preprocessing_refreshed_at"], first["preprocessing_refreshed_at"])  # carried forward
        self.assertNotEqual(second["train_end"], first["train_end"])  # the weights-relevant part did move forward


if __name__ == "__main__":
    unittest.main()
