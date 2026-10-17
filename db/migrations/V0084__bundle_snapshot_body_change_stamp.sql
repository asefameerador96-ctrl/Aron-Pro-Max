-- V0084 bundle snapshot body and the masterdata change stamp (SCALE-03, audit docs/audit/production-readiness-2026-10-15.md;
-- F-SYS-067 docs/24 s4.10, D-REP-10). The worker's pre-generation stored only the snapshot seq, so every GET /v1/sync/bundle
-- rebuilt the day's bundle (about 25 queries and a full cfg_value scan) and knew its ETag only after the build. The built
-- body now rides with its seq: a login answers 304 from the stored seq before any build and serves the stored body while
-- the snapshot is current. Current means: built under the caller's scope_version, the committed config version and the
-- masterdata change stamp, a counter bumped by a statement trigger on every write to the reference tables the bundle reads
-- (outlets, routes, assignments, geography, products, prices, plans, calendar, code lists, content, surveys, users).
-- Field data (memos, dues, tasks, day states) reaches the phone through the delta, as before.

SET lock_timeout = '5s';

ALTER TABLE app.bundle_snapshot
  ADD COLUMN body_gz        bytea,
  ADD COLUMN scope_version  bigint,
  ADD COLUMN config_version bigint,
  ADD COLUMN change_stamp   bigint;
COMMENT ON COLUMN app.bundle_snapshot.body_gz IS 'The full bundle (every section, placeholder meta) as gzip JSON, served by GET /v1/sync/bundle while the snapshot is current; null until a build stored it.';
COMMENT ON COLUMN app.bundle_snapshot.scope_version IS 'app_user.scope_version the body was built under; a token carrying another one rebuilds.';
COMMENT ON COLUMN app.bundle_snapshot.config_version IS 'The committed config version the body carries; a newer one rebuilds.';
COMMENT ON COLUMN app.bundle_snapshot.change_stamp IS 'app.change_stamp.value (masterdata) when the body was built; a later reference write rebuilds.';

CREATE TABLE app.change_stamp (
  name        text PRIMARY KEY CHECK (name ~ '^[a-z_]{1,40}$'),
  value       bigint NOT NULL DEFAULT 1 CHECK (value >= 1),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE app.change_stamp IS 'One counter per named set of tables, bumped by a statement trigger on every write to them; a stored bundle snapshot that remembers the counter knows when it went stale.
owner: backend:sync | capture: SERVER | retention: ops | pii: none';
COMMENT ON COLUMN app.change_stamp.name IS 'The counter: masterdata (the reference tables the field bundle reads).';
COMMENT ON COLUMN app.change_stamp.value IS 'Grows by one per writing statement on the set; never reset.';
COMMENT ON COLUMN app.change_stamp.updated_at IS 'When the counter last moved.';
INSERT INTO app.change_stamp (name) VALUES ('masterdata');

CREATE FUNCTION app.bump_change_stamp() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE app.change_stamp SET value = value + 1, updated_at = now() WHERE name = TG_ARGV[0];
  RETURN NULL;
END $$;
COMMENT ON FUNCTION app.bump_change_stamp() IS 'Statement trigger: bumps the change stamp named by its argument after a write to one of its tables.';

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['outlet', 'outlet_location_history', 'route', 'route_assignment', 'zone', 'territory', 'division', 'wing', 'cluster',
                           'sku', 'sku_price', 'product_node', 'sales_plan', 'calendar_holiday', 'code_list', 'code_list_item', 'content_item', 'survey'] LOOP
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH STATEMENT EXECUTE FUNCTION app.bump_change_stamp(''masterdata'')', t || '_change_stamp', t);
  END LOOP;
END $$;
-- Users: only the members the bundle shows (a login's own row updates, passwords and MFA never bump the stamp).
CREATE TRIGGER app_user_change_stamp AFTER INSERT OR UPDATE OF full_name, role, designation, locale, home_zone_id, status, scope_version OR DELETE ON app.app_user
  FOR EACH STATEMENT EXECUTE FUNCTION app.bump_change_stamp('masterdata');

-- The api writes the snapshot it built (and marks a same-content row current again); the worker's pre-generation does the
-- same; retention deletes old dates (V0032). The stamp is read by both and bumped by whoever writes a reference table.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw',    'app', 'change_stamp', 'SELECT, UPDATE', 'bundle snapshot currency; the trigger bumps it on admin writes'),
  ('worker_rw', 'app', 'change_stamp', 'SELECT, UPDATE', 'bundle pre-generation; the trigger bumps it on job writes'),
  ('jobs_rw',   'app', 'change_stamp', 'SELECT, UPDATE', 'the trigger bumps it on job writes');
SELECT app.apply_db_role_grants();
