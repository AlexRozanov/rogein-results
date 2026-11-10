ALTER TABLE award_groups
    ADD COLUMN IF NOT EXISTS length_meters INTEGER NULL;

ALTER TABLE award_groups
    ADD COLUMN IF NOT EXISTS climb_meters INTEGER NULL;

ALTER TABLE award_groups
    ADD COLUMN IF NOT EXISTS leg_meters JSONB NOT NULL DEFAULT '[]'::jsonb;
