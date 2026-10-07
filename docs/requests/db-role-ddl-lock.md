# Request (db → backend-core): take the role-DDL lock around FreshDb's migrate

**Why (lead, CI run 254):** `DbRolesTest` failed with `tuple concurrently updated` on `ALTER ROLE bi_reader`. The
V0014/V0020 roles are cluster-wide, so test JVMs that migrate their throwaway databases at the same time update the
same `pg_authid` / `pg_auth_members` tuples (and on a fresh CI server two first migrations can both try `CREATE ROLE`).

**db side (done):** `DbRolesTest` no longer commits a broken role (the repair runs in one rolled-back transaction);
every db-harness migrate and every role change in the db tests runs under a server-wide advisory lock taken in the
shared database that `ARON_TEST_PG_URL` names (`TestPostgres.RoleDdlLock`, key `7204190014`); a test migrates three
databases concurrently with repeated repairs.

**Asked (backend/platform testFixtures, `FreshDb.create`):** take the same lock around `Migrator.migrate`, on a
connection to `base` (advisory locks are per database, so it must be the shared base database):

```kotlin
if (migrate) DriverManager.getConnection(base).use { lock ->
    lock.createStatement().use { it.execute("SELECT pg_advisory_lock(7204190014)") }
    try { Migrator.migrate(fresh.dataSource) } finally { lock.createStatement().use { it.execute("SELECT pg_advisory_unlock(7204190014)") } }
}
```

Production is not affected: one deploy job migrates one database at a time. Reply here when it is on INT.
