"""
feature_store.py

Persisted cache of the hourly model inputs that predictions.load_exogenous() derives from the raw
wind / solar / fuel_price tables: the wind and solar power-output proxies (summed over every site)
and the Henry Hub gas price lagged 2 and 3 days. They live in `model_features_hourly` (table owned
by the backend's ModelFeatureHourly JPA entity - ddl-auto=update creates it; this module only
reads/writes rows, like model_store.py does for model_artifacts).

Why: those four values are a tiny function of a huge amount of raw data (31 weather sites x 24
hours x every day in the window), and the models need years of them - LEAR's longest calibration
window is 1456 days - so every prediction run used to download ~90 MB of per-site weather rows only
to collapse them into two numbers per hour. Reading the cache instead is ~5 MB.

Everything the models consume is covered: LSTM uses all four columns, LEAR uses wind, solar and
the 3-day gas lag. Price is NOT cached (electrical_price stays the single source of truth), and
calendar features are derived from the timestamp at read time.

How it stays correct
  - The values are produced by predictions.load_exogenous_raw() - the one and only implementation
    of the feature formulas - so the cache can never drift from the raw path.
  - Not everything is final: ingestion re-fetches weather from yesterday through +3 days on every
    run (those newest hours are a revised forecast), and a gas price can land a few days late. So
    refresh() recomputes a trailing window (REFRESH_LOOKBACK_DAYS back, REFRESH_AHEAD_DAYS ahead)
    on every run; older hours are immutable. ingestion.py calls refresh() as its LAST step, with
    `--since` passed through, so a historical backfill refreshes the cache over the same dates.
  - If a refresh fails, the stale tail is deleted rather than left behind: the reader then sees a
    hole and falls back to the raw tables (slower, never wrong) until a refresh succeeds.
  - read_cached() returns None - "use the raw path" - unless it can prove the cache covers every
    requested hour. A missing table, a gap, or a window reaching past the newest cached hour all
    just mean the old, slow path runs; nothing about serving depends on the cache being healthy.

If the feature formulas change (the physical constants in predictions.py - CUT_IN_MS, RATED_MS,
NOCT_C, ... - or the proxy functions), cached rows are stale: run `python feature_store.py
--rebuild` after deploying the change. The cache does not detect this itself.

Usage:
  python feature_store.py                          # refresh the trailing window (what ingestion.py does)
  python feature_store.py --rebuild                # recompute everything from the earliest raw data
  python feature_store.py --rebuild --since 2024-01-01   # ... or from a given date
  python feature_store.py --verify --days 30       # read-only: compare cache with a fresh recompute
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import sqlalchemy as sa

import ingestion as ing
import predictions as pred

logger = logging.getLogger("feature_store")

TABLE = "model_features_hourly"
TIME_COL = "interval_start_utc"
# Same names and order load_exogenous() has always returned them in.
FEATURE_COLS = [
    "henry_hub_price_usd_mmbtu_lag2d",
    "henry_hub_price_usd_mmbtu_lag3d",
    "wind_power_output_proxy_total",
    "solar_power_output_proxy_total",
]

# Trailing window refresh() recomputes every run. Must cover the newest weather revisions (a few
# days) and the gas forward-fill + lag reach (GAS_MAX_STALE_DAYS + 3 = 17 days): a gas price that
# is published late changes lag values up to that many days after it.
REFRESH_LOOKBACK_DAYS = 21
# Ingestion fetches weather through today + 3 days (ingestion.update_all_weather) - the y1 target.
REFRESH_AHEAD_DAYS = 3
# A rebuild computes this many days per query so memory and query size stay bounded.
REBUILD_CHUNK_DAYS = 120

# verify() treats values within this (relative and absolute) as equal - ~6 orders of magnitude above
# summation-order rounding noise, ~6 below any difference a model could feel.
VERIFY_TOLERANCE = 1e-9

_warned: set[str] = set()


def _warn_once(message: str) -> None:
    """The reader runs once per load_exogenous() call (update.py makes hundreds): say a reason once."""
    if message not in _warned:
        _warned.add(message)
        logger.warning(message)


def _dt(ts) -> datetime:
    return pd.Timestamp(ts).to_pydatetime()


def _has_table(engine: sa.Engine) -> bool:
    return sa.inspect(engine).has_table(TABLE)


def _max_cached(engine: sa.Engine) -> pd.Timestamp | None:
    with engine.begin() as conn:
        value = conn.execute(sa.text(f"SELECT MAX({TIME_COL}) FROM {TABLE}")).scalar()
    return None if value is None else pd.Timestamp(value)


def _raw_data_start(engine: sa.Engine) -> pd.Timestamp | None:
    """Earliest timestamp any raw source behind the cache has data for."""
    starts = []
    for table, column in (("wind", "time"), ("solar", "time"), ("fuel_price", "period")):
        with engine.begin() as conn:
            value = conn.execute(sa.text(f"SELECT MIN({column}) FROM {table}")).scalar()
        if value is not None:
            starts.append(pd.Timestamp(value))
    return min(starts) if starts else None


# --------------------------------------------------------------------------- #
# Read path
# --------------------------------------------------------------------------- #

def read_cached(engine: sa.Engine, start, end) -> pd.DataFrame | None:
    """The frame predictions.load_exogenous_raw(engine, start, end) would return (identical up to
    floating-point summation order, ~1e-15 relative - see verify()), read from the cache - or
    None if the cache can't prove it covers `start`..`end` (inclusive, hourly):

      - no cache table / unreadable            -> None
      - an hour missing inside the range       -> None (a gap, or the range runs past the newest
                                                  cached hour, e.g. a refresh didn't happen)
      - the range begins before the first cached hour: fine only if no raw source has any data
        before that hour (the raw path would return NaN there too); otherwise None, because the
        cache is then a partial backfill, not the true start of history.
    """
    hours = pd.date_range(pd.Timestamp(start), pd.Timestamp(end), freq="h")
    query = sa.text(
        f"SELECT {TIME_COL} AS time, {', '.join(FEATURE_COLS)} FROM {TABLE} "
        f"WHERE {TIME_COL} >= :start AND {TIME_COL} <= :end ORDER BY {TIME_COL}"
    ).bindparams(sa.bindparam("start", type_=sa.DateTime()), sa.bindparam("end", type_=sa.DateTime()))
    try:
        # conn.execute, not pd.read_sql: pandas re-wraps database errors in its own DatabaseError,
        # which would slip past the DBAPIError handler this fallback depends on.
        with engine.begin() as conn:
            result = conn.execute(query, {"start": _dt(hours[0]), "end": _dt(hours[-1])})
            cached = pd.DataFrame(result.fetchall(), columns=["time", *FEATURE_COLS])
    except sa.exc.DBAPIError:
        _warn_once(f"{TABLE} is not readable - computing model inputs from the raw tables instead "
                   "(slow: re-downloads the full weather history).")
        return None

    if cached.empty:
        _warn_once(f"{TABLE} has no rows for {hours[0]}..{hours[-1]} - computing from the raw tables instead.")
        return None
    cached["time"] = pd.to_datetime(cached["time"])
    first = cached["time"].iloc[0]
    if not pd.DatetimeIndex(cached["time"]).equals(hours[hours >= first]):
        _warn_once(f"{TABLE} is missing hours in {hours[0]}..{hours[-1]} (gap, or a refresh didn't run) - "
                   "computing from the raw tables instead.")
        return None
    if first > hours[0]:
        try:
            raw_start = _raw_data_start(engine)
        except sa.exc.DBAPIError:
            raw_start = None
        if raw_start is None or raw_start < first:
            _warn_once(f"{TABLE} starts at {first} but raw data exists earlier - computing from the "
                       "raw tables instead (run `python feature_store.py --rebuild`).")
            return None

    out = pd.DataFrame({"time": hours}).merge(cached, on="time", how="left")
    for col in FEATURE_COLS:
        out[col] = pd.to_numeric(out[col], errors="coerce")
    return out


# --------------------------------------------------------------------------- #
# Write path
# --------------------------------------------------------------------------- #

def _write_range(engine: sa.Engine, start: pd.Timestamp, end: pd.Timestamp) -> int:
    """Recompute start..end (inclusive hours) from the raw tables and upsert it. Returns rows written."""
    total, chunk_start = 0, start
    while chunk_start <= end:
        chunk_end = min(chunk_start + timedelta(days=REBUILD_CHUNK_DAYS) - timedelta(hours=1), end)
        frame = pred.load_exogenous_raw(engine, chunk_start, chunk_end)
        frame = frame.rename(columns={"time": TIME_COL})[[TIME_COL, *FEATURE_COLS]]
        ing.upsert_dataframe(engine, TABLE, frame, [TIME_COL])
        total += len(frame)
        chunk_start = chunk_end + timedelta(hours=1)
    return total


def _invalidate_from(engine: sa.Engine, start: pd.Timestamp) -> None:
    """Drop every cached hour from `start` on, so the reader falls back to the raw tables instead of
    serving a tail that a failed refresh left stale. Best effort: if the database is unreachable the
    raw path would be too."""
    try:
        with engine.begin() as conn:
            conn.execute(
                sa.text(f"DELETE FROM {TABLE} WHERE {TIME_COL} >= :start").bindparams(
                    sa.bindparam("start", type_=sa.DateTime())),
                {"start": _dt(start)},
            )
        logger.warning("Removed cached hours from %s on so predictions use the raw tables until the next "
                       "successful refresh.", start)
    except Exception:  # noqa: BLE001 - already handling the original failure
        logger.exception("Could not invalidate the stale tail of %s", TABLE)


def _window_end(now) -> tuple[pd.Timestamp, pd.Timestamp]:
    """(today 00:00 UTC, last hour refresh covers) for `now` (default: the current UTC time)."""
    ts = pd.Timestamp(now if now is not None else datetime.now(timezone.utc))
    if ts.tzinfo is not None:
        ts = ts.tz_convert("UTC").tz_localize(None)
    today = ts.normalize()
    return today, today + timedelta(days=REFRESH_AHEAD_DAYS, hours=23)


def refresh(engine: sa.Engine, since=None, now=None, full: bool = False) -> int:
    """Bring the cache up to date: recompute a window and upsert it. Returns rows written (0 if
    skipped). Idempotent; on a normal day it is a few thousand raw rows.

    Default (`full=False`): the trailing window - `REFRESH_LOOKBACK_DAYS` before today through
    `REFRESH_AHEAD_DAYS` ahead - or earlier when the cache is behind (the pipeline missed days) or
    when `since` asks for it (ingestion.py --since rewrote history). An empty cache is bootstrapped
    from the earliest raw data.

    `full=True` (the --rebuild CLI mode): recompute everything from `since` - or the earliest raw
    data - regardless of what is cached. Use after changing the feature formulas, or if verify()
    finds drift.

    A missing table is skipped with a warning by the daily refresh (the backend creates it on boot
    via ddl-auto, and ingestion must not fail before that), but raises for `full=True`: someone
    asking for a rebuild by hand must not be told it succeeded when nothing was written.
    """
    if not _has_table(engine):
        if full:
            raise RuntimeError(f"{TABLE} does not exist - start the backend once so ddl-auto creates it.")
        logger.warning("%s does not exist yet (the backend creates it on boot) - skipping the feature "
                       "refresh; predictions will use the raw tables.", TABLE)
        return 0
    today, end = _window_end(now)
    last = None if full else _max_cached(engine)
    if last is None:
        raw_start = _raw_data_start(engine)
        if raw_start is None:
            logger.warning("No raw wind/solar/gas data yet - nothing to cache.")
            return 0
        start = raw_start.floor("D")
        if not full:
            logger.info("%s is empty - building it from %s", TABLE, start)
    else:
        start = min(today, last.floor("D")) - timedelta(days=REFRESH_LOOKBACK_DAYS)
    if since is not None:
        since_day = pd.Timestamp(since).normalize()
        start = since_day if full else min(start, since_day)
    return _build(engine, start, end)


def _build(engine: sa.Engine, start: pd.Timestamp, end: pd.Timestamp) -> int:
    logger.info("[features] recomputing %s .. %s", start, end)
    try:
        rows = _write_range(engine, start, end)
    except Exception:
        _invalidate_from(engine, start)
        raise
    logger.info("[features] wrote %d hourly rows to %s", rows, TABLE)
    return rows


# --------------------------------------------------------------------------- #
# Verification
# --------------------------------------------------------------------------- #

def verify(engine: sa.Engine, start, end) -> tuple[int, int]:
    """Read-only. Compare the cache against a fresh recompute from the raw tables over
    start..end. Returns (hours_compared, hours_that_differ); a window the cache can't serve counts
    every hour as differing.

    Compared to VERIFY_TOLERANCE, not bit-for-bit: the wind/solar totals are a sum over sites, and
    the raw queries have no ORDER BY, so Postgres may return the sites in a different order on
    each run - and floating-point addition isn't associative, so the last bit (~1e-15 relative)
    legitimately varies between two computations of the same data. Measured on production data:
    most hours differ at exactly that level, none by more than 5e-15 relative."""
    cached = read_cached(engine, start, end)
    raw = pred.load_exogenous_raw(engine, start, end)
    if cached is None:
        return len(raw), len(raw)
    differs = ~np.isclose(cached[FEATURE_COLS].to_numpy(), raw[FEATURE_COLS].to_numpy(),
                          rtol=VERIFY_TOLERANCE, atol=VERIFY_TOLERANCE, equal_nan=True).all(axis=1)
    return len(raw), int(differs.sum())


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def main() -> None:
    parser = argparse.ArgumentParser(description="Maintain the model_features_hourly cache.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--rebuild", action="store_true",
                      help="recompute everything (from --since, or the earliest raw data)")
    mode.add_argument("--verify", action="store_true",
                      help="read-only: compare the last --days of the cache with a fresh recompute")
    parser.add_argument("--since", type=lambda s: pd.Timestamp(s).date(), help="YYYY-MM-DD")
    parser.add_argument("--days", type=int, default=30, help="--verify window, days back from today (default 30)")
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")
    engine = ing.make_engine()
    try:
        if args.verify:
            today, _ = _window_end(None)
            compared, differing = verify(engine, today - timedelta(days=args.days), today - timedelta(hours=1))
            logger.info("verify: %d hours compared, %d differ", compared, differing)
            sys.exit(0 if differing == 0 else 1)
        refresh(engine, since=args.since, full=args.rebuild)
    except Exception:
        logger.exception("Feature cache update FAILED")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
