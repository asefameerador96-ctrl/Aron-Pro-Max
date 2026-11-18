-- Optional (--fill-locations): outlet coordinates from master_geo_config, ONLY where our outlet has none. Never overwrites.
-- Inside the Bangladesh bounding box, rounded to 8 decimals (master-data-mapping M01). :confirm = 'true' sets
-- location_confirmed (the owner's switch, M01). Every change is logged in apsis_map.fill_log (rollback). psql variables: confirm, run_id.
INSERT INTO apsis_map.fill_log (outlet_id, prev_location_basis, prev_location_confirmed, run_id)
SELECT o.id, o.location_basis, o.location_confirmed, :run_id FROM app.outlet o
  JOIN (SELECT DISTINCT ON (outlet_code) outlet_code, NULLIF(btrim(lat::text), '')::numeric AS lat, NULLIF(btrim(lng::text), '')::numeric AS lng
          FROM @C_GEO@ ORDER BY outlet_code, _row_uuid) g ON g.outlet_code = o.code
 WHERE o.lat IS NULL AND o.lng IS NULL AND g.lat BETWEEN 20.5 AND 26.7 AND g.lng BETWEEN 88.0 AND 92.7
ON CONFLICT (outlet_id) DO NOTHING;
UPDATE app.outlet o
   SET lat = round(g.lat, 8), lng = round(g.lng, 8), location_basis = 'master',
       location_confirmed = (:'confirm' = 'true')
  FROM (SELECT DISTINCT ON (outlet_code) outlet_code,
               NULLIF(btrim(lat::text), '')::numeric AS lat, NULLIF(btrim(lng::text), '')::numeric AS lng
          FROM @C_GEO@ ORDER BY outlet_code, _row_uuid) g
 WHERE o.code = g.outlet_code AND o.lat IS NULL AND o.lng IS NULL
   AND g.lat BETWEEN 20.5 AND 26.7 AND g.lng BETWEEN 88.0 AND 92.7;
