-- V0151 dw view contract 1.4 -> 1.5 (AMO manual build, F1; D-AMO-MAN-08, D-AMO-FND-03).
--
-- 1. Validates the four constraints V0150 added NOT VALID (separate transaction, squawk rule; the columns are new, so every
--    row is NULL and the checks cannot fail).
-- 2. dw.fact_visit.force_sale_reason (copied by the worker's visit rebuild from app.visit) and dw.v_visit gains it, placed
--    after geo_action (`is_effective` stays last). A view cannot take a column in the middle, so the view is dropped and
--    recreated; its comments are saved and restored, and the grants are re-applied (app.apply_db_role_grants).
--    Minor version: a column is added, none removed or retyped.
-- There is no dw.v_rubric_criterion (rubric criteria are JSON in app.rubric_version, D-AMO-FND-01), so nothing else changes.
--
-- Behaviour under a running worker: ADD COLUMN on dw.fact_visit (a partitioned table the worker writes) needs ACCESS
-- EXCLUSIVE on it and its partitions; taken by ONE LOCK ... NOWAIT retried for about 60 s (V0144 pattern), never waiting while
-- holding part of the set. The column is nullable without a default: nothing is rewritten. Idempotent.

SET lock_timeout = '5s';

ALTER TABLE app.visit VALIDATE CONSTRAINT visit_force_sale_reason_check;
ALTER TABLE app.visit VALIDATE CONSTRAINT visit_force_sale_reason_needs_force_sale;
ALTER TABLE app.outlet_request_event VALIDATE CONSTRAINT outlet_request_event_route_id_fkey;
ALTER TABLE app.outlet_request_event VALIDATE CONSTRAINT outlet_request_event_cluster_id_fkey;

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE dw.fact_visit IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0151: dw.fact_visit stayed locked for 60 s (is the worker paused?)'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS force_sale_reason text;
COMMENT ON COLUMN dw.fact_visit.force_sale_reason IS 'Why the sale was made outside the geo-fence: internet_problem or location_change (app.visit.force_sale_reason); null unless geo_action is force_sale. Copied by the rebuild.';

-- Save the comments of the view and its columns, drop, recreate, restore.
CREATE TEMP TABLE v_visit_comments ON COMMIT DROP AS
SELECT a.attname::text AS col, col_description('dw.v_visit'::regclass, a.attnum) AS descr
  FROM pg_attribute a WHERE a.attrelid = 'dw.v_visit'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
CREATE TEMP TABLE v_visit_view_comment ON COMMIT DROP AS SELECT obj_description('dw.v_visit'::regclass, 'pg_class') AS descr;

DROP VIEW dw.v_visit;
CREATE VIEW dw.v_visit AS
SELECT business_date, visit_client_uuid, user_id, route_id, zone_id, outlet_id, visit_kind, opened_at, ended_at, outcome_code,
       call_declined, planned, completed, device_verdict, server_verdict, geo_action, force_sale_reason, distance_m,
       server_distance_m, radius_m, within_fence, is_mock, voided, entry_source, geo_key, outlet_key, user_key, updated_at,
       data_origin, (NOT voided) AS is_effective
  FROM dw.fact_visit v;

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT col, descr FROM v_visit_comments WHERE descr IS NOT NULL LOOP
    EXECUTE format('COMMENT ON COLUMN dw.v_visit.%I IS %L', r.col, r.descr);
  END LOOP;
  EXECUTE format('COMMENT ON VIEW dw.v_visit IS %L', (SELECT descr FROM v_visit_view_comment));
END $$;
COMMENT ON COLUMN dw.v_visit.force_sale_reason IS 'Why the sale was made outside the geo-fence: internet_problem or location_change; null unless geo_action is force_sale.';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.5' $$;

SELECT app.apply_db_role_grants();
