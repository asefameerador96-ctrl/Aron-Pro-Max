-- tso-manual B2: the Target Status fixture of build contract s4.3 (screenshots 39-42, D = 2026-10-25). Territory "Apsis" holds zone
-- "Banani - Test" (routes R1, R2: every October target and sale of the screenshot) and zone "Gulshan - Test" (route R3: no October
-- figure, November targets for the per-node n = 0 check); house "House A" groups both zones. Territory "Other" (division "Ctg") holds
-- zone "Agrabad" (route R4): out of the TSO's reach, in the national figures. Achievement is written to dw.agg_daily_route_sku, the
-- aggregator's output that the API reads (sale lines only: free_qty_base never counts).
INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing');
INSERT INTO app.division (code, name, wing_id) VALUES ('D1', 'Dhaka', (SELECT id FROM app.wing WHERE code = 'W1')), ('D2', 'Ctg', (SELECT id FROM app.wing WHERE code = 'W1'));
INSERT INTO app.territory (code, name, division_id) VALUES ('T1', 'Apsis', (SELECT id FROM app.division WHERE code = 'D1')), ('T2', 'Other', (SELECT id FROM app.division WHERE code = 'D2'));
INSERT INTO app.house (code, name, territory_id) SELECT 'H1', 'House A', id FROM app.territory WHERE code = 'T1';
INSERT INTO app.zone (code, name, territory_id, house_id)
  SELECT 'Z1', 'Banani - Test', t.id, h.id FROM app.territory t, app.house h WHERE t.code = 'T1' AND h.code = 'H1'
  UNION ALL SELECT 'Z2', 'Gulshan - Test', t.id, h.id FROM app.territory t, app.house h WHERE t.code = 'T1' AND h.code = 'H1'
  UNION ALL SELECT 'Z3', 'Agrabad', t.id, NULL FROM app.territory t WHERE t.code = 'T2';
INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
  SELECT d.rc, 'Route ' || d.rc, z.id, 'sr', 'daily', 127 FROM (VALUES ('R1', 'Z1'), ('R2', 'Z1'), ('R3', 'Z2'), ('R4', 'Z3')) d(rc, zc) JOIN app.zone z ON z.code = d.zc;
INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('tgtadmin', 'Target Admin', 'ADMIN', false);

-- Catalogue: four categories; Cigarette has eight variants in catalogue order (brand sort, then variant sort), "Marise Special Blend"
-- has two SKUs (20s and 10s) so an item sums its SKUs before it is rounded.
INSERT INTO app.product_node (level, code, name, sort) VALUES ('category', 'CIG', 'Cigarette', 1), ('category', 'BID', 'Bidi', 2), ('category', 'LGT', 'Lighter', 3), ('category', 'MAT', 'Match', 4);
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'segment', 'S-' || c.code, c.name || ' segment', c.id, 1 FROM app.product_node c WHERE c.level = 'category';
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'brand', d.bc, d.bn, s.id, d.so FROM (VALUES ('B-MAX', 'Maxim', 'S-CIG', 1), ('B-MAR', 'Marise', 'S-CIG', 2), ('B-BD', 'Black Diamond', 'S-CIG', 3),
    ('B-SUN', 'Sunmoon', 'S-CIG', 4), ('B-ABL', 'Abul', 'S-BID', 1), ('B-LGT', 'Flame', 'S-LGT', 1), ('B-MAT', 'Dhaka Match', 'S-MAT', 1)) d(bc, bn, sc, so)
  JOIN app.product_node s ON s.level = 'segment' AND s.code = d.sc;
INSERT INTO app.product_node (level, code, name, parent_id, sort)
  SELECT 'variant', d.vc, d.vn, b.id, d.so FROM (VALUES
    ('V-MR', 'Maxim Regular', 'B-MAX', 1), ('V-MB', 'Maxim Bolt', 'B-MAX', 2), ('V-MDB', 'Maxim Double Burst', 'B-MAX', 3),
    ('V-MSB', 'Marise Special Blend', 'B-MAR', 1), ('V-BDF', 'Black Diamond Double Filter', 'B-BD', 1), ('V-BDA', 'Black Diamond Advanced', 'B-BD', 2),
    ('V-SA', 'Sunmoon American', 'B-SUN', 1), ('V-SL', 'Sunmoon Lights', 'B-SUN', 2),
    ('V-AB', 'Abul Bidi', 'B-ABL', 1), ('V-FL', 'Flame Lighter', 'B-LGT', 1), ('V-DM', 'Dhaka Match', 'B-MAT', 1)) d(vc, vn, bc, so)
  JOIN app.product_node b ON b.level = 'brand' AND b.code = d.bc;
INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, sort)
  SELECT d.sc, v.id, d.cat, d.sn, left(d.sn, 20), d.unit, d.bpp, d.unit, d.so FROM (VALUES
    ('MR20', 'V-MR', 'cigarette', 'Maxim Regular 20s', 'stick', 20, 1), ('MB20', 'V-MB', 'cigarette', 'Maxim Bolt 20s', 'stick', 20, 1),
    ('MDB20', 'V-MDB', 'cigarette', 'Maxim Double Burst 20s', 'stick', 20, 1), ('MSB20', 'V-MSB', 'cigarette', 'Marise Special Blend 20s', 'stick', 20, 1),
    ('MSB10', 'V-MSB', 'cigarette', 'Marise Special Blend 10s', 'stick', 10, 2), ('BDF20', 'V-BDF', 'cigarette', 'BD Double Filter 20s', 'stick', 20, 1),
    ('BDA20', 'V-BDA', 'cigarette', 'BD Advanced 20s', 'stick', 20, 1), ('SA20', 'V-SA', 'cigarette', 'Sunmoon American 20s', 'stick', 20, 1),
    ('SL20', 'V-SL', 'cigarette', 'Sunmoon Lights 20s', 'stick', 20, 1), ('AB25', 'V-AB', 'bidi', 'Abul Bidi 25', 'stick', 25, 1),
    ('FL1', 'V-FL', 'lighter', 'Flame Lighter', 'piece', 25, 1), ('DM1', 'V-DM', 'match', 'Dhaka Match', 'dozen', 1, 1)) d(sc, vc, cat, sn, unit, bpp, so)
  JOIN app.product_node v ON v.level = 'variant' AND v.code = d.vc;

-- October targets: the eight Cigarette items 1000, 1000, 10000, 10000, 5000, 6000, 2999, 5000 (T = 40999); Maxim Regular is split over
-- R1 and R2 (600 + 400) and Marise Special Blend over its two SKUs (7000 + 3000). Bidi 11000, Lighter 100, Match 170. R4 (out of reach):
-- Maxim Regular 5000. November: R1 Maxim Regular 3100, R3 Maxim Regular 3000.
INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by)
  SELECT '00000000-0000-4000-8000-0000000000b2', decode(repeat('b2', 32), 'hex'), 16, id FROM app.app_user WHERE username = 'tgtadmin';
INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid)
  SELECT r.id, s.id, d.m::date, d.q, '00000000-0000-4000-8000-0000000000b2' FROM (VALUES
    ('R1', 'MR20', '2026-10-01', 600), ('R2', 'MR20', '2026-10-01', 400), ('R1', 'MB20', '2026-10-01', 1000), ('R2', 'MDB20', '2026-10-01', 10000),
    ('R1', 'MSB20', '2026-10-01', 7000), ('R1', 'MSB10', '2026-10-01', 3000), ('R2', 'BDF20', '2026-10-01', 5000), ('R1', 'BDA20', '2026-10-01', 6000),
    ('R2', 'SA20', '2026-10-01', 2999), ('R1', 'SL20', '2026-10-01', 5000), ('R1', 'AB25', '2026-10-01', 11000), ('R2', 'FL1', '2026-10-01', 100),
    ('R1', 'DM1', '2026-10-01', 170), ('R4', 'MR20', '2026-10-01', 5000), ('R1', 'MR20', '2026-11-01', 3100), ('R3', 'MR20', '2026-11-01', 3000)) d(rc, sc, m, q)
  JOIN app.route r ON r.code = d.rc JOIN app.sku s ON s.code = d.sc;

-- October sales, business dates 1st..25th: Cigarette 350 + 330 + 20 + 200 + 30 + 30 + 40 + 30 = 1030; Bidi 50, Lighter 85, Match 72.
-- Maxim Regular 350 = R1 200 (3 Oct) + R2 150 (25 Oct); Marise 200 = 120 (20s) + 80 (10s). A free quantity and a September sale must not count;
-- R4 sells 999 Maxim Regular (out of reach).
INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, free_qty_base)
  SELECT d.bd::date, r.id, s.id, d.q, d.f FROM (VALUES
    ('2026-10-03', 'R1', 'MR20', 200, 7), ('2026-10-25', 'R2', 'MR20', 150, 0), ('2026-10-10', 'R1', 'MB20', 330, 0), ('2026-10-11', 'R2', 'MDB20', 20, 0),
    ('2026-10-12', 'R1', 'MSB20', 120, 0), ('2026-10-24', 'R1', 'MSB10', 80, 0), ('2026-10-01', 'R2', 'BDF20', 30, 0), ('2026-10-05', 'R1', 'BDA20', 30, 0),
    ('2026-10-06', 'R2', 'SA20', 40, 0), ('2026-10-07', 'R1', 'SL20', 30, 0), ('2026-10-08', 'R1', 'AB25', 50, 5), ('2026-10-13', 'R2', 'FL1', 85, 0),
    ('2026-10-14', 'R1', 'DM1', 72, 0), ('2026-09-30', 'R1', 'MR20', 500, 0), ('2026-10-15', 'R4', 'MR20', 999, 0)) d(bd, rc, sc, q, f)
  JOIN app.route r ON r.code = d.rc JOIN app.sku s ON s.code = d.sc;
INSERT INTO dw.summary_freshness (table_name, last_updated_at, last_event_id) VALUES ('agg_daily_route_sku', TIMESTAMPTZ '2026-10-25 05:30:00Z', 0)
  ON CONFLICT (table_name) DO UPDATE SET last_updated_at = excluded.last_updated_at;
