-- V0116 data origin (data-architecture review 2026-10-25, High 3; lead action row 1, D-DATA-ARCH-REVIEW-01): every row
-- says whether it is real, pilot, test or seed data, so test and real sales never mix in history and the move to the
-- final account can drop test and seed rows and reconcile counts by origin.
--
-- 1. Type app.data_origin, ordered production < pilot < test < seed ("more synthetic" sorts higher, so GREATEST/max of
--    the contributors is the origin of a row built from several).
-- 2. Masters: app.outlet, app.route and app.app_user gain data_origin NOT NULL DEFAULT 'production' (real rows are
--    production unless someone says otherwise; the dev seed tags its own rows, db/seed 01, 03, 06).
-- 3. Capture rows: every transaction table listed in the new registry app.data_origin_capture gains data_origin, set by a
--    BEFORE INSERT trigger (app.stamp_data_origin) that always overwrites what the writer sent (the sync writer copies
--    payload keys into columns, so a phone can never choose its own origin): the most synthetic origin among the row's
--    users (user_id, acting_for_user_id, entered_by_user_id, ...), its outlet and its route, and, for a child row (memo
--    line, discount, void, print, check line, ...), its parent's origin. No user, outlet, route or parent -> production.
--    Ingest needs no change.
--    The value is frozen at capture. Only app.restamp_data_origin(from) re-derives it (after a master is re-tagged, by
--    the seed, and by the move runbook); a guard trigger (app.guard_data_origin) refuses any change of a non-null value
--    unless the role is the table's owner (migrations, the seed, the runbook) or a superuser, so the API, worker and job
--    roles cannot re-tag history even though app.apply_db_role_grants() lets them execute every app function. This
--    holds where the apps sign in with their own logins (dbPerAppLogins, today only the dev test account); where an app
--    signs in as the admin login (dev-lite, stage, prod templates today), that login owns the tables and the guard
--    cannot tell it from a migration (infra row 7 of the review). The
--    write-once guards (guard_synced_row) and the three append-only guards let exactly that column move; app.touch_master
--    no longer bumps version or updated_at when data_origin is the only change.
-- 4. dw: the facts, the route and outlet aggregates and the three dimensions gain data_origin, filled by BEFORE INSERT OR
--    UPDATE triggers in the database, so the projector (backend-reports' Aggregator, explicit column lists) needs no
--    change: a fact takes its source row's origin (fact_memo from app.memo, fact_visit from app.visit, the others from
--    their user/route keys); a route-day or outlet-day aggregate takes the most synthetic of its route (and outlet) and
--    of the memos, visits, stock movements and collections behind it, so a row reads 'production' only when nothing
--    non-production went into it. Zone, hourly-zone and screen-use aggregates carry no origin (no view reads them; a
--    zone has no origin of its own).
-- 5. Every dw.v_* view over captured or master rows gains data_origin as its last column (additive; CREATE OR REPLACE
--    keeps the grants). Per-user views (v_daily_sr, v_geo_integrity) and v_final_submit_log take max() over the rows they
--    group. v_device_command takes the most synthetic user with an active binding on the device (devices have no origin
--    of their own; ops log, not frozen). The calendar views (v_calendar, v_calendar_month, v_working_day) are reference
--    data with no origin and stay unchanged.
-- 6. Backfill: the migration ends with app.restamp_data_origin(NULL), which fills every existing capture and dw row (all
--    production at this point; the dev seed's re-run then tags its masters and restamps). Pilot size: a full pass.
-- No NOT NULL constraint on the capture columns: partitioned parents cannot take NOT VALID constraints (squawk gate)
-- and the trigger never writes NULL; DataOriginTest proves no NULL is left (D-DB-DATA-ORIGIN-01).

SET lock_timeout = '5s';

-- ---------- 1. type ----------
CREATE TYPE app.data_origin AS ENUM ('production', 'pilot', 'test', 'seed');
COMMENT ON TYPE app.data_origin IS 'Origin of a row: production (real), pilot (real, pilot phase), test (made while testing), seed (synthetic fixture). Ordered: GREATEST/max gives the most synthetic contributor.';

-- ---------- 2. masters ----------
ALTER TABLE app.outlet ADD COLUMN data_origin app.data_origin NOT NULL DEFAULT 'production';
ALTER TABLE app.route ADD COLUMN data_origin app.data_origin NOT NULL DEFAULT 'production';
ALTER TABLE app.app_user ADD COLUMN data_origin app.data_origin NOT NULL DEFAULT 'production';
COMMENT ON COLUMN app.outlet.data_origin IS 'Origin of the outlet: production (default), pilot, test (for example the owner''s test outlets) or seed; stamped onto every capture row at this outlet.';
COMMENT ON COLUMN app.route.data_origin IS 'Origin of the route: production (default), pilot, test (for example the Gulshan-Banani test geography) or seed; stamped onto every capture row on this route.';
COMMENT ON COLUMN app.app_user.data_origin IS 'Origin of the account: production (default for real users), pilot, test (test accounts) or seed; stamped onto every capture row of this user.';

-- ---------- 3. capture rows ----------
CREATE TABLE app.data_origin_capture (
  table_name  text PRIMARY KEY,
  parent      text,
  child_col   text,
  parent_col  text,
  pass        smallint NOT NULL DEFAULT 1 CHECK (pass IN (1, 2)),
  CHECK ((parent IS NULL) = (child_col IS NULL) AND (parent IS NULL) = (parent_col IS NULL) AND (parent IS NULL) = (pass = 1))
);
INSERT INTO app.data_origin_capture (table_name) VALUES
  ('attendance_event'), ('route_day_event'), ('day_exception'), ('stock_movement'), ('visit'), ('visit_skip'), ('memo'),
  ('due_collection'), ('due_ledger'), ('survey_response'), ('distribution_check'), ('call_assessment'),
  ('outlet_change_request'), ('task'), ('visit_plan'), ('visit_plan_outlet'), ('leave_application'), ('feedback'),
  ('redemption'), ('gift_photo'), ('gift_assignment'), ('price_compliance_check'), ('sale_abort'), ('indent_movement'),
  ('final_submit'), ('route_day'), ('supervisor_day'), ('qc_entry'), ('qc_summary_entry'), ('risk_signal'), ('geo_fix'),
  ('geo_breadcrumb'), ('media'), ('web_entry_route_day'), ('loyalty_ledger');
INSERT INTO app.data_origin_capture (table_name, parent, child_col, parent_col, pass) VALUES
  ('memo_line', 'memo', 'memo_client_uuid', 'client_uuid', 2),
  ('memo_discount', 'memo', 'memo_client_uuid', 'client_uuid', 2),
  ('memo_void', 'memo', 'memo_client_uuid', 'client_uuid', 2),
  ('print_event', 'memo', 'memo_client_uuid', 'client_uuid', 2),
  ('qc_entry_line', 'memo', 'memo_client_uuid', 'client_uuid', 2),
  ('distribution_check_line', 'distribution_check', 'check_client_uuid', 'client_uuid', 2),
  ('call_assessment_answer', 'call_assessment', 'assessment_client_uuid', 'client_uuid', 2),
  ('redemption_line', 'redemption', 'redemption_client_uuid', 'client_uuid', 2),
  ('web_entry_line', 'web_entry_route_day', 'entry_client_uuid', 'client_uuid', 2),
  ('qc_summary_entry_line', 'qc_summary_entry', 'entry_client_uuid', 'client_uuid', 2),
  ('task_event', 'task', 'task_uuid', 'client_uuid', 2),
  ('feedback_status', 'feedback', 'feedback_client_uuid', 'client_uuid', 2);
COMMENT ON TABLE app.data_origin_capture IS 'Registry of the app tables whose rows carry data_origin (stamped at insert by app.stamp_data_origin, re-derived by app.restamp_data_origin); a child row also takes its parent''s origin.
owner: db | capture: REFERENCE | retention: master | pii: none';
COMMENT ON COLUMN app.data_origin_capture.table_name IS 'App table (schema app) whose rows carry data_origin.';
COMMENT ON COLUMN app.data_origin_capture.parent IS 'Parent app table of a child row (null for a row stamped from its own keys only).';
COMMENT ON COLUMN app.data_origin_capture.child_col IS 'Column of the child row holding the parent''s client UUID.';
COMMENT ON COLUMN app.data_origin_capture.parent_col IS 'Column of the parent the child column points at.';
COMMENT ON COLUMN app.data_origin_capture.pass IS 'Restamp order: 1 = rows stamped from their own keys, 2 = child rows (after their parents).';

-- The most synthetic origin among a row's users, outlet, route and (for a child) parent; production when none is known.
-- n is the row as jsonb (to_jsonb(NEW)); p_users names the user columns (dw facts keyed by user_key pass their own).
CREATE FUNCTION app.derive_data_origin(n jsonb, p_parent text DEFAULT NULL, p_child_col text DEFAULT NULL,
    p_parent_col text DEFAULT NULL,
    p_users text[] DEFAULT ARRAY['user_id', 'acting_for_user_id', 'entered_by_user_id', 'assessed_user_id', 'assigned_user_id',
                                 'acting_user_id', 'assignee_user_id', 'submitted_by', 'entered_by', 'chosen_by_user_id'])
RETURNS app.data_origin
LANGUAGE plpgsql STABLE
AS $$
DECLARE
  users bigint[] := ARRAY(SELECT (n ->> k)::bigint FROM unnest(p_users) k WHERE n ->> k IS NOT NULL);
  o     app.data_origin;
  po    app.data_origin;
  puuid uuid;
BEGIN
  o := GREATEST((SELECT max(u.data_origin) FROM app.app_user u WHERE u.id = ANY (users)),
                (SELECT x.data_origin FROM app.outlet x WHERE x.id = (n ->> 'outlet_id')::bigint),
                (SELECT r.data_origin FROM app.route r WHERE r.id = (n ->> 'route_id')::bigint));
  IF p_parent IS NOT NULL AND n ->> p_child_col IS NOT NULL THEN
    puuid := (n ->> p_child_col)::uuid;
    -- The child's business date first (prunes a partitioned parent to one month), then any date (a void on a later day).
    IF n ->> 'business_date' IS NOT NULL THEN
      EXECUTE format('SELECT data_origin FROM app.%I WHERE %I = $1 AND business_date = $2', p_parent, p_parent_col)
        INTO po USING puuid, (n ->> 'business_date')::date;
    END IF;
    IF po IS NULL THEN
      EXECUTE format('SELECT max(data_origin) FROM app.%I WHERE %I = $1', p_parent, p_parent_col) INTO po USING puuid;
    END IF;
    o := GREATEST(o, po);
  END IF;
  RETURN coalesce(o, 'production');
END $$;
COMMENT ON FUNCTION app.derive_data_origin(jsonb, text, text, text, text[]) IS 'Most synthetic data_origin among a row''s users, outlet, route and parent row (production when none is known).';

-- BEFORE INSERT on every registry table. TG_ARGV = (parent, child_col, parent_col) for a child row, none otherwise.
CREATE FUNCTION app.stamp_data_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_NARGS = 3 THEN
    NEW.data_origin := app.derive_data_origin(to_jsonb(NEW), TG_ARGV[0], TG_ARGV[1], TG_ARGV[2]);
  ELSE
    NEW.data_origin := app.derive_data_origin(to_jsonb(NEW));
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.stamp_data_origin() IS 'BEFORE INSERT: sets data_origin from the row''s users, outlet, route and parent, overwriting any value the writer sent.';

-- True for the table owner (migrations, app.restamp_data_origin) and superusers; false for the API, worker and job roles.
CREATE FUNCTION app.may_restamp_origin(p_rel oid) RETURNS boolean
LANGUAGE sql STABLE
AS $$
  SELECT coalesce((SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user), false)
      OR pg_catalog.pg_has_role(current_user, (SELECT relowner FROM pg_catalog.pg_class WHERE oid = p_rel), 'MEMBER')
$$;
COMMENT ON FUNCTION app.may_restamp_origin(oid) IS 'Whether the current role may change a stamped data_origin (owner of the table or superuser).';

CREATE FUNCTION app.guard_data_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.data_origin IS NOT NULL AND NEW.data_origin IS DISTINCT FROM OLD.data_origin AND NOT app.may_restamp_origin(TG_RELID) THEN
    RAISE EXCEPTION 'app.%: data_origin changes only through app.restamp_data_origin', TG_TABLE_NAME
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.guard_data_origin() IS 'BEFORE UPDATE: a stamped data_origin changes only through app.restamp_data_origin (table owner).';

-- The three append-only tables (due_ledger, loyalty_ledger, qc_summary_entry_line) keep refusing every change except a
-- restamp of data_origin (app.guard_data_origin decides who may).
CREATE FUNCTION app.deny_mutation_except_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' OR (to_jsonb(NEW) - 'data_origin') IS DISTINCT FROM (to_jsonb(OLD) - 'data_origin') THEN
    RAISE EXCEPTION 'table %.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.deny_mutation_except_origin() IS 'Append-only guard that lets only data_origin move (restamp); deletes and every other change are refused.';

DO $$
DECLARE
  r   record;
  def text;
BEGIN
  FOR r IN SELECT * FROM app.data_origin_capture ORDER BY pass, table_name LOOP
    EXECUTE format('ALTER TABLE app.%I ADD COLUMN data_origin app.data_origin', r.table_name);
    EXECUTE format('COMMENT ON COLUMN app.%I.data_origin IS %L', r.table_name,
      'Origin of the row (production, pilot, test, seed): the most synthetic of its users, outlet, route'
      || CASE WHEN r.parent IS NOT NULL THEN ' and parent row (app.' || r.parent || ')' ELSE '' END
      || ', stamped at insert; changes only through app.restamp_data_origin.');
    IF r.parent IS NULL THEN
      EXECUTE format('CREATE TRIGGER %I BEFORE INSERT ON app.%I FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin()',
        r.table_name || '_stamp_origin', r.table_name);
    ELSE
      EXECUTE format('CREATE TRIGGER %I BEFORE INSERT ON app.%I FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin(%L, %L, %L)',
        r.table_name || '_stamp_origin', r.table_name, r.parent, r.child_col, r.parent_col);
    END IF;
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.guard_data_origin()',
      r.table_name || '_guard_origin', r.table_name);
    -- Write-once guards: data_origin joins the columns that may change (the origin guard above decides who may).
    FOR def IN
      SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t
       WHERE t.tgrelid = format('app.%I', r.table_name)::regclass AND t.tgparentid = 0 AND NOT t.tgisinternal
         AND t.tgfoid = 'app.guard_synced_row'::regproc
    LOOP
      IF def !~ '\)$' OR def ~ 'guard_synced_row\(\)$' THEN
        RAISE EXCEPTION 'unexpected guard definition on app.%: %', r.table_name, def;
      END IF;
      EXECUTE regexp_replace(regexp_replace(def, '^CREATE TRIGGER', 'CREATE OR REPLACE TRIGGER'), '\)$', ', ''data_origin'')');
    END LOOP;
    -- Append-only guards: replaced by the variant that lets only data_origin move.
    FOR def IN
      SELECT t.tgname FROM pg_trigger t
       WHERE t.tgrelid = format('app.%I', r.table_name)::regclass AND t.tgparentid = 0 AND NOT t.tgisinternal
         AND t.tgfoid = 'app.deny_mutation'::regproc
    LOOP
      EXECUTE format('CREATE OR REPLACE TRIGGER %I BEFORE UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.deny_mutation_except_origin()',
        def, r.table_name);
    END LOOP;
  END LOOP;
END $$;

-- ---------- 4. dw ----------
-- Facts: TG_ARGV = (source table, fact column, source column) or (user column) or none (standard keys).
CREATE FUNCTION dw.stamp_fact_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  n  jsonb := to_jsonb(NEW);
  so app.data_origin;
BEGIN
  IF TG_NARGS = 3 THEN
    -- A fact with a source row copies that row's frozen origin, so app and dw counts by origin agree even after a
    -- master is re-tagged without a restamp; only a fact whose source is missing falls back to its keys.
    EXECUTE format('SELECT data_origin FROM app.%I WHERE %I = $1 AND business_date = $2', TG_ARGV[0], TG_ARGV[2])
      INTO so USING (n ->> TG_ARGV[1])::uuid, (n ->> 'business_date')::date;
    NEW.data_origin := coalesce(so, app.derive_data_origin(n));
  ELSIF TG_NARGS = 1 THEN
    NEW.data_origin := app.derive_data_origin(n, p_users => ARRAY[TG_ARGV[0]]);
  ELSE
    NEW.data_origin := app.derive_data_origin(n);
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION dw.stamp_fact_origin() IS 'BEFORE INSERT OR UPDATE on a dw fact: data_origin copied from its source app row (memo, visit), else from its user and route keys.';

-- Aggregates: TG_ARGV[0] = 'route' (route-day grain) or 'outlet' (outlet-day grain).
CREATE FUNCTION dw.stamp_agg_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  n jsonb := to_jsonb(NEW);
  rid bigint := (n ->> 'route_id')::bigint;
  oid_ bigint := (n ->> 'outlet_id')::bigint;
  d date := NEW.business_date;
BEGIN
  IF TG_ARGV[0] = 'outlet' THEN
    NEW.data_origin := GREATEST(app.derive_data_origin(n),
      (SELECT max(data_origin) FROM app.memo WHERE outlet_id = oid_ AND business_date = d),
      (SELECT max(data_origin) FROM app.visit WHERE outlet_id = oid_ AND business_date = d),
      (SELECT max(data_origin) FROM app.due_collection WHERE outlet_id = oid_ AND business_date = d));
  ELSE
    NEW.data_origin := GREATEST(app.derive_data_origin(n),
      (SELECT max(data_origin) FROM app.memo WHERE route_id = rid AND business_date = d),
      (SELECT max(data_origin) FROM app.visit WHERE route_id = rid AND business_date = d),
      (SELECT max(data_origin) FROM app.stock_movement WHERE route_id = rid AND business_date = d),
      (SELECT max(data_origin) FROM app.due_collection WHERE route_id = rid AND business_date = d));
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION dw.stamp_agg_origin() IS 'BEFORE INSERT OR UPDATE on a route-day or outlet-day aggregate: the most synthetic origin of its route (and outlet) and of the memos, visits, stock movements and collections behind it.';

-- Dimensions: TG_ARGV[0] = 'route', 'outlet' or 'user'; the master's own origin.
CREATE FUNCTION dw.stamp_dim_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE n jsonb := to_jsonb(NEW);
BEGIN
  NEW.data_origin := coalesce(CASE TG_ARGV[0]
    WHEN 'route' THEN (SELECT data_origin FROM app.route WHERE id = (n ->> 'route_id')::bigint)
    WHEN 'outlet' THEN (SELECT data_origin FROM app.outlet WHERE id = (n ->> 'outlet_id')::bigint)
    WHEN 'user' THEN (SELECT data_origin FROM app.app_user WHERE id = (n ->> 'user_id')::bigint) END, 'production');
  RETURN NEW;
END $$;
COMMENT ON FUNCTION dw.stamp_dim_origin() IS 'BEFORE INSERT OR UPDATE on dim_geo, dim_outlet, dim_user: the master''s data_origin.';

DO $$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('fact_memo', 'fact', ARRAY['memo', 'memo_client_uuid', 'client_uuid'], 'its memo (app.memo)'),
      ('fact_visit', 'fact', ARRAY['visit', 'visit_client_uuid', 'client_uuid'], 'its visit (app.visit)'),
      ('fact_attendance', 'fact', ARRAY[]::text[], 'its user'),
      ('fact_supervisor_day', 'fact', ARRAY[]::text[], 'its user'),
      ('fact_device_day', 'fact', ARRAY[]::text[], 'its user'),
      ('fact_geo_fix', 'fact', ARRAY[]::text[], 'its user and route'),
      ('fact_activity', 'fact', ARRAY['user_key'], 'its user'),
      ('fact_device_integrity', 'fact', ARRAY['user_key'], 'its user'),
      ('fact_consent', 'fact', ARRAY['user_key'], 'its user'),
      ('agg_daily_route', 'agg', ARRAY['route'], 'its route and the memos, visits, stock movements and collections of the route-day'),
      ('agg_daily_route_sku', 'agg', ARRAY['route'], 'its route and the memos, visits, stock movements and collections of the route-day'),
      ('agg_daily_route_brand', 'agg', ARRAY['route'], 'its route and the memos, visits, stock movements and collections of the route-day'),
      ('agg_daily_route_segment', 'agg', ARRAY['route'], 'its route and the memos, visits, stock movements and collections of the route-day'),
      ('agg_daily_route_variant', 'agg', ARRAY['route'], 'its route and the memos, visits, stock movements and collections of the route-day'),
      ('route_last_position', 'agg', ARRAY['route'], 'its route, user and the memos, visits, stock movements and collections of the route-day'),
      ('agg_daily_outlet', 'agg', ARRAY['outlet'], 'its outlet, route and the memos, visits and collections of the outlet-day'),
      ('agg_daily_outlet_sku', 'agg', ARRAY['outlet'], 'its outlet, route and the memos, visits and collections of the outlet-day'),
      ('dim_geo', 'dim', ARRAY['route'], 'the route (app.route.data_origin)'),
      ('dim_outlet', 'dim', ARRAY['outlet'], 'the outlet (app.outlet.data_origin)'),
      ('dim_user', 'dim', ARRAY['user'], 'the user (app.app_user.data_origin)')) AS v(t, kind, args, src)
  LOOP
    EXECUTE format('ALTER TABLE dw.%I ADD COLUMN data_origin app.data_origin', r.t);
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON dw.%I FOR EACH ROW EXECUTE FUNCTION dw.%I(%s)',
      r.t || '_origin', r.t, 'stamp_' || r.kind || '_origin',
      (SELECT coalesce(string_agg(quote_literal(a), ', '), '') FROM unnest(r.args) a));
    EXECUTE format('COMMENT ON COLUMN dw.%I.data_origin IS %L', r.t,
      'Origin (production, pilot, test, seed): the most synthetic of ' || r.src || '; set by trigger on every write.');
  END LOOP;
END $$;

-- SCD2 (V0043 dw.scd2_track): a dimension change opens a new version only when a column the _version table keeps
-- changed. Before, every column of the dimension counted, so data_origin (on dim_geo, dim_outlet, dim_user, not on
-- their _version tables) would have opened a content-identical version at the backfill and at every re-tag, moving
-- geo_key, outlet_key and user_key of later facts for nothing. Same function otherwise.
CREATE OR REPLACE FUNCTION dw.scd2_track() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  k     text := TG_ARGV[0];
  v     text := TG_TABLE_NAME || '_version';
  d     date := app.dhaka_date(now());
  cols  text;
  kept  text[];
  id_   bigint;
  has_cur  boolean;
  cur_from date;
  had_any  boolean;
BEGIN
  IF TG_OP = 'DELETE' THEN
    EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2 AND valid_to IS NULL AND (valid_from IS NULL OR valid_from < $1)', v, k)
      USING d, (to_jsonb(OLD) ->> k)::bigint;
    EXECUTE format('DELETE FROM dw.%I WHERE %I = $1 AND valid_to IS NULL AND valid_from = $2', v, k) USING (to_jsonb(OLD) ->> k)::bigint, d;
    RETURN NULL;
  END IF;
  -- Columns both tables share, so a column added later to the dimension alone never breaks the projector's upsert
  -- and never opens a version.
  SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum), array_agg(a.attname::text) INTO cols, kept
    FROM pg_attribute a
   WHERE a.attrelid = TG_RELID AND a.attnum > 0 AND NOT a.attisdropped
     AND EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = ('dw.' || v)::regclass AND b.attname = a.attname
                   AND b.attnum > 0 AND NOT b.attisdropped AND b.attgenerated = '');
  id_ := (to_jsonb(NEW) ->> k)::bigint;
  IF TG_OP = 'UPDATE'
     AND (SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(to_jsonb(NEW)) e WHERE e.key = ANY (kept) AND e.key <> 'updated_at')
         = (SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(to_jsonb(OLD)) e WHERE e.key = ANY (kept) AND e.key <> 'updated_at') THEN
    RETURN NULL;                                                   -- no versioned column moved: no new version
  END IF;
  EXECUTE format('SELECT true, valid_from FROM dw.%I WHERE %I = $1 AND valid_to IS NULL', v, k) INTO has_cur, cur_from USING id_;
  EXECUTE format('SELECT EXISTS (SELECT 1 FROM dw.%I WHERE %I = $1)', v, k) INTO had_any USING id_;
  IF has_cur AND cur_from >= d THEN
    -- A second change on the same business date rewrites that date's version (also when the clock stepped back).
    EXECUTE format('UPDATE dw.%1$I SET (%2$s) = (SELECT %2$s FROM jsonb_populate_record(NULL::dw.%3$I, $1)) WHERE %4$I = $2 AND valid_to IS NULL',
                   v, cols, TG_TABLE_NAME, k) USING to_jsonb(NEW), id_;
  ELSE
    EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2 AND valid_to IS NULL', v, k) USING d, id_;
    EXECUTE format('INSERT INTO dw.%1$I (%2$s, valid_from) SELECT %2$s, $2 FROM jsonb_populate_record(NULL::dw.%3$I, $1)',
                   v, cols, TG_TABLE_NAME) USING to_jsonb(NEW), CASE WHEN had_any THEN d END;    -- the first version ever is valid since ever
  END IF;
  RETURN NULL;
END $$;

-- touch_master (V0001): a write that changes only data_origin (a re-tag of a master, a restamp of route_day,
-- supervisor_day or gift_assignment) is classification, not an edit: it keeps version and updated_at, so a seed or
-- runbook re-tag never breaks an admin's If-Match, never sends the outlet or route to phones again as a bundle delta and
-- never moves a route-day's version. Every other update bumps exactly as before.
CREATE OR REPLACE FUNCTION app.touch_master() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  n jsonb := to_jsonb(NEW);
  o jsonb;
BEGIN
  IF n ? 'data_origin' THEN
    o := to_jsonb(OLD);
    IF n -> 'data_origin' IS DISTINCT FROM o -> 'data_origin' AND n - 'data_origin' = o - 'data_origin' THEN
      RETURN NEW;                            -- only the origin moved: no new version
    END IF;
  END IF;
  NEW.updated_at := now();
  NEW.version := OLD.version + 1;          -- always forward: a stale If-Match version can never match again
  RETURN NEW;
END $$;

-- ---------- restamp ----------
-- Runs with the caller's rights: for anyone but the owner the origin guard refuses the first row it would change.
CREATE FUNCTION app.restamp_data_origin(p_from date DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  r       record;
  args    text;
  has_bd  boolean;
  n       bigint;
  total   bigint := 0;
BEGIN
  FOR r IN SELECT * FROM app.data_origin_capture ORDER BY pass, table_name LOOP
    args := CASE WHEN r.parent IS NULL THEN '' ELSE format(', %L, %L, %L', r.parent, r.child_col, r.parent_col) END;
    has_bd := EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = format('app.%I', r.table_name)::regclass
                        AND attname = 'business_date' AND NOT attisdropped);
    EXECUTE format('UPDATE app.%I x SET data_origin = app.derive_data_origin(to_jsonb(x)%s)
                     WHERE %s data_origin IS DISTINCT FROM app.derive_data_origin(to_jsonb(x)%s)',
                   r.table_name, args, CASE WHEN has_bd THEN '($1 IS NULL OR business_date >= $1) AND' ELSE '' END, args)
      USING p_from;
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
  END LOOP;
  -- dw: a write fires the table's origin trigger, which recomputes the value.
  FOR r IN SELECT c.oid::regclass AS rel, EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = c.oid AND b.attname = 'business_date') AS bd
             FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE s.nspname = 'dw' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND a.attname = 'data_origin' AND NOT a.attisdropped
            ORDER BY 1 LOOP
    EXECUTE format('UPDATE %s SET data_origin = data_origin %s', r.rel, CASE WHEN r.bd THEN 'WHERE $1 IS NULL OR business_date >= $1' ELSE '' END)
      USING p_from;
  END LOOP;
  RETURN total;
END $$;
COMMENT ON FUNCTION app.restamp_data_origin(date) IS 'Re-derives data_origin on every registry table (rows on or after p_from; null = all) from the current tags of users, outlets, routes and parents, then refreshes dw. Owner only (the origin guard refuses other roles). Returns the number of app rows changed.';

-- ---------- 5. views (data_origin appended; grants kept) ----------
CREATE OR REPLACE VIEW dw.v_attendance AS
 SELECT business_date, user_id, role, zone_id, check_in_at, check_out_at,
        CASE
            WHEN check_in_at IS NOT NULL AND check_out_at IS NOT NULL THEN round(EXTRACT(epoch FROM check_out_at - check_in_at) / 3600.0, 2)
            ELSE NULL::numeric
        END AS hours_in_field,
        check_in_is_mock, check_out_is_mock, updated_at, data_origin
   FROM dw.fact_attendance f;

CREATE OR REPLACE VIEW dw.v_collections AS
 SELECT a.business_date, a.outlet_id, o.outlet_code, o.outlet_name, COALESCE(a.route_id, o.route_id) AS route_id, o.zone_id,
        a.net_mtk AS sales_net_mtk, a.due_mtk AS new_due_mtk, a.dues_collected_mtk, a.updated_at, a.data_origin
   FROM dw.agg_daily_outlet a
     LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id
  WHERE a.due_mtk <> 0 OR a.dues_collected_mtk <> 0;

CREATE OR REPLACE VIEW dw.v_daily_outlet AS
 SELECT a.business_date, a.outlet_id, o.outlet_code, o.outlet_name, COALESCE(a.route_id, o.route_id) AS route_id,
        o.cluster_id, o.zone_id, o.channel, o.sub_channel_id, o.geo_class, o.outlet_kind, o.location_confirmed,
        a.visited, a.geo_valid, a.active_memo_count, a.cigarette_sold_sticks, a.bidi_sold_sticks, a.lighter_sold_pieces,
        a.match_sold_dozens, a.net_mtk, a.due_mtk, a.dues_collected_mtk, a.updated_at, a.data_origin
   FROM dw.agg_daily_outlet a
     LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id;

CREATE OR REPLACE VIEW dw.v_daily_route AS
 SELECT a.business_date, a.route_id, g.route_code, g.route_name, g.visit_kind, a.zone_id, g.zone_name, g.territory_id,
        g.territory_name, g.division_id, g.division_name, g.wing_id, g.wing_name, a.planned, a.exception_approved,
        a.day_state, a.logged_in_at, a.sales_submitted_at, a.final_submitted_at, a.target_outlets, a.visited_outlets,
        a.successful_calls, dw.pct(a.successful_calls::numeric, a.target_outlets::numeric) AS strike_rate_pct, a.visits,
        a.geo_valid_visits, dw.pct(a.geo_valid_visits::numeric, a.visits::numeric) AS geo_valid_pct, a.force_sale_visits,
        a.mock_visits, a.suspicious_visits, a.active_memo_count, a.gross_mtk, a.offer_discount_mtk, a.drp_discount_mtk,
        a.qc_deduction_mtk, a.net_mtk, a.paid_mtk, a.due_mtk, a.dues_collected_mtk, a.late_rows_after_final, a.updated_at,
        a.data_origin
   FROM dw.agg_daily_route a
     LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE OR REPLACE VIEW dw.v_daily_sku AS
 SELECT a.business_date, a.route_id, g.route_code, g.zone_id, g.territory_id, a.sku_id, p.sku_code, p.short_name,
        p.base_unit, p.base_per_pack, p.brand_id, p.brand_name, p.category_code, a.sold_qty_base, a.free_qty_base,
        a.issued_qty_base, a.returned_qty_base, a.gross_mtk, a.memo_count, a.updated_at, a.data_origin
   FROM dw.agg_daily_route_sku a
     LEFT JOIN dw.dim_product p ON p.sku_id = a.sku_id
     LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE OR REPLACE VIEW dw.v_daily_sr AS
 SELECT business_date, user_id,
    array_agg(DISTINCT route_id) FILTER (WHERE k = 'v'::text AND route_id IS NOT NULL) AS route_ids,
    count(*) FILTER (WHERE k = 'v'::text) AS visits,
    count(DISTINCT outlet_id) FILTER (WHERE k = 'v'::text AND reached) AS visited_outlets,
    count(DISTINCT outlet_id) FILTER (WHERE k = 'm'::text) AS successful_calls,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text) AS geo_valid_visits,
    dw.pct(count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text)::numeric, count(*) FILTER (WHERE k = 'v'::text)::numeric) AS geo_valid_pct,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'force_sale'::text) AS force_sale_visits,
    count(*) FILTER (WHERE k = 'v'::text AND is_mock) AS mock_visits,
    count(*) FILTER (WHERE k = 'm'::text) AS active_memo_count,
    COALESCE(sum(gross_mtk), 0::numeric)::bigint AS gross_mtk,
    COALESCE(sum(net_mtk), 0::numeric)::bigint AS net_mtk,
    COALESCE(sum(due_mtk), 0::numeric)::bigint AS due_mtk,
    count(*) FILTER (WHERE k = 'm'::text AND captured_offline) AS memos_captured_offline,
    min(at) FILTER (WHERE k = 'v'::text) AS first_visit_at,
    max(end_at) FILTER (WHERE k = 'v'::text) AS last_visit_end_at,
    count(*) FILTER (WHERE k = 'm'::text AND entry_source = 'manual'::text) AS memos_entered_manually,
    max(data_origin) AS data_origin
   FROM ( SELECT 'v'::text AS k, fact_visit.business_date, fact_visit.user_id, fact_visit.route_id, fact_visit.outlet_id,
            fact_visit.outcome_code IS DISTINCT FROM 'abandoned'::text AND fact_visit.call_declined IS NOT TRUE AS reached,
            fact_visit.server_verdict, fact_visit.geo_action, fact_visit.is_mock,
            NULL::bigint AS gross_mtk, NULL::bigint AS net_mtk, NULL::bigint AS due_mtk, NULL::boolean AS captured_offline,
            fact_visit.opened_at AS at, COALESCE(fact_visit.ended_at, fact_visit.opened_at) AS end_at,
            fact_visit.entry_source, fact_visit.data_origin
           FROM dw.fact_visit
          WHERE NOT fact_visit.voided AND fact_visit.visit_kind = 'sr_call'::text
        UNION ALL
         SELECT 'm'::text, fact_memo.business_date, fact_memo.user_id, fact_memo.route_id, fact_memo.outlet_id,
            NULL::boolean, NULL::text, NULL::text, NULL::boolean,
            fact_memo.gross_mtk, fact_memo.net_mtk, fact_memo.due_mtk, fact_memo.captured_offline,
            NULL::timestamp with time zone, NULL::timestamp with time zone,
            fact_memo.entry_source, fact_memo.data_origin
           FROM dw.fact_memo
          WHERE fact_memo.status = 'active'::text AND fact_memo.line_count > 0) x
  GROUP BY business_date, user_id;

CREATE OR REPLACE VIEW dw.v_geo_integrity AS
 SELECT business_date, user_id,
    count(*) FILTER (WHERE k = 'v'::text) AS visits,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text) AS server_in_range,
    dw.pct(count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text)::numeric, count(*) FILTER (WHERE k = 'v'::text)::numeric) AS geo_valid_pct,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'out_of_range'::text) AS server_out_of_range,
    count(*) FILTER (WHERE k = 'v'::text AND device_verdict = 'no_fix'::text) AS no_fix,
    count(*) FILTER (WHERE k = 'v'::text AND device_verdict = 'accuracy_too_low'::text) AS accuracy_too_low,
    count(*) FILTER (WHERE k = 'v'::text AND (device_verdict = 'mocked'::text OR is_mock)) AS mocked_visits,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'force_sale'::text) AS force_sale_visits,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'blocked'::text) AS blocked_visits,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict IS NOT NULL AND server_verdict <> device_verdict) AS device_server_mismatch,
    count(*) FILTER (WHERE k = 'f'::text) AS fixes,
    count(*) FILTER (WHERE k = 'f'::text AND is_mock) AS mock_fixes,
    round(avg(accuracy_m)::numeric, 1) AS avg_accuracy_m,
    max(data_origin) AS data_origin
   FROM ( SELECT 'v'::text AS k, fact_visit.business_date, fact_visit.user_id, fact_visit.server_verdict,
            fact_visit.device_verdict, fact_visit.geo_action, fact_visit.is_mock, NULL::double precision AS accuracy_m,
            fact_visit.data_origin
           FROM dw.fact_visit
          WHERE NOT fact_visit.voided
        UNION ALL
         SELECT 'f'::text, fact_geo_fix.business_date, fact_geo_fix.user_id, NULL::text, NULL::text, NULL::text,
            fact_geo_fix.is_mock, fact_geo_fix.accuracy_m, fact_geo_fix.data_origin
           FROM dw.fact_geo_fix) x
  GROUP BY business_date, user_id;

CREATE OR REPLACE VIEW dw.v_device_command AS
 SELECT seq, command_id, device_id, type, state, reason, created_by, created_at, expires_at, approved_by, approved_at,
        decision_reason, push_sent_at, sent_at, acked_at,
        coalesce((SELECT max(u.data_origin) FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id
                   WHERE b.device_id = c.device_id AND b.status = 'active'), 'production'::app.data_origin) AS data_origin
   FROM app.device_command c;

CREATE OR REPLACE VIEW dw.v_final_submit_log AS
 SELECT DISTINCT ON (zone_id, business_date) zone_id, business_date, submitted_at AS first_submitted_at,
        submitted_by AS first_submitted_by, via AS first_via, count(*) OVER w AS submits,
        max(submitted_at) OVER w AS last_submitted_at, NOT bool_or(reopened_at IS NULL) OVER w AS reopened_now,
        max(data_origin) OVER w AS data_origin
   FROM app.final_submit f
  WINDOW w AS (PARTITION BY zone_id, business_date)
  ORDER BY zone_id, business_date, submitted_at, id;

CREATE OR REPLACE VIEW dw.v_memo_flags AS
SELECT m.id AS memo_id, m.client_uuid AS memo_client_uuid, m.family_uuid AS memo_family_uuid, m.memo_no,
       m.business_date, m.status, m.user_id, m.outlet_id, o.code AS outlet_code, o.name AS outlet_name,
       s.route_id, z.id AS zone_id, z.territory_id, t.division_id, d.wing_id,
       m.gross_mtk, m.server_flags AS flags, m.received_at AS stored_at, m.voided_at, m.data_origin
  FROM app.memo m
  JOIN app.outlet o ON o.id = m.outlet_id
  LEFT JOIN LATERAL (
    SELECT coalesce(m.route_id, p.route_id, o.route_id) AS route_id,
           coalesce(m.zone_id,
                    app.route_zone_on(p.route_id, m.business_date),
                    (SELECT r.zone_id FROM app.route r WHERE r.id = p.route_id),
                    o.zone_id) AS zone_id
      FROM (SELECT (SELECT h.route_id FROM app.outlet_placement_history h
                     WHERE m.route_id IS NULL AND h.outlet_id = m.outlet_id AND h.valid_from <= m.business_date
                       AND (h.valid_to IS NULL OR h.valid_to > m.business_date)) AS route_id) p
  ) s ON true
  LEFT JOIN app.zone z ON z.id = s.zone_id
  LEFT JOIN app.territory t ON t.id = z.territory_id
  LEFT JOIN app.division d ON d.id = t.division_id
 WHERE m.server_flags <> '{}';

CREATE OR REPLACE VIEW dw.v_outlet_masked AS
 SELECT id AS outlet_id, code AS outlet_code, name AS outlet_name, name_bn AS outlet_name_bn, zone_id, route_id, cluster_id,
        channel, sub_channel_id, geo_class, outlet_kind, price_type, status, location_confirmed,
        CASE
            WHEN contact_number ~ '^01[0-9]{9}$'::text THEN '01*****'::text || "right"(contact_number, 3)
            ELSE NULL::text
        END AS contact_number_masked,
        updated_at, data_origin
   FROM app.outlet o;

CREATE OR REPLACE VIEW dw.v_route_monthly_target AS
 SELECT t.month, t.route_id, t.sku_id, s.category_code, s.base_unit, t.qty_base AS monthly_qty_base, cm.working_days,
        CASE
            WHEN cm.working_days > 0 THEN t.qty_base::numeric / cm.working_days::numeric
            ELSE NULL::numeric
        END AS daily_qty_base,
        t.batch_uuid, t.updated_at, r.data_origin
   FROM app.route_monthly_target t
     JOIN app.sku s ON s.id = t.sku_id
     JOIN app.route r ON r.id = t.route_id
     LEFT JOIN dw.v_calendar_month cm ON cm.month = t.month;

COMMENT ON COLUMN dw.v_attendance.data_origin IS 'Origin of the user-day (dw.fact_attendance.data_origin: the user''s origin).';
COMMENT ON COLUMN dw.v_collections.data_origin IS 'Origin of the outlet-day (dw.agg_daily_outlet.data_origin): production only when nothing non-production went into it.';
COMMENT ON COLUMN dw.v_daily_outlet.data_origin IS 'Origin of the outlet-day (dw.agg_daily_outlet.data_origin): production only when nothing non-production went into it.';
COMMENT ON COLUMN dw.v_daily_route.data_origin IS 'Origin of the route-day (dw.agg_daily_route.data_origin): production only when nothing non-production went into it.';
COMMENT ON COLUMN dw.v_daily_sku.data_origin IS 'Origin of the route-day (dw.agg_daily_route_sku.data_origin): production only when nothing non-production went into it.';
COMMENT ON COLUMN dw.v_daily_sr.data_origin IS 'Most synthetic origin among the visits and memos of the user-day.';
COMMENT ON COLUMN dw.v_geo_integrity.data_origin IS 'Most synthetic origin among the visits and fixes of the user-day.';
COMMENT ON COLUMN dw.v_device_command.data_origin IS 'Most synthetic origin among the users with an active binding on the device now (production when none).';
COMMENT ON COLUMN dw.v_final_submit_log.data_origin IS 'Most synthetic origin among the zone-day''s final submits.';
COMMENT ON COLUMN dw.v_memo_flags.data_origin IS 'Origin of the memo (app.memo.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.v_outlet_masked.data_origin IS 'Origin of the outlet (app.outlet.data_origin).';
COMMENT ON COLUMN dw.v_route_monthly_target.data_origin IS 'Origin of the route (app.route.data_origin).';

-- ---------- 6. grants and backfill ----------
SELECT app.apply_db_role_grants();
SELECT app.restamp_data_origin(NULL);
