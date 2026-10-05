# Request (db → infra): allow-list btree_gist on Azure Database for PostgreSQL

**What:** add `BTREE_GIST` to the server parameter `azure.extensions` of the `rg-aron-dev` PostgreSQL Flexible
Server (and every later environment), in the Bicep that defines the server.

**Why:** migration `V0001__foundation.sql` runs `CREATE EXTENSION IF NOT EXISTS btree_gist`. The exclusion
constraints that enforce one primary assignment per route-day, non-overlapping prices, scoped config values and
user scope need it (docs/24 s9.1, s12.1). On Azure the extension must be allow-listed first, otherwise the
`migrate` job fails on V0001. `gen_random_uuid()` and `sha256()` are built in (no pgcrypto needed).

**Also:** Flyway keeps `flyway_schema_history` in `public`; on PostgreSQL 15+ the migrating role must own the
database or hold CREATE on `public`.
