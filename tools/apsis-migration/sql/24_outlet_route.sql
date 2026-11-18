-- master_outlet_section_mapping -> apsis_hist.outlet_route (effective-dated history; the live outlet.route_id is not touched).
INSERT INTO apsis_hist.outlet_route (row_uuid, outlet_code, route_code, valid_from, valid_to)
SELECT DISTINCT ON (c._row_uuid) c._row_uuid, btrim(c.outlet_code::text), btrim(c.route_code::text),
       CASE WHEN left(btrim(c.valid_from::text), 4) IN ('', '0000') THEN NULL ELSE left(btrim(c.valid_from::text), 10)::date END,
       CASE WHEN c.valid_to IS NULL OR left(btrim(c.valid_to::text), 4) IN ('', '0000') THEN NULL ELSE left(btrim(c.valid_to::text), 10)::date END
  FROM @C_OUTLET_ROUTE@ c WHERE c.outlet_code IS NOT NULL AND c.route_code IS NOT NULL ORDER BY c._row_uuid
ON CONFLICT (row_uuid) DO UPDATE SET outlet_code = EXCLUDED.outlet_code, route_code = EXCLUDED.route_code,
  valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to;
