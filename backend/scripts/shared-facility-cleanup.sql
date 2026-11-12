-- One-off, for the shared-facility change (Compute Jobs rework, Phase 2). Run once, by hand.
--
-- Before: one facility row per user. After: the backend reads one row for everyone, the lowest ID.
-- This keeps the oldest admin's row and deletes the others. If that admin has no row, it keeps the
-- lowest ID, which is the row the backend already reads.
--
-- Approved plans solved on other capacity need no change here. Their stored facility values no longer
-- match, so the plan check (every 60 seconds, and on every approve, apply or export) marks them INVALIDATED.
--
-- Step 1: read-only preview. Check the keep column before running step 2.
SELECT f.id, f.owner_email, f.name, f.max_cpu_hours, f.max_gpu_hours, f.location,
       f.id = (SELECT COALESCE(
                   (SELECT f2.id FROM facilities f2
                     WHERE f2.owner_email = (SELECT u.email FROM users u WHERE u.role = 'ROLE_ADMIN'
                                              ORDER BY u.created_at, u.id LIMIT 1)
                     ORDER BY f2.id LIMIT 1),
                   (SELECT MIN(id) FROM facilities))) AS keep
FROM facilities f
ORDER BY f.id;

-- Step 2: the delete. Run it only after step 1 looks right.
BEGIN;
DELETE FROM facilities
WHERE id <> (SELECT COALESCE(
                 (SELECT f2.id FROM facilities f2
                   WHERE f2.owner_email = (SELECT u.email FROM users u WHERE u.role = 'ROLE_ADMIN'
                                            ORDER BY u.created_at, u.id LIMIT 1)
                   ORDER BY f2.id LIMIT 1),
                 (SELECT MIN(id) FROM facilities)));
COMMIT;
