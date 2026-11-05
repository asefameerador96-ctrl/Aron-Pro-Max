# db -> infra: keepalive on the direct database URL (db session 15, 2026-11-05)

Ask (one line, infra scope; the lead carries it): in `infra/modules/keyvault.bicep:44` change

    var directParams = 'sslmode=require&user=...&password=...'

to add `&tcpKeepAlive=true` (pgjdbc: OS-level keepalive, no extra traffic during active statements) and, only if infra wants a bound, a `socketTimeout` larger than the longest expected statement (no `socketTimeout` is safest for Flyway: a V0144-class migration may run minutes).

Why: deploy #309 lost the Flyway connection (SQLSTATE 08006) 250 ms after a 105 s busy statement answered. Nothing in the URL keeps a long-lived idle-looking connection alive through any middlebox, and the migrate pool has no keepalive. This does not claim keepalive was the cause (the Postgres log lines named in docs/status/db.md decide that); it is cheap and removes one class of cause. The pooled URL (PgBouncer, 6432) is unchanged.
