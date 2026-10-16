# Production readiness audit: four owner questions

Written 2026-10-17 from `AronData/audit-raw.json`: six finder reports (78 findings) and 61 skeptic verdicts. Basis: worktree `lane/ui-calm-glass` @ d75f0b04 (= integration 26cfb26a + UI work); integration has since moved to 4085e0d0 (V0074-V0083), so re-check a row before acting on it.

Rules: a finding with two verdicts is **confirmed** if at least one holds; corrected status/severity is the more conservative of the two. 31 findings were verified (two checkers each; HD-09 one). 47 had no verdict (all OPS-*, PERF-02..11 and the minor tail elsewhere) and are "unverified, finder's severity". After correction **no critical remains**: HD-01, DB-01, SCALE-01/02/03 were downgraded to major; SCALE-09 was refuted.

Rule 6 applies: nothing here is "cannot fail". Each answer says what is implemented and tested, designed only, or missing, and where the proof runs (TEST account now, FINAL account later, CI, device lab).

## 1. The four answers

### (a) Are we keeping a data lake?

**No, not yet. The hooks exist; the lake is a final-account build and an owner decision (D-560).**

| | Evidence |
|---|---|
| Implemented, tested | What a lake would be fed from: 11 monthly RANGE-partitioned parents (`grep "PARTITION BY" db/migrations`), retention classes and the `archive_manifest` state machine (V0051; `RetentionPolicyTest.kt:36-77`), 8 stable `dw.v_*` views with `bi_reader` limited to them (V0020:60-63), CI-enforced data dictionary (V0016, `DataDictionaryTest`). |
| Designed only | Blob Parquet by business_date + Fabric Lakehouse + OneLake shortcut + Power BI gateway (docs/16 s13.3; docs/18 s3.3 ~USD 400/month, unverified). The export job that would fill it (HD-02). |
| Missing | No `archive` container, no Archive-tier lifecycle, no Fabric/Synapse/OneLake in any Bicep (`storage.bicep:70-80, 93-125`; HD-03, minor). Nothing leaves the primary database today, not even a dump (HD-01). |
| Proof runs | TEST: create `archive`, write verified `sql_gz` partition exports. FINAL: Parquet, Fabric capacity and licences (owner, gate 0c), T-4-169 (3-year report off the lake). |

### (b) Is the database enterprise grade and usable later as historical data or a dump?

**Schema: yes, proven in CI. Dump: not today. Historical: designed, not built.**

| | Evidence |
|---|---|
| Implemented, tested (CI, PostgreSQL 16) | Idempotent spine: `ingest_registry` PK + advisory lock + FOR UPDATE (V0008:11-33, `IngestService.kt:321-322`). Money as integer milli-taka with CHECKs (V0007 part 2:6-27). Immutability triggers on 46 tables (V0007:17-40), hash-chained `audit_log` (`AuditLog.kt:9-33`). Versioned reference data; roles generated from `app.db_role_grant` (V0014/V0020, `DbRolesTest`); PII envelope-encrypted (V0049); migration gate (`tools/ci/migrations-check.sh`). |
| Designed only | Archive export/verify/drop: `RetentionJob.kt:19-22` "never exports and never drops" (DB-03). Nine capture tables and every `dw.agg_*` unpartitioned (V0007:361-1913, V0011:177-281; DB-02). Audit WORM export (HD-04). Restoring an archived month: one sentence, docs/16:2835 (HD-06). |
| Missing | **No periodic dump the owner holds**: only Azure PITR (7 d dev, 35 d prod) tied to a server in a temporary subscription; the sole `pg_dump` is the manual move step `move-to-final-account.md:62-72` (HD-01). `ingest_registry`/`sync_batch` never pruned (DB-01): ~1 B rows/year at fleet. `(user_id, business_date)` indexes missing on `dw.fact_memo`/`fact_visit` (DB-04). `owner_name`/`address` written in clear into the immutable audit chain (`AdminKit.kt:91`, `AdminOutlets.kt:25-26`; DB-05). |
| Proof runs | Schema rules: CI now. PITR restore: scripted (`drill.sh:80-137`), **never run**; TEST, ~USD 0.30, waits on "owner approved restore drill" (HD-09). Dump-restore: TEST once HD-01 exists. Growth at fleet size: FINAL only. |

### (c) 8,500 users with no lag?

**Not proven. Sized for it on paper; three hot-path costs in code contradict the design; no load number for the API, no device number for the phone.**

| | Evidence |
|---|---|
| Implemented, tested | Backpressure end to end (`AdmissionControl.kt:28-80`, `SyncApi.kt:46,196-204`), caches, gzip, no-polling sync enforced by lint (`NoPollingLintTest`), incremental aggregation (`AggregationWorker.kt:36-79`), prod ladder = docs/18 full fleet (`prod.bicepparam:54-69`), FINAL only. Measured: set-based ingest INSERT p95 8-16 ms per 200-visit batch (`docs/status/db.md:66-69`, SQL only); route_day index 0.03 ms (db.md:205); APK 10-12 MB; failover ~30 s. |
| Wrong | Ingest is N+1: ~12-20 statements and 1-4 savepoints per record, advisory lock per record (`IngestService.kt:180, 280-290, 321-322, 389-398, 810-826`; `RecordWriter.kt:121`) vs docs/18 s4.2 (SCALE-02). `dbPerAppLogins = false` in prod/stage (`prod.apps.bicepparam:25`): API runs as the admin login with **no statement_timeout**, all replicas share one PgBouncer pool of 20 (SCALE-08/07). Autoscale 50 vs decided 30 (SCALE-06, minor). |
| Designed only | Bundle rebuilt per GET, ~25 queries incl. a full `cfg_value` scan, ETag after the build (`BundleService.kt:43-44, 72-148`; `ScopedConfig.kt:106-123`; `SyncApi.kt:258-264`; SCALE-03). Worker single-threaded, no scale rule, no backlog metric (`AggregationWorker.kt:166-175`; SCALE-05). |
| Missing | **No load test of any kind** (SCALE-01); docs/14:489 and D-133/134 require a k6 smoke from phase 1c. Phone gates (cold start 2.5 s, sale save 300 ms, scroll, battery) have tooling but **no recorded number** (`device-checks.md:101-102`; SCALE-10/PERF-01); three unverified main-thread risks (PERF-02..04). |
| Proof runs | k6 smoke at tens of VUs: TEST now (docs/28 allows it). Azure Load Testing S1-S10 and the 8,500 proof: FINAL only (docs/28 rule 3, N-057; T-4-157 pass = `cl_waiting 0`). Phone gates: lab A06 now (D-PERF-04/05 "ready now"), then every release candidate. |

### (d) No data loss?

**Designed and CI-tested on the paths that matter; three real gaps; the restore has never been drilled.**

| | Evidence |
|---|---|
| Implemented, tested (CI) | Phone: one Room transaction per capture with UUID v4 enforced (`CaptureRepository.kt:41-45, 86-122`), atomic draft rename + double-commit guard (`DraftStore.kt:14-36`, `SaleCommitter.kt:32-55`), persisted outbox state machine with same-`batch_uuid` resend (`SyncEngine.kt:109-117, 222-254`), purge only of fully-acked families (`LocalPurge.kt:36-67`), photos deleted only after record ack + blob stored (`MediaUploader.kt:141-158`). Server: nothing dropped, rejects/quarantines keep the payload (`IngestService.kt:857-904`), no record passes through memory (DD-11), `SyncConvergenceFuzzTest`. |
| Wrong | Phone DB is WAL + `synchronous=NORMAL`; docs/17:55 fixes FULL "a sale must survive a battery pull" (`AronDatabase.kt:94`; DD-01): a pull before the checkpoint can roll back a printed memo. Outbox consumer ignores the V0033 horizon and dead-letter columns; a poison key retries every 5 s for ever (`AggregationWorker.kt:28-67, 121-139`; DD-04): stale tiles, source rows safe. |
| Designed only | After a PITR **nothing mints the new `server_generation` row** that makes phones re-send (only writer V0008:144; DD-02): rows acked between restore point and restore stay `acked` and are purged 3 days later, the FM-20 silent-loss path. |
| Implemented, a gap | `retry_exhausted`: 10 HTTP-level 500/413/MALFORMED answers strand a row on the phone with no server copy and no alert (`SyncRules.kt:19,60-61`; `SyncEngine.kt:352-396`; DD-05). |
| Untested | Backups provisioned (`postgres.bicep:97-151`), PITR drill scripted, **never run**; RB-52 geo-restore absent (DD-06). SQLCipher reopen proofs only as `androidTest` (DD-12). |
| Proof runs | CI now; the Azure dev server has only smoke/seed runs (DD-10). PITR drill: TEST now (owner words). Geo-restore + T-4-174, fuzz through PgBouncer transaction mode (T-1-153): FINAL staging. |

## 2. Strengths (already top tier)

- Idempotent, immutable sync spine: registry PK + advisory lock (`IngestService.kt:319-326`), batch replay with stored response (:236-270), `guard_synced_row` on 46 tables (V0007:17-40); `SchemaV1bTest`, `SyncConvergenceFuzzTest`.
- Kill-safe phone write path: one transaction per capture, persisted in-flight batches, purge never touches an un-acked family (`SyncEngine.kt`, `LocalPurge.kt:8-67`; `SaleFlowTest`, `LocalPurgeTest`).
- Money enforced in the database: every money column `bigint *_mtk`, `app.div_half_up`, memo CHECKs (V0007 part 2:6-27).
- Roles and dictionary as data, CI-tested: `app.db_role_grant` (V0014/V0020, `DbRolesTest`); `DataDictionaryTest` fails on any uncommented column.
- Battery and data budget by construction: one balanced-power fix per cycle (`AndroidLocation.kt:53-72`), WorkManager-only sync with jittered backoff, gzip batches, photos <= 150 KB (`PhotoCompressor.kt:24-75`), APK gate on every push (`tools/ci/apk-size-gate.py`).
- Safe overload: 429/503 with jittered Retry-After, dashboards shed first (`AdmissionControl.kt`, `RequestIsolation.kt`); probes never touch the database (`Http.kt:76-85`).
- Deploy and CI discipline: PITR point before every migration, images by digest, health gate, `rollback_sha` (`infra/deploy.sh`); gitleaks, OSV, Semgrep, oasdiff, squawk; Key Vault references only (`seed-secrets.sh:32-44`).
- Failover drilled: 2026-10-07, ~30 s outage, api reconnected itself (`database-failover.md:129-131`). Dev backups real: ZoneRedundant HA, geo backup, 7-day PITR (live `az`, 2026-10-16).

## 3. Confirmed gaps (severity, then dimension)

Effort = my estimate, engineer-hours. Lanes: backend-core, backend-admin, db, infra, android-core, android-sys, android-sr, web.

| Id | Dim | Status | Sev | Means for the owner | Fix | Lane | h | When |
|---|---|---|---|---|---|---|---|---|
| DD-01 | Durability | wrong | major | A battery pull can erase a sale already printed. | `PRAGMA synchronous=FULL` in the SQLCipher `postKey` hook; JVM test (`== 2`); A06 commit time. | android-core | 3 | before |
| DD-02 | Durability | designed-only | major | After a restore, phones silently discard up to 3 days of sales they think are safe. | `mint-generation.sh` (or jobs_rw endpoint) inserting `app.server_generation`; called from `drill.sh pitr`; RB-14/RB-02 step; acceptance test. | infra + backend-core | 6 | before |
| DD-04 | Durability | wrong | major | A tile can stay stale all day; a broken key spins the worker for ever. | Read `(tx_id,id)` under `app.outbox_horizon()`, persist `last_tx_id`; set `attempts/not_before/dead_at`; alert on `v_dirty_key_dead`. | backend-core | 8 | before |
| DD-05 | Durability | implemented | major | An hour-long server fault can leave sales stuck on phones, nobody told. | Do not count whole-batch 5xx/413/transport toward `rowMaxRetries`; alert on `sync_rejected` past `parked_until`; support "release exhausted rows". | android-core + backend-admin | 8 | before |
| DD-06 / HD-09 | Durability, History | impl.-untested | major | "Restore takes ~2 h" is a guess; nobody has restored this database. | Owner: "owner approved restore drill"; run `drill.yml pitr` (~USD 0.30); record minutes in `infra.md`/RB-14; write RB-52. | infra (+ owner) | 3 | before |
| DB-01 / HD-08 | Database, History | missing | major | Registry grows ~1 B rows/year; sync slows every month. | Two `RetentionJob` steps: chunked DELETE past `cfg.retention.ingest_registry_days` (voided kept), expire `sync_batch`; tests; oldest-row alert. | backend-core | 4 | before |
| DB-02 / HD-07 | Database, History | designed-only | major | Nine busy tables and every aggregate can only grow; retention impossible later without a rewrite. | Partitioning batch (RANGE on business_date, `partition_policy` rows, V0021 trigger) for the nine tables and `dw.agg_*`; recreate the D-DB-PART-01 ruling. | db | 16 | before first FINAL migration |
| DB-03 / HD-02 / DD-03 | Database, History | designed-only | major (loss: minor) | Nothing is archived or dropped; hot window and 7-year archive do not exist; disk full in ~323 fleet days. | J9 job: export partition (`sql_gz`, Parquet later) -> verify sha256/count -> DETACH/DROP; restore path; stale-`planned` alert; 70 % storage alert now. | backend-core + infra | 24 | after (dated row; before month 5) |
| DB-04 | Database | missing | major | Sync-health page takes seconds per user at fleet size. | `CREATE INDEX ON dw.fact_memo (user_id, business_date)`, same on `fact_visit`; plan test. | db | 1 | before |
| DB-05 | Database | designed-only | major | Owner names and addresses sit in an audit trail that can never be edited. | Add `owner_name`, `address`, `*_enc`, `wrapped_dek` to `PII_KEYS` (`AdminKit.kt:91`); grep test on a seeded row. | backend-admin | 3 | before |
| SCALE-01 | Scale | missing | major | Every capacity number is arithmetic; the first real test is the first fleet morning. | k6 smoke (login, bundle +/- ETag, 200-row batch, duplicate, dashboard) on every INT push against dev; record p50/p95, statements/request; S1-S10 in FINAL. | infra + backend-core | 12 | before |
| SCALE-02 | Scale | wrong | major | Uploads do ~10x the database work the design allows; the evening wave queues and 503s before it scales. | Batch lookups per family, one lock and one savepoint per family, multi-row registry upsert, savepoint cap; `BatchAcceptanceTest` asserts statement count. | backend-core | 24 | before |
| SCALE-03 | Scale | designed-only | major | 8,500 logins each rebuild the day's bundle even when nothing changed. | F-SYS-067: worker writes gzip snapshots to Blob with seq in `bundle_snapshot`; 304 from stored ETag before any build; scope-filter `ScopedConfig.load`; index `cfg_value` dates. | backend-core | 20 | before |
| SCALE-05 | Scale | designed-only | major | At 17:00 dashboards fall behind exactly when watched; nobody told. | KEDA postgresql scaler on unclaimed `dirty_key` (or N claimers); emit depth/lag; alert > 2,000 for 5 min; measure ms/key. | backend-core + infra | 8 | before |
| SCALE-07 | Scale | impl.-untested | major | All API replicas squeeze through 20 connections; a slow report starves uploads. | Raise `pgbouncer.default_pool_size` (dynamic, 40-60) in prod params and pass it from `main.bicep`; per-login pools after SCALE-08; alert `cl_waiting > 0`. | infra | 3 | before |
| SCALE-08 | Scale | wrong | major | In prod a runaway query runs for ever as admin and drains the pool. | `dbPerAppLogins = true` in stage/prod; server fallback `statement_timeout` in `postgres.bicep`; `check_infra.py` assertion. | infra | 2 | before |
| SCALE-10 / PERF-01 | Scale, Phone | impl.-untested | major | "No lag on a 2 GB phone" has no measurement behind it. | Run D-PERF-04/05 on the lab A06; add `recordSale` benchmark and JankStats scroll; medians into `device-checks.md`; gate each release candidate. | android-core | 8 | before |
| HD-01 | History | missing | major | A subscription reset or deleted server loses every pilot row; the owner holds nothing. | ACA cron `db-dump`: nightly `pg_dump -Fc` to `archive`, sha256/size in `app.backup_run`, 35-day dailies, monthly to Archive tier; `drill.yml dump-restore` monthly. | infra | 8 | before |
| HD-04 | History | designed-only | major | A superuser or a restore could rewrite history unseen. | `audit-export` container (immutability unlocked in pilot, D-484), daily export of audit rows + chain head after `audit_verify()`; partition `audit_log` in the DB-02 batch; lock at wave 1. | infra + backend-core | 8 | before |
| HD-05 | History | wrong | major | The second prod deploy (adds the replica) is refused by our own guard; analysts have no login. | Filter `EXISTING_POSTGRES_IDS` on `replicationRole` (`deploy.sh:317`, `whatif-guard.py:44-46`); `bi_reader` login in `runtime-logins.sql`; `dw.bi_user_scope` + RLS in CI; replica-lag alert. | infra + db | 8 | guard before; RLS after |
| SCALE-04 | Scale | designed-only | minor | Some "what changed" pulls become full downloads with many replicas; correct, costs data. | Persist the fingerprint (bytea) on `app.bundle_snapshot` (BC-71); cross-instance test. | backend-core | 4 | after |
| SCALE-06 | Scale | wrong | minor | Infra scales at 50 req/s per replica; the decision says 30. | `apiConcurrentRequests = 30` in prod/stage; pin in `check_infra.py`. | infra | 1 | before |
| HD-03 | History | designed-only | minor | No place to run a report older than 13-25 months. | Owner confirms Fabric at 0c; `archive` container now; Parquet + OneLake + T-4-169 in FINAL. | infra (+ owner) | 16 | after |
| HD-06 | History | designed-only | minor | A dues dispute into an archived month needs hand-crafted SQL. | `aron restore-month` job: download, verify, COPY FROM, ATTACH, manifest `restored`; runbook; round-trip test. | backend-core | 8 | after |

## 4. Refuted or downgraded

- **SCALE-09 refuted (both)**: no Redis/no auth app is a recorded Phase 1 decision (docs/24 s3.6 D24-58, D24-20; docs/33 ADR-02/06; README D7 open for FINAL); lockout is DB-backed and shared.
- **HD-01, DB-01, SCALE-01/02/03 critical -> major**: real, unbuilt, no pilot risk, bounded fix before FINAL.
- **SCALE-02 impl.-untested -> wrong**: the code does what docs/16/17/18 forbid; checker found more savepoints (`RecordWriter.kt:121`, `GeoRecheck.kt:63`).
- **SCALE-04 wrong/major -> designed-only/minor**: 304 is replica-independent (`BundleService.kt:174-175`); the morning login never uses a delta; fix is open item BC-71.
- **SCALE-06 major -> minor**: ACA `concurrentRequests` is a 15-s averaged rate, not in-flight; only the 50-vs-30 drift survives.
- **SCALE-07 split**: "503s at the evening peak" refuted (documented peaks need 5-14 connections; pool size is dynamic); kept major because `dbPerAppLogins=false` collapses all replicas into one admin pool.
- **DB-05 split**: outbox leg wrong (`tracking_action.created` never emitted); redaction already landed for phone/contact/NID/TIN/password (`AdminKit.kt:87-96`, `CheckerAdminTest.kt:114`); gap narrowed to `owner_name`/`address`, kept major (chain immutable).
- **DD-05 trigger overstated**: handler exceptions are parked with payload (`IngestService.kt:274-297`); 503/429 not counted; residual gap kept major.
- **DD-04 split major/minor**: source rows safe, aggregates rebuildable; kept major for stale tiles and infinite retry.
- **DD-02 missing -> designed-only; HD-08 -> missing**; **DD-03, HD-03, HD-06 major -> minor**: nothing deletes today, so no loss.

## 5. Unverified (finder's severity) and minor

No checker ran on these; treat as "finder says".

**Unverified major**
- OPS-01 JWT rotation in code (`JwtKeys.kt:18-45`), not wired in `apps.bicep`, no runbook, never drilled. infra, ~6 h, before.
- OPS-02 Worker has no liveness probe, heartbeat or restart alert; a hung worker means no bundles at 06:00. infra + backend-core, ~4 h, before.
- OPS-03 No application metrics (`grep Micrometer|OpenTelemetry backend` empty); docs/18 s6.6 alerts unwritable. backend-core + infra, ~8 h, before.
- OPS-04 Fleet Sev1 alerts (login % at 09:00, sync success < 97 %) evaluated nowhere; sync-health pull-only (`OpsApi.kt:322-335`). backend-core, ~4 h, before.
- OPS-05 `app.app_error` stored, never read; no crash-rate alert, so a bad ring cannot pause itself. backend-admin + web, ~6 h, before.
- OPS-06 Log alerts deployed (`alerts.bicep:16-105`) but seeded proof N-062 never ran; dev-lite sets `enableLogAlerts = false`. infra, ~2 h, before.
- OPS-07 E-mail-only action group (`monitoring.bicep:86-99`), no SMS/voice, Service Health off in prod. infra, ~2 h, FINAL.
- OPS-08 Runbooks 4 of the docs/18 set; only failover drilled. infra, ~8 h, before.
- OPS-09 `deploy.sh:31` has no `stage` profile, no stage/prod OIDC identities: first prod deploy would be the chain's first run. infra, ~8 h, FINAL.
- OPS-11 (wrong) Cost controls blind in TEST: budgets disabled by billing policy, `cost-report.sh` estimates (~USD 560/month vs docs/28 target), 2026-10-10 review unrecorded. Owner action.
- PERF-02 (wrong) Every Sale stepper tap writes the draft file and re-runs the review on the UI thread (`SaleViewModel.kt:27`, `SaleFlow.kt:85-91`). android-sr, ~4 h, before.
- PERF-03 (wrong) First Print parses 278 KB of Bengali TTF and rasterises on `Dispatchers.Main` (`SrDay.kt:80, 302-309`). android-sr, ~2 h, before.
- PERF-04 (wrong) KV images up to 8 MB decoded unsampled in composition (`CallContentHost.kt:108`): OOM risk on 2 GB. android-sr, ~3 h, before.
- PERF-05 Glass tier policy lacks the RAM floor, frame probe and 2 GB -> C default (`Glass.kt:48-66, 121-129`). android-core, ~12 h, before.

**Minor and info (compact)**
- OPS-10 `/v1/admin/releases` not served (backend-admin). OPS-12 no log-cap or all-routes 5xx alert. OPS-13 JVM deps not OSV-scanned. OPS-14 web has no health route/probe. OPS-15 `infra.md` stale since 2026-10-08.
- PERF-06 lists rebuild row inputs per recomposition (`SaleRoute.kt:36,44`). PERF-07 sync file/pref reads at startup. PERF-08 SQLCipher settings unmeasured (DD-01 requires FULL; do not drop to NORMAL). PERF-09 no per-ABI splits (12 MB vs 4.4-5.7). PERF-10 no recorded day-of-data bytes; `download_network_policy=any` can push 8 MB over mobile. PERF-11 nested scroll on the Sale screen.
- DD-07 photo path safe, no blob versioning. DD-08 invalid client UUID only logged. DD-09 draft file no fsync; SQLITE_FULL recovery absent. DD-12 SQLCipher proofs lab-only. DD-10/11 strengths.
- DB-06 docs/31 s3 column rule mismatch (fix text). DB-07 `time_basis` not on the row. DB-08 outcome lists are CHECKs. DB-09 redundant `(business_date)` indexes. DB-10 `sales_plan` index. DB-11 payload contract unenforced; SCD2 keys unfilled. DB-12 stale `db/schema.sql`; forbid in-place rebuilds after FINAL. DB-13 `dim_date.is_holiday` never refreshed. DB-14 no `created_by` FK, no view-contract test.
- SCALE-11 prod ladder right, three components absent. SCALE-12 web tiles poll on hidden tabs (web). SCALE-13 login/OTP path fine.
- HD-10 photo lifecycle stops at Cold. HD-11 legal hold unbuilt. HD-12 move runbook omits `apply_db_role_grants()` and consumer reset. HD-13 84-month retention awaits counsel.

## 6. Go-live checklist

| # | Must be true | Proved by | Where |
|---|---|---|---|
| 1 | k6 smoke numbers recorded per endpoint; Azure Load Testing S1-S10 passed with `cl_waiting 0` (SCALE-01). | run ids in `docs/evidence`; T-4-157 | TEST smoke; FINAL S1-S10 |
| 2 | A 200-row batch is bounded in statements, one savepoint per family (SCALE-02). | `BatchAcceptanceTest` count; ack p95 in k6 | CI + FINAL |
| 3 | Bundles pre-generated; 304 without a build; live share < 5 % (SCALE-03). | worker log; k6 morning shape | TEST job; FINAL share |
| 4 | Per-app logins with timeouts and a sized PgBouncer pool in prod/stage (SCALE-07/08). | `check_infra.py`; first FINAL deploy | CI + FINAL |
| 5 | Worker scales on backlog, emits depth/lag, has heartbeat + liveness, is alerted (SCALE-05, OPS-02). | seeded backlog alerts in 5 min | TEST |
| 6 | Lab A06: cold start <= 2.5 s, sale save <= 300 ms, jank <= 2 %, battery <= 6 %/8 h, day's bytes (SCALE-10, D-06). | `device-checks.md` medians per RC | device lab |
| 7 | Phone DB `synchronous=FULL` (DD-01). | JVM pragma test; A06 commit time | CI + lab |
| 8 | PITR drilled and timed on dev; geo-restore RB-52 timed on staging; both mint `server_generation`, phones re-send (DD-02/06, HD-09). | `infra.md` run ids; `GenerationResyncTest` | TEST PITR; FINAL geo |
| 9 | Nightly owner-held dump + monthly dump-restore drill (HD-01). | `app.backup_run`; `drill.yml dump-restore` | TEST, then FINAL |
| 10 | Registry pruning live; partitioning batch before the first FINAL migration; archive job dated (DB-01/02/03). | `RetentionJobTest`; migration on INT; `DECISIONS.md` row | CI + FINAL |
| 11 | Whole-batch failures no longer exhaust rows; parked rows alert; consumer honours horizon and dead-letters (DD-05, DD-04). | `SyncEngineTest`; worker test; seeded alert | CI + TEST |
| 12 | Audit redacts owner name/address; daily audit export in an immutable container (DB-05, HD-04). | grep test; export + `audit_verify()` | CI + TEST |
| 13 | Seeded sync error and aggregation failure page within 5 min; SLA job live; SMS/voice + Service Health in prod (OPS-03/04/06/07). | N-062 run ids; one real page | TEST; FINAL page |
| 14 | main -> stage -> promote-prod -> rollback rehearsed once; JWT rotation drilled (OPS-09, OPS-01). | workflow run ids; runbook minutes | FINAL chain; TEST JWT |
| 15 | Second prod deploy creates the replica unrefused; `ARON_DB_READ_URL` set; lag alert on; `bi_reader` login exists (HD-05). | deploy run; `az` shows the replica | FINAL |
