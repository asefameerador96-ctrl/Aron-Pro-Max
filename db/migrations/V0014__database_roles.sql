-- V0014 least-privilege database roles (docs/16 s2.1 and s13.4b; docs/31 s3 item 5). Privilege roles are NOLOGIN
-- groups; the runtime login identities (one per container app, created by infra) are made members of exactly one.
-- The migrating login owns every object and is never used at run time. Grants are generated from app.db_role_grant
-- (the data), so the map and the database cannot diverge; app.apply_db_role_grants() re-applies them and every later
-- migration that creates a table calls it. DbRolesTest proves the positive and the negative matrix.
--
--   api_rw    API: read and insert app; update app except append-only tables; never delete; read dw; run app helpers
--   worker_rw worker: read app; write dw; write only the worker-owned app tables (state, signals, queues, retention)
--   jobs_rw   partition and retention jobs: worker_rw plus app.ensure_partitions() (SECURITY DEFINER)
--   web_ro    web dashboards: read dw and the business code lists; nothing else in app
--   bi_reader BI and other products (read replica): read dw only (views are the contract, docs/31 s3)

DO $$
DECLARE
  r     text;
  attr  record;
  fixes text[];
  f     text;
  extra text;
BEGIN
  FOREACH r IN ARRAY ARRAY['api_rw', 'worker_rw', 'jobs_rw', 'web_ro', 'bi_reader'] LOOP
    SELECT * INTO attr FROM pg_roles WHERE rolname = r;
    IF NOT FOUND THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', r);
      CONTINUE;
    END IF;
    -- A role already on the server (another database of the same server, or an earlier install) is reused when it is
    -- exactly as intended; nothing below needs ADMIN on it then. Only a superuser may touch SUPERUSER and BYPASSRLS,
    -- and only a holder of ADMIN may alter the role or its memberships, so anything else stops for a human to fix.
    IF attr.rolsuper OR attr.rolbypassrls THEN
      RAISE EXCEPTION 'role % is superuser or bypasses RLS; fix it by hand before migrating', r;
    END IF;
    SELECT string_agg(g.rolname, ', ') INTO extra FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
     WHERE m.member = attr.oid AND NOT (r = 'jobs_rw' AND g.rolname = 'worker_rw');
    IF extra IS NOT NULL THEN
      RAISE EXCEPTION 'role % is a member of % and would inherit their rights; revoke by hand before migrating', r, extra;
    END IF;
    fixes := '{}';
    IF attr.rolcanlogin THEN fixes := fixes || 'NOLOGIN'; END IF;
    IF attr.rolcreatedb THEN fixes := fixes || 'NOCREATEDB'; END IF;
    IF attr.rolcreaterole THEN fixes := fixes || 'NOCREATEROLE'; END IF;
    IF attr.rolinherit THEN fixes := fixes || 'NOINHERIT'; END IF;
    IF cardinality(fixes) > 0 THEN
      IF NOT pg_has_role(current_user, r, 'USAGE WITH ADMIN OPTION') THEN
        RAISE EXCEPTION 'role % needs %; this login lacks ADMIN on it, run ALTER ROLE as its creator', r, array_to_string(fixes, ' ');
      END IF;
      FOREACH f IN ARRAY fixes LOOP EXECUTE format('ALTER ROLE %I %s', r, f); END LOOP;
    END IF;
  END LOOP;
  -- PostgreSQL 16 fixes inheritance per grant: jobs_rw inherits everything worker_rw holds.
  IF NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid = 'worker_rw'::regrole AND m.member = 'jobs_rw'::regrole
                    AND m.inherit_option) THEN
    IF NOT pg_has_role(current_user, 'worker_rw', 'USAGE WITH ADMIN OPTION') THEN
      RAISE EXCEPTION 'jobs_rw must inherit worker_rw; this login lacks ADMIN on worker_rw, grant it as its creator';
    END IF;
    GRANT worker_rw TO jobs_rw WITH INHERIT TRUE;
  END IF;
END $$;

-- The grant map. object '*' means every table of the schema; except lists the tables a '*' row leaves out.
CREATE TABLE app.db_role_grant (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role        text NOT NULL CHECK (role IN ('api_rw','worker_rw','jobs_rw','web_ro','bi_reader')),
  schema_name text NOT NULL CHECK (schema_name IN ('app','dw')),
  object      text NOT NULL,                                     -- table name or '*'
  privileges  text NOT NULL CHECK (privileges ~ '^(SELECT|INSERT|UPDATE|DELETE)(, (SELECT|INSERT|UPDATE|DELETE))*$'),
  except_tables text[] NOT NULL DEFAULT '{}',
  except_columns text[] NOT NULL DEFAULT '{}' CHECK (cardinality(except_columns) = 0 OR object NOT LIKE '*%'),  -- column grant on the rest
  note        text NOT NULL,
  UNIQUE (role, schema_name, object)
);
COMMENT ON TABLE app.db_role_grant IS
  'Least-privilege grant map of the database roles (docs/16 s13.4b); app.apply_db_role_grants() generates the GRANTs.';

-- Append-only and trail tables the API may insert into but never update.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, note) VALUES
  ('api_rw', 'app', '*', 'SELECT, INSERT', '{db_role_grant,partition_policy}', 'ingest and admin writes'),
  ('api_rw', 'app', '*/update', 'UPDATE',
   '{db_role_grant,partition_policy,audit_log,domain_event,due_ledger,loyalty_ledger,outlet_location_history,outlet_request_event,device_status_report,submit_void_event,route_day_void_barrier,geo_fix,stock_movement,indent_movement,risk_signal,event_consumer,server_generation}',
   'state and lifecycle updates; the guard triggers limit which columns change'),
  ('api_rw', 'app', 'auth_lockout', 'DELETE', '{}', 'a successful login clears the lockout counter (not a transaction table)'),
  ('api_rw', 'dw', '*', 'SELECT', '{}', 'API read path (app home, dashboards)'),
  ('worker_rw', 'app', '*', 'SELECT', '{app_user,mfa_secret,device_otp,refresh_token,enrolment_token}',
   'projector and jobs read capture tables; never credentials or one-time secrets'),
  ('worker_rw', 'app', 'app_user', 'SELECT', '{}', 'every column except password_hash (column grant, see except_columns)'),
  ('worker_rw', 'dw', '*', 'SELECT, INSERT, UPDATE, DELETE', '{}', 'aggregates and facts are rebuilt by the worker'),
  ('worker_rw', 'app', 'route_day', 'INSERT, UPDATE', '{}', 'route-day creation and state timestamps'),
  ('worker_rw', 'app', 'supervisor_day', 'INSERT, UPDATE', '{}', 'supervisor-day settle'),
  ('worker_rw', 'app', 'risk_signal', 'INSERT, UPDATE', '{}', 'risk rules'),
  ('worker_rw', 'app', 'domain_event', 'INSERT', '{}', 'events raised by jobs'),
  ('worker_rw', 'app', 'event_consumer', 'INSERT, UPDATE', '{}', 'projector position'),
  ('worker_rw', 'app', 'dirty_key', 'INSERT, UPDATE, DELETE', '{}', 'rebuild queue'),
  ('worker_rw', 'app', 'loyalty_ledger', 'INSERT', '{}', 'ledger projection (deferred programme, docs/27)'),
  ('worker_rw', 'app', 'due_ledger', 'INSERT', '{}', 'due ledger projection'),
  ('worker_rw', 'app', 'visit', 'UPDATE', '{}', 'server geo re-check columns (guard trigger limits the columns)'),
  ('worker_rw', 'app', 'media', 'UPDATE', '{}', 'blob-created confirmation'),
  ('worker_rw', 'app', 'sync_rejected', 'UPDATE', '{}', 'parked rows turn final'),
  ('worker_rw', 'app', 'outlet_change_request', 'UPDATE', '{}', 'requests lapse after 30 days'),
  ('worker_rw', 'app', 'ingest_registry', 'DELETE', '{}', 'retention after cfg.retention.ingest_registry_days'),
  ('worker_rw', 'app', 'sync_batch', 'DELETE', '{}', 'replay store expiry'),
  ('worker_rw', 'app', 'device_nonce', 'DELETE', '{}', 'expired nonces'),
  ('web_ro', 'dw', '*', 'SELECT', '{fact_geo_fix,fact_attendance}', 'dashboards read aggregates only; no PII facts'),
  ('web_ro', 'app', 'code_list', 'SELECT', '{}', 'labels of business codes'),
  ('web_ro', 'app', 'code_list_item', 'SELECT', '{}', 'labels of business codes'),
  ('bi_reader', 'dw', '*', 'SELECT', '{fact_geo_fix,fact_attendance}', 'stable dw views and tables; no PII facts; nothing in app');

UPDATE app.db_role_grant SET except_columns = '{password_hash}' WHERE role = 'worker_rw' AND object = 'app_user';

-- Revokes everything the five roles hold on app and dw tables and grants exactly the map on tables, partitioned
-- parents and views (never on single partitions: rows are reached through the parent). Idempotent.
CREATE FUNCTION app.apply_db_role_grants() RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  g record;
  t record;
BEGIN
  FOR t IN SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname IN ('app','dw') AND c.relkind IN ('r','p','v','m') LOOP
    EXECUTE format('REVOKE ALL ON %I.%I FROM api_rw, worker_rw, jobs_rw, web_ro, bi_reader', t.nspname, t.relname);
  END LOOP;
  FOR g IN SELECT * FROM app.db_role_grant LOOP
    FOR t IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = g.schema_name AND c.relkind IN ('r','p','v','m') AND NOT c.relispartition
                AND (c.relname = g.object OR (g.object LIKE '*%' AND c.relname <> ALL (g.except_tables)))
    LOOP
      IF cardinality(g.except_columns) = 0 THEN
        EXECUTE format('GRANT %s ON %I.%I TO %I', g.privileges, g.schema_name, t.relname, g.role);
      ELSE
        EXECUTE format('GRANT %s (%s) ON %I.%I TO %I', g.privileges,
          (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) FROM pg_attribute a
            WHERE a.attrelid = format('%I.%I', g.schema_name, t.relname)::regclass AND a.attnum > 0 AND NOT a.attisdropped
              AND a.attname <> ALL (g.except_columns)),
          g.schema_name, t.relname, g.role);
      END IF;
    END LOOP;
  END LOOP;
  -- Sequences behind serial columns (identity columns need none) and functions created by later migrations.
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA app TO api_rw, worker_rw';
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA dw TO worker_rw';
  EXECUTE 'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app, dw FROM PUBLIC';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO api_rw, worker_rw';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA dw TO api_rw, worker_rw, web_ro, bi_reader';
  EXECUTE 'REVOKE EXECUTE ON FUNCTION app.apply_db_role_grants(), app.ensure_partitions(date, date) FROM api_rw, worker_rw';
  EXECUTE 'GRANT EXECUTE ON FUNCTION app.ensure_partitions(date, date) TO jobs_rw';
END $$;
REVOKE EXECUTE ON FUNCTION app.apply_db_role_grants() FROM PUBLIC;

GRANT USAGE ON SCHEMA app TO api_rw, worker_rw, web_ro;
GRANT USAGE ON SCHEMA dw TO api_rw, worker_rw, web_ro, bi_reader;
REVOKE CREATE ON SCHEMA app, dw, stg FROM PUBLIC;
REVOKE ALL ON SCHEMA stg FROM PUBLIC;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA app TO api_rw, worker_rw;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA dw TO worker_rw;

-- Functions: trigger functions run with the rights of the writing role, and the helpers they and the CHECK
-- constraints call (audit_row_hash, div_half_up, dhaka_date, ...) need EXECUTE, so the writing roles may execute the
-- app functions; the administration functions are revoked from them and from PUBLIC. Partition maintenance runs with
-- the owner's rights (it creates and attaches tables) and only jobs_rw may call it.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO api_rw, worker_rw;
REVOKE EXECUTE ON FUNCTION app.apply_db_role_grants(), app.ensure_partitions(date, date) FROM api_rw, worker_rw;
-- Functions a later migration creates are not executable by PUBLIC (SECURITY DEFINER ones included); the migration
-- calls app.apply_db_role_grants() to give the runtime roles what the map says.
-- (Per-schema default privileges can only add to the global ones, so the revoke is global, for this login.)
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER FUNCTION app.ensure_partitions(date, date) SECURITY DEFINER SET search_path = pg_catalog, app;
GRANT EXECUTE ON FUNCTION app.ensure_partitions(date, date) TO jobs_rw;

SELECT app.apply_db_role_grants();
