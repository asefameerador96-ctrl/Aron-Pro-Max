# backend-reports lane status

## HANDOVER (2026-10-15, session recycling at about 450k tokens)
- Lane head is the commit that carries this note. **Checked** (independent checker finished, findings fixed): everything up to and including `403e6146` (aggregation, dashboards, reports engine + batches A/B/C, ops, team, tracking, N-031 devices, entry_source copy needs no checker, retailer-list/sku-list/geofence-calibration/qc `source`: own tests only, no checker needed per lead). **Not independently re-reviewed**: the last three commits before this note: the Opus F-SYS-058 and Sonnet F-SYS-064 checkers' failing tests (kept as regression tests) and `80565e8b`, the fixes for them. Each checker defect has its test green on the head; the full `:backend:analytics:test` re-run was still in progress when I stopped (targeted ExportJobs*, CheckerF058*, ReportApiTest, ReportBatchC/D, EntrySource all green; the earlier full run before these fixes was green except one test of mine, fixed).
- Open rows: F-AMO-039 (supervisor day state), F-AMO-044 (zone-wide AMO bundle), F-SYS-067 (bundle pre-generation, D+1 pre-fetch): their blockers (backend-core F-SYS-016 / F-API-005) are landed per docs/status/backend.csv; not started.
- dw entry_source: done (Aggregator copies `entry_source` into dw.fact_memo / fact_visit, online-offline shows `manual_memos`). `entered_by_user_id` is NOT a dw column (V0077 added only entry_source); copying it needs a db migration first.
- Known gaps, all logged: PDF export (docs/requests/backend-reports-pdf-export.md, D-REP-06); other lanes' list endpoints must call `PiiReadBudget.charge` (docs/requests/backend-reports-pii-budget-list-endpoints.md); export-job poison guard needs a `report_export.attempts` column (D-REP-08); memo-gap lost block tail (D-REP-05).
- Env for the worker: ARON_BLOB_ACCOUNT + ARON_BLOB_CONTAINER_EXPORTS (default `exports`); N-031 needs ARON_PUBLIC_API_URL, ARON_ATTESTATION_ROOTS.
- Traps: Postgres in the container stops now and then (`pg_ctlcluster 16 main start`); `:backend:analytics:test` takes 20+ min; run it in the background with the longest timeout; a `//` comment after a chained `.bind(...)` swallows the rest of the line.

Updated 2026-10-15. Released by the lead; wall-clock test offenders fixed (scan: 0 in analytics); audit writes now go through `AuditLog.write` (tracking action, quarantine resolve, report export) with the actor and `after.scope` recorded and tested.

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
