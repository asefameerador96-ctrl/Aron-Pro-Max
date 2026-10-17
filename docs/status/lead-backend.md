# lead-backend status (session_014aSx5HgZg1V3hp2ydEvQC4, branch lane/lead-backend)

Assigned by the lead 2026-10-17 night: the backend requests of the wave 2 lanes, in priority order. Each request file
carries the resolution note at its top.

| # | Request | Outcome | Commit |
|---|---|---|---|
| 1 | qa-backend-jackson-cves (+ qa-infra-gradle-osv) | Jackson 2.22.3 BOM in backend:platform; OSV gate 0 blocking; CI step stays infra's | d5c33599 |
| 2 | android-amo-tso-dashboard-backend | by_segment (segment rollup filled), category report units, SR names on RouteDayState (additive), cover-route 500 fixed; TSO outlet section left to the lead | 2e83eb75 |
| 3 | android-tso-record-mapping | bundle rubrics for AMO/TSO; code lists already sent; scope tree = existing GET /v1/admin/geo/{level}; visit-plans confirmed | 4e0a3206 |
| 4 | android-amo-report-access | route-memo, route-std open to AMO; route-std category_name; brand target deferred (docs/27) | 2e83eb75 |
| 5 | android-amo-update-base-server-guards | guards on approval (mock, 100 m, 1000 m, monthly cap); cfg keys still to register (db, after V0084-87) | 4e0a3206 |
| 6 | android-amo-supervisor-day-reopen | not built: submit void family is DROP (F-API-069, D-64); reasoning in the file | docs |
| 7 | qa-visit-radius-read | geofence-calibration radius min/max columns (non-breaking) | 2e83eb75 |
| 8 | qa-release-publish-route / qa-apk-security-check | release admin served + update-check block fixes / infra only | 4e0a3206 |

Checkers: Opus (T1: aggregation, Update Base, releases) found 4 defects, all fixed with tests; Sonnet (T2) found none,
one guard taken (legacy stars_1_5).

Open for others: db + lead register `cfg.geo.update_base_*` (3 keys, docs/24 s9.5 rows); infra adds the Gradle OSV
and APK security CI steps; lead decides a TSO outlet bundle section and whether submit void comes back.
Traps: V0084-V0087 are taken by lane/lead-hardening (not on INT): do not number a migration in that range or after it
until it lands. backend:app tests take about 15 minutes locally.
