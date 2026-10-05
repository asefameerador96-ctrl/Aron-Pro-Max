# Request: backend runtime contract for the Azure deploy (from infra, 2026-10-05)

**To:** backend lane (items 1 to 6); the architect for `docs/24` s6.4 (item 7). **Blocks:** the automatic deploy
stops at the migrations step until item 1 exists (manual deploys can untick `run_migrations`).

1. **`ARON_ROLE=migrate`** in `backend:app` `main()`: run Flyway `migrate` from `classpath:db/migration` against
   `ARON_DB_URL`, log the applied versions, exit 0 on success and non-zero on any failure (the deploy waits for the job
   and stops when it fails). Today `main()` throws for any role other than api/worker.
2. **`GET /v1/health/ready`** (in the contract, `getReadiness`): 200 when a `SELECT 1` through the pool answers within
   2 s, 503 otherwise, with `X-Aron-Api: 1`. Liveness stays `/v1/health` (process only), so a database failover never
   restarts every replica. Dev probes `/v1/health` until this exists; prod probes `/v1/health/ready`.
3. **Worker keeps running.** The placeholder worker exits at once; Container Apps restarts it in a loop. Until the job
   loop exists, block (for example `Thread.currentThread().join()`) after logging.
4. **Database URLs carry their credentials.** `ARON_DB_URL` / `ARON_DB_READ_URL` are complete JDBC URLs from Key Vault
   (`...?sslmode=require&user=...&password=...`). The api gets the PgBouncer URL (port 6432, `prepareThreshold=0`,
   transaction pooling: no session state, no session advisory locks, no `LISTEN`); the worker and migrate get the
   direct URL (5432) because they take advisory locks. Use transaction-scoped locks (`pg_try_advisory_xact_lock`) in
   api code if one is ever needed there.
5. **New environment variables set by the deploy** (all roles unless noted):
   `ARON_BLOB_CONTAINER_BUNDLES` (`bundles`), `ARON_MEDIA_EVENTS_QUEUE` (`media-events`, Event Grid BlobCreated
   messages for the `media` container; the worker drains it, at-least-once, handler idempotent),
   `ARON_FRONT_DOOR_ID` (Front Door profile id: when set, the api SHOULD answer 403 to a request whose `X-Azure-FDID`
   header is different, so the dev apps cannot be reached around the WAF), `AZURE_CLIENT_ID` (the role's managed
   identity, for `DefaultAzureCredential`: blob user-delegation keys and the queue), `APPLICATIONINSIGHTS_ROLE_NAME`.
   Shared-key access on the storage account is OFF: user-delegation SAS only (docs/24 s4.11 already says so).
6. **FCM placeholder.** `ARON_FCM_SERVICE_ACCOUNT_JSON` is `{}` until the sponsor sets the GitHub secret; treat `{}`
   (or empty) as "push disabled" (`ERR_PUSH_DISABLED`), never as a startup failure.
7. **docs/24 s6.4**: please add the variables of item 5 and the direct/pooled split of item 4 to the table.
8. For the alerts of docs/24 s13.1 that need application metrics (worker lag, `payload_conflict`,
   `memo_no_duplicate`, pending rows), tell infra the metric names you emit (OpenTelemetry through the agent); infra
   adds the alert rules.
