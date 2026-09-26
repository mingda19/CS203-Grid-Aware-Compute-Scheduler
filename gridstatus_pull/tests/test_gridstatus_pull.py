"""Offline tests for gridstatus_pull (no API key or network needed).

Run from the gridstatus_pull/ folder (pytest.ini lives there):
    pip install -r requirements-dev.txt
    pytest -v
"""

import os
import sys
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from unittest import mock

import pandas as pd

import gridstatus_pull as gp


def raw(rows):
    """Build a GridStatus-shaped response: (utc timestamp, location, spp)."""
    return pd.DataFrame(rows, columns=["interval_start_utc", "location", "spp"])


class FakeClient:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def get_dataset(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


class NormalizeTests(unittest.TestCase):
    def test_schema_and_units(self):
        out = gp.normalize(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", "31.5")]))
        self.assertEqual(list(out.columns), gp.OUT_COLS)
        self.assertEqual(out.loc[0, "spp_usd_mwh"], 31.5)  # string coerced to number
        # 05:00 UTC in June is 00:00 CDT (UTC-5)
        self.assertEqual(out.loc[0, "interval_start_local"].hour, 0)

    def test_empty_response(self):
        out = gp.normalize(pd.DataFrame())
        self.assertTrue(out.empty)
        self.assertEqual(list(out.columns), gp.OUT_COLS)

    def test_missing_column_raises_clear_error(self):
        bad = pd.DataFrame({"interval_start_utc": ["2025-06-01T05:00:00+00:00"], "price": [1.0]})
        with self.assertRaisesRegex(RuntimeError, "missing"):
            gp.normalize(bad)

    def test_dst_fall_back_keeps_both_repeated_hours(self):
        # 2025-11-02: clocks fall back, so local 01:00 happens twice (05:00Z and 06:00Z).
        out = gp.normalize(raw([
            ("2025-11-02T05:00:00+00:00", "LZ_NORTH", 20.0),
            ("2025-11-02T06:00:00+00:00", "LZ_NORTH", 22.0),
        ]))
        self.assertEqual(out["interval_start_local"].dt.hour.tolist(), [0, 1])
        merged_utc = out["interval_start_utc"].nunique()
        self.assertEqual(merged_utc, 2)


class MergeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "sub" / "dam.csv"  # parent dir does not exist yet

    def test_creates_file_and_parent_dir(self):
        new = gp.normalize(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0)]))
        added, total = gp.merge_into_csv(self.path, new)
        self.assertEqual((added, total), (1, 1))
        self.assertTrue(self.path.exists())
        self.assertFalse(self.path.with_suffix(".csv.tmp").exists())

    def test_new_row_wins_on_duplicate_and_result_is_sorted(self):
        first = gp.normalize(raw([
            ("2025-06-01T07:00:00+00:00", "LZ_NORTH", 30.0),
            ("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0),
        ]))
        gp.merge_into_csv(self.path, first)
        second = gp.normalize(raw([
            ("2025-06-01T05:00:00+00:00", "LZ_NORTH", 11.5),  # corrected
            ("2025-06-01T06:00:00+00:00", "LZ_NORTH", 20.0),  # new
        ]))
        added, total = gp.merge_into_csv(self.path, second)
        self.assertEqual((added, total), (1, 3))

        df = pd.read_csv(self.path)
        self.assertEqual(df["spp_usd_mwh"].tolist(), [11.5, 20.0, 30.0])
        self.assertTrue(pd.to_datetime(df["interval_start_utc"], utc=True).is_monotonic_increasing)

    def test_rerun_is_idempotent(self):
        new = gp.normalize(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0)]))
        gp.merge_into_csv(self.path, new)
        added, total = gp.merge_into_csv(self.path, new)
        self.assertEqual((added, total), (0, 1))

    def test_rows_without_price_never_overwrite_good_data(self):
        gp.merge_into_csv(self.path, gp.normalize(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0)])))
        bad = gp.normalize(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", None)]))
        gp.merge_into_csv(self.path, bad)
        self.assertEqual(pd.read_csv(self.path)["spp_usd_mwh"].tolist(), [10.0])

    def test_locations_are_kept_separate(self):
        gp.merge_into_csv(self.path, gp.normalize(raw([
            ("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0),
            ("2025-06-01T05:00:00+00:00", "LZ_HOUSTON", 12.0),
        ])))
        self.assertEqual(len(pd.read_csv(self.path)), 2)


class WindowTests(unittest.TestCase):
    def test_covers_the_whole_next_operating_day_after_afternoon_publication(self):
        # 5pm CDT on 23 Sep = 22:00 UTC. ERCOT has just published all of 24 Sep
        # (Central), whose last hour starts at 04:00 UTC on 25 Sep.
        now = datetime(2025, 9, 23, 22, 0, tzinfo=timezone.utc)
        _, end = gp.fetch_window(now)
        last_hour = datetime(2025, 9, 25, 4, 0, tzinfo=timezone.utc)
        end_utc = datetime(end.year, end.month, end.day, tzinfo=timezone.utc)
        self.assertGreater(end_utc, last_hour)

    def test_default_lookback(self):
        now = datetime(2025, 9, 23, 22, 0, tzinfo=timezone.utc)
        start, _ = gp.fetch_window(now)
        self.assertEqual(start, date(2025, 9, 20))

    def test_since_widens_but_never_shrinks(self):
        now = datetime(2025, 9, 23, 22, 0, tzinfo=timezone.utc)
        self.assertEqual(gp.fetch_window(now, since=date(2025, 1, 1))[0], date(2025, 1, 1))
        self.assertEqual(gp.fetch_window(now, since=date(2025, 9, 22))[0], date(2025, 9, 20))


class FetchTests(unittest.TestCase):
    def test_requests_the_right_dataset_and_location(self):
        client = FakeClient(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0)]))
        out = gp.fetch_dam_prices(client, date(2025, 6, 1), date(2025, 6, 3), "LZ_NORTH")
        call = client.calls[0]
        self.assertEqual(call["dataset"], "ercot_spp_day_ahead_hourly")
        self.assertEqual(call["filter_column"], "location")
        self.assertEqual(call["filter_value"], "LZ_NORTH")
        self.assertEqual(len(out), 1)

    def test_update_dataset_end_to_end_with_fake_client(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "dam.csv"
            client = FakeClient(raw([("2025-06-01T05:00:00+00:00", "LZ_NORTH", 10.0)]))
            result = gp.update_dataset(client, path=path, now=AFTERNOON)
            self.assertEqual(len(pd.read_csv(path)), 1)
            self.assertEqual((result.fetched, result.added, result.total), (1, 1, 1))


# 2:30 PM CDT on 23 Sep 2025 (19:30 UTC): after ERCOT's 1:30 PM posting deadline.
AFTERNOON = datetime(2025, 9, 23, 19, 30, tzinfo=timezone.utc)
# 10:00 AM CDT the same day: before publication.
MORNING = datetime(2025, 9, 23, 15, 0, tzinfo=timezone.utc)

TODAY_ROW = ("2025-09-23T05:00:00+00:00", "LZ_NORTH", 20.0)      # 23 Sep 00:00 Central
TOMORROW_ROW = ("2025-09-24T05:00:00+00:00", "LZ_NORTH", 25.0)   # 24 Sep 00:00 Central


class PullStatusTests(unittest.TestCase):
    def run_pull(self, rows, now):
        with tempfile.TemporaryDirectory() as d:
            client = FakeClient(raw(rows))
            with self.assertLogs("gridstatus_pull", level="INFO") as logs:
                result = gp.update_dataset(client, path=Path(d) / "dam.csv", now=now)
        return result, "\n".join(logs.output)

    def test_success_when_tomorrow_is_published(self):
        result, log = self.run_pull([TODAY_ROW, TOMORROW_ROW], AFTERNOON)
        self.assertTrue(result.ok)
        self.assertTrue(result.next_day_published)
        self.assertIn("Pull succeeded: fetched 2 rows", log)
        self.assertIn("Tomorrow's prices are present", log)
        self.assertNotIn("WARNING", log)

    def test_warns_when_tomorrow_is_missing_after_publication_time(self):
        result, log = self.run_pull([TODAY_ROW], AFTERNOON)
        self.assertFalse(result.ok)
        self.assertIs(result.next_day_published, False)
        self.assertIn("WARNING", log)
        self.assertIn("NOT there yet", log)

    def test_missing_tomorrow_is_fine_before_publication_time(self):
        result, log = self.run_pull([TODAY_ROW], MORNING)
        self.assertTrue(result.ok)
        self.assertIsNone(result.next_day_published)
        self.assertNotIn("WARNING", log)

    def test_no_rows_is_reported_as_not_ok(self):
        result, log = self.run_pull([], MORNING)
        self.assertFalse(result.ok)
        self.assertIn("no priced rows", log)


class RowCapTests(unittest.TestCase):
    def test_default_cap_is_1000_and_sent_to_the_api(self):
        self.assertEqual(gp.DEFAULT_MAX_ROWS, 1000)
        client = FakeClient(raw([TODAY_ROW]))
        gp.fetch_dam_prices(client, date(2025, 9, 20), date(2025, 9, 26), "LZ_NORTH")
        self.assertEqual(client.calls[0]["limit"], 1000)

    def test_custom_cap_is_sent_to_the_api(self):
        client = FakeClient(raw([TODAY_ROW]))
        gp.fetch_dam_prices(client, date(2025, 9, 20), date(2025, 9, 26), "LZ_NORTH", max_rows=50)
        self.assertEqual(client.calls[0]["limit"], 50)

    def test_hitting_the_cap_is_flagged_as_incomplete(self):
        with tempfile.TemporaryDirectory() as d:
            client = FakeClient(raw([TODAY_ROW, TOMORROW_ROW]))
            with self.assertLogs("gridstatus_pull", level="INFO") as logs:
                result = gp.update_dataset(client, path=Path(d) / "dam.csv", now=AFTERNOON, max_rows=2)
        self.assertTrue(result.truncated)
        self.assertFalse(result.ok)
        self.assertIn("2-row cap", "\n".join(logs.output))

    def test_normal_run_is_under_the_cap(self):
        with tempfile.TemporaryDirectory() as d:
            client = FakeClient(raw([TODAY_ROW, TOMORROW_ROW]))
            result = gp.update_dataset(client, path=Path(d) / "dam.csv", now=AFTERNOON)
        self.assertFalse(result.truncated)


class NextDayStatusTests(unittest.TestCase):
    def test_uses_central_time_for_tomorrow(self):
        # 03:00 UTC on 24 Sep is still 10:00 PM on 23 Sep in Chicago, so "tomorrow"
        # is 24 Sep (Central) even though the UTC date has already rolled over.
        now = datetime(2025, 9, 24, 3, 0, tzinfo=timezone.utc)
        latest = pd.Timestamp("2025-09-24 23:00", tz="America/Chicago")
        self.assertTrue(gp.next_day_status(latest, now))
        today_only = pd.Timestamp("2025-09-23 23:00", tz="America/Chicago")
        self.assertFalse(gp.next_day_status(today_only, now))


class EntryPointTests(unittest.TestCase):
    def test_missing_api_key_raises_clear_error(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "GRIDSTATUS_API_KEY"):
                gp.make_client()

    def run_main(self, side_effect=None, result=None):
        with mock.patch.object(sys, "argv", ["gridstatus_pull.py"]), \
             mock.patch.object(gp, "update_dam_prices", side_effect=side_effect, return_value=result), \
             self.assertRaises(SystemExit) as cm:
            gp.main()
        return cm.exception.code

    def test_exit_code_0_on_success(self):
        ok = gp.PullResult(fetched=24, added=24, total=24, latest_local=None, next_day_published=True)
        self.assertEqual(self.run_main(result=ok), gp.EXIT_OK)

    def test_exit_code_1_on_failure(self):
        with self.assertLogs("gridstatus_pull", level="ERROR"):
            self.assertEqual(self.run_main(side_effect=RuntimeError("boom")), gp.EXIT_FAILED)

    def test_exit_code_2_when_data_incomplete(self):
        late = gp.PullResult(fetched=24, added=0, total=24, latest_local=None, next_day_published=False)
        self.assertEqual(self.run_main(result=late), gp.EXIT_INCOMPLETE)


if __name__ == "__main__":
    unittest.main()
