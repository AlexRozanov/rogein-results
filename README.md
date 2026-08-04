# Rogein MVP (SQLite + CSV)

MVP-утилита для обработки результатов рогейна:

- импортирует CSV в SQLite;
- хранит настройки соревнования;
- применяет ручные корректировки;
- пересчитывает и выводит результаты;
- экспортирует итог в CSV.
- валидирует формат CSV и при ошибке не меняет текущие результаты.

## Требования

- Python 3.10+

## Быстрый старт

```bash
python3 rogein_mvp.py --db rogein.sqlite3 init-db
python3 rogein_mvp.py --db rogein.sqlite3 import-csv --csv csv/results-190-teams.csv
python3 rogein_mvp.py --db rogein.sqlite3 set-settings --control-minutes 240 --penalty-per-minute 3 --dq-minutes 60 --finish-cp 240
python3 rogein_mvp.py --db rogein.sqlite3 recalculate
python3 rogein_mvp.py --db rogein.sqlite3 show-results --limit 20
```

## Логика расчета (в MVP)

- Очки КП: из номера КП убирается последняя цифра (`cp // 10`), затем суммируются только уникальные значения.
- `start_station_id` и `finish_cp` не учитываются в очках.
- Штраф за опоздание:
  - `delay_seconds = max(0, elapsed - control_time)`
  - `penalty_minutes = ceil(delay_seconds / 60)`
  - `penalty_points = penalty_minutes * penalty_per_minute`
- Дисквалификация: если время больше `control_minutes + dq_minutes`, статус `DQ`.
- `dq_minutes` — это запас времени после КВ (например, `control=240`, `dq=60` => DQ после 300 минут).
- Исключение перегона работает по правилам:
  - область: участник или все участники;
  - направление: прямое, обратное, оба;
  - режим: 1 раз или всегда;
  - вычет времени: `min(реальное время перегона, max_leg_seconds)` (если максимум задан).
- Проверка финиша:
  - финишный КП (из `finish_cp`) должен быть у участника;
  - финишный КП должен быть последним по времени.
  - если не выполнено — статус `ERR`.

## Ручные корректировки

### 1) Добавить взятие КП

```bash
python3 rogein_mvp.py --db rogein.sqlite3 add-cp --participant-id 130 --cp 77 --time "2026-08-02 15:10:00"
```

### 2) Удалить взятие КП

Удалить первое совпадение:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 remove-cp --participant-id 130 --cp 77
```

Удалить все совпадения:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 remove-cp --participant-id 130 --cp 77 --all
```

Удалить конкретную отметку по времени:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 remove-cp --participant-id 130 --cp 77 --time "2026-08-02 15:10:00"
```

### 3) Исключить время перегона между КП

Только для одного участника, прямое направление, 1 раз:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 exclude-leg --participant-id 130 --from-cp 77 --to-cp 92 --direction forward --mode once
```

Для всех участников, в обоих направлениях, всегда:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 exclude-leg --all-participants --from-cp 77 --to-cp 92 --direction both --mode always
```

С ограничением максимального вычета на перегон:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 exclude-leg --participant-id 130 --from-cp 77 --to-cp 92 --direction forward --mode always --max-leg-seconds 600
```

Просмотр существующих правил:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 list-exclude-leg
python3 rogein_mvp.py --db rogein.sqlite3 list-exclude-leg --participant-id 130
```

Изменение существующего правила:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 update-exclude-leg --rule-id 5 --participant-id 130 --from-cp 77 --to-cp 92 --direction both --mode always --max-leg-seconds 600
```

Удаление правила:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 delete-exclude-leg --rule-id 5
```

После любой корректировки нужно запустить пересчет:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 recalculate
```

## Экспорт результатов

```bash
python3 rogein_mvp.py --db rogein.sqlite3 export-results --csv out/results.csv
```

## Веб-интерфейс (локально)

```bash
python3 rogein_mvp.py --db rogein.sqlite3 serve-web --host 127.0.0.1 --port 8787
```

Перезапуск на том же порту без ручного поиска процесса:

```bash
python3 rogein_mvp.py --db rogein.sqlite3 serve-web --host 127.0.0.1 --port 8787 --restart
```

После запуска откройте:

- [http://127.0.0.1:8787](http://127.0.0.1:8787)

В интерфейсе доступны:

- загрузка CSV файла с результатами прямо из браузера;
- настройка `control_minutes`, `penalty_per_minute`, `dq_minutes` (после КВ), `finish_cp`;
- пересчет;
- таблица результатов с фильтром;
- переход по `ID` на отдельную страницу участника (`/participant/<id>`);
- карточка участника;
- добавление корректировок `add-cp`, `remove-cp`, `exclude-leg` с параметрами:
  - для одного участника или для всех;
  - прямое/обратное/оба направления;
  - 1 раз или всегда;
  - максимум времени перегона (вычитается `min(реальное, максимум)`).
- управление правилами исключения: просмотр, изменение, удаление.

## Важно про CSV

- CSV должен быть с разделителем `;`.
- Внутри поля `brief` могут быть переводы строк — это поддерживается.
- Значения времени должны быть в ISO-формате (`YYYY-MM-DD HH:MM:SS`).
- При успешной загрузке CSV результаты пересчитываются автоматически.
- При ошибке формата CSV загрузка отклоняется и текущие данные/результаты в БД не изменяются.
