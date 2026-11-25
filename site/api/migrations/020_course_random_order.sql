ALTER TABLE award_groups
    ADD COLUMN IF NOT EXISTS course_random_order JSONB NOT NULL DEFAULT '[]'::jsonb;
