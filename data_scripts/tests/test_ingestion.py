"""Offline tests for ingestion.py (no API key, network, or real Postgres needed).

ingestion.py does not exist yet - this file defines its contract (TDD). DB-backed
tests use an in-memory SQLite engine via SQLAlchemy: the module is expected to
write portable SQL (SQLAlchemy Core "ON CONFLICT" upserts, supported by both the
sqlite and postgresql dialects), so the same code path is exercised here as
against the real Supabase Postgres - these tests are not mocking the DB away,
they're using a real (just small, in-memory) database.

Run from the data_scripts/ folder (pytest.ini lives there):
    pip install -r requirements-dev.txt
    pytest -v tests/test_ingestion.py
"""

import unittest
from datetime import date, datetime, timezone

import pandas as pd
import sqlalchemy as sa

import ingestion as ing


def make_engine() -> sa.Engine:
    return sa.create_engine("sqlite:///:memory:")


# --------------------------------------------------------------------------- #
# DATABASE_URL parsing
# --------------------------------------------------------------------------- #

class ParseDatabaseUrlTests(unittest.TestCase):
    def test_percent_encoded_password_is_decoded(self):
        url = ing._parse_database_url("postgresql://postgres.ref:p%40ss%23w%25rd%2A@db.example.com:6543/postgres")
        self.assertEqual(url.password, "p@ss#w%rd*")
        self.assertEqual(url.username, "postgres.ref")
        self.assertEqual((url.host, url.port, url.database), ("db.example.com", 6543, "postgres"))

    def test_plain_password_is_left_alone(self):
        url = ing._parse_database_url("postgres://postgres:plainPass123@db.example.com/postgres")
        self.assertEqual(url.password, "plainPass123")
        self.assertEqual(url.drivername, "postgresql+psycopg2")


# --------------------------------------------------------------------------- #
# Generic upsert helper - the one piece of new infrastructure every source uses
# --------------------------------------------------------------------------- #

class UpsertTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE prices (interval_start_utc TEXT, location TEXT, "
                "price REAL, PRIMARY KEY (interval_start_utc, location))"
            ))

    def read_all(self) -> pd.DataFrame:
        with self.engine.begin() as conn:
            return pd.read_sql("SELECT * FROM prices ORDER BY interval_start_utc, location", conn)

    def test_inserts_into_empty_table(self):
        df = pd.DataFrame({
            "interval_start_utc": ["2025-01-01T00:00:00", "2025-01-01T01:00:00"],
            "location": ["LZ_NORTH", "LZ_NORTH"],
            "price": [10.0, 12.0],
        })
        added, total = ing.upsert_dataframe(self.engine, "prices", df, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (2, 2))
        self.assertEqual(self.read_all()["price"].tolist(), [10.0, 12.0])

    def test_conflicting_key_updates_value_not_duplicates_row(self):
        first = pd.DataFrame({
            "interval_start_utc": ["2025-01-01T00:00:00"], "location": ["LZ_NORTH"], "price": [10.0],
        })
        ing.upsert_dataframe(self.engine, "prices", first, ["interval_start_utc", "location"])

        corrected = pd.DataFrame({
            "interval_start_utc": ["2025-01-01T00:00:00"], "location": ["LZ_NORTH"], "price": [11.5],
        })
        added, total = ing.upsert_dataframe(self.engine, "prices", corrected, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (0, 1))
        self.assertEqual(self.read_all()["price"].tolist(), [11.5])

    def test_rerun_with_identical_data_is_idempotent(self):
        df = pd.DataFrame({
            "interval_start_utc": ["2025-01-01T00:00:00"], "location": ["LZ_NORTH"], "price": [10.0],
        })
        ing.upsert_dataframe(self.engine, "prices", df, ["interval_start_utc", "location"])
        added, total = ing.upsert_dataframe(self.engine, "prices", df, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (0, 1))

    def test_different_locations_same_time_are_kept_separate(self):
        df = pd.DataFrame({
            "interval_start_utc": ["2025-01-01T00:00:00", "2025-01-01T00:00:00"],
            "location": ["LZ_NORTH", "LZ_SOUTH"],
            "price": [10.0, 12.0],
        })
        added, total = ing.upsert_dataframe(self.engine, "prices", df, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (2, 2))

    def test_empty_dataframe_is_a_no_op(self):
        empty = pd.DataFrame(columns=["interval_start_utc", "location", "price"])
        added, total = ing.upsert_dataframe(self.engine, "prices", empty, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (0, 0))

    def test_a_dataframe_spanning_several_chunks_upserts_completely(self):
        # A one-shot historical backfill can hand this tens of thousands of
        # rows in one call - a single INSERT that size reliably drops the
        # connection against Supabase's pooler (confirmed directly running a
        # real --since backfill), which is why upsert_dataframe chunks at
        # all. 3.5 chunks here, well past the boundary, on an otherwise
        # ordinary small in-memory DB - this pins the chunking loop's own
        # correctness (every row present, upserts still dedupe across a
        # chunk boundary), not the connection-size issue chunking exists for.
        n = int(ing.UPSERT_CHUNK_ROWS * 3.5)
        times = pd.date_range("2025-01-01", periods=n, freq="h")
        df = pd.DataFrame({"interval_start_utc": [t.isoformat() for t in times],
                           "location": "LZ_NORTH", "price": range(n)})
        added, total = ing.upsert_dataframe(self.engine, "prices", df, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (n, n))
        self.assertEqual(len(self.read_all()), n)

        # Rerun with the last chunk's worth of rows corrected - exercises an
        # update (not insert) landing inside a specific chunk.
        corrected = df.tail(ing.UPSERT_CHUNK_ROWS // 2).assign(price=lambda d: d["price"] + 1000)
        added, total = ing.upsert_dataframe(self.engine, "prices", corrected, ["interval_start_utc", "location"])
        self.assertEqual((added, total), (0, n))
        self.assertEqual(self.read_all()["price"].max(), n - 1 + 1000)


# --------------------------------------------------------------------------- #
# Location lookup by description - the `location` table (seeded independently,
# with its own coordinates) is the source of truth for which sites each
# weather dataset should fetch, tagged by description ("load", "wind", ...)
# --------------------------------------------------------------------------- #

class LocationByDescriptionTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE location (location_id INTEGER PRIMARY KEY, "
                "latitude REAL, longitude REAL, description TEXT)"
            ))
            conn.execute(sa.text(
                "INSERT INTO location VALUES (5, 32.78, -96.80, 'wind'), (9, 33.26, -98.21, 'wind'), "
                "(12, 30.0, -97.0, 'solar')"
            ))

    def test_returns_only_rows_matching_the_description_ordered_by_id(self):
        out = ing.fetch_locations_by_description(self.engine, "wind")
        self.assertEqual(out["location_id"].tolist(), [5, 9])
        self.assertEqual(out["latitude"].tolist(), [32.78, 33.26])

    def test_unseeded_description_raises_clear_error(self):
        with self.assertRaisesRegex(ing.LocationNotSeededError, "datacenters"):
            ing.fetch_locations_by_description(self.engine, "datacenters")


# --------------------------------------------------------------------------- #
# Weather (Open-Meteo) - fetch/normalize, keyed by the location_id already
# known from fetch_locations_by_description (no resolution step needed)
# --------------------------------------------------------------------------- #

class FakeOpenMeteoHourly:
    def __init__(self, start_ts, interval_s, n_steps, values):
        self._start, self._interval, self._n, self._values = start_ts, interval_s, n_steps, values

    def Time(self): return self._start
    def TimeEnd(self): return self._start + self._interval * self._n
    def Interval(self): return self._interval
    def Variables(self, i): return self._values[i]


class FakeVar:
    def __init__(self, values): self._values = values
    def ValuesAsNumpy(self): return self._values


class FakeOpenMeteoResponse:
    def __init__(self, latitude, longitude, hourly):
        self._lat, self._lon, self._hourly = latitude, longitude, hourly

    def Latitude(self): return self._lat
    def Longitude(self): return self._lon
    def Hourly(self): return self._hourly


class FakeOpenMeteoClient:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def weather_api(self, url, params):
        self.calls.append(params)
        return self.responses


class WeatherFetchTests(unittest.TestCase):
    def test_fetch_weather_tags_rows_with_the_known_location_id(self):
        import numpy as np
        hourly = FakeOpenMeteoHourly(1735689600, 3600, 2, [FakeVar(np.array([9.4, 7.8]))])
        client = FakeOpenMeteoClient([FakeOpenMeteoResponse(32.78, -96.80, hourly)])

        dataset = ing.WeatherDataset(
            name="load_demand", table="load_weather", description="load", hourly_vars=["temperature_2m"],
        )
        locations = pd.DataFrame({"location_id": [5], "latitude": [32.78], "longitude": [-96.80]})
        out = ing.fetch_weather(client, dataset, locations, "2025-01-01", "2025-01-02")

        self.assertEqual(list(out.columns), ["location_id", "time", "temperature_2m"])
        self.assertEqual(len(out), 2)
        self.assertEqual(out["location_id"].iloc[0], 5)
        self.assertEqual(client.calls[0]["latitude"], [32.78])
        self.assertEqual(out["temperature_2m"].tolist(), [9.4, 7.8])


class FetchWeatherRetryTests(unittest.TestCase):
    """_fetch_weather_chunk_with_retries exists because Open-Meteo's
    historical-forecast endpoint intermittently times out server-side mid-
    stream (OpenMeteoRequestsError, no HTTP status code - confirmed directly
    that this doesn't trigger make_openmeteo_client's own urllib3 Retry at
    all) - worth its own test since a real retry was only ever observed via
    a live, flaky network call, never deterministically. time.sleep is
    monkeypatched so this doesn't actually wait out the backoff."""

    def setUp(self):
        self._real_sleep = ing.time.sleep
        self.sleeps = []
        ing.time.sleep = lambda s: self.sleeps.append(s)

    def tearDown(self):
        ing.time.sleep = self._real_sleep

    def test_succeeds_after_transient_failures(self):
        from openmeteo_requests.Client import OpenMeteoRequestsError
        dataset = ing.WeatherDataset(name="x", table="x", description="x", hourly_vars=[])
        calls = []

        def flaky(client, dataset, locations, start_date, end_date):
            calls.append((start_date, end_date))
            if len(calls) < 3:
                raise OpenMeteoRequestsError("timeoutReached")
            return pd.DataFrame({"ok": [1]})

        real_fetch = ing.fetch_weather
        ing.fetch_weather = flaky
        try:
            out = ing._fetch_weather_chunk_with_retries(None, dataset, None, "2022-01-01", "2022-02-01")
        finally:
            ing.fetch_weather = real_fetch
        self.assertEqual(len(calls), 3)
        self.assertEqual(out["ok"].iloc[0], 1)
        self.assertEqual(len(self.sleeps), 2)  # 2 retries before the 3rd (successful) attempt
        self.assertEqual(self.sleeps, [5.0, 10.0])  # WEATHER_FETCH_RETRY_BACKOFF_S doubling

    def test_raises_after_exhausting_retries(self):
        from openmeteo_requests.Client import OpenMeteoRequestsError
        dataset = ing.WeatherDataset(name="x", table="x", description="x", hourly_vars=[])

        def always_fails(client, dataset, locations, start_date, end_date):
            raise OpenMeteoRequestsError("timeoutReached")

        real_fetch = ing.fetch_weather
        ing.fetch_weather = always_fails
        try:
            with self.assertRaises(OpenMeteoRequestsError):
                ing._fetch_weather_chunk_with_retries(None, dataset, None, "2022-01-01", "2022-02-01")
        finally:
            ing.fetch_weather = real_fetch
        self.assertEqual(len(self.sleeps), ing.WEATHER_FETCH_RETRIES - 1)


class UpdateWeatherChunkingTests(unittest.TestCase):
    """update_weather previously made one Open-Meteo request for the whole
    start_date..end_date range - confirmed directly that a ~4.6-year single
    request times out server-side, where the same range chunked at
    WEATHER_FETCH_CHUNK_DAYS succeeds. No network: fetch_locations_by_description
    and fetch_weather are monkeypatched to capture the date range each chunk
    actually requests, against a real (in-memory) target table so the upsert
    side of update_weather still runs for real."""

    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE weather_test (location_id INTEGER, time TEXT, temperature_2m REAL, "
                "PRIMARY KEY (location_id, time))"
            ))
        self.dataset = ing.WeatherDataset(
            name="x", table="weather_test", description="x", hourly_vars=["temperature_2m"],
        )
        self.calls = []
        self._real_locations, self._real_fetch = ing.fetch_locations_by_description, ing.fetch_weather
        ing.fetch_locations_by_description = lambda engine, description: pd.DataFrame(
            {"location_id": [1], "latitude": [0.0], "longitude": [0.0]})

        def fake_fetch_weather(client, dataset, locations, start_date, end_date):
            self.calls.append((start_date, end_date))
            hours = pd.date_range(start_date, pd.Timestamp(end_date) + pd.Timedelta(days=1), freq="h",
                                  inclusive="left")
            return pd.DataFrame({"location_id": 1, "time": hours, "temperature_2m": 1.0})

        ing.fetch_weather = fake_fetch_weather

    def tearDown(self):
        ing.fetch_locations_by_description, ing.fetch_weather = self._real_locations, self._real_fetch

    def test_splits_a_long_range_at_the_chunk_boundary(self):
        ing.update_weather(self.engine, client=None, dataset=self.dataset,
                           start_date="2022-01-29", end_date="2026-11-05")
        starts = [s for s, _ in self.calls]
        ends = [e for _, e in self.calls]
        self.assertEqual(starts[0], "2022-01-29")
        self.assertEqual(ends[-1], "2026-11-05")
        # Every chunk but the last is exactly WEATHER_FETCH_CHUNK_DAYS long, contiguous, no gaps/overlaps.
        for i in range(len(self.calls) - 1):
            self.assertEqual(pd.Timestamp(ends[i]) - pd.Timestamp(starts[i]),
                             pd.Timedelta(days=ing.WEATHER_FETCH_CHUNK_DAYS - 1))
            self.assertEqual(pd.Timestamp(starts[i + 1]), pd.Timestamp(ends[i]) + pd.Timedelta(days=1))
        self.assertGreater(len(self.calls), 1)

    def test_a_short_range_is_a_single_chunk(self):
        ing.update_weather(self.engine, client=None, dataset=self.dataset,
                           start_date="2026-11-01", end_date="2026-11-05")
        self.assertEqual(self.calls, [("2026-11-01", "2026-11-05")])

    def test_rows_from_every_chunk_land_in_the_table(self):
        added, total = ing.update_weather(self.engine, client=None, dataset=self.dataset,
                                          start_date="2022-01-29", end_date="2022-06-01")
        expected_hours = len(pd.date_range("2022-01-29", pd.Timestamp("2022-06-01") + pd.Timedelta(days=1),
                                           freq="h", inclusive="left"))
        self.assertEqual((added, total), (expected_hours, expected_hours))
        with self.engine.begin() as conn:
            count = conn.execute(sa.text("SELECT COUNT(*) FROM weather_test")).scalar_one()
        self.assertEqual(count, expected_hours)


class UpdateAllWeatherSinceTests(unittest.TestCase):
    """update_all_weather(engine, since=...) previously had no `since`
    parameter at all - main() had nothing to pass it even if it had tried to,
    so --since silently did nothing for weather while still correctly
    backfilling EIA/DAM (both of which do thread `since` through). No network:
    make_openmeteo_client and update_weather are monkeypatched to capture the
    date range actually requested, without calling Open-Meteo or touching a DB."""

    def setUp(self):
        self.calls = []
        self._real_client, self._real_update = ing.make_openmeteo_client, ing.update_weather
        ing.make_openmeteo_client = lambda: object()
        ing.update_weather = lambda engine, client, dataset, start_date, end_date: (
            self.calls.append((dataset.name, start_date, end_date)), (0, 0))[1]

    def tearDown(self):
        ing.make_openmeteo_client, ing.update_weather = self._real_client, self._real_update

    def test_since_extends_the_start_date_back(self):
        ing.update_all_weather(None, reference_date=date(2026, 11, 2), since=date(2026, 2, 1))
        starts = {start for _, start, _ in self.calls}
        ends = {end for _, _, end in self.calls}
        self.assertEqual(starts, {"2026-02-01"})
        self.assertEqual(ends, {"2026-11-05"})  # reference_date + 3 days, unaffected by since
        self.assertEqual(len(self.calls), len(ing.WEATHER_DATASETS))

    def test_no_since_keeps_the_normal_yesterday_start(self):
        ing.update_all_weather(None, reference_date=date(2026, 11, 2))
        starts = {start for _, start, _ in self.calls}
        self.assertEqual(starts, {"2026-11-01"})

    def test_since_in_the_future_relative_to_yesterday_is_not_used(self):
        # since is a backfill request, never a way to shrink the normal window.
        ing.update_all_weather(None, reference_date=date(2026, 11, 2), since=date(2026, 11, 2))
        starts = {start for _, start, _ in self.calls}
        self.assertEqual(starts, {"2026-11-01"})


# --------------------------------------------------------------------------- #
# GridStatus DAM price - normalize logic is unchanged from gridstatus_pull.py;
# what's new is that update_dam_prices upserts into the DB instead of merging
# a CSV.
# --------------------------------------------------------------------------- #

class FakeGridStatusClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get_dataset(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


class DamPriceIngestionTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE electrical_price (interval_start_utc TEXT, location TEXT, "
                "spp_usd_mwh REAL, PRIMARY KEY (interval_start_utc, location))"
            ))

    def test_update_dam_prices_upserts_fetched_rows(self):
        raw = pd.DataFrame(
            [("2025-06-01T05:00:00+00:00", "LZ_NORTH", 31.5)],
            columns=["interval_start_utc", "location", "spp"],
        )
        client = FakeGridStatusClient(raw)
        now = datetime(2025, 9, 23, 19, 30, tzinfo=timezone.utc)

        result = ing.update_dam_prices(self.engine, client, location="LZ_NORTH", now=now)

        with self.engine.begin() as conn:
            rows = pd.read_sql("SELECT * FROM electrical_price", conn)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows["spp_usd_mwh"].iloc[0], 31.5)
        self.assertEqual(result.added, 1)

    def test_only_lz_north_pulled_by_default_due_to_gridstatus_quota(self):
        # See chat: the real GridStatus quota is 500k rows/month (checked via
        # get_api_usage()), not the 1000/month the old script's docstring
        # claimed - rows aren't the binding constraint. Requests/month (250)
        # is: fetch_dam_prices requests one zone per call, so 8 zones/day
        # alone would be 240 requests/month. Continuous ingestion stays
        # LZ_NORTH-only until that's addressed separately.
        self.assertEqual(ing.DEFAULT_DAM_LOCATIONS, ["LZ_NORTH"])


# --------------------------------------------------------------------------- #
# EIA fetchers - pivot/rename logic ported from eia_pull.py; pinned here since
# eia_pull.py is being deleted.
# --------------------------------------------------------------------------- #

class EiaFetchTests(unittest.TestCase):
    def test_fetch_gas_price_renames_and_coerces(self):
        class FakeSession:
            def get(self, url, params, timeout):
                class R:
                    def raise_for_status(self): pass
                    def json(self):
                        return {"response": {"data": [{"period": "2025-01-01", "value": "2.55"}]}}
                return R()

        out = ing.fetch_gas_price(FakeSession(), date(2025, 1, 1), date(2025, 1, 2))
        self.assertEqual(list(out.columns), ["period", "henry_hub_price_usd_mmbtu"])
        self.assertEqual(out["henry_hub_price_usd_mmbtu"].iloc[0], 2.55)

    def test_fetch_ercot_fuel_mix_excludes_negative_storage_from_pct_denominator(self):
        class FakeSession:
            def get(self, url, params, timeout):
                class R:
                    def raise_for_status(self): pass
                    def json(self):
                        return {"response": {"data": [
                            {"period": "2025-01-01T00", "fueltype": "BAT", "value": "-50"},
                            {"period": "2025-01-01T00", "fueltype": "NG", "value": "100"},
                        ]}}
                return R()

        out = ing.fetch_ercot_fuel_mix(FakeSession(), date(2025, 1, 1), date(2025, 1, 2))
        # BAT charging (-50) must not appear in any _pct denominator: NG is 100% of
        # positive generation even though bat_mwh is negative. Lowercase column
        # names (not EIA's own uppercase fuel codes) to match the real DB columns.
        self.assertIn("bat_mwh", out.columns)
        self.assertAlmostEqual(out["ng_pct"].iloc[0], 100.0)


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #

class OrchestrationTests(unittest.TestCase):
    def test_one_source_failing_does_not_stop_the_others(self):
        engine = make_engine()
        calls = []

        def boom(*a, **k):
            calls.append("weather")
            raise RuntimeError("network down")

        def eia_ok(*a, **k):
            calls.append("eia")
            return 1

        def dam_ok(*a, **k):
            calls.append("dam")
            return 1

        result = ing.run_ingestion(
            engine,
            weather_fn=boom,
            eia_fn=eia_ok,
            dam_fn=dam_ok,
        )
        self.assertEqual(sorted(calls), ["dam", "eia", "weather"])
        self.assertFalse(result.weather_ok)
        self.assertTrue(result.eia_ok)
        self.assertTrue(result.dam_ok)


if __name__ == "__main__":
    unittest.main()
