"""
predictions.py

Feature engineering + model serving for LZ_NORTH DAM price. Ports the
imputation/power-proxy/calendar logic originally validated in the EDA phase
(in now-deleted scripts model/wind_solar_power_features.py,
model/wind_shear_veer_turbulence.py, model/build_master_table.py - see
model/model.md S3.5 for the formulas' rationale) and adds the new
prediction-writing layer. This module is now the only place that logic
survives in runnable form.

Reuses ingestion.upsert_dataframe for the DB write rather than duplicating it
(predictions.py and ingestion.py are "self contained" with respect to the
pruned EDA scripts, not with respect to each other).

Known gap, not yet solved here (flagging rather than silently glossing over
it): the model was trained on a price target winsorized at a FIXED 4-std band
computed once from the full 2020-2026 training history (mean=56.35,
std=362.55 -> band [-1393.85, 1506.55]). Live price-lag features must be
clipped to that SAME fixed band, not a band recomputed from a small recent
window (which would drift from what the model was trained on).
WINSORIZE_LOWER/WINSORIZE_UPPER below hardcode that band; if the model is
ever retrained on a different window, these must be updated together with
it, or predictions will be computed on a different scale than the model
expects.

Separately: the imputation functions exist to backfill a HISTORICAL gap
(Open-Meteo didn't serve wind_speed_120m etc. before 2021-03-23) - live
Open-Meteo data has no such gap, so at prediction time they are expected to be
no-ops (each has an `if not missing.any(): continue` guard). They're still run
defensively, in case a live fetch has a transient gap for a specific hour.

Two model types are served, chosen by the --model file's extension:
  .json  XGBoost (the default) - one row of FEATURE_COLUMNS per target hour.
  .pt    seq2seq LSTM (model/lstm_model.py, trained by model/train_lstm.py) -
         168h of history + the target day's known-ahead inputs. Its artifact
         carries its own feature list, scalers and price transform, and torch
         is only imported on this path.

Usage:
  python predictions.py --date 2026-03-02   # predict the 24 hours of that UTC date
  python predictions.py                     # predict tomorrow (UTC), the normal daily run
  python predictions.py --model ../model/models/lstm_full.pt   # same, with the LSTM

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
MODEL_DIR = SCRIPT_DIR.parent / "model"
DEFAULT_MODEL_PATH = MODEL_DIR / "models" / "xgboost_baseline_full.json"
DEFAULT_MODEL_VERSION = "xgboost_baseline_full"
DEFAULT_LOCATION = "LZ_NORTH"
# load_forecast_dam uses ERCOT's weather-zone scheme (north/south/west/
# houston/system_total), a different naming convention from the LZ_
# settlement points above - see model/model.md S8.3. Must not be confused
# with DEFAULT_LOCATION; passing "LZ_NORTH" here matches zero rows.
DEFAULT_LOAD_FORECAST_ZONE = "north"

# Fixed winsorization band the model was trained against - see module docstring.
WINSORIZE_LOWER = -1393.85
WINSORIZE_UPPER = 1506.55

# Henry Hub is published on business days with a lag, so the target day's
# "yesterday" price is the last published one carried forward - but never one
# older than this.
GAS_MAX_STALE_DAYS = 7

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
    "henry_hub_price_usd_mmbtu_lag1d",
    "wind_power_output_proxy_total", "solar_power_output_proxy_total", "load_forecast_dam_north_mwh"
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


def load_price_history(engine: sa.Engine, location: str, since: datetime,
                       until: datetime | None = None) -> pd.DataFrame:
    """Prices from `since` on; `until` (inclusive) caps the query itself, for
    callers that must not see anything past an information cutoff."""
    params = {"location": location, "since": _iso(since)}
    until_clause = ""
    if until is not None:
        until_clause = "AND interval_start_utc <= :until "
        params["until"] = _iso(until)
    query = sa.text(
        "SELECT interval_start_utc AS time, spp_usd_mwh AS price FROM electrical_price "
        f"WHERE location = :location AND interval_start_utc >= :since {until_clause}"
        "ORDER BY interval_start_utc"
    )
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params=params)
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

def load_load_forecast(engine: sa.Engine, zone: str, start: datetime, end: datetime) -> pd.DataFrame:
    query = sa.text(
        "SELECT interval_start_utc AS time, load_forecast_mwh AS load_forecast_dam_north_mwh "
        "FROM load_forecast_dam "
        "WHERE zone = :zone AND interval_start_utc >= :start AND interval_start_utc < :end"
    )
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"zone": zone, "start": _iso(start), "end": _iso(end)})
    df["time"] = pd.to_datetime(df["time"])
    return df


def load_exogenous(engine: sa.Engine, start: datetime, end: datetime) -> pd.DataFrame:
    """Hourly exogenous inputs for start..end (both inclusive), one row per
    hour: gas, wind/solar power proxies and the DAM load forecast. Shared by
    the XGBoost and LSTM feature builders so both compute them identically.
    A new exogenous model input (e.g. battery data) needs a column added here.
    Hours with no source data come back as NaN, not dropped."""
    start, end = pd.Timestamp(start), pd.Timestamp(end)
    out = pd.DataFrame({"time": pd.date_range(start, end, freq="h")})

    gas = load_single_column(engine, "fuel_price", "period", "henry_hub_price_usd_mmbtu",
                             start - timedelta(days=5), end)
    gas = gas.set_index("time")["henry_hub_price_usd_mmbtu"]
    if not gas.empty:
        # Extend the daily index through the target day before the ffill/shift:
        # stopping at the last published row would leave every day after it
        # (i.e. any real day-ahead run) without a lag-1d value at all.
        days = pd.date_range(gas.index.min(), max(gas.index.max(), end.floor("D")), freq="D")
        gas = gas.reindex(days).ffill(limit=GAS_MAX_STALE_DAYS).shift(1)
    out["henry_hub_price_usd_mmbtu_lag1d"] = out["time"].dt.floor("D").map(gas)

    wind = load_weather_table(engine, "wind", ["wind_speed_80m", "wind_speed_120m", "temperature_120m"],
                              start, end + timedelta(hours=1))
    wind["wind_speed_120m"] = impute_speed_loglog(wind, "wind_speed_120m", "wind_speed_80m")
    wind = add_wind_power_proxy(wind)
    wind_total = wind.groupby("time")["wind_power_output_proxy"].sum().rename("wind_power_output_proxy_total")
    out = out.merge(wind_total.reset_index(), on="time", how="left")

    solar = load_weather_table(engine, "solar", ["shortwave_radiation", "temperature_2m"],
                               start, end + timedelta(hours=1))
    solar = add_solar_power_proxy(solar)
    solar_total = solar.groupby("time")["solar_power_output_proxy"].sum().rename("solar_power_output_proxy_total")
    out = out.merge(solar_total.reset_index(), on="time", how="left")

    demand = load_load_forecast(engine, DEFAULT_LOAD_FORECAST_ZONE, start, end + timedelta(hours=1))
    return out.merge(demand, on="time", how="left")


# --------------------------------------------------------------------------- #
# Feature assembly for the target day(s)
# --------------------------------------------------------------------------- #

def assemble_xgb_features(price_s: pd.Series, exog: pd.DataFrame, target_times: pd.DatetimeIndex) -> pd.DataFrame:
    """Build the FEATURE_COLUMNS feature matrix for `target_times` (UTC), using
    only data that would be available by the DAM bid deadline the day before.
    `price_s` is the hourly price history (already clipped to the winsorization
    band), `exog` the load_exogenous() frame covering `target_times`. No DB
    access here, so model/evaluate.py runs the exact same code on CSV history.

    lag_24h/lag_48h are computed per target hour - they always reach back into
    already-known history (even hour 24 of a 24h-ahead batch has its "24h ago"
    land on the already-known day before). The rolling features are different:
    hour 2+ of a same-batch day-ahead prediction would need part of its own
    window to include not-yet-known (also-being-predicted) hours, so they're
    frozen as of the last known actual hour and broadcast across the whole
    batch instead of varying per target hour. (lag_1h had this same problem far
    more severely - freezing it the same way still made predictions much
    worse, not better, measured head-to-head under this same realistic
    serving simulation - so it was dropped from the model entirely rather
    than frozen. Full numbers: model/model.md S8.2.) Known limitation: the
    rolling features get slightly "stale" for later hours in the batch -
    measured as a minor effect, unlike lag_1h's."""
    last_known = target_times.min() - timedelta(hours=1)

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
    features = features.merge(exog, on="time", how="left")

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


def build_features(engine: sa.Engine, target_times: pd.DatetimeIndex, location: str = DEFAULT_LOCATION) -> pd.DataFrame:
    """assemble_xgb_features() on inputs loaded from the DB."""
    earliest_needed = target_times.min() - timedelta(hours=1) - timedelta(hours=168)
    price = load_price_history(engine, location, earliest_needed)
    price_s = price.set_index("time")["price"].asfreq("h")
    exog = load_exogenous(engine, target_times.min(), target_times.max())
    return assemble_xgb_features(price_s, exog, target_times)


# --------------------------------------------------------------------------- #
# Model + predict + write
# --------------------------------------------------------------------------- #

def _lstm_module():
    """model/lstm_model.py, imported on first use: it pulls in torch, which an
    XGBoost-only deployment doesn't need to have installed."""
    if str(MODEL_DIR) not in sys.path:
        sys.path.insert(0, str(MODEL_DIR))
    import lstm_model
    import torch
    # xgboost and torch each load their own OpenMP runtime; on macOS, letting
    # both run multi-threaded in one process deadlocks or segfaults. One day
    # of LSTM inference is far too small to need threads anyway.
    torch.set_num_threads(1)
    return lstm_model


def load_model(path: Path):
    """Returns (kind, model): ("xgb", XGBRegressor) for a .json file, or
    ("lstm", LSTMForecaster in eval mode, its artifact dict on .artifact) for
    a .pt file."""
    path = Path(path)
    if path.suffix == ".json":
        model = xgb.XGBRegressor()
        model.load_model(path)
        return "xgb", model
    if path.suffix == ".pt":
        model, artifact = _lstm_module().load_artifact(path)
        model.artifact = artifact
        return "lstm", model
    raise ValueError(f"Unsupported model file {path.name!r}: expected .json (XGBoost) or .pt (LSTM).")


def build_sequence(engine: sa.Engine, target_times: pd.DatetimeIndex, location: str, artifact: dict):
    """LSTM inputs for `target_times` (the 24 hours of one UTC day): tensors
    shaped (1, past_window, n_past) and (1, horizon, n_future), clipped,
    transformed and scaled exactly as the artifact was trained.

    Price is queried only up to the hour before `target_times.min()`; the
    exogenous inputs cover the history window and the target day. Raises
    lstm_model.MissingInputError (after logging which inputs) rather than
    predicting from incomplete inputs; price gaps of a few hours are
    forward-filled with a warning.
    """
    lstm = _lstm_module()
    import torch

    if len(target_times) != artifact["horizon"]:
        raise ValueError(f"LSTM predicts {artifact['horizon']} consecutive hours, got {len(target_times)}.")
    day_start = target_times.min()
    cutoff = day_start - timedelta(hours=1)
    history_start = day_start - timedelta(hours=artifact["past_window"])

    frame = load_exogenous(engine, history_start, target_times.max())
    unknown = [c for c in artifact["exog_cols"] if c not in frame.columns]
    if unknown:
        raise ValueError(f"Model needs exogenous columns that load_exogenous doesn't provide: {unknown}")
    frame = lstm.add_cyclic_calendar(add_calendar_features(frame)).set_index("time")

    price = load_price_history(engine, location, history_start, until=cutoff)
    frame[lstm.PRICE_COL] = price.set_index("time")["price"]
    # Same reason as build_features: a source with zero rows comes back as dtype object.
    frame = frame.apply(pd.to_numeric, errors="coerce")

    try:
        past, future = lstm.assemble_inputs(frame, day_start, artifact, log=logger)
    except lstm.MissingInputError as exc:
        logger.error("Not predicting %s: %s", day_start.date(), exc)
        raise
    return torch.from_numpy(past).unsqueeze(0), torch.from_numpy(future).unsqueeze(0)


def lstm_predict(model, sequence) -> tuple[np.ndarray | None, np.ndarray, np.ndarray | None]:
    """(p10, p50, p90) in $/MWh for one day. p10/p90 are None for a point
    model (trained with --loss mse)."""
    past, future = sequence
    preds = _lstm_module().predict_quantiles(model, past, future, model.artifact)[0]
    by_quantile = dict(zip(model.artifact["arch"]["quantiles"], preds.T))
    return by_quantile.get(0.1), by_quantile[0.5], by_quantile.get(0.9)


def predict_prices(preds, times, location: str,
                   model_version: str, generated_at: datetime) -> pd.DataFrame:
    gen_at = pd.Timestamp(generated_at)
    if gen_at.tzinfo is not None:
        gen_at = gen_at.tz_convert("UTC").tz_localize(None)
    return pd.DataFrame({
        "interval_start_utc": pd.to_datetime(times),
        "location": location,
        "predicted_price": np.asarray(preds, dtype=float),
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

    model_path = Path(model_path)
    kind, model = load_model(model_path)
    logger.info("Building %s inputs for %s (%d hours)", kind, target_date, len(target_times))
    if kind == "xgb":
        preds = model.predict(build_features(engine, target_times, location))
    else:
        p10, preds, p90 = lstm_predict(model, build_sequence(engine, target_times, location, model.artifact))
        if p10 is not None and p90 is not None:
            # Only the point forecast has a column in predicted_price - the interval is logged.
            for t, lo, mid, hi in zip(target_times, p10, preds, p90):
                logger.info("%s  P10 %8.2f  P50 %8.2f  P90 %8.2f", t.strftime("%Y-%m-%d %H:%M"), lo, mid, hi)

    generated_at = datetime.now(timezone.utc)
    predictions = predict_prices(preds, target_times, location,
                                 model_version=model_path.stem, generated_at=generated_at)

    added = write_predictions(engine, predictions)
    logger.info("Wrote %d predictions for %s (model_version=%s)", added, target_date, model_path.stem)
    return PredictionResult(target_date=target_date, rows_written=added, model_version=model_path.stem)


def main() -> None:
    parser = argparse.ArgumentParser(description="Predict LZ_NORTH DAM prices and write them to predicted_price.")
    parser.add_argument("--date", type=date.fromisoformat, help="target UTC date (default: tomorrow)")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL_PATH, help="saved model: .json (XGBoost) or .pt (LSTM)")
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
