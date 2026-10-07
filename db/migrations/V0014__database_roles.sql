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
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['api_rw', 'worker_rw', 'jobs_rw', 'web_ro', 'bi_reader'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', r);
    END IF;
  END LOOP;
END $$;
-- PostgreSQL 16 fixes inheritance per grant: jobs_rw inherits everything worker_rw holds.
GRANT worker_rw TO jobs_rw WITH INHERIT TRUE;

-- The grant map. object '*' means every table of the schema; except lists the tables a '*' row leaves out.
CREATE TABLE app.db_role_grant (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role        text NOT NULL CHECK (role IN ('api_rw','worker_rw','jobs_rw','web_ro','bi_reader')),
  schema_name text NOT NULL CHECK (schema_name IN ('app','dw')),
  object      text NOT NULL,                                     -- table name or '*'
  privileges  text NOT NULL CHECK (privileges ~ '^(SELECT|INSERT|UPDATE|DELETE)(, (SELECT|INSERT|UPDATE|DELETE))*$'),
  except_tables text[] NOT NULL DEFAULT '{}',
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
  ('api_rw', 'dw', '*', 'SELECT', '{}', 'API read path (app home, dashboards)'),
  ('worker_rw', 'app', '*', 'SELECT', '{}', 'projector and jobs read capture tables'),
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
  ('web_ro', 'dw', '*', 'SELECT', '{}', 'dashboards read aggregates only'),
  ('web_ro', 'app', 'code_list', 'SELECT', '{}', 'labels of business codes'),
  ('web_ro', 'app', 'code_list_item', 'SELECT', '{}', 'labels of business codes'),
  ('bi_reader', 'dw', '*', 'SELECT', '{}', 'stable dw views and tables; nothing in app');

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
      EXECUTE format('GRANT %s ON %I.%I TO %I', g.privileges, g.schema_name, t.relname, g.role);
    END LOOP;
  END LOOP;
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
ALTER FUNCTION app.ensure_partitions(date, date) SECURITY DEFINER SET search_path = pg_catalog, app;
GRANT EXECUTE ON FUNCTION app.ensure_partitions(date, date) TO jobs_rw;

SELECT app.apply_db_role_grants();
