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
