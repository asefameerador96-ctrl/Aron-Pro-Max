-- V0175 dw view contract 1.7 -> 1.8 (SR manual build, sr-server train 1; D-SV1-01, D-SV1-02).
-- dw.fact_visit gains zero_sale_reason_code and call_photo_uuid (copied by the worker's visit rebuild from app.visit); dw.v_visit gains
-- zero_sale_reason_code and call_capture_taken (a boolean: a media id is never in a reuse view) after force_sale_reason (`is_effective` stays last). A view cannot take a column in the middle, so
-- it is dropped and recreated; comments are saved and restored, grants re-applied. Minor version: columns added only.
-- Lock: ONE LOCK ... NOWAIT retried for about 60 s (V0144 pattern). Columns are nullable without default: no rewrite. Idempotent.

SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE dw.fact_visit, dw.v_visit IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0175: dw.fact_visit stayed locked for 60 s (is the worker paused?)'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS zero_sale_reason_code text, ADD COLUMN IF NOT EXISTS call_photo_uuid uuid;
COMMENT ON COLUMN dw.fact_visit.zero_sale_reason_code IS 'Why the call ended with a zero sale (code list zero_sale_reason, app.visit.zero_sale_reason_code); null on every other visit.';
COMMENT ON COLUMN dw.fact_visit.call_photo_uuid IS 'media_uuid of the outlet photo taken at call start (app.visit.call_photo_uuid); null when none.';

CREATE TEMP TABLE v_visit_comments ON COMMIT DROP AS
SELECT a.attname::text AS col, col_description('dw.v_visit'::regclass, a.attnum) AS descr
  FROM pg_attribute a WHERE a.attrelid = 'dw.v_visit'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
CREATE TEMP TABLE v_visit_view_comment ON COMMIT DROP AS SELECT obj_description('dw.v_visit'::regclass, 'pg_class') AS descr;

-- squawk-ignore ban-drop-view
DROP VIEW dw.v_visit;
CREATE VIEW dw.v_visit AS
SELECT business_date, visit_client_uuid, user_id, route_id, zone_id, outlet_id, visit_kind, opened_at, ended_at, outcome_code,
       call_declined, planned, completed, device_verdict, server_verdict, geo_action, force_sale_reason, zero_sale_reason_code,
       call_capture_taken, distance_m, server_distance_m, radius_m, within_fence, is_mock, voided, entry_source, geo_key, outlet_key,
       user_key, updated_at, data_origin, (NOT voided) AS is_effective
  FROM (SELECT f.*, (f.call_photo_uuid IS NOT NULL) AS call_capture_taken FROM dw.fact_visit f) v;

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT col, descr FROM v_visit_comments WHERE descr IS NOT NULL LOOP
    EXECUTE format('COMMENT ON COLUMN dw.v_visit.%I IS %L', r.col, r.descr);
  END LOOP;
  EXECUTE format('COMMENT ON VIEW dw.v_visit IS %L', (SELECT descr FROM v_visit_view_comment));
END $$;
COMMENT ON COLUMN dw.v_visit.zero_sale_reason_code IS 'Why the call ended with a zero sale (code list zero_sale_reason); null on every other visit.';
COMMENT ON COLUMN dw.v_visit.call_capture_taken IS 'True when the call has an outlet photo taken at call start (the media id itself is not in a reuse view, like force_photo_uuid).';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.8' $$;

SELECT app.apply_db_role_grants();
