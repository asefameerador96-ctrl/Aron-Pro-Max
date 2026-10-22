-- The SR slice smoke's own outlets (lead 2026-10-07; moved to Gulshan/Banani by lane db 2026-10-22, D-SEED-02,
-- docs/requests/db-infra-smoke-gulshan.md): one retail outlet on each of sr1001's dev routes, so the smoke sale (voided at
-- the end of every run) never lands on an outlet a tester uses. The Gulshan routes are 2F (GUL-SR-2 Sat+Tue, GUL-SR-1
-- Sun+Wed, BAN-SR-11 Mon+Thu; none on Friday), so with one outlet per route the smoke finds an outlet on today's
-- planned route every selling day (infra/scripts/slice-smoke.py takes the code list). Same columns and history rows as
-- db/seed/03_outlets_and_plans.sql; idempotent; dev only (loaded by the dev seed image after db/seed, so after
-- 06_dev_gulshan_banani.sql, which closes the Mirpur routes MIR-SR-D/3F/2F the first smoke outlet sat on). Where the
-- Gulshan seed did not run (test profile: BackendCoreAsksV0061Test loads this file on the Mirpur fixture), only
-- SMOKE-SR-001 exists, on MIR-SR-D, as before.

-- An existing SMOKE-SR-001 from before the move (on MIR-SR-D): placed on GUL-SR-2 with its pin moved; its open
-- placement row is closed today and a new one opened (placement history is half-open dates, no overlap), and a
-- location row is appended (location history is append-only; the latest row is the pin).
UPDATE app.outlet o SET route_id = ro.id, zone_id = ro.zone_id, cluster_id = cl.id,
                        address = 'Smoke Road, Gulshan 2, Dhaka', lat = 23.792900, lng = 90.408200
  FROM app.route ro JOIN app.cluster cl ON cl.zone_id = ro.zone_id AND cl.name = 'Gulshan 2 Circle'
 WHERE ro.code = 'GUL-SR-2' AND o.code = 'SMOKE-SR-001' AND o.route_id IS DISTINCT FROM ro.id;
UPDATE app.outlet_placement_history h SET route_id = o.route_id, cluster_id = o.cluster_id
  FROM app.outlet o
 WHERE h.outlet_id = o.id AND o.code LIKE 'SMOKE-SR-%' AND h.valid_to IS NULL AND h.route_id IS DISTINCT FROM o.route_id
   AND h.valid_from >= app.dhaka_date(now());
UPDATE app.outlet_placement_history h SET valid_to = app.dhaka_date(now())
  FROM app.outlet o
 WHERE h.outlet_id = o.id AND o.code LIKE 'SMOKE-SR-%' AND h.valid_to IS NULL AND h.route_id IS DISTINCT FROM o.route_id;
INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
  SELECT o.id, o.route_id, o.cluster_id, app.dhaka_date(now()) FROM app.outlet o
   WHERE o.code LIKE 'SMOKE-SR-%' AND EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id)
     AND NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL);
INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
  SELECT o.id, o.lat, o.lng, o.location_accuracy_m, 'base_update', now() FROM app.outlet o
   WHERE o.code LIKE 'SMOKE-SR-%'
     AND EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id)
     AND (SELECT (h.lat, h.lng) FROM app.outlet_location_history h WHERE h.outlet_id = o.id ORDER BY h.valid_from DESC, h.id DESC LIMIT 1)
         IS DISTINCT FROM (o.lat, o.lng);

INSERT INTO app.outlet (code, name, name_bn, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel,
                        sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed, location_accuracy_m,
                        outlet_kind, price_type, visit_sequence)
SELECT s.code, s.name, s.name_bn, 'Smoke Owner', s.phone, s.address,
       z.id, ro.id, cl.id, 'GT', sc.id, 'Urban', s.lat, s.lng, 'master', true, 8, 'retail', 'outlet', 99
  FROM (VALUES ('SMOKE-SR-001', 'GUL-SR-2', 'Gulshan 2 Circle', 'Slice Smoke Outlet', 'স্লাইস স্মোক আউটলেট', '01799999999',
                'Smoke Road, Gulshan 2, Dhaka', 23.792900, 90.408200),
               ('SMOKE-SR-002', 'GUL-SR-1', 'Gulshan 1 Circle', 'Slice Smoke Outlet 2', 'স্লাইস স্মোক আউটলেট ২', '01799999998',
                'Smoke Road, Gulshan 1, Dhaka', 23.781200, 90.416900),
               ('SMOKE-SR-003', 'BAN-SR-11', 'Banani Road 11', 'Slice Smoke Outlet 3', 'স্লাইস স্মোক আউটলেট ৩', '01799999997',
                'Smoke Road, Banani 11, Dhaka', 23.794100, 90.404700),
               -- Without the Gulshan seed (the test profile keeps the Mirpur fixture): the first outlet stays on MIR-SR-D.
               ('SMOKE-SR-001', 'MIR-SR-D', 'Mirpur 10 Bazar', 'Slice Smoke Outlet', 'স্লাইস স্মোক আউটলেট', '01799999999',
                'Smoke Road, Mirpur 10, Dhaka', 23.815000, 90.368700))
         AS s(code, route, cluster, name, name_bn, phone, address, lat, lng)
  JOIN app.route ro ON ro.code = s.route
  JOIN app.zone z ON z.id = ro.zone_id
  JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = s.cluster
  JOIN app.sub_channel sc ON sc.code = 'GT-GROCERY'
 WHERE NOT EXISTS (SELECT 1 FROM app.outlet x WHERE x.code = s.code)
   AND (s.route <> 'MIR-SR-D' OR NOT EXISTS (SELECT 1 FROM app.route g WHERE g.code = 'GUL-SR-2'));

INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
  SELECT o.id, o.route_id, o.cluster_id, DATE '2026-01-01' FROM app.outlet o
   WHERE o.code LIKE 'SMOKE-SR-%' AND NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id);
INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
  SELECT o.id, o.lat, o.lng, o.location_accuracy_m, 'migration', TIMESTAMPTZ '2026-01-01 00:00:00+06' FROM app.outlet o
   WHERE o.code LIKE 'SMOKE-SR-%' AND NOT EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id);
