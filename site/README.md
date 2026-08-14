# Rogein site (local MVP)

Витрина прошедших стартов: Vue + Axum + PostgreSQL + nginx.

Локальная БД:

```
postgres://rogain:rogain@127.0.0.1:5432/rogain
```

## Запуск API

```bash
cd site/api
cp .env.example .env   # уже совпадает с локальным Postgres
cargo run
```

API: `http://127.0.0.1:18790` (8080/8090 на этой машине уже заняты)

- `GET /api/health`
- `GET /api/events`
- `GET /api/events/{slug}`
- `GET /api/events/{slug}/participants/{source_id}`
- `PUT /api/events/{slug}` — JSON результатов, заголовок `Authorization: Bearer dev-token`
- `PUT /api/events/{slug}/map` — сырая карта (`Content-Type`, `X-Map-File-Name`, опционально `X-Map-Width` / `X-Map-Height`)

Из десктопа: Настройки → «Публикация на сайт». Адрес и токен лежат в
`site_publish.json` в папке данных программы и не сбрасываются при завершении старта.

`results.participant_id` — суррогат сайта (`participants.id`).
Связь с десктопом — `participants.source_id`.
У участника отдельно: `bib`, `chip_physical`, `chip_logical`.

## Демо-старт

```bash
curl -X PUT http://127.0.0.1:18790/api/events/schukino-demo \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  --data-binary @examples/demo-event.json
```

## Запуск сайта

```bash
cd site/web
npm install
npm run dev
```

Открыть `http://127.0.0.1:5174`

Vite проксирует `/api` и `/media` на Axum.

## nginx (как в проде, опционально)

Сначала `npm run build` в `site/web`, затем подключить `site/nginx/localhost.conf`.
Вход: `http://127.0.0.1:8088`
