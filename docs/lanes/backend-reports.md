# Lane brief: backend-reports

Session model: **Sonnet** (docs/29 s3). Owns: backend module `analytics` (and report handlers it registers).

Read `docs/lanes/README.md` first.

- Scope: aggregation worker (dirty keys into `dw` facts and aggregates), dashboards, leaderboard, team locations and stock, the ReportQuery registry and its json, xlsx, pdf and print exports, report handler batches A, B, C (no Target, Astha, Campaign Gift, Diamond League, Superstar reports: docs/27), suspicious-locations report, memo-number-gap report, AMO zone bundle, supervisor day state.
- Spec: `docs/16` s8 and s9 (aggregates and KPI functions), `docs/09`, the report inventories in `docs/evidence/manuals/`, `docs/24` s7 (memo maths).
- You build against the schema (`db/migrations` V0007 to V0012) and seeded data; ingest arrives from backend-core, so test with fixture facts inserted by SQL until it lands. Aggregates must be rebuildable and idempotent (running twice gives the same numbers).
- Dashboards read `dw` aggregates only, never the transaction log. Scope comes from the token on every report.
- T1 rows here: aggregation (F-SYS-015, F-SYS-096), dirty-key triggers, retention and partition jobs: Opus checker.

## Wave 4: dashboard summaries and the report specs (owner directive 2026-10-22, docs/34 s2 and s3)

1. Contract first: within the first hour push one OpenAPI slice per dashboard card plus the FF Geo, route-map
   and sale-position endpoints (`contract/`), with the filter object (wing, division, territory, house, zone,
   category, segment, brand, variant, date) and the response shapes the brief implies; web-dashboard builds
   from them in parallel.
2. Summary tables (`dw`) kept current from the domain-event outbox within a minute: memo totals by route ×
   SKU × business date (latest version only), calls (completed, within_fence, distance), check-in/download
   and Bikroy Joma per route per day, final submit per zone per day (first wins; midnight auto-submit job),
   latest position per route; `last_updated_at` per table exposed by the API.
3. The twelve card queries and the three map endpoints exactly per `docs/ui-reference/web/dashboard-brief.md`
   (Target Outlet excludes manual and dummy routes; channel from the outlet master; By Channel STD cigarette
   only; Memo counts distinct memo ids per brand; daily target = monthly target / working days with Fridays
   and holidays excluded; geo-fencing % per route for pin colours; Distance Covered per docs/34 Q1 default).
   Fixture tests reproduce the brief's arithmetic checks (channel sums equal the cigarette card, and so on).
4. Scope from the token on every query (docs/34 s1), including the BRAND filter and the House level.
5. Reports: as the lead files `docs/reports/<nn>-<report>.md`, build each with a fixture test that reproduces
   the spec's checks and an export with the sample's columns in order. Raw September workbooks never enter
   the repository.
