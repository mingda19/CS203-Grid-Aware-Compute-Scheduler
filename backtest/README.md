# Optimiser backtest

Replays the optimiser over historical ERCOT LZ_NORTH prices and compares it with a fixed schedule. The plan
is in [docs/backtest-plan.md](../docs/backtest-plan.md). This folder holds ticket 1 (the dataset and the harness)
and ticket 2's metrics.

## How it works

For every workload profile and every day:
1. **Jobs** are generated from the profile with a fixed seed. Only jobs whose fixed schedule (everyone at their
   normal start time) fits the facility's capacity are accepted.
2. **Planning:** the day is planned once per price source, using only that source's prices. One source is
   perfect foresight, which plans with the actual prices.
3. **Settling:** the fixed schedule and every plan are paid at the actual prices.
4. **Checking:** every plan is checked against the optimiser contract's rules.

The harness runs the production optimiser code (`backend/.../service/allocation/`). It lives in the backend's
test code: `backend/src/test/java/com/gacs/backend/backtest/`.

## Run it

From the repo root:

```bash
# 1. Price inputs (written to backtest/data/, not committed)
.venv/bin/python3 backtest/export_prices.py \
    --forecasts model/reports/forecasts_walkforward.parquet --models xgb,naive_w1,naive_d1

# 2. The backtest (written to backtest/output/<run-id>/, not committed)
cd backend
mvn test -Dtest=BacktestRun -Dbacktest.run=true
```

Settings for step 2 (all optional):

| Property | Default |
|---|---|
| `backtest.from`, `backtest.to` | `2024-01-01`, `2026-08-29` |
| `backtest.profiles` | every file in `backend/src/test/resources/backtest/profiles/` |
| `backtest.sources` | every `forecast_*.csv` in the data folder |
| `backtest.dataDir`, `backtest.outDir` | `../backtest/data`, `../backtest/output` |
| `backtest.exactBudgetSeconds` | `30` |
| `backtest.threads` | CPU count |
| `backtest.runId` | current UTC time |

The full period with four profiles and three forecasts takes about 15 seconds on a 12-core laptop.

## Reproduce a run

```bash
mvn test -Dtest=BacktestRun -Dbacktest.run=true \
    -Dbacktest.reproduce=../backtest/output/<run-id>/manifest.json
```

This reruns with the same settings. It refuses to start if any input file or profile has changed, and fails
unless `days.csv`, `jobs.csv` and `skipped.csv` are byte-identical to the original. If the original has metrics,
`metrics_daily.csv` and `metrics_summary.csv` must also be byte-identical.

## Metrics (ticket 2)

Every run computes its metrics at the end and prints the headline per profile and source. To (re)compute them
for an existing run:

```bash
cd backend
mvn test -Dtest=BacktestMetricsRun -Dbacktest.metrics=../backtest/output/<run-id>
```

They are computed from the run's files and the same price files. The run refuses if a price file's hash no
longer matches the run's manifest.

For one profile, day and planning source:
- **Realised savings** = fixed schedule − plan, both at actual prices. What the operator really saves; the headline.
- **Projected savings** = fixed schedule − plan, both at the planning prices. What the app promised.
- **Perfect-foresight savings** = fixed schedule − a plan made with actual prices. The most that was possible.
- **Forecast-error cost** = realised plan cost − perfect plan cost.

For the whole period:
- totals, with savings as a ratio of totals
- a 95% interval from a 7-day moving-block bootstrap (2,000 draws, fixed seed, the same method as `model/evaluate.py`)
- daily savings: mean, median, standard deviation, 10th and 90th percentiles
- share of days with negative savings
- share of the possible savings captured
- energy (baseline vs plan)
- jobs scheduled, deadline adherence, violations, proven-optimal share
- forecast MAE / RMSE, over all hours and over the hours the plan used
- solve times (not repeatable)

**Savings rule (PRD):** a day where any job was not scheduled is reported but left out of every savings figure.

## Output (`backtest/output/<run-id>/`)

| File | Contents |
|---|---|
| `days.csv` | One row per profile, day and source: jobs, energy (kWh), costs and flags (see below) |
| `jobs.csv` | One row per job per source: work, times, status, where it ran, and its costs |
| `skipped.csv` | Days or sources left out, and why (for example, missing prices) |
| `timings.csv` | Solve time per row. The only results file that changes between reruns. |
| `manifest.json` | See below |
| `metrics_daily.csv` | Ticket 2 metrics, one row per profile, day and source |
| `metrics_summary.csv` | Ticket 2 metrics for the whole period, one row per profile and source |
| `metrics.json` | The summaries, solve times, bootstrap settings, and hashes of everything the metrics came from |

Cost and flag columns in `days.csv`:
- `baseline_cost_actual`: the fixed schedule at actual prices
- `optimised_cost_actual`: the plan at actual prices (realised)
- `optimised_cost_planned`: the plan at the planning prices (what the app would show)
- `proven_optimal`
- `violations`

The `manifest.json` records:
- the git commit, and whether there were uncommitted changes
- the settings
- the SHA-256 of every input file and profile
- the SHA-256 of the three repeatable outputs
- a summary

Costs are raw USD, not rounded, and cover scheduled jobs only. Source `perfect` is perfect foresight.

## Workload profiles

These are committed in `backend/src/test/resources/backtest/profiles/`:
- `business-hours-training`
- `nightly-batch`
- `mixed`
- `tight`

Hours are UTC; each file's description gives the Texas time. **Bump `version` whenever a profile changes**, so
old results are never mistaken for new ones.

## Known limits

- **Forecasts:** the forecasts used now come from `forecasts_walkforward.parquet` (9 October): XGBoost and naive
  models predicting one day ahead. The model the app serves predicts 48 hours ahead (`y0`). Its walk-forward
  forecasts need `model/train.csv` / `val.csv` rebuilt with the current feature columns (`lag_72h`, `lag_96h`,
  Henry Hub `lag2d` / `lag3d`), and the script that builds them isn't in this repo. Once rebuilt, run
  `model/evaluate.py --mode walkforward --target y0` and export from `forecasts_walkforward_y0.parquet`.
- **Jobs:** all generated, and they start and finish inside one UTC day (plan decision D5).
- **Market:** one zone (LZ_NORTH), day-ahead prices only.
