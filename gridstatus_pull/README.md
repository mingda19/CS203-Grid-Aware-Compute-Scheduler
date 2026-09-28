# gridstatus_pull

Pulls **ERCOT day-ahead market (DAM) hourly prices** for the **LZ_NORTH** load zone from
[GridStatus.io](https://www.gridstatus.io) and saves them to a local CSV.

It follows the same conventions as `eia_pull/` (EIA) and `data_scripts/` (Open-Meteo): a
Python script that pulls from an API and merges into a CSV under `data/`, which is gitignored.
Loading data into Postgres is the backend's job; this script only produces the data and the schema.

## Quick start

Run these from the repo root.

```bash
pip install -r gridstatus_pull/requirements.txt
python gridstatus_pull/gridstatus_pull.py
```

Before the first run you need a `.env` file at the **repo root** containing your key
(`GRIDSTATUS_API_KEY=...`). If you don't have a `.env` yet, run `cp .env.example .env` and fill
it in (careful: this overwrites an existing `.env`). Get a key from your GridStatus.io account
settings. `.env` is gitignored, so it is never committed.

Output: `data/GridStatus/ercot_dam_lz_north.csv`

## What you get

One row per hour. Unique key: `(interval_start_utc, location)`.

| Column | Meaning |
|---|---|
| `interval_start_utc` | Hour start in **UTC**. Use this to join with EIA / weather data. |
| `interval_start_local` | Same instant in Central time (America/Chicago), for display only. |
| `location` | Settlement point, e.g. `LZ_NORTH`. |
| `spp_usd_mwh` | Day-ahead settlement point price in $/MWh. Can be negative. |

Timestamps are UTC on purpose: ERCOT publishes in Central time, and on the day clocks go back
there are two 01:00 hours, so local time is not a safe key.

## How the day-ahead market works (why it runs once a day)

The DAM is an auction that runs **once a day**. Bids close at 10:00 Central, and ERCOT posts all
24 hourly prices for the **next** day by 1:30 PM Central (~18:30 UTC in summer, ~19:30 UTC in
winter). These are cleared prices, not a forecast, and nothing changes during the day, so one run
per day after publication is enough.

A pull at 3 PM on the 23rd therefore contains the 23rd (published yesterday) and the 24th (new).

## What a run does

1. Asks GridStatus for 3 days back through tomorrow (`LZ_NORTH` only), capped at 1000 rows.
   The look-back catches corrections and covers a missed day. The default window is ~100-125 rows.
2. Drops rows without a price.
3. Merges into the CSV, de-duplicating on the key and keeping the newest row.
4. Writes via a temp file, so a crash can't leave a half-written CSV.
5. Logs the result and checks that tomorrow's prices arrived.

## Options

```bash
python gridstatus_pull/gridstatus_pull.py                       # normal daily run
python gridstatus_pull/gridstatus_pull.py --month               # from the 1st of this month
python gridstatus_pull/gridstatus_pull.py --since 2020-09-24 --max-rows 60000   # history backfill
python gridstatus_pull/gridstatus_pull.py --location LZ_HOUSTON # another zone
```

## Reading the log and exit code

```
INFO Pull succeeded: fetched 101 rows, 101 new, 101 in CSV -> data\GridStatus\ercot_dam_lz_north.csv
INFO Latest interval received: 2026-09-23 23:00:00-05:00 (Central)
```

| Exit code | Meaning | What to do |
|---|---|---|
| `0` | Success. | Nothing. |
| `1` | The pull **failed** (missing/bad key, network, GridStatus changed its columns). | Read the error in the log. |
| `2` | It worked but the data is **incomplete**: no rows, hit the row cap, or it's after 2:00 PM Central and tomorrow's prices are still missing. | Re-run a bit later, or raise `--max-rows` if you meant to pull more. |

## API quota

GridStatus limits rows per month, so each run is capped at **1000 rows** (`--max-rows`).
A normal run uses about 100-125. Only raise the cap on purpose, for example a one-time history load
(6 years of one zone is about 52,600 rows). There is deliberately no request cache: a cached answer
from before ERCOT publishes would hide the new prices.

## Sharing the data

Nobody commits data files in this repo (`data/` is gitignored). To hand a CSV to a teammate, send the
file directly (chat or shared drive) along with the schema above. For the database, load
`interval_start_utc` as a timestamp with time zone, and skip `interval_start_local`, since it can be
derived. A primary key of `(location, interval_start_utc)` suits "prices for years X to Y" queries.

## Tests

The tests run offline with a fake client, so no API key is needed.

```bash
cd gridstatus_pull
pip install -r requirements-dev.txt
pytest
```

## Files

```
gridstatus_pull/
  gridstatus_pull.py      the script (fetch_dam_prices() returns the table; update_dam_prices() runs a full pull)
  requirements.txt        runtime dependencies
  requirements-dev.txt    + pytest
  pytest.ini
  tests/test_gridstatus_pull.py
```

## Troubleshooting

- **`GRIDSTATUS_API_KEY is not set`**: create `.env` at the repo root (see Quick start).
- **`no rows returned for location=...`**: check the location spelling (`LZ_NORTH`, with an underscore).
- **Exit code 2 after 2 PM Central with "NOT there yet"**: prices for tomorrow haven't been posted or
  ingested yet. Re-run in a while.
- **`missing [...] ... Has the dataset schema changed?`**: GridStatus renamed a column. The script
  expects `interval_start_utc`, `location` and `spp` from dataset `ercot_spp_day_ahead_hourly`.
