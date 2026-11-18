-- Masters step: MATCH vendor masters to our masters by business code; never create or overwrite a live master (the
-- October master-data loader creates them: docs/reference/master-data-loader.md). Fills the id map and the lookup tables
-- the fact steps join, stubs outlets we do not have (codes only, no personal data), and writes the gap counts.
-- psql variables: run_id. Templates: @STAGE@ is replaced by the runner with the canonical view names.
TRUNCATE apsis_map.outlet_geo, apsis_map.route_geo, apsis_map.sku_map, apsis_map.user_map;

INSERT INTO apsis_map.route_geo
SELECT r.code, r.id, z.id, t.id, d.id, w.id
  FROM app.route r JOIN app.zone z ON z.id = r.zone_id JOIN app.territory t ON t.id = z.territory_id
  JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id;

INSERT INTO apsis_map.outlet_geo
SELECT o.code, o.id, o.route_id, o.zone_id, t.id, d.id, w.id
  FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.territory t ON t.id = z.territory_id
  JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id;

INSERT INTO apsis_map.sku_map SELECT code, id, base_per_pack FROM app.sku;
INSERT INTO apsis_map.user_map SELECT lower(COALESCE(employee_code, username)), id FROM app.app_user
  ON CONFLICT (user_code) DO NOTHING;

-- id map (vendor key -> our id), one row per matched vendor master row. Idempotent: upsert by (vendor_table, vendor_key).
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_outlets', c.code, 'app.outlet', g.outlet_id, c._row_uuid
  FROM @C_OUTLETS@ c JOIN apsis_map.outlet_geo g ON g.outlet_code = c.code ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_routes', c.code, 'app.route', g.route_id, c._row_uuid
  FROM @C_ROUTES@ c JOIN apsis_map.route_geo g ON g.route_code = c.code ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;
INSERT INTO apsis_map.id_map (vendor_table, vendor_key, our_table, our_id, row_uuid)
SELECT DISTINCT ON (c.code) 'master_sku', c.code, 'app.sku', g.sku_id, c._row_uuid
  FROM @C_SKU@ c JOIN apsis_map.sku_map g ON g.sku_code = c.code ORDER BY c.code, c._row_uuid
ON CONFLICT (vendor_table, vendor_key) DO UPDATE SET our_id = EXCLUDED.our_id, row_uuid = EXCLUDED.row_uuid;

-- outlets of the dump that we do not have: codes only (no name, no phone, no coordinates).
INSERT INTO apsis_hist.outlet_stub (row_uuid, outlet_code, route_code, zone_code, vendor_active)
SELECT DISTINCT ON (c.code) c._row_uuid, c.code, c.route_code, c.zone_code,
       CASE WHEN lower(btrim(c.active::text)) IN ('1','true','active','y','yes') THEN true
            WHEN lower(btrim(c.active::text)) IN ('0','false','inactive','n','no','closed','deleted') THEN false END
  FROM @C_OUTLETS@ c
 WHERE c.code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apsis_map.outlet_geo g WHERE g.outlet_code = c.code)
 ORDER BY c.code, c._row_uuid
ON CONFLICT (outlet_code) DO NOTHING;

-- gap counts (numbers only)
INSERT INTO apsis_ctl.report (run_id, name, value)
SELECT :run_id, 'masters_gap', jsonb_build_object(
  'vendor_outlets',  (SELECT count(DISTINCT code) FROM @C_OUTLETS@),
  'outlets_matched', (SELECT count(*) FROM apsis_map.id_map WHERE vendor_table = 'master_outlets'),
  'outlets_stubbed', (SELECT count(*) FROM apsis_hist.outlet_stub),
  'vendor_routes',   (SELECT count(DISTINCT code) FROM @C_ROUTES@),
  'routes_matched',  (SELECT count(*) FROM apsis_map.id_map WHERE vendor_table = 'master_routes'),
  'vendor_skus',     (SELECT count(DISTINCT code) FROM @C_SKU@),
  'skus_matched',    (SELECT count(*) FROM apsis_map.id_map WHERE vendor_table = 'master_sku'))
ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value;
