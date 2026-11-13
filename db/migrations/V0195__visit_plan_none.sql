-- Lane masterdata-fit, push 1 (M03, owner ruling D-OWNER-PARITY-02): a route can have NO visit plan and it is stored as it is.
-- In the field's master data 88,477 outlets carry no visit frequency and no visit day. We do not invent a plan (an invented 127 would inflate
-- the target outlets) and we do not refuse the route. The explicit form of "no plan" is visit_kind = 'none' with visit_days_mask = 0:
-- never planned on any date, never counted in target outlets, shown to the apps and the portal as "no visit plan".
-- A mask of 0 is allowed ONLY together with kind 'none' (kind NULL with mask 0 is refused), and kind 'none' needs mask 0.
-- Expand only: every existing row (mask 1..127, kind NULL/daily/3f/2f) still satisfies the new rules. The new checks are added NOT VALID here
-- and validated in V0196 (squawk rule). app.visit_kind_matches keeps its signature (replaced in place).

SET lock_timeout = '5s';

CREATE OR REPLACE FUNCTION app.visit_kind_matches(p_kind text, p_mask smallint) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT CASE
            WHEN p_kind IS NULL THEN p_mask > 0
            WHEN p_kind = 'none' THEN p_mask = 0
            WHEN p_kind = 'daily' THEN p_mask = 127
            WHEN p_kind = '3f' THEN bit_count(p_mask::int::bit(7)) = 3
            WHEN p_kind = '2f' THEN bit_count(p_mask::int::bit(7)) = 2
            ELSE false END $$;

ALTER TABLE app.route DROP CONSTRAINT route_visit_days_mask_check;
ALTER TABLE app.route DROP CONSTRAINT route_visit_kind_check;
ALTER TABLE app.route ADD CONSTRAINT route_visit_days_mask_check CHECK (visit_days_mask BETWEEN 0 AND 127) NOT VALID;
ALTER TABLE app.route ADD CONSTRAINT route_visit_kind_check CHECK (visit_kind IN ('daily','3f','2f','none')) NOT VALID;

ALTER TABLE app.route_planned DROP CONSTRAINT route_planned_visit_days_mask_check;
ALTER TABLE app.route_planned DROP CONSTRAINT route_planned_visit_kind_check;
ALTER TABLE app.route_planned ADD CONSTRAINT route_planned_visit_days_mask_check CHECK (visit_days_mask BETWEEN 0 AND 127) NOT VALID;
ALTER TABLE app.route_planned ADD CONSTRAINT route_planned_visit_kind_check CHECK (visit_kind IN ('daily','3f','2f','none')) NOT VALID;

COMMENT ON COLUMN app.route.visit_days_mask IS 'Bit mask of visit days: bit0 Saturday through bit6 Friday; 127 means daily; 0 means no visit plan (visit_kind none), never planned.';
COMMENT ON COLUMN app.route_planned.visit_days_mask IS 'Bit mask of planned visit days: bit0 Saturday through bit6 Friday; 0 means no visit plan (visit_kind none).';
