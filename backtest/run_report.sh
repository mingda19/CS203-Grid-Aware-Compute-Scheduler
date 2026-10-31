#!/usr/bin/env bash
# One command for the whole backtest report (docs/backtest-plan.md ticket 3):
# rebuild the 48h feature files, make walk-forward forecasts, export prices, run the headline backtest and every
# assumption rerun, then write docs/reports/backtest-<date>.md.
#
# Needs: model/train.csv + model/val.csv (built by the data scripts), .venv with model/requirements.txt, Java 21.
# Usage, from the repo root:   backtest/run_report.sh
# Data and run output go under backtest/data/ and backtest/output/report/ (not committed); the report is committed.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-$REPO/.venv/bin/python3}"
DATA="$REPO/backtest/data/y0"
OUT="$REPO/backtest/output/report"
FROM=2024-01-03   # first target day the 48h walk-forward covers
TO=2026-08-29
SENSITIVITY="$REPO/backend/src/test/resources/backtest/profiles/sensitivity"

cd "$REPO"
echo "== 1/4 features, forecasts, prices"
"$PY" backtest/build_features.py --out backtest/data/features
"$PY" model/evaluate.py --mode walkforward --target y0 --models naive_last_known,naive_w1,xgb \
    --data-dir backtest/data/features --out-dir backtest/data/walkforward_y0 > backtest/data/walkforward_y0.log
"$PY" backtest/export_prices.py --forecasts backtest/data/walkforward_y0/forecasts_walkforward_y0.parquet \
    --models xgb,naive_last_known,naive_w1 --out "$DATA"

run() { # run <runId> [extra -D settings...]
    local id="$1"; shift
    echo "== backtest $id"
    (cd backend && ./mvnw -q test -Dtest=BacktestRun -Dsurefire.failIfNoSpecifiedTests=false -Dbacktest.run=true \
        -Dbacktest.dataDir="$DATA" -Dbacktest.outDir="$OUT" -Dbacktest.runId="$id" \
        -Dbacktest.from="$FROM" -Dbacktest.to="$TO" "$@") | grep -E "^Backtest |^  " || true
    test -f "$OUT/$id/metrics.json" || { echo "run $id failed" >&2; exit 1; }
}

echo "== 2/4 headline run"
run main -Dbacktest.sources=xgb,naive_last_known,naive_w1

echo "== 3/4 assumption reruns"
run a2-flexibility -Dbacktest.sources=xgb -Dbacktest.profiles="$(ls "$SENSITIVITY"/mixed-flex-*.json | paste -sd, -)"
run a3-cpu-kwh-0.5x -Dbacktest.sources=xgb -Dbacktest.cpuKwhPerHour=0.025
run a3-cpu-kwh-2x   -Dbacktest.sources=xgb -Dbacktest.cpuKwhPerHour=0.10
run a3-gpu-kwh-0.5x -Dbacktest.sources=xgb -Dbacktest.gpuKwhPerHour=0.20
run a3-gpu-kwh-2x   -Dbacktest.sources=xgb -Dbacktest.gpuKwhPerHour=0.80
run a4-capacity-0.5x -Dbacktest.sources=xgb -Dbacktest.capacityCpu=128 -Dbacktest.capacityGpu=32
run a4-capacity-2x   -Dbacktest.sources=xgb -Dbacktest.capacityCpu=512 -Dbacktest.capacityGpu=128
run a8-heuristic -Dbacktest.sources=xgb -Dbacktest.solver=heuristic

echo "== 4/4 report"
"$PY" backtest/report.py --runs "$OUT" --prices "$DATA/actual_prices.csv" \
    --forecast-eval backtest/data/walkforward_y0 --out docs/reports
