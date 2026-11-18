-- trans_section_summary -> apsis_hist.route_day_vendor (the vendor's own figures, for reconciliation only). psql variables: chunk.
DELETE FROM apsis_hist.route_day_vendor WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.route_day_vendor (row_uuid, business_date, chunk_key, route_code, route_id, target_outlets, visited, successful_calls)
SELECT DISTINCT ON (c._row_uuid) c._row_uuid,
       CASE WHEN length(btrim(c.summary_date::text)) = 10 THEN btrim(c.summary_date::text)::date
            ELSE btrim(c.summary_date::text)::timestamp::date END,
       :'chunk', btrim(c.route_code::text), rg.route_id,
       NULLIF(btrim(c.target_outlets::text), '')::numeric::int, NULLIF(btrim(c.visited::text), '')::numeric::int,
       NULLIF(btrim(c.successful_calls::text), '')::numeric::int
  FROM @C_SUMMARY@ c LEFT JOIN apsis_map.route_geo rg ON rg.route_code = btrim(c.route_code::text)
 WHERE c._chunk = :'chunk' AND c.route_code IS NOT NULL AND c.summary_date IS NOT NULL AND left(btrim(c.summary_date::text), 4) <> '0000'
 ORDER BY c._row_uuid;
