-- V0068 trigram indexes for outlet search (AUD-PERF-07). Released from db/held after infra allow-listed PG_TRGM in
-- azure.extensions (lane/infra bb221dfe; docs/requests/db-azure-pg-trgm.md). ORDER: this migration must deploy in the
-- same or a later deploy than that infra change (the infra stage sets the server parameter before the migrate job);
-- on a server without PG_TRGM allow-listed CREATE EXTENSION fails and the migration rolls back whole.
--
-- AUD-PERF-07: outlet search is name ILIKE '%q%' OR code ILIKE '%q%' (backend/masterdata OutletsApi). Trigram GIN
-- indexes serve both sides of the OR (BitmapOr) for q of 3 or more characters. name_bn is not searched today, so it
-- gets no index. app.outlet is pilot-sized in the test account and empty when a new (final) account is migrated, so the
-- builds are marked for squawk instead of CONCURRENTLY (Flyway runs each migration in one transaction).

SET lock_timeout = '5s';

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX outlet_name_trgm ON app.outlet USING gin (name gin_trgm_ops);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX outlet_code_trgm ON app.outlet USING gin (code gin_trgm_ops);
