# Rogein Desktop 0.1 (Tauri + Vue + TypeScript + SQLite)

Тестовый desktop-проект версии `0.1.0`:

- фронтенд: `Vue 3 + TypeScript + Vite`
- desktop runtime: `Tauri v2`
- локальная БД: `SQLite` через `@tauri-apps/plugin-sql`

## Что уже работает

- desktop оболочка Tauri запускается;
- в окне приложения открывается текущий Python MVP UI (`http://127.0.0.1:8787`);
- есть проверка доступности backend и статус в заголовке.

## Вариант B: статус миграции в native-ядро

В `src-tauri` добавлен первый этап переноса логики из Python:

- создана SQLite-схема для `participants`, `marks_raw`, `settings`, `results`, `corrections`, `leg_exclusion_rules`;
- добавлен транзакционный импорт CSV со строгой валидацией и откатом при ошибке;
- поддержан special-case footer от SFR Reader (`Результаты выгружены из SFR Reader`);
- после успешного импорта выполняется немедленный `recalculate` в той же транзакции;
- добавлен расчет результатов: финиш последним, штраф `ceil(delay/60)`, DQ как `control + dq_minutes`, исключение старт/финиш КП из очков, учет правил исключения перегонов и legacy-коррекций.

Tauri-команды (через `invoke`) на этом этапе:

- `import_csv_content(csvContent, reset)` -> импорт + пересчет;
- `recalculate_results()` -> пересчет без импорта.
- `get_settings()` / `set_settings(...)` -> чтение и обновление настроек;
- `get_results(limit, status, search)` + `get_participants(limit)` -> списки;
- `get_participant_details(participantId)` -> детальная карточка участника.
- `add_cp_correction(...)` / `remove_cp_correction(...)` -> ручные корректировки отметок;
- `add_exclusion_rule(...)` / `update_exclusion_rule(...)` / `delete_exclusion_rule(...)` / `get_exclusion_rules(...)` -> управление правилами исключения перегонов.

Во Vue добавлен базовый native UI:

- импорт CSV (reset/merge), пересчет, обновление данных;
- редактирование настроек (CT, штраф, DQ, финиш КП);
- фильтрация и просмотр таблицы результатов из локальной SQLite.
- блок ручных корректировок и правил (как в Python MVP): add/remove CP, CRUD правил исключения, просмотр карточки участника.

## Команды

```bash
# Установка зависимостей
env -u npm_config_devdir -u NPM_CONFIG_DEVDIR npm install

# Веб-режим
env -u npm_config_devdir -u NPM_CONFIG_DEVDIR npm run dev

# Сборка фронтенда
env -u npm_config_devdir -u NPM_CONFIG_DEVDIR npm run build

# Tauri dev
. "$HOME/.cargo/env"
env -u npm_config_devdir -u NPM_CONFIG_DEVDIR npm run tauri:dev

# Tauri build
. "$HOME/.cargo/env"
env -u npm_config_devdir -u NPM_CONFIG_DEVDIR npm run tauri:build
```

## Linux prerequisites (Ubuntu)

Для сборки Tauri на Linux нужны системные пакеты (`sudo`):

```bash
sudo apt update
sudo apt install -y \
  pkgconf \
  libwebkit2gtk-4.1-dev \
  librsvg2-dev \
  libgtk-3-dev \
  libayatana-appindicator3-dev
```

Если отсутствует Rust:

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
```

## Важно

Перед запуском Tauri нужно поднять Python backend:

```bash
cd /data/projects/Rogein
python3 rogein_mvp.py --db rogein.sqlite3 serve-web --host 127.0.0.1 --port 8787
```
