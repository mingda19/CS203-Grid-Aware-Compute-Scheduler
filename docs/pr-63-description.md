## Summary

Adds authenticated owner-only endpoints to approve, reject, or request revision of persisted optimization plans (GRID-11 AC2 / AC2.2). Approval records intent; it does not dispatch workloads. Revision records a request without changing or rerunning the stored plan.

Successful optimization requests now persist an `OptimizerRun` and an `OptimizationPlan` in one transaction. The plan shares the run's `VARCHAR(36)` primary key through a foreign key. Solver name, `AllocationSolver.VERSION`, bound optimizer configuration, outcome, and optimality remain separate from mutable review state. Owner-scoped explanation persistence and retrieval are preserved.

Decisions use the approved `AuditEventService` and `audit_events` as their only history. The duplicate `ScheduleDecision` entity, repository, and action enum are removed. Each event records the security-context actor, action, trimmed rationale, and the lowercase SHA-256 digest of the exact stored result JSON encoded as UTF-8. The audit event and review-state update commit atomically under a per-plan write lock. Retries append another event; `decisionId` in the response is the audit event ID.

## API behavior

- `POST /api/schedules/{runId}/approve`, `/reject`, and `/revise` retain their response shape and require a nonblank string `comment` of at most 2,000 characters before trimming, matching the approved audit rationale policy.
- Invalid request bodies are rejected before plan lookup or locking. Client identity fields are ignored; operator IDs are resolved from the authenticated email's database user.
- `/api/schedules/optimize` now requires an existing database user to own the plan. A valid JWT whose email has no matching user receives `401`; missing or invalid tokens retain the existing `403` behavior.
- Missing and unowned plans both return `404`, including for administrators.

## Storage considerations

Every successful optimization stores the complete result in an unbounded `text` column under the existing `ddl-auto=update` setup. Review growth and retention before large-scale use. These plan tables are treated as unreleased; no existing database rows are dropped, converted, or backfilled automatically. Failed-request and timeout-run persistence beyond successful run/plan pairs remains separate work.

## Validation

Integration coverage includes linked run metadata and database foreign-key enforcement; persistence rollback; authenticated audit identity and schedule hashes; all three actions and repeated decisions; audit rollback; concurrent decisions; ownership and malformed inputs; comment boundaries; and preservation of owner-scoped explanations. Verified on 2026-10-31:

- Focused decision, optimization, audit, and repository tests: **50 passed**, zero failures or errors.
- Full backend suite: **240 passed**, zero failures, errors, or skipped tests.
- `git diff --check`: clean.

Run from `backend/` with `./mvnw.cmd -o test`. Local verification supplied the installed Mockito core JAR as a startup javaagent via `-DargLine=-javaagent:<mockito-core.jar>` because the sandbox blocks Mockito's dynamic JVM attachment. No build or production configuration was changed for this workaround.
