-- The SR slice smoke's own outlet (lead 2026-10-07): one retail outlet on the seeded daily route MIR-SR-D, so the smoke
-- sale (voided at the end of every run) never lands on an outlet a tester uses. Same columns and history rows as
-- db/seed/03_outlets_and_plans.sql; idempotent; dev only (loaded by the dev seed image after db/seed).
INSERT INTO app.outlet (code, name, name_bn, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel,
                        sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed, location_accuracy_m,
                        outlet_kind, price_type, visit_sequence)
SELECT 'SMOKE-SR-001', 'Slice Smoke Outlet', 'স্লাইস স্মোক আউটলেট', 'Smoke Owner', '01799999999', 'Smoke Road, Mirpur 10, Dhaka',
       z.id, ro.id, cl.id, 'GT', sc.id, 'Urban', 23.815000, 90.368700, 'master', true, 8, 'retail', 'outlet', 99
  FROM app.route ro
  JOIN app.zone z ON z.id = ro.zone_id
  JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = 'Mirpur 10 Bazar'
  JOIN app.sub_channel sc ON sc.code = 'GT-GROCERY'
 WHERE ro.code = 'MIR-SR-D' AND NOT EXISTS (SELECT 1 FROM app.outlet x WHERE x.code = 'SMOKE-SR-001');

INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
  SELECT o.id, o.route_id, o.cluster_id, DATE '2026-01-01' FROM app.outlet o
   WHERE o.code = 'SMOKE-SR-001' AND NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id);
INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
  SELECT o.id, o.lat, o.lng, o.location_accuracy_m, 'migration', TIMESTAMPTZ '2026-01-01 00:00:00+06' FROM app.outlet o
   WHERE o.code = 'SMOKE-SR-001' AND NOT EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id);
