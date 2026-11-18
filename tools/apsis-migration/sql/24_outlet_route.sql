-- master_outlet_section_mapping -> apsis_hist.outlet_route (effective-dated history; the live outlet.route_id is not touched). Zero dates -> NULL.
INSERT INTO apsis_hist.outlet_route (row_uuid, outlet_code, route_code, valid_from, valid_to)
SELECT DISTINCT ON (c._row_uuid) c._row_uuid, btrim(c.outlet_code::text), btrim(c.route_code::text),
       apsis_map.mysql_date(c.valid_from::text), apsis_map.mysql_date(c.valid_to::text)
  FROM @C_OUTLET_ROUTE@ c WHERE c.outlet_code IS NOT NULL AND c.route_code IS NOT NULL ORDER BY c._row_uuid
ON CONFLICT (row_uuid) DO UPDATE SET outlet_code = EXCLUDED.outlet_code, route_code = EXCLUDED.route_code,
  valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to;
