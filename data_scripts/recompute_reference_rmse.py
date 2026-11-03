"""
recompute_reference_rmse.py

Recomputes `rmse_reference` (backend's RmseReference entity) from real,
settled prediction residuals - replaces predictions.REFERENCE_RMSE's
hand-maintained dict, which was frozen at whatever model/evaluate.py
measured the day someone last remembered to update it (model/model.md's
continuous-learning section, point 4: "doesn't need to be validated and has
to be run anyway. No need a new row every time it is updated"). Upserts one
row per (model_kind, target, hour_utc) in place - no history kept, unlike
model_artifacts, where keeping old snapshots around is the whole point.

model_kind and target aren't columns on `predicted_price` - they're read off
the `model_version` string by the same "{kind}_{target}_..." convention every
model file/artifact in this project already follows (lstm_y0_full.pt,
xgboost_y1_train.json, lear_y0_2026-11-09, ...), so this never needs a join
against model_artifacts or a hardcoded kind/target list - a brand-new model
kind needs no change here, as long as its model_version follows that
convention (predictions.py's load_model/load_model_from_blob/predict_lstm.py/
predict_lear.py all name theirs this way already).

Run by data_scripts/update.py after each weekly tune (so the reference
reflects whatever's now serving), and safe to run standalone any time -
nothing here depends on a tune having just happened.

Usage:
  python recompute_reference_rmse.py                      # every (kind, target) with enough settled residuals
  python recompute_reference_rmse.py --lookback-days 90    # default: 90
  python recompute_reference_rmse.py --location LZ_NORTH   # default: predictions.DEFAULT_LOCATION
"""

from __future__ import annotations

import argparse
import logging
import time
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
import sqlalchemy as sa

import ingestion as ing
import predictions as pred

logger = logging.getLogger("recompute_reference_rmse")
DEFAULT_LOOKBACK_DAYS = 90
# Fewer points make an hour's RMSE too noisy to trust - that slot just keeps
# whatever rmse_reference already has (or predictions.REFERENCE_RMSE's
# fallback) rather than being overwritten with a bad estimate.
MIN_SAMPLES_PER_HOUR = 5


def load_settled_residuals(engine: sa.Engine, location: str, since: datetime) -> pd.DataFrame:
    """(time, model_version, predicted, actual) for every settled target hour
    across EVERY model_version, not just one - recompute_rmse_by_slot groups
    by the (kind, target) each model_version names, so a model promoted
    partway through the lookback window still contributes whatever residuals
    it has, rather than needing its own separate query. Same latest-
    generated_at-wins dedup as predictions.load_recent_residuals, generalized
    across model_version instead of filtering to one."""
    query = sa.text(
        "WITH latest AS ("
        "  SELECT interval_start_utc, model_version, predicted_price,"
        "         ROW_NUMBER() OVER (PARTITION BY interval_start_utc, model_version"
        "                            ORDER BY generated_at DESC) AS rn"
        "  FROM predicted_price"
        "  WHERE location = :location AND interval_start_utc >= :since"
        ")"
        " SELECT latest.interval_start_utc AS time, latest.model_version AS model_version,"
        "        latest.predicted_price AS predicted, electrical_price.spp_usd_mwh AS actual"
        " FROM latest"
        " JOIN electrical_price"
        "   ON electrical_price.interval_start_utc = latest.interval_start_utc"
        "  AND electrical_price.location = :location"
        " WHERE latest.rn = 1"
    )
    with engine.begin() as conn:
        df = pd.read_sql(query, conn, params={"location": location, "since": pd.Timestamp(since).isoformat()})
    df["time"] = pd.to_datetime(df["time"])
    return df


def recompute_rmse_by_slot(residuals: pd.DataFrame, min_samples: int = MIN_SAMPLES_PER_HOUR) -> pd.DataFrame:
    """(model_kind, target, hour_utc, rmse, n_samples, computed_through) - one
    row per slot with enough settled residuals. model_kind/target come from
    splitting model_version on "_" (see module docstring); a model_version
    that doesn't have at least two "_"-separated parts is skipped and logged,
    not silently miscategorized."""
    if residuals.empty:
        return residuals.assign(model_kind=[], target=[], hour_utc=[], rmse=[], n_samples=[], computed_through=[])
    parts = residuals["model_version"].str.split("_", n=2)
    valid = parts.str.len() >= 2
    if not valid.all():
        bad = sorted(residuals.loc[~valid, "model_version"].unique())
        logger.warning("Skipping %d row(s) with an unparseable model_version (no kind_target_... prefix): %s",
                       int((~valid).sum()), bad)
    residuals = residuals[valid].copy()
    residuals["model_kind"] = parts[valid].str[0]
    residuals["target"] = parts[valid].str[1]
    residuals["hour_utc"] = residuals["time"].dt.hour
    residuals["sq_err"] = (residuals["predicted"] - residuals["actual"]) ** 2

    grouped = residuals.groupby(["model_kind", "target", "hour_utc"]).agg(
        rmse=("sq_err", lambda s: float(np.sqrt(s.mean()))),
        n_samples=("sq_err", "size"),
        computed_through=("time", "max"),
    ).reset_index()
    rows = grouped[grouped["n_samples"] >= min_samples].copy()
    # Python writes bypass RmseReference's JPA lifecycle callbacks.
    rows["updated_at"] = datetime.now(timezone.utc).replace(tzinfo=None)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Recompute rmse_reference from settled prediction residuals.")
    parser.add_argument("--lookback-days", type=int, default=DEFAULT_LOOKBACK_DAYS)
    parser.add_argument("--location", default=pred.DEFAULT_LOCATION)
    args = parser.parse_args()

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    engine = ing.make_engine()
    since = datetime.now(timezone.utc) - timedelta(days=args.lookback_days)
    residuals = load_settled_residuals(engine, args.location, since)
    rows = recompute_rmse_by_slot(residuals)
    if rows.empty:
        logger.info("No slot had >= %d settled residuals in the last %d day(s) - nothing to upsert.",
                   MIN_SAMPLES_PER_HOUR, args.lookback_days)
        return

    added, total = ing.upsert_dataframe(engine, "rmse_reference", rows, ["model_kind", "target", "hour_utc"])
    for (kind, target), group in rows.groupby(["model_kind", "target"]):
        logger.info("%s/%s: %d hour(s) updated, mean RMSE %.2f over %d-%d samples/hour", kind, target,
                   len(group), group["rmse"].mean(), int(group["n_samples"].min()), int(group["n_samples"].max()))
    logger.info("Upserted %d row(s) into rmse_reference (%d total).", added, total)


if __name__ == "__main__":
    main()
