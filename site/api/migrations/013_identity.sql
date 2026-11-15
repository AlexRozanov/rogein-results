CREATE TABLE IF NOT EXISTS organizers (
    id          BIGSERIAL PRIMARY KEY,
    slug        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    short_name  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO organizers (slug, name, short_name)
VALUES ('malahit', 'СК Малахит', 'Малахит')
ON CONFLICT (slug) DO NOTHING;

CREATE TABLE IF NOT EXISTS clubs (
    id              BIGSERIAL PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    organizer_id    BIGINT NULL REFERENCES organizers(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO clubs (slug, name, organizer_id)
SELECT 'malahit', 'СК Малахит', id
FROM organizers
WHERE slug = 'malahit'
ON CONFLICT (slug) DO NOTHING;

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS organizer_id BIGINT NULL REFERENCES organizers(id);

UPDATE events
SET organizer_id = (SELECT id FROM organizers WHERE slug = 'malahit')
WHERE organizer_id IS NULL;

ALTER TABLE events
    ALTER COLUMN organizer_id SET NOT NULL;

ALTER TABLE upcoming_events
    ADD COLUMN IF NOT EXISTS organizer_id BIGINT NULL REFERENCES organizers(id);

UPDATE upcoming_events
SET organizer_id = (SELECT id FROM organizers WHERE slug = 'malahit')
WHERE organizer_id IS NULL;

ALTER TABLE upcoming_events
    ALTER COLUMN organizer_id SET NOT NULL;

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_status_check CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE IF NOT EXISTS profiles (
    user_id         BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    display_name    TEXT NOT NULL DEFAULT '',
    phone           TEXT NOT NULL DEFAULT '',
    birth_date      DATE NULL,
    gender          TEXT NOT NULL DEFAULT '',
    home_club_id    BIGINT NULL REFERENCES clubs(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS club_memberships (
    id              BIGSERIAL PRIMARY KEY,
    club_id         BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status          TEXT NOT NULL DEFAULT 'active',
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at         TIMESTAMPTZ NULL,
    UNIQUE (club_id, user_id),
    CONSTRAINT club_memberships_status_check
        CHECK (status IN ('invited', 'active', 'left'))
);

CREATE TABLE IF NOT EXISTS sessions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip              TEXT NULL,
    user_agent      TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id);

CREATE TABLE IF NOT EXISTS permissions (
    code    TEXT PRIMARY KEY,
    label   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    label       TEXT NOT NULL,
    system      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id             BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_code     TEXT NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE IF NOT EXISTS user_roles (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id         BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    organizer_id    BIGINT NOT NULL REFERENCES organizers(id) ON DELETE CASCADE,
    event_id        BIGINT NULL REFERENCES events(id) ON DELETE CASCADE,
    club_id         BIGINT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    CONSTRAINT user_roles_one_scope CHECK (event_id IS NULL OR club_id IS NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS user_roles_organizer_uniq
    ON user_roles (user_id, role_id, organizer_id)
    WHERE event_id IS NULL AND club_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS user_roles_event_uniq
    ON user_roles (user_id, role_id, event_id)
    WHERE event_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS user_roles_club_uniq
    ON user_roles (user_id, role_id, club_id)
    WHERE club_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_roles_user ON user_roles (user_id);

CREATE TABLE IF NOT EXISTS ingest_tokens (
    id              BIGSERIAL PRIMARY KEY,
    organizer_id    BIGINT NOT NULL REFERENCES organizers(id) ON DELETE CASCADE,
    event_slug      TEXT NOT NULL,
    name            TEXT NOT NULL,
    token_hash      TEXT NOT NULL UNIQUE,
    scopes          TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_by      BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ NULL,
    expires_at      TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS ingest_tokens_slug_active
    ON ingest_tokens (event_slug)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS audit_log (
    id                  BIGSERIAL PRIMARY KEY,
    at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id       BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    actor_token_id      BIGINT NULL REFERENCES ingest_tokens(id) ON DELETE SET NULL,
    action              TEXT NOT NULL,
    entity_type         TEXT NOT NULL DEFAULT '',
    entity_id           TEXT NOT NULL DEFAULT '',
    meta                JSONB NOT NULL DEFAULT '{}'::jsonb
);

INSERT INTO permissions (code, label) VALUES
    ('users.read', 'Список сотрудников'),
    ('users.manage', 'Управление сотрудниками'),
    ('roles.manage', 'Состав ролей'),
    ('audit.read', 'Журнал действий'),
    ('site.content', 'Тексты витрины'),
    ('events.read', 'Старты в админке'),
    ('events.announce', 'Анонсы календаря'),
    ('events.pack', 'Пакет старта'),
    ('events.live', 'Online старта'),
    ('events.publish', 'Публикация результатов'),
    ('events.unpublish', 'Снять публикацию'),
    ('events.tokens', 'Токены телефона'),
    ('events.edit', 'Карточка старта'),
    ('account.self', 'Свой профиль'),
    ('events.enter', 'Заявка на старт'),
    ('club.join', 'Вступление в клуб'),
    ('club.read', 'Страница клуба'),
    ('club.members', 'Состав клуба'),
    ('club.manage', 'Карточка клуба')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (code, label, system) VALUES
    ('owner', 'Владелец', TRUE),
    ('admin', 'Администратор', TRUE),
    ('secretary', 'Секретарь', TRUE),
    ('editor', 'Редактор', TRUE),
    ('finish', 'Финиш', TRUE),
    ('participant', 'Участник', TRUE),
    ('club_officer', 'Офицер клуба', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'owner'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'admin'
  AND p.code <> 'roles.manage'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON p.code IN (
    'events.read', 'events.announce', 'events.pack', 'events.live',
    'events.publish', 'events.tokens', 'events.edit'
)
WHERE r.code = 'secretary'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('site.content', 'events.read')
WHERE r.code = 'editor'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('events.read', 'events.live', 'events.tokens')
WHERE r.code = 'finish'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('account.self', 'events.enter', 'club.join', 'club.read')
WHERE r.code = 'participant'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON p.code IN ('club.read', 'club.members', 'club.manage')
WHERE r.code = 'club_officer'
ON CONFLICT DO NOTHING;
