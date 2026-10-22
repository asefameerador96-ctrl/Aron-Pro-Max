-- On top of seed_day.sql (control totals in AggregationTest/DashboardTest): channels, houses, targets, calendar, downloads, final submit.
-- Hand-computed expectations are in NationalDashboardTest. Dhaka business date 2026-10-04; October 2026 has 31 days, five Fridays
-- (2, 9, 16, 23, 30) and one global holiday (20): 25 working days.
UPDATE app.outlet SET channel = 'Astha' WHERE code = 'O2';
UPDATE app.outlet SET channel = 'DCC' WHERE code = 'O3';
UPDATE app.outlet SET channel = 'RCC' WHERE code = 'O4';
INSERT INTO app.house (code, name, territory_id) SELECT 'H1', 'House A', id FROM app.territory;
INSERT INTO app.house (code, name, territory_id) SELECT 'H1D', 'house a', id FROM app.territory;   -- duplicate by name
UPDATE app.zone SET house_id = (SELECT id FROM app.house WHERE code = 'H1') WHERE code = 'Z1';
UPDATE app.zone SET house_id = (SELECT id FROM app.house WHERE code = 'H1D') WHERE code = 'Z2';
INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('2026-10-20', 'global', 0, 'holiday', false, 'Test holiday');
INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by)
  SELECT '00000000-0000-4000-8000-0000000000c1', decode(repeat('ab', 32), 'hex'), 2, u.id FROM app.app_user u WHERE u.username = 'sr001';
INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid)
  SELECT r.id, s.id, DATE '2026-10-01', d.q, '00000000-0000-4000-8000-0000000000c1'
    FROM (VALUES ('R1', 'SKU1', 2500), ('R3', 'SKU1', 1250)) d(rc, sc, q) JOIN app.route r ON r.code = d.rc JOIN app.sku s ON s.code = d.sc;
UPDATE app.route_day SET bundle_count = 1, target_frozen_at = TIMESTAMPTZ '2026-10-04 01:30Z'
  WHERE business_date = DATE '2026-10-04' AND route_id IN (SELECT id FROM app.route WHERE code IN ('R1', 'R2'));
INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via)
  SELECT '00000000-0000-4000-8000-0000000000f1', z.id, DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 11:00Z', 'web'
    FROM app.zone z, app.app_user u WHERE z.code = 'Z2' AND u.username = 'sr003';
-- Check-ins (brief s3.4: a successful login is a check-in AND a download): R1's carries its route, R2's carries none and is matched through
-- sr002's primary assignment (V0108 rule); R3 never checked in.
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from)
  SELECT r.id, u.id, 'primary', DATE '2026-09-01' FROM app.route r, app.app_user u WHERE (r.code, u.username) IN (('R1', 'sr001'), ('R2', 'sr002'), ('R3', 'sr003'));
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind)
  SELECT d.cu::uuid, d.cu::uuid, DATE '2026-10-04', u.id, r.id, d.at::timestamptz, 1, 'check_in'
    FROM (VALUES ('00000000-0000-4000-8000-0000000000a1', 'sr001', 'R1', '2026-10-04 01:40Z'),
                 ('00000000-0000-4000-8000-0000000000a2', 'sr002', NULL, '2026-10-04 01:50Z')) d(cu, un, rc, at)
    JOIN app.app_user u ON u.username = d.un LEFT JOIN app.route r ON r.code = d.rc;
