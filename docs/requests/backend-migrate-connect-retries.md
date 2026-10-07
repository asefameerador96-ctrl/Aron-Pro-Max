# Request to backend-core: the migrate role waits for its first database connection

From: infra lane, 2026-10-07. Row: N-013 (deploy), follow-up of the failed migrate execution of run dd3fb58.

## What happens

Twice in two days, the dev deploy's migrate job execution ended Failed. Both executions were `caj-aron-dev-migrate-0glfqfl`
on 2026-10-06 and `caj-aron-dev-migrate-phf2oxm` on 2026-10-07 at 04:09 UTC. The console log of the second, now printed
by `infra/deploy.sh`, shows:

```
ERROR [main] aron.main - migrate failed
FlywaySqlUnableToConnectToDbException: Unable to obtain connection from database: aron-migrate -
  Connection is not available, request timed out after 5005ms (total=0, active=0, idle=0, waiting=0)
  at com.aktcl.aron.backend.platform.Migrator.migrate(Database.kt:66)
```

`total=0` means that, 5 s after the pool started, the fresh job replica had not yet opened its first connection to
PostgreSQL. The replica is in the VNet, and the connection goes through private DNS, TLS and SCRAM to the zone-redundant
server. The same image migrated fine minutes before and after. Nothing was wrong with the database or the migrations.

## Ask

Only in the `migrate` role, wait for the database before failing:

- `Migrator.migrate` in `backend/platform/src/main/kotlin/com/aktcl/aron/backend/platform/Database.kt`: add
  `.connectRetries(10)` (Flyway retries with backoff, about 2 minutes in total) and `.connectRetriesInterval(15)`.
- A longer `connectionTimeout` (for example 30 s) for the migrate pool only. The api and worker pools keep 5 s, so that
  requests fail fast.

The api is not affected, because `initializationFailTimeout = -1` lets it start and become ready later.

## Until then

`infra/deploy.sh` runs the migrate job a second time, once only, and only when the first execution failed with exactly
this connect error (Flyway is idempotent). Any other migration failure still stops the deploy at once. When this request
is done, the infra lane keeps the guard but expects never to see the second attempt in a log.
