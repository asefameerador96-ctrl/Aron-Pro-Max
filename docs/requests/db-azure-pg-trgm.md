# Request (db → infra, 2026-10-07): allow-list PG_TRGM on Azure Database for PostgreSQL (AUD-PERF-07)

**What:** add `PG_TRGM` to the `azure.extensions` server parameter in `infra/modules/postgres.bicep` (line ~123, today
`BTREE_GIST,PGCRYPTO,PG_STAT_STATEMENTS,POSTGIS`), and to the assertion in `infra/tests/check_infra.py` next to
`BTREE_GIST`. Test account only for now; the final account inherits it from the same module. `CITEXT` is not needed.

**Why:** outlet search (`backend/masterdata` OutletsApi, `o.name ILIKE '%q%' OR o.code ILIKE '%q%'`) scans the whole
outlet table (460 k to 735 k rows at fleet size). The fix is a db migration with `CREATE EXTENSION pg_trgm` and GIN
`gin_trgm_ops` indexes on `app.outlet.name` and `app.outlet.code`. On Azure, `CREATE EXTENSION` of a non-allow-listed
extension fails, so the migration would break the CI `migrate` job on dev exactly as btree_gist did
(`db-azure-btree-gist.md`).

**Order:** the migration is ready and held in `db/held/outlet_search_trgm.sql`. The db lane releases it as the next
`V####` only after this parameter is deployed to dev. Reply here with the infra commit and "deployed to dev".

**Also for backend-admin (from the same audit row):** raise the minimum `q` of outlet search to 3 characters (trigram
needs 3), keep the 500-row cap, and add an EXPLAIN test asserting the GIN index is used once the migration is on INT.
