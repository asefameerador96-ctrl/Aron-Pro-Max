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
| QA-MNY-01 (D-18 split family) | header with gross and negative net quarantined arithmetic_mismatch; stored one flagged when a child lands (Opus PASS, nits applied) | f58e18a, c0ec85c |
| replay: paper backfill | stored manual entry answers first, only for its keyer; no re-pricing on a retry (Opus PASS, nits applied) | 1e13f64, 1c262f8 |
| replay: daily-tracking actions | caller's own action before the reach check; others 403/409 as before (Opus PASS, nits applied) | 2a0394a, follow-up |
| replay: PDA upload cap | stored upload before the cap; new files hold the current cap (Opus PASS) | 34ac151 |
| replay: device enrol after revoke | own phone's lost response answered; anything else on a revoked token is TOKEN_INVALID (Opus PASS, nit applied) | 88658a0, 2aad44e |
| KD-1/KD-2 day close | ordered FOR NO KEY UPDATE on route-days (ingest + Final Submit); clock after the lock, micros; qa DayCloseRaceTest strict 200 rounds green locally (Opus PASS w/ nits: afterBatch sorted, transient failure retries the family, routeIds sorted) | f52bd40, follow-up |
| QA-MNY-03 xlsx mtk cell | numeric up to 2^53, text above or non-integer | see log |

Outlet-request replay: done (09071414, reads the caller's own request). Risk-signal review replay: kept as 404 out of
reach on purpose; its answer is the live signal row, and returning it would leak current state outside the reach.
Sweep items still open (Opus list, lower risk): daily-tracking actions (reach before the audit lookup), PDA upload cap
(cfg.support.max_upload_mb lowered), device enrol after the token is revoked, paper backfill (prices re-looked up
before ingest, money: replay gets payload_conflict or no_price_on_date after a back-dated price change).
Also open: config changes, changes/{id} and versions/{v} return every zone's items to a TSO (gaps baseline).
Questions with the lead: owner phone unmasked to the TSO in the bundle; fleet-size TSO territories.

Open for others: db + lead register `cfg.geo.update_base_*` (3 keys, docs/24 s9.5 rows); infra adds the Gradle OSV
and APK security CI steps; lead decides a TSO outlet bundle section and whether submit void comes back.
Traps (updated 2026-10-18 05:30): D-MIG-RANGE-01 (lead, lane/lead-docs-night 2e962851) lifted the V0084-V0087 hold: INT stops at V0083, lead-hardening renumbers its V0084-V0087 at promotion; lead-backend took V0084, V0085 is backend-authz's. Before this ruling: do not number a migration in that range or after it
until it lands. backend:app tests take about 15 minutes locally.

Traps found tonight: run only one Gradle build at a time on this box (a checker's concurrent build recompiled classes
under a running test JVM: 74 NoClassDefFoundError failures that were not real); a contract edit needs
`npm run gen:contract` in web/ (CI checks web/src/contract/openapi.d.ts); a local `gitleaks git .` scans every
remote ref, so pass `--log-opts=HEAD` to match CI.

## Handover for the next session (recycled 2026-10-18 ~01:45 UTC)

Branch tip: 2cc82215 (pushed). CI: ae11b20e green and checked. 45755543 and ab1a7e9c red: openapi.d.ts was stale and
INT's gitleaks hunk was missing; both are fixed in f7425e97. f7425e97, d7c82910 and 2cc82215 still have no green run
of their own. The lead sends 'checked <sha>' when one does, so watch the runs and fix any red one.

Ownership split (lead 01:10): backend-authz (session_01LaW5TQ87wr3m8HBjQVjcZG, lane/backend-authz) owns the remaining
config scope (changes, changes/{id}, versions/{v}, reach pending), CONFIG_READERS, config list limits, per-request MFA,
finance.backdate, SUPPORT reports. Do not do those. It builds on 8a571fa1/faae41f7/45755543.

Next items, in the lead's order (messages 01:08-01:18):
1. **QA-MNY-01 (T1 money, Opus checker):** MemoChecks checks the net's sign only when the memo and its children arrive
   in one batch. A header first and children later (gross 9,200, net -30,480) is accepted with no flag. Run the same
   check when the late children land (replay-safe). Rule D-18: a ZERO sale + QC may be a credit; gross > 0 with
   net < 0 is flagged or quarantined as today. Repro: an @Disabled test on lane/qa dcacec1a; request file
   docs/requests/qa-money-gate-findings.md (from qa). Merge INT first. Send the lead the sha when it is green.
2. QA-MNY-02 is DROPPED (parked until MQ-03).
3. Rest of the replay sweep, money first: paper backfill (PaperBackfill.kt:105-116, prices re-looked up before ingest),
   then daily-tracking actions, PDA upload cap, device enrol after the token is revoked. Risk-signal review stays as is
   (see above).
4. KD-1/KD-2 (docs/requests/qa-backend-dayclose-late-rows.md, read it with `git show origin/lane/qa:...`):
   `SELECT ... FROM app.route_day ... ORDER BY route_id FOR NO KEY UPDATE` before the late check in finalSubmit (not
   FOR SHARE); truncate the clock to micros once in finalSubmit. Run qa's DayCloseRaceTest without the tolerance, then
   tell qa.
5. TSO bundle follow-ups: none open beyond the fleet note (rulings logged in DECISIONS D-TSO-BUNDLE-OUTLETS).
6. Last and low: ReportOutputs.kt:50, an xlsx mtk cell falls back to toDouble(). Keep it numeric, but write text
   above 2^53.

Discipline: before any push that touches contract/openapi.yaml, run `python3 tools/slice-contract.py`,
`cd web && npm run gen:contract` and `bash web/scripts/ci.sh generate`. Merge INT before each push. Do not edit lane/qa's
@Disabled repros or the shrink-only baselines. The ApiWorld release fixture is accepted (the lead, 23:31).

## Session 2 (2026-10-18 from ~01:45 UTC)

Open notes from the checkers (not done, low): a stored PDA upload replays with no age limit (older than this work);
multi-record transactions (acceptQuarantined, backfill match, BundleService plans) still lock route-days record by record,
a deadlock there now retries/fails instead of dropping a count; a header with gross and negative net stored before
f58e18a whose children were all stored is never flagged (one-off query on dev: net_mtk < 0 AND gross_mtk <> 0);
cfg.memo.allow_negative_net is not read on the server (both paths assume true); a future reopen endpoint must lock
route-days (route_id order) before final_submit. The paper backfill replay for another keyer still re-prices (409 as
before). Local trap: start PostgreSQL with `pg_ctlcluster 16 main start` and export
ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres.

### Known backend follow-ups (lead rulings 2026-10-18 04:58 UTC)

- (b) Multi-record transactions (IngestService acceptQuarantined, the backfill match, BundleService plans) still take
  route-day row locks record by record, not in (date, route_id) order. A deadlock there now aborts and retries the
  family (afterStored rethrows transient failures) instead of silently dropping a late count. No code now.
- (e) When `POST /v1/day/reopen` is built: lock the zone's route-days (`ORDER BY route_id FOR NO KEY UPDATE`) BEFORE
  updating app.final_submit, the order ingest uses (route_day, then final_submit), and read the clock after the
  zone-day lock (KD-2). Otherwise a reopen deadlocks against a late row.
- (c) One-off, read-only, dev data only (not run from a lane container: no dev credentials here; whoever runs it
  writes the count here, no data change):
  `SELECT client_uuid, business_date, gross_mtk, net_mtk, server_flags FROM app.memo WHERE net_mtk < 0 AND gross_mtk <> 0 ORDER BY business_date;`
  Rows are pre-f58e18a headers with a gross and a negative net that never got a later child (so never flagged).
  Result: not run yet.
- (a) PDA stored-upload age limit: ruled OK (refused with a visible reason, never partial, config key with a generous
  default agreed in a decision line). Blocked on a config key: registering `cfg.support.upload_replay_max_h` needs a
  migration. DONE in ac70e42 as V0084 (D-MIG-RANGE-01; hardening renumbers its own V0084-V0087 at promotion).
- (d) `cfg.memo.allow_negative_net`: fixed by D-18, read by no code (DECISIONS D-18-FIXED-KEY); 5edcace (a switch)
  reverted in cf16c05. A later migration marks it read-only or removes it.
