-- AC4.1: latest natural-language parse provenance; existing jobs remain unparsed.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS parsed_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS confirmation_outcome VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_jobs_confirmation_outcome' AND conrelid = 'jobs'::regclass
    ) THEN
        ALTER TABLE jobs ADD CONSTRAINT chk_jobs_confirmation_outcome
            CHECK (confirmation_outcome IN ('PENDING', 'CONFIRMED', 'REJECTED'));
    END IF;
END $$;
