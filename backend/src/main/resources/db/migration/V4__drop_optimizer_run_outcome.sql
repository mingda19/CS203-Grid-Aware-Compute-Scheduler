-- Migration: Drop optimizer_runs.status and optimizer_runs.proven_optimal (GRID-134/135/140 cleanup)
-- Both columns now live on schedule_runs instead, to stop the same outcome being duplicated across
-- OptimizerRun and ScheduleRun. ddl-auto=update never drops columns, so this must be applied by
-- hand on any database where optimizer_runs already exists.
-- Safe to re-run.

ALTER TABLE optimizer_runs DROP COLUMN IF EXISTS status;
ALTER TABLE optimizer_runs DROP COLUMN IF EXISTS proven_optimal;
