-- V0150 AMO manual build, foundation train F1 (docs/requests/amo-manual-build-2026-11.md s2; D-AMO-MAN-08, 10, 12, 13, 14;
-- D-AMO-FND-01 to 04).
--
-- 1. app.visit.force_sale_reason: why the SR or AMO sold outside the geo-fence (screenshot 23): internet_problem or
--    location_change. Only a force sale carries one (CHECK). Both CHECKs are added NOT VALID (every existing row is NULL, so they
--    hold) and validated by V0151 in a transaction of its own (squawk rule, as V0112/V0113).
-- 2. app.outlet_request_event.route_id / cluster_id: the route and cluster an AMO corrected on a verification form
--    (D-AMO-MAN-12). The verification record already carries the re-captured fix and photo (fix_*, photo_uuid columns);
--    nothing moves on the outlet until the approval rules say so. FKs NOT VALID, validated by V0151.
-- 3. app.cfg_key rows: the three AMO call targets, the price-compliance tile switch, the Joint Call default star, the Perf
--    Assessment brand list, and the three Update Base guard keys the backend already reads with documented defaults
--    (docs/requests/android-amo-update-base-server-guards.md; docs/19 s4). The guard key for the monthly cap keeps the name
--    the backend reads, cfg.geo.update_base_max_per_outlet_month (the contract text says "monthly_cap"; D-AMO-FND-02).
-- 4. Rubric sections and guidance: app.rubric_version.criteria is a JSON array, not a table (V0023), so section,
--    guidance_en and guidance_bn are new members of the criterion objects (contract 1.11.0), not columns: no DDL
--    (D-AMO-FND-01). The joint_call version 2 content is dev seed data (db/seed/08b_dev_amo_manual_content.sql).
--
-- Behaviour under a running worker and API: ADD COLUMN needs ACCESS EXCLUSIVE on app.visit and app.outlet_request_event,
-- the FK constraints SHARE ROW EXCLUSIVE on app.route and app.cluster. All four are taken by ONE LOCK ... NOWAIT inside a
-- sub-transaction, retried for about 60 s (the V0144 pattern), so the migration never waits while holding part of the set.
-- Nothing is rewritten: the columns are nullable without defaults. Additive and idempotent.

SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.visit, app.outlet_request_event IN ACCESS EXCLUSIVE MODE NOWAIT;
      LOCK TABLE app.route, app.cluster IN SHARE ROW EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0150: app.visit, app.outlet_request_event, app.route or app.cluster stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.visit ADD COLUMN IF NOT EXISTS force_sale_reason text;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'visit_force_sale_reason_check' AND conrelid = 'app.visit'::regclass) THEN
    ALTER TABLE app.visit ADD CONSTRAINT visit_force_sale_reason_check
      CHECK (force_sale_reason IN ('internet_problem', 'location_change')) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'visit_force_sale_reason_needs_force_sale' AND conrelid = 'app.visit'::regclass) THEN
    ALTER TABLE app.visit ADD CONSTRAINT visit_force_sale_reason_needs_force_sale
      CHECK (force_sale_reason IS NULL OR geo_action = 'force_sale') NOT VALID;
  END IF;

  ALTER TABLE app.outlet_request_event ADD COLUMN IF NOT EXISTS route_id bigint, ADD COLUMN IF NOT EXISTS cluster_id bigint;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'outlet_request_event_route_id_fkey' AND conrelid = 'app.outlet_request_event'::regclass) THEN
    ALTER TABLE app.outlet_request_event ADD CONSTRAINT outlet_request_event_route_id_fkey
      FOREIGN KEY (route_id) REFERENCES app.route (id) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'outlet_request_event_cluster_id_fkey' AND conrelid = 'app.outlet_request_event'::regclass) THEN
    ALTER TABLE app.outlet_request_event ADD CONSTRAINT outlet_request_event_cluster_id_fkey
      FOREIGN KEY (cluster_id) REFERENCES app.cluster (id) NOT VALID;
  END IF;
END $$;

COMMENT ON COLUMN app.visit.force_sale_reason IS 'Why the sale was made outside the geo-fence, chosen on the phone: internet_problem or location_change. Only set when geo_action is force_sale (CHECK); null otherwise. Separate from force_reason_code (the older free code).';
COMMENT ON COLUMN app.outlet_request_event.route_id IS 'Route the verifier (AMO) chose on a verification form; null when the form did not change it. Nothing moves until the approval rules say so.';
COMMENT ON COLUMN app.outlet_request_event.cluster_id IS 'Cluster the verifier (AMO) chose on a verification form; null when the form did not change it. Nothing moves until the approval rules say so.';

-- ---------- config keys (D-AMO-MAN-10, 13, 14; docs/19 s4 and s9; 4.3 of the build contract) ----------
-- A null default is "no value" (the card prints a dash); the call targets are monthly counts per zone, resolved for the caller's zone.
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.amo.call_target_month', 'amo', 'S', 'int', 'null'::jsonb, '{"min": 0, "max": 100000}'::jsonb, NULL, ARRAY['global', 'role', 'territory', 'zone']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'AMO monthly call target (control and joint calls together) for the zone: the Dashboard target cards show it and today''s share of it. Unset = the card prints a dash (D-AMO-MAN-06).'),
  ('cfg.amo.control_call_target_month', 'amo', 'S', 'int', 'null'::jsonb, '{"min": 0, "max": 100000}'::jsonb, NULL, ARRAY['global', 'role', 'territory', 'zone']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'AMO monthly Control Call target for the zone (Dashboard target card). Unset = the card prints a dash.'),
  ('cfg.amo.joint_call_target_month', 'amo', 'S', 'int', 'null'::jsonb, '{"min": 0, "max": 100000}'::jsonb, NULL, ARRAY['global', 'role', 'territory', 'zone']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.field',
   'AMO monthly Joint Call target for the zone (Dashboard target card). Unset = the card prints a dash.'),
  ('cfg.amo.price_compliance_tile', 'amo', 'S', 'bool', 'false'::jsonb, '{}'::jsonb, NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Shows the Price compliance tile in the AMO Control Call menu. The vendor menu has three tiles; off by default (D-AMO-MAN-14).'),
  ('cfg.amo.joint_call_default_star', 'amo', 'S', 'enum', '"1"'::jsonb, '{"enum": ["1", "none"]}'::jsonb, NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Joint Call rating each item starts on: 1 (every item starts at one star, as the vendor app) or none (the AMO must rate every item before Save) (D-AMO-MAN-10).'),
  ('cfg.rubric.distribution_brands', 'rubric', 'S', 'list', '[]'::jsonb, '{"max_items": 50}'::jsonb, 'brand ids that exist in the product tree', ARRAY['global', 'wing', 'division', 'territory', 'zone']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'Brand ids, in order, of the Perf Assessment distribution and out-of-stock lists. Empty = every brand of the product tree (D-AMO-MAN-10).'),
  ('cfg.geo.update_base_max_distance_m', 'geo', 'S', 'int', '100'::jsonb, '{"min": 20, "max": 500}'::jsonb, NULL, ARRAY['global', 'territory']::text[], 2, NULL, 'B', 'both', false, false, 'down', 'cfg.edit.security',
   'Update Base: the chosen point may be at most this many metres from the AMO''s own fix (D-95). Checked on the phone and again when the location request is approved.'),
  ('cfg.geo.update_base_max_move_m', 'geo', 'S', 'int', '1000'::jsonb, '{"min": 100, "max": 5000}'::jsonb, NULL, ARRAY['global']::text[], 2, NULL, 'B', 'both', false, false, 'down', 'cfg.edit.security',
   'Update Base: the largest move of an outlet''s stored point one location change may apply, metres (hard cap, D-111).'),
  ('cfg.geo.update_base_max_per_outlet_month', 'geo', 'S', 'int', '1'::jsonb, '{"min": 1, "max": 5}'::jsonb, NULL, ARRAY['global']::text[], 2, NULL, 'B', 'server', false, false, 'down', 'cfg.edit.security',
   'Update Base: how many location changes may be approved for one outlet in a Dhaka month (register R-32).')
ON CONFLICT (key) DO NOTHING;
