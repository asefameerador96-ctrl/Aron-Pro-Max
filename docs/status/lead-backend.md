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

## Night, second round (lead messages 2026-10-17 21:41, 22:43, 23:24)

| Item | Outcome | Commit |
|---|---|---|
| 1b replay-first (feedback, leave, visit plans) | stored uuid answers before rules; merged plan uuid and re-routed outlet covered (Opus checker) | 7503bdef |
| config resolve/values scope (qa ledger) | TSO/DMO/WM see own nodes, ancestors, nodes under; whole resolve chain in reach; values filtered | 8a571fa1, follow-up |
| config changes Idempotency-Key | replay before date checks; other body 409 | follow-up |
| replay sweep (money) | price batch, dues adjustment | 45755543 |
| TSO outlet bundle (F-TSO-027) | AMO zone-wide builder for TSO | 3f3184bd |
| replay sweep | final submit, data void, web-entry route-day and QC | f29d6892, e4cdb414 |
| web contract types | regenerated after the additive contract hunks | f7425e97 |
| outlet-request replay | caller's own request, not today's reach | 09071414 |

Outlet-request replay: done (09071414, reads the caller's own request). Risk-signal review replay: kept as 404 out of
reach on purpose; its answer is the live signal row, and returning it would leak current state outside the reach.
Sweep items still open (Opus list, lower risk): daily-tracking actions (reach before the audit lookup), PDA upload cap
(cfg.support.max_upload_mb lowered), device enrol after the token is revoked, paper backfill (prices re-looked up
before ingest, money: replay gets payload_conflict or no_price_on_date after a back-dated price change).
Also open: config changes, changes/{id} and versions/{v} return every zone's items to a TSO (gaps baseline).
Questions with the lead: owner phone unmasked to the TSO in the bundle; fleet-size TSO territories.

Open for others: db + lead register `cfg.geo.update_base_*` (3 keys, docs/24 s9.5 rows); infra adds the Gradle OSV
and APK security CI steps; lead decides a TSO outlet bundle section and whether submit void comes back.
Traps: V0084-V0087 are taken by lane/lead-hardening (not on INT): do not number a migration in that range or after it
until it lands. backend:app tests take about 15 minutes locally.

Traps found tonight: run only one Gradle build at a time on this box (a checker's concurrent build recompiled classes
under a running test JVM: 74 NoClassDefFoundError failures that were not real); a contract edit needs
`npm run gen:contract` in web/ (CI checks web/src/contract/openapi.d.ts); a local `gitleaks git .` scans every
remote ref, so pass `--log-opts=HEAD` to match CI.
