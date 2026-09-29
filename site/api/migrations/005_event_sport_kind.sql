ALTER TABLE events
    ADD COLUMN IF NOT EXISTS sport_kind TEXT NOT NULL DEFAULT 'rogaine';

UPDATE events
SET sport_kind = 'rogaine'
WHERE sport_kind IS NULL OR sport_kind = '';
