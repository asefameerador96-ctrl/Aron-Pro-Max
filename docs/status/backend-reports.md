# backend-reports lane status

Updated 2026-10-07.

## Done (pushed to INT)
- **F-SYS-015** aggregation worker: outbox projector (gap-safe), dirty-key claim/lease, route-day and zone-day rebuilds, facts, SKU/brand/outlet/hourly aggregates. Opus checker found 4 defects, all fixed with tests. Wired into `ARON_ROLE=worker`.
- **F-API-014** `GET /v1/dashboards/summary` (the contract's name for the national dashboard): scoped from the token's reach, 30 s cache per scope hash, as-of, children, by_category/channel/brand, BSR. Sonnet checker: no confirmed defects.
- **N-031** device enrolment and policy service (single-use tokens, Keystore attestation parser, device proof, ETag policy, status reports, signed directives, revoke): pushed; Opus checker running.
- Also pushed: reports engine and batches A/B/C, team/app-home, daily tracking, ops, admission control (Sonnet/Opus checkers fixed; N-052 group checker pending).
- **Contract v1.2** (web-dashboard asks): `DailyTrackingPage.comparator` (exact: rebuilt from login time and memo commit times of the previous day with routes in scope, null for a past date), `SyncHealthPage` `summary.config_ack_pct/pending_photos/quarantine_backlog` and `by_zone[]`, `LoginSubmitStatus.zones[]`. Pending photos = `app.media` rows `pending_blob`, attributed to the zone of the user's route that day.

## Report column sets for web-dashboard
The real column sets are the `columns` of each report in the registry: call `POST /v1/reports/{key}/query` (any row-less request returns `columns`), or read `ReportHandlers*.kt` (`col(key, label, type)`). Day-control list names are in `docs/requests/web-config-day-control-columns.md` (answered there).

## In progress / next three
1. F-API-017 ReportQuery registry (json, inline xlsx via fastexcel, print; logged export behind an `ExportLog` interface).
2. F-API-053 / F-SYS-058 / F-SYS-064 need the export tables: BLOCKED on `docs/requests/backend-reports-export-tables.md` (db lane).
3. Report handler batches A, B, C (N-048, N-051, N-052), then F-API-013/015/016/018/023/024/034.

## Requests filed
- `backend-reports-db-indexes-and-events.md` (db: indexes, segment aggregate; backend-core: outbox events).
- `backend-reports-export-tables.md` (db: report_export, pii_read_budget).

## Traps
- Local Gradle works only with the committed mirror (settings.gradle.kts); Postgres in the container stops now and then: `pg_ctlcluster 16 main start`.
- Seed fixture `backend/analytics/src/test/resources/seed_day.sql` (control totals in AggregationTest/DashboardTest) is reusable for report tests; it needs one session (pg_temp functions).
- Contract names differ from backlog names: `/v1/dashboards/summary`, `/v1/reports/{key}/query` (POST), `/v1/report-exports`.
