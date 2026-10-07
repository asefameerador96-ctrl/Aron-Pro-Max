# Request to db (from backend-core, 2026-10-07): bundle downloads per route-day (F-SYS-025, Data Entry Log)

F-SYS-025: "the first and last download and upload time and counts per route per day are stored in Dhaka time with MIN
as first and MAX as last". Uploads are covered (`route_day.in_field_at` first, `last_batch_at` last, `app.sync_batch`
counts; the `data-entry-log` report reads them). Downloads have only the first (`route_day.target_frozen_at`).

## Ask (forward-only)
```sql
ALTER TABLE app.route_day
  ADD COLUMN last_bundle_at timestamptz,
  ADD COLUMN bundle_count   int NOT NULL DEFAULT 0 CHECK (bundle_count >= 0);
```
backend-core then sets, on every full `GET /v1/sync/bundle` (not 304, not delta pages) for each route-day in the bundle:
`last_bundle_at = greatest(last_bundle_at, now())`, `bundle_count = bundle_count + 1` (one short UPDATE, never blocking
the bundle), and backend-reports adds the two columns to `data-entry-log`. MIN/MAX semantics: first = `target_frozen_at`,
last = `last_bundle_at`.
