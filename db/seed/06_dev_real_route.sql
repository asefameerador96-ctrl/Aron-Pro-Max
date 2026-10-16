-- Dev only: give the test SR sr1001 two REAL Mirpur routes (AKTCL retailer list of 2026-10-11, loaded on the dev
-- database by tools/data/retailers_to_sql.py; see docs/status/real-data.md) next to the seeded MIR-SR-* routes, so geo
-- validation can be tested on real outlet pins. Both routes sit in zone 5747 "Mirpur 11" (territory 1209 Mirpur,
-- division 66 Mirpur, wing 6 Dhaka North) and the two three-day groups together cover every selling day:
--   16685  Mirpur-10 (Sat, Mon, Wed)    16686  Jute Putty (Sun, Tue, Thu)
-- No data of a person is in this file: only vendor route and zone codes. Every statement is a no-op when the real
-- routes are not loaded (CI, local), so the seed stays safe everywhere; idempotent like the other seed files.

-- The SR is the primary assignee of both routes from 2026-10-01 (open-ended).
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason)
  SELECT r.id, u.id, 'primary', DATE '2026-10-01', 'dev seed: real Mirpur route for geo tests (docs/status/real-data.md)'
    FROM app.route r, app.app_user u
   WHERE r.code IN ('16685', '16686') AND u.username = 'sr1001'
     AND NOT EXISTS (SELECT 1 FROM app.route_assignment x WHERE x.route_id = r.id AND x.kind = 'primary'
                       AND (x.valid_to IS NULL OR x.valid_to > current_date));

-- Planned visit days, effective-dated like db/seed/01 (the planner reads route_planned, else route.visit_days_mask).
INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from)
  SELECT r.id, r.visit_kind, r.visit_days_mask, DATE '2026-10-01' FROM app.route r
   WHERE r.code IN ('16685', '16686') AND NOT EXISTS (SELECT 1 FROM app.route_planned p WHERE p.route_id = r.id);

-- The bundle lists a route's sellable SKUs from its zone's sales plan: the real zone gets the same plan as Z-MIR
-- (every active SKU with a non-zero outlet price, db/seed/03).
INSERT INTO app.sales_plan (zone_id, sku_id, valid_from)
  SELECT z.id, s.id, DATE '2026-10-01' FROM app.zone z, app.sku s
   WHERE z.code = '5747' AND s.status = 'active'
     AND EXISTS (SELECT 1 FROM app.sku_price p WHERE p.sku_id = s.id AND p.price_type = 'outlet' AND p.amount_mtk > 0)
     AND NOT EXISTS (SELECT 1 FROM app.sales_plan p WHERE p.zone_id = z.id AND p.sku_id = s.id AND p.valid_to IS NULL);
