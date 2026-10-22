-- Map fixtures on top of seed_day.sql + national_day_extra.sql (same session: pg_temp.memo/line/visit exist).
-- Outlet bases around Gulshan (docs/34 s4.4); visit 2 (O2, force sale) is 81.83 m north of its base, the brief's header example.
UPDATE app.outlet SET lat = 23.7925, lng = 90.4078 WHERE code IN ('O1', 'O2');
UPDATE app.outlet SET lat = 23.7808, lng = 90.4165 WHERE code = 'O3';
UPDATE app.outlet SET lat = 23.7937, lng = 90.4043 WHERE code = 'O4';
ALTER TABLE app.visit DISABLE TRIGGER visit_immutable;
UPDATE app.visit SET outlet_lat = 23.7925, outlet_lng = 90.4078, fix_lat = 23.79251, fix_lng = 90.4078, distance_m = 1.11
 WHERE client_uuid = '00000000-0000-4000-8000-000000000001';
UPDATE app.visit SET outlet_lat = 23.7925, outlet_lng = 90.4078, fix_lat = 23.79323593, fix_lng = 90.4078, distance_m = 81.83,
                     force_photo_uuid = '00000000-0000-4000-8000-0000000000b1'
 WHERE client_uuid = '00000000-0000-4000-8000-000000000002';
UPDATE app.visit SET outlet_lat = 23.7937, outlet_lng = 90.4043, fix_lat = 23.79371, fix_lng = 90.4043, distance_m = 1.11
 WHERE client_uuid = '00000000-0000-4000-8000-000000000004';
ALTER TABLE app.visit ENABLE TRIGGER visit_immutable;

-- A repeat sale at O1 (second active memo of the outlet-day, 5 sticks of SKU1).
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a7', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-005', TIMESTAMPTZ '2026-10-04 08:00Z', 4000, 4000, 1, 'active');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a7', 1, 'SKU1', 5, 800, 'sale');
-- An edited sale at O4: memo a8 replaces a4 (6 sticks of SKU2 instead of 5), with its own edit position.
INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                      memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                      net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, received_at, supersedes_client_uuid, edit_reason_code)
  SELECT '00000000-0000-4000-8000-0000000000a8', '00000000-0000-4000-8000-0000000000a4', DATE '2026-10-04', m.user_id, m.route_id, TIMESTAMPTZ '2026-10-04 05:00Z', 1,
         m.visit_client_uuid, m.outlet_id, 'sr002-261004-002', 'sale', TIMESTAMPTZ '2026-10-04 05:00Z', DATE '2026-10-01', 'outlet', 6000, 0, 0, 0, 0, 6000, 6000, 0, false, 1, 0, 0,
         'active', TIMESTAMPTZ '2026-10-04 05:30Z', m.client_uuid, 'wrong_qty'
    FROM app.memo m WHERE m.client_uuid = '00000000-0000-4000-8000-0000000000a4';
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a8', 1, 'SKU2', 6, 1000, 'sale');
UPDATE app.memo SET status = 'superseded', superseded_by_client_uuid = '00000000-0000-4000-8000-0000000000a8' WHERE client_uuid = '00000000-0000-4000-8000-0000000000a4';

CREATE FUNCTION pg_temp.fix(p_route text, p_user text, p_at timestamptz, p_lat double precision, p_lng double precision, p_slot text, p_src uuid) RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, slot, user_id, route_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider,
                           is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present)
  SELECT DATE '2026-10-04', 'visit', p_src, p_slot, u.id, r.id, p_at, CASE WHEN p_slot = 'edit_fix' THEN 'memo_edit' ELSE 'breadcrumb' END, 'ok', p_lat, p_lng, 10, 'fused',
         false, false, true, false, false, true, false
    FROM app.app_user u, app.route r WHERE u.username = p_user AND r.code = p_route
$$;
SELECT pg_temp.fix('R1', 'sr001', TIMESTAMPTZ '2026-10-04 03:50Z', 23.7925, 90.4078, 'fix', gen_random_uuid());
SELECT pg_temp.fix('R1', 'sr001', TIMESTAMPTZ '2026-10-04 05:10Z', 23.7808, 90.4165, 'fix', gen_random_uuid());
SELECT pg_temp.fix('R1', 'sr001', TIMESTAMPTZ '2026-10-04 06:30Z', 23.7937, 90.4043, 'fix', gen_random_uuid());
SELECT pg_temp.fix('R2', 'sr002', TIMESTAMPTZ '2026-10-04 04:35Z', 23.7937, 90.4043, 'fix', gen_random_uuid());
SELECT pg_temp.fix('R2', 'sr002', TIMESTAMPTZ '2026-10-04 05:00Z', 23.79375, 90.4043, 'edit_fix', '00000000-0000-4000-8000-0000000000a8');
