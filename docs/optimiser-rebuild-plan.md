# Optimiser rebuild plan: moving to the work-based contract (§6.4)

The optimiser on `staging` (`feat/optimiser`, last changed 2026-10-19) was built against the earlier draft of
the GRID-124 contract. The contract in [api-contract.md §6.4](api-contract.md) changed on 2026-10-20 to a
work-based allocation model. This plan lists what was decided, what is still open, and the changes needed to
bring the optimiser in line, in the order they should be done.

## 1. Decisions

### Settled

| # | Topic | Decision |
|---|---|---|
| 1 | Jobs competing for capacity | Place as many jobs as possible, then minimise total cost. Remaining ties go to the earlier `originalStartTime`, then the lower `id`. **Temporary for Sprint 3:** job priority replaces the tie-break in Sprint 4 (each job gets a weight instead of counting as 1). |
| 4a | Capacity used by the baseline | The baseline uses the same per-slot limit as the optimiser (the Capacity (AC3) rule: `capacityOverrides[]`, else `clusterCapacity`, else defaults). |
| 4b | Exempt jobs over the limit | Exempt jobs always run at `originalStartTime`, as in their baseline, even if they exceed a slot's limit. That slot then has no room for other jobs. |
| - | Per-job status | `OPTIMISED`, `UNCHANGED_EXEMPT`, `NO_FEASIBLE_SLOT`. `RESCHEDULED` and `UNCHANGED_ALREADY_OPTIMAL` are removed. |
| 5 | Seeded fallback prices | Kept. Staging's contract (PR #47) documents the history-based fallback and the `usedSeededFallbackPrices` field. The `422` still applies when a `forecast[]` or a partly covered forecast misses a baseline slot. |

Why decision 1 needs both parts: a `NO_FEASIBLE_SLOT` job is left out of the cost totals, so it costs $0.
An objective of "minimise cost" alone would leave jobs unplaced to save money. Placing a job must always
beat leaving it out.

### Still open

| # | Topic | Recommendation | Blocks |
|---|---|---|---|
| 3 | `400` / `422` vs `NO_FEASIBLE_SLOT` | `400` if a job cannot finish by its deadline even alone on the cluster. `422` if any baseline slot has no price. `NO_FEASIBLE_SLOT` only when the job does not fit around the other jobs. (A teammate's note proposed `NO_FEASIBLE_SLOT` for the first two cases.) | Phase 2 |
| 2 | Negative savings | Allow `projectedSavingsUsd`, `savingsUsd` and the percentage to go below 0, and explain it in the summary text and dashboard. This can happen because each job's baseline ignores the other jobs. | Phase 4 |
| 6 | Stored-jobs mode | When `jobs` is omitted, the current endpoint optimises the operator's stored jobs. The contract makes `jobs` required. Either remove the mode or add it to the contract. | Phase 2 |
| 7 | Rounding details | Costs at 4 dp (agreed). Still to state: percentage at 2 dp, and whether totals are summed before or after rounding. | Phase 4 |

### Contract wording to add

Not yet in the contract. For the contract owner to add, with a change-note row:

- Baseline sentence: "...took the largest share **the slot's capacity limit (see Capacity)** allows...".
- Capacity section: exempt jobs may exceed a slot's limit, and the slot then has no room for other jobs.
- Objective: "When jobs compete for capacity, the optimiser places as many jobs as possible, then minimises cost. Ties go to the earlier `originalStartTime`. Replaced by job priority in Sprint 4."
- Change notes: add the 2026-10-20 rename to `OPTIMISED` and the removal of `RESCHEDULED`.
- `422`: covers every baseline slot, not only the `originalStartTime` slot.
- `horizonHours` is ignored when `forecast[]` is sent. Range is 1–168.
- For a `NO_FEASIBLE_SLOT` item: `allocatedCpuHours` / `allocatedGpuHours` = 0, and the cost fields are `null`.

## 2. Where the code is

Branch off `staging`, which has the optimiser and, once the contract PR is merged, the new contract.

| File (`backend/src/main/java/com/gacs/backend/`) | Role today |
|---|---|
| `controller/ScheduleOptimizationController.java` | `POST /api/schedules/optimize` |
| `dto/OptimizeRequestDto.java`, `dto/OptimizeResponseDto.java` | Request and response |
| `service/ScheduleOptimizationService.java` | Validation, price loading, cost totals, explanation text |
| `service/ScheduleSolver.java` | Solver interface and records (`JobSpec`, `SolverAssignment`, `PerJobStatus`, ...) |
| `service/HybridScheduleSolver.java` | Exact solver first, simulated annealing as fallback |
| `service/ExactScheduleSolver.java` | ojAlgo 0/1 MILP, one binary per (job, slot) |
| `service/SimulatedAnnealingScheduleSolver.java` | Heuristic fallback, seeded from the greedy stub |
| `service/GreedyStubScheduleSolver.java` | Original stub; seeds the annealing solver and is used in tests |
| `service/CandidateSlots.java` | Legal one-hour start slots per job |
| `service/CostModel.java` | kWh factors (hard-coded), cost, rounding to 2 dp |
| `service/PriceSeries.java`, `service/PriceSeriesProvider.java` | Price curve and loading, including the seeded fallback |

Tests: `ExactScheduleSolverTest`, `HybridScheduleSolverTest`, `SimulatedAnnealingScheduleSolverTest`,
`GreedyStubScheduleSolverTest`, `ScheduleOptimizationServiceTest`, `ScheduleOptimizationAcceptanceTest`,
`ScheduleOptimizationIntegrationTest`, and the brute-force checker `BruteForceScheduleOracle`.

The earlier uncommitted one-hour drafts (`service/optimizer/`, `helpers/BruteForce.java`) were removed in Phase 1;
the new `BruteForceAllocator` replaces them.

## 3. The core change

Today every job runs for exactly one hour, using all its CPU and GPU hours in that hour. Under the new
contract:

- `cpuHours` / `gpuHours` are total **work**. In each slot `t` a job gets a share `x[j,t]` of its work
  (`0 < x ≤ 1`), using `x * cpuHours` CPU-hours and `x * gpuHours` GPU-hours. One share drives both.
- A placed job's shares add up to 1, and the slots it uses are **consecutive**.
- There is no per-job rate cap, so how long a job runs is decided by the solver.
- The **baseline** is the job started at `originalStartTime`, alone on the cluster, taking the most the
  slot limit allows each hour until done.

## 4. Phases

### Phase 0: contract and branch (before code)

- [x] Merge the contract PR into `staging` (#45).
- [ ] Settle open points 3 and 6 (they shape validation). 2 and 7 can wait until Phase 4. Point 5 is settled.
- [ ] Contract owner adds the wording in "Contract wording to add".
- [x] Branch `feat/optimiser-allocation` off `staging`.

### Phase 1: shared building blocks

These are used by both the solvers and the checker, so they come first. They live in the new package
`service/allocation/` next to the old solver, which keeps working until Phase 3 switches over and
deletes the old one-hour types (`ScheduleSolver.JobSpec`, `SolverAssignment`, `CandidateSlots`, `CostModel`).

- [x] **Slot capacity.** `SlotCapacity.limitAt(slot)`: the AC3 rule (override, else cluster capacity, else defaults).
- [x] **Config.** Move `cpu-kwh-per-hour`, `gpu-kwh-per-hour`, `default-max-cpu-hours-per-slot` and
      `default-max-gpu-hours-per-slot` to `gacs.optimiser.*` properties, keeping the current defaults.
- [x] **Baseline calculator.** From `originalStartTime`, give each slot `min(remaining work, the share the
      slot limit allows)` until the shares reach 1. The share a slot allows is
      `min(cpuLimit / cpuHours, gpuLimit / gpuHours)`, skipping the side whose hours are 0. Returns the
      per-slot allocations and the cost. Also used to place exempt jobs.
- [x] **Allocation records.** `OptimizerJob`, `SlotAllocation(slot, share)` and `JobPlan` (checks consecutive
      slots and shares adding up to 1; `start()` / `end()` give `scheduledStart` / `scheduledEnd`). Status and
      reason are added with the solver output in Phase 3.
- [x] **Candidate windows.** (`JobWindow`) Replace `CandidateSlots` (one-hour starts) with the window a job may use: starts
      in `[originalStartTime, originalStartTime + flexibilityWindowHours]`, every slot priced, last slot
      ending by `deadline`.
- [x] **Brute-force checker.** New `BruteForceAllocator` (test code), plus `ContractRules`, which lists
      every hard rule a set of plans breaks and is reused on the real solvers in Phase 5. The old
      `BruteForceScheduleOracle` goes with the old solver in Phase 3. Continuous shares can't be enumerated, so for small cases enumerate each
      job's start and end slots and shares in steps of 1/4, and keep the best plan by
      (jobs placed, then cost, then tie-break).
- [x] Unit tests (33, all passing): baseline (single slot, spill into a second slot because of a smaller override,
      exempt over the limit), slot capacity order, and the checker on hand-computed cases.

### Phase 2: request validation

- [ ] Request DTO: add `forecast[]` (`intervalStartUtc`, `predictedPrice`) and `capacityOverrides[]`
      (`intervalStartUtc`, `maxCpuHours`, `maxGpuHours`).
- [ ] Prices: if `forecast[]` is present use it, else load for `forecastLocation` from the **earliest
      `originalStartTime`** (not "now") for `horizonHours`. Neither present: `400`.
- [ ] `400` for duplicate job `id`, `originalStartTime` not on the hour (reject; don't round down), and a job
      that cannot finish by its deadline even with its baseline (open point 3).
- [ ] `422` when any baseline slot of any job has no price, naming the job and the slot (open point 3). Map
      to `422` in the global exception handler, using a dedicated exception type.
- [ ] Apply decisions 5 (seeded fallback) and 6 (stored-jobs mode).
- [ ] Past `originalStartTime` needs no special rule: with `forecast[]` it is allowed, and with
      `forecastLocation` it has no stored price, so it is a `422`.
- [ ] Remove `validateJobsAgainstWindow`'s horizon check tied to "now".

### Phase 3: the solvers

**Exact solver (`ExactScheduleSolver`, ojAlgo MILP).** For each flexible job `j` and slot `t` in its window:

| Variable | Type | Meaning |
|---|---|---|
| `p[j]` | binary | job is placed |
| `s[j,t]` | binary | job starts at `t` (only within the flexibility window) |
| `a[j,t]` | binary | job is running in `t` |
| `x[j,t]` | continuous `[0,1]` | share of the job's work in `t` |

Constraints:
- `Σ_t s[j,t] = p[j]` (one start if placed) and `Σ_t x[j,t] = p[j]` (all work done if placed).
- `x[j,t] ≤ a[j,t]`, and `x[j,t] ≥ δ * a[j,t]` with a small `δ` (e.g. `1e-4`), so a running slot always
  has a non-zero share and there are no hidden gaps.
- Consecutive slots: add an end binary `e[j,t]` (job's last slot is `t`) with `Σ_t e[j,t] = p[j]` and
  `a[j,t] = a[j,t-1] + s[j,t] - e[j,t-1]` (with `a[j,-1] = 0`). A job is then running from its start slot
  to its end slot with no gaps, and not at all if it is unplaced.
- No slot before the job's first allowed start, and no slot ending after `deadline`.
- Capacity per slot: `Σ_j x[j,t] * cpuHours[j] ≤ max(0, cpuLimit(t) - exemptCpu(t))`, and the same for GPU.
  Exempt jobs are fixed in advance by the baseline calculator (decision 4b).

Objective (minimise):

```
  - M * Σ_j p[j]                                     // place as many jobs as possible (decision 1)
  + Σ_j Σ_t x[j,t] * jobKwh[j] / 1000 * price(t)     // then cost
  + tie-break terms                                   // earliest originalStartTime / lowest id kept first; earliest start
```

`M` must be larger than the largest possible total cost, e.g. `1 + Σ_j jobKwh[j] / 1000 * maxPrice`.
Remove the old "unplaced" variable priced at the baseline cost: it caused jobs to be marked infeasible while
a slot was free. In Sprint 4, `M * p[j]` becomes `M * priority[j] * p[j]`.

`provenOptimal` = ojAlgo reports `OPTIMAL` within the time budget.

**Heuristic fallback (`SimulatedAnnealingScheduleSolver`).** Search over each job's start slot and the
order jobs are given capacity. Turn a state into a plan by going through jobs in that order and filling
consecutive slots from the start, like the baseline but with the room left after earlier jobs. A job that
can't finish before its deadline is unplaced. Score by (jobs placed, cost). Every plan built this way is
feasible, so `provenOptimal = false` plans still meet every rule. Seed from the greedy order (earliest
`originalStartTime` first).

**Hybrid routing (`HybridScheduleSolver`).** Keep the size check, but count variables per job (window length)
instead of one-hour candidate slots. Re-tune the threshold, since the new model has about 4 variables per
(job, slot) instead of 1.

**Greedy stub.** Rewrite it as the decoder above with the default order, or delete it and update the
tests that use it.

**Reasons for `NO_FEASIBLE_SLOT`.** Built after solving: find the first hours in the job's window where the
slot is full, e.g. "cluster full between 10:00 and 18:00".

### Phase 4: response

- [ ] `runId`: plain UUID v4 (drop the `run-` prefix).
- [ ] Run `status`: `SCHEDULED` / `NEEDS_ATTENTION`. Add `provenOptimal`. Remove `usedHeuristicFallback`
      (replaced by `provenOptimal`). Remove `usedSeededFallbackPrices` unless decision 5 keeps the fallback.
- [ ] Items: add `allocations[]` (`intervalStartUtc`, `cpuHours`, `gpuHours`) and `reason`. A
      `NO_FEASIBLE_SLOT` item has null `scheduledStart` / `scheduledEnd`, no allocations, and is left out of
      both totals.
- [ ] Costs: per-slot `share * jobKwh / 1000 * price`, rounded to 4 dp. Percentage per decision 7.
- [ ] Negative savings per decision 2: no clamping at 0.
- [ ] Timestamps: serialise with a `Z` suffix (§2.4). Accept input with a `Z` as well.
- [ ] Explanation text: drop wording about "moved" jobs and the "one hour" assumption. Cover placed,
      exempt and not-placed jobs, and negative savings.

### Phase 5: tests and docs

- [ ] Checker comparison: exact solver equals the brute-force checker on every small case (jobs placed and
      cost within 1e-9).
- [ ] Property tests over random small inputs: shares add up to 1, slots are consecutive, capacity holds
      every slot, nothing ends after a deadline or starts outside the window, exempt jobs match their
      baseline.
- [ ] Heuristic: always feasible, never places fewer jobs than the greedy decoder.
- [ ] Cases from this plan: job competing for one slot (tie-break picks the earlier job), override forcing a
      second slot, exempt jobs over the limit, negative savings, `400` / `422` / `NO_FEASIBLE_SLOT` split.
- [ ] Update `ScheduleOptimizationServiceTest`, the acceptance and integration tests, and
      `docs/schedule-optimize.http` (use `forecast[]` so the examples no longer expire).

## 5. Out of scope (Sprint 4)

- Job priority (replaces the tie-break in decision 1).
- Saving runs and queuing `NO_FEASIBLE_SLOT` jobs for a later run.
- Frontend: the decision panel still shows hard-coded sample figures and does not call the endpoint.
