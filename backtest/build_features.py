"""
Rebuild model/train.csv + model/val.csv in the current 48h/72h feature layout, so model/evaluate.py can produce
walk-forward forecasts for the model the app serves (docs/backtest-plan.md, decision D4).

Why this exists: model/model.md section 9.2 moved the models from a next-day target to y0 (48h ahead) and y1
(72h ahead) and changed their inputs. The CSVs on disk still have the old columns, and the script that first
built them is not in this repo. Every new column can be derived from the old ones:

  lag_72h, lag_96h, roll_*      from lz_north_price, through data_scripts/predictions.assemble_xgb_features:
                                the code that builds live features, so training and serving match exactly
  hour, day_of_week, month,     predictions.add_calendar_features
  is_us_holiday
  henry_hub_..._lag2d / _lag3d  the old _lag1d shifted by one and two days (it is one value per UTC day, the
                                last published price carried forward, so the shift gives the same series the
                                live code builds, apart from the live 7-day staleness limit)
  wind/solar proxies            unchanged (they are the hour's own values, as in serving)

Dropped: lag_24h, lag_48h, henry_hub_..._lag1d, load_forecast_dam_north_mwh (not known 48h ahead).
The train/val split is kept as it was. Nothing under model/ is changed.

Usage (from the repo root):
  .venv/bin/python3 backtest/build_features.py --out backtest/data/features
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "data_scripts"))
import predictions as P  # noqa: E402

GAS_OLD = "henry_hub_price_usd_mmbtu_lag1d"
GAS_2D, GAS_3D = "henry_hub_price_usd_mmbtu_lag2d", "henry_hub_price_usd_mmbtu_lag3d"
KEEP = ["lz_north_price", "lz_north_price_winsorized"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rebuild(frame: pd.DataFrame) -> pd.DataFrame:
    frame = frame.sort_values("time").set_index("time")
    if frame.index.duplicated().any():
        raise SystemExit("duplicate hours in train.csv/val.csv")
    hourly = frame.asfreq("h")
    times = hourly.index

    # Gas: one value per UTC day. lag2d on day d is the old lag1d of day d-1, lag3d the old lag1d of day d-2.
    daily_gas = hourly[GAS_OLD].groupby(times.floor("D")).first()
    if (hourly[GAS_OLD].groupby(times.floor("D")).nunique() > 1).any():
        raise SystemExit(f"{GAS_OLD} is not constant within a day; the shift below would be wrong")
    day_index = pd.date_range(daily_gas.index.min(), daily_gas.index.max(), freq="D")
    daily_gas = daily_gas.reindex(day_index)
    exog = pd.DataFrame({"time": times})
    exog[GAS_2D] = exog["time"].dt.floor("D").map(daily_gas.shift(1))
    exog[GAS_3D] = exog["time"].dt.floor("D").map(daily_gas.shift(2))
    exog["wind_power_output_proxy_total"] = hourly["wind_power_output_proxy_total"].to_numpy()
    exog["solar_power_output_proxy_total"] = hourly["solar_power_output_proxy_total"].to_numpy()

    # Price features exactly as serving builds them: from the price clipped to the training band.
    price = hourly["lz_north_price"].clip(P.WINSORIZE_LOWER, P.WINSORIZE_UPPER)
    features = P.assemble_xgb_features(price, exog, times)

    out = hourly[KEEP].join(features)
    out = out[hourly["lz_north_price"].notna()]  # only the hours the source had
    return out.reset_index().rename(columns={"index": "time"})[["time", *KEEP, *P.FEATURE_COLUMNS]]


def main() -> None:
    parser = argparse.ArgumentParser(description="Rebuild train/val CSVs in the y0/y1 feature layout.")
    parser.add_argument("--data-dir", type=Path, default=REPO / "model", help="folder with the old train.csv/val.csv")
    parser.add_argument("--out", type=Path, default=REPO / "backtest" / "data" / "features")
    args = parser.parse_args()

    parts = {name: pd.read_csv(args.data_dir / f"{name}.csv", parse_dates=["time"]) for name in ("train", "val")}
    split = parts["val"]["time"].min()
    rebuilt = rebuild(pd.concat(parts.values(), ignore_index=True))

    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"sources": {}, "outputs": {}, "feature_columns": P.FEATURE_COLUMNS}
    for name in ("train", "val"):
        manifest["sources"][name + ".csv"] = sha256(args.data_dir / f"{name}.csv")
    for name, rows in (("train", rebuilt[rebuilt["time"] < split]), ("val", rebuilt[rebuilt["time"] >= split])):
        if len(rows) != len(parts[name]):
            raise SystemExit(f"{name}: {len(rows)} rows rebuilt from {len(parts[name])}")
        path = args.out / f"{name}.csv"
        rows.to_csv(path, index=False, date_format="%Y-%m-%d %H:%M:%S", float_format="%.10g")
        nan = {c: int(n) for c, n in rows[P.FEATURE_COLUMNS].isna().sum().items() if n}
        manifest["outputs"][path.name] = {"rows": len(rows), "first": str(rows["time"].min()),
                                          "last": str(rows["time"].max()), "nan_cells": nan, "sha256": sha256(path)}
        print(f"{path}: {len(rows)} rows, {rows['time'].min()} -> {rows['time'].max()}, missing values {nan or 'none'}")
    (args.out / "features_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    np.seterr(all="ignore")
    main()
