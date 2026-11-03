-- Apply explicitly after the backend has booted once with ModelArtifact/RmseReference/
-- ValidationState present (ddl-auto=update creates the three tables themselves on that boot -
-- see those entities' Javadoc); this migration only adds the one thing ddl-auto can't express
-- (a partial unique index) and seeds validation_state's two rows. No migration runner is
-- configured (see V4/V5's own notes) - apply by hand. Safe to re-run.

-- At most one active model_artifacts row per (model_kind, target) - JPA/Hibernate has no
-- annotation for a partial index, so this is the actual enforcement; ModelArtifact.active's
-- Javadoc points back here.
CREATE UNIQUE INDEX IF NOT EXISTS idx_model_artifacts_one_active
    ON model_artifacts (model_kind, target) WHERE is_active;

-- Seed both targets at the existing holdout boundary (model/train.csv ends 2026-01-31,
-- model/val.csv / model/evaluate.py's HOLDOUT_FOLD starts 2026-02-01) - adjust these two dates
-- first if that boundary ever moves.
INSERT INTO validation_state (target, validation_start_date, original_start_date, updated_at)
VALUES ('y0', DATE '2026-02-01', DATE '2026-02-01', now()),
       ('y1', DATE '2026-02-01', DATE '2026-02-01', now())
ON CONFLICT (target) DO NOTHING;
