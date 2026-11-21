-- Cloud field sync: append-only journal relay (docs/field-sync.md §6 cloud).

CREATE TABLE IF NOT EXISTS field_sessions (
    slug TEXT PRIMARY KEY,
    start_code_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS field_ops (
    id BIGSERIAL PRIMARY KEY,
    slug TEXT NOT NULL REFERENCES field_sessions (slug) ON DELETE CASCADE,
    op_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    device_seq BIGINT NOT NULL,
    clock_ms BIGINT NOT NULL DEFAULT 0,
    actor TEXT NOT NULL DEFAULT '',
    op_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    envelope JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (op_id)
);

CREATE INDEX IF NOT EXISTS field_ops_slug_device_seq_idx
    ON field_ops (slug, device_id, device_seq);

CREATE INDEX IF NOT EXISTS field_ops_slug_received_idx
    ON field_ops (slug, received_at);
