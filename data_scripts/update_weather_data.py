"""Fetch today's and yesterday's weather data from Open-Meteo and merge it into
the four existing weather CSVs (load demand, datacenters, solar, wind).

Replaces load_weather.py, datacenter_weather.py, solar_weather.py and
wind_weather.py, which only fetched and printed data without saving it.

Usage:
    python update_weather_data.py

Or from another script / notebook:
    from update_weather_data import update_all_weather_data
    update_all_weather_data()

This is call-triggered only (not scheduled/autonomous). Each call re-fetches
today and yesterday and overwrites any existing rows for those two dates, so
it's safe to call more than once per day.
"""

from __future__ import annotations

import argparse
import datetime as dt
from dataclasses import dataclass
from pathlib import Path

import openmeteo_requests
import pandas as pd
import requests_cache
from retry_requests import retry

SCRIPT_DIR = Path(__file__).resolve().parent
HISTORICAL_FORECAST_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast"

# Unit suffixes used in the CSV column headers, keyed by Open-Meteo variable name.
UNITS = {
    "temperature_2m": "°C",
    "dew_point_2m": "°C",
    "apparent_temperature": "°C",
    "relative_humidity_2m": "%",
    "wind_speed_10m": "km/h",
    "cloud_cover": "%",
    "direct_normal_irradiance": "W/m²",
    "shortwave_radiation": "W/m²",
    "wind_speed_100m": "km/h",
    "wind_direction_100m": "°",
    "wind_gusts_10m": "km/h",
    "wind_speed_120m": "km/h",
    "wind_speed_80m": "km/h",
    "wind_speed_180m": "km/h",
    "wind_speed_200m": "km/h",
    "wind_direction_80m": "°",
    "wind_direction_120m": "°",
    "wind_direction_180m": "°",
    "wind_direction_200m": "°",
    "temperature_120m": "°C",
    "temperature_1000hPa": "°C",
}


@dataclass
class WeatherDataset:
    name: str
    csv_filename: str
    latitudes: list[float]
    longitudes: list[float]
    hourly_vars: list[str]

    @property
    def csv_path(self) -> Path:
        return SCRIPT_DIR / self.csv_filename


DATASETS = [
    WeatherDataset(
        name="load_demand",
        csv_filename="../data/OpenMeteo/Open-meteo-load_demand.csv",
        latitudes=[32.7766642, 32.7554883, 32.735687, 33.0216577, 32.8136151, 32.9136363, 33.1496718, 33.1983388, 32.7459645, 32.7667955, 32.9756415, 33.2165858, 32.9483335, 33.0463292, 33.1031744, 32.3843761, 32.3476438, 32.7592955, 33.6356618, 32.0954304, 33.9137085],
        longitudes=[-96.7969879, -97.3307658, -97.1080656, -96.6979973, -96.9546899, -96.6364294, -96.835567, -96.6389342, -96.9977846, -96.5991593, -96.8899636, -97.1324105, -96.7298519, -96.9941903, -96.6705503, -96.8499694, -97.3866837, -97.7972544, -96.6088805, -96.4688727, -98.4933873],
        hourly_vars=["temperature_2m", "dew_point_2m", "apparent_temperature", "relative_humidity_2m", "wind_speed_10m"],
    ),
    WeatherDataset(
        name="datacenters",
        csv_filename="../data/OpenMeteo/Open-meteo-datacenters.csv",
        latitudes=[32.8007032, 32.9815, 32.9136363, 32.9999663, 32.475335, 32.5332101, 32.3293111],
        longitudes=[-96.8191922, -96.6985, -96.6364294, -97.2925405, -97.0103181, -96.8153, -96.6252679],
        hourly_vars=["temperature_2m", "dew_point_2m", "apparent_temperature"],
    ),
    WeatherDataset(
        name="solar",
        csv_filename="../data/OpenMeteo/Open-meteo-solar.csv",
        latitudes=[32.0991878, 32.0621768, 32.2619765, 32.4133566, 32.6494596, 33.1967938, 33.5544296, 33.75694, 34.5358942, 31.1850631, 29.025893, 31.4600293, 29.2683783, 33.3962182],
        longitudes=[-96.4929797, -97.179026, -96.8350999, -97.3516558, -96.3226072, -96.1526985, -96.1526985, -95.6457951, -101.7585159, -102.1750439, -96.238651, -96.8568489, -98.0465185, -95.2308142],
        hourly_vars=["cloud_cover", "direct_normal_irradiance", "shortwave_radiation", "temperature_2m"],
    ),
    WeatherDataset(
        name="wind",
        csv_filename="../data/OpenMeteo/Open-meteo-wind.csv",
        latitudes=[33.2560382, 33.2200087, 33.5948302, 33.7900416, 33.6460993, 33.6672185, 33.9309651, 32.7074673, 32.1793404, 32.3436673, 32.0440287, 31.9200245, 32.3011042, 30.89033, 31.2089984, 30.9047591, 26.3582862],
        longitudes=[-98.2212979, -98.7481167, -98.625139, -98.2212979, -97.6982272, -97.3516558, -98.7481167, -98.3964938, -98.2212979, -100.4500575, -100.1100942, -100.9369401, -100.113438, -102.3998415, -102.2414915, -102.0826214, -97.6733997],
        hourly_vars=["wind_speed_100m", "wind_direction_100m", "wind_gusts_10m", "wind_speed_120m", "wind_speed_80m", "wind_speed_180m", "wind_speed_200m", "wind_direction_80m", "wind_direction_120m", "wind_direction_180m", "wind_direction_200m", "temperature_120m", "temperature_1000hPa"],
    ),
]


def _make_client() -> openmeteo_requests.Client:
    cache_session = requests_cache.CachedSession(str(SCRIPT_DIR / ".cache"), expire_after=3600)
    retry_session = retry(cache_session, retries=5, backoff_factor=0.2)
    return openmeteo_requests.Client(session=retry_session)


def _find_blank_line(csv_path: Path) -> int:
    """Return the 0-indexed line number of the blank line separating the
    location metadata block from the hourly data block."""
    with open(csv_path, "r") as f:
        for i, line in enumerate(f):
            if line.strip() == "":
                return i
    raise ValueError(f"{csv_path}: no blank line found separating metadata from hourly data")


def _read_metadata_block(csv_path: Path, blank_idx: int) -> list[str]:
    with open(csv_path, "r") as f:
        return [next(f) for _ in range(blank_idx)]


def _read_existing_hourly(dataset: WeatherDataset, blank_idx: int) -> pd.DataFrame:
    df = pd.read_csv(dataset.csv_path, skiprows=blank_idx + 1)
    rename_map = {f"{var} ({UNITS[var]})": var for var in dataset.hourly_vars}
    return df.rename(columns=rename_map)[["location_id", "time"] + dataset.hourly_vars]


def _fetch_new_hourly(
    client: openmeteo_requests.Client,
    dataset: WeatherDataset,
    start_date: str,
    end_date: str,
    expected_locations: int,
) -> pd.DataFrame:
    params = {
        "latitude": dataset.latitudes,
        "longitude": dataset.longitudes,
        "start_date": start_date,
        "end_date": end_date,
        "hourly": dataset.hourly_vars,
    }
    responses = client.weather_api(HISTORICAL_FORECAST_URL, params=params)

    if len(responses) != expected_locations:
        raise ValueError(
            f"{dataset.name}: API returned {len(responses)} locations, "
            f"expected {expected_locations} (existing CSV location count)"
        )

    frames = []
    for location_id, response in enumerate(responses):
        hourly = response.Hourly()
        data = {
            "location_id": location_id,
            "time": pd.date_range(
                start=pd.to_datetime(hourly.Time(), unit="s", utc=True),
                end=pd.to_datetime(hourly.TimeEnd(), unit="s", utc=True),
                freq=pd.Timedelta(seconds=hourly.Interval()),
                inclusive="left",
            ).strftime("%Y-%m-%dT%H:%M"),
        }
        for i, var in enumerate(dataset.hourly_vars):
            data[var] = hourly.Variables(i).ValuesAsNumpy()
        frames.append(pd.DataFrame(data))

    return pd.concat(frames, ignore_index=True)


def update_dataset(client: openmeteo_requests.Client, dataset: WeatherDataset, start_date: str, end_date: str) -> int:
    """Fetch start_date..end_date for `dataset` and merge into its CSV.
    Returns the number of rows in the merged file after the update."""

    blank_idx = _find_blank_line(dataset.csv_path)
    metadata_lines = _read_metadata_block(dataset.csv_path, blank_idx)
    expected_locations = len(metadata_lines) - 1  # minus the header row

    existing_hourly = _read_existing_hourly(dataset, blank_idx)
    new_hourly = _fetch_new_hourly(client, dataset, start_date, end_date, expected_locations)

    merged = (
        pd.concat([existing_hourly, new_hourly], ignore_index=True)
        .drop_duplicates(subset=["location_id", "time"], keep="last")
        .sort_values(["location_id", "time"])
        .reset_index(drop=True)
    )

    hourly_header = "location_id,time," + ",".join(f"{var} ({UNITS[var]})" for var in dataset.hourly_vars)

    with open(dataset.csv_path, "w", newline="") as f:
        f.writelines(metadata_lines)
        f.write("\n")
        f.write(hourly_header + "\n")
        merged.to_csv(f, index=False, header=False, na_rep="NaN", lineterminator="\n")

    return len(merged)


def _update_range(start_date: str, end_date: str) -> None:
    client = _make_client()
    for dataset in DATASETS:
        row_count = update_dataset(client, dataset, start_date, end_date)
        print(f"  {dataset.name}: merged, {row_count} total hourly rows in {dataset.csv_filename}")


def update_all_weather_data(reference_date: dt.date | None = None) -> None:
    """Fetch today's and yesterday's data (UTC) for all 4 datasets and merge
    each into its CSV. Pass `reference_date` to override "today" (mainly for
    testing)."""

    today = reference_date or dt.datetime.now(dt.timezone.utc).date()
    yesterday = today - dt.timedelta(days=1)
    start_date, end_date = yesterday.isoformat(), today.isoformat()

    print(f"Updating weather data for {start_date} to {end_date} (UTC)")
    _update_range(start_date, end_date)


def backfill_current_month(reference_date: dt.date | None = None) -> None:
    """Backfill the 1st of the current month through today (UTC) for all 4
    datasets and merge each into its CSV. Safe to re-run: merges/dedupes the
    same way update_all_weather_data does."""

    today = reference_date or dt.datetime.now(dt.timezone.utc).date()
    start_date = today.replace(day=1).isoformat()
    end_date = today.isoformat()

    print(f"Backfilling weather data for {start_date} to {end_date} (UTC)")
    _update_range(start_date, end_date)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Fetch Open-Meteo weather data and merge it into the CSVs.")
    parser.add_argument(
        "--month",
        action="store_true",
        help="Backfill the entire current month (1st through today) instead of just today/yesterday.",
    )
    args = parser.parse_args()

    if args.month:
        backfill_current_month()
    else:
        update_all_weather_data()
