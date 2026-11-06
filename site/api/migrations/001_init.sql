CREATE TABLE IF NOT EXISTS events (
    id              BIGSERIAL PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,
    title           TEXT NOT NULL,
    competition_date DATE NULL,
    status          TEXT NOT NULL DEFAULT 'published',
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    map_file_name   TEXT NULL,
    map_mime        TEXT NULL,
    map_width       INTEGER NULL,
    map_height      INTEGER NULL
);

CREATE TABLE IF NOT EXISTS award_groups (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    source_id       BIGINT NOT NULL,
    name            TEXT NOT NULL,
    gender_mode     TEXT NOT NULL DEFAULT 'any',
    min_age         INTEGER NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (event_id, source_id)
);

CREATE TABLE IF NOT EXISTS participants (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    source_id       BIGINT NOT NULL,
    bib             TEXT NOT NULL,
    chip_physical   TEXT NULL,
    chip_logical    TEXT NULL,
    name            TEXT NOT NULL,
    gender          TEXT NULL,
    birth_date      DATE NULL,
    age             INTEGER NULL,
    team_id         BIGINT NULL,
    format_name     TEXT NULL,
    marks           JSONB NOT NULL DEFAULT '[]'::jsonb,
    path            JSONB NOT NULL DEFAULT '[]'::jsonb,
    UNIQUE (event_id, source_id)
);

CREATE INDEX IF NOT EXISTS idx_participants_event_bib
    ON participants(event_id, bib);
CREATE INDEX IF NOT EXISTS idx_participants_event_team
    ON participants(event_id, team_id);

CREATE TABLE IF NOT EXISTS results (
    id              BIGSERIAL PRIMARY KEY,
    event_id        BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    participant_id  BIGINT NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    award_group_id  BIGINT NOT NULL REFERENCES award_groups(id) ON DELETE CASCADE,
    place           INTEGER NULL,
    points_raw      INTEGER NOT NULL DEFAULT 0,
    penalty_points  INTEGER NOT NULL DEFAULT 0,
    points_final    INTEGER NOT NULL DEFAULT 0,
    elapsed_seconds INTEGER NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'OK',
    UNIQUE (award_group_id, participant_id)
);

CREATE INDEX IF NOT EXISTS idx_results_group_place
    ON results(award_group_id, place NULLS LAST, points_final DESC);
