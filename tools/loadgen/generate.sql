-- N-054 load-test master data: a synthetic fleet of any size on a SCRATCH database (never production, never a shared
-- dev database). Load into a database that has all migrations and db/seed applied, as a superuser:
--   psql -d aron_load -v ON_ERROR_STOP=1 -v srs=8500 -f tools/loadgen/generate.sql
-- `srs` (default 8500, docs/28: run small in the test account, e.g. -v srs=50) scales everything like the real fleet
-- (docs/22, db/perf/generate.sql): one zone per 8.09 SRs, 3.6 zones per territory, 5.8 territories per division, 1.334
-- routes per SR in the three kinds (Daily 28.6 %, 3F 53.7 %, 2F 17.7 %, P-17 visit-day groups), 30 to 52 outlets
-- per route (mean 41; 8,500 SRs give about 4.65 lakh outlets), 3 clusters per zone, an AMO per zone and a TSO per
-- territory. Every SR gets a bound, active phone (a copy of the seed's dev phone, device_uuid 1d000000-...-<n>) and
-- every synthetic user the seeded SR's password hash, so `tools/loadgen/replay.py` logs in with the seed password
-- (set it first with ARON_SEED_PASSWORD; it is never in this file). Idempotent per run: refuses a second load.
\set ON_ERROR_STOP 1
\if :{?srs}
\else
  \set srs 8500
\endif
SET synchronous_commit = off;
SELECT setseed(0.54);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM app.app_user WHERE username LIKE 'lsr%') THEN RAISE EXCEPTION 'load data already present (lsr* users): use a fresh scratch database'; END IF;
  IF NOT EXISTS (SELECT 1 FROM app.app_user WHERE username = 'sr1001' AND password_hash IS NOT NULL) THEN RAISE EXCEPTION 'set the seed password first (db/seed with ARON_SEED_PASSWORD)'; END IF;
END $$;

CREATE TEMP TABLE lg_size AS
SELECT :srs::int AS srs,
       greatest(1, round(:srs / 8.09))::int AS zones,
       greatest(1, round(:srs / 8.09 / 3.61))::int AS territories,
       greatest(1, round(:srs / 8.09 / 3.61 / 5.82))::int AS divisions,
       greatest(1, round(:srs * 1.3337))::int AS routes;
SELECT * FROM lg_size;

-- ---------- geography (one wing; the seed's W-CEN) ----------
INSERT INTO app.division (code, name, wing_id)
  SELECT 'LD' || d, 'Load division ' || d, (SELECT id FROM app.wing WHERE code = 'W-CEN') FROM lg_size, generate_series(1, divisions) d;
INSERT INTO app.territory (code, name, division_id)
  SELECT 'LT' || t, 'Load territory ' || t, dv.id FROM lg_size, generate_series(1, territories) t
    JOIN app.division dv ON dv.code = 'LD' || (1 + (t - 1) % (SELECT divisions FROM lg_size));
INSERT INTO app.house (code, name, territory_id) SELECT 'LH' || substr(t.code, 3), 'House ' || t.code, t.id FROM app.territory t WHERE t.code LIKE 'LT%';
INSERT INTO app.zone (code, name, territory_id, house_id)
  SELECT 'LZ' || z, 'Load zone ' || z, t.id, h.id FROM lg_size, generate_series(1, zones) z
    JOIN app.territory t ON t.code = 'LT' || (1 + (z - 1) % (SELECT territories FROM lg_size)) JOIN app.house h ON h.territory_id = t.id;
INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT z.id, 'Market ' || c, 'market' FROM app.zone z, generate_series(1, 3) c WHERE z.code LIKE 'LZ%';

-- ---------- routes of the three kinds (bit0 Sat ... bit6 Fri) ----------
CREATE TEMP TABLE lg_route AS
SELECT r, CASE WHEN r % 1000 < 286 THEN 'daily' WHEN r % 1000 < 823 THEN '3f' ELSE '2f' END AS kind
  FROM lg_size, generate_series(1, routes) r;
INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
  SELECT 'LR' || x.r, 'Load route ' || x.r, x.kind, z.id, 'sr', x.kind,
         CASE x.kind WHEN 'daily' THEN 127 WHEN '3f' THEN (ARRAY[42, 21])[1 + x.r % 2] ELSE (ARRAY[36, 18, 9])[1 + x.r % 3] END,
         1 + x.r / (SELECT zones FROM lg_size)
    FROM lg_route x JOIN app.zone z ON z.code = 'LZ' || (1 + (x.r * 7919) % (SELECT zones FROM lg_size));
INSERT INTO app.route_planned (route_id, visit_kind, visit_days_mask, valid_from)
  SELECT id, visit_kind, visit_days_mask, DATE '2026-01-01' FROM app.route WHERE code LIKE 'LR%';

-- ---------- outlets: 30 to 52 per route (mean 41), pins on a grid inside Bangladesh ----------
INSERT INTO app.outlet (code, name, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel, geo_class, lat, lng,
                        location_basis, location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
SELECT 'LO' || r.id || '-' || n, 'Store ' || n, 'Owner ' || n, '019' || lpad(((r.id * 64 + n) % 100000000)::text, 8, '0'), 'Road ' || n,
       r.zone_id, r.id, (SELECT min(c.id) FROM app.cluster c WHERE c.zone_id = r.zone_id),
       CASE WHEN n % 10 = 0 THEN 'Astha' ELSE 'GT' END, 'Urban',
       round((21.0 + (r.id % 500) * 0.008 + n * 0.0004)::numeric, 6), round((88.5 + (r.id / 500) * 0.01 + n * 0.0004)::numeric, 6),
       'master', true, 10, CASE WHEN n = 1 THEN 'wholesale' ELSE 'retail' END, CASE WHEN n = 1 THEN 'distributor' ELSE 'outlet' END, n
  FROM app.route r CROSS JOIN LATERAL generate_series(1, 30 + (r.id * 37) % 23) n
 WHERE r.code LIKE 'LR%';
INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
  SELECT id, route_id, cluster_id, DATE '2026-01-01' FROM app.outlet WHERE code LIKE 'LO%';
INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
  SELECT id, lat, lng, location_accuracy_m, 'migration', TIMESTAMPTZ '2026-01-01 00:00:00+06' FROM app.outlet WHERE code LIKE 'LO%';
INSERT INTO app.sales_plan (zone_id, sku_id, valid_from)
  SELECT z.id, s.id, DATE '2026-01-01' FROM app.zone z, app.sku s
   WHERE z.code LIKE 'LZ%' AND s.status = 'active' AND EXISTS (SELECT 1 FROM app.sku_price p WHERE p.sku_id = s.id AND p.price_type = 'outlet' AND p.amount_mtk > 0);

-- ---------- people: SRs, an AMO per zone, a TSO per territory; the seeded SR's password hash ----------
INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash)
  SELECT 'lsr' || s, 'Load SR ' || s, 'SR', 'Sales Representative', 'L-SR-' || s, 'bn',
         (SELECT z.id FROM app.zone z WHERE z.code = 'LZ' || (1 + (s * 7919) % (SELECT zones FROM lg_size))), false, false,
         (SELECT password_hash FROM app.app_user WHERE username = 'sr1001')
    FROM lg_size, generate_series(1, srs) s;
INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash)
  SELECT 'lamo' || lower(z.code), 'Load AMO ' || z.code, 'AMO', 'Area Market Officer', 'L-AMO-' || z.code, 'bn', z.id, false, false,
         (SELECT password_hash FROM app.app_user WHERE username = 'sr1001') FROM app.zone z WHERE z.code LIKE 'LZ%';
INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, pilot, must_change_password, password_hash)
  SELECT 'ltso' || lower(t.code), 'Load TSO ' || t.code, 'TSO', 'Territory Sales Officer', 'L-TSO-' || t.code, 'bn', false, false,
         (SELECT password_hash FROM app.app_user WHERE username = 'sr1001') FROM app.territory t WHERE t.code LIKE 'LT%';
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason)
  SELECT u.id, 'zone', z.id, DATE '2026-01-01', 'loadgen' FROM app.app_user u JOIN app.zone z ON u.username = 'lamo' || lower(z.code);
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason)
  SELECT u.id, 'territory', t.id, DATE '2026-01-01', 'loadgen' FROM app.app_user u JOIN app.territory t ON u.username = 'ltso' || lower(t.code);
-- Route n goes to SR 1 + (n - 1) % srs: about a third of the SRs hold two routes (1.33 routes per SR).
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason)
  SELECT r.id, u.id, 'primary', DATE '2026-01-01', 'loadgen'
    FROM app.route r JOIN app.app_user u ON u.username = 'lsr' || (1 + (substr(r.code, 3)::int - 1) % (SELECT srs FROM lg_size))
   WHERE r.code LIKE 'LR%';

-- ---------- a bound, active phone per SR (the seed's dev phone as template) ----------
INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk,
                        public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version)
  SELECT ('1d000000-0000-4000-8000-' || lpad(to_hex(s), 12, '0'))::uuid, d.flavour, d.app_package, d.status, d.device_owner, d.lockdown_level,
         d.trust_level, d.public_key_jwk, 'loadgen-' || s, d.attestation_summary, d.app_signing_cert_sha256, u.home_zone_id, d.device_info, d.app_version
    FROM lg_size, generate_series(1, srs) s
    JOIN app.app_user u ON u.username = 'lsr' || s
    CROSS JOIN (SELECT * FROM app.device WHERE device_uuid = '00000000-0000-4000-8000-000000000001') d;
INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via)
  SELECT d.id, u.id, 0, 'support' FROM app.device d
    JOIN app.app_user u ON u.username = 'lsr' || ('x' || lpad(substr(d.device_uuid::text, 25), 16, '0'))::bit(64)::bigint
   WHERE d.device_uuid::text LIKE '1d000000-%';

ANALYZE app.outlet; ANALYZE app.route; ANALYZE app.app_user; ANALYZE app.route_assignment; ANALYZE app.device;
SELECT (SELECT count(*) FROM app.app_user WHERE username LIKE 'l%' AND username ~ '^l(sr|amo|tso)') AS users,
       (SELECT count(*) FROM app.app_user WHERE username LIKE 'lsr%') AS srs,
       (SELECT count(*) FROM app.route WHERE code LIKE 'LR%') AS routes,
       (SELECT count(*) FROM app.outlet WHERE code LIKE 'LO%') AS outlets,
       (SELECT count(*) FROM app.device_binding b JOIN app.device d ON d.id = b.device_id WHERE d.device_uuid::text LIKE '1d000000-%') AS bound_phones;
