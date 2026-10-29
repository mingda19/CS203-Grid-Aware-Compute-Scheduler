# Backtest plan: fixed-schedule baseline, savings metrics and report

Plan for three tickets. Nothing is implemented yet.

1. **Build repeatable fixed-schedule baseline dataset and harness**
2. **Calculate baseline cost, optimiser cost and savings metrics**
3. **Generate backtest report and validate assumptions**

The PRD's headline target is a **15–25% cut in energy cost for flexible load, measured against a fixed-schedule
baseline**, and it says this "must be validated through historical backtesting" (PRD section 1). The backtest
section of the PRD adds:

> The baseline shall be a fixed schedule using the same workload set, resource configuration, and evaluation
> horizon. Backtesting shall use two years of ERCOT historical data where the required inputs are available.
> The evaluation shall report energy cost, energy consumed, completed workload units, deadline adherence,
> forecast error, schedule feasibility, and savings variance.

and the MVP cannot be signed off without "a backtesting report covering forecast accuracy, projected cost
reduction, throughput, and constraint violations".

## Progress

**Ticket 1: done** on `feat/backtest` (2026-10-29). See [backtest/README.md](../backtest/README.md) for how to run it.

- **Decisions applied as recommended:** D1–D7. D4 is partly applied (see the forecasts note below).
- **Profiles:** `business-hours-training`, `nightly-batch`, `mixed`, `tight` (v1).
- **Full run:** 2024-01-01 to 2026-08-29, four profiles, XGBoost plus two naive forecasts plus perfect foresight.
  That's 15,552 day rows in about 15 s: 0 skipped, 0 rule violations, 100% proven optimal.
- **Repeatable:** reruns give byte-identical `days.csv` / `jobs.csv` / `skipped.csv`, at different thread
  counts too (checked on three full runs).
- **Optimiser fix found by the backtest:** when several plans cost exactly the same, ojAlgo's parallel search
  returned a different one from run to run. This also affected the live endpoint. `ExactAllocationSolver` now
  adds tiny fixed tie-break weights per share and per running hour, which make the cheapest plan unique. A
  regression test uses the real day that exposed it.
- **Forecasts (D4 not fully met):** the backtest currently plans with the 9 October walk-forward file (XGBoost,
  one day ahead). The served model's 48 h forecasts (`y0`) need `model/train.csv` / `val.csv` rebuilt with the
  current feature columns. The script that builds them isn't in this repo; ask the data/model owner.
- **Early signal, not a result:** with jobs confined to one UTC day (D5), daytime profiles save very little.
  The UTC day ends at 19:00 Texas time, so these jobs can't move into cheap overnight hours. Ticket 3 should
  test D5 directly.

## 1. What already exists

| Piece | Where | Use in the backtest |
|---|---|---|
| Hourly LZ_NORTH DAM prices, 2020-01-01 to 2026-08-30 | `model/train.csv`, `model/val.csv` (`lz_north_price`; not committed, rebuilt by the data scripts) | Actual prices that every plan is settled at |
| Out-of-sample day-ahead forecasts | `model/evaluate.py --mode walkforward --target y0`: five expanding-window folds, test anchor days 2024-01-01 to 2026-08-31, written to `model/reports/forecasts_walkforward_y0.parquet` | Prices the optimiser plans with, made only from data available at the time. **Not generated yet** (`model/reports/` is empty). |
| Optimiser | `backend/.../service/allocation/`: `OptimizationProblemFactory`, `HybridAllocationSolver`, `BaselineCalculator`, `EnergyCostModel` | Plans each day; the same code the endpoint uses |
| Fixed-schedule baseline per job | `BaselineCalculator` | Each job at its original time, alone on the cluster |
| Single-run cost metrics | `ScheduleOptimizationService` response: baseline cost, optimised cost, savings, percentage, per job and in total | The per-day numbers the backtest adds up |
| Rule checker | `ContractRules` (test code) | Checks every backtest plan for constraint violations |
| Default facility | `Facility`: 256 CPU-hours and 64 GPU-hours per hour, `LZ_NORTH` | Resource configuration for the baseline and the optimiser |

**Gaps:**
- There is no record of real historical jobs, so the workload set has to be generated (section 3).
- Forecasts for past days don't exist yet.
- Energy used (kWh) isn't in the response (PRD FR-21).
- Job `priority` (merged on `staging`, 2026-10-28) is validated but not yet passed to the solver.

## 2. Decisions needed before starting

| # | Question | Recommendation |
|---|---|---|
| D1 | **Workload set.** There is no job history. | Generate it from a small set of named, versioned **workload profiles** (section 3.1) with fixed seeds. Report results per profile, so the 15–25% claim is never resting on one invented mix. |
| D2 | **What "fixed-schedule baseline" means.** The optimiser's baseline prices each job alone on the cluster, so a set of baselines can exceed capacity. A real fixed schedule must fit. | Generate workloads whose fixed schedule fits capacity, and check it. Then the fixed schedule and the optimiser's baseline are the same thing, and savings can't go negative just because the baseline was impossible. Track how often a generated day is rejected. |
| D3 | **Evaluation period.** | 2024-01-01 to 2026-08-30: the walk-forward test period, about 2.7 years of out-of-sample forecasts. That meets "two years". |
| D4 | **Which forecast plans the schedule.** | Plan with the model the app serves (XGBoost, `y0` = 48 h ahead). Also run the naive "same hour last week" forecast and perfect foresight (actual prices), to show what forecast quality is worth. |
| D5 | **Planning cadence.** | One run per day. Jobs start and finish inside that UTC day (version 1). Jobs that cross midnight would need the next day's `y1` forecast; leave that for later and say so in the report. |
| D6 | **Where the harness lives.** | In the backend, Java, calling the allocation package directly, so the backtest runs exactly the production optimiser code. Run it as a JUnit test tagged `backtest`, excluded from the normal build (no new dependencies). The report is generated in Python next to `evaluate.py`, which already has pandas and plotting. |
| D7 | **Repeatability.** Data files are never committed (`.gitignore`). | Every input is rebuilt by a script. Each run writes a **manifest**: git commit, SHA-256 of every input file, profile versions, seeds and settings. Rerunning with the same manifest must give byte-identical results (section 3.4). |

## 3. Ticket 1: baseline dataset and harness

### 3.1 Workload profiles
Each profile is a small JSON file in `backend/src/test/resources/backtest/profiles/`, committed, with a version
number. For each day it describes:
- how many jobs, with a range
- each job's CPU-hours and GPU-hours (ranges), and what share are GPU-heavy
- **fixed start times**: the hours the jobs normally run at, e.g. business-hours training or a nightly batch at 01:00
- flexibility window and deadline slack (ranges)
- the share of exempt jobs

Starting profiles, small enough that most days solve to proven optimal:

| Profile | Shape |
|---|---|
| `business-hours-training` | GPU-heavy runs starting 08:00–12:00, 4–12 h flexibility |
| `nightly-batch` | CPU batch starting 00:00–03:00, little flexibility |
| `mixed` | Both, plus 10% exempt jobs |
| `tight` | Same as `mixed` with flexibility 0–2 h, a lower bound on savings |

A generator turns `(profile, day, seed)` into that day's jobs. The seed for each day comes from the profile seed
and the date, so any single day can be rebuilt on its own.

### 3.2 Price inputs
A script `backtest/export_prices.py` (Python, next to `model/`) writes two plain CSV files the Java harness can read:
- `actual_prices.csv`: hour (UTC), price. Raw `lz_north_price`, not the winsorised column: the backtest must pay real spike prices.
- `forecast_prices_<model>.csv`: hour, price, model, anchor day. Taken from `forecasts_walkforward_y0.parquet`, plus the naive forecast.

It checks that each covered day has 24 hours of both, and lists the days that don't. Days with missing prices are
skipped and counted, never filled in.

### 3.3 The harness
For each day in the period, for each profile and each planning price source:
1. Generate the day's jobs and check the fixed schedule fits capacity (D2).
2. Build an `OptimizationProblem` with the **planning prices** (forecast) and the facility capacity, using the
   same factory as the endpoint.
3. Solve with `HybridAllocationSolver`.
4. Price three things at the **actual** prices: the fixed-schedule baseline, the optimised plan, and the plan made
   with perfect foresight.
5. Check every plan with `ContractRules`: window, deadline, consecutive hours, capacity, exempt jobs.
6. Write one row per job and one row per day (section 4.2) to `backtest/output/<run-id>/`.

**No look-ahead by construction.** The solver only ever receives the planning price series. Actual prices are
used afterwards to settle costs. This is enforced in the harness's types and covered by a test.

### 3.4 Repeatability
- **Inputs:** rebuilt by scripts; the manifest records their hashes. A run refuses to start if an input's hash
  differs from the manifest it was asked to reproduce.
- **Seeds:** fixed per profile and derived per day.
- **The solver:** the heuristic is deterministic, but the exact solver has a time limit, so a slow machine could
  stop it at a different point. The harness raises the time budget (e.g. 30 s per day), records `provenOptimal`
  for every day, and the report states the share of proven days. If a day is still not proven, its result comes
  from the deterministic heuristic, so reruns match.
- **Run time:** about 975 days × 4 profiles × 3 price sources ≈ 11,700 solves. With 5–10 jobs a day, most solve
  in under 0.2 s. Days run in parallel (each solve is single-threaded), so a full run should take minutes. Measure
  and record it.

### 3.5 Tests (ticket 1 done when these pass)
- The same seed and inputs give identical output files, compared by hash.
- A one-day backtest with hand-computed costs.
- The fixed schedule fits capacity on every accepted day.
- The solver never receives actual prices when planning on a forecast.
- Missing-price days are skipped and counted, not filled.

## 4. Ticket 2: cost and savings metrics

### 4.1 Single-run part (small, do first)
- Add **energy used (kWh)** per job and in total to the optimiser response (PRD FR-21), in the `EnergyCostModel`
  units. Energy is the same for the baseline and the plan, because the work is the same. Reporting it lets the
  backtest check that.
- Check with the team whether this needs a contract change. **Don't edit `docs/api-contract.md`; propose the wording.**

### 4.2 Backtest metrics
Per day, per profile and price source:

| Metric | Definition |
|---|---|
| Baseline cost | Fixed schedule at actual prices |
| Realised optimised cost | Plan made on the forecast, paid at actual prices. **The headline number.** |
| Projected optimised cost | Plan made on the forecast, priced at the forecast (what the app shows the operator) |
| Perfect-foresight cost | Plan made on actual prices, paid at actual prices: the most the optimiser could save |
| Realised savings, $ and % | Baseline − realised optimised; % of baseline |
| Projected − realised gap | How far the app's promise differs from what happens (PRD guardrail) |
| Forecast-error cost | Realised − perfect foresight |
| Energy used (kWh) | Baseline vs optimised; must be equal for placed jobs |
| Jobs completed | Placed / total; `NO_FEASIBLE_SLOT` count (throughput guardrail) |
| Deadline adherence | Share of placed jobs ending by their deadline; should be 100% |
| Constraint violations | Count from `ContractRules`; must be 0 |
| Forecast error | MAE and RMSE of the forecast over the hours the jobs used, and over all hours |
| Proven optimal | Share of days |
| Solve time | Median and 95th percentile |

Over the whole period:
- totals
- mean and median daily savings %
- standard deviation, 10th and 90th percentiles
- share of days with negative realised savings
- a 95% confidence interval for the savings %, from a block bootstrap by week (the same method and settings `evaluate.py` uses: 7-day blocks, 2,000 draws)

**Savings rule (PRD):** savings are only claimed when the baseline and the plan cover the same jobs and the same
energy. Days where a job is `NO_FEASIBLE_SLOT` are reported separately and left out of the savings figures.

### 4.3 Tests (ticket 2 done when these pass)
- Every metric on a hand-computed two-day backtest.
- Totals equal the sum of the days.
- Energy for the baseline equals energy for the plan on every day with all jobs placed.
- The bootstrap gives the same interval with the same seed.

## 5. Ticket 3: report and assumption checks

### 5.1 Report
`backtest/report.py` reads the harness output and writes `docs/reports/backtest-<date>.md`, with charts beside it.
The report is committed; the raw output is not. Sections:
1. **Headline:** realised savings % with its confidence interval, per profile, against the 15–25% target.
2. **Method:** period, profiles, facility, forecast model, the manifest (commit and input hashes), and how to rerun.
3. **Savings:** daily distribution, by month and season, by price volatility, and the worst days.
4. **Forecast:** realised vs projected vs perfect foresight; forecast error vs lost savings.
5. **Guardrails:** throughput, deadline adherence, violations (expected 0), proven-optimal share, solve time.
6. **Assumptions:** each one with its test and a verdict (section 5.2).
7. **Limits:** synthetic workloads, jobs inside one day, one zone, no real-time prices.

### 5.2 Assumptions to check
| # | Assumption | How it's tested | Passes when |
|---|---|---|---|
| A1 | Savings of 15–25% are achievable | Headline result per profile | The confidence interval overlaps 15–25% for the realistic profiles |
| A2 | Flexibility is what drives savings | Rerun `mixed` with flexibility fixed at 0, 2, 4, 8, 12, 24 h | Savings rise with flexibility and are about 0 at 0 h |
| A3 | Results don't hinge on the kWh factors (0.05 / 0.40) | Rerun with each factor halved and doubled | Savings % moves by less than ±2 points |
| A4 | Capacity doesn't distort the result | Rerun at 50%, 100% and 200% of facility capacity | Changes are explained (tighter means less room); no violations |
| A5 | The forecast is good enough to act on | XGBoost vs naive vs perfect foresight | XGBoost realised savings ≥ naive, and the gap to perfect foresight is reported |
| A6 | The app's projected savings are honest | Projected vs realised, per day | Mean gap near 0, with no systematic over-promise |
| A7 | Negative savings are rare (decision 2) | Share of days below 0 | Reported; investigated if above 5% |
| A8 | The heuristic is close to optimal | On proven-optimal days, rerun heuristic-only | Median gap below 1% |
| A9 | Price spikes don't dominate | Savings with and without the top 1% of price hours | Reported; flagged if most of the savings come from spikes |

A failed assumption is a finding, not a bug. The report says so plainly.

### 5.3 Done when
- The report is generated end to end by one documented command, from a clean checkout plus the data scripts.
- Every number in it traces back to the manifest.
- The team has reviewed the A1 verdict.

## 6. Order of work

| Step | Ticket | Work | Size |
|---|---|---|---|
| 0 | - | Agree D1–D7. Branch `feat/backtest` off `staging`. | S |
| 1 | 2 | Energy used (kWh) in the optimiser response; propose contract wording | S |
| 2 | 1 | Run `evaluate.py --mode walkforward --target y0` once; write `export_prices.py` | S |
| 3 | 1 | Workload profiles and generator, with the capacity check (D2) | M |
| 4 | 1 | Harness: daily loop, three price sources, rule checks, output files, manifest | M |
| 5 | 1 | Harness tests (section 3.5), including the repeatability check | M |
| 6 | 2 | Per-day and whole-period metrics, bootstrap (section 4.2) and tests | M |
| 7 | 3 | `report.py` and the first full run | M |
| 8 | 3 | Assumption reruns (A2–A9) and verdicts | M |
| 9 | 3 | Team review of the headline result | S |

Steps 2 and 3 can run in parallel.

## 7. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| Synthetic workloads don't look like real ones | The savings figure may not carry over | Several profiles, per-profile results, sensitivity runs; state it in the report |
| Walk-forward forecasts take long to generate (LSTM retrained per fold) | Delays step 2 | Start with XGBoost and naive only; add the LSTM later |
| Exact solver not proven on busy days | Results depend on machine speed | Bigger time budget for the backtest; record the proven share; heuristic fallback is deterministic |
| Job priority (`staging`) not yet used by the solver | Backtest won't reflect priority | Out of scope here; rerun once priority is wired in |
| Data scripts change the CSVs later | Old results can't be reproduced | Manifest hashes; a run refuses mismatched inputs |
| Only LZ_NORTH, DAM, jobs inside one day | Narrower than production | Listed under "Limits" in the report |
