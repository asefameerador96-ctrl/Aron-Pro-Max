-- tso-manual B2: the TSO dashboard fixture day (build contract s4.2 and s5 B2, screenshots 6 to 8), Dhaka business date 2026-10-25
-- (a Sunday). Written to the app tables only; the tests aggregate it with the real worker, so every figure goes through the SQL the
-- cards and the summary read. October 2026: 31 days, five Fridays (2, 9, 16, 23, 30), a global holiday on the 21st (W = 25 at the
-- global and territory node), a zone holiday on the 12th in "Banani - Test" (W = 24 there).
--
-- Territory "Apsis" (T1): zone "Banani - Test" (Z1, house "House A") and zone "Gulshan - Test" (Z2, house "House B").
--   Z1  R1 "Apsis Route"     normal SR, planned, successful login, Bikroy Joma done      target outlets 40
--       R2 "Apsis Route 2"   normal SR, planned, successful login (stand-in SR), no submit target outlets 30
--       R3 "Apsis Route 3"   normal SR, planned, logged in, never downloaded              target outlets 29
--       R4 "AMO Route"       AMO route, planned, logged in            (not a target route: AMO)
--       R5 "Manual Route"    manual route, planned, logged in         (not a target route: manual)
--       R6 "Dummy Route"     dummy BR route, planned, logged in       (not a target route: dummy)
--   Z2  R7 "Excused Route"   normal SR, planned, logged in, approved day exception (not a target route: excused)
--       R8 "Route 8"         normal SR, planned, not started                              target outlets 10
--       R9 "Route 9"         normal SR, planned, vacant (no SR), not started              target outlets 10
-- Territory "Exact" (T9): zone "Exact Zone" (ZX, zone holiday on the 13th: W = 24), route RX (not planned) with a 7-stick monthly
-- target and a sale of 1 stick, 2 lighters and 1 dozen matches: the exact-percentage case (NationalExactPctTest) and three unit
-- families in the national segment card (SegmentShareTest).
--
-- The national route set (D-TSO-MAN-09) is R1, R2, R3, R8, R9: 5 target routes, 2 successful logins, 1 submitted (screenshot 7:
-- 40 % and 20 %), 119 target outlets and 3 successful calls (2.5 %), 4 completed calls all inside the fence (screenshot 6: 100 %).
-- Sales count every route: Cigarette A = 40 (20 + 10 + 5 + 3 + 2; free and DRP lines on R1 never count), Bidi A = 2991 (2990 + 1),
-- Lighter and Match 0; monthly targets Cigarette 42000, Bidi 26000, Lighter 100, Match 175 (s4.2). Value: Cigarette (segment
-- "Medium") 250 000 mtk, Bidi (segment "Bidi") 750 000 mtk of 1 000 000 (7500 bp). Brand CPR: Maxim at 2 of the 3 successful calls,
-- Bidi Gold at 1 (67 % and 33 %); the excused route's Bidi Gold sale is outside the route set.
INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing');
INSERT INTO app.division (code, name, wing_id) VALUES ('D1', 'Dhaka', (SELECT id FROM app.wing WHERE code = 'W1'));
INSERT INTO app.territory (code, name, division_id) VALUES ('T1', 'Apsis', (SELECT id FROM app.division WHERE code = 'D1')), ('T9', 'Exact', (SELECT id FROM app.division WHERE code = 'D1'));
INSERT INTO app.house (code, name, territory_id) SELECT d.c, d.n, t.id FROM (VALUES ('H1', 'House A'), ('H2', 'House B')) d(c, n), app.territory t WHERE t.code = 'T1';
INSERT INTO app.zone (code, name, territory_id, house_id)
  SELECT 'Z1', 'Banani - Test', t.id, h.id FROM app.territory t, app.house h WHERE t.code = 'T1' AND h.code = 'H1'
  UNION ALL SELECT 'Z2', 'Gulshan - Test', t.id, h.id FROM app.territory t, app.house h WHERE t.code = 'T1' AND h.code = 'H2'
  UNION ALL SELECT 'ZX', 'Exact Zone', t.id, NULL FROM app.territory t WHERE t.code = 'T9';
INSERT INTO app.cluster (zone_id, name) SELECT id, 'C ' || code FROM app.zone;
INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask, route_class)
  SELECT d.rc, d.rn, z.id, d.k, 'daily', 127, d.cls FROM (VALUES
    ('R1', 'Apsis Route', 'Z1', 'sr', 'normal'), ('R2', 'Apsis Route 2', 'Z1', 'sr', 'normal'), ('R3', 'Apsis Route 3', 'Z1', 'sr', 'normal'),
    ('R4', 'AMO Route', 'Z1', 'amo', 'normal'), ('R5', 'Manual Route', 'Z1', 'sr', 'manual'), ('R6', 'Dummy Route', 'Z1', 'sr', 'dummy_br'),
    ('R7', 'Excused Route', 'Z2', 'sr', 'normal'), ('R8', 'Route 8', 'Z2', 'sr', 'normal'), ('R9', 'Route 9', 'Z2', 'sr', 'normal'),
    ('RX', 'Exact Route', 'ZX', 'sr', 'normal')) d(rc, rn, zc, k, cls)
  JOIN app.zone z ON z.code = d.zc;

-- People: the route SRs (screenshot 8 names), a stand-in SR on R2, AMOs and a TSO. AMO coverage of Z1 on the day: amo6334 (zone),
-- amo63342 (house A), amo7000 (territory: Z1 and Z2); amo9004 (house B) covers Z2 only. Never listed: amo9001 (disabled), amo9002 (scope
-- ended on the 20th), amo9003 (scope starts on the 26th), tso9100 (a TSO, not an AMO).
INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES
  ('sr334001', 'SR - Testing Banani', 'SR', false), ('sr334002', 'SR - Testing Banani 2', 'SR', false), ('sr334003', 'SR - Testing Banani 3', 'SR', false),
  ('srcover1', 'SR Cover', 'SR', false), ('sr334005', 'SR Manual', 'SR', false), ('sr334006', 'SR Dummy', 'SR', false), ('sr334007', 'SR Excused', 'SR', false),
  ('sr334008', 'SR Route 8', 'SR', false), ('sr334010', 'SR Exact', 'SR', false),
  ('amo6334', 'AMO-6334', 'AMO', false), ('amo63342', 'AMO-63342', 'AMO', false), ('amo7000', 'AMO-7000', 'AMO', false),
  ('amo9002', 'AMO-9002', 'AMO', false), ('amo9003', 'AMO-9003', 'AMO', false), ('amo9004', 'AMO-9004', 'AMO', false),
  ('tso9100', 'TSO Apsis', 'TSO', false), ('tgtadmin', 'Target Admin', 'ADMIN', false);
INSERT INTO app.app_user (username, full_name, role, must_change_password, status, disabled_at) VALUES ('amo9001', 'AMO-9001', 'AMO', false, 'disabled', now());
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, valid_to)
  SELECT u.id, d.t, CASE d.t WHEN 'zone' THEN (SELECT id FROM app.zone WHERE code = d.n) WHEN 'house' THEN (SELECT id FROM app.house WHERE code = d.n)
                             ELSE (SELECT id FROM app.territory WHERE code = d.n) END, d.f::date, d.e::date
    FROM (VALUES ('amo6334', 'zone', 'Z1', '2026-09-01', NULL), ('amo63342', 'house', 'H1', '2026-09-01', NULL), ('amo7000', 'territory', 'T1', '2026-09-01', NULL),
                 ('amo9001', 'zone', 'Z1', '2026-09-01', NULL), ('amo9002', 'zone', 'Z1', '2026-09-01', '2026-10-20'), ('amo9003', 'zone', 'Z1', '2026-10-26', NULL),
                 ('amo9004', 'house', 'H2', '2026-09-01', NULL), ('tso9100', 'territory', 'T1', '2026-09-01', NULL)) d(u, t, n, f, e)
    JOIN app.app_user u ON u.username = d.u;

-- Outlets: R1 O1, O2; R2 O3, O3B, O3C; one each on R4..R9 and RX. OX (RX) is an Astha outlet: Exact Zone sells cigarettes in one
-- channel only (s4.2 By Channel STD "100.00% Astha Channel"); territory Apsis sells GT 30 and Astha 10 sticks (7500 / 2500 bp).
INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
  SELECT d.oc, 'Outlet ' || d.oc, 'Owner', r.zone_id, r.id, (SELECT c.id FROM app.cluster c WHERE c.zone_id = r.zone_id), d.ch
    FROM (VALUES ('O1', 'R1', 'GT'), ('O2', 'R1', 'Astha'), ('O3', 'R2', 'GT'), ('O3B', 'R2', 'GT'), ('O3C', 'R2', 'GT'), ('O4', 'R4', 'GT'), ('O5', 'R5', 'GT'),
                 ('O6', 'R6', 'GT'), ('O7', 'R7', 'GT'), ('O8', 'R8', 'GT'), ('O9', 'R9', 'GT'), ('OX', 'RX', 'Astha')) d(oc, rc, ch)
    JOIN app.route r ON r.code = d.rc;

-- Catalogue: one SKU per category. Segments "Medium" (cigarette) and "Bidi"; brands Maxim and Bidi Gold.
INSERT INTO app.product_node (level, code, name, sort) VALUES ('category', 'CIG', 'Cigarette', 1), ('category', 'BID', 'Bidi', 2), ('category', 'LGT', 'Lighter', 3), ('category', 'MAT', 'Match', 4);
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'segment', d.sc, d.sn, c.id, 1 FROM (VALUES ('SEG-MED', 'Medium', 'CIG'), ('SEG-BID', 'Bidi', 'BID'), ('SEG-LGT', 'Lighter', 'LGT'), ('SEG-MAT', 'Match', 'MAT')) d(sc, sn, cc)
  JOIN app.product_node c ON c.level = 'category' AND c.code = d.cc;
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'brand', d.bc, d.bn, s.id, 1 FROM (VALUES ('B-MAX', 'Maxim', 'SEG-MED'), ('B-BG', 'Bidi Gold', 'SEG-BID'), ('B-FL', 'Flame', 'SEG-LGT'), ('B-DM', 'Dhaka Match', 'SEG-MAT')) d(bc, bn, sc)
  JOIN app.product_node s ON s.level = 'segment' AND s.code = d.sc;
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'variant', d.vc, d.vn, b.id, 1 FROM (VALUES ('V-MR', 'Maxim Regular', 'B-MAX'), ('V-BG', 'Bidi Gold 25', 'B-BG'), ('V-FL', 'Flame Lighter', 'B-FL'), ('V-DM', 'Dhaka Match', 'B-DM')) d(vc, vn, bc)
  JOIN app.product_node b ON b.level = 'brand' AND b.code = d.bc;
INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, sort)
  SELECT d.sc, v.id, d.cat, d.sn, left(d.sn, 20), d.unit, d.bpp, d.unit, 1 FROM (VALUES
    ('MR20', 'V-MR', 'cigarette', 'Maxim Regular 20s', 'stick', 20), ('BG25', 'V-BG', 'bidi', 'Bidi Gold 25', 'stick', 25),
    ('FL1', 'V-FL', 'lighter', 'Flame Lighter', 'piece', 25), ('DM1', 'V-DM', 'match', 'Dhaka Match', 'dozen', 1)) d(sc, vc, cat, sn, unit, bpp)
  JOIN app.product_node v ON v.level = 'variant' AND v.code = d.vc;

-- October targets (s4.2): Cigarette 42000 (R1 20000 + R2 22000), Bidi 26000 (R2), Lighter 100 (R1), Match 175 (R3); RX Cigarette 7.
INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by)
  SELECT '00000000-0000-4000-8000-0000000000b3', decode(repeat('b3', 32), 'hex'), 6, id FROM app.app_user WHERE username = 'tgtadmin';
INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid)
  SELECT r.id, s.id, DATE '2026-10-01', d.q, '00000000-0000-4000-8000-0000000000b3' FROM (VALUES
    ('R1', 'MR20', 20000), ('R2', 'MR20', 22000), ('R2', 'BG25', 26000), ('R1', 'FL1', 100), ('R3', 'DM1', 175), ('RX', 'MR20', 7)) d(rc, sc, q)
  JOIN app.route r ON r.code = d.rc JOIN app.sku s ON s.code = d.sc;

-- Calendar: the global holiday and the two zone holidays.
INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('2026-10-21', 'global', 0, 'holiday', false, 'B2 global holiday');
INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en)
  SELECT d.d::date, 'zone', z.id, 'holiday', false, 'B2 zone holiday' FROM (VALUES ('2026-10-12', 'Z1'), ('2026-10-13', 'ZX')) d(d, zc) JOIN app.zone z ON z.code = d.zc;

-- Route-days of 2026-10-25. Downloads (bundle_count, target_frozen_at) make a login successful together with a check-in.
INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id, acting_user_id, state, target_outlets, logged_in_at, sales_submitted_at,
                           bundle_count, target_frozen_at, last_bundle_at)
  SELECT r.id, DATE '2026-10-25', true, a.id, c.id, d.st, d.t, CASE WHEN d.li THEN TIMESTAMPTZ '2026-10-25 02:00Z' END,
         CASE WHEN d.st = 'sales_submitted' THEN TIMESTAMPTZ '2026-10-25 10:00Z' END,
         CASE WHEN d.dl THEN 1 ELSE 0 END, CASE WHEN d.dl THEN TIMESTAMPTZ '2026-10-25 01:30Z' END, CASE WHEN d.dl THEN TIMESTAMPTZ '2026-10-25 01:30Z' END
    FROM (VALUES ('R1', 'sr334001', NULL, 'sales_submitted', 40, true, true), ('R2', 'sr334002', 'srcover1', 'logged_in', 30, true, true),
                 ('R3', 'sr334003', NULL, 'logged_in', 29, true, false), ('R4', 'amo6334', NULL, 'logged_in', 5, true, true),
                 ('R5', 'sr334005', NULL, 'logged_in', 5, true, true), ('R6', 'sr334006', NULL, 'logged_in', 5, true, true),
                 ('R7', 'sr334007', NULL, 'logged_in', 7, true, true), ('R8', 'sr334008', NULL, 'not_started', 10, false, false),
                 ('R9', NULL, NULL, 'not_started', 10, false, false)) d(rc, au, cu, st, t, li, dl)
    JOIN app.route r ON r.code = d.rc LEFT JOIN app.app_user a ON a.username = d.au LEFT JOIN app.app_user c ON c.username = d.cu;
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-25', u.id, r.id, TIMESTAMPTZ '2026-10-25 02:10Z', 1, 'check_in'
    FROM (VALUES ('R1', 'sr334001'), ('R2', 'srcover1'), ('R3', 'sr334003'), ('R4', 'amo6334'), ('R5', 'sr334005'), ('R6', 'sr334006'), ('R7', 'sr334007')) d(rc, un)
    JOIN app.route r ON r.code = d.rc JOIN app.app_user u ON u.username = d.un;
INSERT INTO app.day_exception (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, reason_code, route_ids, from_date, to_date, status)
  SELECT '00000000-0000-4000-8000-0000000000e7', '00000000-0000-4000-8000-0000000000e7', DATE '2026-10-25', u.id, TIMESTAMPTZ '2026-10-25 01:00Z', 1, 'sick_leave',
         ARRAY[(SELECT id FROM app.route WHERE code = 'R7')], DATE '2026-10-25', DATE '2026-10-25', 'approved'
    FROM app.app_user u WHERE u.username = 'tso9100';

CREATE FUNCTION pg_temp.visit(p_uuid uuid, p_user text, p_route text, p_outlet text, p_seq int, p_opened timestamptz, p_verdict text, p_action text, p_outcome text)
  RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at,
                         sequence_no, planned, fix_is_mock, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action,
                         close_client_uuid, outcome_code, ended_at)
  SELECT p_uuid, p_uuid, DATE '2026-10-25', u.id, r.id, p_opened, 1, 'sr_call', o.id, p_opened, p_seq, true, false, p_verdict, 50, 100, 'master', p_action,
         gen_random_uuid(), p_outcome, p_opened + interval '5 minutes'
    FROM app.app_user u, app.route r, app.outlet o WHERE u.username = p_user AND r.code = p_route AND o.code = p_outlet
$$;
CREATE FUNCTION pg_temp.memo(p_uuid uuid, p_visit uuid, p_user text, p_route text, p_outlet text, p_no text, p_at timestamptz, p_gross bigint, p_lines int)
  RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                        memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                        net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, received_at)
  SELECT p_uuid, p_uuid, DATE '2026-10-25', u.id, r.id, p_at, 1, p_visit, o.id, p_no, 'sale', p_at, DATE '2026-10-01', 'outlet', p_gross, 0, 0, 0, 0,
         p_gross, p_gross, 0, false, p_lines, 0, 0, 'active', p_at + interval '1 hour'
    FROM app.app_user u, app.route r, app.outlet o WHERE u.username = p_user AND r.code = p_route AND o.code = p_outlet
$$;
CREATE FUNCTION pg_temp.line(p_memo uuid, p_no int, p_sku text, p_qty int, p_price bigint, p_kind text) RETURNS void LANGUAGE sql AS $$
  INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id,
                             line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
  SELECT gen_random_uuid(), p_memo, DATE '2026-10-25', m.user_id, m.route_id, m.captured_at, 1, p_memo, p_no, s.id, p_kind, p_qty, s.base_unit, 1, p_qty, 'outlet',
         DATE '2026-10-01', p_price, 1, p_qty * p_price
    FROM app.memo m, app.sku s WHERE m.client_uuid = p_memo AND s.code = p_sku
$$;

-- Calls: R1 two sales and R2 one sale plus one completed no-sale call, all inside the fence (4 of 4); R2's abandoned call (outside)
-- is no call. Outside the route set, each outside the fence: R4 (AMO), R5 (manual), R6 (dummy), R7 (excused).
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2001', 'sr334001', 'R1', 'O1', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'in_range', 'sale_allowed', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2002', 'sr334001', 'R1', 'O2', 2, TIMESTAMPTZ '2026-10-25 05:00Z', 'in_range', 'sale_allowed', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2003', 'srcover1', 'R2', 'O3', 1, TIMESTAMPTZ '2026-10-25 04:30Z', 'in_range', 'sale_allowed', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2004', 'srcover1', 'R2', 'O3B', 2, TIMESTAMPTZ '2026-10-25 05:30Z', 'in_range', 'sale_allowed', 'owner_absent');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2005', 'srcover1', 'R2', 'O3C', 3, TIMESTAMPTZ '2026-10-25 06:30Z', 'out_of_range', 'blocked', 'abandoned');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2006', 'amo6334', 'R4', 'O4', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'out_of_range', 'force_sale', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2007', 'sr334005', 'R5', 'O5', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'out_of_range', 'force_sale', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2008', 'sr334006', 'R6', 'O6', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'out_of_range', 'force_sale', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2009', 'sr334007', 'R7', 'O7', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'out_of_range', 'force_sale', 'sold');
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000b2010', 'sr334010', 'RX', 'OX', 1, TIMESTAMPTZ '2026-10-25 04:00Z', 'in_range', 'sale_allowed', 'sold');

-- Memos: Maxim Regular 6250 mtk a stick, Bidi Gold 250 (2500 for the one-stick memo); free and DRP lines carry no value.
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2101', '00000000-0000-4000-8000-0000000b2001', 'sr334001', 'R1', 'O1', 'sr334001-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 125000, 2);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2101', 1, 'MR20', 20, 6250, 'sale');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2101', 2, 'MR20', 20, 0, 'promo_free');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2102', '00000000-0000-4000-8000-0000000b2002', 'sr334001', 'R1', 'O2', 'sr334001-261025-002', TIMESTAMPTZ '2026-10-25 05:10Z', 62500, 2);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2102', 1, 'MR20', 10, 6250, 'sale');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2102', 2, 'MR20', 10, 0, 'drp_reward');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2103', '00000000-0000-4000-8000-0000000b2003', 'srcover1', 'R2', 'O3', 'srcover1-261025-001', TIMESTAMPTZ '2026-10-25 04:40Z', 747500, 1);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2103', 1, 'BG25', 2990, 250, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2104', '00000000-0000-4000-8000-0000000b2006', 'amo6334', 'R4', 'O4', 'amo6334-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 31250, 1);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2104', 1, 'MR20', 5, 6250, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2105', '00000000-0000-4000-8000-0000000b2007', 'sr334005', 'R5', 'O5', 'sr334005-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 18750, 1);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2105', 1, 'MR20', 3, 6250, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2106', '00000000-0000-4000-8000-0000000b2008', 'sr334006', 'R6', 'O6', 'sr334006-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 12500, 1);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2106', 1, 'MR20', 2, 6250, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2107', '00000000-0000-4000-8000-0000000b2009', 'sr334007', 'R7', 'O7', 'sr334007-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 2500, 1);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2107', 1, 'BG25', 1, 2500, 'sale');
SELECT pg_temp.memo('00000000-0000-4000-8000-0000000b2110', '00000000-0000-4000-8000-0000000b2010', 'sr334010', 'RX', 'OX', 'sr334010-261025-001', TIMESTAMPTZ '2026-10-25 04:10Z', 31250, 3);
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2110', 1, 'MR20', 1, 6250, 'sale');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2110', 2, 'FL1', 2, 10000, 'sale');
SELECT pg_temp.line('00000000-0000-4000-8000-0000000b2110', 3, 'DM1', 1, 5000, 'sale');
