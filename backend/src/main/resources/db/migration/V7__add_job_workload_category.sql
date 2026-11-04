-- Migration: Add workload_category column to jobs table (usability bug 6).
-- Apply by hand, like V3-V6 (see their notes - no migration runner is configured;
-- ddl-auto=update creates this column itself on next boot, this file is the documented,
-- idempotent equivalent plus the CHECK constraint ddl-auto cannot express).
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS workload_category VARCHAR(20) NOT NULL DEFAULT 'ML_TRAINING';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_jobs_workload_category'
    ) THEN
        ALTER TABLE jobs ADD CONSTRAINT chk_jobs_workload_category
            CHECK (workload_category IN ('ML_TRAINING', 'CRYPTO_MINING', 'HPC_BATCH', 'HVAC_PRE_COOL', 'GRID_ALERT'));
    END IF;
END $$;
