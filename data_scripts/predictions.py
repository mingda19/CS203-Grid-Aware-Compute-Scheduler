"""
predictions.py

Feature engineering + model serving for LZ_NORTH DAM price. Ports the
imputation/power-proxy/calendar logic validated in the EDA phase (see
model/wind_solar_power_features.py, model/wind_shear_veer_turbulence.py,
model/build_master_table.py - all being pruned; this is now the only place
that logic survives) and adds the new prediction-writing layer.

Reuses ingestion.upsert_dataframe for the DB write rather than duplicating it
(the two modules are "self contained" with respect to the pruned EDA scripts,
not with respect to each other).

Known gap, not yet solved here (flagging rather than silently glossing over
it): the model was trained on a price target winsorized at a FIXED 4-std band
computed once from the full 2020-2026 training history (mean=56.35,
std=362.55 -> band [-1393.85, 1506.55] - see model/wind_shear_veer_turbulence.py).
Live price-lag features must be clipped to that SAME fixed band, not a band
recomputed from a small recent window (which would drift from what the model
was trained on). WINSORIZE_LOWER/WINSORIZE_UPPER below hardcode that band;
if the model is ever retrained on a different window, these must be updated
together with it, or predictions will be computed on a different scale than
the model expects.

Separately: the imputation functions exist to backfill a HISTORICAL gap
(Open-Meteo didn't serve wind_speed_120m etc. before 2021-03-23) - live
Open-Meteo data has no such gap, so at prediction time they are expected to be
no-ops (each has an `if not missing.any(): continue` guard). They're still run
defensively, in case a live fetch has a transient gap for a specific hour.

Usage:
  python predictions.py --date 2026-03-02   # predict the 24 hours of that UTC date
  python predictions.py                     # predict tomorrow (UTC), the normal daily run

Or import: from predictions import run_predictions
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import holidays
import numpy as np
import pandas as pd
import sqlalchemy as sa
import xgboost as xgb

import ingestion as ing

logger = logging.getLogger("predictions")

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_MODEL_PATH = SCRIPT_DIR.parent / "model" / "models" / "xgboost_baseline_full.json"
DEFAULT_MODEL_VERSION = "xgboost_baseline_full"
DEFAULT_LOCATION = "LZ_NORTH"

# Fixed winsorization band the model was trained against - see module docstring.
WINSORIZE_LOWER = -1393.85
WINSORIZE_UPPER = 1506.55

# Physical / assumed constants (same as model/wind_solar_power_features.py - no
# real farm metadata exists, see model/model.md S3.5).
STANDARD_PRESSURE_PA = 101_325.0
R_SPECIFIC_DRY_AIR = 287.05
CUT_IN_MS = 3.0
RATED_MS = 12.5
CUT_OUT_MS = 25.0
NOCT_C = 45.0
GAMMA_PER_C = 0.004

FEATURE_COLUMNS = [
    "lag_24h", "lag_48h", "roll_mean_24h", "roll_std_24h", "roll_mean_168h", "roll_std_168h",
    "hour", "day_of_week", "month", "is_us_holiday",
    "henry_hub_price_usd_mmbtu_lag1d", "demand_forecast_mwh",
    "wind_power_output_proxy_total", "solar_power_output_proxy_total",
]


# --------------------------------------------------------------------------- #
# Imputation (ported from model/wind_solar_power_features.py, model/wind_shear_veer_turbulence.py)
# --------------------------------------------------------------------------- #

def impute_speed_loglog(df: pd.DataFrame, target_col: str, ref_col: str) -> pd.Series:
    out = df[target_col].copy()
    for loc_id, g in df.groupby("location_id"):
        missing = g[target_col].isna()
        if not missing.any():
            continue
        known = ~missing
        x_known = g.loc[known, ref_col].clip(lower=0.01)
        y_known = g.loc[known, target_col].clip(lower=0.01)
        slope, intercept = np.polyfit(np.log(x_known), np.log(y_known), 1)
        x_missing = g.loc[missing, ref_col].clip(lower=0.01)
        pred = np.exp(intercept) * (x_missing ** slope)
        out.loc[g.loc[missing].index] = pred.values
    return out


def impute_direction_veer(df: pd.DataFrame, target_col: str, ref_col: str) -> pd.Series:
    out = df[target_col].copy()
    for loc_id, g in df.groupby("location_id"):
        missing = g[target_col].isna()
        if not missing.any():
            continue
        known = ~missing
        diff = (g.loc[known, target_col] - g.loc[known, ref_col] + 180) % 360 - 180
        mean_veer = np.degrees(np.arctan2(np.sin(np.radians(diff)).mean(), np.cos(np.radians(diff)).mean()))
        pred = (g.loc[missing, ref_col] + mean_veer) % 360
        out.loc[g.loc[missing].index] = pred.values
    return out


def impute_temperature_harmonic(df: pd.DataFrame, target_col: str) -> pd.Series:
    out = df[target_col].copy()
    doy = df["time"].dt.dayofyear.to_numpy()
    hour = df["time"].dt.hour.to_numpy()
    X_all = np.column_stack([
        np.sin(2 * np.pi * doy / 365.25), np.cos(2 * np.pi * doy / 365.25),
        np.sin(2 * np.pi * hour / 24), np.cos(2 * np.pi * hour / 24),
        np.ones(len(df)),
    ])
    for loc_id, g in df.groupby("location_id"):
        missing = g[target_col].isna()
        if not missing.any():
            continue
        known = ~missing
        X_known = X_all[g.index[known.to_numpy()]]
        y_known = g.loc[known, target_col].to_numpy()
        coef, *_ = np.linalg.lstsq(X_known, y_known, rcond=None)
        X_missing = X_all[g.index[missing.to_numpy()]]
        pred = X_missing @ coef
        out.loc[g.loc[missing].index] = pred
    return out


# --------------------------------------------------------------------------- #
# Power-output proxies (ported from model/wind_solar_power_features.py)
# --------------------------------------------------------------------------- #

def add_wind_power_proxy(wind: pd.DataFrame) -> pd.DataFrame:
    temp_k = wind["temperature_120m"] + 273.15
    rho = STANDARD_PRESSURE_PA / (R_SPECIFIC_DRY_AIR * temp_k)

    v = wind["wind_speed_120m"] / 3.6  # km/h -> m/s
    raw = 0.5 * rho * v ** 3

    rated_value = 0.5 * rho * RATED_MS ** 3
    power = raw.clip(upper=rated_value)
    power = power.where(v >= CUT_IN_MS, 0.0)
    power = power.where(v <= CUT_OUT_MS, 0.0)

    wind = wind.copy()
    wind["air_density_proxy"] = rho
    wind["wind_power_output_proxy"] = power
    return wind


def add_solar_power_proxy(solar: pd.DataFrame) -> pd.DataFrame:
    solar = solar.copy()
    t_cell = solar["temperature_2m"] + (NOCT_C - 20.0) / 800.0 * solar["shortwave_radiation"]
    proxy = solar["shortwave_radiation"] * (1 - GAMMA_PER_C * (t_cell - 25.0))
    solar["solar_power_output_proxy"] = proxy.clip(lower=0.0)
    return solar


# --------------------------------------------------------------------------- #
# Calendar features (ported from model/build_master_table.py) - UTC throughout,
# by explicit decision (join convenience beats the minor DST-blur cost)
# --------------------------------------------------------------------------- #

def add_calendar_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["hour"] = df["time"].dt.hour
    df["day_of_week"] = df["time"].dt.dayofweek
    df["month"] = df["time"].dt.month
    years = range(df["time"].dt.year.min(), df["time"].dt.year.max() + 1)
    us_holidays = holidays.US(years=years)
    df["is_us_holiday"] = df["time"].dt.date.astype("O").isin(us_holidays).astype(int)
    return df


# --------------------------------------------------------------------------- #
# Loading raw inputs from the DB
# --------------------------------------------------------------------------- #

def _iso(dt) -> str:
    """Bind as an ISO string, not a pandas Timestamp or raw datetime: pandas
    Timestamp isn't bindable by sqlite3's raw DBAPI driver at all (same issue as
    ingestion.upsert_dataframe), and a plain datetime hits sqlite3's deprecated
    default adapter, which formats with a space ("...28 08:00:00") while our own
    ISO-string-stored columns use "T" ("...28T08:00:00") - a lexicographic
    comparison of the two formats silently gives wrong range-filter results.
    Binding an explicit ISO string keeps both sides of the comparison in the
    same format on sqlite, and compares correctly on Postgres too (it casts an
    ISO string against a timestamp column automatically)."""
    return pd.Timestamp(dt).isoformat()


def load_price_history(engine: sa.Engine, location: str, since: datetime) -> pd.DataFrame:
    query = sa.text(
        "SELECT interval_start_utc AS time, spp_usd_mwh AS price FROM electrical_price "
        "WHERE location = :location AND interval_start_utc >= :since ORDER BY interval_start_utc"
    )
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"location": location, "since": _iso(since)})
    df["time"] = pd.to_datetime(df["time"])
    df["price"] = df["price"].clip(lower=WINSORIZE_LOWER, upper=WINSORIZE_UPPER)
    return df


def load_weather_table(engine: sa.Engine, table: str, columns: list[str],
                       start: datetime, end: datetime) -> pd.DataFrame:
    col_list = ", ".join(["location_id", "time"] + columns)
    query = sa.text(f"SELECT {col_list} FROM {table} WHERE time >= :start AND time < :end")
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"start": _iso(start), "end": _iso(end)})
    df["time"] = pd.to_datetime(df["time"])
    return df


def load_single_column(engine: sa.Engine, table: str, time_col: str, value_col: str,
                       start: datetime, end: datetime) -> pd.DataFrame:
    query = sa.text(f"SELECT {time_col} AS time, {value_col} FROM {table} "
                    f"WHERE {time_col} >= :start AND {time_col} < :end")
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"start": _iso(start), "end": _iso(end)})
    df["time"] = pd.to_datetime(df["time"])
    return df


# --------------------------------------------------------------------------- #
# Feature assembly for the target day(s)
# --------------------------------------------------------------------------- #

def build_features(engine: sa.Engine, target_times: pd.DatetimeIndex, location: str = DEFAULT_LOCATION) -> pd.DataFrame:
    """Build the FEATURE_COLUMNS feature matrix for `target_times` (UTC), using
    only data that would be available by the DAM bid deadline the day before.

    lag_24h/lag_48h are computed per target hour - they always reach back into
    already-known history (even hour 24 of a 24h-ahead batch has its "24h ago"
    land on the already-known day before). The rolling features are different:
    hour 2+ of a same-batch day-ahead prediction would need part of its own
    window to include not-yet-known (also-being-predicted) hours, so they're
    frozen as of the last known actual hour and broadcast across the whole
    batch instead of varying per target hour. (lag_1h had this same problem far
    more severely - see chat/model/train_xgboost_baseline.py - and was dropped
    from the model entirely rather than frozen, since measured head-to-head
    under realistic serving conditions it made predictions much worse, not
    better.) Known limitation: the rolling features get slightly "stale" for
    later hours in the batch - measured as a minor effect, unlike lag_1h's."""
    last_known = target_times.min() - timedelta(hours=1)
    earliest_needed = min(last_known, target_times.min()) - timedelta(hours=168)

    price = load_price_history(engine, location, earliest_needed)
    price_s = price.set_index("time")["price"].asfreq("h")

    frozen = {
        "roll_mean_24h": price_s.loc[last_known - timedelta(hours=23):last_known].mean(),
        "roll_std_24h": price_s.loc[last_known - timedelta(hours=23):last_known].std(),
        "roll_mean_168h": price_s.loc[last_known - timedelta(hours=167):last_known].mean(),
        "roll_std_168h": price_s.loc[last_known - timedelta(hours=167):last_known].std(),
    }
    lags = pd.DataFrame([frozen] * len(target_times), index=target_times)
    lags["lag_24h"] = price_s.reindex(target_times - timedelta(hours=24)).to_numpy()
    lags["lag_48h"] = price_s.reindex(target_times - timedelta(hours=48)).to_numpy()

    features = lags.reset_index().rename(columns={"index": "time"})
    features = add_calendar_features(features)

    gas = load_single_column(engine, "fuel_price", "period", "henry_hub_price_usd_mmbtu",
                             target_times.min() - timedelta(days=5), target_times.max())
    gas = gas.set_index("time")["henry_hub_price_usd_mmbtu"].asfreq("D").ffill().shift(1)
    features["henry_hub_price_usd_mmbtu_lag1d"] = features["time"].dt.floor("D").map(gas)

    demand = load_single_column(engine, "hourly_demand_forecast", "period", "demand_forecast_mwh",
                                target_times.min(), target_times.max() + timedelta(hours=1))
    features = features.merge(demand, on="time", how="left")

    wind = load_weather_table(engine, "wind", ["wind_speed_80m", "wind_speed_120m", "temperature_120m"],
                              target_times.min(), target_times.max() + timedelta(hours=1))
    wind["wind_speed_120m"] = impute_speed_loglog(wind, "wind_speed_120m", "wind_speed_80m")
    wind = add_wind_power_proxy(wind)
    wind_total = wind.groupby("time")["wind_power_output_proxy"].sum().rename("wind_power_output_proxy_total")
    features = features.merge(wind_total.reset_index(), on="time", how="left")

    solar = load_weather_table(engine, "solar", ["shortwave_radiation", "temperature_2m"],
                               target_times.min(), target_times.max() + timedelta(hours=1))
    solar = add_solar_power_proxy(solar)
    solar_total = solar.groupby("time")["solar_power_output_proxy"].sum().rename("solar_power_output_proxy_total")
    features = features.merge(solar_total.reset_index(), on="time", how="left")

    features = features.set_index("time").reindex(target_times)[FEATURE_COLUMNS]
    # A left-joined column can come back as dtype "object" instead of float64
    # when the source query returned zero rows for the target window (pandas
    # can't infer a numeric dtype with nothing to infer from) - e.g. if
    # ingestion hasn't reached the target date yet. XGBoost's predict() rejects
    # object dtype outright, even though every value in it is NaN and it's
    # otherwise perfectly able to handle missing values (missing=np.nan in
    # XGB_PARAMS). Coerce explicitly so a genuinely-missing feature degrades to
    # "NaN, handled natively" rather than a hard crash.
    for col in FEATURE_COLUMNS:
        features[col] = pd.to_numeric(features[col], errors="coerce")
    return features


# --------------------------------------------------------------------------- #
# Model + predict + write
# --------------------------------------------------------------------------- #

def load_model(path: Path) -> xgb.XGBRegressor:
    model = xgb.XGBRegressor()
    model.load_model(path)
    return model


def predict_prices(model, features: pd.DataFrame, times, location: str,
                   model_version: str, generated_at: datetime) -> pd.DataFrame:
    preds = model.predict(features)
    gen_at = pd.Timestamp(generated_at)
    if gen_at.tzinfo is not None:
        gen_at = gen_at.tz_convert("UTC").tz_localize(None)
    return pd.DataFrame({
        "interval_start_utc": pd.to_datetime(times),
        "location": location,
        "predicted_price": preds,
        "model_version": model_version,
        "generated_at": gen_at,
    })


def write_predictions(engine: sa.Engine, predictions_df: pd.DataFrame) -> int:
    added, _ = ing.upsert_dataframe(
        engine, "predicted_price", predictions_df,
        ["interval_start_utc", "location", "model_version", "generated_at"],
    )
    return added


@dataclass
class PredictionResult:
    target_date: date
    rows_written: int
    model_version: str


def run_predictions(engine: sa.Engine, model_path: Path = DEFAULT_MODEL_PATH,
                    target_date: date | None = None, location: str = DEFAULT_LOCATION) -> PredictionResult:
    target_date = target_date or (datetime.now(timezone.utc).date() + timedelta(days=1))
    target_times = pd.date_range(
        start=pd.Timestamp(target_date), periods=24, freq="h",
    )

    logger.info("Building features for %s (%d hours)", target_date, len(target_times))
    features = build_features(engine, target_times, location)

    model = load_model(model_path)
    generated_at = datetime.now(timezone.utc)
    predictions = predict_prices(model, features, target_times, location,
                                 model_version=model_path.stem, generated_at=generated_at)

    added = write_predictions(engine, predictions)
    logger.info("Wrote %d predictions for %s (model_version=%s)", added, target_date, model_path.stem)
    return PredictionResult(target_date=target_date, rows_written=added, model_version=model_path.stem)


def main() -> None:
    parser = argparse.ArgumentParser(description="Predict LZ_NORTH DAM prices and write them to predicted_price.")
    parser.add_argument("--date", type=date.fromisoformat, help="target UTC date (default: tomorrow)")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL_PATH, help="path to the saved XGBoost model")
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    engine = ing.make_engine()
    try:
        run_predictions(engine, model_path=args.model, target_date=args.date)
    except Exception:
        logger.exception("Prediction run FAILED")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
