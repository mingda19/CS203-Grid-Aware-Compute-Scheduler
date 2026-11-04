# Usability batch 2–6 — report

Branch `fix/usability-batch-2-to-6`, off `chore/retouch-frontend`. Five bugs, one commit (or
two, where the backend and frontend halves are separable) per bug. This file covers what
changed, what was deliberately left undone, every existing test left failing on purpose (and
why), and everything that needs Mingda's call rather than mine.

I could not run the app, reach a database, or see the UI in this environment (no `.env`, no
Supabase access, no JDK 25 — see **Environment note** at the end). Everything below is backed by
reading the code and running the test suites; nothing here is a claim of visual verification.

## Bug 2 — unidentifiable jobs in error messages

**Changed:**
- `backend/.../service/allocation/OptimizerJob.java`: added `identity()` — `"id 23 ('gpt-agent-1')"`
  — used only at the two `OptimizationProblemFactory` call sites that build operator-facing
  messages (`"Job ... cannot finish between ..."` and `"No forecast price for ... which job ...
  uses"`). `label()` itself (`"id 23"`) is untouched: its exact wording is pinned by
  `PriorityConflictTest` and documented verbatim in `docs/api-contract.md` for a *different*
  message (`AllocationResult`'s priority-conflict explanation), which is out of scope for this bug.
- Frontend: the jobs table, the optimizer's "Job Placement Details" table, the "why this
  recommendation" explanation, and the delete-confirmation dialog all now show a job's numeric
  `#id` next to its name.
- New tests: `OptimizerJobTest` (new), plus extended assertions in `OptimizationProblemFactoryTest`
  checking the job's name now appears alongside "job id N".

**Left undone / assumption:** duplicate job names are still allowed. I treated "should names be
unique?" as a product decision I shouldn't make unilaterally — enforcing uniqueness now could
reject existing, legitimately-duplicated data (e.g. recurring jobs with the same name), and the
bug report's actual complaint ("I can't tell jobs apart") is fully addressed by showing the id
everywhere a job appears, without forcing names to be unique. **Needs Mingda's call** if unique
names are also wanted.

## Bug 3 — approve and reject both succeed on one schedule

**Changed:** this is the one bug in this batch with a real design trade-off; read this section
before the others.

- `ScheduleReviewService.decide()` now refuses *any* further decision on a run once it holds a
  terminal one (`APPROVED`, `REJECTED`, or `REVISION_REQUESTED`) — including repeating the same
  action — returning `409` with `data.reason = "ALREADY_DECIDED"` (new
  `ScheduleExportRejectedException.Reason` value, reusing its existing HTTP-mapping plumbing).
- Frontend: `decisionUnavailable()`/`planUnavailable()` in `schedule-review-utils.ts` mirror the
  same terminal-state set, so Approve/Reject/Revise are each disabled once the plan already holds
  a decision, and each button's label reflects what was decided ("Schedule approved", "Schedule
  rejected") instead of leaving all three clickable regardless of state.
- `docs/api-contract.md` §6.5 updated with a new subsection ("One terminal decision per run") and
  a changelog row; the old line claiming "repeated requests ... create additional history
  entries" is superseded rather than deleted, so the history of what changed and when stays
  readable.
- New test: `ScheduleDecisionStateMachineTest` (6 cases: reject/revise/re-approve after approve,
  approve after reject, approve after revise, and a fresh PENDING run still working normally).

**Why this is the batch's biggest trade-off.** The literal bug report says "allowing rejection of
an already-approved schedule is a heuristic violation" and asks for one terminal decision per run
— I implemented exactly that. But investigating it, I found this backend **intentionally**
supported reversing a decision (reject-after-approve, revise-after-approve, etc.) as a real
feature: the only way, in the existing design, to retroactively halt a schedule after it was
approved and dispatched (`WorkloadSchedule` rows already created/started) is to reject or revise
the same run after the fact, which `ScheduleGate`/`ScheduleApplicationService`'s execution-gating
logic then uses to block further `Running` transitions. This is documented
("Repeated requests, including retries, create additional history entries.") and exercised by
seven existing test methods across three files, which this change leaves failing (per the task's
hard rule: I did not touch their assertions). Running `./mvnw test`, these are the only
failures/errors in the whole suite:

- `ScheduleDecisionIntegrationTest.allActionsAppendHistoryAndUseAuthenticatedDatabaseIdentity` —
  drives APPROVE→REJECT→REVISE→APPROVE on one run and asserts all four succeed.
- `ScheduleDecisionIntegrationTest.concurrentDecisionsSerializeAndLatestHistoryMatchesState` —
  fires APPROVE and REJECT concurrently on one run and asserts both succeed.
- `ScheduleEnforcementIntegrationTest.applicationIsIdempotentAndRejectionBlocksStarts` — approves,
  applies, starts a workload Running, then **rejects the already-approved run to halt it**
  (`decide(run,"reject")`) and checks that a later `Running` transition is then refused.
- `ScheduleEnforcementIntegrationTest.revisionRequestedRunCannotBeAppliedOrStarted` — approves,
  applies, then **revises the already-approved run** to invalidate the application.
- `ScheduleEnforcementIntegrationTest.supersessionUsesApprovalOrderAndCannotBeRevived` — approves
  two overlapping runs in sequence, then **rejects the one left APPROVED** to test it can't be
  revived.
- `ScheduleExportIntegrationTest.unapprovedPlans_Forbidden` — approve→reject→revise→approve on one
  run, checking export is forbidden at each non-approved step in between.
- `ScheduleExportIntegrationTest.newerApprovalOfSameJob_Stale` — approves two overlapping runs,
  then **rejects the newer (currently-approved) one**, checking the older one's STALE reason
  string.

**This needs Mingda's decision**, specifically: is "retract an approval via reject/revise" a
capability the product still wants? If yes, the fix here is too strict and should be narrowed —
e.g. a separate, explicitly-named "revoke" action distinct from the first-time decision, rather
than overloading `reject`/`revise` to mean two different things depending on the run's current
state (which is arguably part of why this bug exists in the first place: the UI offered "Reject"
as if it were always a first decision, when on an approved run it actually revokes). If no, these
seven tests should be rewritten (not by me, per the hard rule) to match the new contract.

## Bug 4 — double approval and duplicated workload-schedule rows

**Changed:** `WorkloadScheduleRepository` gained
`deleteByOwnerEmailAndJobIdInAndRunIdNot(ownerEmail, jobIds, runId)`. `ScheduleApplicationService.apply()`
calls it before inserting new rows, deleting any other run's calendar entries for the jobs it's
about to (re)write. This is safe because `ScheduleGate.supersede()` already guarantees at most one
`APPROVED` plan per job at a time — any other run sharing a job id is necessarily
stale/rejected/invalidated, never a second valid approval.

**Root cause, for the record:** `ScheduleGate.supersede()` only ever marked the *older*
`OptimizationPlan` `STALE`; it never touched `WorkloadSchedule` rows a previous `apply()` call had
already written. Re-optimizing and re-approving the same job three times left three rows.

**New test:** `ScheduleEnforcementIntegrationTest.reapprovingAndApplyingSameJobSupersedesRatherThanAccumulates`
(optimize→approve→apply twice for an overlapping job; asserts exactly one row survives, pointing
at the newer run). Passes; no existing test conflicts.

## Bug 5 — calendar view (overlap + date-line off-by-one)

**Changed**, both in `frontend/src/components/schedule-calendar/GoogleCalendarView.tsx`:

- **Date-line bug.** `weekDays`/`monthCells`/`miniCalendarCells` built their grids with
  local-timezone `Date` math (`getDay`/`setDate`/`getMonth`/`getFullYear`), while event-matching
  and the `isToday`/`isSelected` flags used `getMarketDateString` (market-timezone-aware) on the
  very same `Date`. Whenever the browser/server's local timezone isn't America/Chicago, those two
  calendars can disagree on what day an instant falls on — which is exactly "the line pointed at
  Nov 5 when the market date was Nov 4." `currentDate` is now a label anchor permanently pinned to
  noon UTC on the market-local date being viewed (`marketDateLabelAnchor`), and every grid
  builder/nav handler/title formatter uses new pure market-date-string arithmetic
  (`addMarketDays`, `addMarketMonths`, `marketDayOfWeek`, `marketDaysInMonth`,
  `marketDateStringFromParts` — all added to `date-utils.ts`, which stays the only module naming a
  timezone) instead of local `Date` getters/setters. `currentHourDecimal` (the line's vertical
  position) was already correct and is untouched.
- **Overlap.** Week/day views rendered every event in a slot as a full-width absolutely-positioned
  box at the same z-index, so overlapping jobs fully occluded each other. New `layoutEvents()`
  assigns each event a lane via greedy interval-graph coloring within its own connected-overlap
  cluster (so an unrelated event elsewhere in the same day still gets full width, rather than
  every event in the day sharing the busiest moment's lane count); the week/day views render each
  job as a narrower box side by side with the others in its slot, each independently clickable.
  Month view already lists same-day jobs as a stacked, individually-clickable list (capped at 3
  with "+N more") — that already satisfies "a way to reach each of them," so it's untouched.

**New tests:** `date-utils.test.ts` gained 6 cases for the new helpers (including one asserting
the anchor round-trips through `getMarketDateString`, the exact invariant the fix depends on).
New `GoogleCalendarView.test.ts` (5 cases) covers `layoutEvents`, including the "transitively
overlapping chain reuses a freed lane instead of needing one lane per event" case.

**Could not verify:** I could not load the calendar in a browser, so I could not screenshot the
fix. The logic is covered by the tests above and by reasoning through the DST/offset math, not by
looking at it.

## Bug 6 — workload category on jobs

**Changed, end to end:**
- Backend: new `WorkloadCategory` enum (`ML_TRAINING`, `CRYPTO_MINING`, `HPC_BATCH`,
  `HVAC_PRE_COOL`, `GRID_ALERT` — matching `GoogleCalendarView.tsx`'s `WorkloadType`/`TYPE_CONFIG`
  exactly, same pattern as `JobPriority`), a `workload_category` column on `jobs` (migration
  `V7__add_job_workload_category.sql`, following the V3–V6 convention: `ddl-auto=update` creates
  it automatically, this file is the documented, idempotent, hand-applied equivalent plus the
  `CHECK` constraint `ddl-auto` can't express), wired through `JobRequestDto`/`JobResponseDto`/
  `JobService` validation.
- `ScheduleApplicationService.apply()` now writes the job's own `workloadCategory` into the
  dispatched `WorkloadSchedule.type`, replacing the old heuristic ("any GPU hours at all implies
  ML Training, else HPC Batch") that the job never actually chose.
- Frontend: a "Workload category" dropdown on the job create/edit form (reusing the exact label
  set the calendar already defines), shown in the jobs table and job detail view.
- New tests: `JobServiceTest` (+114 lines covering create/update/validation), `ScheduleEnforcementIntegrationTest.appliedScheduleUsesJobsWorkloadCategoryNotGpuHeuristic`.

**Flagged assumption, not a blocker:** `GRID_ALERT` ("Grid Peak Alert" in the calendar's display
name) reads like a system/grid-event pseudo-category, not something an operator would ever
describe their *own* job as — but the task said to mirror "whatever other categories the calendar
view already defines," so it's included in the job-creation dropdown as given. **Needs Mingda's
call** on whether to exclude it from job creation (it would stay a legitimate value for
system-dispatched calendar entries either way).

## Test suite results

**Backend** (`cd backend && ./mvnw test`): this environment has only JDK 21 installed, but the
project targets Java 25 (`pom.xml`'s `java.version`) — `./mvnw test` fails immediately with
"release version 25 not supported" regardless of any change in this PR; I could not install a
JDK. I verified everything here with `-Dmaven.compiler.release=21` as a local-only override (never
committed). With that override: **710 run, 687 passing, 6 failures, 1 error, 16 skipped** — the
baseline was 694 passing / 0 failures / 16 skipped; the 7 broken tests are exactly the ones listed
under Bug 3 above, and the roughly +16 net new tests are this batch's new coverage (2 +6 +1 +5 are
frontend/backend-split; backend alone: `OptimizerJobTest` +2, `ScheduleDecisionStateMachineTest`
+6, `ScheduleEnforcementIntegrationTest` +2, `JobServiceTest` additions). **Please re-run on a
machine with JDK 25 to get a real baseline-vs-now comparison** — I'm confident the 7 failures are
real and caused by the Bug 3 change (I confirmed each one individually), but I cannot rule out the
JDK 21 override masking or introducing something unrelated.

**Frontend** (`cd frontend && npm install && npx tsc --noEmit && npx vitest run`): clean.
`tsc --noEmit` reports no errors. `vitest run`: **150 passing, 0 failing** (21 test files) — up
from the stated baseline of 135, consistent with this batch's new test files
(`GoogleCalendarView.test.ts`, additions to `date-utils.test.ts` and `schedule-review-utils.test.ts`).

## Environment note

No `.env`, no Supabase/database access, and no browser available in this session — every claim
above is from reading code and running test suites, never from using the running app. Flagged
separately because the task instructions asked for explicit honesty on this point: I did not
visually verify the calendar fix, the job form's new dropdown, or any other UI change.
