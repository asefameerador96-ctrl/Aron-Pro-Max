-- master_ff_attendance_records -> apsis_hist.attendance, one chunk. Device columns never reach staging. A row with no usable check-in time is
-- counted as skipped. The user's home zone chain is the scope of the row. psql variables: chunk, src_tz.
CREATE TEMP TABLE _src ON COMMIT DROP AS
SELECT * FROM (
  SELECT DISTINCT ON (c._row_uuid)
         c._row_uuid, btrim(c.user_code::text) AS user_code, apsis_map.mysql_ts(c.check_in_at::text, :'src_tz') AS in_utc,
         apsis_map.mysql_ts(c.check_out_at::text, :'src_tz') AS out_utc, apsis_map.num(c.lat::text) AS lat, apsis_map.num(c.lng::text) AS lng
    FROM @C_ATT@ c WHERE c._chunk = :'chunk' AND c.user_code IS NOT NULL AND btrim(c.user_code::text) <> '' ORDER BY c._row_uuid) x
 WHERE in_utc IS NOT NULL;
DELETE FROM apsis_hist.reject WHERE vendor_table = :'vt' AND chunk_key = :'chunk';
INSERT INTO apsis_hist.reject (vendor_table, row_uuid, chunk_key, reason, vendor_date) SELECT :'vt', _row_uuid, :'chunk', 'date_out_of_window', app.dhaka_date(in_utc) FROM _src WHERE NOT apsis_map.in_window(app.dhaka_date(in_utc));
DELETE FROM _src WHERE NOT apsis_map.in_window(app.dhaka_date(in_utc));
SELECT apsis_hist.ensure_partition('attendance', d) FROM (SELECT DISTINCT app.dhaka_date(in_utc) AS d FROM _src) x;
DELETE FROM apsis_hist.attendance WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.attendance (row_uuid, business_date, chunk_key, user_code, user_id, zone_id, territory_id, division_id, wing_id, check_in_at, check_out_at, lat, lng)
SELECT s._row_uuid, app.dhaka_date(s.in_utc), :'chunk', s.user_code, um.user_id, um.home_zone_id, um.territory_id, um.division_id, um.wing_id,
       s.in_utc, s.out_utc, s.lat::double precision, s.lng::double precision
  FROM _src s LEFT JOIN apsis_map.user_map um ON um.code_norm = apsis_map.norm_code(s.user_code);
