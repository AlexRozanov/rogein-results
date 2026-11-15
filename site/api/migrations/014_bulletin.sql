ALTER TABLE upcoming_events
    ADD COLUMN IF NOT EXISTS bulletin JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE upcoming_events
    ALTER COLUMN sport_kind SET DEFAULT 'orient';
