-- Allow REVOKE in audit_events.action_type. Apply explicitly before deploying; no migration runner
-- is configured.
--
-- Why this is needed: spring.jpa.hibernate.ddl-auto=update created a CHECK constraint on this column
-- from AuditActionType when the table was first generated, listing only the values that existed then
-- (APPROVE, REJECT, REVISE, OVERRIDE, DISPATCH). `update` adds columns but never rewrites an existing
-- check, so adding REVOKE to the Java enum left the database still rejecting it: revoking an approval
-- failed with a DataIntegrityViolationException, surfaced as "This change conflicts with data already
-- stored." A freshly generated schema (every test run) has the new value and passes, which is exactly
-- why this only shows up against a database that has been around a while.
--
-- Same shape as the review_state fix in V5: Hibernate's generated constraint names differ by
-- installation, so find the check by what it refers to rather than by name. Additive and idempotent.

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
        (action_type IN ('APPROVE','REJECT','REVISE','REVOKE','OVERRIDE','DISPATCH'));
END $$;
