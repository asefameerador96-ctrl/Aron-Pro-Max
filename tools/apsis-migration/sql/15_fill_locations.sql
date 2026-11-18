-- Optional (--fill-locations): outlet coordinates from master_geo_config, ONLY where our outlet has none and its basis is not 'provisional' (a field-
-- captured point stays). Inside the Bangladesh bounding box, rounded to 8 decimals (master-data-mapping M01). :confirm = 'true' sets location_confirmed
-- (the owner's switch, M01). Every change is logged with before and after in apsis_map.fill_log; the undo (runbook) touches only rows still holding the
-- filled value. Needs the time-boxed grant apsis_ctl.allow_fill_locations(). psql variables: confirm, run_id.
CREATE TEMP TABLE _fill ON COMMIT DROP AS
SELECT o.id AS outlet_id, o.lat AS p_lat, o.lng AS p_lng, o.location_basis AS p_basis, o.location_confirmed AS p_conf, round(g.lat, 8) AS n_lat, round(g.lng, 8) AS n_lng
  FROM app.outlet o
  JOIN (SELECT DISTINCT ON (apsis_map.norm_code(outlet_code)) apsis_map.norm_code(outlet_code) AS cn, apsis_map.num(lat::text) AS lat, apsis_map.num(lng::text) AS lng
          FROM @C_GEO@ WHERE outlet_code IS NOT NULL ORDER BY apsis_map.norm_code(outlet_code), _row_uuid) g ON g.cn = apsis_map.norm_code(o.code)
 WHERE o.lat IS NULL AND o.lng IS NULL AND COALESCE(o.location_basis, 'none') <> 'provisional'
   AND g.lat BETWEEN 20.5 AND 26.7 AND g.lng BETWEEN 88.0 AND 92.7;
INSERT INTO apsis_map.fill_log (outlet_id, prev_lat, prev_lng, prev_location_basis, prev_location_confirmed, new_lat, new_lng, run_id)
SELECT outlet_id, p_lat, p_lng, p_basis, p_conf, n_lat, n_lng, :run_id FROM _fill ON CONFLICT (outlet_id) DO NOTHING;
UPDATE app.outlet o SET lat = f.n_lat, lng = f.n_lng, location_basis = 'master', location_confirmed = (:'confirm' = 'true')
  FROM _fill f WHERE o.id = f.outlet_id;
