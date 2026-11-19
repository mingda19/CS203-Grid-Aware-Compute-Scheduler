"""
Export the price inputs for the optimiser backtest (docs/backtest-plan.md, ticket 1) as plain CSV files
the Java harness reads:

  <out>/actual_prices.csv          interval_start_utc,price   raw LZ_NORTH DAM price (not winsorised:
                                                             the backtest pays real spike prices)
  <out>/forecast_<model>.csv       interval_start_utc,price   one out-of-sample forecast per hour
  <out>/export_manifest.json       source files and their SHA-256, row counts, complete days

Actual prices come from model/train.csv + model/val.csv (`lz_north_price`). Forecasts come from a
walk-forward forecast file written by model/evaluate.py: every forecast there was made by a model trained
only on data before its fold, so planning with it uses nothing from the future.

Supported forecast files:
  forecasts_walkforward*.parquet           one forecast per hour per model (column `horizon_h`)
  multiday_forecasts_walkforward.parquet   48h/72h-ahead forecasts; pick one with --lead (2 = 48h, 3 = 72h)
Models with several seeds (the LSTM) are averaged per hour.

Usage (from the repo root):
  .venv/bin/python3 backtest/export_prices.py \\
      --forecasts model/reports/forecasts_walkforward.parquet --models xgb,naive_w1,naive_d1
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pandas as pd

REPO = Path(__file__).resolve().parent.parent
TIME_FORMAT = "%Y-%m-%dT%H:%M:%S"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def complete_days(times: pd.Series) -> int:
    """Number of UTC days with all 24 hours present."""
    per_day = times.dt.floor("D").value_counts()
    return int((per_day == 24).sum())


def load_actuals(data_dir: Path) -> pd.DataFrame:
    parts = [pd.read_csv(data_dir / f"{name}.csv", usecols=["time", "lz_north_price"], parse_dates=["time"])
             for name in ("train", "val")]
    actual = pd.concat(parts).rename(columns={"time": "interval_start_utc", "lz_north_price": "price"})
    actual = actual.dropna().sort_values("interval_start_utc")
    duplicates = actual["interval_start_utc"].duplicated()
    if duplicates.any():
        raise SystemExit(f"{int(duplicates.sum())} duplicate hours in train.csv/val.csv")
    return actual


def load_forecast(path: Path, model: str, lead: int | None) -> pd.DataFrame:
    frame = pd.read_parquet(path)
    frame = frame[frame["model"] == model]
    if "lead" in frame.columns:
        if lead is None:
            raise SystemExit(f"{path.name} has several leads: pass --lead (2 = 48h, 3 = 72h)")
        frame = frame[frame["lead"] == lead]
    if frame.empty:
        raise SystemExit(f"no rows for model '{model}' in {path.name}")
    # Several seeds (LSTM) -> one forecast per hour.
    hourly = frame.groupby("time", as_index=False)["pred"].mean()
    hourly = hourly.rename(columns={"time": "interval_start_utc", "pred": "price"}).dropna()
    return hourly.sort_values("interval_start_utc")


def write(frame: pd.DataFrame, path: Path) -> None:
    frame.to_csv(path, index=False, date_format=TIME_FORMAT, float_format="%.6f")


def main() -> None:
    parser = argparse.ArgumentParser(description="Export backtest price inputs.")
    parser.add_argument("--data-dir", type=Path, default=REPO / "model", help="folder with train.csv and val.csv")
    parser.add_argument("--forecasts", type=Path, required=True, help="walk-forward forecast parquet file")
    parser.add_argument("--models", required=True, help="comma-separated model names, e.g. xgb,naive_w1")
    parser.add_argument("--lead", type=int, help="multiday files only: 2 = 48h ahead, 3 = 72h ahead")
    parser.add_argument("--out", type=Path, default=REPO / "backtest" / "data")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    manifest: dict = {"sources": {}, "outputs": {}, "lead": args.lead}

    for name in ("train", "val"):
        source = args.data_dir / f"{name}.csv"
        manifest["sources"][str(source.relative_to(REPO) if source.is_relative_to(REPO) else source)] = sha256(source)
    forecast_source = args.forecasts
    manifest["sources"][str(forecast_source.relative_to(REPO) if forecast_source.is_relative_to(REPO)
                            else forecast_source)] = sha256(forecast_source)

    actual = load_actuals(args.data_dir)
    actual_path = args.out / "actual_prices.csv"
    write(actual, actual_path)
    manifest["outputs"]["actual_prices.csv"] = {
        "rows": len(actual), "complete_days": complete_days(actual["interval_start_utc"]),
        "first": actual["interval_start_utc"].min().strftime(TIME_FORMAT),
        "last": actual["interval_start_utc"].max().strftime(TIME_FORMAT),
    }

    for model in [m.strip() for m in args.models.split(",") if m.strip()]:
        forecast = load_forecast(args.forecasts, model, args.lead)
        path = args.out / f"forecast_{model}.csv"
        write(forecast, path)
        manifest["outputs"][path.name] = {
            "rows": len(forecast), "complete_days": complete_days(forecast["interval_start_utc"]),
            "first": forecast["interval_start_utc"].min().strftime(TIME_FORMAT),
            "last": forecast["interval_start_utc"].max().strftime(TIME_FORMAT),
        }

    for name in manifest["outputs"]:
        manifest["outputs"][name]["sha256"] = sha256(args.out / name)
    (args.out / "export_manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    for name, info in manifest["outputs"].items():
        print(f"{name}: {info['rows']} rows, {info['complete_days']} complete days, {info['first']} -> {info['last']}")


if __name__ == "__main__":
    main()
