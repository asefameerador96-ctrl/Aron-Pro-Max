# backend-reports lane status

## WAVE 4 (2026-10-22, session 3 again): national dashboard (docs/34 s2)
Branch `lane/backend-reports`; contract 1.4.0 first (a18fc5fb, fixed in b2f2976a), then code.

### Done (pushed)
- **Contract 1.4.0** (additive): 14 operations, `POST /v1/dashboards/national/{sales,cpr,channel-calls,final-submit,login-submit,channel-std,breakdown,geo-fencing,ff-geo/pins}` (body `NationalDashboardFilter`, breakdown and pins wrap it), `GET .../national/ff-geo/routes/{route_id}`, `GET /v1/dashboards/route-map/{route_id}`, `GET /v1/dashboards/sale-position/{outlet_id}`, `GET .../national/filter-options`, `GET .../national/freshness`. `last_updated_at`, `as_of`, `date`, `till_time` on every response. Appendix A of docs/24 updated (230 operations).
- **Summary rebuilds** (`DashboardSummaries.kt`, hooked into the route-day and zone-day rebuilds, idempotent): outlet x SKU, variant memo counts, route last position, completed/geo-valid call counts, first/last sale, download time, visit geometry, final-submit time, midnight auto-submit (summary only), freshness. Inert until the db objects exist (`ready()`).
- **Eight card queries + filter options + freshness** (`NationalDashboard.kt`), **pins, popup, route map, sale position** (`NationalMaps.kt`), **HTTP routes** (`NationalDashboardApi.kt`, wired in `Wiring.kt`), scope from the token on every query, BRAND filter through `BrandScopeResolver` (default none until authz lands its `brands` claim).
- Tests: `NationalDashboardTest` (brief arithmetic: channel STD = cigarette card, channel calls = CPR calls, brand memo counts not additive, daily target 2 500 + 1 250 over 25 working days, midnight job), `NationalMapsTest` (81.83 m, 3 470.55 m path, pin boundaries), `NationalDashboardApiTest` (HTTP, scope, roles, brand user). `ContractConformanceTest`, `RouteInventoryTest`, `shared:contract:jvmTest`, redocly lint green.

### BLOCKED on db (request filed): `docs/requests/db-backend-reports-dashboard-summaries.md`
Needs `dw.agg_daily_outlet_sku`, `dw.agg_daily_route_variant`, `dw.route_last_position`, `dw.summary_freshness`, the listed columns on `dw.dim_geo`, `dw.dim_product`, `dw.agg_daily_route`, `dw.agg_daily_zone`, `dw.fact_visit`, and route class (normal/manual/dummy). Until the migration is on INT the endpoints answer 503; tests use `dashboard_summaries_test.sql` (analytics) and `dashboard_summaries_shim.sql` (backend/app, loaded by ApiWorld). **Delete both the day the real migration lands.**

### Decisions (to log in DECISIONS.md by the lead)
- D-DASH-03 completed call = sr_call visit with outcome not null and not abandoned/not_reached; geo-validated = server verdict (else phone) in_range; pin geo % = validated/completed of that route today, no call yet = green.
- D-DASH-04 contact (SR and outlet phone) is returned null: the phone numbers are PII and only the step-up read path may show them; wire it with PiiReadBudget when the web asks.
- D-DASH-05 route map path and sale position are point reads of one route-day / one outlet-day (`dw.fact_geo_fix`, `dw.fact_visit`, and `app.memo` for new/repeat/edited); the cards read summaries only.
- D-DASH-06 targets are read from `app.target` (route scope, sku level) until db delivers `route_monthly_target`; working days come from Fridays + global `app.calendar_holiday` (zone-scoped holidays ignored for the national figure).
- D-DASH-07 channel calls and CPR count normal planned routes only; sales, channel STD and geo fencing count all routes.
- Contract: `SalePosition.radius_m` is 10..5000 (the stored radius), not the enum 20/50/100.

### Next three
1. Opus checker findings on the summaries (running), then fix.
2. Reports from `docs/reports/*.md` when the lead files them (none yet).
3. When db lands: delete the two test shims, run the full suite, re-run the conformance test.

### Traps
- A `/*` inside a KDoc comment opens a nested comment (a path like `/v1/x/*` broke the build once).
- `app.visit` and `app.memo` are immutable by trigger: fixtures that need coordinates `ALTER TABLE ... DISABLE TRIGGER visit_immutable` first.
- `pg_temp` helper functions in seed_day.sql live only in the connection that ran it: run every fixture file on the same connection.
- Fresh container: `pg_ctlcluster 16 main start`, then create the `aron_test` role and database (see the older handover below).

## HANDOVER (2026-10-15, session 3 recycling early: queue done; context was far below 400k)
- **Full `:backend:analytics:test` on 26f5bef0: GREEN, 134 tests, 0 failed** (19 min, local PG16; a fresh container needs the `aron_test` role and database: `CREATE ROLE aron_test LOGIN SUPERUSER PASSWORD 'aron_test'; CREATE DATABASE aron_test OWNER aron_test;` and `ARON_TEST_PG_URL=jdbc:postgresql://127.0.0.1:5432/aron_test?user=aron_test&password=aron_test`). ContractDriftTest green; slice and WireDtos checks pass; not run locally: AuthDtoContractTest (no Android SDK here), redocly lint. No contract change since.
- **F-AMO-044 zone-wide AMO bundle**: done (`SupervisorBundle.kt`, `BundleService`, 28c0fe0 + ea71d69). Opus checker: 4 confirmed defects, all fixed with its tests kept (`AmoZoneBundleCheckerTest`). Decisions D-REP-09, D-REP-11. The checker's fixes were not re-reviewed.
- **F-SYS-067 bundle pre-generation**: generation, refresh, coverage check done (`BundlePreGeneration.kt`, worker); Sonnet checker: 4 confirmed defects, all fixed with its tests kept (`BundlePreGenerationCheckerTest`). **Serving stored bodies is NOT built** (no `bundle_user` dirty producers; infra `bundles` container): `docs/requests/backend-reports-bundle-serve-snapshot.md`. So the row's "morning download serves it" stays open; ETag and 304 work as before. The 8,500-bundle load budget is N-058's.
- **F-AMO-039**: backend-core's `DayStates` already covers it; added `anAmoVisitOnAnSrRouteLeavesTheRouteDayAlone`. Its dw projection (`dw.fact_supervisor_day`) and the dw copy of `entered_by_user_id` wait on `docs/requests/backend-reports-db-entered-by.md` (db not yet answered).
- **Checked** (independent checker finished, findings fixed): everything up to `403e6146`, plus F-AMO-044 and F-SYS-067 as above. **Not independently re-reviewed**: c58d9beb, 575c0792, 80565e8b (older checker regression tests and fixes) and the fix commits of this session.
- Old known gaps still open: PDF export (D-REP-06); other lanes' list endpoints must call `PiiReadBudget.charge`; export-job poison guard needs `report_export.attempts` (D-REP-08); memo-gap lost block tail (D-REP-05).
- Traps: `--offline` fails in a fresh container (no Gradle cache); a `//` comment inside a chained call swallows the rest of the line; JDBI reads `[1:20]` in SQL as a named parameter; a bundle test's version numbers (`<date>:<seq>`) are per user and can coincide.

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
