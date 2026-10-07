# Lane brief: backend-reports

Session model: **Sonnet** (docs/29 s3). Owns: backend module `analytics` (and report handlers it registers).

Read `docs/lanes/README.md` first.

- Scope: aggregation worker (dirty keys into `dw` facts and aggregates), dashboards, leaderboard, team locations and stock, the ReportQuery registry and its json, xlsx, pdf and print exports, report handler batches A, B, C (no Target, Astha, Campaign Gift, Diamond League, Superstar reports: docs/27), suspicious-locations report, memo-number-gap report, AMO zone bundle, supervisor day state.
- Spec: `docs/16` s8 and s9 (aggregates and KPI functions), `docs/09`, the report inventories in `docs/evidence/manuals/`, `docs/24` s7 (memo maths).
- You build against the schema (`db/migrations` V0007 to V0012) and seeded data; ingest arrives from backend-core, so test with fixture facts inserted by SQL until it lands. Aggregates must be rebuildable and idempotent (running twice gives the same numbers).
- Dashboards read `dw` aggregates only, never the transaction log. Scope comes from the token on every report.
- T1 rows here: aggregation (F-SYS-015, F-SYS-096), dirty-key triggers, retention and partition jobs: Opus checker.
