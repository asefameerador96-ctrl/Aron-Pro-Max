-- V0177 sr-server train 2, push B (contract 1.16.0; D-SV2-02, D-SV2-03).
-- 1. app.route_day.late_rows_after_sales_submit (SRM-B-30): memos and due collections CAPTURED after the route-day's Sales Submit and stored before its
--    Final Submit are counted, never refused (offline-first). After the Final Submit the row counts under late_rows_after_final instead.
--    Nullable-free constant default (no rewrite); the CHECK is NOT VALID here and validated by V0178 (squawk rule).
-- 2. cfg.sale.journey_repeat_sale_enabled: the server journey emits `repeat_sale` only when this is on. It ships OFF: the AMO build that knows the value
--    must be the minimum app version first (request file contract-1.14-sr-manual.md, train 2 rule).
SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.route_day IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0186: app.route_day stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.route_day ADD COLUMN IF NOT EXISTS late_rows_after_sales_submit int NOT NULL DEFAULT 0;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'route_day_late_rows_sales_check' AND conrelid = 'app.route_day'::regclass) THEN
    ALTER TABLE app.route_day ADD CONSTRAINT route_day_late_rows_sales_check CHECK (late_rows_after_sales_submit >= 0) NOT VALID;
  END IF;
END $$;

COMMENT ON COLUMN app.route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after its Sales Submit and stored before its Final Submit (counted once per stored record, never refused). After the Final Submit a late row counts under late_rows_after_final instead.';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.sale.journey_repeat_sale_enabled', 'sale', 'S', 'bool', 'false'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'The server Sales Journey (getTeamJourney) names a second or later sale at the same outlet on the same day repeat_sale. Shipped off until the AMO app build that knows the value is the minimum version; off: such a call is new_sale as before.')
ON CONFLICT (key) DO NOTHING;
