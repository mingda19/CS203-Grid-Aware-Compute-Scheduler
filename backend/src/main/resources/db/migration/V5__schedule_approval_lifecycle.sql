-- AC2.3. Apply explicitly before deploying; no migration runner is configured.
-- Additive and idempotent. Old results and audit events are retained.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS scheduling_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE facilities ADD COLUMN IF NOT EXISTS scheduling_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE optimization_plans ADD COLUMN IF NOT EXISTS input_snapshot text;
ALTER TABLE optimization_plans ADD COLUMN IF NOT EXISTS invalidation_reason text;
ALTER TABLE optimization_plans ADD COLUMN IF NOT EXISTS invalidated_at timestamptz;
ALTER TABLE optimization_plans ADD COLUMN IF NOT EXISTS applied_at timestamptz;
ALTER TABLE workload_schedules ADD COLUMN IF NOT EXISTS run_id varchar(36);
ALTER TABLE workload_schedules ADD COLUMN IF NOT EXISTS job_id bigint;
CREATE UNIQUE INDEX IF NOT EXISTS uq_workload_run_job ON workload_schedules (run_id, job_id);

-- Hibernate-generated check names differ by installation. Replace only review-state checks.
DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'optimization_plans'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%review_state%'
    LOOP
        EXECUTE format('ALTER TABLE optimization_plans DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
    ALTER TABLE optimization_plans ADD CONSTRAINT ck_plan_review_state CHECK
        (review_state IN ('PENDING','APPROVED','REJECTED','REVISION_REQUESTED','INVALIDATED','STALE'));
END $$;

UPDATE optimization_plans
SET review_state = 'INVALIDATED',
    invalidation_reason = 'Missing input provenance; generate a new recommendation',
    invalidated_at = CURRENT_TIMESTAMP
WHERE input_snapshot IS NULL AND review_state NOT IN ('INVALIDATED','STALE');
