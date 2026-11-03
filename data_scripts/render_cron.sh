#!/bin/bash
# Entrypoint for the Render Cron Job (see render.yaml at the repo root).
# Not run_daily.sh: that script is for the local launchd dev-convenience
# scheduler and appends to a local logs/cron.log file, which would be both
# unreadable (Render's dashboard shows stdout/stderr directly, not a file
# inside the job's own throwaway container) and pointless (Render spins up a
# fresh container per run - nothing written to disk here survives to the
# next run). This script lets both scripts print straight to stdout/stderr
# instead, and assumes `pip install -r data_scripts/requirements.txt` already
# ran as the Render build step, so it uses `python` from PATH rather than a
# project-local .venv (which doesn't exist on Render's build either - it's
# gitignored).
#
# predict_lstm.py and predict_lear.py replace the old single predictions.py
# call (predictions.py itself is now a local-file dev/testing tool - see its
# module docstring) - both still run from this ONE script, after ingestion,
# rather than as their own separate Render Cron jobs: ingestion must run
# exactly once per day (GridStatus/EIA have tight monthly request quotas -
# ingestion.py's own module docstring - so a second cron job re-running it
# would burn through them twice as fast), and Render Cron has no
# "depends-on"/ordering between separate jobs to express "after ingestion"
# otherwise. What IS kept independent is each model kind's own failure: one
# call's exit code never skips the other's (predict_lstm.py/predict_lear.py
# module docstrings - a bug or outage in one model kind must not block the
# other from serving its own predictions).
set -uo pipefail

cd "$(dirname "$0")" || exit 1

python ingestion.py
ingestion_status=$?

if [ "$ingestion_status" -eq 1 ]; then
    echo "ingestion.py hard-failed (exit 1) - skipping predict_lstm.py/predict_lear.py"
    exit 1
fi
# exit 0 (full success) or 2 (one source failed, others ok) both proceed -
# see run_daily.sh / ingestion.py's module docstring for why.

python predict_lstm.py
lstm_status=$?
python predict_lear.py
lear_status=$?

if [ "$lstm_status" -ne 0 ] || [ "$lear_status" -ne 0 ]; then
    exit 1
fi
exit 0
