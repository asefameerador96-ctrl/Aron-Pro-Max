-- Raw archive (every table that is not one of the four typed facts): the reviewed vendor row, whole, as jsonb. Nothing a later step may need
-- (programme rows, targets, balances, memo numbers, prices, mappings) is lost except the columns the review dropped as personal or credential.
-- A table WITH a date column: a row whose date is unusable or outside the plausible window is rejected (counted, gated, no partition). A table
-- without a date column (masters, configuration) is archived under the sentinel date 1970-01-01. psql variables: chunk, vt, has_date ('true'|'false').
CREATE TEMP TABLE _raw ON COMMIT DROP AS
SELECT DISTINCT ON (r._row_uuid) r._row_uuid, r.j, apsis_map.mysql_date(r.d) AS bd FROM @C_RAW@ r WHERE r._chunk = :'chunk' ORDER BY r._row_uuid;
DELETE FROM apsis_hist.reject WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.reject (vendor_table, row_uuid, chunk_key, reason, vendor_date)
SELECT :'vt', _row_uuid, :'chunk', CASE WHEN bd IS NULL THEN 'date_unusable' ELSE 'date_out_of_window' END, COALESCE(bd, DATE '1970-01-01')
  FROM _raw WHERE :'has_date' = 'true' AND NOT apsis_map.in_window(bd);
DELETE FROM _raw WHERE :'has_date' = 'true' AND NOT apsis_map.in_window(bd);
UPDATE _raw SET bd = DATE '1970-01-01' WHERE :'has_date' <> 'true';
SELECT apsis_hist.ensure_partition('raw_archive', d) FROM (SELECT DISTINCT bd AS d FROM _raw) x;
DELETE FROM apsis_hist.raw_archive WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.raw_archive (vendor_table, row_uuid, business_date, chunk_key, data) SELECT :'vt', _row_uuid, bd, :'chunk', j FROM _raw;
