"""
update.py

Weekly continuous-learning cron for the LZ_NORTH price models (model/model.md's
continuous-learning section - update.py decisions 1, 5, 6, 7; decision 4 is
recompute_reference_rmse.py, run at the end of this script; decision 3 is a
separate, standalone anomaly-detection experiment, model/anomaly_detection_experiment.py).
For each target (y0, y1) and both model kinds (lstm, lear):

  1. Tune a CANDIDATE artifact from the currently-active one, on a fixed-size
     sliding training window ending the day before the live validation
     window starts (point 5 - the model keeps generalizing from current
     conditions, not slowly outweighing them with years of accumulated
     samples):
       lstm   train_lstm.py --warm-start <active>, --window-days WINDOW_DAYS,
              a fresh torch.optim.Adam every run (point 6 - no optimizer
              state is carried between runs by design), and
              price_clip/scalers refreshed only every PREPROCESSING_REFRESH_DAYS
              days (point 7), never every week.
       lear   LEARForecaster.from_artifact(active) refit through the same
              cutoff - LEAR has no weights-vs-scalers split to begin with
              (model/lear_model.py's module docstring on recal_every), so
              there's nothing extra to freeze or schedule separately here.
  2. Score the candidate against the active artifact on the live validation
     window (ValidationState.validationStartDate .. yesterday). Promote only
     if the candidate's MAE is not worse - this is a plain MAE comparison,
     not model/evaluate.py's Diebold-Mariano/bootstrap rigor: that answers
     "is this difference real," appropriate for a one-off human-reviewed
     decision; this runs unattended every week and needs a cheap,
     deterministic accept/reject rule instead. A wrong promotion's safety
     net is model_artifacts' rollback (re-promote the previous row), not a
     more elaborate automated rule.
  3. Separately, the fold-in experiment (point 1): would absorbing the
     OLDEST remaining validation day into training hurt either kind's score
     on the REST of the window (validationStartDate + 1 .. yesterday)? Only
     advances ValidationState.validationStartDate if NEITHER kind is hurt -
     so LSTM and LEAR are always validated on the identical window
     (model/evaluate.py's keep_common_days exists for exactly this reason:
     every model scored on the same days, so comparisons stay meaningful).

Supabase is the source of truth throughout (point 1: "load all of it into
supabase... use <start> till yesterday as the validation set") - this script
builds its training/validation frames live from electrical_price/wind/solar/
fuel_price, not from model/train.csv|val.csv (those stay as the static
snapshot model/evaluate.py's offline backtests use; update.py and they are
deliberately on separate data paths). Before this script's first run, back-
fill any gap in that raw ingested data left by a missed cron day with the
existing one-shot tool: `python ingestion.py --since 2026-02-01` (ingestion.py's
own module docstring - no new backfill tool needed for that part of point 1).

Usage:
  python update.py                        # both targets, both kinds, today's "yesterday" as the data horizon
  python update.py --target y0             # only y0
  python update.py --kind lear             # only LEAR
  python update.py --dry-run               # tune and score, never promote or advance the validation cursor
"""

from __future__ import annotations

import argparse
import io
import logging
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import sqlalchemy as sa

import ingestion as ing
import model_store as store
import predictions as pred

logger = logging.getLogger("update")

SCRIPT_DIR = Path(__file__).resolve().parent
TRAIN_LSTM_SCRIPT = pred.MODEL_DIR / "train_lstm.py"

TARGETS = ("y0", "y1")
KINDS = ("lstm", "lear")

WINDOW_DAYS = 180  # point 5: sliding fixed-size training window
PREPROCESSING_REFRESH_DAYS = 30  # point 7: scalers/price_clip cadence
LSTM_MAX_FINE_TUNE_EPOCHS = 20  # a fine-tune from a warm start needs far fewer than a from-scratch fit
ARTIFACT_RETENTION = 12  # weekly snapshots kept per (kind, target) - see model_store.prune_old_artifacts


# --------------------------------------------------------------------------- #
# validation_state
# --------------------------------------------------------------------------- #

@dataclass
class ValidationState:
    target: str
    validation_start_date: date
    original_start_date: date
    last_checked_date: date | None


def read_validation_state(engine: sa.Engine, target: str) -> ValidationState:
    with engine.begin() as conn:
        row = conn.execute(sa.text(
            "SELECT validation_start_date, original_start_date, last_checked_date "
            "FROM validation_state WHERE target = :target"
        ), {"target": target}).mappings().first()
    if row is None:
        raise RuntimeError(f"No validation_state row for target={target!r} - seed it first "
                           "(backend/.../db/migration/V6__model_artifacts_and_validation_state.sql).")
    return ValidationState(
        target=target, validation_start_date=pd.Timestamp(row["validation_start_date"]).date(),
        original_start_date=pd.Timestamp(row["original_start_date"]).date(),
        last_checked_date=pd.Timestamp(row["last_checked_date"]).date() if row["last_checked_date"] else None,
    )


def advance_validation_start(engine: sa.Engine, target: str, new_start: date, checked_through: date) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text(
            "UPDATE validation_state SET validation_start_date = :new_start, "
            "last_checked_date = :checked_through, updated_at = :now WHERE target = :target"
        ), {"new_start": pred._iso(new_start), "checked_through": pred._iso(checked_through),
            "now": pred._iso(datetime.now(timezone.utc)), "target": target})


def mark_checked(engine: sa.Engine, target: str, checked_through: date) -> None:
    with engine.begin() as conn:
        conn.execute(sa.text(
            "UPDATE validation_state SET last_checked_date = :checked_through, updated_at = :now "
            "WHERE target = :target"
        ), {"checked_through": pred._iso(checked_through), "now": pred._iso(datetime.now(timezone.utc)),
            "target": target})


# --------------------------------------------------------------------------- #
# Frames, built live from Supabase (not model/train.csv|val.csv - see module docstring)
# --------------------------------------------------------------------------- #

def build_lstm_training_csv(engine: sa.Engine, start: pd.Timestamp, end: pd.Timestamp, out_path: Path) -> None:
    """A CSV with model/train.csv's exact columns, covering [start, end], written
    live from Supabase - train_lstm.py reads it unmodified via --data-dir, so
    fine-tuning gets exactly the same feature computation (predictions.assemble_xgb_features)
    production serving already uses, with no second implementation to drift
    out of sync with it. Needs 168h of price history before `start` for the
    rolling features' own lookback - fetched here, not left to the caller."""
    anchor_times = pd.date_range(start, end, freq="h")
    price = pred.load_price_history(engine, pred.DEFAULT_LOCATION, start - timedelta(hours=168))
    price_s = price.set_index("time")["price"].asfreq("h")
    exog = pred.load_exogenous(engine, anchor_times.min(), anchor_times.max())
    features = pred.assemble_xgb_features(price_s, exog, anchor_times)

    raw = price_s.reindex(anchor_times)
    out = features.reset_index().rename(columns={"index": "time"})
    out["lz_north_price"] = raw.to_numpy()
    out["lz_north_price_winsorized"] = raw.clip(pred.WINSORIZE_LOWER, pred.WINSORIZE_UPPER).to_numpy()
    out = out[["time", "lz_north_price", "lz_north_price_winsorized", "lag_72h", "lag_96h", "roll_mean_24h",
              "roll_std_24h", "roll_mean_168h", "roll_std_168h", "hour", "day_of_week", "month", "is_us_holiday",
              "henry_hub_price_usd_mmbtu_lag2d", "henry_hub_price_usd_mmbtu_lag3d",
              "wind_power_output_proxy_total", "solar_power_output_proxy_total"]]
    out.to_csv(out_path, index=False)


def build_lear_history(engine: sa.Engine, start: pd.Timestamp, end: pd.Timestamp, windows: tuple[int, ...],
                       exog_cols: list[str]) -> pd.DataFrame:
    """Hourly frame covering [start - max(windows) - 7 days, end] - the raw
    material for LEARForecaster.predict_day() across many target days (used
    for both scoring and refitting). Unlike predictions.build_lear_frame
    (scoped to one day's serving call, fetched fresh per call), this is built
    ONCE and reused across every day in the range - refetching ~4 years of
    history per validation day would be, at minimum, 270x more Supabase
    traffic than necessary for one validation run."""
    lear = pred._lear_module()
    history_start = start - timedelta(days=max(windows) + 7)
    frame = pred.load_exogenous(engine, history_start, end)
    unknown = [c for c in exog_cols if c not in frame.columns]
    if unknown:
        raise ValueError(f"LEAR needs exogenous columns load_exogenous doesn't provide: {unknown}")
    frame = pred.add_calendar_features(frame).set_index("time")
    price = pred.load_price_history(engine, pred.DEFAULT_LOCATION, history_start, until=end)
    frame[lear.RAW_PRICE_COL] = price.set_index("time")["price"]
    return frame.apply(pd.to_numeric, errors="coerce")


def settled_actuals(engine: sa.Engine, days: pd.DatetimeIndex, lead_hours: int) -> pd.DataFrame:
    """actual $/MWh for every target hour of every anchor day in `days` - the
    ground truth both the candidate and the active artifact are scored
    against on the live validation window."""
    first_target = days.min() + timedelta(hours=lead_hours)
    last_target = days.max() + timedelta(hours=lead_hours + 23)
    price = pred.load_price_history(engine, pred.DEFAULT_LOCATION, first_target, until=last_target)
    return price.set_index("time")["price"]


# --------------------------------------------------------------------------- #
# Scoring - plain MAE, not model/evaluate.py's full DM/bootstrap rigor (module docstring)
# --------------------------------------------------------------------------- #

def score_lstm(engine: sa.Engine, model, artifact: dict, days: pd.DatetimeIndex, actual: pd.Series) -> float:
    lead_hours = artifact["lead_hours"]
    errors = []
    for day in days:
        target_times = pd.date_range(day + timedelta(hours=lead_hours), periods=24, freq="h")
        try:
            sequence = pred.build_sequence(engine, target_times, pred.DEFAULT_LOCATION, artifact)
        except Exception as exc:  # noqa: BLE001 - a day this model can't score is dropped, not fatal to the run
            logger.warning("lstm: skipping %s - %s", day.date(), exc)
            continue
        _, preds, _ = pred.lstm_predict(model, sequence)
        truth = actual.reindex(target_times).to_numpy()
        errors.append(np.abs(preds - truth))
    return float(np.concatenate(errors).mean()) if errors else float("nan")


def score_lear(model, frame: pd.DataFrame, days: pd.DatetimeIndex, lead_hours: int, actual: pd.Series) -> float:
    lear = pred._lear_module()
    errors = []
    for day in days:
        target_day = day + timedelta(hours=lead_hours)
        target_times = pd.date_range(target_day, periods=24, freq="h")
        try:
            preds = model.predict_day(frame, target_day)
        except lear.MissingInputError as exc:
            logger.warning("lear: skipping %s - %s", day.date(), exc)
            continue
        truth = actual.reindex(target_times).to_numpy()
        errors.append(np.abs(preds - truth))
    return float(np.concatenate(errors).mean()) if errors else float("nan")


# --------------------------------------------------------------------------- #
# Tuning a candidate from the currently-active artifact
# --------------------------------------------------------------------------- #

def tune_lstm_candidate(engine: sa.Engine, target: str, train_end: date, active_row: dict | None) -> dict:
    """Fine-tune (warm-start) if there's an active artifact to start from,
    otherwise train from scratch (a brand-new deployment's first-ever run).
    Returns the new artifact dict (not yet saved)."""
    start = pd.Timestamp(train_end) - timedelta(days=WINDOW_DAYS - 1)
    refresh_preprocessing = True
    warm_start_path = None

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        build_lstm_training_csv(engine, start, pd.Timestamp(train_end) + timedelta(hours=23), tmp / "train.csv")
        cmd = [sys.executable, str(TRAIN_LSTM_SCRIPT), "--fit-on", "train", "--target", target,
              "--data-dir", str(tmp), "--train-end", str(train_end), "--window-days", str(WINDOW_DAYS),
              "--max-epochs", str(LSTM_MAX_FINE_TUNE_EPOCHS), "--out", str(tmp / "candidate.pt"), "--threads", "1"]
        if active_row is not None:
            warm_start_path = tmp / "active.pt"
            warm_start_path.write_bytes(active_row["blob"])
            _, active_artifact = pred._lstm_module().load_artifact(warm_start_path)
            last_refresh = pd.Timestamp(active_artifact.get("preprocessing_refreshed_at", train_end))
            refresh_preprocessing = (pd.Timestamp(train_end) - last_refresh).days >= PREPROCESSING_REFRESH_DAYS
            cmd += ["--warm-start", str(warm_start_path)]
            if refresh_preprocessing:
                cmd += ["--refresh-preprocessing"]  # default (omitted): reuse the warm-start source's
                                                    # price_clip/scalers (point 7)
        logger.info("lstm/%s: %s (warm_start=%s, refresh_preprocessing=%s)", target,
                   " ".join(cmd), warm_start_path is not None, refresh_preprocessing)
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f"train_lstm.py failed for {target}:\n{result.stdout}\n{result.stderr}")
        lstm = pred._lstm_module()
        _, artifact = lstm.load_artifact(tmp / "candidate.pt")
    return artifact


def tune_lear_candidate(engine: sa.Engine, target: str, train_end: date, active_row: dict | None) -> dict:
    """Refit LEAR's calibration through `train_end`. There's no separate
    weights-vs-preprocessing split to manage here (model/lear_model.py's
    module docstring) - one refit covers everything this cycle needs to
    update, every week."""
    lear = pred._lear_module()
    lead_hours = pred.LEAD_HOURS[target]
    lead_days = lead_hours // 24
    if active_row is not None:
        _, source_artifact = lear.load_artifact(io.BytesIO(active_row["blob"]))
        windows, exog_cols = source_artifact["windows"], source_artifact["exog_cols"]
    else:
        windows, exog_cols = lear.WINDOWS, lear.EXOG_COLS

    start = pd.Timestamp(train_end) - timedelta(days=WINDOW_DAYS - 1)
    anchor = pd.Timestamp(train_end).normalize()
    target_day = anchor + timedelta(days=lead_days)
    # fit()'s design matrix reads D-0 (today's own value) for every "known
    # ahead" exogenous column at `target_day` - the frame has to reach that
    # far, not just through train_end, or those columns come back all-NaN
    # for this fit and get silently dropped (lear_model._design's "missing
    # for the target day, left out").
    history = build_lear_history(engine, start, target_day + timedelta(hours=23), windows, exog_cols)
    model = lear.LEARForecaster(windows=windows, exog_cols=exog_cols, horizon_days=lead_days, n_jobs=4)
    model.fit(history, target_day)
    return model.to_artifact(target=target, trained_on="train")


# --------------------------------------------------------------------------- #
# One (kind, target) tune cycle
# --------------------------------------------------------------------------- #

def tune_and_maybe_promote(engine: sa.Engine, kind: str, target: str, validation_start: date,
                           score_through: date, dry_run: bool) -> tuple[bool, float | None]:
    """Tunes a candidate, scores it against the active artifact on the full
    validation window [validation_start, score_through], and promotes it if
    it's not worse. `score_through` is the newest ANCHOR day whose TARGET
    (score_through + lead_hours) already has a settled actual - NOT simply
    "yesterday": with a 48-72h lead time, "yesterday" as an anchor would ask
    to score against a target 2-3 days in the future (see run()). Returns
    (promoted, candidate_mae) - candidate_mae is None if nothing could be scored."""
    lead_hours = pred.LEAD_HOURS[target]
    active_row = store.load_active_artifact(engine, kind, target)
    train_end = validation_start - timedelta(days=1)

    if kind == "lstm":
        artifact = tune_lstm_candidate(engine, target, train_end, active_row)
    else:
        artifact = tune_lear_candidate(engine, target, train_end, active_row)

    buf = io.BytesIO()
    (pred._lstm_module() if kind == "lstm" else pred._lear_module()).save_artifact(buf, artifact)
    blob, fmt = buf.getvalue(), ("torch" if kind == "lstm" else "joblib")

    days = pd.date_range(validation_start, score_through, freq="D")
    actual = settled_actuals(engine, days, lead_hours)
    if kind == "lstm":
        candidate_model = pred._lstm_module().build_model(artifact)
        candidate_model.artifact = artifact
        candidate_mae = score_lstm(engine, candidate_model, artifact, days, actual)
    else:
        candidate_model = pred._lear_module().LEARForecaster.from_artifact(artifact)
        candidate_mae = score_lear(candidate_model, build_lear_history(
            engine, days.min(), days.max() + timedelta(hours=lead_hours + 23),
            artifact["windows"], artifact["exog_cols"]), days, lead_hours, actual)

    active_mae = None
    if active_row is not None:
        _, active_model = pred.load_model_from_blob(active_row["blob"], active_row["format"])
        if kind == "lstm":
            active_mae = score_lstm(engine, active_model, active_model.artifact, days, actual)
        else:
            active_mae = score_lear(active_model, build_lear_history(
                engine, days.min(), days.max() + timedelta(hours=lead_hours + 23),
                active_model.artifact["windows"], active_model.artifact["exog_cols"]), days, lead_hours, actual)

    promote = active_mae is None or (np.isfinite(candidate_mae) and candidate_mae <= active_mae)
    logger.info("%s/%s: candidate MAE %.3f, active MAE %s -> %s", kind, target, candidate_mae,
               f"{active_mae:.3f}" if active_mae is not None else "n/a (first run)",
               "PROMOTE" if promote else "keep active")

    if promote and not dry_run:
        version = f"{kind}_{target}_{date.today().isoformat()}"
        store.save_model_artifact(
            engine, model_kind=kind, target=target, blob=blob, format=fmt, model_version=version,
            trained_through=artifact["train_end"], valid_from_date=validation_start, valid_to_date=score_through,
            metrics={"mae": candidate_mae}, promote=True,
        )
        store.prune_old_artifacts(engine, kind, target, keep=ARTIFACT_RETENTION)
    return promote, candidate_mae


# --------------------------------------------------------------------------- #
# Fold-in experiment (point 1)
# --------------------------------------------------------------------------- #

def fold_in_is_safe(engine: sa.Engine, kind: str, target: str, validation_start: date, score_through: date) -> bool:
    """Would training through `validation_start` (one day later than today's
    cutoff) hurt this kind's score on the REMAINING window
    (validation_start + 1 .. score_through), compared to the artifact
    currently active (trained only through validation_start - 1)? Both sides
    are scored on the exact same remaining window, so this isolates the
    effect of that one extra day of training data. `score_through` - see
    tune_and_maybe_promote's docstring - is not simply "yesterday"."""
    active_row = store.load_active_artifact(engine, kind, target)
    if active_row is None:
        return False  # nothing to compare against yet - don't advance on a first run
    lead_hours = pred.LEAD_HOURS[target]
    remaining = pd.date_range(validation_start + timedelta(days=1), score_through, freq="D")
    if remaining.empty:
        return False
    actual = settled_actuals(engine, remaining, lead_hours)

    if kind == "lstm":
        candidate = tune_lstm_candidate(engine, target, validation_start, active_row)
        model = pred._lstm_module().build_model(candidate)
        model.artifact = candidate
        with_day = score_lstm(engine, model, candidate, remaining, actual)
        _, active_model = pred.load_model_from_blob(active_row["blob"], active_row["format"])
        without_day = score_lstm(engine, active_model, active_model.artifact, remaining, actual)
    else:
        candidate = tune_lear_candidate(engine, target, validation_start, active_row)
        model = pred._lear_module().LEARForecaster.from_artifact(candidate)
        history = build_lear_history(engine, remaining.min(), remaining.max() + timedelta(hours=lead_hours + 23),
                                     candidate["windows"], candidate["exog_cols"])
        with_day = score_lear(model, history, remaining, lead_hours, actual)
        _, active_model = pred.load_model_from_blob(active_row["blob"], active_row["format"])
        without_day = score_lear(active_model, history, remaining, lead_hours, actual)

    logger.info("%s/%s fold-in check: with %s MAE %.3f, without MAE %.3f", kind, target, validation_start,
               with_day, without_day)
    return np.isfinite(with_day) and (not np.isfinite(without_day) or with_day <= without_day)


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def run(engine: sa.Engine, targets: list[str], kinds: list[str], dry_run: bool) -> None:
    real_yesterday = (datetime.now(timezone.utc) - timedelta(days=1)).date()
    for target in targets:
        # The newest ANCHOR day whose TARGET (this day + lead_hours) already
        # has a settled actual - NOT real_yesterday itself: with a 48-72h
        # lead time, real_yesterday's own target is 2-3 days in the future.
        score_through = real_yesterday - timedelta(days=pred.LEAD_HOURS[target] // 24)
        state = read_validation_state(engine, target)
        if state.last_checked_date == score_through:
            logger.info("%s: already checked through %s - skipping.", target, score_through)
            continue

        fold_in_votes = []
        for kind in kinds:
            tune_and_maybe_promote(engine, kind, target, state.validation_start_date, score_through, dry_run)
            fold_in_votes.append(fold_in_is_safe(engine, kind, target, state.validation_start_date, score_through))

        if set(kinds) == set(KINDS) and fold_in_votes and all(fold_in_votes):
            new_start = state.validation_start_date + timedelta(days=1)
            logger.info("%s: fold-in safe for every kind - advancing validation_start_date to %s.",
                       target, new_start)
            if not dry_run:
                advance_validation_start(engine, target, new_start, score_through)
        else:
            logger.info("%s: validation_start_date stays at %s.", target, state.validation_start_date)
            if not dry_run:
                mark_checked(engine, target, score_through)


def main() -> None:
    parser = argparse.ArgumentParser(description="Weekly continuous-learning tune for the LZ_NORTH price models.")
    parser.add_argument("--target", choices=TARGETS, action="append", dest="targets")
    parser.add_argument("--kind", choices=KINDS, action="append", dest="kinds")
    parser.add_argument("--dry-run", action="store_true", help="tune and score, never promote or advance the cursor")
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    engine = ing.make_engine()
    try:
        run(engine, args.targets or list(TARGETS), args.kinds or list(KINDS), args.dry_run)
        if not args.dry_run:
            subprocess.run([sys.executable, str(SCRIPT_DIR / "recompute_reference_rmse.py")], check=False)
    except Exception:
        logger.exception("Continuous-learning tune FAILED")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
