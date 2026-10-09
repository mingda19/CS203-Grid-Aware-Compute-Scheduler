"""
Train the seq2seq LSTM for LZ_NORTH DAM price (see lstm_model.py for the
model and the input cutoff rules, LSTM comparison in model/evaluate.py).

One sample per UTC day D: encoder sees the 168 hours before D (price +
exogenous + calendar), decoder sees D's 24 hours of known-ahead inputs
(exogenous + calendar, no price), target is D's 24 prices. The price is
clipped to the fitting set's [p0.5, p99.5] and signed-log transformed; the
clip bounds, transform and scalers are saved inside the artifact.

Modes:
  --fit-on train      Fit on train.csv only. The last 10% of train days are
                      held out for early stopping; val.csv is never read.
                      Saves model/models/lstm_train.pt - the model to evaluate.
  --fit-on train+val  Fit on train.csv + val.csv with no held-out data, for
                      production. Runs for the best epoch count (and learning
                      rate schedule) found by the --fit-on train run, read
                      from its artifact. Saves model/models/lstm_full.pt.

To add a feature (e.g. battery data): append its column name to EXOG_COLS,
make sure the column is in train.csv/val.csv, retrain. Serving then needs
that column from predictions.load_exogenous.

Usage:
  python model/train_lstm.py --fit-on train --seed 0
  python model/train_lstm.py --fit-on train+val --seed 0
"""

from __future__ import annotations

import argparse
import copy
import logging
import random
import time
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader, TensorDataset

import lstm_model as lm

logger = logging.getLogger("train_lstm")

OUT_DIR = Path(__file__).resolve().parent
MODELS_DIR = OUT_DIR / "models"

RAW_PRICE_COL = "lz_north_price"
EXOG_COLS = [
    "load_forecast_dam_north_mwh",
    "wind_power_output_proxy_total",
    "solar_power_output_proxy_total",
    "henry_hub_price_usd_mmbtu_lag1d",
]
EXOG_MAX_FFILL_HOURS = 3
EARLY_STOP_FRACTION = 0.10

ARCH = {"hidden": 128, "layers": 2, "dropout": 0.2}
LEARNING_RATE = 1e-3
BATCH_SIZE = 64
MAX_EPOCHS = 200
PATIENCE = 10
LR_PATIENCE = 4
LR_FACTOR = 0.5
GRAD_CLIP = 1.0


# --------------------------------------------------------------------------- #
# Data
# --------------------------------------------------------------------------- #

def find_data_dir() -> Path:
    """train.csv/val.csv live under model/ by convention, but have also been
    kept at the repo root - accept either."""
    for candidate in (OUT_DIR, OUT_DIR.parent):
        if (candidate / "train.csv").exists():
            return candidate
    raise FileNotFoundError(f"train.csv not found under {OUT_DIR} or {OUT_DIR.parent} - pass --data-dir.")


def load_frame(data_dir: Path, splits: list[str], exog_cols: list[str]) -> pd.DataFrame:
    """Hourly frame (naive UTC index) with lm.PRICE_COL, exog and calendar
    columns. Exogenous gaps up to EXOG_MAX_FFILL_HOURS are forward-filled;
    price gaps are left as NaN so the samples that touch them get dropped."""
    parts = [pd.read_csv(data_dir / f"{name}.csv", parse_dates=["time"]) for name in splits]
    df = pd.concat(parts, ignore_index=True).sort_values("time").set_index("time").asfreq("h")
    absent = [c for c in exog_cols if c not in df.columns]
    if absent:
        raise KeyError(f"EXOG_COLS not found in {'/'.join(splits)}.csv: {absent}")
    df[exog_cols] = df[exog_cols].ffill(limit=EXOG_MAX_FFILL_HOURS)
    df = lm.add_cyclic_calendar(df).rename(columns={RAW_PRICE_COL: lm.PRICE_COL})
    return df[[lm.PRICE_COL, *exog_cols, *lm.CALENDAR_COLS]]


def candidate_days(frame: pd.DataFrame, cfg: dict) -> pd.DatetimeIndex:
    """UTC days whose full encoder window and 24 target hours fall inside `frame`."""
    first = (frame.index.min() + pd.Timedelta(hours=cfg["past_window"])).ceil("D")
    last = (frame.index.max() - pd.Timedelta(hours=cfg["horizon"] - 1)).floor("D")
    return pd.date_range(first, last, freq="D")


def build_samples(frame: pd.DataFrame, days: pd.DatetimeIndex, cfg: dict):
    """Stack (past, future, target) for every day with complete inputs and
    targets. Returns the arrays plus the days actually kept."""
    past, future, target, kept = [], [], [], []
    for day in days:
        try:
            p, f = lm.assemble_inputs(frame, day, cfg, price_ffill_limit=0)
        except lm.MissingInputError:
            continue
        y = lm.target_array(frame, day, cfg)
        if np.isnan(y).any():
            continue
        past.append(p)
        future.append(f)
        target.append(y)
        kept.append(day)
    if len(kept) < len(days):
        logger.info("Dropped %d of %d days with missing inputs or targets", len(days) - len(kept), len(days))
    return np.stack(past), np.stack(future), np.stack(target), pd.DatetimeIndex(kept)


def to_dataset(past, future, target) -> TensorDataset:
    return TensorDataset(torch.from_numpy(past), torch.from_numpy(future), torch.from_numpy(target))


# --------------------------------------------------------------------------- #
# Training
# --------------------------------------------------------------------------- #

def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def compute_loss(pred: torch.Tensor, target: torch.Tensor, loss_name: str, quantiles: list[float]) -> torch.Tensor:
    if loss_name == "mse":
        return torch.nn.functional.mse_loss(pred[..., 0], target)
    return lm.pinball_loss(pred, target, quantiles)


def run_epoch(model, loader, loss_name: str, device, optimizer=None) -> float:
    model.train(optimizer is not None)
    total, n = 0.0, 0
    with torch.set_grad_enabled(optimizer is not None):
        for past, future, target in loader:
            past, future, target = past.to(device), future.to(device), target.to(device)
            loss = compute_loss(model(past, future), target, loss_name, model.quantiles)
            if optimizer is not None:
                optimizer.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), GRAD_CLIP)
                optimizer.step()
            total += loss.item() * len(target)
            n += len(target)
    return total / n


def dollar_metrics(model, samples, frame: pd.DataFrame, cfg: dict) -> dict:
    """MAE/RMSE of the point forecast (and interval coverage) in $/MWh
    against the raw, unclipped price."""
    past, future, _, days = samples
    preds = lm.predict_quantiles(model, past, future, cfg)
    actual = np.stack([frame[lm.PRICE_COL].reindex(pd.date_range(d, periods=cfg["horizon"], freq="h")).to_numpy()
                       for d in days])
    quantiles = cfg["arch"]["quantiles"]
    err = preds[..., quantiles.index(0.5)] - actual
    metrics = {"mae": float(np.abs(err).mean()), "rmse": float(np.sqrt((err ** 2).mean())), "n_days": len(days)}
    if 0.1 in quantiles and 0.9 in quantiles:
        inside = (actual >= preds[..., quantiles.index(0.1)]) & (actual <= preds[..., quantiles.index(0.9)])
        metrics["p10_p90_coverage"] = float(inside.mean())
    return metrics


def fit_with_early_stopping(model, train_ds, stop_ds, loss_name: str, device, seed: int, max_epochs: int):
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(optimizer, factor=LR_FACTOR, patience=LR_PATIENCE)
    train_loader = DataLoader(train_ds, batch_size=BATCH_SIZE, shuffle=True,
                              generator=torch.Generator().manual_seed(seed))
    stop_loader = DataLoader(stop_ds, batch_size=256)

    best = {"loss": float("inf"), "epoch": 0, "state": None}
    lr_history = []
    for epoch in range(1, max_epochs + 1):
        started = time.time()
        lr_history.append(optimizer.param_groups[0]["lr"])
        train_loss = run_epoch(model, train_loader, loss_name, device, optimizer)
        stop_loss = run_epoch(model, stop_loader, loss_name, device)
        scheduler.step(stop_loss)
        improved = stop_loss < best["loss"]
        if improved:
            best = {"loss": stop_loss, "epoch": epoch, "state": copy.deepcopy(model.state_dict())}
        logger.info("epoch %3d  train %.4f  early-stop %.4f  lr %.1e  %.1fs%s", epoch, train_loss, stop_loss,
                    lr_history[-1], time.time() - started, "  *" if improved else "")
        if epoch - best["epoch"] >= PATIENCE:
            logger.info("Early stopping: no improvement for %d epochs", PATIENCE)
            break
    model.load_state_dict(best["state"])
    return best["epoch"], best["loss"], lr_history[:best["epoch"]]


def fit_fixed_epochs(model, train_ds, loss_name: str, device, seed: int, lr_schedule: list[float]):
    """Replay a known-good schedule with no held-out data to stop on."""
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)
    train_loader = DataLoader(train_ds, batch_size=BATCH_SIZE, shuffle=True,
                              generator=torch.Generator().manual_seed(seed))
    for epoch, lr in enumerate(lr_schedule, start=1):
        started = time.time()
        for group in optimizer.param_groups:
            group["lr"] = lr
        train_loss = run_epoch(model, train_loader, loss_name, device, optimizer)
        logger.info("epoch %3d/%d  train %.4f  lr %.1e  %.1fs", epoch, len(lr_schedule), train_loss, lr,
                    time.time() - started)
    return train_loss


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the seq2seq LSTM for LZ_NORTH DAM price.")
    parser.add_argument("--fit-on", choices=["train", "train+val"], default="train")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--loss", choices=["pinball", "mse"], default="pinball",
                        help="pinball: P10/P50/P90 quantiles (default). mse: a plain point model.")
    parser.add_argument("--tag", help="artifact is saved as models/lstm_<tag>.pt (default: train / full)")
    parser.add_argument("--data-dir", type=Path, help="folder holding train.csv and val.csv")
    parser.add_argument("--max-epochs", type=int, default=MAX_EPOCHS)
    parser.add_argument("--schedule-from", type=Path, default=MODELS_DIR / "lstm_train.pt",
                        help="--fit-on train+val: the --fit-on train artifact to take the epoch count from")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--train-end", type=date.fromisoformat,
                        help="fit only on data up to and including this UTC date (walk-forward folds)")
    parser.add_argument("--extra-exog", default="",
                        help="comma-separated columns to add to EXOG_COLS for this run (feature ablations)")
    parser.add_argument("--out", type=Path, help="artifact path (default: models/lstm_<tag>.pt)")
    parser.add_argument("--threads", type=int, help="torch CPU threads (default: torch's own choice)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    set_seed(args.seed)
    if args.threads:
        torch.set_num_threads(args.threads)
    device = torch.device(args.device)
    data_dir = args.data_dir or find_data_dir()
    MODELS_DIR.mkdir(exist_ok=True)

    splits = ["train"] if args.fit_on == "train" else ["train", "val"]
    exog_cols = EXOG_COLS + [c for c in args.extra_exog.split(",") if c]
    frame = load_frame(data_dir, splits, exog_cols)
    if args.train_end:
        frame = frame.loc[:pd.Timestamp(args.train_end) + pd.Timedelta(hours=23)]
    quantiles = [0.5] if args.loss == "mse" else list(lm.DEFAULT_QUANTILES)

    cfg = {
        "kind": "lstm",
        "past_window": lm.PAST_WINDOW,
        "horizon": lm.HORIZON,
        "exog_cols": exog_cols,
        "calendar_cols": list(lm.CALENDAR_COLS),
        "trained_on": args.fit_on,
        "train_end": str(frame.index.max()),
        "loss": args.loss,
        "seed": args.seed,
    }
    cfg.update(lm.fit_preprocessing(frame, cfg["exog_cols"], cfg["calendar_cols"]))
    cfg["arch"] = {**ARCH, "n_past_feats": len(lm.past_feature_names(cfg)),
                   "n_future_feats": len(lm.future_feature_names(cfg)), "quantiles": quantiles}

    samples = build_samples(frame, candidate_days(frame, cfg), cfg)
    past, future, target, days = samples
    logger.info("%s: %d daily samples %s .. %s | past %s future %s | price clip [%.2f, %.2f]", args.fit_on,
                len(days), days[0].date(), days[-1].date(), past.shape[1:], future.shape[1:], *cfg["price_clip"])

    model = lm.LSTMForecaster(cfg["arch"]["n_past_feats"], cfg["arch"]["n_future_feats"],
                              quantiles=quantiles, **ARCH).to(device)

    if args.fit_on == "train":
        n_stop = max(1, int(round(len(days) * EARLY_STOP_FRACTION)))
        fit_part = tuple(a[:-n_stop] for a in samples)
        stop_part = tuple(a[-n_stop:] for a in samples)
        logger.info("Fitting on %d days, early stopping on the last %d (%s .. %s)", len(days) - n_stop, n_stop,
                    stop_part[3][0].date(), stop_part[3][-1].date())
        best_epoch, best_loss, lr_history = fit_with_early_stopping(
            model, to_dataset(*fit_part[:3]), to_dataset(*stop_part[:3]), args.loss, device, args.seed,
            args.max_epochs)
        model.cpu()
        metrics = {"early_stop_loss": best_loss,
                   "early_stop": dollar_metrics(model, stop_part, frame, cfg),
                   "fit": dollar_metrics(model, fit_part, frame, cfg)}
    else:
        if not args.schedule_from.exists():
            raise FileNotFoundError(f"{args.schedule_from} not found - run --fit-on train first (the epoch "
                                    "count for the final fit comes from it).")
        _, source = lm.load_artifact(args.schedule_from)
        if source["trained_on"] != "train" or source["loss"] != args.loss:
            raise ValueError(f"{args.schedule_from} was trained with fit-on={source['trained_on']}, "
                             f"loss={source['loss']} - need a --fit-on train run with --loss {args.loss}.")
        best_epoch, lr_history = source["best_epoch"], source["lr_history"]
        logger.info("Fitting on all %d days for %d epochs (schedule from %s)", len(days), best_epoch,
                    args.schedule_from.name)
        train_loss = fit_fixed_epochs(model, to_dataset(past, future, target), args.loss, device, args.seed,
                                      lr_history)
        model.cpu()
        # In-sample only: there is no held-out data left in this mode.
        metrics = {"train_loss": train_loss, "fit": dollar_metrics(model, samples, frame, cfg)}

    artifact = {**cfg, "state_dict": model.state_dict(), "best_epoch": best_epoch, "lr_history": lr_history,
                "metrics": metrics}
    tag = args.tag or ("train" if args.fit_on == "train" else "full")
    out_path = args.out or MODELS_DIR / f"lstm_{tag}.pt"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    lm.save_artifact(out_path, artifact)

    for name, m in metrics.items():
        if isinstance(m, dict):
            coverage = f"  P10-P90 coverage {m['p10_p90_coverage']:.1%}" if "p10_p90_coverage" in m else ""
            logger.info("[%s] %d days  MAE %.3f  RMSE %.3f%s", name, m["n_days"], m["mae"], m["rmse"], coverage)
    logger.info("Best epoch %d. Model saved to %s", best_epoch, out_path)


if __name__ == "__main__":
    main()
