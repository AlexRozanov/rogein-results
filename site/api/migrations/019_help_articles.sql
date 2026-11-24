-- Справка для судей в личном кабинете (этап 1: только чтение).
CREATE TABLE IF NOT EXISTS help_articles (
    id          BIGSERIAL PRIMARY KEY,
    slug        TEXT NOT NULL UNIQUE,
    section     TEXT NOT NULL,
    title       TEXT NOT NULL,
    summary     TEXT NOT NULL DEFAULT '',
    body_md     TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    published   BOOLEAN NOT NULL DEFAULT true,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT help_articles_section_check CHECK (
        section IN ('start', 'day', 'field', 'online', 'faq')
    )
);

CREATE INDEX IF NOT EXISTS idx_help_articles_section_sort
    ON help_articles (section, sort_order, title);
