ALTER TABLE upcoming_events
    ADD COLUMN IF NOT EXISTS bulletin_ready BOOLEAN NOT NULL DEFAULT false;

-- Уже заполненные бюллетени остаются доступными на сайте.
UPDATE upcoming_events
SET bulletin_ready = true
WHERE bulletin IS NOT NULL
  AND bulletin <> '{}'::jsonb
  AND bulletin_ready = false;
