# Request (db → infra): runtime database logins as members of the least-privilege roles

**What exists (V0014, pushed):** NOLOGIN privilege roles `api_rw`, `worker_rw`, `jobs_rw` (member of `worker_rw`),
`web_ro`, `bi_reader`, with grants generated from `app.db_role_grant` and proved by `DbRolesTest`. The migrating login
owns every object; `app.ensure_partitions()` is SECURITY DEFINER and callable only by `jobs_rw`.

**Asked (infra, Bicep / bootstrap):**
1. One LOGIN per container app (Entra managed identity or password in Key Vault), never the server admin:
   `app_api` → `GRANT api_rw TO app_api`; `app_worker` → `worker_rw`; `app_jobs` (the scheduled partition and
   retention job) → `jobs_rw`; `app_web` (if the web BFF ever reads the database) → `web_ro`; `bi_reader` on the
   read replica → `bi_reader`. Each membership `WITH INHERIT TRUE`; none of them superuser, CREATEROLE or BYPASSRLS.
2. Keep the server admin login only for the `migrate` job (it needs CREATEROLE once, to create the five roles).
3. Point `ARON_DB_URL` of api and worker at their own logins (and `ARON_DB_READ_URL` at the replica login).

Until then the apps keep working on the admin login; nothing breaks, but least privilege is not in force.

## Update 2026-10-07 (V0020; audit AUD-DA-03, AUD-TP-7, AUD-REL-03)

**New in the database:** privilege roles `auth_rw` (login path), `pii_reader` (outlet contact columns) and
`support_ro` (device tables, sync log without payloads). `web_ro` and `bi_reader` now read only the `dw.v_*` views,
including `dw.v_outlet_masked`. `app.db_role_limit` holds the docs/18 s2.6 limits: api 15 s with lock 3 s, auth 5 s
with lock 3 s, worker 10 min, jobs 30 min, web 60 s, BI 10 min, and idle-in-transaction 30 s for all.
`app.apply_login_limits()` writes these onto every login that belongs to exactly one privilege role.

**Asked of infra (migrate job, Bicep, `seed-secrets.sh`):**
1. Create the login identities, each a member of exactly one privilege role `WITH INHERIT TRUE`:
   - `app_api` → `api_rw`
   - `app_auth` → `auth_rw` (if auth gets its own pool; otherwise skip it)
   - `app_worker` → `worker_rw`
   - `app_jobs` → `jobs_rw`
   - `app_web` → `web_ro`
   - `bi_reader` login → `bi_reader`
   
   Additionally grant `pii_reader` and `support_ro` to `app_api` `WITH INHERIT FALSE, SET TRUE`, so they are reachable only by `SET LOCAL ROLE`.
2. After `flyway migrate` and the login grants, run `SELECT app.apply_login_limits();` as the migrator in the same job.
   It needs ADMIN on the logins, which their creator has.
3. Run Flyway itself with `lock_timeout` 5 s. Every new migration already sets it, and the squawk gate enforces that.
   On SQLSTATE 55P03, retry the job once after 30 s.

**Asked of backend-core and CI (AUD-TP-7):**
4. Stop connecting as a superuser:
   - CI's PostgreSQL service container gives the tests a non-superuser login with CREATEDB and CREATEROLE.
   - The ingest, auth and scope test suites run their database calls as `api_rw`, either with
     `SET ROLE api_rw` after `GRANT api_rw TO CURRENT_USER WITH INHERIT FALSE, SET TRUE` (the pattern of `DbRolesTest`)
     or through a login that is a member.
   - A missing grant then fails a test instead of production.
5. Each pool sets its role's `ApplicationName`. Map timeouts: 57014 (statement timeout) and 55P03 (lock timeout) → 503 with Retry-After (see AUD-REL-02).
