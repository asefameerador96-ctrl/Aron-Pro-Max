-- The slice smoke's own SR (lane sr-phone-fixes, D-SRFIX-04): `smoke1`, primary on ONE dev route SMK-SR-D (own zone Z-SMK under T-DHK-N / H-GUL, daily)
-- that holds only the SMOKE-SR-001..003 outlets (infra/sql/devseed-smoke-outlet.sql, loaded after this file), bound to the
-- seeded dev device 00000000-0000-4000-8000-000000000001 beside sr1001 (a device may carry several users; the unique
-- (device, user) pair is what the binding index guards). The owner's pilot login sr1001 is not touched: its phone stops showing the
-- smoke sales as "from other phones or earlier installs of this login today", and the smoke outlets leave its routes.
--
-- PROFILE: Azure dev only, exactly like 06 (changes nothing when 04's config version exists, i.e. in CI, the backend tests
-- and a local load; the Mirpur fixture stays as it is and the smoke outlet file falls back to MIR-SR-D). Needs 06's zone
-- Z-GUL; without it nothing is done. Idempotent: every insert is guarded.
-- No password is committed: infra/scripts/devseed-run.sh gives every pilot account without one the Argon2id hash of
-- ARON_SEED_PASSWORD, so smoke1 logs in with the same secret as sr1001.

DO $seed$
DECLARE
  today date := app.dhaka_date(now());
BEGIN
  IF EXISTS (SELECT 1 FROM app.cfg_version WHERE summary = 'Dev database overrides (docs/24 s9.4, seed)') THEN
    RAISE NOTICE 'seed 06 smoke user skipped (config version of 04 present: test or local profile)';
    RETURN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app.zone WHERE code = 'Z-GUL') THEN
    RAISE NOTICE 'seed 06 smoke user skipped (no Z-GUL: seed 06 has not run)';
    RETURN;
  END IF;

  -- Its own zone Z-SMK (territory T-DHK-N, house H-GUL, cluster 'Smoke Circle'), so the per-zone Final Submit preview of Z-GUL
  -- stays as it was. Z-SMK itself is inside tso1001's territory, so on dev a TSO/DMO/national card shows it as one service zone
  -- not final and one route not logged in on days without a smoke run (accepted dev-only residual, D-SRFIX-04b).
  INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
    SELECT 'Z-SMK', 'Slice smoke (dev)', 'স্লাইস স্মোক (ডেভ)', t.id, h.id, 'Smoke DEP' FROM app.territory t, app.house h
     WHERE t.code = 'T-DHK-N' AND h.code = 'H-GUL' AND NOT EXISTS (SELECT 1 FROM app.zone x WHERE x.code = 'Z-SMK');
  INSERT INTO app.cluster (zone_id, name, cluster_type)
    SELECT z.id, 'Smoke Circle', 'Transit Hub' FROM app.zone z
     WHERE z.code = 'Z-SMK' AND NOT EXISTS (SELECT 1 FROM app.cluster x WHERE x.zone_id = z.id AND x.name = 'Smoke Circle');
  INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no, route_class)
    SELECT 'SMK-SR-D', 'Slice smoke (daily)', 'Daily', z.id, 'sr', 'daily', 127, 99, 'normal'
      FROM app.zone z WHERE z.code = 'Z-SMK' AND NOT EXISTS (SELECT 1 FROM app.route x WHERE x.code = 'SMK-SR-D');
  INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from)
    SELECT r.id, r.visit_kind, r.visit_days_mask, DATE '2026-10-01' FROM app.route r
     WHERE r.code = 'SMK-SR-D' AND NOT EXISTS (SELECT 1 FROM app.route_planned p WHERE p.route_id = r.id);
  -- The zone's open sales plan, like 06_dev_gulshan_banani's for Z-GUL: BundleService builds a route's sales_plan_sku_ids per zone
  -- (no fallback), and the smoke needs a plan SKU that is active and has an outlet price (slice-smoke.py pick_route).
  INSERT INTO app.sales_plan (zone_id, sku_id, valid_from)
    SELECT z.id, s.id, DATE '2026-10-01' FROM app.zone z, app.sku s
     WHERE z.code = 'Z-SMK' AND s.status = 'active'
       AND EXISTS (SELECT 1 FROM app.sku_price p WHERE p.sku_id = s.id AND p.price_type = 'outlet' AND p.amount_mtk > 0)
       AND NOT EXISTS (SELECT 1 FROM app.sales_plan p WHERE p.zone_id = z.id AND p.sku_id = s.id AND p.valid_to IS NULL);

  INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password)
    SELECT 'smoke1', 'Slice Smoke SR (dev)', 'SR', 'Sales Representative', 'T-SMK-0001', 'en', z.id, true, false
      FROM app.zone z WHERE z.code = 'Z-SMK' AND NOT EXISTS (SELECT 1 FROM app.app_user x WHERE lower(x.username) = 'smoke1');

  INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason)
    SELECT r.id, u.id, 'primary', today, 'dev seed: the slice smoke SR (D-SRFIX-04)'
      FROM app.route r, app.app_user u
     WHERE r.code = 'SMK-SR-D' AND u.username = 'smoke1'
       AND NOT EXISTS (SELECT 1 FROM app.route_assignment x WHERE x.route_id = r.id AND x.kind = 'primary'
                         AND (x.valid_to IS NULL OR x.valid_to > today));

  INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via)
    SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u
     WHERE d.device_uuid = '00000000-0000-4000-8000-000000000001' AND u.username = 'smoke1'
       AND NOT EXISTS (SELECT 1 FROM app.device_binding b WHERE b.user_id = u.id AND b.status = 'active');

  IF to_regtype('app.data_origin') IS NOT NULL THEN
    UPDATE app.app_user SET data_origin = 'test' WHERE username = 'smoke1' AND data_origin <> 'test';
    UPDATE app.route SET data_origin = 'test' WHERE code = 'SMK-SR-D' AND data_origin <> 'test';
  END IF;
  RAISE NOTICE 'seed 06 smoke user applied (smoke1 on SMK-SR-D)';
END
$seed$;
