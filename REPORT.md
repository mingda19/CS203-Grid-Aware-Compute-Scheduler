# REPORT — `fix/revoke-calendar-elapsed`

Base: `chore/retouch-frontend` (294f7c5). Three commits, one per task.

| | |
|---|---|
| `e03f1db` | Task 1 — revoke: governance bypass + error mapping |
| `721d878` | Task 2 — `appliedAt` exposed end to end |
| `5d4899d` | Task 3 — reschedule jobs whose start has passed |

## Test results

| Suite | Before (untouched base) | After |
|---|---|---|
| `backend` (`mvn test`) | 719 run, **1 failure**, 16 skipped | 760 run, **1 failure**, 16 skipped |
| `frontend` (`npx vitest run`) | 151 passed | 174 passed |
| `frontend` (`npx tsc --noEmit`) | clean | clean |

41 backend tests and 23 frontend tests added. **The one backend failure is pre-existing and
unrelated** — see "The pre-existing backend failure" below. It is the *same single test* before and
after my changes, and I did not touch it.

### Toolchain notes (sandbox, not the repo)

- `./mvnw` could not run: the wrapper downloads Maven 3.9.16 from Maven Central and the sandbox
  proxy answered `429`. I used the pre-installed `/opt/maven/bin/mvn` (3.9.11) instead. Same POM,
  same surefire, same JVM.
- `backend/pom.xml` sets `<java.version>25</java.version>` and only JDK 21 was installed, so
  `release version 25 not supported`. I downloaded Temurin 25+36 to `/opt/jdk25` and ran with
  `JAVA_HOME=/opt/jdk25`. **Neither of these is a repo change** — nothing in the tree was modified
  to make the build run.

## What I could not verify, because I cannot run the app

Stated plainly, because several of these are the heart of the three reports:

- **I never saw the UI.** No screenshot, no click, no rendered dialog. Every frontend claim below
  rests on reading the code and on unit tests of the pure functions behind it. The confirmation
  dialog's *markup* is covered only by `tsc`; its *logic* (which jobs are named, what the text says)
  is covered by `elapsed-jobs.test.ts`.
- **I never reached Postgres or a running Spring Boot process.** Backend behaviour is verified
  through the two suites, including `@SpringBootTest` integration tests against the `test` profile.
- **Task 1's leading hypothesis is unconfirmed and unconfirmable here.** Whether the operator's
  process predates `/revoke` cannot be determined from the sandbox. I made the system behave sanely
  either way rather than chasing it — see below.
- **I did not observe the pre-existing failing test pass.** I observed it fail on the untouched base
  branch, which establishes it is not mine. My explanation of *why* it fails is a diagnosis from
  reading the code plus arithmetic, not an observation of it passing at another hour (the fixture
  derives from the system clock, and no clock-faking tool is available here).
- **No load, concurrency or performance claim.** The 409 lock-contention mapping is tested by
  stubbing the exception, not by racing real transactions.

---

# Task 1 — Revoke throws, and the exception reaches the global handler

## The two defects named in the brief: both real, both fixed

**1. `ScheduleDecision.action` missing `"REVOKE"`** (`frontend/src/lib/api.ts`). Confirmed. The
decide() response for a revoke carries `action: "REVOKE"`, which did not fit the declared
`"APPROVE" | "REJECT" | "REVISE"`. Extracted as `RecordedDecisionAction` and widened; the new
`schedule-review-api.test.ts` case asserts `response.data?.action === "REVOKE"`, which only compiles
because the type admits it.

**2. `AuditEventController.recordEvent` routing `REVOKE` to `default`.** Confirmed, and it is the
most serious thing in this branch. `default -> auditEventService.recordEvent(request)` writes an
audit event with **no ownership check and no state check**, so:

- any authenticated caller could append a `REVOKE` event naming a run they do not own, and
- a run's own owner could "revoke" a plan that was never approved.

`REVOKE` now joins `APPROVE, REJECT, REVISE` in the `switch`, so it goes through
`ScheduleReviewService.decide` and inherits the operator-row lock, the plan write lock, the
ownership lookup and the state machine. Four integration tests in
`ScheduleEnforcementIntegrationTest` pin it: unowned → 404 with no event written and the approval
still standing; pending → 409 `ALREADY_DECIDED` with no event; owner's own → 201, plan `REJECTED`,
and `apply` then refused `NOT_APPROVED`. Three `@WebMvcTest` cases additionally assert
`verify(auditEventService, never()).recordEvent(any())` on both the success and refusal paths — the
bypass itself, not just its symptoms.

## A third defect I found: `ScheduleGate.DECISIONS` omitted `REVOKE`

```java
private static final Set<AuditActionType> DECISIONS =
        EnumSet.of(APPROVE, REJECT, REVISE);   // REVOKE missing
```

This set is what `requireMatchingApproval` and `requireCurrent` use to ask the audit log for a run's
*latest decision*. With `REVOKE` absent, a revoked run's latest decision still read as the `APPROVE`
it withdrew.

**Nothing reachable today depends on it** — a revoked plan is `REJECTED`, so `requireApproved`
refuses it before the audit log is consulted, and a revoked run cannot be re-approved. I added
`REVOKE` anyway: an execution gate that would read a withdrawn approval as a run's current decision
is the wrong default to leave for whichever caller asks next. Flagged as *latent*, not as a live bug.

## The exception audit

I read `GlobalExceptionHandler` and traced every exception that can escape
`ScheduleReviewService.decide`. The catch-all does return a proper `ApiResponse` envelope, so the
real problem is narrower and worse than "no envelope": **failures that should be 4xx were arriving
as 500**, and a 500 tells the client nothing it can act on. It cannot distinguish a transient race
(retry) from an endpoint the server does not have (reload/redeploy) from a plan that moved
underneath the decision (refresh) — so it cannot tell the operator what to do, and the button
appears to do nothing.

Already correctly mapped (verified, unchanged): `ResourceNotFoundException` → 404,
`ScheduleExportRejectedException` → its reason's status + `data.reason`, `BadCredentialsException` →
401, `IllegalArgumentException` → 400, `AccessDeniedException` → 403,
`MethodArgumentNotValidException` → 400.

Added:

| Exception | Was | Now | Why it is reachable from a revoke |
|---|---|---|---|
| `ConcurrencyFailureException` (incl. `CannotAcquireLock…`, `PessimisticLockingFailure…`, `OptimisticLockingFailure…`) | 500 | **409** | Every decision takes `PESSIMISTIC_WRITE` on the plan row via `findOwnedForUpdate`, after locking the operator row. Two operator actions racing on one run, or a decision racing apply/export, lands here. 409 is also what `useScheduleReview` already turns into the "refresh plans" banner. |
| `DataIntegrityViolationException` | 500 | **409** | A constraint the database refuses is a conflict with stored data, not a server fault. |
| `jakarta.persistence.EntityNotFoundException` | 500 | **404** | A plan reads its owner through a `LAZY` `@ManyToOne`; if that row is gone when the proxy resolves, the resource genuinely no longer exists. |
| `NoResourceFoundException` | container error page | **404 envelope** naming the path | This is the version-skew shape: a browser newer than its deployment. Spring's own error body is not an `ApiResponse`, so a client reading `message` off every response had nothing to show. |
| `HttpRequestMethodNotSupportedException` | container error page | **405 envelope** | Same reasoning. |

Driver and SQL text is logged, never returned — `DataIntegrityViolationException`'s message carries
column names and types.

I deliberately did **not** add a handler for `IllegalStateException`. Three existing tests
(`ScheduleDecisionIntegrationTest`'s rollback tests) assert 500 for an injected
`IllegalStateException`, and they are right to: an unexpected internal failure *should* be a 500.
Mapping it would have broken them for no gain.

## Frontend hardening

`fetchJson` already survived an HTML body (`response.json().catch(() => ({}))`), but it trusted the
*shape* of whatever parsed. Fixed in `api.ts`:

- `apiErrorMessage(status, body)` takes `message` only when it really is a non-blank **string**. A
  gateway returning `{"message": {...}}` previously became the string `"[object Object]"`.
- `apiErrorReason(body)` takes `data.reason` only when it is a string.
- 404/405 get wording that names the likely cause: *"This action is not available on the server
  (HTTP 404). The server may be running an older version than this page…"* — which is the actionable
  version of the operator's "does nothing".
- A transport failure (server down, DNS, CORS preflight refused) was rejecting with a bare
  `TypeError("Failed to fetch")` that reached the UI as the explanation for a failed decision. It is
  now an `ApiRequestError` with `status: 0` and a readable message, so every caller has one error
  type to handle.

`RejectPlanModal` already renders `error`, so these messages reach the operator without a UI change.

## On the leading hypothesis

I could not confirm it. What I did instead: made both sides behave sanely whichever is true. If the
server is old, the client now says so specifically and names the path; if the server is current, the
governance bypass and the mis-statused failures are fixed regardless. **I did not add any version
negotiation or capability endpoint** — that is a larger design decision and nothing in the brief
asked for it.

---

# Task 2 — "Add to calendar" relights after the schedule was applied

The diagnosis in the brief is exactly right, and I confirmed each part:
`OptimizationPlan.appliedAt` exists with a Lombok getter; `grep -rn appliedAt
backend/src/main/java/com/gacs/backend/dto/` returns nothing; `calendarUnavailable()` checked only
`reviewState === "APPROVED"`.

The other half of the cause is in `use-schedule-review.ts`: "already added" was tracked in
`addedRuns`, a `React.useState` set, which does not survive a remount. So the client's only record
of the fact was lost on navigation and the server never told it otherwise.

Fixed end to end:

- `ScheduleApplicationService.PlanDetail` gains `appliedAt` (`Instant`), from `plan.getAppliedAt()`.
- `SchedulePlanDetail` in `api.ts` gains `appliedAt: string | null`.
- `calendarUnavailable()` returns `"This schedule is already on the workload calendar."` once it is
  set.
- The hook reads server truth first: `addedToCalendar: !!plan?.appliedAt || addedRuns.has(selected)`,
  with `addedRuns` kept only as the optimistic half between a successful apply and the next detail
  load. `addToCalendar` also mirrors `appliedAt` locally on success so the panel reads as done
  without a refetch.

The button then renders **"Added to calendar"** and is disabled — done rather than available,
matching how the decision buttons already render a terminal state as "Schedule approved".

**Two ordering decisions, both tested.** The applied check sits *after* `planUnavailable()` (a stale
or unreadable plan needs regenerating, which matters more than whether it once reached the calendar)
and *before* the "no scheduled jobs" case (an applied plan is finished either way, and "already
added" is the more informative answer).

Tests: three new `calendarUnavailable` cases in `schedule-review-utils.test.ts` covering the new
branch and both orderings; two backend integration tests asserting the DTO reports `appliedAt`
absent before `apply` and present after, and that it survives re-application (apply is idempotent,
and the UI now depends on the stamp staying set).

Fixture updates: `appliedAt: null` added to two `SchedulePlanDetail` literals. Mechanical, forced by
the new required field. **No assertion changed.**

---

# Task 3 — Elapsed jobs discarded instead of rescheduled

The diagnosis in the brief is correct. `JobWindow.startSlots()` caps the loop at
`min(job.latestStart(), lastSlot)`, `latestStart()` is `originalStartTime +
flexibilityWindowHours`, and `firstSlot()` clamps forward to `earliestStart`. For an elapsed job
`latest < firstSlot()`, the loop body never runs, and the job is `NO_FEASIBLE_SLOT`.

## Where I put the change, and why not where it looks like it goes

The obvious move is a `rescheduleElapsedJobs` boolean on `OptimizationProblem`, threaded into
`JobWindow`. I did not do that, because `GreedyAllocator.cheapestPlan`,
`cheapestPlanEndingAt` and `SynchronizedGroupPlacer` each build `new JobWindow(job, prices,
earliestStart)` from a `LocalDateTime` parameter rather than from the problem. Threading a flag
through all of them is five chances to miss one, and a solver that disagreed with the plan checker
about a job's window is precisely the class of bug `JobWindow`'s single `usable()` predicate exists
to prevent.

Instead the re-anchoring is **one field on `OptimizerJob`**:

```java
public LocalDateTime latestStart() {
    if (exempt) return originalStartTime;
    return rescheduledFromElapsedStart ? deadline : originalStartTime.plusHours(flexibilityWindowHours);
}
```

`OptimizationProblemFactory.reanchorElapsed` sets the field, per job, only where
`originalStartTime` is actually before the current hour. Because every window everywhere reads
`job.latestStart()` through `JobWindow`, the greedy solver, the exact solver, the synchronized-group
placer, the brute-force checker and the plan checker all agree with no further plumbing. Returning
the *deadline* rather than a computed slot keeps the "must end by the deadline" rule in the one
place that owns it — `JobWindow`'s existing clamp to `lastSlot()`.

### Two things this deliberately does not touch

- **`flexibilityWindowHours` is not rewritten.** Rewriting it would have been the shortest
  implementation and would have been a bug: `OptimizationProblem.jobEntry` puts it in the run
  snapshot, and `ScheduleGate.requireInputsUnchanged` rebuilds that line from the stored job, so the
  two would disagree and every such plan would invalidate.
- **The flag is absent from `snapshot()`**, for the same reason `earliestStart` is: a re-run must not
  hash differently and invalidate a pending approval. Tested
  (`theFlagIsNotPartOfTheSnapshot`).

## The part that would have made the feature useless

`ScheduleGate.requireJobsUnchanged` re-derives `job.latestAllowedStart()` from the `jobs` row on
**every** approve, apply and export:

```java
if (item.scheduledStart().isBefore(earliest) || item.scheduledStart().isAfter(latest))
    throw invalidated(runId, "job " + id + " must now start between ...");
```

A re-anchored job is, by design, placed after `latest`. So the optimiser would have returned a
perfectly good plan and the gate would have refused it the instant the operator tried to approve
it — citing a flexibility window they had explicitly chosen to set aside. The feature would have
looked like it worked and been unusable.

The gate now skips **only the upper-bound half** for an item carrying
`rescheduledFromElapsedStart`. The lower bound (`isBefore(earliest)`) and the deadline check below
it are untouched, so the two rules that matter still hold: the job does not start before its own
original start, and it still finishes by its deadline.
`ElapsedJobRescheduleIntegrationTest.withTheOptInTheJobIsPlacedApprovedAndApplied` asserts
**approve and apply both succeed** — not merely that a plan comes back.

## Scope limits, all tested

- **No slot may begin before the current hour, flag or not.** Re-anchoring moves only the upper
  bound; the lower bound stays in `JobWindow.usable()`. Asserted at the window level
  (`reanchoringNeverAllowsAnElapsedHour`, which checks `isUsable` and `runFrom` for every elapsed
  hour) and on a real HTTP response (`scheduledStart >= now`).
- **Exempt jobs are never re-anchored.** An exempt job's whole meaning is that it runs at its
  original time. `reanchoredToDeadline()` returns `this` for one, and the record's constructor
  *rejects* the `exempt && rescheduledFromElapsedStart` combination outright rather than trusting
  callers.
- **A job whose deadline has also passed stays infeasible**, with or without the flag: there is no
  window left to widen.

## Savings for rescheduled jobs — the decision you asked me to state

**I left the arithmetic alone and labelled the result instead.** `baselineCostUsd` for a rescheduled
job is still its baseline from `originalStartTime`, which means it is priced over hours that have
already elapsed, and `savingsUsd` is still `baseline - optimized`.

I considered re-basing the baseline to start at the current hour, which would make the saving a
"what you get versus doing it now" figure. I did not, for three reasons:

1. A baseline is defined in the contract as *what would have happened without optimising*. For a job
   whose start has passed, what would have happened is that it ran (or should have run) at its
   original time. Re-basing would answer a different question under the same field name.
2. `BaselineCalculator` is also what admits a job at all (`build()` rejects a job that cannot finish
   alone between its start and deadline). Re-basing the baseline would have changed admission as a
   side effect, silently rejecting jobs whose remaining window is short.
3. Every other job in the same run would still be measured the old way, so the run totals would mix
   two definitions.

So the numbers stay comparable within a run and **the caveat is made explicit** instead: the
response marks the job (`rescheduledFromElapsedStart`), `plainEnglishExplanation` says *"their
savings are measured against a baseline that itself already partly elapsed"*, the per-job UI panel
repeats it, and the contract documents it. **This is the item most worth your review** — if you
would rather see a now-based figure, it should probably be an additional field rather than a
redefinition of this one.

## A third infeasibility reason, which turned out to be needed

The brief said to add one "if it helps". It does, and not only for the opt-in path. Admission only
ever proves a job fits alone *from its original start*. Once that start has passed, fewer hours
remain, so a job can have start slots available and still be unplaceable **with the cluster
completely idle** — and the existing code would have reported that as `Cluster full: … other jobs
use the capacity this job needs`, sending the operator to free capacity that is not the problem.

Added, checked before the capacity message: `Not enough time left: only the hours from X to Y UTC
remain before this job's deadline, which is less than it needs to finish even alone on the cluster.`
It is computed with a new `BaselineCalculator.runFrom(job, from)` — `baseline(job)` now delegates to
it — rather than a second copy of the fill rule. This case is reachable **with the flag off** too
(a long flexibility window can still reach the current hour), so it is not gated on the flag.

I also extended the existing `Window elapsed` reason to name the opt-in as a remedy while the
deadline is still ahead. The existing assertion on that message
(`PastSlotExclusionTest:162`, `.contains("Window elapsed").contains("every hour this job could start
in")`) still passes — I appended, I did not rewrite.

## Frontend: comparing instants, not wall-clock strings

The brief says to compare `originalStartTime` against `currentMarketHourInput()`. I implemented that
comparison **as instants**, and this is a deliberate refinement worth flagging:
`job.originalStartTime` is a timezone-free string that means **UTC** (see `toInputDate`'s comment),
while `currentMarketHourInput()` returns **market wall time**. Comparing the two directly would be
wrong by the UTC offset — six hours in November — and ambiguous for the repeated hour when Central
Time falls back.

So `date-utils.ts` gains `currentMarketHourStart()`, built *from* `currentMarketHourInput()` and
`marketWallTimeToUtc()`, returning the instant that hour began. `date-utils` stays the only module
converting between UTC and market time, and **no hour offset is hardcoded anywhere** — tested in
both winter (CST, −6) and summer (CDT, −5).

`elapsed-jobs.ts` holds the detection and the dialog's wording as pure functions, so both are
covered by tests rather than living in JSX. Exempt jobs are excluded from the dialog: the opt-in
cannot move them, so naming them would promise something that will not happen.

The dialog appears **only** when at least one job has elapsed — `runOptimization` checks first and
calls `optimize(false)` directly otherwise, so the common path keeps exactly one click. It names each
job by **name and id** (`Nightly ETL (#118)`) with its original start and deadline in market time,
states that only hours from the current hour to each deadline will be used, and offers three
choices: Cancel, **Leave them out** (today's behaviour — an operator who would rather fix the start
times should not have to accept a widened window to see the rest of the plan), and **Reschedule and
optimize**, which is the only path that sends the flag.

---

# Tests I changed, under exception 2

Two assertions in `ApprovedScheduleJsonSerializerTest`. Both are **shape** assertions on the JSON
export document, and both were forced by a rule the repo's own tests impose, not by my code failing.

`document_JobFieldsComplete` asserts the export document mirrors **every** per-job field of
`ScheduleItem`:

```java
Set<String> itemFields = componentNames(ScheduleItem.class);
itemFields.remove("allocations");
assertThat(jobFields).containsAll(itemFields).contains("slots");
```

Adding `rescheduledFromElapsedStart` to `ScheduleItem` therefore *requires* adding it to
`ApprovedScheduleDocument.Job`. That test needed no edit — it is derived — but the document gaining a
field moved two sibling assertions:

**1. `serialize_AllKeysPresent`, per-job key count.**

```java
// old
assertThat(fieldNames(unscheduled)).hasSize(13);
// new
assertThat(fieldNames(unscheduled)).hasSize(14);
```

Why the old one is now wrong: it counts the keys of a job object in the export. The object has 14
keys because `document_JobFieldsComplete` requires it to mirror `ScheduleItem`, which now has 14
per-job fields. `13` asserted a shape the repo's own parity rule no longer permits. The
assertion's *intent* — "every key is present, with null where a value is absent" — is unchanged, and
the other assertions in that test (nulls present, `slots` an empty array) are untouched.

**2. `serialize_ExactOutput`, the golden JSON string.** `"rescheduledFromElapsedStart":false` was
inserted after `"savingsUsd"` in each of the three job objects, e.g.:

```java
// old
+ "\"baselineCostUsd\":null,\"optimizedCostUsd\":null,\"savingsUsd\":null,\"slots\":[]},"
// new
+ "\"baselineCostUsd\":null,\"optimizedCostUsd\":null,\"savingsUsd\":null,"
+ "\"rescheduledFromElapsedStart\":false,\"slots\":[]},"
```

Why the old one is now wrong: it is a byte-exact fixture of the document's serialization, and the
document deliberately gained a documented field. The test's purpose — that the same schedule always
produces byte-identical output — is unchanged and still enforced; only the expected bytes moved. The
`false` values confirm the field defaults correctly for jobs that were not rescheduled.

**No other existing assertion was modified anywhere in this branch.** Everything else was either an
addition or a mechanical fixture update for a new required field (`appliedAt: null` in two TS
literals, `rescheduledFromElapsedStart: false` in three).

### Why the CSV export was left alone

`ApprovedScheduleCsvSerializer` has no parity rule and its test passed untouched. Its column list is
a **published format for execution adapters**, and `rescheduledFromElapsedStart` is advisory
provenance, not data an adapter needs in order to dispatch. Adding a column would change a documented
contract for no operational gain. Noted in the contract.

---

# The pre-existing backend failure

`ScheduleEnforcementIntegrationTest.expiryPersistsStaleWithoutAnExportRequest` — `expected: STALE but
was: INVALIDATED`.

**It fails on the untouched base branch.** I ran the full suite before making any change: 719 tests,
this one failure. It is the same single failure after my changes. I did not modify it.

**Diagnosis** (reading, not observation — see the caveat at the top). The test fakes a plan whose
slots have ended by rewriting one date string in the stored result JSON:

```java
start = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS);   // @BeforeEach
// deadline = start.plusHours(3)
String past = stored.replace(start.toLocalDate().toString(), start.minusDays(4).toLocalDate().toString());
```

It replaces only `start`'s date. When `start`'s hour is 21:00 UTC or later, `start + 3h` falls on the
*next* calendar date, so the stored JSON contains two different dates and only the first is rewritten
— leaving a plan whose start is in the past and whose end is still days ahead. The gate then detects
inconsistency and marks it `INVALIDATED` rather than reaching the all-slots-ended check that yields
`STALE`.

The suite ran at 22:26–22:57 UTC, so `start` was `2026-11-06T22:00` and the deadline
`2026-11-07T01:00` — different dates. The test should pass at any UTC hour from 00 to 20.

**I left it failing and did not touch it**, per the brief. It is a genuine latent flake in a test
fixture, not a product bug, and fixing it is outside these three tasks. If you want it fixed: the
robust change is to rewrite the whole stored window rather than one date string — for instance by
shifting every timestamp in the JSON, or by moving the clock with the existing
`support/MutableClock`, which exists for exactly this ("the only way to reach that state is the way
it happens in production: make a real plan, then let time pass"). **Your call, not mine.**

---

# Left undone, deliberately

- **No version negotiation for Task 1's hypothesis.** The client now reports a missing endpoint
  clearly; it does not probe server capabilities. That is a design decision for you.
- **An exempt job whose start has elapsed is still reported `UNCHANGED_EXEMPT` at a past time.**
  `AllocationResult.of` gives every exempt job its baseline plan unconditionally. That is
  pre-existing behaviour, documented in `PerJobStatus` ("Kept at originalStartTime, running as in its
  baseline, because isExempt is true"), and arguably wrong once that time has passed — an operator
  may be shown a schedule entry for an hour that is gone. **Out of scope here and unchanged**, but
  worth a look. My integration test asserts only that the opt-in does not move it.
- **Revoking an approval does not revive plans it superseded.** `gate.supersede` marks overlapping
  approved plans `STALE` on approval, and revoking the superseding run leaves them stale. Existing,
  intentional behaviour (`supersessionUsesApprovalOrderAndCannotBeRevived` asserts it); I only
  noticed it while tracing the revoke path.
- **No CSV export column** for the new marker — reasoned above.
- **No migration.** Nothing in these three tasks changes the schema: `appliedAt` already existed as a
  column, `REVOKE` was already a `@Enumerated(STRING)` value in a `length = 50` varchar, and the
  optimiser flag is request-scoped and never persisted.
