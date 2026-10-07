-- Runtime database logins (docs/requests/db-runtime-roles.md): one LOGIN per process role, each a member of exactly
-- one NOLOGIN privilege role from db migration V0014, WITH INHERIT TRUE; never superuser, CREATEDB, CREATEROLE,
-- REPLICATION or BYPASSRLS. Idempotent: creates a missing login, re-applies the attributes and the password from Key
-- Vault (repairs drift), adds the membership, and revokes any other membership a login may have gained.
--   app_api     -> api_rw     the api container
--   app_worker  -> worker_rw  reserved for a worker without scheduled jobs (not used by a container today)
--   app_jobs    -> jobs_rw    the worker container: it runs the scheduled partition and retention jobs, and jobs_rw
--                             holds every worker_rw right plus app.ensure_partitions()
-- Run by the server admin login (it created the V0014 roles, so it holds ADMIN on them) through the dblogins job
-- (infra/apps.bicep) right after the migrations, and by infra/scripts/image-smoke.sh in CI.
-- Passwords come from the environment (psql \getenv), never from the command line or this file.
\set ON_ERROR_STOP on
\getenv pw_api ARON_PW_APP_API
\getenv pw_worker ARON_PW_APP_WORKER
\getenv pw_jobs ARON_PW_APP_JOBS
-- \gset stores the (secret) results in variables instead of printing them.
SELECT set_config('aron.pw_app_api', :'pw_api', false) AS _a,
       set_config('aron.pw_app_worker', :'pw_worker', false) AS _b,
       set_config('aron.pw_app_jobs', :'pw_jobs', false) AS _c \gset
SET lock_timeout = '5s';

DO $$
DECLARE
  l   record;
  a   record;
  pw  text;
  g   record;
BEGIN
  FOR l IN SELECT * FROM (VALUES ('app_api', 'api_rw'), ('app_worker', 'worker_rw'), ('app_jobs', 'jobs_rw')) v(login, grp) LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = l.grp) THEN
      RAISE EXCEPTION 'privilege role % does not exist: run the migrations (V0014) first', l.grp;
    END IF;
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
    -- Exactly one membership: drop any other (a login must never inherit more than its privilege role).
    FOR g IN SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
              WHERE m.member = l.login::regrole AND r.rolname <> l.grp LOOP
      EXECUTE format('REVOKE %I FROM %I', g.rolname, l.login);
      RAISE NOTICE 'revoked % from %', g.rolname, l.login;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid = l.grp::regrole AND m.member = l.login::regrole
                      AND m.inherit_option) THEN
      EXECUTE format('GRANT %I TO %I WITH INHERIT TRUE', l.grp, l.login);
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), l.login);
  END LOOP;
END $$;

-- Evidence for the deploy log (no secrets): each login, its attributes and its one membership.
SELECT r.rolname AS login, r.rolcanlogin AS can_login, r.rolsuper AS superuser, r.rolcreaterole AS createrole,
       r.rolbypassrls AS bypassrls, string_agg(g.rolname, ',') AS member_of
  FROM pg_roles r LEFT JOIN pg_auth_members m ON m.member = r.oid LEFT JOIN pg_roles g ON g.oid = m.roleid
 WHERE r.rolname IN ('app_api', 'app_worker', 'app_jobs')
 GROUP BY 1, 2, 3, 4, 5 ORDER BY 1;
