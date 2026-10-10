"""
Validate and compare the LZ_NORTH DAM price models against actual prices
they never trained on (PRD FR-11: MAE + RMSE by horizon and model version).

Every model forecasts each test day D (24 UTC hours) from what it would have
at the DAM deadline: price actuals up to D-1 23:00, plus D's known-ahead
inputs. That cutoff is enforced here, not trusted: each model is handed a
history frame whose price is blanked from D 00:00 on, and none of the CSV's
precomputed lag_*/roll_* columns (which include the target hour).

Models (--models):
  naive_d1  price at the same hour on D-1
  naive_w1  price at the same hour on D-7
  xgb       XGBoost baseline, features built by predictions.assemble_xgb_features
            (the code that serves it): lags per hour, rolling stats frozen at
            D-1 23:00
  lstm      seq2seq LSTM, inputs built by lstm_model.assemble_inputs (ditto);
            P50 is the point forecast
  lear      LEAR benchmark (lear_model.py): 24 LASSO hour-models on the full
            24-hour profiles of previous days, averaged over four calibration
            windows. Recalibrates itself on the history before each test day
            (--recal-every), so it is not trained per fold and has no seed.
  --variant NAME=col1,col2 adds "lstm_NAME" / "lear_NAME" (for whichever of
            lstm and lear is in --models): that model with those extra
            EXOG_COLS (e.g. battery features), compared against the plain one.

Modes:
  --mode holdout      train <= 2026-01-31, test 2026-02-01..2026-08-30. Uses
                      models/xgboost_baseline_train.json and models/lstm_train.pt.
  --mode walkforward  five expanding-window folds (FOLDS below); XGBoost and
                      the LSTM are retrained per fold, the LSTM once per seed.
                      Fold artifacts are cached under models/folds/.

Writes, under model/reports/: forecasts_<mode>.parquet (every prediction),
eval_<mode>_<date>.md, and plots/<mode>_*.png.

Usage:
  python model/evaluate.py --mode holdout
  python model/evaluate.py --mode walkforward
  python model/evaluate.py --mode walkforward --variant battery=battery_soc_mwh,battery_net_mw
  python model/evaluate.py --mode walkforward --models naive_d1,naive_w1,xgb,lstm,lear --seeds 0,1,2
"""

from __future__ import annotations

import argparse
import logging
import math
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

OUT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(OUT_DIR.parent / "data_scripts"))

import predictions as P  # noqa: E402  (imports xgboost - must stay ahead of torch, see set_num_threads)
import xgboost as xgb  # noqa: E402
import torch  # noqa: E402

import lear_model as lear  # noqa: E402
import lstm_model as lm  # noqa: E402
from train_lstm import EXOG_MAX_FFILL_HOURS, RAW_PRICE_COL, find_data_dir  # noqa: E402
from train_xgboost_baseline import XGB_PARAMS  # noqa: E402

# xgboost and torch each load their own OpenMP runtime; on macOS, letting both
# run multi-threaded in one process deadlocks or segfaults (and importing
# torch first segfaults regardless). Inference here is small enough not to care.
torch.set_num_threads(1)

MODELS_DIR = OUT_DIR / "models"
FOLDS_DIR = MODELS_DIR / "folds"
REPORTS_DIR = OUT_DIR / "reports"

PRICE = lm.PRICE_COL
WINSORIZED_COL = "lz_north_price_winsorized"
XGB_EXOG = ["henry_hub_price_usd_mmbtu_lag1d", "wind_power_output_proxy_total",
            "solar_power_output_proxy_total", "load_forecast_dam_north_mwh"]
# Derived from price, including the target hour - never shown to a model at prediction time.
PRICE_DERIVED = ["lag_24h", "lag_48h", "roll_mean_24h", "roll_std_24h", "roll_mean_168h", "roll_std_168h",
                 WINSORIZED_COL]
HISTORY_DAYS = 15  # history handed to a model per day: covers the 168h windows and the D-7 naive

# (fold, last train day, first test day, last test day)
FOLDS = [
    ("1", "2023-12-31", "2024-01-01", "2024-06-30"),
    ("2", "2024-06-30", "2024-07-01", "2024-12-31"),
    ("3", "2024-12-31", "2025-01-01", "2025-06-30"),
    ("4", "2025-06-30", "2025-07-01", "2026-01-31"),
    ("5", "2026-01-31", "2026-02-01", "2026-08-31"),
]
HOLDOUT_FOLD = "5"

SPIKE_QUANTILE = 0.95
LOW_PRICE_QUANTILE = 0.25
CHEAPEST_N = 6
SMAPE_MIN_ABS_PRICE = 1.0
HAC_LAG_DAYS = 7
BOOTSTRAP_BLOCK_DAYS = 7
BOOTSTRAP_DRAWS = 2000
LEAK_TEST_DAYS = 12
REFERENCE = {  # measured earlier on val, raw price: (MAE, RMSE)
    "naive_d1": (9.06, 21.99),
    "xgb": (9.65, 18.66),
    "xgb on CSV features (leaky rolls)": (8.89, 16.65),
}
REFERENCE_TOLERANCE = 0.05

# Chart identity: one fixed colour per model, never reassigned.
INK, MUTED, GRID, SURFACE = "#0b0b0b", "#898781", "#e1e0d9", "#fcfcfb"
MODEL_COLORS = {"lstm": "#2a78d6", "xgb": "#eb6834", "lear": "#4a3aa7", "naive_d1": "#1baf7a", "naive_w1": "#eda100"}
VARIANT_COLORS = ["#e87ba4", "#008300"]
MODEL_DASHES = {"naive_d1": (4, 2), "naive_w1": (1, 2)}


# --------------------------------------------------------------------------- #
# Data
# --------------------------------------------------------------------------- #

def load_history(data_dir: Path) -> pd.DataFrame:
    """train+val as one hourly frame: the actuals, and the history models draw on."""
    parts = [pd.read_csv(data_dir / f"{name}.csv", parse_dates=["time"]) for name in ("train", "val")]
    frame = pd.concat(parts, ignore_index=True).sort_values("time").set_index("time").asfreq("h")
    return lm.add_cyclic_calendar(frame).rename(columns={RAW_PRICE_COL: PRICE})


def day_hours(day: pd.Timestamp) -> pd.DatetimeIndex:
    return pd.date_range(day, periods=24, freq="h")


def test_days(frame: pd.DataFrame, first: str, last: str) -> pd.DatetimeIndex:
    """Days in [first, last] with all 24 actual prices."""
    days = pd.date_range(first, last, freq="D")
    counts = frame[PRICE].notna().groupby(frame.index.floor("D")).sum()
    return days[counts.reindex(days, fill_value=0).to_numpy() == 24]


def day_history(frame: pd.DataFrame, day: pd.Timestamp, future_price=np.nan,
                history_days: int | None = HISTORY_DAYS) -> pd.DataFrame:
    """What a model may see when forecasting `day`: recent history (all of it
    for history_days=None) with the price blanked from `day` 00:00 on.
    `future_price` swaps the blank for a planted value - the leakage test uses
    it to prove nothing reads it."""
    cols = [c for c in frame.columns if c not in PRICE_DERIVED]
    start = None if history_days is None else day - pd.Timedelta(days=history_days)
    history = frame.loc[start:day + pd.Timedelta(hours=23), cols].copy()
    history.loc[day:, PRICE] = future_price
    return history


# --------------------------------------------------------------------------- #
# Models - common interface: fit(frame, train_end), predict_day(history, day)
# --------------------------------------------------------------------------- #

class NaiveForecaster:
    seed = None
    history_days = HISTORY_DAYS

    def __init__(self, model_id: str, lag_days: int):
        self.id, self.lag, self.model_version = model_id, pd.Timedelta(days=lag_days), model_id

    def fit(self, frame: pd.DataFrame, train_end: pd.Timestamp) -> None:
        pass

    def predict_day(self, history: pd.DataFrame, day: pd.Timestamp):
        return history[PRICE].reindex(day_hours(day) - self.lag).to_numpy(), None, None


class XGBForecaster:
    id = "xgb"
    history_days = HISTORY_DAYS
    seed = XGB_PARAMS["random_state"]

    def __init__(self, pretrained: Path | None = None):
        self.pretrained = pretrained

    def fit(self, frame: pd.DataFrame, train_end: pd.Timestamp) -> None:
        """Hold-out: the saved train-only model. Walk-forward: refit exactly as
        train_xgboost_baseline.py does (CSV feature columns, winsorized target)
        on rows up to train_end."""
        if self.pretrained is not None:
            self.model = xgb.XGBRegressor()
            self.model.load_model(self.pretrained)
            self.model_version = self.pretrained.stem
            return
        rows = frame.loc[:train_end].dropna(subset=[WINSORIZED_COL])
        self.model = xgb.XGBRegressor(**XGB_PARAMS, missing=np.nan)
        self.model.fit(rows[P.FEATURE_COLUMNS], rows[WINSORIZED_COL])
        self.model_version = f"xgboost_baseline_trainend_{train_end.date()}"

    def predict_day(self, history: pd.DataFrame, day: pd.Timestamp):
        price = history[PRICE].clip(P.WINSORIZE_LOWER, P.WINSORIZE_UPPER)
        features = P.assemble_xgb_features(price, history[XGB_EXOG].reset_index(), day_hours(day))
        return self.model.predict(features), None, None


class LSTMForecaster:
    history_days = HISTORY_DAYS

    def __init__(self, model_id: str, seed: int, artifact_path: Path):
        self.id, self.seed, self.path = model_id, seed, artifact_path

    def fit(self, frame: pd.DataFrame, train_end: pd.Timestamp) -> None:
        """Training happens in train_lstm.py (see train_missing_lstms); this
        loads the artifact and refuses one that has seen the test period."""
        self.model, self.artifact = lm.load_artifact(self.path)
        seen_until = pd.Timestamp(self.artifact["train_end"])
        if self.artifact["trained_on"] != "train" or seen_until > train_end:
            raise ValueError(f"{self.path.name} was trained on data up to {seen_until} "
                             f"({self.artifact['trained_on']}) - past this fold's train end {train_end}.")
        self.model_version = self.path.stem

    def predict_day(self, history: pd.DataFrame, day: pd.Timestamp):
        exog = self.artifact["exog_cols"]
        history[exog] = history[exog].ffill(limit=EXOG_MAX_FFILL_HOURS)
        past, future = lm.assemble_inputs(history, day, self.artifact)
        preds = lm.predict_quantiles(self.model, past[None], future[None], self.artifact)[0]
        by_quantile = dict(zip(self.artifact["arch"]["quantiles"], preds.T))
        return by_quantile[0.5], by_quantile.get(0.1), by_quantile.get(0.9)


class LEARForecaster:
    seed = None  # deterministic: one run, whatever --seeds says
    history_days = None  # its calibration windows reach back up to four years

    def __init__(self, model_id: str, extra_exog: list[str], recal_every: int, n_jobs: int):
        self.id, self.model_version = model_id, f"{model_id}_recal{recal_every}d"
        self.model = lear.LEARForecaster(exog_cols=[*lear.EXOG_COLS, *extra_exog], recal_every=recal_every,
                                         price_col=PRICE, n_jobs=n_jobs)

    def fit(self, frame: pd.DataFrame, train_end: pd.Timestamp) -> None:
        """Nothing per fold: predict_day calibrates on the history before each day."""

    def predict_day(self, history: pd.DataFrame, day: pd.Timestamp):
        try:
            return self.model.predict_day(history, day), None, None
        except lear.MissingInputError as exc:
            raise lm.MissingInputError(str(exc)) from exc


def lstm_artifact_path(model_id: str, train_end: pd.Timestamp, seed: int) -> Path:
    return FOLDS_DIR / f"{model_id}_trainend_{train_end.date()}_seed{seed}.pt"


def train_missing_lstms(jobs: list[dict], data_dir: Path, workers: int, max_epochs: int | None) -> None:
    """Run train_lstm.py for every fold artifact not already cached, a few at
    a time. Each gets its own process (one torch thread is as fast as many
    for this model) and writes its log next to its artifact."""
    todo = [j for j in jobs if not j["path"].exists()]
    if not todo:
        return
    FOLDS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Training {len(todo)} LSTM artifact(s), {workers} at a time ...", flush=True)

    def train(job: dict) -> None:
        cmd = [sys.executable, str(OUT_DIR / "train_lstm.py"), "--fit-on", "train", "--seed", str(job["seed"]),
               "--train-end", str(job["train_end"].date()), "--out", str(job["path"]), "--threads", "1",
               "--data-dir", str(data_dir), "--extra-exog", ",".join(job["extra_exog"])]
        if max_epochs:
            cmd += ["--max-epochs", str(max_epochs)]
        with open(job["path"].with_suffix(".log"), "w") as log:
            result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode != 0:
            raise RuntimeError(f"Training failed - see {job['path'].with_suffix('.log')}")
        print(f"  trained {job['path'].name}", flush=True)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(train, todo))


# --------------------------------------------------------------------------- #
# Forecast table
# --------------------------------------------------------------------------- #

def make_forecasts(forecaster, frame: pd.DataFrame, days: pd.DatetimeIndex, fold: str,
                   train_end: pd.Timestamp) -> pd.DataFrame:
    """Long table, one row per (hour, model, seed):
    time, model, seed, horizon_h (1-24), actual, pred, p10, p90 + traceability."""
    rows = []
    for day in days:
        try:
            history = day_history(frame, day, history_days=forecaster.history_days)
            pred, p10, p90 = forecaster.predict_day(history, day)
        except lm.MissingInputError as exc:
            print(f"  {forecaster.id} (seed {forecaster.seed}): no forecast for {day.date()} - {exc}")
            continue
        hours = day_hours(day)
        rows.append(pd.DataFrame({
            "time": hours, "horizon_h": np.arange(1, 25), "pred": pred,
            "p10": np.nan if p10 is None else p10, "p90": np.nan if p90 is None else p90,
            "actual": frame[PRICE].reindex(hours).to_numpy(),
            "actual_winsorized": frame[WINSORIZED_COL].reindex(hours).to_numpy(),
        }))
    out = pd.concat(rows, ignore_index=True)
    out["model"], out["seed"], out["fold"] = forecaster.id, forecaster.seed, fold
    out["model_version"], out["train_end"] = forecaster.model_version, train_end
    return out


def keep_common_days(fc: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    """Same days, same hours for every model: a day stays only if every
    model/seed produced all 24 forecasts for it."""
    fc = fc.assign(day=fc["time"].dt.floor("D"), run=fc["model"] + "/" + fc["seed"].astype(str))
    ok = fc["pred"].notna() & fc["actual"].notna()
    complete = ok.groupby([fc["run"], fc["day"]]).sum().eq(24).unstack("run", fill_value=False)
    good_days = complete.index[complete.all(axis=1)]
    dropped = fc["day"].nunique() - len(good_days)
    return fc[fc["day"].isin(good_days)].drop(columns="run").reset_index(drop=True), dropped


def leakage_test(forecasters: list, frame: pd.DataFrame, days: pd.DatetimeIndex) -> float:
    """Re-forecast a sample of days with an absurd price planted at every hour
    from D 00:00 on. Any model that reads price at or after D 00:00 changes
    its forecast; returns the largest change seen (must be 0)."""
    sample = days[np.linspace(0, len(days) - 1, min(LEAK_TEST_DAYS, len(days))).astype(int)]
    worst = 0.0
    for forecaster in forecasters:
        for day in sample:
            span = forecaster.history_days
            clean = forecaster.predict_day(day_history(frame, day, history_days=span), day)[0]
            planted = forecaster.predict_day(day_history(frame, day, 99999.0, span), day)[0]
            worst = max(worst, float(np.abs(np.asarray(clean) - np.asarray(planted)).max()))
    return worst


# --------------------------------------------------------------------------- #
# Metrics
# --------------------------------------------------------------------------- #

def pinball(actual: pd.Series, pred: pd.Series, q: float) -> float:
    diff = actual - pred
    return float(np.maximum(q * diff, (q - 1) * diff).mean())


def score(df: pd.DataFrame) -> dict:
    """All metrics for one model run (one seed), in $/MWh against the actual price."""
    err = df["pred"] - df["actual"]
    abs_err = err.abs()
    priced = df["actual"].abs() >= SMAPE_MIN_ABS_PRICE
    smape = 200 * abs_err[priced] / (df.loc[priced, "actual"].abs() + df.loc[priced, "pred"].abs())
    err_w = df["pred"] - df["actual_winsorized"]

    actual = df.pivot(index="day", columns="horizon_h", values="actual")
    pred = df.pivot(index="day", columns="horizon_h", values="pred")
    cheapest_actual = np.argsort(actual.to_numpy(), axis=1, kind="stable")[:, :CHEAPEST_N]
    cheapest_pred = np.argsort(pred.to_numpy(), axis=1, kind="stable")[:, :CHEAPEST_N]
    hits = [len(set(a) & set(p)) / CHEAPEST_N for a, p in zip(cheapest_actual, cheapest_pred)]
    spearman = actual.rank(axis=1).corrwith(pred.rank(axis=1), axis=1)

    out = {
        "mae": float(abs_err.mean()), "rmse": float(np.sqrt((err ** 2).mean())),
        "medae": float(abs_err.median()), "smape": float(smape.mean()), "bias": float(err.mean()),
        "mae_winsorized": float(err_w.abs().mean()), "rmse_winsorized": float(np.sqrt((err_w ** 2).mean())),
        "spike_mae": float(abs_err[df["is_spike"]].mean()), "normal_mae": float(abs_err[~df["is_spike"]].mean()),
        "low_mae": float(abs_err[df["is_low"]].mean()), "low_bias": float(err[df["is_low"]].mean()),
        "cheap_hit": float(np.mean(hits)), "spearman": float(spearman.mean()),
    }
    if df["p10"].notna().all() and df["p90"].notna().all():
        out.update({
            "coverage": float(((df["actual"] >= df["p10"]) & (df["actual"] <= df["p90"])).mean()),
            "below_p10": float((df["actual"] < df["p10"]).mean()),
            "above_p90": float((df["actual"] > df["p90"]).mean()),
            "width": float((df["p90"] - df["p10"]).mean()),
            "pinball_10": pinball(df["actual"], df["p10"], 0.1),
            "pinball_50": pinball(df["actual"], df["pred"], 0.5),
            "pinball_90": pinball(df["actual"], df["p90"], 0.9),
        })
    return out


def score_models(fc: pd.DataFrame) -> pd.DataFrame:
    """Per model: mean and std of every metric across its seeds (std is NaN
    for single-run models). Columns are (metric, 'mean'|'std')."""
    per_run = pd.DataFrame([{"model": model, "seed": seed, **score(df)}
                            for (model, seed), df in fc.groupby(["model", "seed"], dropna=False, sort=False)])
    stats = per_run.drop(columns="seed").groupby("model", sort=False).agg(["mean", "std"])
    stats[("n_seeds", "mean")] = per_run.groupby("model", sort=False).size()
    return stats


def mean_over_seeds(fc: pd.DataFrame, value: str, by: list[str]) -> pd.Series:
    """Metric per model and `by` group: computed per seed, then averaged."""
    per_seed = fc.groupby(["model", "seed", *by], dropna=False, sort=False)[value].mean()
    return per_seed.groupby(["model", *by], sort=False).mean()


def daily_abs_error(fc: pd.DataFrame, model: str) -> pd.Series:
    """Mean absolute error per day (averaged over the model's seeds)."""
    rows = fc[fc["model"] == model]
    return (rows["pred"] - rows["actual"]).abs().groupby(rows["day"]).mean().sort_index()


def compare(fc: pd.DataFrame, a: str, b: str) -> dict:
    """Is model a's MAE different from model b's? Diebold-Mariano test on daily
    mean absolute error with a Newey-West (Bartlett, 7-day) variance, plus a
    7-day moving-block bootstrap interval for the MAE difference a - b.
    Negative = a is better."""
    d = (daily_abs_error(fc, a) - daily_abs_error(fc, b)).to_numpy()
    n = len(d)
    centered = d - d.mean()
    variance = centered @ centered / n
    for k in range(1, min(HAC_LAG_DAYS, n - 1) + 1):
        variance += 2 * (1 - k / (HAC_LAG_DAYS + 1)) * (centered[k:] @ centered[:-k]) / n
    stat = d.mean() / math.sqrt(variance / n)

    rng = np.random.default_rng(0)
    block = min(BOOTSTRAP_BLOCK_DAYS, n)
    n_blocks = math.ceil(n / block)
    starts = rng.integers(0, n - block + 1, size=(BOOTSTRAP_DRAWS, n_blocks))
    idx = (starts[:, :, None] + np.arange(block)).reshape(BOOTSTRAP_DRAWS, -1)[:, :n]
    draws = d[idx].mean(axis=1)
    lo, hi = np.quantile(draws, [0.025, 0.975])
    return {"a": a, "b": b, "delta_mae": float(d.mean()), "ci_lo": float(lo), "ci_hi": float(hi),
            "dm_stat": float(stat), "p_value": math.erfc(abs(stat) / math.sqrt(2)), "n_days": n,
            "significant": bool(hi < 0 or lo > 0)}


def apply_rule(pooled: dict, by_fold: list[dict]) -> tuple[bool, int, int, bool]:
    """The verdict rule for "a is better than b": ΔMAE < 0 with a CI excluding
    0 on the pooled test days, and the same in at least 4 of 5 folds.
    Returns (better on the pooled days, folds with lower MAE, folds where it
    also excludes 0, rule met)."""
    better = pooled["delta_mae"] < 0 and pooled["significant"]
    lower = sum(c["delta_mae"] < 0 for c in by_fold)
    holds = sum(c["delta_mae"] < 0 and c["significant"] for c in by_fold)
    return better, lower, holds, bool(by_fold) and better and holds >= math.ceil(0.8 * len(by_fold))


# --------------------------------------------------------------------------- #
# Report helpers
# --------------------------------------------------------------------------- #

def fmt(mean: float, std: float = float("nan"), spec: str = ".2f") -> str:
    if pd.isna(mean):
        return "-"
    return f"{mean:{spec}}" if pd.isna(std) else f"{mean:{spec}} ± {std:{spec}}"


def stat_cell(stats: pd.DataFrame, model: str, metric: str, spec: str = ".2f") -> str:
    if metric not in stats.columns.get_level_values(0):
        return "-"
    return fmt(stats.loc[model, (metric, "mean")], stats.loc[model, (metric, "std")], spec)


def markdown_table(rows: list[dict]) -> str:
    cols = list(rows[0])
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    lines += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for r in rows]
    return "\n".join(lines)


def pivot_table(series: pd.Series, row_name: str, models: list[str], row_labels=None) -> str:
    """model x group Series -> markdown table with one row per group."""
    wide = series.unstack(0)[models]
    return markdown_table([{row_name: (row_labels or {}).get(idx, idx), **{m: f"{wide.loc[idx, m]:.2f}" for m in models}}
                           for idx in wide.index])


def comparison_row(c: dict) -> dict:
    return {"Comparison": f"{c['a']} − {c['b']}", "ΔMAE": f"{c['delta_mae']:+.2f}",
            "95% CI": f"[{c['ci_lo']:+.2f}, {c['ci_hi']:+.2f}]", "DM statistic": f"{c['dm_stat']:.2f}",
            "p-value": f"{c['p_value']:.4f}", "Days": c["n_days"],
            "CI excludes 0": "yes" if c["significant"] else "no"}


# --------------------------------------------------------------------------- #
# Plots
# --------------------------------------------------------------------------- #

def model_color(model: str, models: list[str]) -> str:
    if model in MODEL_COLORS:
        return MODEL_COLORS[model]
    variants = [m for m in models if m not in MODEL_COLORS]
    return VARIANT_COLORS[variants.index(model) % len(VARIANT_COLORS)]


def make_plots(fc: pd.DataFrame, frame: pd.DataFrame, models: list[str], mode: str, plots_dir: Path) -> list[str]:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({
        "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
        "axes.edgecolor": "#c3c2b7", "axes.labelcolor": "#52514e", "text.color": INK,
        "xtick.color": MUTED, "ytick.color": MUTED, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
        "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 11, "axes.titleweight": "bold",
        "axes.titlelocation": "left", "font.size": 9, "legend.frameon": False, "lines.linewidth": 2,
    })
    plots_dir.mkdir(parents=True, exist_ok=True)
    fc = fc.assign(err=fc["pred"] - fc["actual"], abs_err=(fc["pred"] - fc["actual"]).abs())
    written = []

    def style(model):
        return {"color": model_color(model, models), "dashes": MODEL_DASHES.get(model, (None, None))}

    def save(fig, name):
        path = plots_dir / f"{mode}_{name}.png"
        fig.savefig(path, dpi=150, bbox_inches="tight")
        plt.close(fig)
        written.append(path.name)

    # MAE by hour of day - one line per model, labelled at the line end.
    by_hour = mean_over_seeds(fc, "abs_err", ["horizon_h"]).unstack(0)
    fig, ax = plt.subplots(figsize=(8, 4))
    for m in models:
        ax.plot(by_hour.index - 1, by_hour[m], label=m, **style(m))
    ax.set(title="MAE by hour of day", xlabel="Hour (UTC)", ylabel="MAE ($/MWh)", xticks=range(0, 24, 2), ylim=(0, None))
    ax.grid(axis="x", visible=False)
    ax.legend(ncols=len(models), loc="upper center", bbox_to_anchor=(0.5, -0.16))
    save(fig, "mae_by_hour")

    # Sample weeks: actual vs the two real models, LSTM P10-P90 band.
    recent = fc[fc["fold"] == fc["fold"].max()]
    days = np.sort(recent["day"].unique())
    weeks = [pd.DatetimeIndex(days[i:i + 7]) for i in range(0, len(days) - 6, 7)]
    actual = frame[PRICE]
    spread = [actual.loc[w[0]:w[-1] + pd.Timedelta(hours=23)].std() for w in weeks]
    peak = [actual.loc[w[0]:w[-1] + pd.Timedelta(hours=23)].max() for w in weeks]
    picks = [("Calm week (lowest price spread)", weeks[int(np.argmin(spread))]),
             ("Spike week (highest price)", weeks[int(np.argmax(peak))]),
             ("Most recent week", pd.DatetimeIndex(days[-7:]))]
    shown = [m for m in models if not m.startswith("naive")]
    fig, axes = plt.subplots(3, 1, figsize=(10, 9))
    for ax, (title, week) in zip(axes, picks):
        rows = recent[recent["day"].isin(week)]
        truth = rows[rows["model"] == rows["model"].iloc[0]].drop_duplicates("time").set_index("time")["actual"]
        ax.plot(truth.index, truth, color=INK, linewidth=1.5, label="actual")
        for m in shown:
            run = rows[rows["model"] == m]
            run = run[run["seed"] == run["seed"].min()].set_index("time").sort_index()
            ax.plot(run.index, run["pred"], label=m, **style(m))
            if run["p10"].notna().all():
                ax.fill_between(run.index, run["p10"], run["p90"], color=model_color(m, models), alpha=0.15,
                                linewidth=0, label=f"{m} P10–P90")
        ax.set(title=f"{title}: {week[0].date()} to {week[-1].date()}", ylabel="$/MWh")
        ax.grid(axis="x", visible=False)
    axes[0].legend(ncols=6, loc="lower left", bbox_to_anchor=(0, 1.12))
    fig.tight_layout()
    save(fig, "sample_weeks")

    # Residual distribution (clipped so the bulk is readable).
    fig, ax = plt.subplots(figsize=(8, 4))
    bins = np.linspace(-40, 40, 81)
    for m in shown:
        ax.hist(fc.loc[fc["model"] == m, "err"].clip(-40, 40), bins=bins, histtype="step", linewidth=2,
                density=True, label=m, color=model_color(m, models))
    ax.axvline(0, color=MUTED, linewidth=1)
    ax.set(title="Forecast error distribution (pred − actual, clipped to ±$40)", xlabel="$/MWh", ylabel="Density")
    ax.grid(axis="x", visible=False)
    ax.legend()
    save(fig, "residual_hist")

    # Bias: mean error by hour, month and load decile.
    load = frame["load_forecast_dam_north_mwh"].reindex(fc["time"]).to_numpy()
    fc = fc.assign(month=fc["time"].dt.strftime("%Y-%m"), hour=fc["horizon_h"] - 1,
                   load_decile=pd.qcut(pd.Series(load, index=fc.index), 10, labels=False, duplicates="drop") + 1)
    fig, axes = plt.subplots(1, 3, figsize=(14, 3.8), sharey=True)
    for ax, (col, label) in zip(axes, [("hour", "Hour (UTC)"), ("month", "Month"),
                                       ("load_decile", "Load forecast decile (1 = lowest)")]):
        bias = mean_over_seeds(fc, "err", [col]).unstack(0).sort_index()
        x = np.arange(len(bias))
        for m in shown:
            ax.plot(x, bias[m], marker="o", markersize=4, label=m, **style(m))
        ax.axhline(0, color=MUTED, linewidth=1)
        step = max(1, len(x) // 8)
        ax.set_xticks(x[::step], [str(v) for v in bias.index[::step]], rotation=30 if col == "month" else 0)
        ax.set(title=f"Mean error by {label.split(' (')[0].lower()}", xlabel=label)
        ax.grid(axis="x", visible=False)
    axes[0].set_ylabel("Mean error, pred − actual ($/MWh)")
    axes[0].legend()
    fig.tight_layout()
    save(fig, "bias")

    # Per-fold MAE (walk-forward only), LSTM seed spread as error bars.
    if fc["fold"].nunique() > 1:
        per_seed = fc.groupby(["model", "seed", "fold"], dropna=False, sort=False)["abs_err"].mean()
        mean = per_seed.groupby(["model", "fold"], sort=False).mean().unstack(0)
        std = per_seed.groupby(["model", "fold"], sort=False).std().unstack(0)
        fig, ax = plt.subplots(figsize=(9, 4))
        width = 0.8 / len(models)
        x = np.arange(len(mean))
        for i, m in enumerate(models):
            ax.bar(x + (i - (len(models) - 1) / 2) * width, mean[m], width * 0.9, label=m,
                   color=model_color(m, models), yerr=None if std[m].isna().all() else std[m],
                   error_kw={"ecolor": INK, "elinewidth": 1, "capsize": 2})
        ax.set(title="MAE per walk-forward fold (bars: mean over seeds, whiskers: ± 1 std)",
               xlabel="Fold", ylabel="MAE ($/MWh)")
        ax.set_xticks(x, [f"Fold {f}" for f in mean.index])
        ax.grid(axis="x", visible=False)
        ax.legend(ncols=len(models), loc="upper center", bbox_to_anchor=(0.5, -0.16))
        save(fig, "mae_by_fold")
    return written


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main() -> None:
    parser = argparse.ArgumentParser(description="Validate and compare the LZ_NORTH price models.")
    parser.add_argument("--mode", choices=["holdout", "walkforward"], default="holdout")
    parser.add_argument("--models", default="naive_d1,naive_w1,xgb,lstm")
    parser.add_argument("--seeds", help="LSTM seeds, comma-separated (default: 0 for holdout, 0,1,2 for walkforward)")
    parser.add_argument("--variant", action="append", default=[], metavar="NAME=col1,col2",
                        help="also evaluate lstm_NAME / lear_NAME: that model with these extra EXOG_COLS")
    parser.add_argument("--recal-every", type=int, default=1, metavar="N",
                        help="LEAR: recalibrate every N days (1 = daily, as LEAR is defined; 7 for quick runs)")
    parser.add_argument("--lear-jobs", type=int, default=8, help="LEAR: processes fitting hour-models at once")
    parser.add_argument("--folds", help="walkforward: only these folds, e.g. 3,4,5")
    parser.add_argument("--xgb", type=Path, default=MODELS_DIR / "xgboost_baseline_train.json")
    parser.add_argument("--lstm", type=Path, default=MODELS_DIR / "lstm_train.pt",
                        help="holdout: the --fit-on train artifact used for lstm seed 0")
    parser.add_argument("--data-dir", type=Path, help="folder holding train.csv and val.csv")
    parser.add_argument("--out-dir", type=Path, default=REPORTS_DIR)
    parser.add_argument("--jobs", type=int, default=5, help="LSTM trainings to run at once")
    parser.add_argument("--max-epochs", type=int, help="cap LSTM training epochs (smoke tests only)")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="  %(name)s: %(message)s")

    data_dir = args.data_dir or find_data_dir()
    frame = load_history(data_dir)
    models = [m for m in args.models.split(",") if m]
    variants = {name: cols.split(",") for name, cols in (v.split("=", 1) for v in args.variant)}
    # A variant extends whichever of lstm/lear is being evaluated (the LSTM if neither is named).
    lstm_extra, lear_extra = {"lstm": []}, {"lear": []}
    if "lear" in models:
        lear_extra.update({f"lear_{name}": cols for name, cols in variants.items()})
        absent = sorted({c for cols in variants.values() for c in cols if c not in frame.columns})
        if absent:
            raise SystemExit(f"--variant columns not in train.csv/val.csv: {absent}")
    if "lstm" in models or "lear" not in models:
        lstm_extra.update({f"lstm_{name}": cols for name, cols in variants.items()})
    models += [m for m in [*lstm_extra, *lear_extra] if m not in ("lstm", "lear")]
    seeds = [int(s) for s in (args.seeds or ("0" if args.mode == "holdout" else "0,1,2")).split(",")]
    wanted = [HOLDOUT_FOLD] if args.mode == "holdout" else (args.folds.split(",") if args.folds else None)
    folds = [f for f in FOLDS if wanted is None or f[0] in wanted]

    # Which LSTM artifact serves each (model, fold, seed) - and train the ones not cached yet.
    lstm_jobs = []
    for fold, train_end, _, _ in folds:
        train_end = pd.Timestamp(train_end) + pd.Timedelta(hours=23)
        for model in (m for m in models if m in lstm_extra):
            for seed in seeds:
                path = lstm_artifact_path(model, train_end, seed)
                if args.mode == "holdout" and model == "lstm" and seed == 0 and args.lstm.exists():
                    path = args.lstm
                lstm_jobs.append({"fold": fold, "model": model, "seed": seed, "train_end": train_end,
                                  "path": path, "extra_exog": lstm_extra[model]})
    train_missing_lstms(lstm_jobs, data_dir, args.jobs, args.max_epochs)

    tables, fold_info, leak, reference_rows = [], [], 0.0, []
    lear_seconds = {m: 0.0 for m in models if m in lear_extra}
    for fold, train_end, first, last in folds:
        train_end = pd.Timestamp(train_end) + pd.Timedelta(hours=23)
        days = test_days(frame, first, last)
        print(f"Fold {fold}: train <= {train_end.date()}, test {days[0].date()} .. {days[-1].date()} "
              f"({len(days)} days)", flush=True)
        forecasters = []
        for model in models:
            if model.startswith("naive"):
                forecasters.append(NaiveForecaster(model, {"naive_d1": 1, "naive_w1": 7}[model]))
            elif model == "xgb":
                forecasters.append(XGBForecaster(args.xgb if args.mode == "holdout" else None))
            elif model in lear_extra:
                forecasters.append(LEARForecaster(model, lear_extra[model], args.recal_every, args.lear_jobs))
            else:
                forecasters += [LSTMForecaster(model, j["seed"], j["path"]) for j in lstm_jobs
                                if j["fold"] == fold and j["model"] == model]
        for forecaster in forecasters:
            forecaster.fit(frame, train_end)
            started = time.perf_counter()
            tables.append(make_forecasts(forecaster, frame, days, fold, train_end))
            if forecaster.id in lear_seconds:
                lear_seconds[forecaster.id] += time.perf_counter() - started
                print(f"  {forecaster.id}: {len(days)} days in {time.perf_counter() - started:.0f} s", flush=True)
        leak = max(leak, leakage_test(forecasters, frame, days))
        fold_info.append({"fold": fold, "train_end": train_end, "n_days": len(days)})

        if fold == HOLDOUT_FOLD and "xgb" in models:
            # Reference check: the same XGBoost on the CSV's own (leaky) feature columns.
            hours = pd.DatetimeIndex(np.concatenate([day_hours(d) for d in days]))
            xgb_model = next(f for f in forecasters if f.id == "xgb").model
            err = xgb_model.predict(frame.loc[hours, P.FEATURE_COLUMNS]) - frame.loc[hours, PRICE]
            reference_rows.append(("xgb on CSV features (leaky rolls)", float(err.abs().mean()),
                                   float(np.sqrt((err ** 2).mean()))))

    fc, dropped = keep_common_days(pd.concat(tables, ignore_index=True))
    fc["seed"] = fc["seed"].astype("Int64")
    spike_threshold = fc.groupby("fold")["actual"].quantile(SPIKE_QUANTILE)
    fc["is_spike"] = fc["actual"] > fc["fold"].map(spike_threshold)
    low_threshold = fc.groupby("fold")["actual"].quantile(LOW_PRICE_QUANTILE)
    fc["is_low"] = fc["actual"] < fc["fold"].map(low_threshold)

    # Every run must cover exactly the same timestamps, 24 per day.
    stamps = fc.groupby(["model", "seed"], dropna=False)["time"].apply(lambda t: tuple(t.sort_values()))
    assert stamps.nunique() == 1, "models were scored on different timestamps"
    assert (fc.groupby(["model", "seed", "day"], dropna=False).size() == 24).all()
    n_days, n_hours = fc["day"].nunique(), fc["time"].nunique()

    stats = score_models(fc)
    naive_mae = stats.loc["naive_d1", ("mae", "mean")] if "naive_d1" in models else float("nan")
    multi_fold = len(folds) > 1
    lstm_models = [m for m in models if m in lstm_extra]
    lear_models = [m for m in models if m in lear_extra]

    # ---- significance ----
    pairs = [(m, "xgb") for m in lstm_models if "xgb" in models]
    pairs += [(m, "naive_d1") for m in models if m != "naive_d1" and "naive_d1" in models and m != "naive_w1"]
    pairs += [(m, "lstm") for m in lstm_models if m != "lstm" and "lstm" in models]
    pairs += [(m, b) for m in lear_models for b in ("xgb", "lstm") if b in models]
    pairs += [(m, "lear") for m in lear_models if m != "lear" and "lear" in models]
    pooled = [compare(fc, a, b) for a, b in pairs]
    per_fold = {fold: [compare(df, a, b) for a, b in pairs] for fold, df in fc.groupby("fold")} if multi_fold else {}

    # ---- report ----
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    plots = make_plots(fc, frame, models, args.mode, out_dir / "plots")

    period = f"{fc['time'].min().date()} to {fc['time'].max().date()}"
    title = "hold-out" if args.mode == "holdout" else f"walk-forward, {len(folds)} folds"
    lines = [f"# Price forecast validation ({title}) - {date.today().isoformat()}", "",
             f"Scored: {n_days} days / {n_hours} hours, {period}. {dropped} day(s) dropped because a model "
             f"could not forecast them. All values are $/MWh against the actual `{RAW_PRICE_COL}`.",
             f"LSTM seeds: {', '.join(map(str, seeds))}"
             + (" (cells show mean ± std across seeds)." if len(seeds) > 1 else ".")]
    lines += [f"LEAR runtime ({m}): {secs:.0f} s wall-clock for the {sum(i['n_days'] for i in fold_info)} test "
              f"days of {'the full walk-forward run' if multi_fold else 'this run'} "
              f"({secs / sum(i['n_days'] for i in fold_info):.2f} s per day), recalibrating every "
              f"{args.recal_every} day(s) with {args.lear_jobs} worker process(es)."
              for m, secs in lear_seconds.items()]
    lines.append("")

    # verdict
    lines += ["## Verdict", ""]
    main_pair = next((c for c in pooled if (c["a"], c["b"]) == ("lstm", "xgb")), None)
    if main_pair:
        fold_pairs = [next(c for c in cs if (c["a"], c["b"]) == ("lstm", "xgb")) for cs in per_fold.values()]
        better, lower, holds, passed = apply_rule(main_pair, fold_pairs)
        lines.append(f"- LSTM − XGBoost MAE: **{main_pair['delta_mae']:+.2f}** (95% CI "
                     f"[{main_pair['ci_lo']:+.2f}, {main_pair['ci_hi']:+.2f}], DM p = {main_pair['p_value']:.4f}).")
        if multi_fold:
            lines.append(f"- LSTM has the lower MAE in {lower}/{len(fold_pairs)} folds; lower with a CI "
                         f"excluding 0 in {holds}/{len(fold_pairs)}.")
            lines.append(f"- Rule (ΔMAE < 0, CI excludes 0, and that holds in ≥ 4/5 folds): "
                         f"**{'LSTM is better' if passed else 'not met - LSTM is not shown to be better'}**.")
        else:
            lines.append(f"- On this one period the LSTM is {'**better**' if better else '**not shown to be better**'}"
                         " (ΔMAE < 0 with a CI excluding 0). The ≥ 4/5-folds half of the rule needs "
                         "`--mode walkforward`.")
        by_hour = mean_over_seeds(fc.assign(abs_err=(fc["pred"] - fc["actual"]).abs()), "abs_err",
                                  ["horizon_h"]).unstack(0)
        xgb_hours = [str(h - 1) for h in by_hour.index[by_hour["xgb"] < by_hour["lstm"]]]
        lines.append("- By hour of day, XGBoost has the lower MAE at hour(s) "
                     f"{', '.join(xgb_hours)} UTC; the LSTM everywhere else." if xgb_hours
                     else "- The LSTM has the lower MAE at every hour of the day.")
        lines.append(f"- Spike hours: LSTM {stat_cell(stats, 'lstm', 'spike_mae')} vs XGBoost "
                     f"{stat_cell(stats, 'xgb', 'spike_mae')}. Other hours: LSTM "
                     f"{stat_cell(stats, 'lstm', 'normal_mae')} vs XGBoost {stat_cell(stats, 'xgb', 'normal_mae')}.")
        if ("coverage", "mean") in stats.columns:
            lines.append(f"- LSTM P10–P90 interval covers {stat_cell(stats, 'lstm', 'coverage', '.1%')} of actuals "
                         "(target ≈ 80%).")
    for pair in (c for c in pooled if c["a"] == "lear"):
        fold_pairs = [next(c for c in cs if (c["a"], c["b"]) == ("lear", pair["b"])) for cs in per_fold.values()]
        better, lower, holds, passed = apply_rule(pair, fold_pairs)
        line = (f"- LEAR − {pair['b']} MAE: **{pair['delta_mae']:+.2f}** (95% CI [{pair['ci_lo']:+.2f}, "
                f"{pair['ci_hi']:+.2f}], DM p = {pair['p_value']:.4f}). ")
        if multi_fold:
            outcome = "LEAR is better" if passed else "rule not met - LEAR is not shown to be better"
            line += (f"Lower MAE in {lower}/{len(fold_pairs)} folds, with a CI excluding 0 in {holds}/"
                     f"{len(fold_pairs)}: **{outcome}**.")
        else:
            line += (f"On this one period LEAR is {'**better**' if better else '**not shown to be better**'}; "
                     "the ≥ 4/5-folds half of the rule needs `--mode walkforward`.")
        lines.append(line)
    if lear_models:
        lines.append(f"- Cheap-{CHEAPEST_N} hit rate: " + ", ".join(
            f"{m} {stat_cell(stats, m, 'cheap_hit', '.3f')}" for m in models) + ".")
    lines.append("")

    lines += ["## Summary", "", markdown_table([{
        "Model": m, "MAE": stat_cell(stats, m, "mae"), "RMSE": stat_cell(stats, m, "rmse"),
        "MedAE": stat_cell(stats, m, "medae"),
        "Skill vs naive": fmt(1 - stats.loc[m, ("mae", "mean")] / naive_mae, spec="+.1%"),
        "Spike MAE": stat_cell(stats, m, "spike_mae"), f"Cheap-{CHEAPEST_N} hit": stat_cell(stats, m, "cheap_hit", ".3f"),
        "Coverage P10–P90": stat_cell(stats, m, "coverage", ".1%"),
    } for m in models]), "",
        "Skill = 1 − MAE / MAE of naive_d1. Spike hours = actual above the test period's 95th percentile "
        f"(per fold). Cheap-{CHEAPEST_N} hit = share of the day's {CHEAPEST_N} cheapest actual hours that are "
        f"also among the model's {CHEAPEST_N} cheapest predicted hours.", ""]

    skipped = int((fc.drop_duplicates("time")["actual"].abs() < SMAPE_MIN_ABS_PRICE).sum())
    lines += ["## More point metrics", "", markdown_table([{
        "Model": m, "sMAPE (%)": stat_cell(stats, m, "smape", ".1f"),
        "Mean error (bias)": stat_cell(stats, m, "bias", "+.2f"),
        "Rank correlation": stat_cell(stats, m, "spearman", ".3f"),
        "Low-price MAE": stat_cell(stats, m, "low_mae"),
        "Low-price bias": stat_cell(stats, m, "low_bias", "+.2f"),
        "Normal-hour MAE": stat_cell(stats, m, "normal_mae"),
        "MAE (winsorized)": stat_cell(stats, m, "mae_winsorized"),
        "RMSE (winsorized)": stat_cell(stats, m, "rmse_winsorized"),
    } for m in models]), "",
        f"sMAPE skips the {skipped} hour(s) with |actual| < ${SMAPE_MIN_ABS_PRICE:.0f}. Rank correlation = "
        "Spearman between the predicted and actual 24-hour profiles of a day, averaged over days. Low-price "
        f"hours = actual below the test period's {LOW_PRICE_QUANTILE:.0%} quantile (per fold: "
        + ", ".join(f"fold {f} < ${v:.2f}" for f, v in low_threshold.items()) + "); low-price bias = mean "
        "(pred − actual) on those hours, positive when the model flattens the dips.", ""]

    if ("coverage", "mean") in stats.columns:
        lines += ["## Uncertainty (LSTM)", "", markdown_table([{
            "Model": m, "Coverage P10–P90": stat_cell(stats, m, "coverage", ".1%"),
            "Below P10": stat_cell(stats, m, "below_p10", ".1%"), "Above P90": stat_cell(stats, m, "above_p90", ".1%"),
            "Mean width": stat_cell(stats, m, "width"), "Pinball 0.1": stat_cell(stats, m, "pinball_10", ".3f"),
            "Pinball 0.5": stat_cell(stats, m, "pinball_50", ".3f"),
            "Pinball 0.9": stat_cell(stats, m, "pinball_90", ".3f"),
        } for m in lstm_models]), ""]

    lines += ["## Significance", "",
              f"ΔMAE on daily mean absolute error (LSTM = mean over its seeds). Diebold–Mariano with a "
              f"{HAC_LAG_DAYS}-day Newey–West variance; 95% CI from a {BOOTSTRAP_BLOCK_DAYS}-day block bootstrap, "
              f"{BOOTSTRAP_DRAWS} resamples. Negative = the first model is better.", "",
              markdown_table([comparison_row(c) for c in pooled]), ""]

    fc = fc.assign(err=fc["pred"] - fc["actual"], abs_err=(fc["pred"] - fc["actual"]).abs(),
                   sq_err=(fc["pred"] - fc["actual"]) ** 2)
    if multi_fold:
        fold_mae = fc.groupby(["model", "seed", "fold"], dropna=False, sort=False)["abs_err"].mean()
        fold_mean = fold_mae.groupby(["model", "fold"], sort=False).mean()
        fold_std = fold_mae.groupby(["model", "fold"], sort=False).std()
        rows = []
        for info in fold_info:
            f = info["fold"]
            sub = fc[fc["fold"] == f]
            row = {"Fold": f, "Train through": info["train_end"].date(),
                   "Test": f"{sub['time'].min().date()} to {sub['time'].max().date()}", "Days": sub["day"].nunique()}
            row.update({m: fmt(fold_mean[(m, f)], fold_std[(m, f)]) for m in models})
            for c in per_fold[f]:
                if c["b"] == "xgb" or (c["a"] != "lstm" and c["b"] == "lstm") or c["a"] in lear_models:
                    row[f"{c['a']} − {c['b']}"] = (f"{c['delta_mae']:+.2f} [{c['ci_lo']:+.2f}, {c['ci_hi']:+.2f}]"
                                                   f"{' *' if c['significant'] else ''}")
            rows.append(row)
        lines += ["## Per fold (MAE)", "", markdown_table(rows), "",
                  "\\* = 95% CI of the difference excludes 0.", ""]

    horizon_mae = mean_over_seeds(fc, "abs_err", ["horizon_h"]).unstack(0)[models]
    horizon_rmse = np.sqrt(mean_over_seeds(fc, "sq_err", ["horizon_h"]).unstack(0)[models])
    lines += ["## By horizon (hour of day)", "",
              "Horizon h = h hours after the D-1 23:00 UTC cutoff, i.e. UTC hour h−1 of the target day.", "",
              markdown_table([{"Horizon": h, **{f"{m} MAE": f"{horizon_mae.loc[h, m]:.2f}" for m in models},
                               **{f"{m} RMSE": f"{horizon_rmse.loc[h, m]:.2f}" for m in models}}
                              for h in horizon_mae.index]), ""]

    fc["month"] = fc["time"].dt.strftime("%Y-%m")
    fc["day_type"] = np.where(fc["time"].dt.dayofweek >= 5, "Weekend", "Weekday")
    fc["hour_type"] = np.where(fc["is_spike"], "Spike hours", "Normal hours")
    lines += ["## By month (MAE)", "", pivot_table(mean_over_seeds(fc, "abs_err", ["month"]), "Month", models), "",
              "## Spike vs normal hours (MAE)", "",
              pivot_table(mean_over_seeds(fc, "abs_err", ["hour_type"]), "Hours", models), "",
              "Spike threshold per fold: " + ", ".join(f"fold {f} > ${v:.2f}" for f, v in spike_threshold.items()) + ".",
              "", "## Weekday vs weekend (MAE)", "",
              pivot_table(mean_over_seeds(fc, "abs_err", ["day_type"]), "Days", models), ""]

    # sanity checks
    lines += ["## Sanity checks", "",
              f"- **Leakage:** {'PASS' if leak == 0 else 'FAIL'} - re-forecasting sample days with a price of "
              f"$99,999 planted from D 00:00 on changed forecasts by at most {leak:g}.",
              f"- **Coverage of the day:** PASS - every model has exactly 24 forecasts for each of the {n_days} "
              "days, on identical timestamps."]
    if HOLDOUT_FOLD in {f[0] for f in folds}:
        hold = score_models(fc[fc["fold"] == HOLDOUT_FOLD])
        checks = [(m, hold.loc[m, ("mae", "mean")], hold.loc[m, ("rmse", "mean")])
                  for m in ("naive_d1", "xgb") if m in models] + reference_rows
        ref_rows = []
        for name, got_mae, got_rmse in checks:
            ref_mae, ref_rmse = REFERENCE[name]
            ok = abs(got_mae - ref_mae) <= REFERENCE_TOLERANCE and abs(got_rmse - ref_rmse) <= REFERENCE_TOLERANCE
            ref_rows.append({"Model": name, "MAE": f"{got_mae:.2f}", "Reference MAE": ref_mae,
                             "RMSE": f"{got_rmse:.2f}", "Reference RMSE": ref_rmse,
                             f"Within ±{REFERENCE_TOLERANCE}": "yes" if ok else "NO"})
        lines += ["- **Reference numbers** (2026-02-01 to 2026-08-29):", "", markdown_table(ref_rows), ""]
    lines += ["", "## Plots", ""] + [f"![{name}](plots/{name})" for name in plots] + [""]

    stem = f"eval_{args.mode}_{date.today().isoformat()}"
    (out_dir / f"{stem}.md").write_text("\n".join(lines))
    keep = ["time", "fold", "model", "model_version", "train_end", "seed", "horizon_h", "actual",
            "actual_winsorized", "pred", "p10", "p90", "is_spike", "is_low"]
    fc[keep].to_parquet(out_dir / f"forecasts_{args.mode}.parquet", index=False)

    print("\n".join(lines[:lines.index("## By horizon (hour of day)")]))
    print(f"Report: {out_dir / f'{stem}.md'}\nForecasts: {out_dir / f'forecasts_{args.mode}.parquet'}\n"
          f"Plots: {out_dir / 'plots'}")


if __name__ == "__main__":
    main()
