-- master_ff_attendance_records -> apsis_hist.attendance, one chunk. Device columns never reach staging. psql variables: chunk, src_tz.
CREATE TEMP TABLE _src ON COMMIT DROP AS
SELECT DISTINCT ON (c._row_uuid)
       c._row_uuid, lower(btrim(c.user_code::text)) AS user_code,
       (btrim(c.check_in_at::text)::timestamp AT TIME ZONE :'src_tz') AS in_utc,
       CASE WHEN c.check_out_at IS NULL OR btrim(c.check_out_at::text) = '' OR left(btrim(c.check_out_at::text), 4) = '0000' THEN NULL
            ELSE (btrim(c.check_out_at::text)::timestamp AT TIME ZONE :'src_tz') END AS out_utc,
       NULLIF(btrim(c.lat::text), '')::numeric AS lat, NULLIF(btrim(c.lng::text), '')::numeric AS lng
  FROM @C_ATT@ c
 WHERE c._chunk = :'chunk' AND c.user_code IS NOT NULL AND c.check_in_at IS NOT NULL
   AND btrim(c.check_in_at::text) <> '' AND left(btrim(c.check_in_at::text), 4) <> '0000'
 ORDER BY c._row_uuid;
SELECT apsis_hist.ensure_partition('attendance', d) FROM (SELECT DISTINCT app.dhaka_date(in_utc) AS d FROM _src) x;
DELETE FROM apsis_hist.attendance WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.attendance (row_uuid, business_date, chunk_key, user_code, check_in_at, check_out_at, lat, lng)
SELECT _row_uuid, app.dhaka_date(in_utc), :'chunk', user_code, in_utc, out_utc, lat::double precision, lng::double precision FROM _src;
