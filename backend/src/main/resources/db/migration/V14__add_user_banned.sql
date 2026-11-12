-- Admin ban flag. ddl-auto=update adds this column itself on boot; this file is the
-- documented, idempotent equivalent for a hand-applied database.
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;
