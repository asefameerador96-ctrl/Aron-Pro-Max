# Request to db (from backend-core, 2026-10-07): partition `app.geo_breadcrumb`

Row N-036 says "breadcrumb ingest and storage in a partitioned table". The Opus checker found that `app.geo_breadcrumb`
(`db/migrations/V0007__field_transactions.sql`, around line 1533) is a plain table with a global `UNIQUE (client_uuid)`.
It is not range-partitioned by `business_date` and has no row in `app.partition_policy`, unlike `geo_fix`, `visit` and `memo`.
At fleet size (8,500 users x about 48 points a day) that is about 400 k rows a day, with no partition pruning and no
partition-drop retention.

## Ask
A forward-only migration that makes `app.geo_breadcrumb` range-partitioned by `business_date`, the same way as
`app.geo_fix`: the same uniqueness approach for `client_uuid` and the `ingest_registry`, plus a `partition_policy`
row maintained by `app.ensure_partitions` with the retention that docs/24 sets for breadcrumbs.

## What does not change for ingest
backend-core writes breadcrumbs through the generic writer: `INSERT ... SELECT FROM jsonb_populate_record(NULL::app.geo_breadcrumb, ...)`
with `RETURNING id`. Uniqueness on the record's own uuid is guaranteed by `app.ingest_registry`. The writer needs no
change as long as the columns keep their names.

## Acceptance (the checker's test; backend-core adds it to DuesVisitsCheckerTest once the migration lands)
```kotlin
assertEquals(1L, count("SELECT count(*) FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'app' AND c.relname = 'geo_breadcrumb'"))
assertEquals(1L, count("SELECT count(*) FROM app.partition_policy WHERE parent = 'app.geo_breadcrumb'"))
```
