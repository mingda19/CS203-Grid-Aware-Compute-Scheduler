-- Migration: Add priority column to jobs table (GRID-155 / AC1.1)
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_jobs_priority'
    ) THEN
        ALTER TABLE jobs ADD CONSTRAINT chk_jobs_priority
            CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL', 'LOW'));
    END IF;
END $$;
