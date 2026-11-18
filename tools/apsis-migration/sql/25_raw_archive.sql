-- Deferred programmes (docs/27): the vendor row, whole, as jsonb; no behaviour is built on it. psql variables: chunk, vt (vendor table).
SELECT apsis_hist.ensure_partition('raw_archive', d) FROM (SELECT DISTINCT COALESCE(NULLIF(left(r.d::text, 10), '')::date, DATE '1970-01-01') AS d
  FROM @C_RAW@ r WHERE r._chunk = :'chunk') x;
DELETE FROM apsis_hist.raw_archive WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.raw_archive (vendor_table, row_uuid, business_date, chunk_key, data)
SELECT DISTINCT ON (r._row_uuid) :'vt', r._row_uuid,
       COALESCE(CASE WHEN left(r.d::text, 4) IN ('', '0000') THEN NULL ELSE left(r.d::text, 10)::date END, DATE '1970-01-01'),
       :'chunk', r.j
  FROM @C_RAW@ r WHERE r._chunk = :'chunk' ORDER BY r._row_uuid;
