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


class LoadForecastDamIngestionTests(unittest.TestCase):
    def setUp(self):
        self.engine = make_engine()
        with self.engine.begin() as conn:
            conn.execute(sa.text(
                "CREATE TABLE load_forecast_dam (interval_start_utc TEXT, zone TEXT, "
                "load_forecast_mwh REAL, publish_time_utc TEXT, PRIMARY KEY (interval_start_utc, zone))"
            ))

    def test_normalize_reshapes_wide_zones_to_long_rows(self):
        raw = pd.DataFrame([{
            "interval_start_utc": "2026-10-06T00:00:00+00:00",
            "publish_time_utc": "2026-10-05T19:30:00+00:00",
            "north": 20282.2, "south": 100.0, "west": 200.0, "houston": 300.0, "system_total": 20882.2,
        }])
        out = ing.normalize_load_forecast(raw)
        self.assertEqual(len(out), 5)  # one row per zone
        self.assertEqual(set(out["zone"]), set(ing.LOAD_FORECAST_ZONES))
        north_row = out[out["zone"] == "north"].iloc[0]
        self.assertEqual(north_row["load_forecast_mwh"], 20282.2)

    def test_update_load_forecast_dam_upserts_all_zones(self):
        raw = pd.DataFrame([{
            "interval_start_utc": "2026-10-06T00:00:00+00:00",
            "publish_time_utc": "2026-10-05T19:30:00+00:00",
            "north": 20282.2, "south": 100.0, "west": 200.0, "houston": 300.0, "system_total": 20882.2,
        }])
        client = FakeGridStatusClient(raw)
        added, total = ing.update_load_forecast_dam(self.engine, client, now=datetime(2026, 10, 6, tzinfo=timezone.utc))
        self.assertEqual((added, total), (5, 5))

        with self.engine.begin() as conn:
            rows = pd.read_sql("SELECT * FROM load_forecast_dam", conn)
        self.assertEqual(len(rows), 5)


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

        def load_forecast_ok(*a, **k):
            calls.append("load_forecast")
            return 1

        result = ing.run_ingestion(
            engine,
            weather_fn=boom,
            eia_fn=eia_ok,
            dam_fn=dam_ok,
            load_forecast_fn=load_forecast_ok,
        )
        self.assertEqual(sorted(calls), ["dam", "eia", "load_forecast", "weather"])
        self.assertFalse(result.weather_ok)
        self.assertTrue(result.eia_ok)
        self.assertTrue(result.load_forecast_ok)


if __name__ == "__main__":
    unittest.main()
