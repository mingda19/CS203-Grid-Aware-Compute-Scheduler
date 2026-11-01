# Explanation Generation for Schedule Optimization Runs: Design

**Status:** Proposed (decisions below are locked in; see §8 for the few still open)
**Depends on:** `docs/api-contract.md` §6.4 (optimizer contract); PRD §6.4, §6.5, §6.6, §9, §11, §13, §14
**Supersedes:** nothing persisted today — `ScheduleOptimizationService`'s own class javadoc says
*"Nothing is stored."*

---

## 0. Purpose

After `POST /api/schedules/optimize` runs, an operator must read a grounded, plain-language
explanation of the schedule **before** approving it (PRD §6.5, §6.6). Today the explanation is a
deterministic `StringBuilder`/`String.format` string built in
`ScheduleOptimizationService.explain(...)`, and nothing about the run is persisted. This document
specifies the persisted data model, the (now real, enforced) constraint model, the LLM's output
schema, the deterministic fallback, and the guardrail pipeline. It also answers two direct
questions raised in review: whether this duplicates any existing persistence (§6), and what gets
tested (§7).

---

## 1. Persisted data model — trimmed to what's actually used

Every field below is consumed by either the explanation schema (§3), the guardrail pipeline (§5),
or basic run identity/ownership. Nothing is stored "for later" speculatively.

### 1.1 Naming note

PRD §9 names `ScheduleItem` as an entity. The codebase already has
`com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem` — an HTTP-facing **record**, not a JPA
entity, in a different package. No collision. The new JPA entity below is
`com.gacs.backend.model.ScheduleItem`.

### 1.2 `ScheduleRun` (table `schedule_runs`)

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` (PK) | |
| `runId` | `String(36)`, unique, not null | The run id already generated via `UUID.randomUUID().toString()` in `ScheduleOptimizationService.buildResponse` — now persisted instead of discarded |
| `ownerEmail` | `String(255)`, not null, indexed | Matches `Job.ownerEmail` |
| `requestedAt` | `LocalDateTime`, not null | UTC |
| `status` | `String(30)`, not null | `OptimizeResponseDto.STATUS_SCHEDULED` / `STATUS_NEEDS_ATTENTION` |
| `provenOptimal` | `boolean`, not null | From `AllocationResult.provenOptimal()`; feeds `uncertainty` context given to the LLM |
| `baselineCostUsd`, `optimizedCostUsd`, `projectedSavingsUsd`, `projectedSavingsPercent` | `double`, not null | The totals already computed in `buildResponse` — this is the core data the whole feature explains |
| `usedSeededFallbackPrices` | `boolean`, not null | |

**Cut from the earlier draft, and why:**
- `requestSnapshotJson` (the raw request, full serialized JSON) — dropped. It existed purely for
  hypothetical future audit/reproducibility (FR-23/FR-34), neither of which this feature's own
  generation or guardrail logic ever reads. If full-request audit is wanted later, add it then —
  it shouldn't be stored speculatively now.
- `forecastLocation`, `horizonHours` — dropped. Not consumed by the explanation or the guardrail;
  cheap but unused.
- `deterministicExplanationText` — dropped as a *stored* field. `explain(...)` is a pure function
  of `problem` + `result`, both already in memory during the same request that would need the
  fallback. There's no reason to persist a copy "just in case" — the fallback path simply calls
  `explain(...)` fresh when it's actually needed (§4).

### 1.3 `ScheduleItem` (table `schedule_items`)

Keyed `(scheduleRunId, jobId)`, unique composite index — "cost at original schedule vs. cost at
optimized schedule, keyed by job id," exactly as asked.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` (PK) | |
| `scheduleRunId` | `Long` FK, not null, indexed | |
| `jobId` | `Long`, not null | Not a hard FK to `jobs` — an inline optimize request can use a job id that was never CRUD-created |
| `jobName` | `String(255)`, not null | |
| `baselineStart` | `LocalDateTime`, not null | |
| `scheduledStart`, `scheduledEnd` | `LocalDateTime`, nullable | null for `NO_FEASIBLE_SLOT` |
| `perJobStatus` | `String(30)`, not null | `OPTIMISED` \| `UNCHANGED_EXEMPT` \| `NO_FEASIBLE_SLOT` |
| `reason` | `String(500)`, nullable | |
| `baselineCostUsd`, `optimizedCostUsd`, `savingsUsd` | `Double`, nullable | **Can be negative** — matches the existing DTO's documented behavior |
| `whyThisPlacement` | `TEXT`, nullable | The per-job explanation **text**, filled once generated — written by either the LLM or the deterministic fallback, same column either way (§4) |

**Cut:** `allocatedCpuHours`, `allocatedGpuHours`, `allocationsJson` (the per-slot breakdown) —
none of these are used by the explanation schema or the guardrail. They describe *execution*
detail, not the *decision* being explained. If a future feature needs to render per-slot detail
for a historical run, that's a different, separate need.

### 1.4 `ScheduleExplanation` (table `schedule_explanations`) — separate entity

Confirmed: kept as its own 1:1 entity, not columns on `ScheduleRun` — run-history list queries
stay cheap, and the explanation can be regenerated independently later without re-solving.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` (PK) | |
| `scheduleRunId` | `Long` FK, unique, not null | |
| `source` | `String(30)`, not null | `LLM` \| `DETERMINISTIC_FALLBACK` |
| `fallbackReason` | `String(40)`, nullable | **Null means the LLM succeeded.** Otherwise one of `NOT_CONFIGURED` \| `DISABLED_BY_CONFIG` \| `TIMEOUT` \| `PARSE_FAILURE` \| `VALIDATION_FAILURE` \| `PROVIDER_ERROR` |
| `modelName` | `String(60)`, nullable | e.g. `claude-haiku-4-5` — null when `fallbackReason` is non-null |
| `rawResponseJson` | `TEXT`, nullable | Raw unvalidated LLM output, retained only for debugging a failure — never shown to the operator |
| `headline` | `String(200)`, not null | Top-level one-line summary |
| `narrative` | `TEXT`, not null | Top-level prose |
| `validationErrorsJson` | `TEXT`, nullable | Short list of the specific checks that failed, when they did |
| `generatedAt` | `LocalDateTime`, not null | |

**Cut:** `llmAttempted` (redundant — derivable from `fallbackReason`: `NULL` = success,
`NOT_CONFIGURED`/`DISABLED_BY_CONFIG` = never attempted, anything else = attempted and failed),
`modelProvider` (now that Anthropic is the one locked-in choice — see §8 — there's nothing to
distinguish it from), `generationLatencyMs` (nice-to-have for performance monitoring, not needed
for the feature to work; easy to add back if that monitoring is wanted), **and
`structuredPayloadJson` entirely** — this is the biggest single cut. The original draft had the
LLM's full structured response re-serialized and stored as a JSON blob here. That's pure
duplication: every number in it already lives, normalized, in `ScheduleItem` and
`ConstraintImpact` rows (§2). The actual "structured payload" the UI renders for FR-28 is
**assembled at read time** by joining `ScheduleItem` + `ConstraintImpact` + this table's
`headline`/`narrative` — never persisted twice. This also removes a drift risk: if a
`ScheduleItem` cost were ever corrected, a separately-stored JSON blob could silently disagree
with it; with no blob, there's nothing to disagree.

### 1.5 Changes to `ScheduleOptimizationService`

`buildResponse(problem, result)` stays pure. A new step after it, in a read-write transaction,
persists `ScheduleRun` + `ScheduleItem` rows, builds `ConstraintImpact` rows (§2), and calls a new
explanation step synchronously with a short timeout (§8) to fill in `whyThisPlacement` /
`narrativeFragment` / the `ScheduleExplanation` row. The HTTP response shape is unchanged.

---

## 2. Constraint model — now genuinely solver-enforced, with a shared abstraction

Per direction: **all four constraint types below get real solver enforcement, not just a reserved
data model, and every constraint, when attached to a job, is a hard constraint** (same treatment
as today's `deadline` — unsatisfiable means `NO_FEASIBLE_SLOT`, never a soft trade-off). This
answers PRD §14 open question #4 for these types: no soft-preference mode.

### 2.1 Why this is still genuinely hard, even with enforcement decided

Different constraint types have fundamentally different notions of "what satisfying it costs"
and "how it couples jobs together":

| Type | Scope | Coupling |
|---|---|---|
| `DEADLINE` (existing) | single job | none — a pure boundary on that job's own window |
| `FLEXIBILITY_WINDOW` (existing) | single job | none |
| `ABSOLUTE_TIME_WINDOW` (new) | single job | none — just a second window filter, recurring daily rather than relative to baseline |
| `PRECEDENCE` (new) | pair | one job's feasible window depends on *where the other job actually gets placed* |
| `MUTUAL_EXCLUSION` (new) | pair | both jobs' feasible hours depend on each other's actual placement |
| `SYNCHRONIZED_FINISH` (new) | group (N jobs) | every member's end hour must agree with every other member's — breaks the "place one job, then the next" assumption the heuristic solver currently relies on entirely |

`ABSOLUTE_TIME_WINDOW` is the easy one — it never needs to know about any other job.
`PRECEDENCE`/`MUTUAL_EXCLUSION` need to know what's already been placed. `SYNCHRONIZED_FINISH` is
qualitatively harder: it can't be resolved by placing jobs one at a time at all, in either solver.

### 2.2 The two solvers this has to work in, confirmed from the actual code

- **`ExactAllocationSolver`** builds a MIP: for each flexible job and each candidate hour, binary
  variables `s`/`e`/`a` (start/end/running) and a continuous share `x`, wired into "one start, one
  end, consecutive run, capacity per hour" constraints (`ExactAllocationSolver.Model`). A new
  constraint type needs to add **more linear expressions over these same variables** — which is
  very tractable for `PRECEDENCE` and `MUTUAL_EXCLUSION` (see §2.4), and requires a small,
  necessary refactor: today `Model`'s per-job variables (`JobVars`) are a private nested record
  addressed by list position, not a `Map<OptimizerJob, Map<LocalDateTime, Variable>>`. Exposing
  that lookup is the one concrete prerequisite change this needs — flagged here as implementation
  work, not hand-waved as already wired.
- **`GreedyAllocator`** (used by the heuristic/annealing path) places jobs **one at a time**, each
  on its own cheapest feasible plan given capacity left by jobs already placed
  (`ResidualCapacity`), using `JobWindow` to get that job's own candidate hours. A new constraint
  type here needs to **narrow a job's `JobWindow`** based on what's already been placed for jobs
  it's coupled to, and/or **reject a candidate plan outright** if it conflicts with an
  already-placed job.

### 2.3 The abstract `Constraint` class

```java
public abstract class Constraint {

    public abstract ConstraintType type();

    /** The job(s) this constraint governs. */
    public abstract List<Long> jobIds();

    /**
     * Narrows {@code window} for {@code job}, given what's already been placed for the other
     * job(s) this constraint governs ({@code placedSoFar} may be missing an entry for a job not
     * yet placed). Default: no-op. Must never widen the window — only subclasses that can affect
     * feasibility independently of order need to override this.
     */
    public JobWindow restrict(OptimizerJob job, JobWindow window, Map<Long, JobPlan> placedSoFar) {
        return window;
    }

    /**
     * True when {@code candidate} for {@code job} is still compatible with this constraint given
     * what's already been placed. Used by the greedy/heuristic solver as a final accept/reject
     * check after {@link #restrict} has already narrowed the window. Default: always true.
     */
    public boolean isSatisfiedBy(OptimizerJob job, JobPlan candidate, Map<Long, JobPlan> placedSoFar) {
        return true;
    }

    /**
     * Adds this constraint's linear expressions directly into the exact solver's model, given a
     * lookup from job id and hour to that job's binary "running" variable for that hour (the
     * lookup the {@code ExactAllocationSolver.Model} refactor in §2.2 exposes). Default: no-op —
     * a constraint that's fully expressible through window narrowing alone (e.g.
     * `ABSOLUTE_TIME_WINDOW`) doesn't need this.
     */
    public void applyToExactModel(ExpressionsBasedModel model,
                                   Function<Long, Function<LocalDateTime, Variable>> runningVariableOf) {
    }
}
```

Every concrete type overrides only what it actually needs — most override one or two of the three
hooks, not all three.

### 2.4 The four concrete types

**`AbsoluteTimeWindowConstraint(jobId, dailyStartTime, dailyEndTime)`** — the easy one.
Overrides only `restrict`: filters the job's own candidate hours to those whose hour-of-day falls
in `[dailyStartTime, dailyEndTime)`, intersected with whatever `JobWindow` already allows. No
other-job dependency, so this works identically and trivially in both solvers — the exact solver
just needs the same filter applied when it builds `hours` for that job (an input-side change, no
new model constraints needed at all).

**`PrecedenceConstraint(beforeJobId, afterJobId)`** — "Job A must finish before Job B starts."
- Greedy solver: `restrict` on the after-job, once the before-job is in `placedSoFar`, moves its
  earliest allowed start to the before-job's actual end. Until the before-job is placed, no-op
  (ordering the group so predecessors get placed first is a placement-order concern, not this
  constraint's job — see §2.5).
- Exact solver: a direct linear inequality over the existing hour-indexed variables — for every
  hour `t` the after-job could start in, its start indicator can only be 1 if the before-job's
  "running" indicator is 0 for `t` and every later hour up to `t` (equivalently: a weighted-sum
  comparison between the before-job's latest running hour and the after-job's start hour). Exact
  formulation is implementation work; the key point is it's expressible directly over variables
  that already exist — no new variable types needed.

**`MutualExclusionConstraint(jobIdA, jobIdB)`** — "Job A cannot overlap with Job B."
- Greedy solver: `isSatisfiedBy` rejects any candidate for A that overlaps the hours B is already
  placed in (and vice versa).
- Exact solver: for every hour `t` both jobs could possibly run in, `runningA[t] + runningB[t] <= 1`
  — one linear expression per shared candidate hour. Simple.

**`SynchronizedFinishConstraint(jobIds)`** — "All jobs in this group must finish at the same
time." The qualitatively hard one, flagged explicitly as such:
- Greedy solver: placing members one at a time doesn't work — the first member placed fixes the
  group's finish time before anyone knows whether later members can actually reach it. This
  constraint type needs **its own placement step**, run before the normal one-job-at-a-time loop:
  for each group, intersect every member's own feasible end-hour set (from its own `JobWindow` and
  whatever capacity is free), pick the cheapest feasible common end hour across the whole group,
  then place every member to end there (or mark the whole group `NO_FEASIBLE_SLOT` if no common
  end hour exists for all members — consistent with "hard constraint, fails closed").
- Exact solver: equality constraints tying every member's end-hour-weighted sum to one shared
  variable representing the group's finish hour.

This is the one type where "just add a hook to the existing per-job loop" genuinely isn't enough —
it needs a dedicated code path in both solvers. Scoping it accurately here rather than
understating the work.

### 2.5 Placement order in the greedy solver, with group constraints present

`GreedyAllocator.placeInOrder` currently takes an arbitrary job order and places each job
independently. With `PRECEDENCE` and `SYNCHRONIZED_FINISH` constraints present, order now matters
for correctness, not just for the heuristic search quality it already affects: predecessors must
be placed before successors, and synchronized-finish groups must be placed as a unit (via §2.4's
dedicated step) before the remaining ungrouped jobs. This is a topological-ordering concern on top
of the existing order, not a rewrite of the per-job placement logic itself.

### 2.6 `ConstraintDefinition` / `ConstraintDefinitionJob` (tables `constraint_definitions`,
`constraint_definition_jobs`) — how a constraint gets declared

Unchanged in shape from the original draft, now genuinely consumed by
`OptimizationProblemFactory.build(...)` instead of sitting unused: for each job in the request,
load any `ConstraintDefinition` rows referencing it (via the join table) and construct the
matching concrete `Constraint`, attaching it to the `OptimizationProblem`. `role` on the join row
disambiguates each job's part (`PRECEDENCE` → `BEFORE`/`AFTER`; `MUTUAL_EXCLUSION`/
`SYNCHRONIZED_FINISH` → `MEMBER`; `ABSOLUTE_TIME_WINDOW` → `SUBJECT`). A small CRUD layer
(`ConstraintController`/`ConstraintService`, mirroring `JobController`/`JobService`) is the
natural way operators declare these — not detailed further here, since it's standard CRUD and
this document's depth belongs on the explanation feature and the enforcement mechanics above.

### 2.7 `ConstraintImpact` (table `constraint_impacts`) — unchanged shape, still needed

Per `(ScheduleItem, constraint that applied)`: `constraintType`, `code`, `slackHours` (nullable —
not every type has a natural one), `relatedJobIds`, `narrativeFragment` (TEXT, filled by either
the LLM or the deterministic fallback, same column either way — exactly like
`ScheduleItem.whyThisPlacement`). The generic envelope — type + code + optional slack + related
jobs + a text fragment, rather than one forced universal number — is still the right shape; only
the `enforced` flag from the original draft is now dropped, since there's no more "declared but
unenforced" state to flag once every attached constraint type is genuinely enforced.

---

## 3. The LLM output schema — squashed to exactly what the LLM needs to do

**Key design change from the first draft: the LLM never outputs a number.** Every cost, shift,
slack, and job/constraint identity is already computed deterministically by backend code before
the LLM is ever called — the LLM's only job is to *narrate* those already-known facts, never to
restate or re-derive them. This is both a simplification (exactly what was asked for — "I mainly
need the LLM to be able to explain things") and a much stronger grounding story: if the LLM never
states a number, it can never state a wrong one, and the guardrail no longer needs any numeric
cross-checking at all (§5).

```json
{
  "headline": "string",
  "narrative": "string",
  "jobExplanations": [
    { "jobId": 101, "explanation": "string" }
  ],
  "constraintExplanations": [
    {
      "jobId": 101,
      "constraintType": "DEADLINE | FLEXIBILITY_WINDOW | ABSOLUTE_TIME_WINDOW | PRECEDENCE | MUTUAL_EXCLUSION | SYNCHRONIZED_FINISH",
      "relatedJobIds": [102],
      "explanation": "string"
    }
  ]
}
```

The prompt supplies, as **input** context (never requested as output): every `ScheduleItem`'s
baseline/optimized cost, status, and shift; every `ConstraintImpact`'s type, slack, and related
job ids; and the run-level totals. The LLM only ever writes prose into the `explanation` fields,
addressed back to the backend's own job/constraint identities.

**Dropped from the first draft:** `scheduleRunId`, `generatedAtUtc`, `timeWindow`, `costs`,
`priceDrivers`, `constraintCoverage`, `uncertainty` — all either backend-known values that don't
need to round-trip through the LLM (run id, timestamp, horizon, costs), or no longer meaningful
(`constraintCoverage` existed to flag "declared but unenforced" constraints, which no longer
exist once every attached type is genuinely enforced), or judged out of scope for "explain the
decision" (`priceDrivers` — a full market-narrative claim with no structured ground truth to
check it against, the single hardest field in the original schema to guard, and not essential to
explaining *this* schedule's trade-offs).

**FR-26** ("distinguish forecast from observed, label projections") is now satisfied structurally
rather than by an LLM-produced field: the read-side assembly that serves cost figures to the UI
always labels them as projected — a fixed backend caption, never something the LLM decides.

**FR-28** ("structured payload... UI can render without free-form text parsing") is satisfied
because every explanation string is already keyed to a `jobId`/`constraintType`, addressable
individually — the UI never needs to parse one undifferentiated paragraph, even though the LLM
itself only ever produces text.

**Disambiguation note:** if a job has two constraints of the *same* type (e.g. two separate
precedence relationships), `relatedJobIds` is what distinguishes which explanation belongs to
which — the backend matches each `constraintExplanations[]` entry back to the `ConstraintImpact`
row with the same `(jobId, constraintType, relatedJobIds)`, not by array position.

### 3.1 Model and enforcement mechanism

**Locked in: Anthropic Claude Haiku**, via provider-native structured output (JSON-schema/strict
mode). Cheap and fast, and — per the schema design above — the model's job is narrow enough
(narrate already-known facts, never compute anything) that a smaller model is a good fit, not a
compromise.

```java
interface LlmExplanationClient {
    ExplanationGenerationResult generate(ScheduleRun run, List<ScheduleItem> items,
                                          List<ConstraintImpact> impacts, Duration timeout);
}
```

registered `@Autowired(required = false)`, exactly like `EmailService`'s `JavaMailSender` — the
established pattern in this codebase for an optional, degradable dependency.

---

## 4. The deterministic fallback — simplified along with the schema

### 4.1 Triggers

1. **Parse failure** — invalid JSON, or the provider signaled a non-success completion reason
   (safety refusal, truncation) instead of a clean completion.
2. **Timeout** — no response within the configured short budget (§8), enforced explicitly, not a
   client-library default.
3. **Validation failure** — now just two checks, since there are no numbers left to cross-check:
   - every `jobExplanations[].jobId` and `constraintExplanations[].jobId` refers to a real
     `ScheduleItem` in this run, and every real job/constraint-impact has a corresponding
     explanation entry (no gaps, no fabricated extras);
   - every string field is non-blank and within a backend-enforced length ceiling (provider
     JSON-schema modes don't reliably enforce string length, so this is checked in application
     code, not assumed from the schema).

### 4.2 The template — reuses `explain(...)`, doesn't replace it

On fallback: `ScheduleExplanation.narrative` = **exactly** `explain(...)`'s existing output,
unchanged, computed fresh from the in-memory `problem`/`result` (never persisted separately, per
§1.2). `headline` = a short derived sentence computed the same way `explain(...)` already derives
its numbers. Each `ScheduleItem.whyThisPlacement` and `ConstraintImpact.narrativeFragment` is
filled from a small fixed set of per-status/per-type sentences (e.g. `OPTIMISED` → "Placed on its
cheapest feasible plan."; `NO_FEASIBLE_SLOT` → the existing `reason` string verbatim;
`PRECEDENCE` → "Started after Job {relatedJobId} finished, as required."). The UI never needs to
special-case "no explanation available" — the same columns are populated either way.

### 4.3 What gets persisted

`ScheduleExplanation.source = DETERMINISTIC_FALLBACK`, `fallbackReason` set to the specific
trigger, `rawResponseJson` retained when something was actually received (parse/validation
failures), null for timeout/not-configured.

---

## 5. The guardrail pipeline — simplified along with everything above

| Stage | Check | On failure |
|---|---|---|
| 0 — Availability | Client present and enabled? | → fallback, `NOT_CONFIGURED`/`DISABLED_BY_CONFIG` |
| 1 — Transport/timeout | Explicit short timeout | → fallback, `PROVIDER_ERROR`/`TIMEOUT` |
| 2 — Schema validation | Parses? Completion reason clean? | → fallback, `PARSE_FAILURE` |
| 3 — Referential validation | §4.1 item 3's two checks | → fallback, `VALIDATION_FAILURE` |
| 4 — Persist & serve | Only after stage 3 passes, `source = LLM` | — |

**Still all-or-nothing, not partial-accept** — a self-contradictory partially-redacted sentence
is a worse trust failure than a visibly generic fallback (PRD §13). Because persistence (§1)
happens *before* this pipeline runs, PRD §11's "a failure to generate text shall not block access
to the numerical recommendation or the approval controls" holds literally regardless of outcome
here.

Numeric grounding checks from the first draft (cost mismatches, enforced-flag mismatches) are
gone — not loosened, just no longer applicable, because the LLM never produces a number or an
enforcement claim to check in the first place.

---

## 6. Existing persistence — checked directly, confirmed not duplicated

Two things worth being precise about, since they look adjacent to this feature at a glance:

**`WorkloadSchedule`** (table `workload_schedules`) — a plain manual-entry CRUD resource:
`title`, `type`, `cluster`, `powerKw`, `savings`, `status`, `startUtc`, `endUtc`, `notes`. Its
controller (`WorkloadScheduleController`) exposes only GET/POST/PATCH-status/DELETE, with no
`jobId`, no `runId`, and no reference to `Job` or the optimizer anywhere. It's consumed by the
frontend's calendar-view component and jobs dashboard as an operator-maintained log, entirely
disconnected from `POST /api/schedules/optimize`. Confirmed by reading the entity, its controller,
and its frontend callers directly — not something this feature overlaps with.

**`Job`** (table `jobs`) is the optimizer's *input* — the workload definitions it schedules
against. It is not, and was never meant to be, where a *result* lives.

**`Facility`**/**`Location`** are capacity configuration (per-tenant CPU/GPU limits, matching
`OptimizerProperties`'s defaults) and a weather/grid-data reference table respectively — unrelated
to scheduling results.

**Directly grepped the entire `com.gacs.backend.service.allocation` package** (all 21 files) for
any repository usage, `.save()` call, `@Entity`, or `JpaRepository` reference: zero matches. The
whole optimizer pipeline — `OptimizationProblem` → `AllocationSolver.solve()` → `AllocationResult`
→ `ScheduleOptimizationService.buildResponse()` → `OptimizeResponseDto` — is in-memory end to end,
returned straight to the controller as the HTTP response. There is genuinely nothing today that
persists an optimization run's result. `ScheduleRun`/`ScheduleItem`/`ScheduleExplanation` are new,
not a duplicate of anything that already exists.

---

## 7. Testing strategy for the explainer

Four layers, matching how the solver side of this codebase is already tested
(`BruteForceAllocatorTest`, `ExactAllocationSolverTest`, `SolverComparisonTest` give the pattern to
follow):

**Deterministic fallback — pure unit tests, no LLM involved at all.** Given a fixed
`OptimizationProblem` + `AllocationResult`, assert the fallback's `narrative` equals `explain(...)`'s
own output verbatim (not a reimplementation — literally call the same method and compare), assert
every `ScheduleItem` gets a non-blank `whyThisPlacement`, and assert every `ConstraintImpact` gets
a non-blank `narrativeFragment` matching its type's fixed sentence template. Covers every
`PerJobStatus` value and every `ConstraintType` at least once.

**Guardrail validation — pure unit tests against hand-built LLM responses, no real API calls.**
One test per failure mode in §4.1: a response missing a required job's explanation (should
fail), a response with an extra `jobId` that doesn't exist in the run (should fail), a response
with a blank or over-length string (should fail), a response with `relatedJobIds` that don't match
any real `ConstraintImpact` for that job (should fail), and a fully valid response (should pass
and persist with `source = LLM`). This is where most of the coverage should concentrate, since
it's the actual safety-critical logic and needs zero network access to test.

**Constraint enforcement — brute-force comparison, mirroring the existing solver test pattern.**
For each new constraint type, small hand-built cases (2-4 jobs) checked against a brute-force
enumeration of all valid placements, same approach `BruteForceAllocatorTest` already uses for the
existing deadline/window/capacity rules: confirm the exact solver's result actually satisfies the
constraint, confirm the greedy solver's result does too, and confirm a case constructed to be
infeasible under the constraint comes back `NO_FEASIBLE_SLOT` rather than silently violating it
("hard constraint" has to mean hard in the test suite, not just in the design doc). A dedicated
case for `SYNCHRONIZED_FINISH` where no common end hour exists across the group, confirming the
whole group fails closed together rather than partially placing some members.

**End-to-end wiring — one integration test per path**, mirroring
`ScheduleOptimizationIntegrationTest`'s existing style: a full `optimize()` call that persists a
`ScheduleRun`/`ScheduleItem`/`ConstraintImpact` set and produces a `ScheduleExplanation` row, run
twice — once with the LLM client mocked to return a valid response (`source = LLM`), once with it
mocked to return something invalid or absent (`source = DETERMINISTIC_FALLBACK`) — asserting the
HTTP response shape is identical either way except for which `source` ends up persisted. This is
what actually proves PRD §11's "a failure to generate text shall not block access to the
numerical recommendation" rather than just asserting it in prose.

---

## 8. Decisions locked in, and what's still genuinely open

**Locked in:**
- LLM: Anthropic Claude Haiku, provider-native structured output.
- All four new constraint types get real solver enforcement, not just a reserved data model.
- Every constraint, when attached to a job, is hard — no soft-preference mode (answers PRD §14
  open question #4 for these types).
- Synchronous generation with a short, hard timeout (no job-queue infrastructure exists yet).
- `ScheduleExplanation` stays a separate entity from `ScheduleRun`.
- Config and new classes live under `gacs.optimiser.explanation.*` / `com.gacs.backend.service.allocation`
  — see §8.1.

### 8.1 Config namespace and package placement, corrected

The earlier draft proposed `gacs.explanation.*` as a loose, disconnected prefix. Checked the
actual convention: the optimizer already uses a type-safe `@ConfigurationProperties(prefix =
"gacs.optimiser")` record (`OptimizerProperties`), not the looser `@Value("${...}")` style used
elsewhere in the codebase (`EmailService`, `DataFreshnessService`). Since explanation generation
is a sub-feature of the optimizer, the new properties nest under the same prefix:

```java
@ConfigurationProperties(prefix = "gacs.optimiser.explanation")
public record ExplanationProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("claude-haiku-4-5") String modelName,
    @DefaultValue("3s") Duration timeout) {
}
```

And every new class — `ExplanationProperties`, `LlmExplanationClient` and its Anthropic adapter,
the guardrail validator, the four `Constraint` subclasses — lives in
`com.gacs.backend.service.allocation`, alongside `OptimizerJob`, `JobWindow`, and the rest of the
solver's own types, not in the more general `com.gacs.backend.service` package where `EmailService`
lives. JPA entities (`ScheduleRun`, `ScheduleItem`, `ScheduleExplanation`,
`ConstraintDefinition`, `ConstraintDefinitionJob`, `ConstraintImpact`) still go in
`com.gacs.backend.model`, matching `Job`/`WorkloadSchedule`/`Facility`.

### 8.2 Still open

1. **Exact timeout budget and prompt wording** — left as config/iteration, not guessed precisely
   here.
2. **The `ExactAllocationSolver.Model` refactor** (§2.2) to expose a per-job, per-hour variable
   lookup is a real prerequisite for `PRECEDENCE`/`MUTUAL_EXCLUSION`/`SYNCHRONIZED_FINISH` on the
   exact-solver path, and isn't fully specified at the variable-name level here — that's
   implementation work, correctly left for implementation, not asserted as already wired.
3. **The constraint-declaration CRUD API** (§2.6) is sketched by analogy to `JobController` but
   not designed in detail — out of this document's depth, since the focus here is the explanation
   feature and the enforcement mechanics it depends on.

---

## 9. Delivery sequencing

- **Phase A:** `ScheduleRun`/`ScheduleItem` persistence (today's two constraint types only) +
  deterministic-fallback-always explanation. Retires "nothing is persisted," zero LLM risk, zero
  new constraint-enforcement risk.
- **Phase B:** the four new `Constraint` subclasses + solver integration (§2), starting with
  `ABSOLUTE_TIME_WINDOW` (easiest, single-job) and `PRECEDENCE`/`MUTUAL_EXCLUSION` (tractable pair
  constraints), then `SYNCHRONIZED_FINISH` last (needs the dedicated placement step in both
  solvers).
- **Phase C:** the real `LlmExplanationClient` (Anthropic Haiku) + guardrail pipeline. `source`
  flips to `LLM` when generation succeeds.
