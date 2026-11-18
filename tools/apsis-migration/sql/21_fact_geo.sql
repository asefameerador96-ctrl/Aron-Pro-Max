-- live_geo_data_by_outlets -> apsis_hist.visit_geo, one chunk. A row with no usable capture time is not loaded and is counted (rows_skipped).
-- psql variables: chunk, src_tz.
CREATE TEMP TABLE _src ON COMMIT DROP AS
SELECT * FROM (
  SELECT DISTINCT ON (c._row_uuid)
         c._row_uuid, btrim(c.outlet_code::text) AS outlet_code, NULLIF(btrim(c.user_code::text), '') AS user_code,
         NULLIF(btrim(c.route_code::text), '') AS route_code, apsis_map.mysql_ts(c.captured_at::text, :'src_tz') AS at_utc,
         apsis_map.num(c.lat::text) AS lat, apsis_map.num(c.lng::text) AS lng,
         apsis_map.num(c.accuracy_m::text) AS acc, apsis_map.num(c.distance_m::text) AS dist,
         CASE WHEN lower(btrim(c.validated::text)) IN ('1','true','y','yes') THEN true
              WHEN lower(btrim(c.validated::text)) IN ('0','false','n','no') THEN false END AS valid
    FROM @C_GEO_VISIT@ c
   WHERE c._chunk = :'chunk' AND c.outlet_code IS NOT NULL AND btrim(c.outlet_code::text) <> '' ORDER BY c._row_uuid) x
 WHERE at_utc IS NOT NULL;
SELECT apsis_hist.ensure_partition('visit_geo', d) FROM (SELECT DISTINCT app.dhaka_date(at_utc) AS d FROM _src) x;
DELETE FROM apsis_hist.visit_geo WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.visit_geo (row_uuid, business_date, chunk_key, outlet_code, outlet_id, user_code, user_id, route_code, route_id, zone_id,
                                  territory_id, division_id, wing_id, captured_at, lat, lng, accuracy_m, distance_m, geo_validated)
SELECT s._row_uuid, app.dhaka_date(s.at_utc), :'chunk', s.outlet_code, og.outlet_id, s.user_code, um.user_id, s.route_code,
       COALESCE(rg.route_id, og.route_id), COALESCE(rg.zone_id, og.zone_id), COALESCE(rg.territory_id, og.territory_id),
       COALESCE(rg.division_id, og.division_id), COALESCE(rg.wing_id, og.wing_id), s.at_utc,
       s.lat::double precision, s.lng::double precision, s.acc::double precision, s.dist::double precision, s.valid
  FROM _src s LEFT JOIN apsis_map.outlet_geo og ON og.code_norm = apsis_map.norm_code(s.outlet_code)
              LEFT JOIN apsis_map.route_geo rg ON rg.code_norm = apsis_map.norm_code(s.route_code)
              LEFT JOIN apsis_map.user_map um ON um.code_norm = apsis_map.norm_code(s.user_code);
