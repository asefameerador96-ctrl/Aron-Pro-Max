-- Lane masterdata-fit, push 1 (M03, owner ruling D-OWNER-PARITY-02): a route can have NO visit plan and it is stored as it is.
-- In the field's master data 88,477 outlets carry no visit frequency and no visit day. We do not invent a plan (an invented 127 would inflate
-- the target outlets) and we do not refuse the route. The explicit form of "no plan" is visit_kind = 'none' with visit_days_mask = 0:
-- never planned on any date, never counted in target outlets, shown to the apps and the portal as "no visit plan".
-- A mask of 0 is allowed ONLY together with kind 'none' (kind NULL with mask 0 is refused), and kind 'none' needs mask 0.
-- Expand only: every existing row (mask 1..127, kind NULL/daily/3f/2f) still satisfies the new rules. The new checks are added NOT VALID here
-- and validated in V0196 (squawk rule). app.visit_kind_matches keeps its signature (replaced in place).

SET lock_timeout = '5s';

-- Lock safety (squawk / V0144 pattern): the ALTERs below take ACCESS EXCLUSIVE on hot tables. All the locks are taken by NOWAIT inside a
-- sub-transaction and retried (about 60 s); a busy table makes the sub-transaction release everything and sleep holding nothing, so the
-- migration never queues behind a long transaction and never blocks the sync writers behind its own queued lock request.
DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.route IN ACCESS EXCLUSIVE MODE NOWAIT;
      LOCK TABLE app.route_planned IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0195: the tables stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

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

-- The route-kind columns in the app and dw objects say what 'none' means (V0195 comment rule: the dictionary is the rendering of these comments).
COMMENT ON COLUMN app.route.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, mask 0, never planned).';
COMMENT ON COLUMN app.route_history.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, mask 0, never planned). (as it was in the period).';
COMMENT ON COLUMN app.route_planned.visit_kind IS 'Visit pattern in force for the period: daily, 3f, 2f or none (no visit plan, mask 0).';
COMMENT ON COLUMN dw.dim_geo.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, never planned).';
COMMENT ON COLUMN dw.dim_geo_version.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, never planned). (as it was in the period).';
COMMENT ON COLUMN dw.v_dim_geo.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, never planned).';
COMMENT ON COLUMN dw.v_dim_geo_history.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, never planned). (as it was in the period).';
COMMENT ON COLUMN dw.v_daily_route.visit_kind IS 'Visit pattern of the route: daily (every selling day), 3f (three days a week), 2f (two days) or none (no visit plan, never planned).';
COMMENT ON COLUMN dw.fact_visit.visit_kind IS 'Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry. A route''s own pattern (daily, 3f, 2f, none) is in dim_geo.visit_kind.';
COMMENT ON COLUMN dw.v_visit.visit_kind IS 'Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry. A route''s own pattern (daily, 3f, 2f, none) is in dim_geo.visit_kind.';
COMMENT ON COLUMN app.visit.visit_kind IS 'Kind of call: sr_call, amo_control_call, amo_joint_call, tso_visit or web_entry. A route''s own pattern (daily, 3f, 2f, none) is app.route.visit_kind.';
