-- trans_section_summary -> apsis_hist.route_day_vendor (the vendor's own figures, kept for the time-zone and route-day checks). psql variables: chunk.
DELETE FROM apsis_hist.route_day_vendor WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.route_day_vendor (row_uuid, business_date, chunk_key, route_code, route_id, target_outlets, visited, successful_calls)
SELECT * FROM (
  SELECT DISTINCT ON (c._row_uuid) c._row_uuid, apsis_map.mysql_date(c.summary_date::text), :'chunk', btrim(c.route_code::text), rg.route_id,
         apsis_map.num(c.target_outlets::text)::int, apsis_map.num(c.visited::text)::int, apsis_map.num(c.successful_calls::text)::int
    FROM @C_SUMMARY@ c LEFT JOIN apsis_map.route_geo rg ON rg.code_norm = apsis_map.norm_code(c.route_code)
   WHERE c._chunk = :'chunk' AND c.route_code IS NOT NULL AND btrim(c.route_code::text) <> '' ORDER BY c._row_uuid) x
 WHERE x.mysql_date IS NOT NULL;
