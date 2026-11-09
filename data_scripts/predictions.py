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

Two targets are predicted every run, from the same anchor day ("now," the
day whose data is safely known): y_0 = price 48h later, y_1 = price 72h
later. Earlier this was a single "predict tomorrow" target, but that was
circular - ERCOT's own DAM auction already publishes an entire day's price
a full day before this job runs, so "tomorrow" was never actually unknown.
See model/model.md S9 for the full reasoning.

Known gap, not yet solved here (flagging rather than silently glossing over
it): the models were trained on a price target winsorized at a FIXED 4-std
band computed once from the full 2020-2026 training history (mean=56.35,
std=362.55 -> band [-1393.85, 1506.55]). Live price-lag features must be
clipped to that SAME fixed band, not a band recomputed from a small recent
window (which would drift from what the models were trained on).
WINSORIZE_LOWER/WINSORIZE_UPPER below hardcode that band; if a model is ever
retrained on a different window, these must be updated together with it, or
predictions will be computed on a different scale than the model expects.

Separately: the imputation functions exist to backfill a HISTORICAL gap
(Open-Meteo didn't serve wind_speed_120m etc. before 2021-03-23) - live
Open-Meteo data has no such gap, so at prediction time they are expected to be
no-ops (each has an `if not missing.any(): continue` guard). They're still run
defensively, in case a live fetch has a transient gap for a specific hour.

Three model types are known, chosen by the model file's extension (local
file) or `model_artifacts.format` (Supabase):
  .pt / "torch"    seq2seq LSTM (model/lstm_model.py, trained by
                   model/train_lstm.py). 168h of history + the target day's
                   known-ahead inputs. Its artifact carries its own feature
                   list, scalers, price transform and lead_hours, and torch
                   is only imported on this path.
  .joblib / "joblib"  LEAR (model/lear_model.py) - a per-hour LASSO ensemble,
                   Lago et al. 2021. Both LSTM and LEAR are in production
                   (model/reports/eval_holdout_y0_2026-11-01.md: LEAR beats
                   even LSTM, MAE 7.07 vs 9.09 at y0); predict_lstm.py and
                   predict_lear.py each serve one, independently, so neither
                   kind failing blocks the other.
  .json            XGBoost - retired from serving entirely (model/reports/
                   eval_holdout_y0_2026-10-13.md: LSTM beat it by a
                   statistically significant margin, DM p=0.0000, at every
                   hour of the day on both targets, and XGBoost itself was
                   worse than the naive same-hour-yesterday baseline). No
                   model_artifacts row, no predict_*.py script - kept only as
                   a comparison baseline in model/evaluate.py, loadable here
                   from a local .json file for that script's own use.

This module is the shared library (feature engineering, confidence scoring,
DB writes) for predict_lstm.py and predict_lear.py - it has no CLI of its
own. For local-file testing/dev against a specific artifact, use either
script's --model-path (production loads from Supabase instead - see
run_predictions_from_supabase).

Import: from predictions import run_predictions, run_predictions_from_supabase
"""

from __future__ import annotations

import logging
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import holidays
import numpy as np
import pandas as pd
import sqlalchemy as sa

import ingestion as ing

logger = logging.getLogger("predictions")

SCRIPT_DIR = Path(__file__).resolve().parent
MODEL_DIR = SCRIPT_DIR.parent / "model"
DEFAULT_LOCATION = "LZ_NORTH"
# Hours from the anchor day ("now") to each target day's start - model/model.md S9.
LEAD_HOURS = {"y0": 48, "y1": 72}

# Fixed winsorization band the models were trained against - see module docstring.
WINSORIZE_LOWER = -1393.85
WINSORIZE_UPPER = 1506.55

# Reference RMSE per (model kind, horizon, hour-of-day UTC of the TARGET
# hour) lives in Supabase (`rmse_reference`, recomputed periodically by
# recompute_reference_rmse.py from real recent residuals - model/model.md's
# continuous-learning section, point 4), not a hand-maintained dict here -
# load_reference_rmse() below reads it directly. Used as the denominator of
# a raw uncertainty ratio (how a recent run of real-world errors for this
# exact slot compares to what that recompute said to expect), which
# confidence_scores() converts to the bounded (0, 100] score actually
# written to the DB - nothing downstream (the API, the alert service)
# converts it again.
CONFIDENCE_LOOKBACK_DAYS = 21  # enough for a 7-day-halflife EWM to shed its startup transient
CONFIDENCE_HALFLIFE_DAYS = 7
MIN_CONFIDENCE_SAMPLES = 3  # fewer points make "decay" meaningless - that hour is left out, not guessed

# Henry Hub is published on business days with a lag, so a given day's gas
# price is the last published one carried forward - but never one older than this.
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
    "lag_72h", "lag_96h", "roll_mean_24h", "roll_std_24h", "roll_mean_168h", "roll_std_168h",
    "hour", "day_of_week", "month", "is_us_holiday",
    "henry_hub_price_usd_mmbtu_lag2d", "henry_hub_price_usd_mmbtu_lag3d",
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


def load_recent_residuals(engine: sa.Engine, location: str, model_version: str,
                          since: datetime) -> pd.DataFrame:
    """(predicted, actual) pairs for every target hour `model_version` has a
    settled actual for, from `since` on - the raw material for the confidence
    score's live decayed-residual side. Only the latest `generated_at` per
    interval_start_utc counts (a day-ahead prediction and a same-day revision
    for the same hour must not double-count) - picked via ROW_NUMBER() rather
    than a Python-side groupby, since it's cheap to push into the query and
    keeps this portable to both sqlite (tests) and Postgres like the rest of
    this module. The INNER JOIN against electrical_price naturally excludes
    target hours with no settled actual yet - no separate future-date filter
    needed."""
    query = sa.text(
        "WITH latest AS ("
        "  SELECT interval_start_utc, predicted_price,"
        "         ROW_NUMBER() OVER (PARTITION BY interval_start_utc"
        "                            ORDER BY generated_at DESC) AS rn"
        "  FROM predicted_price"
        "  WHERE location = :location AND model_version = :model_version"
        "    AND interval_start_utc >= :since"
        ")"
        " SELECT latest.interval_start_utc AS time, latest.predicted_price AS predicted,"
        "        electrical_price.spp_usd_mwh AS actual"
        " FROM latest"
        " JOIN electrical_price"
        "   ON electrical_price.interval_start_utc = latest.interval_start_utc"
        "  AND electrical_price.location = :location"
        " WHERE latest.rn = 1"
        " ORDER BY latest.interval_start_utc"
    )
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={
            "location": location, "model_version": model_version, "since": _iso(since),
        })
    df["time"] = pd.to_datetime(df["time"])
    return df


def decayed_abs_residuals_by_hour(df: pd.DataFrame, min_samples: int = MIN_CONFIDENCE_SAMPLES) -> dict[int, float]:
    """{hour_of_day: 7-day-halflife-decayed mean |predicted-actual|}, one
    entry per hour-of-day with >= min_samples observations in `df` (as
    returned by load_recent_residuals) - fewer points make a "decay"
    meaningless, so that hour is simply absent, not fabricated. Zero history
    (a brand-new deployment) returns {}.

    Must sort by time before grouping: Series.ewm(times=...) does not sort or
    validate its `times` argument - it decays based on the gap between
    consecutive rows in whatever order they're given, so an unsorted frame
    silently produces wrong weights with no error (verified empirically, not
    just assumed from the docs). groupby() preserves each group's original
    row order, so sorting once up front is enough - no need to re-sort inside
    the loop."""
    if df.empty:
        return {}
    df = df.sort_values("time")
    abs_residual = (df["predicted"] - df["actual"]).abs()
    out: dict[int, float] = {}
    for hour, idx in abs_residual.groupby(df["time"].dt.hour).groups.items():
        if len(idx) < min_samples:
            continue
        decayed = abs_residual.loc[idx].ewm(
            halflife=pd.Timedelta(days=CONFIDENCE_HALFLIFE_DAYS), times=df.loc[idx, "time"],
        ).mean()
        out[int(hour)] = float(decayed.iloc[-1])
    return out


def load_reference_rmse(engine: sa.Engine, kind: str, horizon: str) -> list[float] | None:
    """24-hour RMSE reference from `rmse_reference` (recomputed periodically
    by recompute_reference_rmse.py from real recent residuals - model/model.md's
    continuous-learning section, point 4). None if the table has no complete
    row yet for this (kind, horizon) - e.g. a brand-new model kind before its
    first recompute cycle has run - or doesn't exist yet at all (rmse_reference
    is created by the backend's RmseReference entity, ddl-auto=update, which
    may not have booted yet in a brand-new deployment, or at all in an
    offline test's minimal DB): a missing reference degrading confidence
    scores rather than crashing predictions outright is the same philosophy
    as decayed_abs_residuals_by_hour returning {} for "no live history yet"."""
    query = sa.text("SELECT hour_utc, rmse FROM rmse_reference WHERE model_kind = :kind AND target = :horizon")
    try:
        with engine.begin() as conn:
            rows = dict(conn.execute(query, {"kind": kind, "horizon": horizon}).all())
    except sa.exc.DBAPIError:
        rows = {}
    return [rows[h] for h in range(24)] if len(rows) == 24 else None


def confidence_scores(kind: str, horizon: str, decayed_by_hour: dict[int, float],
                      engine: sa.Engine) -> dict[int, float]:
    """{hour: confidence percentage} for all 24 hours, from the raw ratio
    `decayed_abs_residual / reference_rmse[hour]` mapped through
    `100 / (1 + ratio)`: bounded (0, 100], parameter-free, and with a
    deliberately legible midpoint - exactly 50 means recent errors are
    sitting right at the reference RMSE (the "expected" error level); above
    50 is better than the reference, below is worse. This is the FINAL value
    written to `predicted_price.confidence_score` - nothing downstream
    converts it again. NaN (never 0, never 100) wherever decayed_by_hour has
    no entry for that hour (no live history yet for this slot), or
    load_reference_rmse(engine, kind, horizon) has none for it either."""
    ref = load_reference_rmse(engine, kind, horizon)
    out: dict[int, float] = {}
    for hour in range(24):
        residual = decayed_by_hour.get(hour)
        ref_value = ref[hour] if ref is not None else None
        if residual is not None and ref_value:
            out[hour] = 100.0 / (1.0 + residual / ref_value)
        else:
            out[hour] = np.nan
    return out


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


def load_exogenous(engine: sa.Engine, start: datetime, end: datetime) -> pd.DataFrame:
    """Hourly exogenous inputs for start..end (both inclusive), one row per
    hour: gas (2 and 3 day lags) and wind/solar power proxies. Shared by the
    XGBoost and LSTM feature builders so both compute them identically. A new
    exogenous model input (e.g. battery data) needs a column added here.
    Hours with no source data come back as NaN, not dropped.

    No load forecast here: GridStatus's ercot_load_forecast_dam only ever
    publishes one day ahead, so it can never be a known-ahead input for the
    48h/72h targets this module predicts - see model/model.md S9.2."""
    start, end = pd.Timestamp(start), pd.Timestamp(end)
    out = pd.DataFrame({"time": pd.date_range(start, end, freq="h")})

    # Floored at a FIXED boundary (start - this much), not at whichever row
    # the DB happens to return first: henry_hub only has rows on trading
    # days, so a weekend/holiday gap (observed up to 4 days long) sitting
    # right at the fetch's lower edge used to push gas.index.min() later
    # than intended, silently shrinking shift(3)'s lookback margin to zero
    # and producing a real, reproducible NaN for the oldest day(s) of
    # whatever window called this (seen in production: update.py's
    # per-anchor-day build_sequence calls, scattered on specific anchors
    # whose window edge happened to land on such a gap). GAS_MAX_STALE_DAYS
    # (the ffill tolerance) + 3 (the deepest shift, lag3d) guarantees a real
    # trading day still exists before the cutoff shift(3) needs, even in
    # the worst observed gap.
    gas_floor = (start - timedelta(days=GAS_MAX_STALE_DAYS + 3)).floor("D")
    gas = load_single_column(engine, "fuel_price", "period", "henry_hub_price_usd_mmbtu", gas_floor, end)
    gas = gas.set_index("time")["henry_hub_price_usd_mmbtu"]
    if not gas.empty:
        # Extend the daily index through the target day before the ffill:
        # stopping at the last published row would leave every day after it
        # (i.e. any real day-ahead run) without a lag value at all.
        days = pd.date_range(gas_floor, max(gas.index.max(), end.floor("D")), freq="D")
        gas = gas.reindex(days).ffill(limit=GAS_MAX_STALE_DAYS)
    out["henry_hub_price_usd_mmbtu_lag2d"] = out["time"].dt.floor("D").map(gas.shift(2))
    out["henry_hub_price_usd_mmbtu_lag3d"] = out["time"].dt.floor("D").map(gas.shift(3))

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
    return out.merge(solar_total.reset_index(), on="time", how="left")


# --------------------------------------------------------------------------- #
# Feature assembly for the anchor day
# --------------------------------------------------------------------------- #

def assemble_xgb_features(price_s: pd.Series, exog: pd.DataFrame, anchor_times: pd.DatetimeIndex) -> pd.DataFrame:
    """Build the FEATURE_COLUMNS feature matrix for `anchor_times` (UTC) -
    "now," the hour each row's features are safely known as of, NOT the
    target hour (the target is `anchor + lead_hours`; model/model.md S9).
    `price_s` is the hourly price history (already clipped to the
    winsorization band), `exog` the load_exogenous() frame covering
    `anchor_times`. No DB access here, so model/evaluate.py runs the exact
    same code on CSV history (currently stale there - out of scope for the
    y_0/y_1 change, model/model.md S9).

    Every column is computed directly per anchor row via a plain trailing
    `.rolling()` window ending at that row - no freezing/broadcasting needed
    (unlike the old single adjacent-day target). Because lead_hours here are
    multiples of 24, the 24 anchor hours needed for a target day are always
    exactly "today" relative to when this job runs, and every hour of today
    already has a real, settled DAM price by then (published the day
    before) - so there's no not-yet-known overlap to freeze around."""
    lags = pd.DataFrame(index=anchor_times)
    lags["lag_72h"] = price_s.reindex(anchor_times - timedelta(hours=72)).to_numpy()
    lags["lag_96h"] = price_s.reindex(anchor_times - timedelta(hours=96)).to_numpy()
    lags["roll_mean_24h"] = price_s.rolling(24).mean().reindex(anchor_times).to_numpy()
    lags["roll_std_24h"] = price_s.rolling(24).std().reindex(anchor_times).to_numpy()
    lags["roll_mean_168h"] = price_s.rolling(168).mean().reindex(anchor_times).to_numpy()
    lags["roll_std_168h"] = price_s.rolling(168).std().reindex(anchor_times).to_numpy()

    features = lags.reset_index().rename(columns={"index": "time"})
    features = add_calendar_features(features)
    features = features.merge(exog, on="time", how="left")

    features = features.set_index("time").reindex(anchor_times)[FEATURE_COLUMNS]
    # A left-joined column can come back as dtype "object" instead of float64
    # when the source query returned zero rows for the anchor window (pandas
    # can't infer a numeric dtype with nothing to infer from) - e.g. if
    # ingestion hasn't reached the anchor date yet. XGBoost's predict() rejects
    # object dtype outright, even though every value in it is NaN and it's
    # otherwise perfectly able to handle missing values (missing=np.nan in
    # XGB_PARAMS). Coerce explicitly so a genuinely-missing feature degrades to
    # "NaN, handled natively" rather than a hard crash.
    for col in FEATURE_COLUMNS:
        features[col] = pd.to_numeric(features[col], errors="coerce")
    return features


def build_features(engine: sa.Engine, anchor_times: pd.DatetimeIndex, location: str = DEFAULT_LOCATION) -> pd.DataFrame:
    """assemble_xgb_features() on inputs loaded from the DB."""
    earliest_needed = anchor_times.min() - timedelta(hours=168)
    price = load_price_history(engine, location, earliest_needed)
    price_s = price.set_index("time")["price"].asfreq("h")
    exog = load_exogenous(engine, anchor_times.min(), anchor_times.max())
    return assemble_xgb_features(price_s, exog, anchor_times)


# --------------------------------------------------------------------------- #
# Model + predict + write
# --------------------------------------------------------------------------- #

def _lstm_module():
    """model/lstm_model.py, imported on first use - same reasoning as
    _xgb_module() below, just for the model type actually in production."""
    if str(MODEL_DIR) not in sys.path:
        sys.path.insert(0, str(MODEL_DIR))
    import lstm_model
    import torch
    # xgboost and torch each load their own OpenMP runtime; on macOS, letting
    # both run multi-threaded in one process deadlocks or segfaults. One day
    # of LSTM inference is far too small to need threads anyway.
    torch.set_num_threads(1)
    return lstm_model


def _xgb_module():
    """xgboost, imported on first use: XGBoost is retired from serving (see
    the module docstring), so a production deployment running only the
    default LSTM models doesn't need the package installed at all - only
    someone deliberately pointing --model-y0/--model-y1 at a .json file does."""
    import xgboost
    return xgboost


def _lear_module():
    """model/lear_model.py, imported on first use - same reasoning as
    _lstm_module()/_xgb_module(): a deployment running only LSTM (predict_lstm.py)
    doesn't need sklearn/joblib pulled in, and vice versa for predict_lear.py."""
    if str(MODEL_DIR) not in sys.path:
        sys.path.insert(0, str(MODEL_DIR))
    import lear_model
    return lear_model


def load_model(path: Path):
    """Returns (kind, model): ("xgb", XGBRegressor) for a .json file,
    ("lstm", LSTMForecaster in eval mode, its artifact dict on .artifact) for
    a .pt file, or ("lear", LEARForecaster, its artifact dict on .artifact)
    for a .joblib file."""
    path = Path(path)
    if path.suffix == ".json":
        model = _xgb_module().XGBRegressor()
        model.load_model(path)
        return "xgb", model
    if path.suffix == ".pt":
        model, artifact = _lstm_module().load_artifact(path)
        model.artifact = artifact
        return "lstm", model
    if path.suffix == ".joblib":
        model, artifact = _lear_module().load_artifact(path)
        model.artifact = artifact
        return "lear", model
    raise ValueError(f"Unsupported model file {path.name!r}: expected .json (XGBoost), "
                     ".pt (LSTM), or .joblib (LEAR).")


def load_model_from_blob(blob: bytes, format: str):
    """Like load_model(), but from Supabase-stored bytes (model_store.load_active_artifact's
    `blob`/`format`) rather than a local file - same (kind, model) shape, so
    run_predictions_from_supabase can share _predict_and_write with the local-file
    path load_model() feeds. `format` is model_artifacts.format: "torch" or "joblib" -
    there's no "xgb" here, since XGBoost is retired from serving (module docstring)
    and so never gets a model_artifacts row at all."""
    import io
    buf = io.BytesIO(blob)
    if format == "torch":
        model, artifact = _lstm_module().load_artifact(buf)
        model.artifact = artifact
        return "lstm", model
    if format == "joblib":
        model, artifact = _lear_module().load_artifact(buf)
        model.artifact = artifact
        return "lear", model
    raise ValueError(f"Unsupported model_artifacts.format {format!r}: expected 'torch' or 'joblib'.")


def build_sequence(engine: sa.Engine, target_times: pd.DatetimeIndex, location: str, artifact: dict):
    """LSTM inputs for `target_times` (the 24 hours of one UTC target day,
    `artifact.get("lead_hours", 0)` hours after the anchor day): tensors
    shaped (1, past_window, n_past) and (1, horizon, n_future), clipped,
    transformed and scaled exactly as the artifact was trained.

    Price is queried only up to the hour before the anchor day; the
    exogenous inputs cover the history window and the target day. Raises
    lstm_model.MissingInputError (after logging which inputs) rather than
    predicting from incomplete inputs; price gaps of a few hours are
    forward-filled with a warning.
    """
    lstm = _lstm_module()
    import torch

    if len(target_times) != artifact["horizon"]:
        raise ValueError(f"LSTM predicts {artifact['horizon']} consecutive hours, got {len(target_times)}.")
    lead_hours = artifact.get("lead_hours", 0)
    anchor = target_times.min() - timedelta(hours=lead_hours)
    cutoff = anchor - timedelta(hours=1)
    history_start = anchor - timedelta(hours=artifact["past_window"])

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
        past, future = lstm.assemble_inputs(frame, anchor, artifact, log=logger)
    except lstm.MissingInputError as exc:
        logger.error("Not predicting %s: %s", target_times.min().date(), exc)
        raise
    return torch.from_numpy(past).unsqueeze(0), torch.from_numpy(future).unsqueeze(0)


def build_lear_frame(engine: sa.Engine, target_times: pd.DatetimeIndex, location: str, artifact: dict) -> pd.DataFrame:
    """Hourly frame for LEAR's predict_day() (model/lear_model.py): price
    through the end of the anchor day (lear_model's own module docstring -
    "Information cutoff: price is only ever read before D-(horizon_days-1)
    00:00" - the newest price day LEAR's build_design actually uses is
    D-horizon_days, which lands exactly on the anchor day, not one day
    before it) plus exogenous inputs through the target day, far enough back
    to cover the artifact's longest calibration window.

    Fetching through the anchor day's last hour (not stopping one hour
    before the anchor day starts) is still never a leak: build_design
    discards anything at/after D-(horizon_days-1) 00:00 itself (one day
    later than this cutoff) before building any feature, so hands it
    anchor-day data it's explicitly designed to use, never target-day data.

    +7 days of extra lookback past the longest window: the same
    price_day_offsets() tail (D-7, the same-weekday lag) lear_model.build_design's
    own max_history_days buffer accounts for internally - without it here too,
    day_history-style slicing would cut off the data that offset needs for the
    earliest days of the window."""
    lear = _lear_module()
    target_day = target_times.min().normalize()
    lead_hours = artifact["lead_hours"]
    anchor = target_day - timedelta(hours=lead_hours)
    cutoff = anchor + timedelta(hours=23)
    history_start = anchor - timedelta(days=max(artifact["windows"]) + 7)

    frame = load_exogenous(engine, history_start, target_day + timedelta(hours=23))
    unknown = [c for c in artifact["exog_cols"] if c not in frame.columns]
    if unknown:
        raise ValueError(f"LEAR needs exogenous columns load_exogenous doesn't provide: {unknown}")
    frame = add_calendar_features(frame).set_index("time")

    price = load_price_history(engine, location, history_start, until=cutoff)
    frame[lear.RAW_PRICE_COL] = price.set_index("time")["price"]
    # Same reason as build_features/build_sequence: a source with zero rows comes back as dtype object.
    return frame.apply(pd.to_numeric, errors="coerce")


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
    horizon: str
    target_date: date
    rows_written: int
    model_version: str


def _predict_and_write(engine: sa.Engine, kind: str, model, model_version: str, horizon: str,
                       anchor_date: date, generated_at: datetime, location: str) -> PredictionResult:
    """Everything downstream of "an already-loaded (kind, model)": build
    inputs, predict, score confidence, write. Shared by run_predictions
    (local-file models, --model-y0/--model-y1) and run_predictions_from_supabase
    (the production path: predict_lstm.py/predict_lear.py loading whichever
    model_artifacts row is active) so neither has to duplicate this."""
    # Prefer the loaded model's own self-described target/lead_hours over
    # LEAD_HOURS[horizon] (used only as a fallback for an artifact that
    # doesn't carry one) - this is what actually determines the target date
    # below, so a model loaded under the wrong horizon slot gets a loud
    # warning instead of silently computing the wrong target day for the gap
    # it was really trained on.
    if kind == "xgb":
        trained_for = model.get_booster().attr("target")
    else:
        trained_for = model.artifact.get("target")
    lead_hours = LEAD_HOURS.get(trained_for, LEAD_HOURS[horizon])
    if trained_for is not None and trained_for != horizon:
        logger.warning("%s was trained with target=%s, but is being served as %s - "
                       "check it's the right model for this horizon/slot.", model_version, trained_for, horizon)
    target_date = anchor_date + timedelta(hours=lead_hours)
    target_times = pd.date_range(start=pd.Timestamp(target_date), periods=24, freq="h")

    logger.info("[%s] Building %s inputs for %s (%d hours)", horizon, kind, target_date, len(target_times))
    if kind == "xgb":
        anchor_times = target_times - timedelta(hours=lead_hours)
        preds = model.predict(build_features(engine, anchor_times, location))
    elif kind == "lear":
        preds = model.predict_day(build_lear_frame(engine, target_times, location, model.artifact), target_date)
    else:
        p10, preds, p90 = lstm_predict(model, build_sequence(engine, target_times, location, model.artifact))
        if p10 is not None and p90 is not None:
            # Only the point forecast has a column in predicted_price - the interval is logged.
            for t, lo, mid, hi in zip(target_times, p10, preds, p90):
                logger.info("[%s] %s  P10 %8.2f  P50 %8.2f  P90 %8.2f", horizon,
                           t.strftime("%Y-%m-%d %H:%M"), lo, mid, hi)

    predictions = predict_prices(preds, target_times, location, model_version=model_version,
                                 generated_at=generated_at)

    # Confidence score: how a recent (decayed) run of real-world errors for
    # this exact (kind, horizon, hour-of-day) slot compares to what held-out
    # evaluation (or rmse_reference's periodic recompute) said to expect for
    # it. Fetched once per horizon, not once per hour.
    gen_at_naive = pd.Timestamp(generated_at).tz_convert("UTC").tz_localize(None)
    since = gen_at_naive - timedelta(days=CONFIDENCE_LOOKBACK_DAYS)
    residuals = load_recent_residuals(engine, location, model_version, since)
    decayed_by_hour = decayed_abs_residuals_by_hour(residuals)
    scores = confidence_scores(kind, horizon, decayed_by_hour, engine=engine)
    predictions["confidence_score"] = predictions["interval_start_utc"].dt.hour.map(scores)

    added = write_predictions(engine, predictions)
    logger.info("[%s] Wrote %d predictions for %s (model_version=%s)", horizon, added, target_date, model_version)
    return PredictionResult(horizon=horizon, target_date=target_date, rows_written=added, model_version=model_version)


def run_predictions(engine: sa.Engine, model_paths: dict[str, Path],
                    anchor_date: date | None = None, location: str = DEFAULT_LOCATION) -> list[PredictionResult]:
    """One run, two independent horizons by default (`model_paths` keys:
    "y0", "y1"). Each horizon builds/predicts/writes on its own - the anchor
    hours are provably the same 24 hours of `anchor_date` for both (lead
    times are multiples of 24h), but this is a once-daily batch job, so the
    redundant DB round-trip is simpler than sharing one feature build.

    Loads each model from a local file (model_paths) - the dev/evaluation
    path. Production (predict_lstm.py/predict_lear.py) uses
    run_predictions_from_supabase instead, since Render Cron containers are
    ephemeral and have no local model file to point at."""
    anchor_date = anchor_date or datetime.now(timezone.utc).date()
    generated_at = datetime.now(timezone.utc)
    results = []
    for horizon, model_path in model_paths.items():
        model_path = Path(model_path)
        kind, model = load_model(model_path)
        results.append(_predict_and_write(engine, kind, model, model_path.stem, horizon,
                                          anchor_date, generated_at, location))
    return results


def run_predictions_from_supabase(engine: sa.Engine, model_kind: str, horizons: list[str] | None = None,
                                  anchor_date: date | None = None,
                                  location: str = DEFAULT_LOCATION) -> list[PredictionResult]:
    """Like run_predictions, but loads each horizon's currently-active
    artifact from `model_artifacts` (model_store.load_active_artifact)
    instead of a local file - the production path for predict_lstm.py
    (model_kind="lstm") and predict_lear.py (model_kind="lear"). A horizon
    with no active row yet (a brand-new deployment, or a kind/target before
    its first data_scripts/update.py tune cycle) is skipped with a warning,
    not a hard failure - the other horizon still gets served."""
    import model_store
    anchor_date = anchor_date or datetime.now(timezone.utc).date()
    generated_at = datetime.now(timezone.utc)
    results = []
    for horizon in horizons or ["y0", "y1"]:
        row = model_store.load_active_artifact(engine, model_kind, horizon)
        if row is None:
            logger.warning("[%s] No active %s artifact in model_artifacts yet - skipping.", horizon, model_kind)
            continue
        kind, model = load_model_from_blob(row["blob"], row["format"])
        results.append(_predict_and_write(engine, kind, model, row["model_version"], horizon,
                                          anchor_date, generated_at, location))
    return results
