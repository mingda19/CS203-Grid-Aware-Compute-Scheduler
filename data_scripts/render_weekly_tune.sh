#!/bin/bash
# Entrypoint for the Render Weekly Continuous-Learning Tune Cron Job (see render.yaml).
# Runs update.py for weekly model tuning (LSTM and LEAR) against newly ingested data.
set -uo pipefail

cd "$(dirname "$0")" || exit 1

args=()
if [ -n "${TARGET:-}" ]; then
    args+=(--target "$TARGET")
fi
if [ -n "${KIND:-}" ]; then
    args+=(--kind "$KIND")
fi
case "${DRY_RUN:-false}" in
    true) args+=(--dry-run) ;;
    false|"") ;;
    *) echo "DRY_RUN must be true or false" >&2; exit 2 ;;
esac

python update.py "${args[@]}"
exit $?
