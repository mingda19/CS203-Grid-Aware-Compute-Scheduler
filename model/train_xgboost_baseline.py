"""
Baseline XGBoost model for LZ_NORTH DAM price, trained on model/train.csv
and model/val.csv (one row per UTC hour; see model/model.md S4 for how the
feature columns were built, S8 for features added/rejected after EDA, and
S9 for why the target is 2/3 days ahead rather than the next day).

`time` is the anchor hour ("now" - what the feature columns are safely known
as of), not the target hour. The target is built here by looking up
TARGET_COL at (time + lead_hours) for each row, by real timestamp - not a
positional shift, so it can't silently misalign across a gap in the data.
--target y0 predicts 48h ahead, --target y1 predicts 72h ahead (model/model.md
S9.2) - same feature columns for both, just a different lead time, trained
as two separate models rather than one multi-output model to keep this
simple. Rows whose target lands beyond the data (the tail) or in a gap are
dropped before fitting/scoring - see build_target().

Target: lz_north_price_winsorized (the 4-std-winsorized price, not raw -
training on raw price lets a handful of Winter-Storm-Uri-scale hours
dominate the loss; see model/model.md S3.7 for the winsorization decision).

Modes (mutually exclusive, always combined with --target):
  --train  Fit on train.csv only, report train-set metrics, save the model
           to model/models/xgboost_<target>_train.json.
  --val    Load the model saved by --train and evaluate it on val.csv,
           reporting held-out metrics. Does NOT retrain - run --train first.
  --full   Fit on train.csv + val.csv concatenated (sorted by time BEFORE the
           target lookup, so train's tail rows can still find their target
           in val's early rows instead of being spuriously dropped) - no
           held-out data left, save to model/models/xgboost_<target>_full.json.
           This is the "final" model once hyperparameters have been chosen
           via --train/--val iteration - its reported metrics are in-sample,
           not a real evaluation.

Usage (run from inside model/):
  python train_xgboost_baseline.py --train --target y0
  python train_xgboost_baseline.py --val --target y0
  python train_xgboost_baseline.py --full --target y0
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.metrics import mean_absolute_error, mean_squared_error

OUT_DIR = Path(__file__).resolve().parent
MODELS_DIR = OUT_DIR / "models"

TARGET_COL = "lz_north_price_winsorized"
NON_FEATURE_COLS = {"time", "lz_north_price", "lz_north_price_winsorized"}
# Hours from the anchor row's `time` ("now") to the target hour - model/model.md S9.
LEAD_HOURS = {"y0": 48, "y1": 72}

XGB_PARAMS = {
    "objective": "reg:squarederror",
    "n_estimators": 500,
    "max_depth": 8,
    "learning_rate": 0.04,
    "subsample": 0.7,
    "colsample_bytree": 0.7,
    "random_state": 42,
}


def load_split(name: str) -> pd.DataFrame:
    path = OUT_DIR / f"{name}.csv"
    if not path.exists():
        raise FileNotFoundError(f"{path} not found - expected a pre-built train.csv/val.csv under model/.")
    df = pd.read_csv(path, parse_dates=["time"])
    return df


def feature_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in NON_FEATURE_COLS]


def build_target(df: pd.DataFrame, lead_hours: int) -> np.ndarray:
    """TARGET_COL looked up at (time + lead_hours) for every row, by real
    timestamp - never a positional shift, so a gap in the data can't
    silently misalign the target. NaN wherever no row exists at that future
    timestamp (the data's own tail, or a genuine gap) - the caller must drop
    those before fitting/scoring."""
    lookup = df.set_index("time")[TARGET_COL]
    target_times = df["time"] + pd.Timedelta(hours=lead_hours)
    return lookup.reindex(target_times).to_numpy()


def report_metrics(y_true: np.ndarray, y_pred: np.ndarray, label: str) -> None:
    rmse = mean_squared_error(y_true, y_pred) ** 0.5
    mae = mean_absolute_error(y_true, y_pred)
    naive = np.roll(y_true, 24)  # same-hour-yesterday naive baseline, for context
    naive_rmse = mean_squared_error(y_true[24:], naive[24:]) ** 0.5
    print(f"\n[{label}] n={len(y_true)}")
    print(f"  RMSE: {rmse:.3f}")
    print(f"  MAE:  {mae:.3f}")
    print(f"  Naive (same-hour-yesterday) RMSE for context: {naive_rmse:.3f}")


def fit_model(X: pd.DataFrame, y: np.ndarray) -> xgb.XGBRegressor:
    model = xgb.XGBRegressor(**XGB_PARAMS, missing=np.nan)
    model.fit(X, y)
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description="Baseline XGBoost model for LZ_NORTH DAM price.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--train", action="store_true", help="Fit on train.csv, save model, report train metrics.")
    group.add_argument("--val", action="store_true", help="Evaluate the --train model on val.csv.")
    group.add_argument("--full", action="store_true", help="Fit on train.csv + val.csv combined, save model.")
    parser.add_argument("--target", choices=["y0", "y1"], required=True,
                        help="y0: price 48h after `time`. y1: price 72h after `time`. See model/model.md S9.")
    args = parser.parse_args()
    lead_hours = LEAD_HOURS[args.target]

    MODELS_DIR.mkdir(exist_ok=True)

    if args.train:
        train_df = load_split("train")
        y = build_target(train_df, lead_hours)
        mask = ~np.isnan(y)
        cols = feature_columns(train_df)
        print(f"Training on {int(mask.sum())} rows ({len(train_df) - int(mask.sum())} dropped - "
              f"target beyond the data), {len(cols)} features: {cols}")
        model = fit_model(train_df.loc[mask, cols], y[mask])
        model.get_booster().set_attr(target=args.target)
        out_path = MODELS_DIR / f"xgboost_{args.target}_train.json"
        model.save_model(out_path)
        preds = model.predict(train_df.loc[mask, cols])
        report_metrics(y[mask], preds, "TRAIN")
        print(f"\nModel saved to {out_path}")

    elif args.val:
        model_path = MODELS_DIR / f"xgboost_{args.target}_train.json"
        if not model_path.exists():
            raise FileNotFoundError(f"{model_path} not found - run with --train --target {args.target} first.")
        val_df = load_split("val")
        y = build_target(val_df, lead_hours)
        mask = ~np.isnan(y)
        cols = feature_columns(val_df)
        model = xgb.XGBRegressor()
        model.load_model(model_path)
        preds = model.predict(val_df.loc[mask, cols])
        report_metrics(y[mask], preds, "VAL (held out)")

    elif args.full:
        train_df = load_split("train")
        val_df = load_split("val")
        full_df = pd.concat([train_df, val_df], ignore_index=True).sort_values("time").reset_index(drop=True)
        y = build_target(full_df, lead_hours)
        mask = ~np.isnan(y)
        cols = feature_columns(full_df)
        print(f"Training on {int(mask.sum())} rows (train+val combined, {len(full_df) - int(mask.sum())} "
              f"dropped), {len(cols)} features: {cols}")
        model = fit_model(full_df.loc[mask, cols], y[mask])
        model.get_booster().set_attr(target=args.target)
        out_path = MODELS_DIR / f"xgboost_{args.target}_full.json"
        model.save_model(out_path)
        preds = model.predict(full_df.loc[mask, cols])
        report_metrics(y[mask], preds, "FULL (in-sample, not a held-out evaluation)")
        print(f"\nModel saved to {out_path}")


if __name__ == "__main__":
    main()
