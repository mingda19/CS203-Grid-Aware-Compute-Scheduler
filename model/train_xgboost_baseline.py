"""
Baseline XGBoost model for LZ_NORTH DAM price, trained on the master table
built by build_master_table.py (eda/train.csv, eda/val.csv).

Target: lz_north_price_winsorized (the 4-std-winsorized price, not raw -
training on raw price lets a handful of Winter-Storm-Uri-scale hours
dominate the loss; see the EDA chat history for the winsorization decision).

Modes (mutually exclusive):
  --train  Fit on train.csv only, report train-set metrics, save the model
           to eda/models/xgboost_baseline_train.json.
  --val    Load the model saved by --train and evaluate it on val.csv,
           reporting held-out metrics. Does NOT retrain - run --train first.
  --full   Fit on train.csv + val.csv concatenated (no held-out data left),
           save to eda/models/xgboost_baseline_full.json. This is the "final"
           model once hyperparameters have been chosen via --train/--val
           iteration - its reported metrics are in-sample, not a real
           evaluation.

Usage:
  eda/.venv/bin/python eda/train_xgboost_baseline.py --train
  eda/.venv/bin/python eda/train_xgboost_baseline.py --val
  eda/.venv/bin/python eda/train_xgboost_baseline.py --full
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
        raise FileNotFoundError(f"{path} not found - run build_master_table.py first.")
    df = pd.read_csv(path, parse_dates=["time"])
    return df


def feature_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in NON_FEATURE_COLS]


def report_metrics(y_true: np.ndarray, y_pred: np.ndarray, label: str) -> None:
    rmse = mean_squared_error(y_true, y_pred) ** 0.5
    mae = mean_absolute_error(y_true, y_pred)
    naive = np.roll(y_true, 24)  # same-hour-yesterday naive baseline, for context
    naive_rmse = mean_squared_error(y_true[24:], naive[24:]) ** 0.5
    print(f"\n[{label}] n={len(y_true)}")
    print(f"  RMSE: {rmse:.3f}")
    print(f"  MAE:  {mae:.3f}")
    print(f"  Naive (same-hour-yesterday) RMSE for context: {naive_rmse:.3f}")


def fit_model(train_df: pd.DataFrame) -> xgb.XGBRegressor:
    cols = feature_columns(train_df)
    model = xgb.XGBRegressor(**XGB_PARAMS, missing=np.nan)
    model.fit(train_df[cols], train_df[TARGET_COL])
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description="Baseline XGBoost model for LZ_NORTH DAM price.")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--train", action="store_true", help="Fit on train.csv, save model, report train metrics.")
    group.add_argument("--val", action="store_true", help="Evaluate the --train model on val.csv.")
    group.add_argument("--full", action="store_true", help="Fit on train.csv + val.csv combined, save model.")
    args = parser.parse_args()

    MODELS_DIR.mkdir(exist_ok=True)

    if args.train:
        train_df = load_split("train")
        cols = feature_columns(train_df)
        print(f"Training on {len(train_df)} rows, {len(cols)} features: {cols}")
        model = fit_model(train_df)
        model.save_model(MODELS_DIR / "xgboost_baseline_train.json")
        preds = model.predict(train_df[cols])
        report_metrics(train_df[TARGET_COL].to_numpy(), preds, "TRAIN")
        print(f"\nModel saved to {MODELS_DIR / 'xgboost_baseline_train.json'}")

    elif args.val:
        model_path = MODELS_DIR / "xgboost_baseline_train.json"
        if not model_path.exists():
            raise FileNotFoundError(f"{model_path} not found - run with --train first.")
        val_df = load_split("val")
        cols = feature_columns(val_df)
        model = xgb.XGBRegressor()
        model.load_model(model_path)
        preds = model.predict(val_df[cols])
        report_metrics(val_df[TARGET_COL].to_numpy(), preds, "VAL (held out)")

    elif args.full:
        train_df = load_split("train")
        val_df = load_split("val")
        full_df = pd.concat([train_df, val_df], ignore_index=True).sort_values("time")
        cols = feature_columns(full_df)
        print(f"Training on {len(full_df)} rows (train+val combined), {len(cols)} features: {cols}")
        model = fit_model(full_df)
        model.save_model(MODELS_DIR / "xgboost_baseline_full.json")
        preds = model.predict(full_df[cols])
        report_metrics(full_df[TARGET_COL].to_numpy(), preds, "FULL (in-sample, not a held-out evaluation)")
        print(f"\nModel saved to {MODELS_DIR / 'xgboost_baseline_full.json'}")


if __name__ == "__main__":
    main()
