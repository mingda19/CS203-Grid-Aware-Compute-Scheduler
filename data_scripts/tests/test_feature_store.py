"""Offline tests for feature_store.py (no network, no real Postgres).

The contract under test: the model_features_hourly cache is a pure optimisation. Whenever it
serves a range it returns EXACTLY the frame the raw path computes, and whenever it can't prove
that (no table, a gap, a stale tail after a failed refresh, a partial backfill) it steps aside so
load_exogenous() falls back to the raw tables.

Run from the data_scripts/ folder:
    pytest -v tests/test_feature_store.py
"""

import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

import numpy as np
import pandas as pd
import sqlalchemy as sa

import feature_store as fs
import ingestion as ing
import predictions as pred

NOW = datetime(2026, 3, 5, 21, 0, tzinfo=timezone.utc)  # refresh window: ..2026-03-08 23:00
DATA_START = datetime(2026, 2, 1)
DATA_END = datetime(2026, 3, 25, 23)


def make_engine() -> sa.Engine:
    return sa.create_engine("sqlite:///:memory:")


def iso(ts) -> str:
    return pd.Timestamp(ts).isoformat()


def create_tables(engine: sa.Engine, with_cache: bool = True) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE fuel_price (period TEXT, henry_hub_price_usd_mmbtu REAL)"))
        conn.execute(sa.text("CREATE TABLE wind (location_id INTEGER, time TEXT, wind_speed_80m REAL, "
                             "wind_speed_120m REAL, temperature_120m REAL)"))
        conn.execute(sa.text("CREATE TABLE solar (location_id INTEGER, time TEXT, "
                             "shortwave_radiation REAL, temperature_2m REAL)"))
        if with_cache:
            conn.execute(sa.text(
                f"CREATE TABLE {fs.TABLE} (interval_start_utc TIMESTAMP PRIMARY KEY, "
                "henry_hub_price_usd_mmbtu_lag2d REAL, henry_hub_price_usd_mmbtu_lag3d REAL, "
                "wind_power_output_proxy_total REAL, solar_power_output_proxy_total REAL)"))


def seed_raw(engine: sa.Engine, start=DATA_START, end=DATA_END, seed: int = 7) -> None:
    """Hourly weather for 2 wind + 2 solar sites over start..end, daily gas Mon-Fri."""
    rng = np.random.default_rng(seed)
    hours = pd.date_range(start, end, freq="h")
    wind = [{"loc": loc, "t": iso(t), "s80": float(rng.uniform(5, 40)), "s120": float(rng.uniform(5, 50)),
             "temp": float(rng.uniform(5, 30))} for t in hours for loc in (1, 2)]
    solar = [{"loc": loc, "t": iso(t), "rad": float(rng.uniform(0, 900)), "temp": float(rng.uniform(5, 35))}
             for t in hours for loc in (1, 2)]
    gas = [{"p": iso(d), "v": float(3 + rng.uniform(0, 1))}
           for d in pd.date_range(pd.Timestamp(start).normalize(), end, freq="D") if d.dayofweek < 5]
    with engine.begin() as conn:
        conn.execute(sa.text("INSERT INTO wind VALUES (:loc, :t, :s80, :s120, :temp)"), wind)
        conn.execute(sa.text("INSERT INTO solar VALUES (:loc, :t, :rad, :temp)"), solar)
        conn.execute(sa.text("INSERT INTO fuel_price VALUES (:p, :v)"), gas)


def fresh_engine(with_cache: bool = True) -> sa.Engine:
    engine = make_engine()
    create_tables(engine, with_cache)
    seed_raw(engine)
    return engine


def assert_same(testcase: unittest.TestCase, cached: pd.DataFrame, raw: pd.DataFrame) -> None:
    pd.testing.assert_frame_equal(cached, raw, check_exact=True)


class CacheMatchesRawTests(unittest.TestCase):
    def setUp(self):
        fs._warned.clear()
        self.engine = fresh_engine()
        self.rows = fs.refresh(self.engine, now=NOW)  # empty cache -> bootstraps from the earliest raw data

    def test_bootstrap_covers_the_earliest_raw_data_through_the_refresh_window(self):
        self.assertEqual(self.rows, len(pd.date_range(DATA_START, "2026-03-08 23:00", freq="h")))
        self.assertEqual(fs._max_cached(self.engine), pd.Timestamp("2026-03-08 23:00"))

    def test_cached_frame_is_identical_to_the_raw_frame(self):
        start, end = datetime(2026, 2, 1), datetime(2026, 3, 8, 23)
        cached = fs.read_cached(self.engine, start, end)
        self.assertIsNotNone(cached)
        assert_same(self, cached, pred.load_exogenous_raw(self.engine, start, end))

    def test_a_sub_range_is_identical_too(self):
        start, end = datetime(2026, 2, 20, 5), datetime(2026, 3, 2, 17)
        assert_same(self, fs.read_cached(self.engine, start, end), pred.load_exogenous_raw(self.engine, start, end))

    def test_load_exogenous_serves_from_the_cache_without_touching_the_raw_tables(self):
        with mock.patch.object(pred, "load_exogenous_raw", side_effect=AssertionError("raw path used")):
            out = pred.load_exogenous(self.engine, datetime(2026, 2, 10), datetime(2026, 3, 8, 23))
        self.assertEqual(len(out), len(pd.date_range("2026-02-10", "2026-03-08 23:00", freq="h")))

    def test_the_four_columns_the_models_consume_are_all_cached(self):
        self.assertEqual(fs.FEATURE_COLS, [
            "henry_hub_price_usd_mmbtu_lag2d", "henry_hub_price_usd_mmbtu_lag3d",
            "wind_power_output_proxy_total", "solar_power_output_proxy_total"])
        out = pred.load_exogenous(self.engine, datetime(2026, 3, 1), datetime(2026, 3, 1, 23))
        self.assertEqual(list(out.columns), ["time", *fs.FEATURE_COLS])
        self.assertTrue(out[fs.FEATURE_COLS].notna().all().all())

    def test_verify_reports_zero_differences(self):
        compared, differing = fs.verify(self.engine, datetime(2026, 2, 5), datetime(2026, 3, 5, 23))
        self.assertGreater(compared, 0)
        self.assertEqual(differing, 0)

    def test_verify_ignores_last_bit_summation_noise_but_not_real_differences(self):
        # Production finding: cached and recomputed totals differ at ~5e-15 relative because the raw
        # query returns the sites in no fixed order. That must not read as drift.
        ts = datetime(2026, 2, 12, 6)
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"UPDATE {fs.TABLE} SET wind_power_output_proxy_total = "
                                 "wind_power_output_proxy_total * (1 + 5e-15) WHERE interval_start_utc = :t"
                                 ).bindparams(sa.bindparam("t", type_=sa.DateTime())), {"t": ts})
        self.assertEqual(fs.verify(self.engine, datetime(2026, 2, 5), datetime(2026, 3, 5, 23))[1], 0)
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"UPDATE {fs.TABLE} SET wind_power_output_proxy_total = "
                                 "wind_power_output_proxy_total * 1.001 WHERE interval_start_utc = :t"
                                 ).bindparams(sa.bindparam("t", type_=sa.DateTime())), {"t": ts})
        self.assertEqual(fs.verify(self.engine, datetime(2026, 2, 5), datetime(2026, 3, 5, 23))[1], 1)

    def test_verify_catches_a_tampered_row(self):
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"UPDATE {fs.TABLE} SET wind_power_output_proxy_total = -1 "
                                 "WHERE interval_start_utc = :t").bindparams(
                sa.bindparam("t", type_=sa.DateTime())), {"t": datetime(2026, 2, 12, 6)})
        _, differing = fs.verify(self.engine, datetime(2026, 2, 5), datetime(2026, 3, 5, 23))
        self.assertEqual(differing, 1)


class FallsBackToRawTests(unittest.TestCase):
    """Every way the cache can't be trusted must yield None, and load_exogenous the raw frame."""

    def setUp(self):
        fs._warned.clear()
        self.engine = fresh_engine()
        fs.refresh(self.engine, now=NOW)
        self.start, self.end = datetime(2026, 2, 10), datetime(2026, 3, 8, 23)

    def assert_falls_back(self, start=None, end=None):
        start, end = start or self.start, end or self.end
        self.assertIsNone(fs.read_cached(self.engine, start, end))
        assert_same(self, pred.load_exogenous(self.engine, start, end),
                    pred.load_exogenous_raw(self.engine, start, end))

    def test_no_cache_table(self):
        engine = fresh_engine(with_cache=False)
        self.assertIsNone(fs.read_cached(engine, self.start, self.end))
        assert_same(self, pred.load_exogenous(engine, self.start, self.end),
                    pred.load_exogenous_raw(engine, self.start, self.end))

    def test_a_missing_hour_in_the_middle(self):
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"DELETE FROM {fs.TABLE} WHERE interval_start_utc = :t").bindparams(
                sa.bindparam("t", type_=sa.DateTime())), {"t": datetime(2026, 2, 20, 12)})
        self.assert_falls_back()

    def test_range_running_past_the_newest_cached_hour(self):
        # e.g. today's refresh didn't run: yesterday's window ends a day short of today's request.
        self.assert_falls_back(end=datetime(2026, 3, 9, 23))

    def test_cache_that_only_partly_backfilled_history(self):
        with self.engine.begin() as conn:  # raw data begins 2026-02-01, cache now begins 2026-02-06
            conn.execute(sa.text(f"DELETE FROM {fs.TABLE} WHERE interval_start_utc < :t").bindparams(
                sa.bindparam("t", type_=sa.DateTime())), {"t": datetime(2026, 2, 6)})
        self.assert_falls_back(start=datetime(2026, 2, 1))

    def test_range_entirely_outside_the_cache(self):
        self.assert_falls_back(start=datetime(2026, 4, 1), end=datetime(2026, 4, 2, 23))


class RangeBeforeAllRawDataTests(unittest.TestCase):
    def test_hours_before_any_raw_data_are_nan_exactly_like_the_raw_path(self):
        # update.py's LEAR history starts ~4 years before the training window - before ingestion began.
        fs._warned.clear()
        engine = fresh_engine()
        fs.refresh(engine, now=NOW)
        start, end = datetime(2026, 1, 25), datetime(2026, 2, 8, 23)
        cached = fs.read_cached(engine, start, end)
        self.assertIsNotNone(cached)
        assert_same(self, cached, pred.load_exogenous_raw(engine, start, end))
        self.assertTrue(cached.loc[cached["time"] < "2026-02-01", fs.FEATURE_COLS].isna().all().all())


class RefreshTests(unittest.TestCase):
    def setUp(self):
        fs._warned.clear()
        self.engine = fresh_engine()
        fs.refresh(self.engine, now=NOW)

    def cached_value(self, col: str, ts: datetime) -> float:
        with self.engine.begin() as conn:
            return conn.execute(sa.text(f"SELECT {col} FROM {fs.TABLE} WHERE interval_start_utc = :t").bindparams(
                sa.bindparam("t", type_=sa.DateTime())), {"t": ts}).scalar()

    def revise_wind(self, since: str, until: str | None = None) -> None:
        clause = "time >= :a" + (" AND time < :b" if until else "")
        params = {"a": since, **({"b": until} if until else {})}
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"UPDATE wind SET wind_speed_120m = wind_speed_120m + 3 WHERE {clause}"), params)

    def test_refresh_is_idempotent(self):
        before = fs.read_cached(self.engine, datetime(2026, 2, 1), datetime(2026, 3, 8, 23))
        fs.refresh(self.engine, now=NOW)
        assert_same(self, fs.read_cached(self.engine, datetime(2026, 2, 1), datetime(2026, 3, 8, 23)), before)

    def test_revised_recent_weather_replaces_the_cached_forecast(self):
        recent = datetime(2026, 3, 7, 12)  # the y0/y1 target days: a forecast that gets revised
        old_value = self.cached_value("wind_power_output_proxy_total", recent)
        self.revise_wind("2026-03-04T00:00:00")
        fs.refresh(self.engine, now=NOW)
        self.assertNotEqual(self.cached_value("wind_power_output_proxy_total", recent), old_value)
        start, end = datetime(2026, 2, 1), datetime(2026, 3, 8, 23)
        assert_same(self, fs.read_cached(self.engine, start, end), pred.load_exogenous_raw(self.engine, start, end))

    def test_settled_history_outside_the_trailing_window_is_left_alone(self):
        old = datetime(2026, 2, 10, 12)  # well before today - REFRESH_LOOKBACK_DAYS
        old_value = self.cached_value("wind_power_output_proxy_total", old)
        self.revise_wind("2026-02-01T00:00:00", "2026-02-15T00:00:00")
        fs.refresh(self.engine, now=NOW)
        self.assertEqual(self.cached_value("wind_power_output_proxy_total", old), old_value)

    def test_since_refreshes_a_historical_backfill(self):
        old = datetime(2026, 2, 10, 12)
        old_value = self.cached_value("wind_power_output_proxy_total", old)
        self.revise_wind("2026-02-01T00:00:00", "2026-02-15T00:00:00")
        fs.refresh(self.engine, since=datetime(2026, 2, 1).date(), now=NOW)
        self.assertNotEqual(self.cached_value("wind_power_output_proxy_total", old), old_value)
        start, end = datetime(2026, 2, 1), datetime(2026, 3, 8, 23)
        assert_same(self, fs.read_cached(self.engine, start, end), pred.load_exogenous_raw(self.engine, start, end))

    def test_rebuild_repairs_a_corrupted_row(self):
        with self.engine.begin() as conn:
            conn.execute(sa.text(f"UPDATE {fs.TABLE} SET solar_power_output_proxy_total = 99999"))
        fs.rebuild(self.engine, now=NOW)
        start, end = datetime(2026, 2, 1), datetime(2026, 3, 8, 23)
        assert_same(self, fs.read_cached(self.engine, start, end), pred.load_exogenous_raw(self.engine, start, end))

    def test_a_cache_that_fell_days_behind_catches_up_without_a_gap(self):
        later = datetime(2026, 3, 20, 21, 0, tzinfo=timezone.utc)  # pipeline skipped ~2 weeks
        fs.refresh(self.engine, now=later)
        self.assertEqual(fs._max_cached(self.engine), pd.Timestamp("2026-03-23 23:00"))
        start, end = datetime(2026, 2, 1), datetime(2026, 3, 23, 23)
        assert_same(self, fs.read_cached(self.engine, start, end), pred.load_exogenous_raw(self.engine, start, end))

    def test_failed_refresh_removes_the_stale_tail_so_reads_fall_back(self):
        self.revise_wind("2026-03-04T00:00:00")  # raw moved on; a refresh that dies leaves the cache behind it
        with mock.patch.object(pred, "load_exogenous_raw", side_effect=RuntimeError("db hiccup")):
            with self.assertRaises(RuntimeError):
                fs.refresh(self.engine, now=NOW)
        # The tail is gone, so a range touching it is not served from stale values...
        tail = (datetime(2026, 3, 1), datetime(2026, 3, 8, 23))
        self.assertIsNone(fs.read_cached(self.engine, *tail))
        assert_same(self, pred.load_exogenous(self.engine, *tail), pred.load_exogenous_raw(self.engine, *tail))
        # ...while settled history older than the window is still served from the cache.
        settled = (datetime(2026, 2, 1), datetime(2026, 2, 10, 23))
        self.assertIsNotNone(fs.read_cached(self.engine, *settled))

    def test_missing_cache_table_is_skipped_not_an_error(self):
        engine = fresh_engine(with_cache=False)
        self.assertEqual(fs.refresh(engine, now=NOW), 0)

    def test_no_raw_data_at_all_is_skipped(self):
        engine = make_engine()
        create_tables(engine)
        self.assertEqual(fs.refresh(engine, now=NOW), 0)


class IngestionIntegrationTests(unittest.TestCase):
    def test_feature_refresh_is_the_last_step_and_its_failure_is_isolated(self):
        calls = []

        def step(name):
            return lambda *a, **k: calls.append(name)

        def boom(*a, **k):
            calls.append("features")
            raise RuntimeError("cache refresh blew up")

        result = ing.run_ingestion(make_engine(), weather_fn=step("weather"), eia_fn=step("eia"),
                                   dam_fn=step("dam"), features_fn=boom)
        self.assertEqual(calls, ["weather", "eia", "dam", "features"])
        self.assertTrue(result.weather_ok and result.eia_ok and result.dam_ok)
        self.assertFalse(result.features_ok)
        self.assertFalse(result.ok)  # -> ingestion.py exits 2 (incomplete), predictions still run

    def test_a_source_failing_does_not_stop_the_feature_refresh(self):
        ran = []

        def boom(*a, **k):
            raise RuntimeError("network down")

        result = ing.run_ingestion(make_engine(), weather_fn=boom, eia_fn=lambda e: None, dam_fn=lambda e: None,
                                   features_fn=lambda e: ran.append(True))
        self.assertEqual(ran, [True])
        self.assertFalse(result.weather_ok)
        self.assertTrue(result.features_ok)

    def test_default_feature_step_is_harmless_when_the_cache_table_does_not_exist_yet(self):
        # The state right after deploying this code, before the backend has booted and created the table.
        result = ing.run_ingestion(make_engine(), weather_fn=lambda e: None, eia_fn=lambda e: None,
                                   dam_fn=lambda e: None)
        self.assertTrue(result.ok)

    def test_default_feature_step_refreshes_the_cache(self):
        engine = fresh_engine()
        with mock.patch.object(fs, "_window_end", return_value=fs._window_end(NOW)):
            result = ing.run_ingestion(engine, weather_fn=lambda e: None, eia_fn=lambda e: None,
                                       dam_fn=lambda e: None)
        self.assertTrue(result.ok)
        self.assertEqual(fs._max_cached(engine), pd.Timestamp("2026-03-08 23:00"))


if __name__ == "__main__":
    unittest.main()
