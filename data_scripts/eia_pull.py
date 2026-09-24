"""
eia_pull.py

Pulls data from the EIA API v2 and merges it into CSVs under data/EIA/.
Mirrors update_weather_data.py (Open-Meteo) so both sources update the same way.

Datasets
--------
| name            | file                          | freq    | route                                          |
|-----------------|-------------------------------|---------|------------------------------------------------|
| gas_price       | natural_gas_prices.csv        | daily   | natural-gas/pri/fut (Henry Hub, RNGWHHD)       |
| generation_mix  | electricity_generation.csv    | monthly | electricity/electric-power-operational-data    |
| ercot_fuel_mix  | ercot_fuel_mix_hourly.csv     | hourly  | electricity/rto/fuel-type-data (ERCO)          |

For each dataset, update_dataset():
  1. Works out a start date (default lookback per dataset, or --since/--month).
  2. Fetches that window from EIA, paginating 5,000 rows at a time.
  3. Drops rows that are entirely empty, so a bad fetch can't blank out good data.
  4. Merges with the existing CSV, dedupes on `period` keeping the NEW row
     (EIA revises recent data, so newer is better), sorts by period.
  5. Writes to a temp file and swaps it in, so a crash can't leave a half-written CSV.
  If the CSV doesn't exist yet, it's created.

Usage
-----
  python eia_pull.py                      # default recent window per dataset
  python eia_pull.py --month              # from the 1st of this month
  python eia_pull.py --since 2019-01-01   # backfill history
  python eia_pull.py --only gas_price,generation_mix

Or import: from eia_pull import update_all_eia_data

Default lookback (why it isn't just "yesterday and today" like Open-Meteo)
--------------------------------------------------------------------------
  gas_price       14 days   - published with a lag of several days, trading days only
  generation_mix  6 months  - ~2-3 month release lag, and EIA revises recent months
  ercot_fuel_mix  3 days    - ~1 day lag

Notes
-----
  - Requires EIA_API_KEY in the environment (or a .env file).
  - Timestamps: hourly `period` is UTC, same as Open-Meteo's default. Daily and
    monthly periods are calendar dates. Check this before joining with ERCOT data,
    which ERCOT itself publishes in Central Time.
  - Unlike Open-Meteo, EIA never returns future hours, so the "future NaN
    overwrites real values" problem doesn't apply here. Empty rows are dropped
    anyway as a guard.
  - API calls are cached for 1 hour (requests-cache, key stripped from the cache)
    and retried up to 5 times with backoff.
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

import pandas as pd
import requests_cache
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

BASE = "https://api.eia.gov/v2"
PAGE_SIZE = 5000
DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "EIA"

# Top-level, mutually exclusive fuel categories for the monthly route.
# The raw route mixes these with nested subtotals (ALL, FOS, REN, COW...) and
# sub-components (RFO, DFO, WWW, WAS, SPV...). Summing everything double-counts.
PRIMARY_FUELS = ["COL", "NG", "NUC", "SUN", "WND", "HYC", "GEO", "PET", "BIO", "OTH"]

# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #

def make_session() -> requests_cache.CachedSession:
    session = requests_cache.CachedSession(
        Path(__file__).resolve().parent / ".cache",  # -> data_scripts/.cache.sqlite (gitignored)
        expire_after=3600,
        ignored_parameters=["api_key"],  # keeps the key out of the cache file
    )
    retry = Retry(
        total=5,
        backoff_factor=0.5,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["GET"],
    )
    session.mount("https://", HTTPAdapter(max_retries=retry))
    return session


def fetch_all(session, route: str, params: dict) -> list[dict]:
    """Paginate through an EIA v2 route and return every row."""
    api_key = os.environ.get("EIA_API_KEY")
    if not api_key:
        sys.exit("EIA_API_KEY is not set. Export it or put it in a .env file.")

    rows, offset = [], 0
    while True:
        resp = session.get(
            f"{BASE}/{route}/data/",
            params={**params, "api_key": api_key, "offset": offset, "length": PAGE_SIZE},
            timeout=30,
        )
        resp.raise_for_status()
        body = resp.json()
        if "response" not in body:
            raise RuntimeError(f"EIA error on {route}: {body.get('error', body)}")

        data = body["response"]["data"]
        rows.extend(data)
        if len(data) < PAGE_SIZE:
            return rows
        offset += PAGE_SIZE


# --------------------------------------------------------------------------- #
# Fetchers: each returns a DataFrame with a `period` column plus values
# --------------------------------------------------------------------------- #

def fetch_gas_price(session, start: date, end: date) -> pd.DataFrame:
    rows = fetch_all(session, "natural-gas/pri/fut", {
        "frequency": "daily",
        "data[0]": "value",
        "facets[series][]": "RNGWHHD",
        "start": start.isoformat(),
        "end": end.isoformat(),
    })
    if not rows:
        return pd.DataFrame(columns=["period", "henry_hub_price_usd_mmbtu"])
    df = pd.DataFrame(rows)[["period", "value"]]
    df = df.rename(columns={"value": "henry_hub_price_usd_mmbtu"})
    df["period"] = pd.to_datetime(df["period"])
    df["henry_hub_price_usd_mmbtu"] = pd.to_numeric(df["henry_hub_price_usd_mmbtu"], errors="coerce")
    return df


def fetch_generation_mix(session, start: date, end: date) -> pd.DataFrame:
    rows = fetch_all(session, "electricity/electric-power-operational-data", {
        "frequency": "monthly",
        "data[0]": "generation",
        "facets[location][]": "US",
        "facets[sectorid][]": "99",  # all sectors; avoids summing sector splits twice
        "start": start.strftime("%Y-%m"),
        "end": end.strftime("%Y-%m"),
    })
    if not rows:
        return pd.DataFrame(columns=["period"])
    df = pd.DataFrame(rows)
    df["period"] = pd.to_datetime(df["period"])
    df["generation"] = pd.to_numeric(df["generation"], errors="coerce")

    pivot = df.pivot_table(index="period", columns="fueltypeid", values="generation", aggfunc="sum")
    primary = pivot[[f for f in PRIMARY_FUELS if f in pivot.columns]]
    pct = primary.div(primary.sum(axis=1), axis=0) * 100
    return pct.add_suffix("_pct").reset_index()


def _hourly_params(start: date, end: date) -> dict:
    return {
        "frequency": "hourly",  # UTC. Use "local-hour" for Central Time instead.
        "data[0]": "value",
        "facets[respondent][]": "ERCO",
        "start": f"{start.isoformat()}T00",
        "end": f"{end.isoformat()}T23",
    }


def fetch_ercot_fuel_mix(session, start: date, end: date) -> pd.DataFrame:
    rows = fetch_all(session, "electricity/rto/fuel-type-data", _hourly_params(start, end))
    if not rows:
        return pd.DataFrame(columns=["period"])
    df = pd.DataFrame(rows)
    df["period"] = pd.to_datetime(df["period"], format="%Y-%m-%dT%H")
    df["value"] = pd.to_numeric(df["value"], errors="coerce")

    mwh = df.pivot_table(index="period", columns="fueltype", values="value", aggfunc="sum")
    # Battery / pumped storage can be negative (charging). Exclude negatives from
    # the share denominator so % reflects generation, not net flow.
    positive = mwh.clip(lower=0)
    pct = positive.div(positive.sum(axis=1), axis=0) * 100
    return mwh.add_suffix("_mwh").join(pct.add_suffix("_pct")).reset_index()


# --------------------------------------------------------------------------- #
# Dataset registry
# --------------------------------------------------------------------------- #

@dataclass
class Dataset:
    name: str
    filename: str
    fetch: Callable
    lookback_days: int


DATASETS = [
    Dataset("gas_price", "natural_gas_prices.csv", fetch_gas_price, 14),
    Dataset("generation_mix", "electricity_generation.csv", fetch_generation_mix, 183),
    Dataset("ercot_fuel_mix", "ercot_fuel_mix_hourly.csv", fetch_ercot_fuel_mix, 3),
]


# --------------------------------------------------------------------------- #
# Merge + write
# --------------------------------------------------------------------------- #

def merge_into_csv(path: Path, new: pd.DataFrame) -> tuple[int, int]:
    """Merge `new` into the CSV at `path`. Returns (rows_added, total_rows)."""
    value_cols = [c for c in new.columns if c != "period"]
    new = new.dropna(subset=value_cols, how="all")

    if path.exists():
        old = pd.read_csv(path, parse_dates=["period"])
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        old = pd.DataFrame(columns=new.columns)

    # Keep existing column order; append any new columns (e.g. a new fuel type).
    columns = list(old.columns) + [c for c in new.columns if c not in old.columns]
    frames = [f for f in (old, new) if not f.empty]
    combined = pd.concat(frames, ignore_index=True) if frames else new
    combined = (
        combined.reindex(columns=columns)
        .drop_duplicates(subset="period", keep="last")
        .sort_values("period")
    )

    tmp = path.with_suffix(".csv.tmp")
    combined.to_csv(tmp, index=False)
    tmp.replace(path)
    return len(combined) - len(old), len(combined)


def update_dataset(ds: Dataset, session, since: date | None = None) -> None:
    today = datetime.now(timezone.utc).date()
    default_start = today - timedelta(days=ds.lookback_days)
    # --since only ever widens the window; it never shrinks a dataset's lookback.
    start = min(since, default_start) if since else default_start

    print(f"[{ds.name}] fetching {start} -> {today}")
    new = ds.fetch(session, start, today)
    path = DATA_DIR / ds.filename
    added, total = merge_into_csv(path, new)

    if total:
        df = pd.read_csv(path, usecols=["period"], parse_dates=["period"])
        span = f"{df.period.min():%Y-%m-%d} -> {df.period.max():%Y-%m-%d %H:%M}"
    else:
        span = "empty"
    print(f"[{ds.name}] fetched {len(new)}, +{added} new, {total} total ({span}) -> {path}")


def update_all_eia_data(since: date | None = None, only: list[str] | None = None) -> None:
    session = make_session()
    for ds in DATASETS:
        if only and ds.name not in only:
            continue
        try:
            update_dataset(ds, session, since)
        except Exception as e:  # one dataset failing shouldn't stop the others
            print(f"[{ds.name}] FAILED: {type(e).__name__}: {e}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Update EIA CSVs under data/EIA/.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--month", action="store_true", help="backfill from the 1st of this month")
    group.add_argument("--since", type=date.fromisoformat, help="backfill from YYYY-MM-DD")
    parser.add_argument("--only", help="comma-separated dataset names: "
                        + ",".join(d.name for d in DATASETS))
    args = parser.parse_args()

    since = args.since
    if args.month:
        since = datetime.now(timezone.utc).date().replace(day=1)
    only = args.only.split(",") if args.only else None

    update_all_eia_data(since=since, only=only)


if __name__ == "__main__":
    main()