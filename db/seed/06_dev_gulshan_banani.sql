-- Dev test geography: Gulshan and Banani (owner 2026-10-22, docs/34 s4.4, D-SEED-02; replaces 06_dev_real_route.sql).
-- House H-GUL under T-DHK-N, zones Z-GUL (Gulshan 1 and 2) and Z-BAN (Banani), three SR routes with a Friday-aware
-- schedule (every selling day Saturday to Thursday has exactly one route, Friday none), 60 outlets (20 per route)
-- placed along the streets around Gulshan 2 circle (23.7925 N, 90.4078 E), Gulshan 1 circle (23.7808 N, 90.4165 E)
-- and Banani Road 11 (23.7937 N, 90.4043 E), every outlet channel present, the zones' sales plans, route x SKU monthly
-- targets for the current quarter and the quarter's national holidays. sr1001 works these routes; its Mirpur routes
-- are closed. The seed -> table map for qa's data-flow trace is docs/evidence/seed-gulshan-banani.md.
--
-- PROFILE. The CI and backend test fixture is the Mirpur seed of 01 and 03 (about 250 test assertions key on its
-- codes, schedule and counts), so this file acts only on the Azure dev database. The marker is file 04: the test
-- harnesses and a local `:db:seed` load 04_dev_config_and_codes.sql, the dev deploy never does (infra/deploy.sh removes
-- db/seed/04_*.sql). When 04's config version exists, this file changes nothing. Outlet names are invented, owners and
-- phone numbers synthetic (no data of a real person); positions are plausible street points, not surveyed shops.
-- Idempotent: every insert is guarded, so a second run changes nothing.

DO $seed$
DECLARE
  today date := app.dhaka_date(now());
BEGIN
  IF EXISTS (SELECT 1 FROM app.cfg_version WHERE summary = 'Dev database overrides (docs/24 s9.4, seed)') THEN
    RETURN;   -- test or local profile: the Mirpur fixture stays as the tests expect it
  END IF;

  -- ---------- geography ----------
  INSERT INTO app.house (code, name, name_bn, territory_id)
    SELECT 'H-GUL', 'Gulshan Distribution House', 'গুলশান ডিস্ট্রিবিউশন হাউস', id FROM app.territory
     WHERE code = 'T-DHK-N' AND NOT EXISTS (SELECT 1 FROM app.house WHERE code = 'H-GUL');
  INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
    SELECT z.code, z.name, z.name_bn, t.id, h.id, z.dep FROM app.territory t, app.house h,
           (VALUES ('Z-GUL', 'Gulshan', 'গুলশান', 'Gulshan DEP'), ('Z-BAN', 'Banani', 'বনানী', 'Banani DEP')) AS z(code, name, name_bn, dep)
     WHERE t.code = 'T-DHK-N' AND h.code = 'H-GUL' AND NOT EXISTS (SELECT 1 FROM app.zone x WHERE x.code = z.code);
  INSERT INTO app.cluster (zone_id, name, cluster_type)
    SELECT z.id, c.name, 'market' FROM app.zone z,
           (VALUES ('Z-GUL', 'Gulshan 2 Circle'), ('Z-GUL', 'Gulshan 1 Circle'), ('Z-BAN', 'Banani Road 11')) AS c(zone, name)
     WHERE z.code = c.zone AND NOT EXISTS (SELECT 1 FROM app.cluster x WHERE x.zone_id = z.id AND x.name = c.name);

  -- Friday-aware: three 2F routes, Sat+Tue (1 + 8 = 9), Sun+Wed (2 + 16 = 18), Mon+Thu (4 + 32 = 36); bit0 Sat ... bit6 Fri.
  INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no, route_class)
    SELECT r.code, r.name, r.label, z.id, 'sr', '2f', r.mask, r.seq, 'normal'
      FROM app.zone z, (VALUES ('GUL-SR-2', 'Gulshan 2 (Sat, Tue)', '(Sat, Tue)', 'Z-GUL', 9, 1),
                               ('GUL-SR-1', 'Gulshan 1 (Sun, Wed)', '(Sun, Wed)', 'Z-GUL', 18, 2),
                               ('BAN-SR-11', 'Banani 11 (Mon, Thu)', '(Mon, Thu)', 'Z-BAN', 36, 3)) AS r(code, name, label, zone, mask, seq)
     WHERE z.code = r.zone AND NOT EXISTS (SELECT 1 FROM app.route x WHERE x.code = r.code);
  INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from)
    SELECT r.id, r.visit_kind, r.visit_days_mask, DATE '2026-10-01' FROM app.route r
     WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11') AND NOT EXISTS (SELECT 1 FROM app.route_planned p WHERE p.route_id = r.id);

  INSERT INTO app.sub_channel (channel, code, name, name_bn)
    SELECT s.channel, s.code, s.name, s.name_bn FROM (VALUES ('DCC', 'DCC-1', 'Departmental and convenience', 'ডিপার্টমেন্টাল স্টোর'),
                                                           ('RCC', 'RCC-1', 'Retail chain counter', 'রিটেইল চেইন'),
                                                           ('MT', 'MT-SUPER', 'Super shop', 'সুপার শপ'),
                                                           ('HoReCa', 'HORECA-REST', 'Restaurant and cafe', 'রেস্টুরেন্ট ও ক্যাফে')) AS s(channel, code, name, name_bn)
     WHERE NOT EXISTS (SELECT 1 FROM app.sub_channel x WHERE x.code = s.code);

  -- ---------- 60 outlets: 1..10 along a north-south street, 11..20 along an east-west street from each centre ----------
  INSERT INTO app.outlet (code, name, name_bn, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel,
                          sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed, location_accuracy_m,
                          outlet_kind, price_type, visit_sequence)
  SELECT format('%s-%s', r.prefix, lpad(n::text, 3, '0')),
         nm.en || ' (' || r.area || ')', nm.bn,
         format('Owner %s-%s', r.prefix, n),
         '0170' || lpad((7000000 + r.seq * 1000 + n)::text, 7, '0'),
         format('%s, %s, Dhaka 12%s', CASE WHEN n <= 10 THEN r.street_ns ELSE r.street_ew END, r.area, r.post),
         z.id, ro.id, cl.id, ch.channel, sc.id, 'Urban',
         round((r.lat + CASE WHEN n <= 10 THEN -(n * 0.00058) ELSE 0.00012 * ((n - 11) % 2) END)::numeric, 6)::double precision,
         round((r.lng + CASE WHEN n <= 10 THEN 0.00010 * (n % 2) ELSE (n - 10) * 0.00061 END)::numeric, 6)::double precision,
         'master', true, 8,
         CASE WHEN n = 20 THEN 'wholesale' ELSE 'retail' END,
         CASE WHEN n = 20 THEN 'distributor' ELSE 'outlet' END,
         n
    FROM (VALUES ('GUL2', 'GUL-SR-2', 1, 'Gulshan 2', 'Gulshan 2 Circle', 23.7925, 90.4078, 'Gulshan Avenue', 'Road 90', '12'),
                 ('GUL1', 'GUL-SR-1', 2, 'Gulshan 1', 'Gulshan 1 Circle', 23.7808, 90.4165, 'Gulshan Avenue (south)', 'Road 27', '12'),
                 ('BAN11', 'BAN-SR-11', 3, 'Banani', 'Banani Road 11', 23.7937, 90.4043, 'Kamal Ataturk Avenue', 'Road 11', '13'))
           AS r(prefix, route_code, seq, area, cluster, lat, lng, street_ns, street_ew, post)
    CROSS JOIN generate_series(1, 20) AS n
    -- GT and Astha outlets take a general-store name (rotated per route); the other channels a name of their kind.
    JOIN (VALUES (0, 'Mayer Doa Store', 'মায়ের দোয়া স্টোর'), (1, 'Bismillah Tea Stall', 'বিসমিল্লাহ টি স্টল'),
                 (2, 'Bhai Bhai Store', 'ভাই ভাই স্টোর'), (3, 'Al-Amin General Store', 'আল-আমিন জেনারেল স্টোর'),
                 (4, 'Madina Store', 'মদিনা স্টোর'), (5, 'Rahman Variety Store', 'রহমান ভ্যারাইটি স্টোর'),
                 (6, 'Sotota Store', 'সততা স্টোর'), (7, 'New Haji Store', 'নিউ হাজী স্টোর'),
                 (8, 'Noor Tea Corner', 'নূর টি কর্নার'), (9, 'Bikrampur Store', 'বিক্রমপুর স্টোর'),
                 (10, 'Jononi Store', 'জননী স্টোর'), (11, 'Ma Babar Doa Store', 'মা বাবার দোয়া স্টোর'),
                 (12, 'Sonar Bangla Store', 'সোনার বাংলা স্টোর'), (13, 'Hasan Tea Stall', 'হাসান টি স্টল'),
                 (14, 'Friends Store', 'ফ্রেন্ডস স্টোর'), (15, 'Amanat Store', 'আমানত স্টোর'),
                 (16, 'Rupali Store', 'রূপালী স্টোর'), (17, 'Taqwa Store', 'তাকওয়া স্টোর'),
                 (18, 'Dhaka Tea House', 'ঢাকা টি হাউস'), (19, 'Barakah Store', 'বারাকাহ স্টোর'))
           AS gt(i, en, bn) ON gt.i = (n - 1 + (r.seq - 1) * 7) % 20
    LEFT JOIN (VALUES (7, 1, 'Shapla Departmental Store', 'শাপলা ডিপার্টমেন্টাল স্টোর'), (7, 2, 'Nabil Departmental Store', 'নাবিল ডিপার্টমেন্টাল স্টোর'),
                      (7, 3, 'Tanha Departmental Store', 'তানহা ডিপার্টমেন্টাল স্টোর'),
                      (13, 1, 'Circle Retail Counter', 'সার্কেল রিটেইল কাউন্টার'), (13, 2, 'Lakeside Retail Counter', 'লেকসাইড রিটেইল কাউন্টার'),
                      (13, 3, 'Road 11 Retail Counter', 'রোড ১১ রিটেইল কাউন্টার'),
                      (17, 1, 'City Super Shop', 'সিটি সুপার শপ'), (17, 2, 'Green Super Shop', 'গ্রিন সুপার শপ'),
                      (17, 3, 'Lake Super Shop', 'লেক সুপার শপ'),
                      (19, 1, 'Royal Restaurant', 'রয়্যাল রেস্টুরেন্ট'), (19, 2, 'Lake View Cafe', 'লেক ভিউ ক্যাফে'),
                      (19, 3, 'Kebab House', 'কাবাব হাউস'))
           AS sp(k, seq, en, bn) ON sp.k = n AND sp.seq = r.seq
    CROSS JOIN LATERAL (SELECT coalesce(sp.en, gt.en) AS en, coalesce(sp.bn, gt.bn) AS bn) nm
    CROSS JOIN LATERAL (SELECT CASE WHEN n % 10 = 0 THEN 'Astha'
                                    ELSE coalesce((SELECT channel FROM (VALUES (7, 'DCC'), (13, 'RCC'), (17, 'MT'), (19, 'HoReCa')) AS v(k, channel) WHERE v.k = n), 'GT') END AS channel,
                               CASE WHEN n % 10 = 0 THEN 'ASTHA-1'
                                    ELSE coalesce((SELECT sub FROM (VALUES (7, 'DCC-1'), (13, 'RCC-1'), (17, 'MT-SUPER'), (19, 'HORECA-REST')) AS v(k, sub) WHERE v.k = n),
                                                  CASE WHEN n % 2 = 0 THEN 'GT-TEA' ELSE 'GT-GROCERY' END) END AS sub) ch
    JOIN app.route ro ON ro.code = r.route_code
    JOIN app.zone z ON z.id = ro.zone_id
    JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = r.cluster
    JOIN app.sub_channel sc ON sc.code = ch.sub
   WHERE NOT EXISTS (SELECT 1 FROM app.outlet x WHERE x.code = format('%s-%s', r.prefix, lpad(n::text, 3, '0')));

  INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
    SELECT o.id, o.route_id, o.cluster_id, DATE '2026-10-01' FROM app.outlet o JOIN app.route r ON r.id = o.route_id
     WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11') AND NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id);
  INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
    SELECT o.id, o.lat, o.lng, o.location_accuracy_m, 'migration', TIMESTAMPTZ '2026-10-01 00:00:00+06'
      FROM app.outlet o JOIN app.route r ON r.id = o.route_id
     WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11') AND NOT EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id);

  -- Sales plan: every active SKU with a non-zero outlet price (as Z-MIR in 03).
  INSERT INTO app.sales_plan (zone_id, sku_id, valid_from)
    SELECT z.id, s.id, DATE '2026-10-01' FROM app.zone z, app.sku s
     WHERE z.code IN ('Z-GUL', 'Z-BAN') AND s.status = 'active'
       AND EXISTS (SELECT 1 FROM app.sku_price p WHERE p.sku_id = s.id AND p.price_type = 'outlet' AND p.amount_mtk > 0)
       AND NOT EXISTS (SELECT 1 FROM app.sales_plan p WHERE p.zone_id = z.id AND p.sku_id = s.id AND p.valid_to IS NULL);

  -- ---------- sr1001 moves to Gulshan and Banani; the Mirpur fixture and the real Mirpur routes are closed for it ----------
  UPDATE app.route_assignment a SET valid_to = today, ended_at = now(), reason = coalesce(a.reason, '') || ' | closed by the Gulshan dev seed (D-SEED-02)'
    FROM app.route r, app.app_user u
   WHERE a.route_id = r.id AND a.user_id = u.id AND u.username = 'sr1001' AND a.kind = 'primary'
     AND r.code NOT IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11')
     AND a.valid_from < today AND (a.valid_to IS NULL OR a.valid_to > today);
  INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason)
    SELECT r.id, u.id, 'primary', today, 'dev seed: Gulshan and Banani test geography (D-SEED-02)'
      FROM app.route r, app.app_user u
     WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11') AND u.username = 'sr1001'
       AND NOT EXISTS (SELECT 1 FROM app.route_assignment x WHERE x.route_id = r.id AND x.kind = 'primary'
                         AND (x.valid_to IS NULL OR x.valid_to > today));
  UPDATE app.route SET status = 'inactive' WHERE code IN ('MIR-SR-D', 'MIR-SR-3F', 'MIR-SR-2F') AND status <> 'inactive';
  UPDATE app.app_user SET home_zone_id = (SELECT id FROM app.zone WHERE code = 'Z-GUL')
   WHERE username IN ('sr1001', 'amo1001') AND home_zone_id IS DISTINCT FROM (SELECT id FROM app.zone WHERE code = 'Z-GUL');
  UPDATE app.user_scope s SET valid_to = today
    FROM app.app_user u, app.zone z
   WHERE s.user_id = u.id AND u.username = 'amo1001' AND s.node_type = 'zone' AND s.node_id = z.id AND z.code = 'Z-MIR'
     AND s.valid_from < today AND (s.valid_to IS NULL OR s.valid_to > today);
  INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason)
    SELECT u.id, 'zone', z.id, today, 'dev seed: Gulshan and Banani (D-SEED-02)' FROM app.app_user u, app.zone z
     WHERE u.username = 'amo1001' AND z.code IN ('Z-GUL', 'Z-BAN')
       AND NOT EXISTS (SELECT 1 FROM app.user_scope x WHERE x.user_id = u.id AND x.node_type = 'zone' AND x.node_id = z.id
                         AND (x.valid_to IS NULL OR x.valid_to > today));

  -- ---------- national holidays of the quarter (Oct-Dec 2026) ----------
  -- Durga Puja (Bijoya Dashami) is a lunar date: 2026-10-21 here, AKTCL to confirm against the government list.
  INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, name_bn, reason)
    SELECT h.d, 'global', 0, 'holiday', false, h.en, h.bn, 'dev seed (D-SEED-02)'
      FROM (VALUES (DATE '2026-10-21', 'Durga Puja (Bijoya Dashami)', 'দুর্গাপূজা (বিজয়া দশমী)'),
                   (DATE '2026-12-16', 'Victory Day', 'বিজয় দিবস'),
                   (DATE '2026-12-25', 'Christmas Day', 'বড়দিন')) AS h(d, en, bn)
     WHERE NOT EXISTS (SELECT 1 FROM app.calendar_holiday x WHERE x.date = h.d AND x.scope_type = 'global' AND x.revoked_at IS NULL);

  -- ---------- route x SKU monthly targets, Oct-Dec 2026 (D-TARGET-MIN-01) ----------
  -- "September-like": per route and month 200,000 cigarette sticks (about 7,700 a working day, the brief's national
  -- daily target of 5.36 Cr sticks over 6,933 routes), 30,000 bidi sticks, 500 lighters (20 boxes) and 300 dozen matches,
  -- spread over the zone's plan SKUs with weights 1..3 by catalogue order, rounded to whole packs.
  INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by)
    SELECT md5('gulshan-banani-target-' || m.month)::uuid, sha256(convert_to('dev seed D-SEED-02 ' || m.month, 'UTF8')),
           (SELECT count(*) FROM app.route r JOIN app.sales_plan p ON p.zone_id = r.zone_id AND p.valid_to IS NULL
             WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11')), u.id
      FROM (VALUES (DATE '2026-10-01'), (DATE '2026-11-01'), (DATE '2026-12-01')) AS m(month), app.app_user u
     WHERE u.username = 'superadmin1001'
       AND NOT EXISTS (SELECT 1 FROM app.route_target_batch x WHERE x.batch_uuid = md5('gulshan-banani-target-' || m.month)::uuid);
  INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid, updated_by)
    SELECT w.route_id, w.sku_id, m.month,
           greatest(1, round(w.total::numeric * w.weight / sum(w.weight) OVER (PARTITION BY m.month, w.route_id, w.category_code) / w.base_per_pack))::bigint * w.base_per_pack,
           md5('gulshan-banani-target-' || m.month)::uuid, (SELECT id FROM app.app_user WHERE username = 'superadmin1001')
      FROM (SELECT r.id AS route_id, s.id AS sku_id, s.category_code, s.base_per_pack, 1 + (s.sort % 3) AS weight,
                   CASE s.category_code WHEN 'cigarette' THEN 200000 WHEN 'bidi' THEN 30000 WHEN 'lighter' THEN 500 ELSE 300 END AS total
              FROM app.route r JOIN app.sales_plan p ON p.zone_id = r.zone_id AND p.valid_to IS NULL JOIN app.sku s ON s.id = p.sku_id
             WHERE r.code IN ('GUL-SR-2', 'GUL-SR-1', 'BAN-SR-11')) w
     CROSS JOIN (VALUES (DATE '2026-10-01'), (DATE '2026-11-01'), (DATE '2026-12-01')) AS m(month)
    ON CONFLICT (route_id, sku_id, month) DO NOTHING;
END
$seed$;
