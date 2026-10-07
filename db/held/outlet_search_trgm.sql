-- HELD (not a migration yet): released as the next V####__outlet_search_trgm.sql once infra confirms PG_TRGM is in
-- azure.extensions on dev (docs/requests/db-azure-pg-trgm.md). Flyway does not read db/held.
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
