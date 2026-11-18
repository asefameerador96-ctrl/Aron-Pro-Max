-- trans_section_summary -> apsis_hist.route_day_vendor (the vendor's own figures, kept as informational evidence). A date outside the plausible
-- window is rejected. psql variables: chunk, vt.
CREATE TEMP TABLE _src ON COMMIT DROP AS
SELECT * FROM (
  SELECT DISTINCT ON (c._row_uuid) c._row_uuid, apsis_map.mysql_date(c.summary_date::text) AS bd, btrim(c.route_code::text) AS route_code, rg.route_id,
         apsis_map.num(c.target_outlets::text)::int AS target_outlets, apsis_map.num(c.visited::text)::int AS visited, apsis_map.num(c.successful_calls::text)::int AS calls
    FROM @C_SUMMARY@ c LEFT JOIN apsis_map.route_geo rg ON rg.code_norm = apsis_map.norm_code(c.route_code)
   WHERE c._chunk = :'chunk' AND c.route_code IS NOT NULL AND btrim(c.route_code::text) <> '' ORDER BY c._row_uuid) x
 WHERE bd IS NOT NULL;
DELETE FROM apsis_hist.reject WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.reject (vendor_table, row_uuid, chunk_key, reason, vendor_date) SELECT :'vt', _row_uuid, :'chunk', 'date_out_of_window', bd FROM _src WHERE NOT apsis_map.in_window(bd);
DELETE FROM _src WHERE NOT apsis_map.in_window(bd);
DELETE FROM apsis_hist.route_day_vendor WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.route_day_vendor (row_uuid, business_date, chunk_key, route_code, route_id, target_outlets, visited, successful_calls)
SELECT _row_uuid, bd, :'chunk', route_code, route_id, target_outlets, visited, calls FROM _src;
