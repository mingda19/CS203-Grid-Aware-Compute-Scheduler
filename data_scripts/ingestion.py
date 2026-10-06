"""
ingestion.py

Consolidates weather_pull.py (Open-Meteo), eia_pull.py (EIA), and
gridstatus_pull.py (GridStatus DAM price) into one self-contained module that
writes to Postgres/Supabase instead of CSV. Replaces all three.

Design decisions (see chat history for the reasoning):
  - DB access via SQLAlchemy Core (not ORM): one portable upsert_dataframe()
    helper, using "ON CONFLICT DO UPDATE", works against both the real
    Supabase Postgres and the in-memory SQLite used in tests.
  - Weather rows need a real location_id FK, which the old CSV's array-position
    "location_id" was never reconciled with. resolve_location_ids() looks up
    the real id by (latitude, longitude) and raises loudly on an unseeded site
    rather than silently writing a wrong/missing FK.
  - GridStatus DAM price stays LZ_NORTH-only for continuous ingestion: the
    account's 1000-row/month quota can't support all 8 zones daily (~100-125
    rows/zone/day would exhaust a month's quota in about a day). The other 7
    zones only have the one-time historical xlsx backfill.
  - Each source's failure is isolated (one failing dataset/source must not
    stop the others), matching eia_pull.py's existing per-dataset try/except.

Usage:
  python ingestion.py                      # normal daily run, all sources
  python ingestion.py --only weather,eia    # subset
  python ingestion.py --since 2024-01-01    # backfill history (where supported)

Or import: from ingestion import run_ingestion
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

import openmeteo_requests
import pandas as pd
import requests_cache
import sqlalchemy as sa
from retry_requests import retry
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

logger = logging.getLogger("ingestion")

SCRIPT_DIR = Path(__file__).resolve().parent
EXIT_OK, EXIT_FAILED, EXIT_INCOMPLETE = 0, 1, 2


# --------------------------------------------------------------------------- #
# Generic DB helpers
# --------------------------------------------------------------------------- #

def make_engine() -> sa.Engine:
    database_url = os.environ.get("DATABASE_URL")
    if not database_url:
        raise RuntimeError("DATABASE_URL is not set. Export it or put it in a .env file at the repo root.")
    return sa.create_engine(_parse_database_url(database_url))


def _parse_database_url(database_url: str) -> sa.engine.URL:
    """Build a sqlalchemy.engine.URL from the raw components rather than
    handing create_engine() the literal string. Two independent reasons:

    1. Force the psycopg2 driver explicitly: a bare "postgresql://" scheme
       lets SQLAlchemy pick its own default driver, and as of SQLAlchemy 2.1
       that default is psycopg (v3), not psycopg2 - which raises a bare
       ModuleNotFoundError for 'psycopg' even with psycopg2-binary installed,
       since it never falls back. requirements.txt installs psycopg2-binary,
       so pin the dialect to match rather than also requiring the psycopg v3
       package.
    2. The Supabase password contains characters (%, #, *) that aren't valid
       in a bare URL string without percent-encoding, and DATABASE_URL in
       .env isn't percent-encoded (the backend's DotenvLoader.java has the
       same issue, and a raw `psql "$DATABASE_URL"` hits it too). Handing
       create_engine() a string makes it re-parse those characters as URL
       syntax (a literal '#' truncates everything after it as a fragment,
       etc.) - confirmed this actually breaks the hostname in practice.
       URL.create() takes the username/password as plain values instead of a
       string to re-parse, which sidesteps this entirely.
    """
    if database_url.startswith("postgres://"):
        database_url = database_url.replace("postgres://", "postgresql://", 1)
    without_scheme = database_url.split("://", 1)[1]

    user_info, host_info = without_scheme.rsplit("@", 1)
    username, _, password = user_info.partition(":")
    host_port, _, dbname = host_info.partition("/")
    dbname = dbname.split("?", 1)[0] or "postgres"
    host, _, port_str = host_port.partition(":")

    return sa.engine.URL.create(
        "postgresql+psycopg2",
        username=username,
        password=password or None,
        host=host,
        port=int(port_str) if port_str else None,
        database=dbname,
    )


def upsert_dataframe(engine: sa.Engine, table: str, df: pd.DataFrame, key_cols: list[str]) -> tuple[int, int]:
    """INSERT ... ON CONFLICT(key_cols) DO UPDATE the rows of `df` into `table`.
    Returns (rows_added, rows_total_in_table_now). Portable across the
    postgresql and sqlite dialects (both support ON CONFLICT upserts the same way)."""
    if df.empty:
        with engine.begin() as conn:
            total = conn.execute(sa.text(f"SELECT COUNT(*) FROM {table}")).scalar_one()
        return 0, total

    meta = sa.MetaData()
    tbl = sa.Table(table, meta, autoload_with=engine)

    if engine.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert as dialect_insert
    else:
        from sqlalchemy.dialects.sqlite import insert as dialect_insert

    update_cols = [c for c in df.columns if c not in key_cols]

    # pandas Timestamp isn't bindable by sqlite3's raw DBAPI driver (psycopg2 is
    # more forgiving, but this keeps the same code path correct on both engines).
    df = df.copy()
    for col in df.columns:
        if pd.api.types.is_datetime64_any_dtype(df[col]):
            df[col] = df[col].dt.to_pydatetime()

    records = df.to_dict(orient="records")

    with engine.begin() as conn:
        before = conn.execute(sa.text(f"SELECT COUNT(*) FROM {table}")).scalar_one()
        stmt = dialect_insert(tbl).values(records)
        if update_cols:
            stmt = stmt.on_conflict_do_update(
                index_elements=key_cols,
                set_={c: getattr(stmt.excluded, c) for c in update_cols},
            )
        else:
            stmt = stmt.on_conflict_do_nothing(index_elements=key_cols)
        conn.execute(stmt)
        after = conn.execute(sa.text(f"SELECT COUNT(*) FROM {table}")).scalar_one()

    return after - before, after


class LocationNotSeededError(RuntimeError):
    pass


def fetch_locations_by_description(engine: sa.Engine, description: str) -> pd.DataFrame:
    """The `location` table is the source of truth for which coordinates to
    pull each weather dataset at - rows are tagged with a `description`
    ("load", "datacenters", "solar", "wind") matching the dataset that should
    fetch them. This replaces an earlier design that hardcoded lat/lon lists
    in this file and tried to match them back to location_id by exact
    coordinate equality - which broke the moment the location table was
    seeded with its own (different, DB-owned) coordinates, and was fragile by
    construction anyway (float equality across two independently-maintained
    lists). Raises LocationNotSeededError if nothing is tagged with
    `description` yet, rather than silently fetching nothing."""
    query = sa.text("SELECT location_id, latitude, longitude FROM location WHERE description = :description ORDER BY location_id")
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"description": description})
    if df.empty:
        raise LocationNotSeededError(
            f"No location rows with description={description!r}. Seed the location table first."
        )
    return df


# --------------------------------------------------------------------------- #
# Weather (Open-Meteo)
# --------------------------------------------------------------------------- #

HISTORICAL_FORECAST_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast"


@dataclass
class WeatherDataset:
    name: str
    table: str
    description: str  # matches location.description for the rows this dataset should fetch
    hourly_vars: list[str]


WEATHER_DATASETS = [
    WeatherDataset(
        name="load_demand", table="load_data", description="load",
        hourly_vars=["temperature_2m", "dew_point_2m", "apparent_temperature", "relative_humidity_2m", "wind_speed_10m"],
    ),
    WeatherDataset(
        name="datacenters", table="data_centers", description="datacenters",
        hourly_vars=["temperature_2m", "dew_point_2m", "apparent_temperature"],
    ),
    WeatherDataset(
        name="solar", table="solar", description="solar",
        hourly_vars=["cloud_cover", "direct_normal_irradiance", "shortwave_radiation", "temperature_2m"],
    ),
    WeatherDataset(
        # wind_speed/direction_180m/200m dropped: not used (42% null in backfill - see model/model.md),
        # and being dropped from the DB table too.
        name="wind", table="wind", description="wind",
        hourly_vars=["wind_speed_100m", "wind_direction_100m", "wind_gusts_10m", "wind_speed_120m", "wind_speed_80m", "wind_direction_80m", "wind_direction_120m", "temperature_120m", "temperature_1000hPa"],
    ),
]


def make_openmeteo_client() -> openmeteo_requests.Client:
    cache_session = requests_cache.CachedSession(str(SCRIPT_DIR / ".cache"), expire_after=3600)
    retry_session = retry(cache_session, retries=5, backoff_factor=0.2)
    return openmeteo_requests.Client(session=retry_session)


def fetch_weather(client: openmeteo_requests.Client, dataset: WeatherDataset,
                  locations: pd.DataFrame, start_date: str, end_date: str) -> pd.DataFrame:
    """Fetch start_date..end_date for `dataset` at the coordinates in
    `locations` (as returned by fetch_locations_by_description). Rows are
    tagged with locations["location_id"] by response position (Open-Meteo
    returns responses in the same order the coordinates were requested in),
    not by matching coordinates back - the real location_id is already known
    going in, no resolution step needed."""
    params = {
        "latitude": locations["latitude"].tolist(), "longitude": locations["longitude"].tolist(),
        "start_date": start_date, "end_date": end_date, "hourly": dataset.hourly_vars,
    }
    responses = client.weather_api(HISTORICAL_FORECAST_URL, params=params)

    if len(responses) != len(locations):
        raise ValueError(
            f"{dataset.name}: API returned {len(responses)} locations, expected {len(locations)}"
        )

    frames = []
    for i, response in enumerate(responses):
        hourly = response.Hourly()
        data = {
            "location_id": locations["location_id"].iloc[i],
            "time": pd.date_range(
                start=pd.to_datetime(hourly.Time(), unit="s", utc=True),
                end=pd.to_datetime(hourly.TimeEnd(), unit="s", utc=True),
                freq=pd.Timedelta(seconds=hourly.Interval()),
                inclusive="left",
            ).tz_localize(None),
        }
        for j, var in enumerate(dataset.hourly_vars):
            data[var] = hourly.Variables(j).ValuesAsNumpy()
        frames.append(pd.DataFrame(data))

    return pd.concat(frames, ignore_index=True)


def update_weather(engine: sa.Engine, client: openmeteo_requests.Client, dataset: WeatherDataset,
                   start_date: str, end_date: str) -> tuple[int, int]:
    locations = fetch_locations_by_description(engine, dataset.description)
    new = fetch_weather(client, dataset, locations, start_date, end_date)
    cols = ["location_id", "time"] + dataset.hourly_vars
    # Select using Open-Meteo's own variable casing (e.g. "temperature_1000hPa" -
    # the API is case-sensitive about it), then lowercase for the DB column names
    # (e.g. "temperature_1000hpa" - see Wind.java). Same class of bug as the EIA
    # uppercase-fuel-code columns; fixed generically here so it can't recur for
    # any other Open-Meteo variable with mixed-case naming.
    new = new[cols].rename(columns=str.lower)
    return upsert_dataframe(engine, dataset.table, new, ["location_id", "time"])


def update_all_weather(engine: sa.Engine, reference_date: date | None = None) -> None:
    """Fetch yesterday through tomorrow (UTC) for all 4 weather datasets.

    Through tomorrow, not just today, on purpose: predictions.py predicts
    tomorrow's 24 hours (that's what a day-ahead DAM prediction actually is),
    and needs tomorrow's wind/solar to build those features. Open-Meteo's
    historical-forecast endpoint does serve forecast data that far out (same
    endpoint blends historical reanalysis and forecast seamlessly) - confirmed
    the gap was real: without this, wind/solar were only ever populated
    through today, so predicting tomorrow built an empty-dataframe feature
    that crashed downstream with a dtype error (see chat)."""
    client = make_openmeteo_client()
    today = reference_date or datetime.now(timezone.utc).date()
    yesterday = today - timedelta(days=1)
    tomorrow = today + timedelta(days=1)
    for dataset in WEATHER_DATASETS:
        added, total = update_weather(engine, client, dataset, yesterday.isoformat(), tomorrow.isoformat())
        logger.info("[weather:%s] +%d new, %d total in %s", dataset.name, added, total, dataset.table)


# --------------------------------------------------------------------------- #
# EIA
# --------------------------------------------------------------------------- #

EIA_BASE = "https://api.eia.gov/v2"
EIA_PAGE_SIZE = 5000
PRIMARY_FUELS = ["COL", "NG", "NUC", "SUN", "WND", "HYC", "GEO", "PET", "BIO", "OTH"]


def make_eia_session() -> requests_cache.CachedSession:
    if not os.environ.get("EIA_API_KEY"):
        raise RuntimeError("EIA_API_KEY is not set. Export it or put it in a .env file.")
    session = requests_cache.CachedSession(
        SCRIPT_DIR / ".cache", expire_after=3600, ignored_parameters=["api_key"],
    )
    retry_policy = Retry(total=5, backoff_factor=0.5, status_forcelist=[429, 500, 502, 503, 504],
                         allowed_methods=["GET"])
    session.mount("https://", HTTPAdapter(max_retries=retry_policy))
    return session


def eia_fetch_all(session, route: str, params: dict) -> list[dict]:
    api_key = os.environ.get("EIA_API_KEY", "")
    rows, offset = [], 0
    while True:
        resp = session.get(f"{EIA_BASE}/{route}/data/",
                           params={**params, "api_key": api_key, "offset": offset, "length": EIA_PAGE_SIZE},
                           timeout=30)
        resp.raise_for_status()
        body = resp.json()
        if "response" not in body:
            raise RuntimeError(f"EIA error on {route}: {body.get('error', body)}")
        data = body["response"]["data"]
        rows.extend(data)
        if len(data) < EIA_PAGE_SIZE:
            return rows
        offset += EIA_PAGE_SIZE


def fetch_gas_price(session, start: date, end: date) -> pd.DataFrame:
    rows = eia_fetch_all(session, "natural-gas/pri/fut", {
        "frequency": "daily", "data[0]": "value", "facets[series][]": "RNGWHHD",
        "start": start.isoformat(), "end": end.isoformat(),
    })
    if not rows:
        return pd.DataFrame(columns=["period", "henry_hub_price_usd_mmbtu"])
    df = pd.DataFrame(rows)[["period", "value"]].rename(columns={"value": "henry_hub_price_usd_mmbtu"})
    df["period"] = pd.to_datetime(df["period"])
    df["henry_hub_price_usd_mmbtu"] = pd.to_numeric(df["henry_hub_price_usd_mmbtu"], errors="coerce")
    return df


def fetch_generation_mix(session, start: date, end: date) -> pd.DataFrame:
    rows = eia_fetch_all(session, "electricity/electric-power-operational-data", {
        "frequency": "monthly", "data[0]": "generation", "facets[location][]": "US",
        "facets[sectorid][]": "99", "start": start.strftime("%Y-%m"), "end": end.strftime("%Y-%m"),
    })
    if not rows:
        return pd.DataFrame(columns=["period"])
    df = pd.DataFrame(rows)
    df["period"] = pd.to_datetime(df["period"])
    df["generation"] = pd.to_numeric(df["generation"], errors="coerce")
    pivot = df.pivot_table(index="period", columns="fueltypeid", values="generation", aggfunc="sum")
    primary = pivot[[f for f in PRIMARY_FUELS if f in pivot.columns]]
    pct = primary.div(primary.sum(axis=1), axis=0) * 100
    # EIA's fuel codes are uppercase (COL, NG, ...), giving "COL_pct" etc, but the
    # real DB columns are lowercase ("col_pct" - see FuelGenerationMonthly.java) -
    # Postgres case-folds unquoted identifiers, so an uppercase-keyed dict in
    # upsert_dataframe's ON CONFLICT SET clause fails with AttributeError.
    return pct.add_suffix("_pct").reset_index().rename(columns=str.lower)


def fetch_ercot_fuel_mix(session, start: date, end: date) -> pd.DataFrame:
    rows = eia_fetch_all(session, "electricity/rto/fuel-type-data", {
        "frequency": "hourly", "data[0]": "value", "facets[respondent][]": "ERCO",
        "start": f"{start.isoformat()}T00", "end": f"{end.isoformat()}T23",
    })
    if not rows:
        return pd.DataFrame(columns=["period"])
    df = pd.DataFrame(rows)
    df["period"] = pd.to_datetime(df["period"], format="%Y-%m-%dT%H")
    df["value"] = pd.to_numeric(df["value"], errors="coerce")
    mwh = df.pivot_table(index="period", columns="fueltype", values="value", aggfunc="sum")
    positive = mwh.clip(lower=0)  # BAT/pumped-storage can be negative (charging); exclude from the % denominator
    pct = positive.div(positive.sum(axis=1), axis=0) * 100
    # Same fix as fetch_generation_mix: lowercase to match the real DB columns
    # (e.g. "BAT_mwh" -> "bat_mwh" - see FuelMixHourly.java).
    return mwh.add_suffix("_mwh").join(pct.add_suffix("_pct")).reset_index().rename(columns=str.lower)


@dataclass
class EiaDataset:
    name: str
    table: str
    fetch: Callable
    lookback_days: int
    period_col: str = "period"


EIA_DATASETS = [
    EiaDataset("gas_price", "fuel_price", fetch_gas_price, 14),
    EiaDataset("generation_mix", "fuel_generation_monthly", fetch_generation_mix, 183),
    EiaDataset("ercot_fuel_mix", "fuel_pct_hourly", fetch_ercot_fuel_mix, 3),
]


def update_eia_dataset(engine: sa.Engine, ds: EiaDataset, session, since: date | None = None) -> tuple[int, int]:
    today = datetime.now(timezone.utc).date()
    default_start = today - timedelta(days=ds.lookback_days)
    start = min(since, default_start) if since else default_start

    new = ds.fetch(session, start, today)
    value_cols = [c for c in new.columns if c != "period"]
    new = new.dropna(subset=value_cols, how="all") if value_cols else new
    new = new.rename(columns={"period": "period"})  # column is already named `period`; DB column is `period`
    return upsert_dataframe(engine, ds.table, new, ["period"])


def update_all_eia_data(engine: sa.Engine, since: date | None = None, only: list[str] | None = None) -> None:
    session = make_eia_session()
    for ds in EIA_DATASETS:
        if only and ds.name not in only:
            continue
        try:
            added, total = update_eia_dataset(engine, ds, session, since)
            logger.info("[eia:%s] +%d new, %d total in %s", ds.name, added, total, ds.table)
        except Exception:
            logger.exception("[eia:%s] FAILED", ds.name)


# --------------------------------------------------------------------------- #
# GridStatus DAM price
# --------------------------------------------------------------------------- #

DAM_DATASET = "ercot_spp_day_ahead_hourly"
DEFAULT_DAM_LOCATIONS = ["LZ_NORTH"]  # see module docstring: GridStatus quota can't support all 8 zones daily
LOCAL_TZ = "America/Chicago"
DEFAULT_LOOKBACK_DAYS = 3
END_OFFSET_DAYS = 3
PUBLISH_CHECK_HOUR_CT = 14
DEFAULT_MAX_ROWS = 1000


@dataclass
class PullResult:
    fetched: int
    added: int
    total: int
    latest_local: pd.Timestamp | None = None
    next_day_published: bool | None = None
    truncated: bool = False

    @property
    def ok(self) -> bool:
        return self.fetched > 0 and not self.truncated and self.next_day_published is not False


def make_gridstatus_client():
    api_key = os.environ.get("GRIDSTATUS_API_KEY")
    if not api_key:
        raise RuntimeError("GRIDSTATUS_API_KEY is not set. Export it or put it in a .env file.")
    from gridstatusio import GridStatusClient

    return GridStatusClient(api_key=api_key)


def normalize_dam_prices(raw: pd.DataFrame) -> pd.DataFrame:
    cols = ["interval_start_utc", "location", "spp_usd_mwh"]
    if raw.empty:
        return pd.DataFrame(columns=cols)
    missing = [c for c in ["interval_start_utc", "location", "spp"] if c not in raw.columns]
    if missing:
        raise RuntimeError(f"GridStatus response missing {missing}; got {list(raw.columns)}. Schema changed?")

    utc = pd.to_datetime(raw["interval_start_utc"], utc=True).dt.tz_localize(None)
    return pd.DataFrame({
        "interval_start_utc": utc,
        "location": raw["location"].astype(str),
        "spp_usd_mwh": pd.to_numeric(raw["spp"], errors="coerce"),
    })[cols]


def fetch_dam_prices(client, start: date, end: date, location: str, max_rows: int = DEFAULT_MAX_ROWS) -> pd.DataFrame:
    raw = client.get_dataset(
        dataset=DAM_DATASET, start=start.isoformat(), end=end.isoformat(),
        filter_column="location", filter_value=location,
        columns=["interval_start_utc", "location", "spp"], limit=max_rows, verbose=False,
    )
    return normalize_dam_prices(raw)


def _dam_fetch_window(now: datetime, since: date | None = None) -> tuple[date, date]:
    today = now.astimezone(timezone.utc).date()
    default_start = today - timedelta(days=DEFAULT_LOOKBACK_DAYS)
    start = min(since, default_start) if since else default_start
    return start, today + timedelta(days=END_OFFSET_DAYS)


def update_dam_prices(engine: sa.Engine, client, location: str = DEFAULT_DAM_LOCATIONS[0],
                      since: date | None = None, now: datetime | None = None,
                      max_rows: int = DEFAULT_MAX_ROWS) -> PullResult:
    now = now or datetime.now(timezone.utc)
    start, end = _dam_fetch_window(now, since)

    new = fetch_dam_prices(client, start, end, location, max_rows)
    priced = new.dropna(subset=["spp_usd_mwh"])
    added, total = upsert_dataframe(engine, "electrical_price", priced, ["interval_start_utc", "location"])

    latest = None
    next_day_published = None
    if not priced.empty:
        latest_utc = priced["interval_start_utc"].max()
        latest = pd.Timestamp(latest_utc, tz="UTC").tz_convert(LOCAL_TZ)
        now_ct = pd.Timestamp(now).tz_convert(LOCAL_TZ)
        if now_ct.hour >= PUBLISH_CHECK_HOUR_CT:
            tomorrow = now_ct.date() + timedelta(days=1)
            next_day_published = latest.date() >= tomorrow

    return PullResult(fetched=len(priced), added=added, total=total,
                      latest_local=latest, next_day_published=next_day_published,
                      truncated=len(new) >= max_rows)


def update_all_dam_prices(engine: sa.Engine, since: date | None = None) -> None:
    client = make_gridstatus_client()
    for location in DEFAULT_DAM_LOCATIONS:
        result = update_dam_prices(engine, client, location=location, since=since)
        logger.info("[dam:%s] fetched %d, +%d new, %d total, ok=%s",
                    location, result.fetched, result.added, result.total, result.ok)


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #

@dataclass
class IngestionResult:
    weather_ok: bool = True
    eia_ok: bool = True
    dam_ok: bool = True
    errors: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.weather_ok and self.eia_ok and self.dam_ok


def run_ingestion(engine: sa.Engine, weather_fn: Callable | None = None, eia_fn: Callable | None = None,
                  dam_fn: Callable | None = None, now: datetime | None = None) -> IngestionResult:
    weather_fn = weather_fn or (lambda eng: update_all_weather(eng))
    eia_fn = eia_fn or (lambda eng: update_all_eia_data(eng))
    dam_fn = dam_fn or (lambda eng: update_all_dam_prices(eng))

    result = IngestionResult()
    for name, fn, attr in [("weather", weather_fn, "weather_ok"), ("eia", eia_fn, "eia_ok"),
                           ("dam", dam_fn, "dam_ok")]:
        try:
            fn(engine)
        except Exception as e:  # one source failing must not stop the others
            logger.exception("[%s] FAILED", name)
            setattr(result, attr, False)
            result.errors[name] = str(e)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Daily ingestion: Open-Meteo weather + EIA + GridStatus DAM price.")
    parser.add_argument("--since", type=date.fromisoformat, help="backfill from YYYY-MM-DD (where supported)")
    parser.add_argument("--only", help="comma-separated: weather,eia,dam")
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    only = set(args.only.split(",")) if args.only else {"weather", "eia", "dam"}
    engine = make_engine()

    try:
        result = run_ingestion(
            engine,
            weather_fn=(lambda eng: update_all_weather(eng)) if "weather" in only else (lambda eng: None),
            eia_fn=(lambda eng: update_all_eia_data(eng, since=args.since)) if "eia" in only else (lambda eng: None),
            dam_fn=(lambda eng: update_all_dam_prices(eng, since=args.since)) if "dam" in only else (lambda eng: None),
        )
    except Exception:
        logger.exception("Ingestion FAILED")
        sys.exit(EXIT_FAILED)

    sys.exit(EXIT_OK if result.ok else EXIT_INCOMPLETE)


if __name__ == "__main__":
    main()
