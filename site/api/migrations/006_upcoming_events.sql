CREATE TABLE IF NOT EXISTS upcoming_events (
    id                  BIGSERIAL PRIMARY KEY,
    slug                TEXT NOT NULL UNIQUE,
    title               TEXT NOT NULL,
    competition_date    DATE NULL,
    sport_kind          TEXT NOT NULL DEFAULT 'rogaine',
    status              TEXT NOT NULL DEFAULT 'announced',
    summary             TEXT NOT NULL DEFAULT '',
    published_event_id  BIGINT NULL REFERENCES events(id) ON DELETE SET NULL,
    announced_at        TIMESTAMPTZ NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upcoming_events_status_check
        CHECK (status IN ('draft', 'announced', 'completed', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS idx_upcoming_events_status_date
    ON upcoming_events (status, competition_date);
