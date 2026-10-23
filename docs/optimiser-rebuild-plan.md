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
| 3 | `400` / `422` vs `NO_FEASIBLE_SLOT` | Follow the contract as written (decided 2026-10-23): `400` if a job cannot finish by its deadline even alone on the cluster; `422` if any hour of its baseline has no price; `NO_FEASIBLE_SLOT` only when other jobs took the room. |
| 6 | Stored-jobs mode | Removed (decided 2026-10-23): `jobs` is required, as in the contract. Leaving it out is a `400`. |
| 5 | Seeded fallback prices | Kept. Staging's contract (PR #47) documents the history-based fallback and the `usedSeededFallbackPrices` field. The `422` still applies when a `forecast[]` or a partly covered forecast misses a baseline slot. |

Why decision 1 needs both parts: a `NO_FEASIBLE_SLOT` job is left out of the cost totals, so it costs $0.
An objective of "minimise cost" alone would leave jobs unplaced to save money. Placing a job must always
beat leaving it out.

### Still open

| # | Topic | Recommendation | Blocks |
|---|---|---|---|
| 2 | Negative savings | Allow `projectedSavingsUsd`, `savingsUsd` and the percentage to go below 0, and explain it in the summary text and dashboard. This can happen because each job's baseline ignores the other jobs. | Phase 4 |
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
- [x] Settle open points 3, 5 and 6. Points 2 and 7 wait until Phase 4.
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

### Phase 2: request validation (done)

Built as `OptimizationProblemFactory`, which turns a request into a validated `OptimizationProblem` (jobs,
prices, capacity and every job's baseline). The live endpoint keeps using the old service until Phase 4
switches it over, so it is never half-migrated.

- [x] Request DTO: added `forecast[]` (`intervalStartUtc`, `predictedPrice`) and `capacityOverrides[]`
      (`intervalStartUtc`, `maxCpuHours`, `maxGpuHours`). The old 4-argument constructor is kept until Phase 4.
- [x] Prices: `forecast[]` wins; otherwise load for `forecastLocation` from the **earliest
      `originalStartTime`** for `horizonHours` (1–168, default 48, ignored with `forecast[]`). Neither: `400`.
- [x] `400`: missing body or `jobs` (decision 6), missing fields, blank or over-long name, negative or
      both-zero work, duplicate `id`, a time not on the hour (rejected, not rounded), deadline before start,
      negative flexibility, bad forecast or capacity entries, and a job that cannot finish by its deadline
      even alone on the cluster (decision 3).
- [x] `422` (`UnprocessableDataException`, mapped in `GlobalExceptionHandler`): any hour of a job's baseline
      has no price; the message names the job and the hour. Input errors are always reported first.
- [x] Past `originalStartTime`: allowed with `forecast[]`. With `forecastLocation` the provider's history
      fallback (decision 5) usually prices it; otherwise it is a `422`.
- [ ] Moved to Phase 4: remove the old service's horizon check tied to "now", along with the old service.

### Phase 3: the solvers (done)

All in `service/allocation/`, behind the new `AllocationSolver` interface. Results are `AllocationResult`
(a `JobOutcome` per job: plan, `perJobStatus`, `reason`) plus `provenOptimal`.

- [x] **Exact solver (`ExactAllocationSolver`, ojAlgo).** Variables per flexible job and hour: placed `p`,
      start `s`, end `e`, running `a` (binary), share `x` in [0, 1]. Constraints as designed: one start and one
      end if placed, shares add up to `p`, `a[t] = a[t-1] + s[t] - e[t-1]` for one consecutive block,
      `MIN_SHARE * a <= x <= a` (`MIN_SHARE = 1e-4`), starts only in the window, no unpriced hours, capacity
      after exempt jobs. Instead of one objective with a big `M`, it runs **two passes**: (1) maximise jobs
      placed; (2) with that count fixed, minimise cost plus tiny tie-break terms. A big `M` next to cent-sized
      costs could let the solver's gap tolerance hide real cost differences. The old "unplaced at baseline
      cost" variable is gone. `provenOptimal` = both passes finish optimal.
- [x] **Heuristic (`AnnealingAllocationSolver` + `GreedyAllocator`).** Simulated annealing over the order
      jobs are placed in. Each job takes its cheapest valid run in the capacity left (cheapest hours filled
      first, which is optimal for one job), so every result meets all the rules. Starts from the tie-break
      order; after a swap only the jobs from the swapped position on are re-placed. Deterministic: seeded
      from the problem, iterations depend only on the job count.
- [x] **Hybrid routing (`HybridAllocationSolver`, the Spring bean).** Exact first for runs up to 300
      (job, hour) pairs with a 2 s budget; returns it when proven, otherwise the better of exact and heuristic.
- [x] **Reasons for `NO_FEASIBLE_SLOT`**: "Cluster full between <start> and <deadline> UTC: other jobs use the
      capacity this job needs to finish by its deadline."
- [x] Tests: hand-checked cases for the exact solver; 120 random small runs checking exact against the
      brute-force checker and the heuristic against exact, plus every contract rule (14 of the runs leave a
      job out, 54 spread a job over several hours); routing; determinism; 16 validation tests.
- [ ] Moved to Phase 4: delete the old one-hour solvers (`ScheduleSolver`, `ExactScheduleSolver`,
      `HybridScheduleSolver`, `SimulatedAnnealingScheduleSolver`, `GreedyStubScheduleSolver`, `CandidateSlots`,
      `CostModel`, `BruteForceScheduleOracle` and their tests) when the service switches over.

Measured on 2026-10-23 (`-Xmx384m`, random runs over 24–72 hours):

| Run | Time | Proven optimal |
|---|---|---|
| 5 jobs / 24 h | 0.13 s | yes |
| 10 jobs / 48 h | 0.19 s | yes |
| 20 jobs / 48 h | 2.4 s (exact uses its budget, then heuristic) | no; heuristic within 0.3% of exact's best |
| 40 jobs / 48 h | 0.4 s (heuristic only) | no |
| 60 jobs / 72 h | 0.6 s (heuristic only) | no |

### Phase 4: response and switch-over

- [ ] `ScheduleOptimizationService` uses `OptimizationProblemFactory` + `HybridAllocationSolver`; delete the
      old solvers and the stored-jobs mode (decision 6).

- [ ] `runId`: plain UUID v4 (drop the `run-` prefix).
- [ ] Run `status`: `SCHEDULED` / `NEEDS_ATTENTION`. Add `provenOptimal`. Remove `usedHeuristicFallback`
      (replaced by `provenOptimal`). Keep `usedSeededFallbackPrices` (decision 5).
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
