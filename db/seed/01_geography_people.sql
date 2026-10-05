-- Development and test seed (row N-008): one wing > division > territory > house > zone, three SR routes of kinds
-- Daily, 3F and 2F around Mirpur, Dhaka, and the test accounts. Never loaded in production.
-- Idempotent: every statement inserts only what is missing (WHERE NOT EXISTS), so a second run changes nothing.
-- No password is ever committed. The loader (`:db:seed`, SeedLoader) sets the Argon2id hash of ARON_SEED_PASSWORD on
-- the test accounts when that variable is present; without it they have no password until the admin credential reset
-- (POST /v1/admin/users/{id}/credentials). must_change_password is false so acceptance tests get tokens at login.

INSERT INTO app.wing (code, name, name_bn) SELECT 'W-CEN', 'Central Wing', 'সেন্ট্রাল উইং'
  WHERE NOT EXISTS (SELECT 1 FROM app.wing WHERE code = 'W-CEN');
INSERT INTO app.division (code, name, name_bn, wing_id) SELECT 'D-DHK', 'Dhaka Division', 'ঢাকা বিভাগ', id FROM app.wing
  WHERE code = 'W-CEN' AND NOT EXISTS (SELECT 1 FROM app.division WHERE code = 'D-DHK');
INSERT INTO app.territory (code, name, name_bn, division_id) SELECT 'T-DHK-N', 'Dhaka North', 'ঢাকা উত্তর', id FROM app.division
  WHERE code = 'D-DHK' AND NOT EXISTS (SELECT 1 FROM app.territory WHERE code = 'T-DHK-N');
INSERT INTO app.house (code, name, territory_id) SELECT 'H-MIR', 'Mirpur Distribution House', id FROM app.territory
  WHERE code = 'T-DHK-N' AND NOT EXISTS (SELECT 1 FROM app.house WHERE code = 'H-MIR');
INSERT INTO app.zone (code, name, name_bn, territory_id, house_id, dep_name)
  SELECT 'Z-MIR', 'Mirpur', 'মিরপুর', t.id, h.id, 'Mirpur DEP' FROM app.territory t, app.house h
   WHERE t.code = 'T-DHK-N' AND h.code = 'H-MIR' AND NOT EXISTS (SELECT 1 FROM app.zone WHERE code = 'Z-MIR');

INSERT INTO app.cluster (zone_id, name, cluster_type)
  SELECT z.id, c.name, 'market' FROM app.zone z, (VALUES ('Mirpur 10 Bazar'), ('Mirpur 11 Bazar'), ('Pallabi')) AS c(name)
   WHERE z.code = 'Z-MIR' AND NOT EXISTS (SELECT 1 FROM app.cluster x WHERE x.zone_id = z.id AND x.name = c.name);

-- Route kinds: Daily = every day (127); 3F = Sun, Tue, Thu (2 + 8 + 32 = 42); 2F = Mon, Thu (4 + 32 = 36), the
-- groups of docs/22 P-17 and the contract's Route example (bit0 Sat ... bit6 Fri).
INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
  SELECT r.code, r.name, r.label, z.id, 'sr', r.kind, r.mask, r.seq
    FROM app.zone z, (VALUES ('MIR-SR-D', 'Mirpur 10 Daily', 'Daily', 'daily', 127, 1),
                             ('MIR-SR-3F', 'Mirpur 11 (Sun, Tue, Thu)', '(Sun, Tue, Thu)', '3f', 42, 2),
                             ('MIR-SR-2F', 'Pallabi (Mon, Thu)', '(Mon, Thu)', '2f', 36, 3)) AS r(code, name, label, kind, mask, seq)
   WHERE z.code = 'Z-MIR' AND NOT EXISTS (SELECT 1 FROM app.route x WHERE x.code = r.code);
INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from)
  SELECT r.id, r.visit_kind, r.visit_days_mask, DATE '2026-01-01' FROM app.route r
   WHERE r.code LIKE 'MIR-SR-%' AND NOT EXISTS (SELECT 1 FROM app.route_planned p WHERE p.route_id = r.id);

-- Test accounts: one per field role, and the web roles that run the Day 1 to Day 5 checks.
INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password)
  SELECT u.username, u.full_name, u.role, u.designation, u.code, u.locale, z.id, true, false
    FROM app.zone z, (VALUES
      ('sr1001',  'Test SR (Mirpur)',          'SR',         'Sales Representative', 'T-SR-1001',  'bn'),
      ('amo1001', 'Test AMO (Mirpur)',         'AMO',        'Area Market Officer',  'T-AMO-1001', 'bn'),
      ('tso1001', 'Test TSO (Dhaka North)',    'TSO',        'Territory Sales Officer', 'T-TSO-1001', 'bn'),
      ('dmo1001', 'Test DMO (Dhaka)',          'DMO',        'Division Manager',     'T-DMO-1001', 'en'),
      ('admin1001', 'Test Admin',              'ADMIN',      'Administrator',        'T-ADM-1001', 'en'),
      ('superadmin1001', 'Test Super Admin',   'SUPERADMIN', 'Super Administrator',  'T-SAD-1001', 'en'),
      ('support1001', 'Test Support',          'SUPPORT',    'Helpdesk',             'T-SUP-1001', 'en')
    ) AS u(username, full_name, role, designation, code, locale)
   WHERE z.code = 'Z-MIR' AND NOT EXISTS (SELECT 1 FROM app.app_user x WHERE lower(x.username) = u.username);

-- Supervisory reach: the AMO covers the Mirpur zone, the TSO the territory (all its zones), the DMO the division,
-- admins and support national.
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason)
  SELECT u.id, s.node_type, coalesce(z.id, t.id, d.id, 0), DATE '2026-01-01', 'seed'
    FROM (VALUES ('amo1001', 'zone'), ('tso1001', 'territory'), ('dmo1001', 'division'), ('admin1001', 'national'),
                 ('superadmin1001', 'national'), ('support1001', 'national')) AS s(username, node_type)
    JOIN app.app_user u ON u.username = s.username
    LEFT JOIN app.zone z ON s.node_type = 'zone' AND z.code = 'Z-MIR'
    LEFT JOIN app.territory t ON s.node_type = 'territory' AND t.code = 'T-DHK-N'
    LEFT JOIN app.division d ON s.node_type = 'division' AND d.code = 'D-DHK'
   WHERE NOT EXISTS (SELECT 1 FROM app.user_scope x WHERE x.user_id = u.id AND x.node_type = s.node_type);

-- The SR is the primary assignee of all three routes.
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason)
  SELECT r.id, u.id, 'primary', DATE '2026-01-01', 'seed' FROM app.route r, app.app_user u
   WHERE r.code LIKE 'MIR-SR-%' AND u.username = 'sr1001'
     AND NOT EXISTS (SELECT 1 FROM app.route_assignment x WHERE x.route_id = r.id AND x.kind = 'primary');

-- Sub-channels used by the seeded outlets.
INSERT INTO app.sub_channel (channel, code, name, name_bn)
  SELECT s.channel, s.code, s.name, s.name_bn FROM (VALUES ('GT', 'GT-GROCERY', 'Grocery', 'মুদি দোকান'),
                                                         ('GT', 'GT-TEA', 'Tea stall', 'চায়ের দোকান'),
                                                         ('Astha', 'ASTHA-1', 'Astha outlet', 'আস্থা')) AS s(channel, code, name, name_bn)
   WHERE NOT EXISTS (SELECT 1 FROM app.sub_channel x WHERE x.code = s.code);
