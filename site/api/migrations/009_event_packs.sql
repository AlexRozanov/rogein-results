CREATE TABLE IF NOT EXISTS event_packs (
    slug            TEXT PRIMARY KEY,
    sport_kind      TEXT NOT NULL DEFAULT 'rogaine',
    title           TEXT NOT NULL DEFAULT '',
    pack_json       JSONB NOT NULL,
    map_file_name   TEXT NULL,
    map_mime        TEXT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
