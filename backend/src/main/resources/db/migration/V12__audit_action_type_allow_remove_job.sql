-- Allow REMOVE_JOB in audit_events.action_type. Apply explicitly before deploying; no migration runner
-- is configured.
--
-- Why this is needed: the same trap V9 fixed for REVOKE. spring.jpa.hibernate.ddl-auto=update created a
-- CHECK constraint on this column from AuditActionType when the table was first generated, and never
-- rewrites an existing check. Adding REMOVE_JOB to the Java enum left the live database rejecting it, so
-- deleting a job from an approved plan failed on the audit insert, rolled the whole delete back, and
-- surfaced as "This change conflicts with data already stored." Fresh schemas (every test run) have the
-- new value and pass, so only a database that has been around a while shows it.
--
-- Same shape as V9: Hibernate's generated constraint names differ by installation, so find the check by
-- what it refers to rather than by name. Additive and idempotent.
--
-- When AuditActionType gains another value, add a V13 that copies this file; AuditActionTypeMigrationTest
-- fails until the newest migration lists every enum value.

DO $$
DECLARE constraint_row record;
BEGIN
    FOR constraint_row IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'audit_events'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%action_type%'
    LOOP
        EXECUTE format('ALTER TABLE audit_events DROP CONSTRAINT %I', constraint_row.conname);
    END LOOP;
    ALTER TABLE audit_events ADD CONSTRAINT ck_audit_action_type CHECK
        (action_type IN ('APPROVE','REJECT','REVISE','REVOKE','OVERRIDE','DISPATCH','REMOVE_JOB'));
END $$;
