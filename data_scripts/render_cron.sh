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
set -uo pipefail

cd "$(dirname "$0")" || exit 1

python ingestion.py
ingestion_status=$?

if [ "$ingestion_status" -eq 1 ]; then
    echo "ingestion.py hard-failed (exit 1) - skipping predictions.py"
    exit 1
fi
# exit 0 (full success) or 2 (one source failed, others ok) both proceed -
# see run_daily.sh / ingestion.py's module docstring for why.

python predictions.py
exit $?
