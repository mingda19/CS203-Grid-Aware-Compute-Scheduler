"""
gridstatus_pull.py

Pulls ERCOT day-ahead market (DAM) hourly settlement point prices from
GridStatus.io and merges them into a CSV under data/GridStatus/.
Mirrors eia_pull.py (EIA) and update_weather_data.py (Open-Meteo): like them,
the CSV is a local working copy (data/ is gitignored). Loading the data into
the database is the backend's job, from the schema below.

Dataset
-------
| name            | file                     | freq   | GridStatus dataset          |
|-----------------|--------------------------|--------|-----------------------------|
| ercot_dam_price | ercot_dam_lz_north.csv   | hourly | ercot_spp_day_ahead_hourly  |

Default location is LZ_NORTH (the North load zone, agreed by the team).

Schema (also what fetch_dam_prices() returns)
---------------------------------------------
  interval_start_utc    - hour start, UTC, timezone-aware. JOIN KEY (matches
                          the EIA and Open-Meteo scripts, which are UTC too).
  interval_start_local  - same instant in America/Chicago, for display only.
  location              - settlement point, e.g. LZ_NORTH.
  spp_usd_mwh           - day-ahead settlement point price, $/MWh (can be negative).
  Unique key: (interval_start_utc, location). Each row is one hour.

The DAM is an auction that clears ONCE per day: the bid window closes at
10:00 Central and all 24 hourly prices for the next operating day are posted
by 1:30 PM Central (~18:30 UTC in summer, ~19:30 UTC in winter). These are
cleared prices, not a forecast, and there is nothing "live" to poll, so one run
per day (after publication) is enough; running more often returns the same rows.

update_dataset():
  1. Works out a window: DEFAULT_LOOKBACK_DAYS back from today (UTC) through
     tomorrow. The look-back re-fetches recent days to pick up ERCOT
     corrections and to backfill a missed run; the look-ahead picks up
     tomorrow's prices once they are published.
  2. Fetches that window for one location from GridStatus.
  3. Drops rows with no price, so a bad fetch can't blank out good data.
  4. Merges with the existing CSV, dedupes on (interval_start_utc, location)
     keeping the NEW row (ERCOT can re-post/correct), sorts by time.
  5. Writes to a temp file and swaps it in, so a crash can't leave a
     half-written CSV. If the CSV doesn't exist yet, it's created.
  6. Logs the outcome and checks that tomorrow's prices arrived (see below).

Usage
-----
  python gridstatus_pull.py                      # last few days + tomorrow
  python gridstatus_pull.py --month              # from the 1st of this month
  python gridstatus_pull.py --since 2024-01-01   # backfill history
  python gridstatus_pull.py --location LZ_HOUSTON
  python gridstatus_pull.py --since 2024-01-01 --max-rows 20000   # big backfill, on purpose

Or import: from gridstatus_pull import update_dam_prices, fetch_dam_prices

Logging and exit codes
----------------------
Every run logs (UTC timestamps) whether the pull succeeded, how many rows were
fetched/added, and the latest interval received. After 2:00 PM Central it also
warns if tomorrow's prices are not there yet. The exit code lets a scheduler
decide whether to retry:
  0  success (and tomorrow's prices present, or too early to expect them)
  1  the pull FAILED (bad/missing API key, network error, schema change...)
  2  the pull worked but the data is incomplete: no rows at all, it hit the
     row cap, or it is past 2:00 PM Central and tomorrow's prices are still
     missing -> retry later

Quota rationing
---------------
Each run asks the API for at most --max-rows rows (default 1000, sent as the
`limit` parameter), and a run that hits the cap logs a warning and exits 2. A
normal daily run is ~100-125 rows for one location, so the cap only bites on
long --since/--month backfills, where raising it is a deliberate choice.

Notes
-----
  - Requires GRIDSTATUS_API_KEY in the environment or in a .env file at the
    repo root (.env is gitignored - never commit the key; see .env.example).
  - Timestamps are stored in UTC on purpose. ERCOT publishes in Central Time,
    and the fall-back DST day has a repeated 01:00 hour, so local time is NOT a
    safe join key.
  - Unlike the other two scripts there is no request cache: a cached response
    from before ERCOT publishes would hide the new prices for up to an hour.
    The gridstatusio client already retries with exponential backoff.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

logger = logging.getLogger("gridstatus_pull")

DATASET = "ercot_spp_day_ahead_hourly"
DEFAULT_LOCATION = "LZ_NORTH"
LOCAL_TZ = "America/Chicago"
DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "GridStatus"
FILENAME = "ercot_dam_lz_north.csv"

DEFAULT_LOOKBACK_DAYS = 3  # re-fetch recent days in case ERCOT re-posted them
# Exclusive end date, counted from today's UTC date. Tomorrow's operating day
# (published today) is a Central-time day, so it ends at 05:00-06:00 UTC on the
# day AFTER tomorrow; +3 days is the first UTC midnight that covers all of it.
# Asking for hours that aren't published yet just returns nothing.
END_OFFSET_DAYS = 3
# ERCOT's posting deadline is 1:30 PM Central; only complain about missing
# tomorrow data once it is past this hour (Central) to leave some margin.
PUBLISH_CHECK_HOUR_CT = 14

# Hard cap on rows requested from the API per run, to ration the GridStatus quota.
# A normal daily run is ~100-125 rows; a long --since backfill has to raise it on
# purpose with --max-rows.
DEFAULT_MAX_ROWS = 1000

EXIT_OK, EXIT_FAILED, EXIT_INCOMPLETE = 0, 1, 2

KEY_COLS = ["interval_start_utc", "location"]
OUT_COLS = ["interval_start_utc", "interval_start_local", "location", "spp_usd_mwh"]
API_COLS = ["interval_start_utc", "location", "spp"]


@dataclass
class PullResult:
    fetched: int                       # rows with a price returned by GridStatus
    added: int                         # rows newly added to the CSV
    total: int                         # rows in the CSV afterwards
    latest_local: pd.Timestamp | None  # newest interval fetched, Central time
    next_day_published: bool | None    # None = too early in the day to expect it
    truncated: bool = False            # hit the row cap, so data may be missing

    @property
    def ok(self) -> bool:
        return self.fetched > 0 and not self.truncated and self.next_day_published is not False


# --------------------------------------------------------------------------- #
# Fetch
# --------------------------------------------------------------------------- #

def make_client():
    api_key = os.environ.get("GRIDSTATUS_API_KEY")
    if not api_key:
        raise RuntimeError("GRIDSTATUS_API_KEY is not set. Export it or put it in a .env file.")
    # Imported after the key check on purpose: gridstatusio checks PyPI for a
    # newer version when imported, so a missing key should fail before that.
    from gridstatusio import GridStatusClient

    return GridStatusClient(api_key=api_key)


def normalize(raw: pd.DataFrame) -> pd.DataFrame:
    """Turn a raw GridStatus response into the CSV schema (OUT_COLS)."""
    if raw.empty:
        return pd.DataFrame(columns=OUT_COLS)

    missing = [c for c in API_COLS if c not in raw.columns]
    if missing:
        raise RuntimeError(
            f"GridStatus response for {DATASET} is missing {missing}; "
            f"got columns {list(raw.columns)}. Has the dataset schema changed?"
        )

    utc = pd.to_datetime(raw["interval_start_utc"], utc=True)
    df = pd.DataFrame({
        "interval_start_utc": utc,
        "interval_start_local": utc.dt.tz_convert(LOCAL_TZ),
        "location": raw["location"].astype(str),
        "spp_usd_mwh": pd.to_numeric(raw["spp"], errors="coerce"),
    })
    return df[OUT_COLS]


def fetch_dam_prices(client, start: date, end: date, location: str,
                     max_rows: int = DEFAULT_MAX_ROWS) -> pd.DataFrame:
    """Fetch DAM prices for `location` between start and end (UTC dates).

    Never asks the API for more than `max_rows` rows.
    """
    raw = client.get_dataset(
        dataset=DATASET,
        start=start.isoformat(),
        end=end.isoformat(),
        filter_column="location",
        filter_value=location,
        columns=["interval_start_utc", "location", "spp"],
        limit=max_rows,
        verbose=False,
    )
    df = normalize(raw)
    if df.empty:
        # An empty result is normal for a future-only window, but it is also what
        # a mistyped location returns, so say which one we asked for.
        logger.warning("No rows returned for location=%r between %s and %s", location, start, end)
    return df


# --------------------------------------------------------------------------- #
# Merge + write
# --------------------------------------------------------------------------- #

def merge_into_csv(path: Path, new: pd.DataFrame) -> tuple[int, int]:
    """Merge `new` into the CSV at `path`. Returns (rows_added, total_rows)."""
    new = new.dropna(subset=["spp_usd_mwh"])

    if path.exists():
        old = pd.read_csv(path)
        old["interval_start_utc"] = pd.to_datetime(old["interval_start_utc"], utc=True)
        old["interval_start_local"] = pd.to_datetime(old["interval_start_local"], utc=True)
        old["interval_start_local"] = old["interval_start_local"].dt.tz_convert(LOCAL_TZ)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        old = pd.DataFrame(columns=OUT_COLS)

    frames = [f for f in (old, new) if not f.empty]
    combined = pd.concat(frames, ignore_index=True) if frames else new
    combined = (
        combined.reindex(columns=OUT_COLS)
        .drop_duplicates(subset=KEY_COLS, keep="last")
        .sort_values(KEY_COLS)
        .reset_index(drop=True)
    )

    tmp = path.with_suffix(".csv.tmp")
    combined.to_csv(tmp, index=False)
    tmp.replace(path)
    return len(combined) - len(old), len(combined)


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #

def fetch_window(now: datetime, since: date | None = None) -> tuple[date, date]:
    """(start, end) UTC dates to request; `end` is exclusive."""
    today = now.astimezone(timezone.utc).date()
    default_start = today - timedelta(days=DEFAULT_LOOKBACK_DAYS)
    # --since only ever widens the window; it never shrinks the default lookback.
    start = min(since, default_start) if since else default_start
    return start, today + timedelta(days=END_OFFSET_DAYS)


def next_day_status(latest_local: pd.Timestamp | None, now: datetime) -> bool | None:
    """Did tomorrow's (Central) prices arrive? None if it's too early to expect them."""
    now_ct = pd.Timestamp(now).tz_convert(LOCAL_TZ)
    if now_ct.hour < PUBLISH_CHECK_HOUR_CT:
        return None
    tomorrow = now_ct.date() + timedelta(days=1)
    return latest_local is not None and latest_local.date() >= tomorrow


def update_dataset(client, location: str = DEFAULT_LOCATION, since: date | None = None,
                   path: Path | None = None, now: datetime | None = None,
                   max_rows: int = DEFAULT_MAX_ROWS) -> PullResult:
    now = now or datetime.now(timezone.utc)
    start, end = fetch_window(now, since)
    path = path or (DATA_DIR / FILENAME)

    logger.info("Fetching ERCOT DAM prices for %s: %s -> %s (UTC, end exclusive), max %d rows",
                location, start, end, max_rows)
    new = fetch_dam_prices(client, start, end, location, max_rows)
    priced = new.dropna(subset=["spp_usd_mwh"])
    added, total = merge_into_csv(path, new)

    latest = priced["interval_start_local"].max() if not priced.empty else None
    result = PullResult(
        fetched=len(priced), added=added, total=total, latest_local=latest,
        next_day_published=next_day_status(latest, now),
        truncated=len(new) >= max_rows,
    )

    logger.info("Pull succeeded: fetched %d rows, %d new, %d in CSV -> %s",
                result.fetched, result.added, result.total, path)
    if result.truncated:
        logger.warning("Hit the %d-row cap, so the data may be incomplete. Raise it with "
                       "--max-rows only if you really need more (it uses API quota)", max_rows)
    if result.fetched == 0:
        logger.warning("GridStatus returned no priced rows: check the location name and dates")
    else:
        logger.info("Latest interval received: %s (Central)", latest)
    if result.next_day_published is True:
        logger.info("Tomorrow's prices are present")
    elif result.next_day_published is False:
        logger.warning("Tomorrow's DAM prices are NOT there yet (ERCOT posts by ~1:30 PM Central); "
                       "re-run later")
    else:
        logger.info("Before %d:00 PM Central: tomorrow's prices are not expected yet",
                    PUBLISH_CHECK_HOUR_CT - 12)
    return result


def update_dam_prices(since: date | None = None, location: str = DEFAULT_LOCATION,
                      max_rows: int = DEFAULT_MAX_ROWS) -> PullResult:
    return update_dataset(make_client(), location=location, since=since, max_rows=max_rows)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Update ERCOT DAM price CSV under data/GridStatus/.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--month", action="store_true", help="backfill from the 1st of this month")
    group.add_argument("--since", type=date.fromisoformat, help="backfill from YYYY-MM-DD")
    parser.add_argument("--location", default=DEFAULT_LOCATION,
                        help=f"settlement point / load zone (default {DEFAULT_LOCATION})")
    parser.add_argument("--max-rows", type=int, default=DEFAULT_MAX_ROWS,
                        help=f"cap on rows requested from the API (default {DEFAULT_MAX_ROWS})")
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    since = args.since
    if args.month:
        since = datetime.now(timezone.utc).date().replace(day=1)

    try:
        result = update_dam_prices(since=since, location=args.location, max_rows=args.max_rows)
    except Exception:
        logger.exception("Pull FAILED")
        sys.exit(EXIT_FAILED)
    sys.exit(EXIT_OK if result.ok else EXIT_INCOMPLETE)


if __name__ == "__main__":
    main()
