-- Drop the provenance columns schedule_explanations carried for LLM-generated text.
-- Apply explicitly before deploying; no migration runner is configured.
--
-- The explanation is now always generated deterministically from the run's own figures, so these
-- five columns only ever hold constants: source is always 'DETERMINISTIC_FALLBACK', model_name and
-- raw_response_json are always NULL (no row in this database has ever held a non-null one), and
-- fallback_reason records why a model was not used in a design that no longer tries to use one.
--
-- headline and narrative are deliberately kept. They are the audit record of what the operator was
-- shown when they approved a plan, and regenerating that text on read would let a later change to
-- the wording silently alter what is reported as having been approved (contract FR-34).
--
-- Destructive and NOT idempotent in effect: the dropped values cannot be recovered. They are
-- diagnostics for a removed feature, not operational data. Run the SELECT below first if you want
-- a record of them before they go.
--
--   SELECT schedule_run_id, source, fallback_reason, model_name FROM schedule_explanations;

ALTER TABLE schedule_explanations DROP COLUMN IF EXISTS source;
ALTER TABLE schedule_explanations DROP COLUMN IF EXISTS fallback_reason;
ALTER TABLE schedule_explanations DROP COLUMN IF EXISTS model_name;
ALTER TABLE schedule_explanations DROP COLUMN IF EXISTS raw_response_json;
ALTER TABLE schedule_explanations DROP COLUMN IF EXISTS validation_errors_json;
