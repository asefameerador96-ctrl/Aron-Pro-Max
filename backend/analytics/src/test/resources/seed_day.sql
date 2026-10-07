-- Control-total fixture for F-SYS-015 (hand-computed in AggregationTest). Dhaka business date 2026-10-04.
INSERT INTO app.wing (code, name) VALUES ('W1','Wing');
INSERT INTO app.division (code, name, wing_id) VALUES ('D1','Div', (SELECT id FROM app.wing));
INSERT INTO app.territory (code, name, division_id) VALUES ('T1','Terr', (SELECT id FROM app.division));
INSERT INTO app.zone (code, name, territory_id) VALUES ('Z1','Zone1', (SELECT id FROM app.territory)), ('Z2','Zone2', (SELECT id FROM app.territory));
INSERT INTO app.cluster (zone_id, name) SELECT id, 'C' || code FROM app.zone;
INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
  SELECT 'R1', 'Route1', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1'
  UNION ALL SELECT 'R2', 'Route2', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1'
  UNION ALL SELECT 'R3', 'Route3', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z2';
INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('sr001','SR One','SR',false), ('sr002','SR Two','SR',false), ('sr003','SR Three','SR',false);
INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
  SELECT 'O' || n, 'Outlet ' || n, 'Owner', r.zone_id, r.id, (SELECT id FROM app.cluster WHERE zone_id = r.zone_id), 'GT'
    FROM (VALUES (1,'R1'),(2,'R1'),(3,'R1'),(4,'R2'),(5,'R3')) o(n, rc) JOIN app.route r ON r.code = o.rc, LATERAL (SELECT o.n) x(n2) WHERE true;
INSERT INTO app.product_node (level, code, name) VALUES ('category','CAT','Cigarettes');
INSERT INTO app.product_node (level, code, name, parent_id) VALUES ('segment','SEG','Premium',(SELECT id FROM app.product_node WHERE level='category'));
INSERT INTO app.product_node (level, code, name, parent_id) VALUES ('brand','B1','BrandOne',(SELECT id FROM app.product_node WHERE level='segment')),
  ('brand','B2','BrandTwo',(SELECT id FROM app.product_node WHERE level='segment'));
INSERT INTO app.product_node (level, code, name, parent_id) VALUES ('variant','V1','Var1',(SELECT id FROM app.product_node WHERE code='B1')),
  ('variant','V2','Var2',(SELECT id FROM app.product_node WHERE code='B2'));
INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
  SELECT 'SKU1', id, 'cigarette', 'Sku One', 'S1', 'stick', 10, 'stick' FROM app.product_node WHERE code='V1'
  UNION ALL SELECT 'SKU2', id, 'cigarette', 'Sku Two', 'S2', 'stick', 10, 'stick' FROM app.product_node WHERE code='V2';

-- Day state: R1 sales_submitted (3 target outlets), R2 logged_in (1), R3 final_submitted (1, zone 2).
INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id, state, target_outlets, logged_in_at, sales_submitted_at, final_submitted_at)
  SELECT r.id, DATE '2026-10-04', true, u.id, d.state, d.t, TIMESTAMPTZ '2026-10-04 02:00Z',
         CASE WHEN d.state IN ('sales_submitted','final_submitted') THEN TIMESTAMPTZ '2026-10-04 10:00Z' END,
         CASE WHEN d.state = 'final_submitted' THEN TIMESTAMPTZ '2026-10-04 11:00Z' END
    FROM (VALUES ('R1','sr001','sales_submitted',3), ('R2','sr002','logged_in',1), ('R3','sr003','final_submitted',1)) d(rc, un, state, t)
    JOIN app.route r ON r.code = d.rc JOIN app.app_user u ON u.username = d.un;

CREATE FUNCTION pg_temp.visit(p_uuid uuid, p_user text, p_route text, p_outlet text, p_seq int, p_opened timestamptz, p_verdict text, p_action text,
                              p_mock boolean, p_outcome text) RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at,
                         sequence_no, planned, fix_is_mock, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action,
                         close_client_uuid, outcome_code, ended_at)
  SELECT p_uuid, p_uuid, DATE '2026-10-04', u.id, r.id, p_opened, 1, 'sr_call', o.id, p_opened, p_seq, true, p_mock, p_verdict, 50, 100, 'master', p_action,
         CASE WHEN p_outcome IS NULL THEN NULL ELSE gen_random_uuid() END, p_outcome, CASE WHEN p_outcome IS NULL THEN NULL ELSE p_opened + interval '5 minutes' END
    FROM app.app_user u, app.route r, app.outlet o WHERE u.username = p_user AND r.code = p_route AND o.code = p_outlet
$$;
CREATE FUNCTION pg_temp.memo(p_uuid uuid, p_visit uuid, p_user text, p_route text, p_outlet text, p_no text, p_at timestamptz, p_gross bigint,
                             p_paid bigint, p_lines int, p_status text) RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                        memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                        net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, voided_by_client_uuid, received_at)
  SELECT p_uuid, p_uuid, DATE '2026-10-04', u.id, r.id, p_at, 1, p_visit, o.id, p_no,
         CASE WHEN p_lines = 0 THEN 'zero_sale' ELSE 'sale' END, p_at, DATE '2026-10-01', 'outlet', p_gross, 0, 0, 0, 0,
         p_gross, p_paid, p_gross - p_paid, p_gross > p_paid, p_lines, 0, 0, p_status, CASE WHEN p_status = 'voided' THEN gen_random_uuid() END, p_at + interval '1 hour'
    FROM app.app_user u, app.route r, app.outlet o WHERE u.username = p_user AND r.code = p_route AND o.code = p_outlet
$$;
CREATE FUNCTION pg_temp.line(p_memo uuid, p_no int, p_sku text, p_qty int, p_price bigint, p_kind text) RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id,
                             line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
  SELECT gen_random_uuid(), p_memo, DATE '2026-10-04', m.user_id, m.route_id, m.captured_at, 1, p_memo, p_no, s.id, p_kind, p_qty, 'stick', 1, p_qty, 'outlet',
         DATE '2026-10-01', p_price, 1, p_qty * p_price
    FROM app.memo m, app.sku s WHERE m.client_uuid = p_memo AND s.code = p_sku
$$;

SELECT pg_temp.visit('00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 1, TIMESTAMPTZ '2026-10-04 04:00Z', 'in_range',     'sale_allowed', false, 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-000000000002', 'sr001', 'R1', 'O2', 2, TIMESTAMPTZ '2026-10-04 05:00Z', 'out_of_range', 'force_sale',   false, 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-000000000003', 'sr001', 'R1', 'O3', 3, TIMESTAMPTZ '2026-10-04 06:00Z', 'mocked',       'blocked',      true,  'abandoned');
SELECT pg_temp.visit('00000000-0000-4000-8000-000000000004', 'sr002', 'R2', 'O4', 1, TIMESTAMPTZ '2026-10-04 04:30Z', 'in_range',     'sale_allowed', false, 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-000000000005', 'sr003', 'R3', 'O5', 1, TIMESTAMPTZ '2026-10-04 07:00Z', 'in_range',     'sale_allowed', false, 'sold');

SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a1', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-001', TIMESTAMPTZ '2026-10-04 04:10Z', 16000, 16000, 1, 'active');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a1', 1, 'SKU1', 20, 800, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a2', '00000000-0000-4000-8000-000000000002', 'sr001', 'R1', 'O2', 'sr001-261004-002', TIMESTAMPTZ '2026-10-04 05:10Z', 18000, 10000, 2, 'active');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a2', 1, 'SKU1', 10, 800, 'sale');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a2', 2, 'SKU2', 10, 1000, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a3', '00000000-0000-4000-8000-000000000003', 'sr001', 'R1', 'O3', 'sr001-261004-003', TIMESTAMPTZ '2026-10-04 06:10Z', 5000, 5000, 1, 'voided');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a3', 1, 'SKU2', 5, 1000, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a6', '00000000-0000-4000-8000-000000000003', 'sr001', 'R1', 'O3', 'sr001-261004-004', TIMESTAMPTZ '2026-10-04 06:20Z', 0, 0, 0, 'active');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a4', '00000000-0000-4000-8000-000000000004', 'sr002', 'R2', 'O4', 'sr002-261004-001', TIMESTAMPTZ '2026-10-04 04:40Z', 5000, 5000, 1, 'active');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a4', 1, 'SKU2', 5, 1000, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000a5', '00000000-0000-4000-8000-000000000005', 'sr003', 'R3', 'O5', 'sr003-261004-001', TIMESTAMPTZ '2026-10-04 07:10Z', 24000, 24000, 1, 'active');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a5', 1, 'SKU1', 30, 800, 'sale');

INSERT INTO app.due_collection (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, outlet_id, against_memo_client_uuid,
                                against_memo_no, against_memo_business_date, amount_mtk, is_full_settlement, outstanding_before_mtk)
  SELECT '00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000d1', DATE '2026-10-04', m.user_id, m.route_id, TIMESTAMPTZ '2026-10-04 08:00Z', 1,
         m.outlet_id, m.client_uuid, m.memo_no, m.business_date, 3000, false, 8000
    FROM app.memo m WHERE m.client_uuid = '00000000-0000-4000-8000-0000000000a2';
INSERT INTO app.stock_movement (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, sku_id, qty_entered, unit_entered, pack_factor, qty_base, slip_printed)
  SELECT c.u, c.u, DATE '2026-10-04', usr.id, r.id, TIMESTAMPTZ '2026-10-04 01:00Z', 1, c.kind, s.id, c.q, 'stick', 1, c.q, false
    FROM (VALUES ('00000000-0000-4000-8000-0000000000e1'::uuid, 'issue', 100), ('00000000-0000-4000-8000-0000000000e2'::uuid, 'return', 10)) c(u, kind, q),
         app.route r, app.app_user usr, app.sku s WHERE r.code = 'R1' AND usr.username = 'sr001' AND s.code = 'SKU1';
