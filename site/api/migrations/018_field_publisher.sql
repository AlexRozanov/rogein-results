-- Один live-издатель на cloud-сессию slug.
ALTER TABLE field_sessions
    ADD COLUMN IF NOT EXISTS publisher_device_id TEXT,
    ADD COLUMN IF NOT EXISTS publisher_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS publisher_claimed_at TIMESTAMPTZ;
