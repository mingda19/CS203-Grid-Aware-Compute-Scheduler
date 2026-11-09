-- Migration: Add description column to jobs table (S1 create-job rework).
-- Apply by hand, like V3-V7 (no migration runner is configured; ddl-auto=update creates
-- this column itself on next boot, this file is the documented, idempotent equivalent).
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
