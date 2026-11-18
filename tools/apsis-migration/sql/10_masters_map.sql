-- Masters step: MATCH vendor masters to our masters by NORMALISED business code (apsis_map.norm_code: trim, no inner spaces, upper case,
-- leading zeros of a numeric code stripped; applied to both sides). Never creates or overwrites a live master (the October master-data
-- load owns them). Fills the id map and the lookup tables the fact steps join, stubs outlets we do not have (codes only, no personal data),
-- records live codes that collide after normalisation (excluded from matching) and writes the gap counts the gates read.
-- psql variables: run_id. Templates: @C_*@ are replaced by the runner with the canonical staging views.
TRUNCATE apsis_map.outlet_geo, apsis_map.route_geo, apsis_map.sku_map, apsis_map.user_map, apsis_map.code_ambiguous;

INSERT INTO apsis_map.code_ambiguous SELECT 'outlet', 'live', apsis_map.norm_code(code), count(*) FROM app.outlet GROUP BY 3 HAVING count(*) > 1;
INSERT INTO apsis_map.code_ambiguous SELECT 'route', 'live', apsis_map.norm_code(code), count(*) FROM app.route GROUP BY 3 HAVING count(*) > 1;
INSERT INTO apsis_map.code_ambiguous SELECT 'sku', 'live', apsis_map.norm_code(code), count(*) FROM app.sku GROUP BY 3 HAVING count(*) > 1;
INSERT INTO apsis_map.code_ambiguous SELECT 'user', 'live', apsis_map.norm_code(COALESCE(employee_code, username)), count(*) FROM app.app_user GROUP BY 3 HAVING count(*) > 1;
-- VENDOR side: two different vendor codes that normalise to one ('000457' and '457') must never be merged onto one of our masters (a scope leak across
-- territories): both are excluded from matching and counted. A single spelling used consistently is not ambiguous.
INSERT INTO apsis_map.code_ambiguous SELECT 'outlet', 'vendor', n, count(DISTINCT code) FROM (SELECT apsis_map.norm_code(code) AS n, code FROM @C_OUTLETS@ WHERE code IS NOT NULL) x GROUP BY n HAVING count(DISTINCT code) > 1 ON CONFLICT DO NOTHING;
INSERT INTO apsis_map.code_ambiguous SELECT 'route', 'vendor', n, count(DISTINCT code) FROM (SELECT apsis_map.norm_code(code) AS n, code FROM @C_ROUTES@ WHERE code IS NOT NULL) x GROUP BY n HAVING count(DISTINCT code) > 1 ON CONFLICT DO NOTHING;
INSERT INTO apsis_map.code_ambiguous SELECT 'sku', 'vendor', n, count(DISTINCT code) FROM (SELECT apsis_map.norm_code(code) AS n, code FROM @C_SKU@ WHERE code IS NOT NULL) x GROUP BY n HAVING count(DISTINCT code) > 1 ON CONFLICT DO NOTHING;

INSERT INTO apsis_map.route_geo
SELECT apsis_map.norm_code(r.code), r.code, r.id, z.id, t.id, d.id, d.wing_id
  FROM app.route r LEFT JOIN app.zone z ON z.id = r.zone_id LEFT JOIN app.territory t ON t.id = z.territory_id LEFT JOIN app.division d ON d.id = t.division_id
 WHERE apsis_map.norm_code(r.code) NOT IN (SELECT code_norm FROM apsis_map.code_ambiguous WHERE kind = 'route');
INSERT INTO apsis_map.outlet_geo
SELECT apsis_map.norm_code(o.code), o.code, o.id, o.route_id, z.id, t.id, d.id, d.wing_id
  FROM app.outlet o LEFT JOIN app.zone z ON z.id = o.zone_id LEFT JOIN app.territory t ON t.id = z.territory_id LEFT JOIN app.division d ON d.id = t.division_id
 WHERE apsis_map.norm_code(o.code) NOT IN (SELECT code_norm FROM apsis_map.code_ambiguous WHERE kind = 'outlet');
INSERT INTO apsis_map.sku_map
SELECT apsis_map.norm_code(code), code, id, base_per_pack FROM app.sku
 WHERE apsis_map.norm_code(code) NOT IN (SELECT code_norm FROM apsis_map.code_ambiguous WHERE kind = 'sku');
INSERT INTO apsis_map.user_map
SELECT apsis_map.norm_code(COALESCE(u.employee_code, u.username)), u.id, z.id, t.id, d.id, d.wing_id
  FROM app.app_user u LEFT JOIN app.zone z ON z.id = u.home_zone_id LEFT JOIN app.territory t ON t.id = z.territory_id LEFT JOIN app.division d ON d.id = t.division_id
 WHERE apsis_map.norm_code(COALESCE(u.employee_code, u.username)) NOT IN (SELECT code_norm FROM apsis_map.code_ambiguous WHERE kind = 'user');

-- id map (vendor key -> our id), one row per matched vendor master row. Idempotent: upsert by (vendor_table, vendor_key).
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_outlets', c.code, 'app.outlet', g.outlet_id, c._row_uuid
  FROM @C_OUTLETS@ c JOIN apsis_map.outlet_geo g ON g.code_norm = apsis_map.norm_code(c.code) ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_routes', c.code, 'app.route', g.route_id, c._row_uuid
  FROM @C_ROUTES@ c JOIN apsis_map.route_geo g ON g.code_norm = apsis_map.norm_code(c.code) ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_sku', c.code, 'app.sku', g.sku_id, c._row_uuid
  FROM @C_SKU@ c JOIN apsis_map.sku_map g ON g.code_norm = apsis_map.norm_code(c.code) ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;

-- outlets of the dump that we do not have: codes only (no name, no phone, no coordinates).
INSERT INTO apsis_hist.outlet_stub (row_uuid, outlet_code, route_code, zone_code, vendor_active)
SELECT DISTINCT ON (c.code) c._row_uuid, c.code, c.route_code, c.zone_code,
       CASE WHEN lower(btrim(c.active::text)) IN ('1','true','active','y','yes') THEN true
            WHEN lower(btrim(c.active::text)) IN ('0','false','inactive','n','no','closed','deleted') THEN false END
  FROM @C_OUTLETS@ c
 WHERE c.code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apsis_map.outlet_geo g WHERE g.code_norm = apsis_map.norm_code(c.code))
 ORDER BY c.code, c._row_uuid
ON CONFLICT (outlet_code) DO NOTHING;

-- gap counts (numbers only), distinct NORMALISED vendor codes
INSERT INTO apsis_ctl.report (run_id, name, value)
SELECT :run_id, 'masters_gap', jsonb_build_object(
  'vendor_outlets',  (SELECT count(DISTINCT apsis_map.norm_code(code)) FROM @C_OUTLETS@ WHERE code IS NOT NULL),
  'outlets_matched', (SELECT count(DISTINCT apsis_map.norm_code(c.code)) FROM @C_OUTLETS@ c JOIN apsis_map.outlet_geo g ON g.code_norm = apsis_map.norm_code(c.code)),
  'outlets_stubbed', (SELECT count(*) FROM apsis_hist.outlet_stub),
  'vendor_routes',   (SELECT count(DISTINCT apsis_map.norm_code(code)) FROM @C_ROUTES@ WHERE code IS NOT NULL),
  'routes_matched',  (SELECT count(DISTINCT apsis_map.norm_code(c.code)) FROM @C_ROUTES@ c JOIN apsis_map.route_geo g ON g.code_norm = apsis_map.norm_code(c.code)),
  'vendor_skus',     (SELECT count(DISTINCT apsis_map.norm_code(code)) FROM @C_SKU@ WHERE code IS NOT NULL),
  'skus_matched',    (SELECT count(DISTINCT apsis_map.norm_code(c.code)) FROM @C_SKU@ c JOIN apsis_map.sku_map g ON g.code_norm = apsis_map.norm_code(c.code)),
  'live_outlets',    (SELECT count(*) FROM apsis_map.outlet_geo), 'live_routes', (SELECT count(*) FROM apsis_map.route_geo),
  'live_skus',       (SELECT count(*) FROM apsis_map.sku_map),
  'live_codes_ambiguous', (SELECT count(*) FROM apsis_map.code_ambiguous WHERE side = 'live'),
  'vendor_codes_ambiguous', (SELECT count(*) FROM apsis_map.code_ambiguous WHERE side = 'vendor'),
  'live_outlets_without_zone_chain', (SELECT count(*) FROM apsis_map.outlet_geo WHERE wing_id IS NULL))
ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value;
