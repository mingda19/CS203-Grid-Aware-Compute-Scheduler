"""
predict_lear.py

Thin CLI entrypoint serving the LEAR model (model/lear_model.py) - one of two
independent, separately-scheduled Render Cron jobs (predict_lstm.py is the
other). See predict_lstm.py's module docstring for why they're split: a bug
or outage in one model kind must never block the other from serving its own
predictions. All the actual feature-engineering, prediction and confidence-
scoring logic lives in predictions.py - this file is only "which model kind,
loaded from where."

Loads whichever `model_artifacts` row is active for model_kind="lear"
(written by data_scripts/update.py's weekly tune cycle) via
predictions.run_predictions_from_supabase - never a local file in
production, since Render Cron containers are ephemeral. --model-path is a
dev/testing escape hatch that bypasses Supabase entirely.

Usage:
  python predict_lear.py                      # both horizons, anchored on today (UTC) - the normal daily run
  python predict_lear.py --horizon y0          # only the 48h-ahead target
  python predict_lear.py --date 2026-03-02     # anchor on that UTC date instead of today
  python predict_lear.py --model-path ../model/models/lear_y0_full.joblib --horizon y0  # dev/testing: a local file
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from datetime import date
from pathlib import Path

import ingestion as ing
import predictions as pred

logger = logging.getLogger("predict_lear")
MODEL_KIND = "lear"


def main() -> None:
    parser = argparse.ArgumentParser(description="Predict LZ_NORTH DAM prices with the LEAR model "
                                                 "and write them to predicted_price.")
    parser.add_argument("--date", type=date.fromisoformat, dest="anchor_date",
                        help="anchor UTC date - \"now,\" the day features are safely known as of "
                            "(default: today). Target dates are this plus 48h (y0) / 72h (y1).")
    parser.add_argument("--horizon", choices=["y0", "y1", "both"], default="both")
    parser.add_argument("--model-path", type=Path,
                        help="dev/testing only: serve this local .joblib file instead of the active "
                            "Supabase model_artifacts row (requires --horizon y0 or y1, not both)")
    args = parser.parse_args()
    if args.model_path and args.horizon == "both":
        parser.error("--model-path needs a specific --horizon (y0 or y1), not both")

    logging.Formatter.converter = time.gmtime
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%SZ")

    engine = ing.make_engine()
    try:
        if args.model_path:
            pred.run_predictions(engine, {args.horizon: args.model_path}, anchor_date=args.anchor_date)
        else:
            horizons = ["y0", "y1"] if args.horizon == "both" else [args.horizon]
            pred.run_predictions_from_supabase(engine, MODEL_KIND, horizons, anchor_date=args.anchor_date)
    except Exception:
        logger.exception("Prediction run FAILED")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
