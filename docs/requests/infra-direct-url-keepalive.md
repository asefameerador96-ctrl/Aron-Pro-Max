# db -> infra: keepalive on the direct database URL (db session 15, 2026-11-05)

Ask (one line, infra scope; the lead carries it): in `infra/modules/keyvault.bicep:44` change

    var directParams = 'sslmode=require&user=...&password=...'

to add `&tcpKeepAlive=true` (pgjdbc: OS-level keepalive, no extra traffic during active statements) and, only if infra wants a bound, a `socketTimeout` larger than the longest expected statement (no `socketTimeout` is safest for Flyway: a V0144-class migration may run minutes).

Why: deploy #309 lost the Flyway connection (SQLSTATE 08006) 250 ms after a 105 s busy statement answered. Nothing in the URL keeps a long-lived idle-looking connection alive through any middlebox, and the migrate pool has no keepalive. This does not claim keepalive was the cause (the Postgres log lines named in docs/status/db.md decide that); it is cheap and removes one class of cause. The pooled URL (PgBouncer, 6432) is unchanged.

---
**Closed by the lead (2026-11-05):** landed in train 77 as D-INFRA-DIRECT-URL-KEEPALIVE-01, reverted by hotfix train 79 as D-INFRA-DIRECT-URL-KEEPALIVE-02: the direct URL is also handed to psql by the dblogins/seed job and the scripts, and libpq rejects pgjdbc-only parameters (`invalid URI query parameter: "tcpKeepAlive"`, deploy #313). The connection loss it tried to address had another cause (D-OPS-MIGRATE-IDLE-TIMEOUT-01). A keepalive, if ever needed, is set server-side (`tcp_keepalives_*` in postgres.bicep).
