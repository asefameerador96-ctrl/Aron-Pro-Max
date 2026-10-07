-- V0020 the rest of the role map (docs/16 s13.4b; audit AUD-DA-03, AUD-TP-7, AUD-REL-03), on top of V0014:
--   auth_rw    the login path: identity, device binding and token tables, the config and geography it reads, audit
--   pii_reader outlet PII columns only; reached by SET ROLE from a login that infra makes a member WITH INHERIT FALSE
--   support_ro L1 support: device tables and the sync-batch log without its payloads
--   web_ro, bi_reader  narrowed to the stable dw.v_* views (docs/31 s3: the views are the contract) plus, for web_ro,
--              the code lists; dw.v_outlet_masked is their outlet view (phone as 01*****NNN, no owner or address)
-- Timeouts (docs/18 s2.6, D-139): a setting on a NOLOGIN group never reaches its members' sessions, so the limits are
-- data (app.db_role_limit) and app.apply_login_limits() writes them onto every LOGIN that is a direct member of a
-- privilege role. Infra's migrate job calls it after creating the logins (docs/requests/db-runtime-roles.md).

SET lock_timeout = '5s';

DO $$
DECLARE
  r     text;
  attr  record;
  fixes text[];
  f     text;
  extra text;
BEGIN
  FOREACH r IN ARRAY ARRAY['auth_rw', 'pii_reader', 'support_ro'] LOOP
    SELECT * INTO attr FROM pg_roles WHERE rolname = r;
    IF NOT FOUND THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', r);
      CONTINUE;
    END IF;
    -- Same rules as V0014: reuse a correct role without ADMIN, repair with ADMIN, refuse anything dangerous.
    IF attr.rolsuper OR attr.rolbypassrls THEN
      RAISE EXCEPTION 'role % is superuser or bypasses RLS; fix it by hand before migrating', r;
    END IF;
    SELECT string_agg(g.rolname, ', ') INTO extra FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
     WHERE m.member = attr.oid;
    IF extra IS NOT NULL THEN
      RAISE EXCEPTION 'role % is a member of % and would inherit their rights; revoke by hand before migrating', r, extra;
    END IF;
    fixes := '{}';
    IF attr.rolcanlogin THEN fixes := array_append(fixes, 'NOLOGIN'); END IF;
    IF attr.rolcreatedb THEN fixes := array_append(fixes, 'NOCREATEDB'); END IF;
    IF attr.rolcreaterole THEN fixes := array_append(fixes, 'NOCREATEROLE'); END IF;
    IF attr.rolinherit THEN fixes := array_append(fixes, 'NOINHERIT'); END IF;
    IF cardinality(fixes) > 0 THEN
      IF NOT pg_has_role(current_user, r, 'USAGE WITH ADMIN OPTION') THEN
        RAISE EXCEPTION 'role % needs %; this login lacks ADMIN on it, run ALTER ROLE as its creator', r, array_to_string(fixes, ' ');
      END IF;
      FOREACH f IN ARRAY fixes LOOP EXECUTE format('ALTER ROLE %I %s', r, f); END LOOP;
    END IF;
  END LOOP;
END $$;

-- The map learns explicit column lists (pii_reader) and the 'v_*' object (the views of a schema whose name starts v_).
-- The checks are NOT VALID (squawk: no in-transaction VALIDATE); they bind every new or changed row, and the existing
-- rows satisfy them (DbRolesTest).
ALTER TABLE app.db_role_grant ADD COLUMN only_columns text[] NOT NULL DEFAULT '{}';
ALTER TABLE app.db_role_grant DROP CONSTRAINT db_role_grant_role_check;
ALTER TABLE app.db_role_grant ADD CONSTRAINT db_role_grant_role_check
  CHECK (role IN ('api_rw','worker_rw','jobs_rw','web_ro','bi_reader','auth_rw','pii_reader','support_ro')) NOT VALID;
ALTER TABLE app.db_role_grant ADD CONSTRAINT db_role_grant_one_column_rule
  CHECK (cardinality(only_columns) = 0 OR (cardinality(except_columns) = 0 AND object NOT LIKE '*%' AND object <> 'v_*')) NOT VALID;

DELETE FROM app.db_role_grant WHERE role IN ('web_ro', 'bi_reader') AND schema_name = 'dw' AND object = '*';
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, except_columns, only_columns, note) VALUES
  ('web_ro', 'dw', 'v_*', 'SELECT', '{}', '{}', '{}', 'stable dw views only (docs/31 s3); no PII'),
  ('bi_reader', 'dw', 'v_*', 'SELECT', '{}', '{}', '{}', 'stable dw views only (docs/31 s3); nothing in app'),
  ('auth_rw', 'app', 'app_user', 'SELECT, UPDATE', '{}', '{}', '{}', 'login, lockout counters, password change'),
  ('auth_rw', 'app', 'refresh_family', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'refresh token families'),
  ('auth_rw', 'app', 'refresh_token', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'refresh token rotation'),
  ('auth_rw', 'app', 'auth_lockout', 'SELECT, INSERT, UPDATE, DELETE', '{}', '{}', '{}', 'lockout counter, cleared on success'),
  ('auth_rw', 'app', 'mfa_secret', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'TOTP enrolment and step'),
  ('auth_rw', 'app', 'device', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'device enrolment and binding'),
  ('auth_rw', 'app', 'device_binding', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'user-to-device binding'),
  ('auth_rw', 'app', 'device_otp', 'SELECT, INSERT, UPDATE', '{}', '{}', '{}', 'device one-time codes'),
  ('auth_rw', 'app', 'device_nonce', 'SELECT, INSERT', '{}', '{}', '{}', 'device proof replay guard'),
  ('auth_rw', 'app', 'enrolment_token', 'SELECT, UPDATE', '{}', '{}', '{}', 'enrolment token use count'),
  ('auth_rw', 'app', 'audit_log', 'SELECT, INSERT', '{}', '{}', '{}', 'security audit rows (the chain trigger reads the last row)'),
  ('auth_rw', 'app', 'user_scope', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'route_assignment', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'route', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'zone', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'territory', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'division', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'wing', 'SELECT', '{}', '{}', '{}', 'reach for the token'),
  ('auth_rw', 'app', 'cfg_key', 'SELECT', '{}', '{}', '{}', 'auth settings'),
  ('auth_rw', 'app', 'cfg_value', 'SELECT', '{}', '{}', '{}', 'auth settings'),
  ('auth_rw', 'app', 'cfg_version', 'SELECT', '{}', '{}', '{}', 'auth settings'),
  ('auth_rw', 'app', 'app_release', 'SELECT', '{}', '{}', '{}', 'minimum app version at login'),
  ('pii_reader', 'app', 'outlet', 'SELECT', '{}', '{}', '{id,code,name,owner_name,contact_number,address}',
   'outlet contact PII (D-107); NID, TIN and licence never'),
  ('support_ro', 'app', 'device', 'SELECT', '{}', '{}', '{}', 'device lookup for L1 support (D-138)'),
  ('support_ro', 'app', 'device_binding', 'SELECT', '{}', '{}', '{}', 'device lookup'),
  ('support_ro', 'app', 'device_status_report', 'SELECT', '{}', '{}', '{}', 'device health'),
  ('support_ro', 'app', 'device_directive', 'SELECT', '{}', '{}', '{}', 'pending device commands'),
  ('support_ro', 'app', 'app_release', 'SELECT', '{}', '{}', '{}', 'installed versus current release'),
  ('support_ro', 'app', 'sync_batch', 'SELECT', '{}', '{fingerprint,response_gz}', '{}', 'sync log without payloads');

-- apply_db_role_grants gains the 'v_*' object, only_columns, the three new roles and the admin function below.
CREATE OR REPLACE FUNCTION app.apply_db_role_grants() RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  g    record;
  t    record;
  cols text;
BEGIN
  FOR t IN SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname IN ('app','dw') AND c.relkind IN ('r','p','v','m') LOOP
    EXECUTE format('REVOKE ALL ON %I.%I FROM api_rw, worker_rw, jobs_rw, web_ro, bi_reader, auth_rw, pii_reader, support_ro',
                   t.nspname, t.relname);
  END LOOP;
  FOR g IN SELECT * FROM app.db_role_grant LOOP
    FOR t IN SELECT c.oid, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = g.schema_name AND c.relkind IN ('r','p','v','m') AND NOT c.relispartition
                AND ((g.object = 'v_*' AND c.relkind = 'v' AND c.relname LIKE 'v\_%')
                  OR (g.object <> 'v_*' AND (c.relname = g.object OR (g.object LIKE '*%' AND c.relname <> ALL (g.except_tables)))))
    LOOP
      IF cardinality(g.only_columns) > 0 THEN
        cols := (SELECT string_agg(quote_ident(x), ', ') FROM unnest(g.only_columns) x);
      ELSIF cardinality(g.except_columns) > 0 THEN
        cols := (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) FROM pg_attribute a
                  WHERE a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped AND a.attname <> ALL (g.except_columns));
      ELSE
        cols := NULL;
      END IF;
      IF cols IS NULL THEN
        EXECUTE format('GRANT %s ON %I.%I TO %I', g.privileges, g.schema_name, t.relname, g.role);
      ELSE
        EXECUTE format('GRANT %s (%s) ON %I.%I TO %I', g.privileges, cols, g.schema_name, t.relname, g.role);
      END IF;
    END LOOP;
  END LOOP;
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA app TO api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA dw TO worker_rw';
  EXECUTE 'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app, dw FROM PUBLIC';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA dw TO api_rw, worker_rw, web_ro, bi_reader';
  EXECUTE 'REVOKE EXECUTE ON FUNCTION app.apply_db_role_grants(), app.ensure_partitions(date, date), app.apply_login_limits() '
          'FROM api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT EXECUTE ON FUNCTION app.ensure_partitions(date, date) TO jobs_rw';
END $$;

-- Per-role session limits, written onto each login identity.
CREATE TABLE app.db_role_limit (
  role                                text PRIMARY KEY CHECK (role IN ('api_rw','worker_rw','jobs_rw','web_ro','bi_reader','auth_rw')),
  statement_timeout                   text NOT NULL CHECK (statement_timeout ~ '^[0-9]+(ms|s|min)$'),
  lock_timeout                        text CHECK (lock_timeout ~ '^[0-9]+(ms|s|min)$'),
  idle_in_transaction_session_timeout text NOT NULL CHECK (idle_in_transaction_session_timeout ~ '^[0-9]+(ms|s|min)$'),
  note                                text NOT NULL
);
INSERT INTO app.db_role_limit VALUES
  ('api_rw', '15s', '3s', '30s', 'docs/18 s2.6: api 15 s, lock 3 s'),
  ('auth_rw', '5s', '3s', '30s', 'docs/18 s2.6: auth 5 s, lock 3 s'),
  ('worker_rw', '10min', NULL, '30s', 'docs/18 s2.6: worker 10 min'),
  ('jobs_rw', '30min', NULL, '30s', 'docs/18 s2.6: jobs 30 min'),
  ('web_ro', '60s', NULL, '30s', 'docs/18 s2.6: web 60 s'),
  ('bi_reader', '10min', NULL, '30s', 'docs/18 s2.6: export and BI 10 min, on the replica');

-- Writes the limits of its privilege role onto every LOGIN that is a direct member of exactly one limited role (others
-- are skipped with a notice), for this database (ALTER ROLE ... IN DATABASE). Needs ADMIN on the login (the login's creator). Returns how many logins.
CREATE FUNCTION app.apply_login_limits() RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
  l record;
  n int := 0;
BEGIN
  FOR l IN
    SELECT u.rolname AS login, min(lim.role) AS role, count(*) AS roles
      FROM pg_roles u
      JOIN pg_auth_members m ON m.member = u.oid
      JOIN pg_roles g ON g.oid = m.roleid
      JOIN app.db_role_limit lim ON lim.role = g.rolname
     WHERE u.rolcanlogin AND (m.inherit_option OR m.set_option)   -- an ADMIN-only grant is not a membership in use
     GROUP BY u.rolname
  LOOP
    IF l.roles > 1 THEN
      -- An administrator or test login holding several roles keeps the server defaults; runtime logins hold one.
      RAISE NOTICE 'login % is a member of % privilege roles; no limits written', l.login, l.roles;
      CONTINUE;
    END IF;
    EXECUTE (SELECT format('ALTER ROLE %I IN DATABASE %I SET statement_timeout = %L', l.login, current_database(), x.statement_timeout)
               FROM app.db_role_limit x WHERE x.role = l.role);
    EXECUTE (SELECT format('ALTER ROLE %I IN DATABASE %I SET idle_in_transaction_session_timeout = %L', l.login, current_database(),
                           x.idle_in_transaction_session_timeout) FROM app.db_role_limit x WHERE x.role = l.role);
    IF (SELECT x.lock_timeout FROM app.db_role_limit x WHERE x.role = l.role) IS NOT NULL THEN
      EXECUTE (SELECT format('ALTER ROLE %I IN DATABASE %I SET lock_timeout = %L', l.login, current_database(), x.lock_timeout)
                 FROM app.db_role_limit x WHERE x.role = l.role);
    ELSE
      EXECUTE format('ALTER ROLE %I IN DATABASE %I RESET lock_timeout', l.login, current_database());
    END IF;
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;
REVOKE EXECUTE ON FUNCTION app.apply_login_limits() FROM PUBLIC;

-- The outlet view for dashboards and BI: no owner name, address or national ids; the phone masked as 01*****NNN.
CREATE VIEW dw.v_outlet_masked AS
SELECT o.id AS outlet_id, o.code AS outlet_code, o.name AS outlet_name, o.name_bn AS outlet_name_bn, o.zone_id, o.route_id,
       o.cluster_id, o.channel, o.sub_channel_id, o.geo_class, o.outlet_kind, o.price_type, o.status, o.location_confirmed,
       CASE WHEN o.contact_number ~ '^01[0-9]{9}$' THEN '01*****' || right(o.contact_number, 3) END AS contact_number_masked,
       o.updated_at
  FROM app.outlet o;

REVOKE ALL ON SCHEMA app, dw FROM PUBLIC;
GRANT USAGE ON SCHEMA app TO auth_rw, pii_reader, support_ro;
SELECT app.apply_db_role_grants();

COMMENT ON COLUMN app.db_role_grant.only_columns IS 'Columns a single-table row grants (column grant); empty grants the whole table.';
COMMENT ON TABLE app.db_role_limit IS 'Session limits per privilege role (docs/18 s2.6); app.apply_login_limits() writes them onto the login identities.
owner: db | capture: REFERENCE | retention: master | pii: none';
COMMENT ON COLUMN app.db_role_limit.role IS 'Privilege role the limits belong to.';
COMMENT ON COLUMN app.db_role_limit.statement_timeout IS 'statement_timeout for its logins, for example 15s.';
COMMENT ON COLUMN app.db_role_limit.lock_timeout IS 'lock_timeout for its logins; null leaves the server default.';
COMMENT ON COLUMN app.db_role_limit.idle_in_transaction_session_timeout IS 'idle_in_transaction_session_timeout for its logins.';
COMMENT ON COLUMN app.db_role_limit.note IS 'Source of the limit.';
COMMENT ON FUNCTION app.apply_login_limits() IS 'Writes the session limits of app.db_role_limit onto every login that is a member of one privilege role; run by the migrate job after creating logins.';
COMMENT ON VIEW dw.v_outlet_masked IS 'Stable view: outlets without owner name, address or national ids; the phone masked to 01*****NNN (D-107).
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN dw.v_outlet_masked.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_outlet_masked.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_outlet_masked.outlet_name IS 'Shop name (English).';
COMMENT ON COLUMN dw.v_outlet_masked.outlet_name_bn IS 'Shop name in Bangla.';
COMMENT ON COLUMN dw.v_outlet_masked.zone_id IS 'Zone (app.zone).';
COMMENT ON COLUMN dw.v_outlet_masked.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.v_outlet_masked.cluster_id IS 'Cluster (market) of the outlet.';
COMMENT ON COLUMN dw.v_outlet_masked.channel IS 'Channel (GT, DCC, Astha, RCC, MT, HoReCa).';
COMMENT ON COLUMN dw.v_outlet_masked.sub_channel_id IS 'Sub-channel (app.sub_channel).';
COMMENT ON COLUMN dw.v_outlet_masked.geo_class IS 'Geographic class of the outlet.';
COMMENT ON COLUMN dw.v_outlet_masked.outlet_kind IS 'retail or wholesale.';
COMMENT ON COLUMN dw.v_outlet_masked.price_type IS 'Price type the outlet buys at.';
COMMENT ON COLUMN dw.v_outlet_masked.status IS 'active, closed, merged or archived.';
COMMENT ON COLUMN dw.v_outlet_masked.location_confirmed IS 'True when the master location is confirmed.';
COMMENT ON COLUMN dw.v_outlet_masked.contact_number_masked IS 'Phone masked as 01*****NNN (last three digits); null when not a Bangladeshi mobile number.';
COMMENT ON COLUMN dw.v_outlet_masked.updated_at IS 'UTC instant of the last update.';
