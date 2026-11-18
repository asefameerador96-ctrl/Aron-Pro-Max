-- Programme tables (promotion, target; in scope since 2026-11-12, built later from this archive): the reviewed vendor row, whole, as jsonb.
-- A zero or impossible date becomes 1970-01-01 (and the chunk 'unknown' is counted by the runner); nothing aborts on a MySQL zero date.
-- psql variables: chunk, vt (vendor table).
SELECT apsis_hist.ensure_partition('raw_archive', d) FROM (SELECT DISTINCT COALESCE(apsis_map.mysql_date(r.d), DATE '1970-01-01') AS d
  FROM @C_RAW@ r WHERE r._chunk = :'chunk') x;
DELETE FROM apsis_hist.raw_archive WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.raw_archive (vendor_table, row_uuid, business_date, chunk_key, data)
SELECT DISTINCT ON (r._row_uuid) :'vt', r._row_uuid, COALESCE(apsis_map.mysql_date(r.d), DATE '1970-01-01'), :'chunk', r.j
  FROM @C_RAW@ r WHERE r._chunk = :'chunk' ORDER BY r._row_uuid;
