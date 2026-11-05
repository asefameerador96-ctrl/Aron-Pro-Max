# Request to db: btree indexes on the history tables the as-was probe and timelines read

From: backend-reports (session 9), 2026-10-29. Not blocking the pilot; wanted before the fleet-size account (a bulk geography or product edit makes the probe scan the history tables).

**Who reads them.** `AsWasSql.probe` (cached 20 s per database and date) asks, per dimension, whether any history period closed after the window start with a different value in a column a read uses:

```sql
SELECT 1 FROM app.<table> a LEFT JOIN app.<table> b ON b.<key> = a.<key> AND b.valid_from = a.valid_to
 WHERE a.valid_to IS NOT NULL AND a.valid_to > :from AND (b.<key> IS NULL OR <read columns differ>)
```

The timelines (`AsWasSql.geo|zone|product|outlet|zoneAll`) read the same tables by key and date range. Today every table has the V0119 `EXCLUDE USING gist (key WITH =, daterange(valid_from, valid_to,'[)') WITH &&)` index and the partial unique `(key) WHERE valid_to IS NULL`. Neither serves `a.valid_to > :from` (the probe's driving filter) nor the self-join on `b.valid_from = a.valid_to`: the planner scans the whole table.

**Ask (forward-only migration).** For each of `app.route_zone_history`, `app.route_history`, `app.zone_history`, `app.territory_history`, `app.division_history`, `app.sku_history`, `app.product_node_history`, `app.outlet_class_history` (key columns `route_id`, `route_id`, `zone_id`, `territory_id`, `division_id`, `sku_id`, `node_id`, `outlet_id`):

1. `CREATE INDEX <table>_key_from ON app.<table> (<key>, valid_from);` (the next-period join and key + date-range reads);
2. `CREATE INDEX <table>_closed ON app.<table> (valid_to) WHERE valid_to IS NOT NULL;` (the probe's "closed after the window start" filter).

`CREATE INDEX CONCURRENTLY` is not needed on the pilot; use it in the final account.

**Backend side (this lane, no db change).** The probe is single-flight per database and date (overlapping callers share one scan); a watermark-keyed cache (watermark = max(valid_to) of the tracked tables) is the follow-up once these indexes exist.

**How to verify.** `EXPLAIN (ANALYZE)` of the probe on a database with 8,500 routes, 40,000 outlets and 100,000 history rows per table should show an index scan on `<table>_closed`, not a sequential scan, and finish in tens of milliseconds.
