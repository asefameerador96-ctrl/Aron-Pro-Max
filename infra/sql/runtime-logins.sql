-- Runtime database logins (docs/requests/db-runtime-roles.md incl. its V0020 update): one LOGIN per process role,
-- each inheriting exactly one NOLOGIN privilege role (V0014, V0020); never superuser, CREATEDB, CREATEROLE,
-- REPLICATION or BYPASSRLS. Idempotent: creates a missing login, re-applies the attributes and the password from Key
-- Vault (repairs drift), sets the memberships with their exact options, revokes any other membership, and finally
-- writes the per-role session limits (app.apply_login_limits(), V0020).
--   app_api     -> api_rw     the api container; plus pii_reader and support_ro WITH INHERIT FALSE, SET TRUE (reachable
--                             only by SET LOCAL ROLE)
--   app_worker  -> worker_rw  reserved for a worker without scheduled jobs (not used by a container today)
--   app_jobs    -> jobs_rw    the worker container: it runs the scheduled partition and retention jobs, and jobs_rw
--                             holds every worker_rw right plus app.ensure_partitions()
--   app_bi      -> bi_reader  BI tools (D-INFRA-LAKE-01): SELECT on the stable dw.v_* views (V0020, V0118: no memo_no
--                             column) plus EXECUTE on the dw functions, all invoker rights; the privilege role holds the
--                             name bi_reader, so the BI login is app_bi
--   lake_export -> bi_reader  the worker's nightly Parquet export to the data lake (action row 8): the same rights
-- Run by the server admin login (it created the V0014 roles, so it holds ADMIN on them) through the dblogins job
-- (infra/apps.bicep) right after the migrations, and by infra/scripts/image-smoke.sh in CI.
-- Passwords come from the environment (psql \getenv), never from the command line or this file.
\set ON_ERROR_STOP on
\getenv pw_api ARON_PW_APP_API
\getenv pw_worker ARON_PW_APP_WORKER
\getenv pw_jobs ARON_PW_APP_JOBS
\getenv pw_bi ARON_PW_APP_BI
\getenv pw_lake ARON_PW_LAKE_EXPORT
-- A missing variable would leave its :'name' unexpanded and the server would log the statement with the OTHER
-- passwords in it: stop before that.
\if :{?pw_api}
\else
  DO $$ BEGIN RAISE EXCEPTION 'ARON_PW_APP_API is not set'; END $$;
\endif
\if :{?pw_worker}
\else
  DO $$ BEGIN RAISE EXCEPTION 'ARON_PW_APP_WORKER is not set'; END $$;
\endif
\if :{?pw_jobs}
\else
  DO $$ BEGIN RAISE EXCEPTION 'ARON_PW_APP_JOBS is not set'; END $$;
\endif
\if :{?pw_bi}
\else
  DO $$ BEGIN RAISE EXCEPTION 'ARON_PW_APP_BI is not set'; END $$;
\endif
\if :{?pw_lake}
\else
  DO $$ BEGIN RAISE EXCEPTION 'ARON_PW_LAKE_EXPORT is not set'; END $$;
\endif
-- \gset stores the (secret) results in variables instead of printing them.
SELECT set_config('aron.pw_app_api', :'pw_api', false) AS _a,
       set_config('aron.pw_app_worker', :'pw_worker', false) AS _b,
       set_config('aron.pw_app_jobs', :'pw_jobs', false) AS _c,
       set_config('aron.pw_app_bi', :'pw_bi', false) AS _d,
       set_config('aron.pw_lake_export', :'pw_lake', false) AS _e \gset
SET lock_timeout = '5s';

-- Wanted memberships: login, privilege role, inherit (false = reachable only by SET ROLE).
CREATE TEMP TABLE memberships (login text, grp text, inherit boolean) ON COMMIT PRESERVE ROWS;
INSERT INTO memberships VALUES
  ('app_api', 'api_rw', true), ('app_api', 'pii_reader', false), ('app_api', 'support_ro', false),
  ('app_worker', 'worker_rw', true),
  ('app_jobs', 'jobs_rw', true),
  ('app_bi', 'bi_reader', true),
  ('lake_export', 'bi_reader', true);

DO $$
DECLARE
  l   record;
  a   record;
  pw  text;
  g   record;
BEGIN
  FOR l IN SELECT DISTINCT login FROM memberships ORDER BY login LOOP
    pw := current_setting('aron.pw_' || l.login);
    IF pw IS NULL OR length(pw) < 24 THEN
      RAISE EXCEPTION 'no password (at least 24 characters) for % in the environment', l.login;
    END IF;
    SELECT * INTO a FROM pg_roles WHERE rolname = l.login;
    IF FOUND AND (a.rolsuper OR a.rolreplication OR a.rolbypassrls) THEN
      RAISE EXCEPTION 'login % is superuser, replication or bypasses RLS; only a human with superuser can fix that', l.login;
    END IF;
    -- Statements carrying a password run inside their own handler, so an error never echoes the statement (and
    -- with it the password) into the job log or the server log context.
    BEGIN
      IF NOT FOUND THEN
        EXECUTE format('CREATE ROLE %I LOGIN INHERIT NOCREATEDB NOCREATEROLE PASSWORD %L', l.login, pw);
        RAISE NOTICE 'created login %', l.login;
      ELSE
        EXECUTE format('ALTER ROLE %I LOGIN INHERIT NOCREATEDB NOCREATEROLE PASSWORD %L', l.login, pw);
      END IF;
    EXCEPTION WHEN others THEN
      RAISE EXCEPTION 'could not create or update login %: % (SQLSTATE %)', l.login, SQLERRM, SQLSTATE;
    END;
    -- Exactly these memberships, with exactly these options; anything else is revoked. A membership row belongs to its
    -- grantor, so each unwanted row is revoked GRANTED BY that grantor; a row this login may not revoke (granted by a
    -- superuser) stops the job for a human, like V0014 does.
    FOR g IN SELECT r.rolname, gr.rolname AS grantor FROM pg_auth_members m
               JOIN pg_roles r ON r.oid = m.roleid JOIN pg_roles gr ON gr.oid = m.grantor
              WHERE m.member = l.login::regrole
                AND NOT EXISTS (SELECT 1 FROM memberships x WHERE x.login = l.login AND x.grp = r.rolname
                                   AND x.inherit = m.inherit_option AND m.set_option AND NOT m.admin_option) LOOP
      BEGIN
        EXECUTE format('REVOKE %I FROM %I GRANTED BY %I', g.rolname, l.login, g.grantor);
      EXCEPTION WHEN others THEN
        RAISE EXCEPTION 'cannot revoke % from % (granted by %): %; revoke it by hand as that grantor', g.rolname, l.login, g.grantor, SQLERRM;
      END;
      RAISE NOTICE 'revoked % from % (granted by %; not wanted, or wrong options)', g.rolname, l.login, g.grantor;
    END LOOP;
    FOR g IN SELECT x.grp, x.inherit FROM memberships x WHERE x.login = l.login LOOP
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = g.grp) THEN
        RAISE EXCEPTION 'privilege role % does not exist: run the migrations first', g.grp;
      END IF;
      IF NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid = g.grp::regrole AND m.member = l.login::regrole) THEN
        EXECUTE format('GRANT %I TO %I WITH INHERIT %s, SET TRUE', g.grp, l.login, CASE WHEN g.inherit THEN 'TRUE' ELSE 'FALSE' END);
      END IF;
    END LOOP;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), l.login);
  END LOOP;
END $$;

-- Verify: every login holds exactly its wanted memberships, one row each, with the wanted options (a REVOKE that
-- silently matched nothing, or a duplicate row from another grantor, fails here instead of passing).
DO $$
DECLARE bad text;
BEGIN
  SELECT string_agg(format('%s/%s', w.login, w.grp), ', ') INTO bad FROM memberships w
   WHERE (SELECT count(*) FROM pg_auth_members m WHERE m.member = w.login::regrole AND m.roleid = w.grp::regrole
             AND m.inherit_option = w.inherit AND m.set_option AND NOT m.admin_option) <> 1;
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'memberships not exactly as wanted: %', bad; END IF;
  SELECT string_agg(format('%s/%s', u.rolname, r.rolname), ', ') INTO bad
    FROM (SELECT DISTINCT login FROM memberships) l JOIN pg_roles u ON u.rolname = l.login
    JOIN pg_auth_members m ON m.member = u.oid JOIN pg_roles r ON r.oid = m.roleid
   WHERE NOT EXISTS (SELECT 1 FROM memberships w WHERE w.login = l.login AND w.grp = r.rolname);
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'unwanted memberships remain: %', bad; END IF;
END $$;

-- Read-only logins: SELECT on dw.v_* views and nothing else in app, dw, stg or public (no other relation, no write on
-- any, no CREATE), and no SECURITY DEFINER function they may execute there (one would lend them its owner's writes).
-- Extension objects are left out (Azure's own, granted to PUBLIC). Proves the bi_reader grant map as the logins see
-- it, after every deploy.
DO $$
DECLARE bad text;
BEGIN
  SELECT string_agg(format('%s:%s.%s', l, n.nspname, c.relname), ', ') INTO bad
    FROM unnest(ARRAY['app_bi', 'lake_export']) l
    CROSS JOIN pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname IN ('app', 'dw', 'stg', 'public') AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
     AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
     AND (has_any_column_privilege(l, c.oid, 'INSERT') OR has_any_column_privilege(l, c.oid, 'UPDATE')
          OR has_any_column_privilege(l, c.oid, 'REFERENCES')
          OR (c.relkind <> 'S' AND (has_table_privilege(l, c.oid, 'DELETE') OR has_table_privilege(l, c.oid, 'TRUNCATE')
                                    OR has_table_privilege(l, c.oid, 'TRIGGER')))
          OR (c.relkind = 'S' AND has_sequence_privilege(l, c.oid, 'USAGE'))
          OR (has_any_column_privilege(l, c.oid, 'SELECT') AND NOT (n.nspname = 'dw' AND c.relkind = 'v' AND c.relname LIKE 'v\_%')));
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'read-only logins reach more than SELECT on dw.v_*: %', bad; END IF;
  SELECT string_agg(format('%s:%s', l, n.nspname), ', ') INTO bad
    FROM unnest(ARRAY['app_bi', 'lake_export']) l CROSS JOIN pg_namespace n
   WHERE n.nspname IN ('app', 'dw', 'stg', 'public') AND has_schema_privilege(l, n.oid, 'CREATE');
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'read-only logins may create objects in: %', bad; END IF;
  SELECT string_agg(format('%s:%s.%s', l, n.nspname, f.proname), ', ') INTO bad
    FROM unnest(ARRAY['app_bi', 'lake_export']) l
    CROSS JOIN pg_proc f JOIN pg_namespace n ON n.oid = f.pronamespace
   WHERE n.nspname IN ('app', 'dw', 'stg', 'public') AND f.prosecdef AND has_function_privilege(l, f.oid, 'EXECUTE')
     AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = f.oid AND d.deptype = 'e');
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'read-only logins may execute SECURITY DEFINER functions: %', bad; END IF;
  SELECT string_agg(l, ', ') INTO bad FROM unnest(ARRAY['app_bi', 'lake_export']) l
   WHERE NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'dw' AND c.relkind = 'v' AND c.relname LIKE 'v\_%'
                        AND has_table_privilege(l, c.oid, 'SELECT'));
  IF bad IS NOT NULL THEN RAISE EXCEPTION 'read-only logins cannot read any dw.v_* view: %', bad; END IF;
END $$;

-- Session limits per privilege role (V0020): statement, lock and idle-in-transaction timeouts on each login.
SELECT app.apply_login_limits() AS logins_with_limits;

-- Evidence for the deploy log (no secrets): each login, its attributes and its memberships (* = inherited).
SELECT r.rolname AS login, r.rolcanlogin AS can_login, r.rolsuper AS superuser, r.rolcreaterole AS createrole,
       r.rolbypassrls AS bypassrls, string_agg(g.rolname || CASE WHEN m.inherit_option THEN '*' ELSE '' END, ',' ORDER BY g.rolname) AS member_of
  FROM pg_roles r LEFT JOIN pg_auth_members m ON m.member = r.oid LEFT JOIN pg_roles g ON g.oid = m.roleid
 WHERE r.rolname IN ('app_api', 'app_worker', 'app_jobs', 'app_bi', 'lake_export')
 GROUP BY 1, 2, 3, 4, 5 ORDER BY 1;
