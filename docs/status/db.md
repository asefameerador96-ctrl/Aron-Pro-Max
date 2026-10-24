# db lane status

Updated with every push. Rows of Day 1: N-005, N-006, N-007, N-008 (`python3 tools/my-rows.py db 1 --full`).

## Session 10, 2026-10-23 (lane/db; INT 016427a2 = train 14 merged, fast-forward)

| Job | State | What |
|---|---|---|
| **V0112/V0113 sync_rejected.code** (lead request 2026-10-24 11:27) | pushed (candidate below) | Opus round 2 PASS; local db 273/0; squawk 0 | `price_invalid` added to the `sync_rejected` CHECK (NOT VALID in V0112, VALIDATE in V0113). The 15 quarantined contract codes stay out on purpose (they belong to `sync_quarantine`, V0008 split). Opus checker round 1 FAIL on a first draft that widened the CHECK to the whole enum; narrowed. Test `v0112SyncRejectedTakesPriceInvalidAndStillRefusesQuarantineCodes`. Dictionary regenerated. D-DB-SYNC-REJECTED-PRICE-INVALID-01. Rides train 30 LAST, after the contract batch; backend-core wires `price_invalid` after it is on INT. Next free migration **V0114** |
| **V0111 dw.v_memo_flags** (lead request 2026-10-24 08:52, P2) | **on dev** (train 28, INT cfd75917, deploy 40087756915 green, migrations succeeded, slice smoke passed); candidate 164bcf75, run #1518 green on all 9 jobs | flagged memos (server_flags non-empty) with memo_family_uuid, scope keys route/zone/territory/division/wing frozen at the business date (route-less memo: outlet placement on that date, then current), gross_mtk, flags, status, stored_at, voided_at; no user names (dw rule; the endpoint resolves them); no index at pilot size. Opus checker round 1 FAIL (dictionary not yet rendered; current-placement fallback; family/active-count; fallback untested), all fixed. Test `v0111MemoFlagsViewListsFlaggedMemosWithTheirScope` (the test seed has one zone, so the fallback is proved on the route). Dictionary regenerated. db 272/0 locally. `docs/33` part C's stable-view list is stale (misses V0097-V0111 views): not db's doc, told the lead. D-DB-MEMO-FLAGS-VIEW-01. Next free migration **V0112** |
| zero-price SKU (lead request 2026-10-24 06:00) | answered by message, no db change | owner's screenshot: MaxDB-10S_10HL at ৳0.00 on SR Sale. Not a seed gap: `sku_catalog.csv` (real catalogue) has 0.0 in all five price types for MaxDB-10S_10HL, SupSty-10S, SupSty-20S. db does not invent prices; owner to give prices or discontinue (then a seed-only slice + a test that no active SKU has a zero outlet price). Seeds 03/06 already exclude zero-price SKUs from the sales plan, so the Sale list showing it is an app/bundle question (android-core/backend-core), raised with the lead |
| **train 17 dev deploy** | read (lead relay 10:22 UTC) | INT 1dd9e44c on dev: deploy 39936645829 green 10:17 UTC, migrations step succeeded, **V0110 applied** (`dw.agg_daily_outlet.sold_qty_base` dropped; V0109/V0110 expand/contract closed), health 200 build 1dd9e44c, dev seed Succeeded, slice smoke PASS (2b skipped, non-selling day). Candidate 5a105735 (run #1464) was LAST in train 17. Lane now on requests only; next free migration **V0111**. Contract 1.7.0 needs no migration (lead) |
| train 14 dev deploy | read (lead relay) | INT 016427a2 on dev since 04:34 UTC (deploy run 39899966147 green). **No migration.** backend-reports' Aggregator now fills the four per-category columns of `dw.agg_daily_outlet` but still names `sold_qty_base` in its INSERT/UPSERT |
| train 13.1 dev deploy | read (lead relay) | **V0109 applied on dev** (deploy run 39892764902 green 03:14 UTC) |
| **V0110** | **prepared locally, committed, NOT pushed** (waits for the lead's "train 15 landed, V0110 may go") | `ALTER TABLE dw.agg_daily_outlet DROP COLUMN sold_qty_base` (plain, no CASCADE; lock_timeout; squawk 0). Readers checked: in `db/`, `backend/`, `web/` only backend-reports' Aggregator (removed in train 15) and their `AggregationTest` line 281 name the table column; the other `sold_qty_base` columns (route_sku/brand/segment/variant, outlet_sku, v_daily_sku) are other tables and stay. Tests: the V0109 test now migrates to `target("109")`; new `v0110DropsTheDeprecatedMixedColumn` (row and four columns kept, column gone, view unchanged with grants, an insert without the column works). Dictionary regenerated with `tools/data-dictionary/render.sh`, change-log line V0110 |
| Opus checker V0110 | round 1 FAIL, fixed | blockers: dictionary not yet regenerated (render was running; now committed), comment claimed the Aggregator change was already in (now states the train-15 precondition and the 42703 failure mode); should-fix taken: the test asserts a writer naming the column is refused (42703) instead of a vacuous insert |
| local suites on 25d05157+ (V0110 on INT 016427a2) | db 271/0; platform 93, auth 79, sync 34, masterdata 241, config 68, media 16 all 0 failed; **analytics 95/209 and app 5/420 failed, every one with `column "sold_qty_base" of relation "agg_daily_outlet" does not exist`** (INT's Aggregator still writes it; the route-day rebuild rolls back, so facts and aggregates stay empty) | the expected break: V0110 must ride after train 15. Re-run analytics and app after merging INT with train 15 before declaring |
| train 15 | landed (lead 06:45): INT 02a23471 on dev (deploy 39911078266 green 06:40), Aggregator no longer writes `sold_qty_base`; pre-merged into lane/db (03a6f7e2, DECISIONS.md conflict, both sides kept). Local analytics on it: 210 tests, 1 failure = AggregationTest:282 naming the column | V0110 may go once that line is gone |
| AggregationTest line 281-282 | lead ruled db deletes it (D-DB-V0110-TEST-01); this session's permission check refused the cross-lane test edit; ruling withdrawn, fallback: backend-reports #5 deletes it in its train 16 head, db merges that exact sha, re-runs analytics + app + db, pushes one head LAST in train 16 | waiting for "backend-reports head <sha> checked" |
| V0110 head | **pushed** (lead 08:30: train 16 = INT af2cfbcd on dev, deploy 39922665170 green 08:20; nothing on INT writes or tests the table column). LAST in train 17 | lane/db = V0110 work + backend-reports 87fa26ae (run #1460 green) + INT af2cfbcd. Local on 10866c8c (before the af2cfbcd merge; container rebooted 07:26, re-run with --rerun): db 271, analytics 210, app 426, 0 failed. af2cfbcd adds DataFlowTrace app tests (none names the outlet column); lane CI judges the merged head |
| AggregationTest (backend-reports) | told the lead | `backend/analytics/.../AggregationTest.kt:281` asserts the table's `sold_qty_base` = mixed sum; it fails after V0110 unless train 15 removes it. Not edited by db |
| requests | none open (05:10 UTC) | no new `docs/requests/*db*` on any `origin/lane/*` branch. Next free migration after V0110: **V0111** |
| DECISIONS.md | copied | D-DB-W4-01..05, D-DB-HOUSE-01 (V0093 no unique house index), D-DB-MDM-01 (device_command guard), D-DB-V0108-01 (SQL backfill), D-DB-V0110-01 (expand/contract) |
| local setup | noted | fresh container: role `aron_test` (trust on localhost only, as CI), `max_connections` 400; `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/aron_test?user=aron_test` |

## Session 9, 2026-10-22 (lane/db; INT 384ecee2 = train 9.1)

| Job | State | What |
|---|---|---|
| train 9.1 dev deploy | read (lead relay) | deploy #243 (run 39847052921) green 20:27 UTC: migrations incl. **V0107** applied, dev seed job ran (image `aron-devseed:384ecee2`), health 200 build 384ecee2, worker 0 restarts. Slice smoke: login and bundle passed; **2b skipped (non-blocking)** because the deploy ran at 02:14 Dhaka on **Friday 2026-10-23** (no selling route by design). So the route **could not be read from this deploy**: whether dev runs on Gulshan/Banani is still open |
| when the codes print | recorded | 5be0a3e1's codes print from train 10's deploy only on a Dhaka selling day (Saturday 2026-10-24 06:00 Dhaka = 00:00 UTC onward), unless the fallback below is on dev |
| smoke fallback (lead ask) | built, tested | `infra/scripts/slice-smoke.py`: when today has no smoke route, 2b fetches the bundle of the **next** selling day (the server's own pre-fetch: `for` up to the next working day; it never marks a login, and BundleService refuses past dates, so "last selling day" is not possible) and adds `; next selling day <date>: route <code> (<id>), outlet <code> (<id>), sku <code>` to the 2b line. No sale on such a day; 2b stays FAIL (non-blocking). Test `SliceSmoke.test_a_day_without_a_selling_route_names_the_next_selling_days_route_and_sells_nothing` (stub: 2 bundle calls, no batch); SliceSmoke stub tests 6/6 locally, the rest of check_infra needs compiled Bicep (same 38 errors before and after the change) |
| 88b1c0ab | **candidate**, checked by the lead, LAST head of train 11 (after train 10's deploy, ~23:00 UTC) | CI #1406 green on the jobs that ran (gates, infra, images); web/android/backend skipped, unchanged since 5be0a3e1 |
| qa db-qa-1 / infra-qa-1 | fixed, answered (`docs/requests/db-qa-1-answer.md`) | (1) evidence doc named a non-existent `dw.agg_daily_outlet_category`: now the real tables. (2) a non-selling day (bundle calendar, as DayPlan.isSellingDay) with no planned route makes 2b **SKIP** (exit 3; deploy.sh summary `SKIPPED (not a selling day ...)`, no warning) and still names the next selling day's route; a selling day without a route stays FAIL. Tests: SliceSmoke stubs 7/7 locally, the deploy wiring assertion before the Bicep-dependent part; same 38 Bicep-only errors as before |
| train 10 dev deploy | read (lead relay) | deploy #244 (run 39858553878) green for INT d698f8d4: migrations incl. **V0108** applied, dev seed job ran, health 200 build d698f8d4, worker 0 restarts. Slice smoke: login, bundle 2026-10-23:1 passed; 2b FAIL (non-blocking) with the Friday message, again no route code (Dhaka Friday). 6ce7e368 (train 11, ~23:00 UTC) turns this into SKIPPED with the next selling day's route codes |
| 6ce7e368 | **candidate**, checked, replaces 88b1c0ab as LAST head of train 11 | CI #1413 green on the jobs that ran |
| train 11 dev deploy | read (lead relay 23:10 UTC) | deploy 39867449822 green, build efa9caed, seed job Succeeded. Smoke 2b **SKIP** as designed: "2026-10-23 is not a selling day; next 2026-10-24, route GUL-SR-2"; summary row SKIPPED. **Seed 06 (Gulshan/Banani) is confirmed on dev** (a Mirpur code would have meant seed 06 skipped). Closes session 8's open item 1 |
| backend-reports outlet qty | **V0109** built, on INT with train 13 (3ec852f2, CI #1429 green on all 9 jobs; migrations gate on f2c36714 #1427) | `dw.agg_daily_outlet` + `cigarette_sold_sticks`, `bidi_sold_sticks`, `lighter_sold_pieces`, `match_sold_dozens` (backfilled); `dw.v_daily_outlet` recreated without the mixed-unit `sold_qty_base` (lead ruling); dictionary "Change log of the stable views". Test `v0109SplitsTheOutletQuantityByCategoryAndTheViewLosesTheMixedColumn`. Answer `docs/requests/db-backend-reports-outlet-qty-answer.md`. Local on the merged tree: db 270, analytics 201, masterdata 239, config 68, auth 78, 0 failed |
| **V0110 (reserved)** | waiting on backend-reports | `ALTER TABLE dw.agg_daily_outlet DROP COLUMN sold_qty_base`, once backend-reports' Aggregator fills the four columns and no longer names `sold_qty_base` **and that change is on INT** (backend-reports tells db). Ride last in a train after it. Until the fill deploys, new outlet-days on dev show 0 in the four columns; existing rows keep the backfill |
| local trap (session 9) | noted | `LIKE 'aron_t_%'` also matches `aron_test` (`_` is a wildcard): drop leftover test databases with `LIKE 'aron\_be\_%'` only, never a pattern that can match `aron_test` |
| requests | none open (03:00 UTC); next free migration **V0110** (reserved above), then V0111 | no new `docs/requests/*db*` on any lane branch at 20:35 UTC (backend-reports' checked_in_at = V0108, answered). Next free migration **V0109** |

## HANDOVER (session 8 -> session 9), 2026-10-22 evening, lane/db 5be0a3e1

**State.** a60d941e is on INT 529b02f6 (dev deploy #238 applied V0087-V0106; dev seed Succeeded; slice smoke PASS).
**fb59e0e9** (INT b561b267 + V0107 TOP grants -> NATIONAL + seed 07) rides train 9 last (CI #1388 green). **5be0a3e1**
(seed visibility + V0108 `checked_in_at`) declared on top (CI #1399 + #1394 green). Next free migration: **V0109**.

**Session 9, in order:**
1. Watch train 9's dev deploy (V0107, seed 07) and then 5be0a3e1's. Read the slice smoke line `2b route ...`: it must
   name a Gulshan/Banani route (Sat/Tue GUL-SR-2 + SMOKE-SR-001, Sun/Wed GUL-SR-1 + -002, Mon/Thu BAN-SR-11 + -003;
   Friday fails by design, non-blocking). A MIR-* code means seed 06 skipped on dev (04's config version present): investigate.
2. When backend-authz lands on INT, its `db/seed/07_admin_module_grants.sql` is byte-identical to ours; only the README
   row differs, and the lead told backend-authz #7 to take db's line.
3. Replies: mdm #2 reads the note at the top of `docs/requests/db-mdm-device-command-answer.md` (V0102 changes; their
   `mdm_ddl.sql` fixture becomes redundant). backend-reports was told about V0093, targets, route_class and V0108.
4. Open for the owner/AKTCL: Durga Puja 2026 date in seed 06 (2026-10-21, lunar; confirm). Checker follow-ups before
   the final account: retention of `dw.route_last_position` (unpartitioned, personal) and `fact_visit.fix_lat/lng`.
5. Decisions for DECISIONS.md (lead copies): D-DB-W4-01..05 (session 7 list below); session 8: V0093 has no unique
   house index (lead ruling); device_command guard is forward-only/write-once in the DB, approver role API-only;
   V0108 backfill in SQL (not dirty keys) so it cannot be lost before the consumer ships.

**Traps (session 8).**
- Local Gradle runs every module in parallel. Leftover `aron_be_*` test databases and stray runs exhaust Postgres
  connections ("too many clients") and fail tests at random.
  - Before a run: drop `aron_be_*` and `aron_test`, then recreate `aron_test`.
  - Raise `max_connections` (400 here).
  - Never start a second Gradle run while one is live, and never drop `aron_test` under a running build.
- `pkill -f <pattern>` matches its own shell when the pattern appears in the command line: use `./gradlew --stop`.
- The container can reboot mid-session: `service postgresql start` (the role and config survive).
- Another lane's pending-schema scaffold (`to_regclass` guards) must go in the same promotion as the migrations that
  replace it, or INT turns red. The lead approves the exact file list.

## Session 8, 2026-10-22 (lane/db, INT 529b02f6 merged): in progress

All edits below are in place in V0087-V0104 (none is on INT yet; INT stops at V0086), plus the new V0105/V0106.

| Job | State | What |
|---|---|---|
| 1 CI on e1250831 | run 1335: gates, android, release APKs, images, infra green; backend/web running | local on e1250831's tree: `ContractConformanceTest` failed (`/admin/permissions` lists NATIONAL/BRAND, absent from the contract enum before INT 658f65d0); fixed by merging INT 658f65d0, which carries backend-authz's contract. Full JVM suite re-run on the new tree |
| 1b CI run #1341 on 275438fb | red, fixed | `backend:platform` **PendingAuthzSchemaTest** (backend-authz's scaffold) asserts its schema is still missing; with V0087-V0090/V0105 it is not. Removed the scaffold as its comment orders ("delete with the push that picks the migrations up"): `PendingAuthzSchema` + its FreshDb call, the test, `resources/pending/authz-wave4.sql`; coverage in OwnerDirectiveV0087Test (+ NATIONAL holds TOP's role_grant_map). Local trap: parallel modules exhaust 100 Postgres connections when another client holds some; leftover `aron_be_*` databases kept connections open, so drop them before a run |
| 1c merges (lead 14:00) | done | INT f6278fc6 and lane/backend-admin 98651d47 merged. **backend-authz files removed** (lead-approved, nothing else of theirs): `backend/platform/src/test/kotlin/com/aktcl/aron/backend/platform/PendingAuthzSchemaTest.kt`, `backend/platform/src/testFixtures/resources/pending/authz-wave4.sql`, the `PendingAuthzSchema` object and its `FreshDb` call in `backend/platform/src/testFixtures/kotlin/com/aktcl/aron/backend/platform/TestDatabases.kt`. **backend-admin's pending fixtures no-op:** `ApiWorld` applies `pending/route_monthly_target.sql` and `pending/user-access.sql` only when `to_regclass` finds the table absent; V0089/V0096 create both, so they are skipped. Proof: (a) the whole `:backend:app:test` suite on this tree (RouteTargets and UserAccess tests run on db's tables); (b) applying both files after V0001-V0106 changes nothing (`pg_dump -s` before/after identical apart from the dump's random restrict token). The files themselves are conditional and idempotent (IF NOT EXISTS): untouched, backend-admin deletes them |
| candidate | **a60d941e declared** (CI #1352 green on all 9 jobs; local 1434 tests, 0 failed) | |
| a60d941e | **landed** on INT 529b02f6; dev deploy #238 applied V0087-V0106, dev seed Succeeded, slice smoke PASS (all steps) | messages sent: backend-authz, backend-reports; mdm note at the top of `db-mdm-device-command-answer.md` (mdm #1 archived) |
| relay N3 | in the next candidate | **V0107** copies TOP's role_grant_map rows to NATIONAL then deletes TOP's (idempotent); populated-DB test (a TOP-only grant ends on NATIONAL, TOP holds none) |
| relay seed 07 | in the next candidate: taken from lane/backend-authz 3b7747b5 unchanged, README row, test (re-run keeps a narrowed grant) | `db/seed/07_admin_module_grants.sql` (lane/backend-authz 3b7747b5): 15 modules = V0089 CHECK = contract AdminModule; loaded by name order; overlaps seed 01's admin1001 block (fold when it arrives) |
| candidate | **fb59e0e9 declared** (CI #1388 green, 9 jobs): INT b561b267, V0107, seed 07; rides train 9 last | |
| dev seed visibility | done | **How to confirm dev runs on Gulshan/Banani:** (1) from the next deploy on, the slice smoke log names codes: `slice: PASS 2b route, outlet, SKU: route BAN-SR-11 (22636), outlet SMOKE-SR-003 (...)` (route code from the bundle; a Mirpur code means seed 06 skipped); (2) seed 06 raises NOTICE `seed 06 applied ...` or `seed 06 skipped (config version of 04 present ...)` in the dev-seed container log (deploy.sh prints its tail when the seed fails); (3) on dev: `SELECT code FROM app.route WHERE id = 22636` (deploy #238/#240 smoke route). Smoke outlet per day: Sat/Tue SMOKE-SR-001, Sun/Wed -002, Mon/Thu -003, Friday none |
| backend-reports checked_in_at | built | **V0108** `dw.agg_daily_route.checked_in_at` (nullable) + 7-day SQL backfill with their rule (event route, else a user assigned to the route that day); test `v0108CheckedInAtIsBackfilledForTheLastSevenDays`; answer `docs/requests/db-backend-reports-checked-in-at-answer.md`, message sent (route_id only when the phone has a route loaded: keep the user fallback) |
| 3cfcd0af | CI #1394 green (android/web skipped by change detection) | declared together with V0108's head |
| 2 V0093 (lead ruling) | done | unique active-house index dropped (in place); `is_active`, the one-off dedupe and dim_geo house columns stay; test asserts duplicates are allowed and the index is absent; `db-wave4-tables.md` updated |
| 3 TOP -> NATIONAL | done | backend-authz 8ed44597 reached INT (658f65d0): **V0105** TOP users -> NATIONAL (scope_version + 1) + `app_user_no_top` NOT VALID, **V0106** validates; held file deleted; tests: populated DB re-roles `qatop1` to NATIONAL\|2, a TOP insert is refused (23514) |
| 6a BRAND menu | done (in place, V0087) | BRAND default of `cfg.web.menu_by_role` gains `credentials/change_password [write_own]` (backend-authz's fix) |
| 4 Opus checker V0096/V0102 | round 3 FAIL, fixed; round 4 PASS (+3 should-fixes taken) | **blocker:** a pending factory reset that expires broke both device_command CHECKs, so mdm's pull UPDATE raised 23514 forever for that phone; fixed. Guard now polices UPDATE (forward-only states, fixed request columns, write-once approval/delivery/result), reset needs a requester. V0096: `batch_uuid` FK to `route_target_batch`, month CHECK `extract(day) = 1`. New test `v0102CommandsMoveForwardOnlyAndAPendingResetCanExpire`. Round 4 (PASS, V0105/V0106 safe): approver set only with pending -> queued/rejected and on resets only; sig only with the move to queued; result only with the move to acked/failed; sent_at/push_sent_at only in their states (refused cases tested). mdm told in `db-mdm-device-command-answer.md`. Approver role stays API-only (noted) |
| 5 smoke outlet | done by db (no infra lane this wave) | `infra/sql/devseed-smoke-outlet.sql`: SMOKE-SR-001 (moved to GUL-SR-2 on dev, placement closed/reopened, location appended), -002 GUL-SR-1, -003 BAN-SR-11; on the test profile (no Gulshan seed) SMOKE-SR-001 stays on MIR-SR-D (BackendCoreAsksV0061Test loads the file). `infra/scripts/slice-smoke.py` takes the code list; Friday reported as "no route by design". Proved on a dev-profile and a test-profile database (second run changes nothing); infra SliceSmoke 5/6 locally (6th needs compiled Bicep) |
| 6 seed | complete | Friday-aware schedule, 351 target rows Oct-Dec, three holidays already in seed 06; evidence doc gains the smoke outlets (21 outlets a route on dev) |

## HANDOVER (session 7 -> session 8), 2026-10-22, lane/db e1250831 (INT 256572bb merged)

**Pushed, CI to judge.** Local evidence before the push (lead stopped the run at 557k context): db 267/0, backend
platform 79, auth 71, sync 34, masterdata 192, config 68, media 16, all green on lane/db alone; **analytics, notify,
app and shared:contract were not run on the final tree.** squawk 0 on V0087-V0104. Opus checker: round 1 FAIL (fixed),
round 2 PASS (it reviewed the tree before the backend-admin/mdm realignment of V0096/V0102; those two are unchecked).
Consumers' contract and per-lane deltas: `docs/requests/db-wave4-tables.md`; mdm answer `db-mdm-device-command-answer.md`.

| Migration | What it does | Source |
|---|---|---|
| V0087 / V0088 | role codes NATIONAL (11), BRAND (12); NATIONAL inherits TOP's role cfg values, BRAND the web-role defaults; `*_roles` lists naming TOP gain NATIONAL; menu_by_role NATIONAL = TOP, BRAND = dashboard + reports; role_grant_map TOP -> NATIONAL. V0088 validates role_def_role_check | backend-authz A1-A4 |
| held | TOP users -> NATIONAL + `app_user_no_top`: `db/held/authz_retire_top.sql` (INT tests still insert TOP users). Ship as the next pair when backend-authz says its tests are ready | backend-authz A4/A5 |
| V0089 | `app.user_module_grant` (15 fixed modules, r/w), admins seeded w; seed 01 grants admin1001 (guarded by to_regclass) | backend-authz B |
| V0090 | `app.user_brand_scope` (brand nodes only) | backend-authz C |
| V0091 / V0092 | `user_scope.node_type` += house | backend-authz D |
| V0093 | `house.is_active` (generated from status), one-off dedupe, unique active (territory, name) index, dim_geo house_id/name | brief s1 |
| V0094 / V0095 | `route.route_class` normal/manual/dummy_br/dummy_institutional (+ dim_geo, version) | backend-reports A1 |
| V0096 | `app.route_monthly_target` PK (route, sku, month) + `app.route_target_batch` | backend-admin DDL |
| V0097 | `dw.v_calendar`, `v_calendar_month`, `v_working_day`, `v_route_monthly_target` | brief s2 / reports A5 |
| V0098 | dim_product segment/brand/variant codes | reports A4 |
| V0099 | agg_daily_outlet_sku (partitioned), agg_daily_route_variant, route_last_position, agg_daily_route/zone columns, summary_freshness | reports B1-B5, B7 |
| V0100 | fact_visit radius_m, outlet/fix lat/lng, force_photo_uuid, completed, server_distance_m, within_fence (generated) | reports B6 |
| V0101 | `dw.v_final_submit_log` (first submit wins) | brief s3.3 |
| V0102 / V0103 | `mdm_policy`, `device.mdm_policy_id/version`, `device_command` + guard trigger, `dw.v_device_command`; V0103 validates the device constraints | mdm DDL |
| V0104 | lighters 25/box, entry units pack/pack/pack/dozen, cfg.sale.qty_entry_unit default, `cfg.sale.allow_loose_base_units` | docs/34 s4.2 |
| outlet.channel | nothing: NOT NULL with the six channels since V0004 | |
| seed 06 | Gulshan/Banani dev geography (built, tested in SeedGulshanBananiTest): dev profile only (no 04 config version); replaces 06_dev_real_route.sql; map `docs/evidence/seed-gulshan-banani.md` | D-SEED-02 |

**Session 8, in order:**
1. Watch CI on e1250831; fix forward. Not yet run here on the final tree: `:backend:analytics:test`, `:backend:notify:test`,
   `:backend:app:test`, `:shared:contract:jvmTest`.
2. **Known clash for backend-reports' branch:** their fixtures (NationalDashboardTest x11, NationalMapsTest x6,
   NationalDashboardApiTest x5) insert duplicate ACTIVE houses on purpose; V0093's unique index `house_active_name_once`
   refuses them (they called the index optional). Either drop the index in a new migration (V0105) or ask them to adapt.
   Lead's call; my recommendation: drop it (their query de-duplicates; the one-off dedupe stays).
3. Combined tree (lane/db with the re-role + backend-authz e449edb7 + reports ec0976ed): 68/1325 failed, list sent to
   backend-authz (TOP inserts, grant-less test admins -> 403, portal-login refusal, password rules). Their work.
4. Checker the realigned V0096 and V0102 (T1, Opus).
5. Infra: the dev smoke outlet sits on MIR-SR-D, which the dev seed closes (`db-infra-smoke-gulshan.md`); promote the seed
   with that change. Checker follow-up before the final account: retention of `route_last_position` (unpartitioned,
   personal) and `fact_visit.fix_lat/lng` (event_fact horizon > fix class).
6a. **backend-authz (12:28 UTC): lane/backend-authz 8ed44597 (lane CI run 1328 green) creates no TOP users and logs
   AMO/SR in only from phones: ship `db/held/authz_retire_top.sql` now** as the next pair (V0105 + V0106 validate, or
   after the house-index migration), and mirror their BRAND menu fix in a new migration: the BRAND default of
   `cfg.web.menu_by_role` also gets `{"menu":"credentials","page":"change_password","actions":["write_own"]}` (V0087 is
   pushed: fix forward). Message backend-authz the INT sha once these are on INT (they then delete their pending fixture).
6. Replies owed: candidate sha to backend-authz (session_019B2UpUbUmdw1wR25U3zPMj), backend-reports
   (session_01KfUFry6iMtXCCgfCCjZUCi), mdm (session_01DVRkAnyU5vkAm8vSEydVoa), backend-admin's next session; route.kind stays
   sr/amo, the API rule becomes `kind = 'sr' AND route_class IN ('normal','manual')`.

## Session 7, 2026-10-22 (Wave 4, docs/34): detail

**Built on lane/db:** V0087-V0103 (one test per migration, `OwnerDirectiveV0087Test`), the Gulshan/Banani dev seed
(`SeedGulshanBananiTest`), data dictionary regenerated, squawk 0. Realigned the same day to the two lane requests that
arrived mid-build (backend-authz `db-backend-authz-roles.md` e449edb7, backend-reports
`db-backend-reports-dashboard-summaries.md` ec0976ed). Consumers' contract: `docs/requests/db-wave4-tables.md`.
Opus checker: round 1 FAIL (1 blocker: factory-reset approval could ride in with the insert; 3 should-fix), all fixed.

| Row (docs/lanes/db.md Wave 4) | Migration | Note |
|---|---|---|
| NATIONAL, BRAND + TOP migration | V0087/V0088 | backend-authz's A1-A5 as written; TOP users -> NATIONAL (scope_version+1), `*_roles` lists gain NATIONAL, `app_user_no_top`. **Promote with or after lane/backend-authz** (INT tests insert TOP users) |
| user_module_grant | V0089 | 15 fixed modules (contract AdminModule), admins seeded w; seed 01 grants admin1001 |
| user_brand_scope | V0090 | brand nodes only |
| user_scope house | V0091/V0092 | backend-authz's D |
| house.is_active + dedupe | V0093 | generated from status; one active house per (territory, normalised name); dim_geo house columns |
| route.kind | V0094/V0095 | `route_class` (route.kind is sr/amo since V0002; backend-reports' name) |
| outlet.channel NOT NULL | none | NOT NULL with the six channels since V0004 (test) |
| route_monthly_target | V0096 | upload table + live/superseded rows (only a later upload of the same month supersedes), base units. backend-reports assumed `app.target`: told |
| calendar | V0097 | `dw.v_calendar`, `v_calendar_month`, `v_working_day`, `v_route_monthly_target` over dim_date + calendar_holiday |
| dim_product codes | V0098 | backend-reports A4 |
| summary tables | V0099 | backend-reports B1-B5, B7 as written (B1 prefix index dropped) |
| call distance/within_fence/completed | V0100 | backend-reports B6 + server_distance_m, within_fence generated |
| final_submit_log | V0101 | view, first submit wins |
| device_command | V0102 | two-person factory reset (approver SUPERADMIN or ADMIN mdm=w, never requester), forward-only, write-once delivery, support_ro without result |
| SKU pack sizes | V0103 | lighters 25, entry pack/pack/pack/dozen, qty_entry_unit default, `cfg.sale.allow_loose_base_units` |
| Seed Gulshan/Banani | seed 06 | dev profile only (no 04 config version); Mirpur fixture kept for CI/backend tests; map `docs/evidence/seed-gulshan-banani.md` |

**Open / for the lead:**
- infra: the dev slice smoke's outlet is on MIR-SR-D, which the dev seed closes: `docs/requests/db-infra-smoke-gulshan.md`;
  promote the seed together with that change (or keep the dev seed off).
- backend-reports: targets are `app.route_monthly_target` / `dw.v_route_monthly_target`, not `app.target`.
- Owner/AKTCL: Durga Puja 2026 date in the dev seed is 2026-10-21 (lunar; confirm).
- Decisions for DECISIONS.md (lead copies): D-DB-W4-01 `route_class` not `route.kind` (name taken); D-DB-W4-02 the
  calendar is views over dim_date + calendar_holiday, national holidays only; D-DB-W4-03 the Gulshan seed is gated to the
  dev profile by the absence of 04's config version, because ~250 backend assertions key on the Mirpur fixture;
  D-DB-W4-04 lighter catalogue pack size 25 (owner's "box of 25"), prices stay per piece; D-DB-W4-05 targets in a new
  `route_monthly_target`, the docs/27 `app.target` hook stays empty.

**Traps (new):** Maven Central 429 locally: copy the mirror init script of docs/24-build-spec-verification s9 to
`~/.gradle/init.d/` (never commit). PL/pgSQL `IF x <> CASE ... THEN ... END THEN` does not parse (IF stops at the first
THEN). A seed statement on a table newer than some test's Flyway target needs a `to_regclass` guard (BackendCoreAsksV0061Test
migrates to V0060 and loads the seed).

## Done

- **N-005 schema v1a** (`db/migrations/V0001`–`V0006`): schemas `app`, `dw`, `stg`; helpers `app.dhaka_date`,
  `app.div_half_up`, `app.touch_master`, `app.deny_mutation`, monthly partition maintenance
  (`app.partition_policy`, `app.ensure_partitions`); geography (wing, division, territory, house, zone, cluster),
  routes (`visit_kind` daily/3f/2f agreeing with `visit_days_mask`), `route_planned`, users (case-insensitive
  username), `user_scope`, `route_assignment` (one primary per route-day by exclusion), credentials and session
  tables, product tree, SKUs, five price types (`sku_price`, no overlaps), sales plans, offers as data, outlets with
  placement and location history, config registry (172 keys of docs/24 s9.5), scoped values, changes, versions,
  calendar, code lists, targets. Tests: `MigrationApplyTest` (empty DB, second run no-op, edited and removed
  migrations refused), `SchemaV1aTest` (16 rules). Checker: two rounds, all confirmed defects fixed.

- **N-006 schema v1b** (`V0007`–`V0009`): every one of the 42 record types of contract v1.1 has its table with the
  shared envelope (client_uuid, family_uuid, business_date + business_date_device, UTC captured_at, clock evidence,
  config/bundle versions, voided_at); partitioned visit/memo/memo_line/geo_fix/domain_event (unique keys include
  business_date, plus `app.client_uuid_once`); immutability guard with write-once columns and state-flow triggers;
  memo arithmetic and line gross as CHECKs; route-day, supervisor-day, QC header, due ledger, indent ledger (Phase 2),
  final submit, submit void, data-void barrier, request trail; programmes, enrolments, gifts, gift assignments, Astha
  targets, loyalty ledger (idempotent on source), content items, risk signals; hash-partitioned ingest registry,
  batch replay, rejected, quarantine, server generation, domain-event outbox; V0009 adds the v1.1 code lists and 54
  config keys. Checker: three rounds, PASS.
- **N-007 schema v1c** (`V0010`, `V0011`): devices (package = flavour), bindings (ordinals 0..3), nonces, status
  reports, rendered policy, app package catalogue for the block list, directives, push tokens, releases
  (maker-checker), enrolment tokens (hash only), device FKs, append-only hash-chained audit log (`chain_seq` under a
  lock, `app.audit_verify()`); dw dimensions (dim_date 2026–2030), partitioned facts, route/route-SKU/route-brand/
  zone/hourly/outlet aggregates; `app.dirty_key` with `app.mark_dirty`. Checker: two rounds, PASS.
- **N-008 seed** (`db/seed/`, `:db:seed`): `sr1001` primary on Daily (127), 3F Sun/Tue/Thu (42), 2F Mon/Thu (36)
  with 60 outlets near Mirpur 10; TSO territory and AMO zone scope; 42 SKUs x 5 prices from the CSV; test accounts
  (password hash only from `ARON_SEED_PASSWORD`); dev phone bound with ordinal 0; dev config overrides in their own
  version; idempotent. Answers `docs/requests/backend-seed-login-accounts.md`. Checker: two rounds, PASS.

- **R5 follow-up** (`V0012`, 2026-10-06): defaults of `cfg.web.menu_by_role` (docs/19 s5.3 mapped to the s8.5 roles,
  deferred programme pages left out) and `cfg.app.home_tiles` (SR: the app's tile order without Loyalty Point, Photo
  Capture and Astha). Test: `ConfigDefaultsTest`.

- **V0013** (2026-10-06): `refresh_family.device_uuid`; phone families must be bound or revoked (answers
  `docs/requests/backend-refresh-family-device-uuid.md`). Checker PASS.
- **Data as a product, docs/31 s3** (2026-10-07; one Opus checker, three rounds: 2 blockers + 6 should-fix, then 1
  blocker, then PASS):
  - `V0014` roles `api_rw`, `worker_rw`, `jobs_rw`, `web_ro`, `bi_reader` (NOLOGIN, NOINHERIT, no special
    attributes), grants generated from `app.db_role_grant` (table and column grants) by `app.apply_db_role_grants()`;
    existing roles are reused without ADMIN when correct, repaired with ADMIN, refused when superuser, BYPASSRLS or a
    member of anything else; global default privileges keep new functions from PUBLIC. `DbRolesTest` (7 tests:
    matrix both ways, each role under SET ROLE, second migrating login, repair path). Infra asked for the logins:
    `docs/requests/db-runtime-roles.md`.
  - `V0015` stable views `dw.v_daily_route`, `v_daily_sr`, `v_daily_outlet`, `v_daily_sku`, `v_collections`,
    `v_attendance`, `v_geo_integrity` (+ `dw.fact_attendance`); every column documented; `business_date` filters
    prune partitions (UNION ALL under one GROUP BY, not FULL JOIN); no coordinates or fix accuracy to web/BI.
  - `V0016` + `tools/data-dictionary/render.sh` + `DataDictionaryTest`: every app/dw table, view and column carries
    `owner | capture | retention | pii`; `docs/data-dictionary.md` (142 relations) is rendered from the catalogue and
    the test fails on a missing comment, a stale file or a table PII class below its columns. CI: already enforced by
    `:db:build`; `docs/requests/db-data-dictionary-ci.md` asks infra to keep it required.
  - `V0017` versioned domain events: `domain_event.payload_version` + catalogue `app.domain_event_type` (7 v1 events
    of docs/24 s12.5, JSON Schema each, published versions fixed); insert trigger refuses an uncatalogued type or
    version, a non-object payload or a missing required key. `docs/data-events.md` rendered by `DataEventsTest`.

- **Enterprise-bar audit rows** (2026-10-07; Opus checker per migration):
  - `V0020` (AUD-DA-03, TP-7, REL-03): roles `auth_rw`, `pii_reader` (outlet contact columns), `support_ro`; `web_ro`
    and `bi_reader` read only `dw.v_*` (+ `dw.v_outlet_masked`); `app.db_role_limit` + `app.apply_login_limits()` write
    the docs/18 timeouts onto login identities; tests with real logins (SHOW, DROP refused, 57014 cancel). Checker PASS.
    Infra and backend asks: `docs/requests/db-runtime-roles.md` (update section).
  - `V0021` (AUD-PERF-01): set-based client_uuid uniqueness. Measured on the docs/22-volume database (7 trading days:
    726k outlets, 2.0 M visits, 1.7 M memos, 2.8 M lines, 25 monthly partitions), 200-row batches of visit + memo +
    2 lines each: **66-79 ms p95 with V0007's per-row trigger, 8-16 ms p95 with V0021** (2.3-2.7 ms with no check);
    checker independently 37.8 -> 8.5 ms. `db/perf/generate.sql`, `db/perf/ingest_batches.sql`. Checker PASS.
  - `V0022`: `tracking_action.created` v1 catalogued (the analytics producer was refused); producer asked to drop the
    free-text note (`docs/requests/db-event-tracking-action-note.md`).
  - `V0027` AUD-DA-04 (system code lists): shipped in step with the one-clause fixture change in backend-masterdata
    (`docs/requests/db-masterdata-code-list-fixture.md`, answered).

- **Session 2 (2026-10-07, after the recycle; Opus checker per batch):**
  - CI run 254 role race: `DbRolesTest` repairs inside a rolled-back transaction; harness migrations and role changes
    under a server-wide advisory lock (`TestPostgres.RoleDdlLock`); backend FreshDb asked (`docs/requests/db-role-ddl-lock.md`).
  - `V0023` back-office tables (admin_asset, tutorial, survey/rubric + immutable versions, print_template,
    support_upload, feedback_status, price_batch with status flow and freeze) and the V0007 leave-decision fix
    (answers `backend-admin-content-tables.md`, `backend-admin-price-batch-table.md`).
  - `V0024` R17: flat `cfg.sync.reconcile_types` (default, stored and pending values), `cfg.bundle.outlet_fields`
    server-only, keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`,
    `cfg.sale.require_printer_before_sale` (lead ruling in `android-print-integration.md`; SchemaV1aTest allows them).
    backend-core told: `docs/requests/db-backend-core-config-and-device-v12.md`.
  - `V0025`/`V0026` v1.2 device columns `root_hints`, `root_hints_at`, `integrity_unavailable_reason`/`_at` (NULL = unknown).
  - `V0028` restrictive directions (answers `backend-admin-restrictive-dir.md`; ConfigWorkflowTest adapted in step).

- **Session 3 (2026-10-07; Opus checker, two rounds: 1 blocker (stale dictionary) + 1 should-fix (suffixed names), fixed):**
  - `V0039` web entry for backend-admin: `entry_unlock` (expiry written once, version moves), `web_entry_route_day`
    (re-save = new row with supersedes_client_uuid, old row closed by replaced_at/replaced_by; one live entry per
    route-day), `web_entry_line` (composite FK keeps route/date equal to the entry), `qc_summary_entry` + `_line`.
    Data void's statement works on all three. Answers `backend-admin-web-entry-tables.md` (the entry_unlock row).
  - `V0040` `password_history` (append-only, auth path may prune; worker cannot read) and keys
    `cfg.auth.password_history_depth`, `password_min_age_h`, `password_denylist_enabled` (docs/21 s4). Answers
    `backend-core-password-history.md` (asks 1, 2; the 10k list is not added, it needs a source the owner approves).
  - `V0041` `geo_breadcrumb` range-partitioned (rename, copy with ids, drop; house names restored), statement-level
    uuid-once. Answers `backend-core-breadcrumb-partitioning.md`.
  - Tests: `WebEntryPasswordBreadcrumbTest` (6); DbRolesTest, SchemaV1a/b adapted.

  - `V0042`/`V0043` AUD-DA-02:
    - Capture rows (visit, memo, due_collection, stock_movement) freeze zone and cluster, and visit and memo also the
      outlet channel and geo class. They are always stamped on insert; a device can never choose its own context.
      They are write-once.
    - `app.route_zone_history`, with `app.route_zone_on(route, date)`.
    - SCD2 `dw.dim_*_version` tables beside the type-1 dims, kept by the trigger `dw.scd2_track`. `dw.dim_user`.
      `*_key_on` lookups. Key columns on fact_visit and fact_memo.
    - Projector part asked of backend-reports: `docs/requests/db-backend-reports-scd2-keys.md`.
    - Checker round 1: 1 blocker (forged context through the sync writer) and 2 should-fix, all fixed.
  - `V0044`/`V0045` outlet_location_history bases `none`/`placeholder` without coordinates. Answers
    `backend-core-location-history-basis.md` (answer in `db-location-history-basis-answer.md`).
  - `OutboxCommitOrderTest` drains with bounded polls (integrator's flake report, CI run 37613735145).
  - **Salvage branch `claude/db-s3-salvage`**: ported in session 4 as V0047-V0052 (below); its DA-02 is superseded.

- **Session 4 (2026-10-07; Opus checker, round 1: 1 blocker (stale dictionary) + 3 should-fix, fixed):**
  - `V0047` AUD-PERF-08: four prefix-duplicate indexes dropped; `IndexHygieneTest` fails on any new one.
  - `V0048` AUD-DA-07 part: `dw.build_dim_date(from, to)` extends the calendar past 2030 (worker only).
  - `V0049`/`V0050` AUD-DA-05: outlet `nid`, `tin`, `trade_license` (empty, unread) replaced by `*_enc` bytea +
    `pii_key_id`; `app.pii_key` (wrapped DEKs, never deleted, one active key) for api_rw only. Audit-writer redaction
    asked of backend-admin/core: `docs/requests/db-audit-pii-redaction.md`. Trigram search held:
    `db/held/outlet_search_trgm.sql`, `docs/requests/db-azure-pg-trgm.md` (infra).
  - `V0051`/`V0052` AUD-DA-06 (no-ruling part): `app.retention_policy` (docs/16 s13.1 windows; API cannot see it),
    `partition_policy.retention_class`, `app.archive_manifest` (status flow, export facts written once, jobs_rw only),
    `app.archive_candidates(today)`, `app.default_partition_rows()` (worker/jobs logins only). Capture-table
    partitioning stays deferred (D-DB-PART-01).
  - `V0053` `cfg.sec.record_signature_mode` (docs/19 s9, enum_order, global) and `V0054` `app.security_event`
    (append-only, api_rw/auth_rw insert, worker reads). Answers backend-core's two requests on lane/backend-core; answer
    in `docs/requests/db-security-event-and-signature-mode-answer.md`. Opus checker PASS (round 3).
  - `V0055` `cfg.app.local_history_days` (7, 1..30), `cfg.app.outbox_keep_days` (3, 1..14, re-sync rule in bounds_rule),
    `cfg.app.image_cache_mb` (40, 10..70), global, device: docs/19 values, not the request's draft (answer
    `docs/requests/db-app-cfg-keys-answer.md`). Sonnet checker PASS. Not yet registered: `cfg.app.rejected_keep_days`
    (docs/19 line 679; no request).
  - `V0056`/`V0057` `app.ingest_registry.flags text[]` (`resync_late`, F-SYS-089; catalogue-only add, CHECK NOT VALID
    then validated). Answers `backend-core-resync-late-flag.md`; merge-not-assign note for the conflict path in
    `docs/requests/db-resync-late-flag-answer.md`. Opus checker PASS (round 4).
  - `V0058` working-day window keys (`cfg.calendar.window_unit` calendar|working_days default calendar,
    `cfg.bundle.stale_max_cal_days_ceiling`, `cfg.calendar.break_overrides` list, `cfg.calendar.prefetch_next_working_day`)
    and `V0059`/`V0060` flags `config_stamp_regress`, `checkout_too_early` (each known flag at most once) + partial index
    for the regress count. Answer `docs/requests/db-working-day-keys-and-flags-answer.md`; break-override item checks asked
    of backend-admin (`db-break-overrides-validation.md`). Opus checker PASS (round 5; should-fix applied).

## Session 5, 2026-10-15 (build restart; lead's backend-admin rows and backend-core memo source)

- `V0074` `app.dues_adjustment` (answers `backend-admin-dues-adjustment-table.md`, F-ADM-036, T1 money): signed
  milli-taka, write_off < 0, maker-checker CHECK, guard trigger (insert pending only; decided once, only status/
  decided_by/decided_at/decision_note change; no delete, no truncate), decided_at >= created_at. On approval
  backend-admin writes the due_ledger `adjustment` row (source_client_uuid = client_uuid, business_date =
  app.dhaka_date(now()), user_id = the checker). api_rw SELECT/INSERT/UPDATE, no DELETE for anyone.
- `V0075`/`V0076` `app.sku.image_asset_id` -> `app.admin_asset` (FK NOT VALID + VALIDATE).
- `V0077`/`V0078` (answers `backend-core-memo-source.md` + addendum, F-API-038): `entry_source` device|manual and
  `entered_by_user_id` on memo and visit (CHECK manual iff entered_by), registry flag `manual_entry`, risk code
  `MEMO_DUPLICATE_SUSPECT` (weight 30), `dw.fact_memo`/`fact_visit.entry_source`, `dw.v_daily_sr.memos_entered_manually`.
  The two FKs on partitioned tables cannot be NOT VALID (PG16): added directly at pilot size; the D-DB-PART-01
  pre-staging batch should add such FKs in their own migration. Open for backend-reports:
  `docs/requests/db-backend-reports-entry-source.md` (Aggregator copies entry_source). backend-core closed the
  device-path spoofing gap the checker found (RecordWriter sets the source itself, lane/backend-core 89c1765a).
- `V0079`/`V0080` (lead, T1): |amount_mtk| <= 10^13 on dues_adjustment and due_ledger (BETWEEN, NOT VALID + VALIDATE).
  Checker notes routed to the lead: backend-admin has no API cap on the adjustment amount yet (contract `Mtk` is a plain
  int64), and memo money has no upper bound, so a memo with due_mtk > 10^13 would fail its ledger insert (sync lane cap).
- `V0081` (lead, T1): a paper web_entry visit matched to the phone's memo is superseded with the existing data-void
  tombstone `app.visit.voided_at` (no new column); `docs/requests/db-backend-core-visit-supersede.md` gives
  `matchBackfill` the UPDATE (RETURNING route_id for the dirty key), the registry status and the audit fields.
- `V0082`/`V0083` (answers `backend-reports-db-entered-by.md`; lead row 13:40): `dw.fact_memo`/`fact_visit.entered_by_user_id`
  (one-way CHECK: device rows name no web user; the reverse is not enforced until the projector copies the column),
  `dw.fact_supervisor_day` (unpartitioned, no role CHECK so re-roled users still rebuild), dirty kind
  `supervisor_day_agg` (callers of app.mark_dirty enqueue it; no trigger). backend 788/0, db 246/0 + rerun 11/0.
- Checks: backend 788/0 and db 243/0 on V0079-V0081, changed classes rerun green after checker fixes; squawk ok.
- Checks: db classes 152/0 after the last fixes (full db 240 with 2 test bugs, fixed), backend 781/0, squawk ok;
  Opus checker per batch (V0074 one should-fix closed; V0077 PASS, test fixes applied).

## Session 5, 2026-10-08 (lead's two backend-core requests)

- `V0069` `app.client_error` (answers `backend-core-client-error-table.md`, F-SYS-032). **Deviation (D-DB-S5-01, for
  DECISIONS.md):** the request asked for a BEFORE UPDATE OR DELETE trigger and a retention job that deletes old rows;
  both cannot hold. UPDATE and TRUNCATE are refused by trigger, DELETE is granted only to worker_rw (jobs_rw inherits
  it; RetentionJob runs as jobs_rw); api_rw cannot update or delete. Retention class telemetry like `app_error`.
  **Open for backend-core:** RetentionJob today only manages partitions and archive manifests; nothing deletes
  `client_error` rows (nor `app_error`, whose V0007 trigger refuses DELETE). Add a step deleting `client_error` by
  business_date past the telemetry window of `app.retention_policy`.
  **Closed 05:44 UTC:** backend-core added it to RetentionJob (lane/backend-core dc0c6f26; past telemetry keep_months,
  10k rows per run). `app_error` stays undeleted (V0007 trigger) by design.
- `V0070`/`V0071` `security_event.kind` + `mfa_enrol`, `mfa_verify_failure` (answers `backend-core-mfa-security-events.md`).
- Opus checker PASS (test nit applied: the retention DELETE runs under SET ROLE worker_rw, api_rw refused).

## Session 5 (2026-10-07, from ~23:00 UTC)

**Pushed lane/db 59de9f29: V0061-V0068 on top of the INT merge (f4945a81).** db 226, backend 727/0, squawk ok. Opus
checker per batch: V0061-V0064 PASS (nits applied); V0065-V0068 one blocker (V0067 missing from SchemaV1aTest's ruled
set) fixed before the push. Integrator and backend-core told.
- `V0061` `app.media_upload` (answers `backend-core-media-upload-ledger.md`, unblocks F-API-007): as asked, plus
  immutability (UPDATE/DELETE/TRUNCATE refused, 42501), out of api_rw's `*/update` row, and a CHECK that `blob_path`
  ends in `/<media_uuid>.jpg`. Notes for backend-core: (a) the CHECK compares the canonical lowercase uuid text, so
  build the path from `UUID.fromString(x).toString()` (an uppercase uuid in the path is a 23514, map it to 400, not
  500); (b) `blob_path` is UNIQUE and has no purpose in it, so one media_uuid uploaded as feedback and then as support
  conflicts on blob_path: keep the conflict target explicit `ON CONFLICT (media_uuid, purpose) DO NOTHING` and map a
  remaining 23505 to 409 (or decide that one uuid has one purpose).
- `V0062` `outlet_confirmed_lat_lng` partial index (answers `backend-core-outlet-geo-index.md`); plan test uses it.
- `V0063`/`V0064` `app.device.last_sync_error` with the format CHECK (answers `backend-core-device-telemetry-columns.md`).
- `V0065`/`V0066` `route_day.last_bundle_at`, `bundle_count` (answers `backend-core-route-day-downloads.md`, F-SYS-025).
  The UPDATE goes through `route_day_touch`, so every counted download also moves `updated_at` and `version`: an
  If-Match on route_day taken before a bundle download goes stale.
- `V0067` `cfg.app.rejected_keep_days` 30 (7..90), docs/19 s9 (candidate row, no request).
- `V0068` AUD-PERF-07: pg_trgm GIN on outlet name and code (released from db/held). ORDER: needs lane/infra bb221dfe
  (PG_TRGM in azure.extensions) in the same or an earlier deploy.
- Test `BackendCoreAsksV0061Test` migrates to V0060, loads db/seed and `infra/sql/devseed-smoke-outlet.sql`, then
  migrates forward (the dev database's path before the 2026-10-08 phone checks): outlets fingerprint unchanged,
  SMOKE-SR-001 found through the new index.

## Handoff (session 4, 2026-10-07 ~19:40 UTC)

**On lane/db: V0039-V0060 (head after 2205ff98), green locally (db 218, backend 660 on the INT merge of ~18:00);
checker PASS per batch.**
INT has V0001-V0040; the integrator promotes the rest. Session 4 added V0047-V0060 (see "Session 4" under Done).
Next rows for session 5 (lead, 22:42 UTC; read on origin/lane/backend-core, T1 first, Opus checker):
1. `docs/requests/backend-core-media-upload-ledger.md` (unblocks F-API-007).
2. `docs/requests/backend-core-outlet-geo-index.md`.
3. `docs/requests/backend-core-device-telemetry-columns.md`.
4. F-SYS-025 route-day download columns.
Tell the integrator (session_016uXu7QkPQGGTn3FmR92YHu) the head and backend-core (its current session is in the
registry) when each lands. Numbering continues at V0061.
Also open: AUD-PERF-07, blocked on infra adding PG_TRGM to `azure.extensions` (`docs/requests/db-azure-pg-trgm.md`);
then release `db/held/outlet_search_trgm.sql` as the next V#### with a plan test. Every other db backlog row is in db.csv. Candidates if idle: `cfg.app.rejected_keep_days` (docs/19 line 679, no request yet);
due_ledger rows in `db/perf/generate.sql`; `due_ledger.memo_business_date` only if bundle p95 needs it.
Local test trap added: point `ARON_TEST_PG_URL` at a dedicated `aron_test` database (the platform smoke test migrates
the URL's database itself) and recreate it after editing an unshipped migration (checksum).

## Handoff (session 3 recycled, 2026-10-07 ~13:10 UTC)

**On lane/db (V0039-V0046), green locally on db (200) and every backend suite; Opus checker PASS per batch.**
INT has V0001-V0038; the integrator promotes the rest.
- `V0039` web entry tables (backend-admin). `V0040` password_history + 3 keys (backend-core).
- `V0041` partitioned geo_breadcrumb (backend-core).
- `V0042`/`V0043` AUD-DA-02 (capture context, route-zone history, SCD2 dimension versions).
- `V0044`/`V0045` location-history bases none/placeholder (backend-core).
- `V0046` route_day acting-user index: dayStates plan becomes a BitmapOr, 0.03 ms vs 0.40 ms at 120k route-days.
  Checker nit: in the final account, build it CONCURRENTLY if route_day already holds history.

**Open asks to other lanes:**
- `db-backend-reports-scd2-keys.md` (backend-reports): zone from the capture row, fact keys, dim_user, the rebuild test.
  This closes AUD-DA-02 point 5.
- `db-backend-core-config-and-device-v12.md`, `db-role-ddl-lock.md`, `db-event-tracking-action-note.md`, as before.
- backend-admin: history rows on every basis change (`db-location-history-basis-answer.md`). API notes on web entry
  (`backend-admin-web-entry-tables.md`).

**Next rows (lead's order):**
1. Infra's per-app logins: answered by V0029 (`db-runtime-roles-gaps.md`); nothing open for db.
2. Salvage port done (V0047-V0052); DA-07 recorded above. PERF-03, db side done: V0020 `app.db_role_limit` +
   `apply_login_limits()` set the docs/18 timeouts (api 15 s/lock 3 s, auth 5 s, worker 10 min, jobs 30 min, web 60 s)
   on each login; V0029 closed the grant gaps. Left to infra: `dbPerAppLogins = true` (`db-runtime-roles-gaps.md`).
3. Query-plan candidates (session 4 review):
   - `BundleService.openMemos` joins `app.memo` on client_uuid only (due_ledger has no memo business_date), so each open
     memo costs one probe of the (client_uuid, business_date) unique index per month partition: ~13 probes per memo in
     the hot window, about 1 ms per 50 open memos. No migration now; if bundle p95 shows it, add
     `due_ledger.memo_business_date` (backend-core writes it) so the join prunes. Needs due_ledger rows in
     `db/perf/generate.sql` to measure (still open).
   - `task.route_id`: no index needed, no query filters on it (Tasks.kt only reads it).
   - `outlet_change_request (status, business_date)` and `(outlet_id)` and `task (assignee_user_id, status)` exist (V0007);
     BundleService's `ongoing` task list and the pending-request EXISTS use them.

**Traps:**
- Push only to `lane/db` (not `claude/db-wip-v0023`, not INT). A pushed migration is shipped: fix forward only.
- Never `pkill -f "gradlew -q"` from a shell whose own command line contains that string (it kills itself).
- Two Gradle runs at once delete each other's test results ("0 tests"). Run modules one after another; recreate
  `aron_test` (DROP ... WITH (FORCE)) when a local migration changed, or platform's smoke test fails on the checksum.
- Checkers must not run Gradle while the suites run: the shared server hits max_connections ("too many clients").
- Run Gradle one invocation at a time. Concurrent runs corrupt the test results.
- Before a full run: `rm -rf db/build/test-results`, then recreate the shared `aron_test` DB if a local migration was
  edited (Flyway checksum).
- The local `aron_test` login is not a superuser. Backend tests then hit two problems that CI does not have: the DROP
  DATABASE FORCE race with autovacuum, and `session_replication_role`. For a CI-like run, use a local superuser test
  login (`aron_su`, password set at run time, never committed).
- Always rerun the db and all backend suites AFTER merging INT and BEFORE pushing. Other lanes add outbox producers and
  fixtures that interact with db changes.
- Every new migration needs `SET lock_timeout = '5s';` and must pass squawk:
  `tools/ci/migrations-check.sh origin/<INT> <squawk>`; install squawk with `tools/ci/install-tool.sh squawk <dir>`.
  Squawk rules: no DROP FUNCTION, no DROP DEFAULT, no VALIDATE in the same transaction.
- A new event type needs a catalogue row (`app.domain_event_type`) before its producer ships.
- A new table or column needs `COMMENT ON` with the metadata line, then `tools/data-dictionary/render.sh` (or
  `-Paron.writeDictionary=true`).
- A migration that creates a table calls `SELECT app.apply_db_role_grants();`.

## AUD-DA-07: dw objects of docs/16 M-61..M-99, built or deliberately deferred (2026-10-07, session 4)

Built (V0011, V0015, V0042/V0043, V0048 and backend-reports' DDL): `dim_date` (+ `dw.build_dim_date`), `dim_geo`,
`dim_outlet`, `dim_product`, `dim_user` and their SCD2 `*_version` tables (M-61 except `dim_outlet_pii` and
`dim_supervisor_assignment`); `fact_visit`, `fact_memo` (M-62/M-70 part); `agg_daily_route`, `_route_sku`,
`_route_brand`, `_route_segment`, `_zone`, `_outlet`, `agg_hourly_zone` (M-63 part); `fact_device_day` (M-69),
`fact_geo_fix` (M-80), `fact_attendance`, `fact_activity`, `fact_consent`, `fact_device_integrity`,
`agg_daily_screen_use`; stable views `v_daily_*`, `v_collections`, `v_attendance`, `v_geo_integrity`,
`v_outlet_masked` (M-66/M-68 part).

Deferred, each built only when a BUILD row or a dashboard needs it (no consumer today):
- Programme, target and offer objects (M-64 programme part, M-65, M-67 target KPIs, M-71, M-76, `dim_gift`,
  `dim_program_period`, redemption and gift facts): deferred with the programmes themselves (sponsor rule 2, docs/27).
- `fact_memo_line` and `dim_sku_price`/`fact_price_change` (M-62 part, M-74): when the BOD line-grain dashboard or a
  price-compliance report lands; the daily route-SKU aggregate serves today's tiles.
- Dues: `fact_due_ledger`, `fact_due_allocation`, `agg_memo_due_open`, `agg_outlet_due_ageing_daily` (M-62 part, M-73):
  with the dues-ageing report (phase 2b); `v_collections` covers today's collection tile.
- Month aggregates and balances (M-64 non-programme), `agg_daily_user`, `agg_daily_user_sku` (M-72), `fact_dq_flag`
  (M-75), the s8.9 capture facts other than geo_fix (M-77..M-93: QC, survey, media, tasks, assessments, distribution),
  `fact_web_entry_line`, `fact_config_change`, `dim_app_version`, `dim_reason`, `dim_device`, `bridge_*`,
  `agg_outlet_visit_streak`, `agg_month_outlet_category`, `dim_outlet_pii` (D-107: PII stays in app behind grants),
  `dim_supervisor_assignment`: land with their capture tables or first report, as docs/16 schedules (2a..5a).

## Lead rulings applied (docs/24 s14a, 2026-10-06)

R1 registry hash partitioning, R2 only the s9.5 keys, R3 scope_id ordinals, R4 `_` in SKU codes: already as built.
R5: V0012. R6 / docs/27: no programme, target or discount tables or migrations will be added; the ones already shipped
(programme, programme_enrolment, gift, gift_assignment, astha_target, loyalty_ledger, redemption, redemption_line,
gift_photo, target_*, offer*) stay as empty hooks and are not edited.

## Next

- Index and query-plan review at docs/22 volume (`db/perf/generate.sql`): ingest measured (V0021); bundle, worker and
  dashboard plans in progress. Then AUD-DA-01 (outbox commit order, dirty-key dead letter), DA-02, DA-05..08, PERF-03/07/08.

- Back-office tables of docs/24 s12.1 that no db row names (`survey`, `survey_question`, `rubric`, `tutorial`,
  `print_template`, `supervisor_target`, `web_entry_*`, `qc_summary_entry`, `entry_unlock`, `dues_adjustment`,
  `price_batch`, `tracking_action`, `report_export_log`, `client_error`): added only when a docs/25 row of another lane
  needs one (`python3 tools/my-rows.py db` lists no db row after Day 1).

## Sponsor go-live checklist (db)

- [ ] AKTCL confirms the authored code lists of the code-list migration (V0027): `void_reason` (Q43), `stock_variance_reason`,
  `outlet_close_reason`, `submit_void_reason`, `edit_reason` (two more live-app reasons, MQ-18), `feedback_category`
  (Q-62), and adds codes through the admin code-list page if needed.
- [ ] AKTCL supplies Bangla labels for every code-list item whose `label_bn` is NULL
  (`SELECT list_key, code, label_en FROM app.code_list_item WHERE label_bn IS NULL AND valid_to IS NULL`).

## Not verified here

- N-005 "applies on the Azure dev database": not reachable from this container; it needs
  `docs/requests/db-azure-btree-gist.md` (infra) first.

## Requests filed

- `docs/requests/db-azure-btree-gist.md` (infra): allow-list `btree_gist`.
- `docs/requests/db-docs19-config-keys.md` (lead): seed the docs/19 keys outside s9.5?
- `docs/requests/db-config-scope-ids.md` (lead): integer `scope_id` for role and geo_class scopes.
- `docs/requests/db-sku-code-spaces.md` (lead): catalogue SKU codes contain spaces; contract pattern does not.
- `docs/requests/db-runtime-roles.md` (infra): runtime logins as members of the V0014 roles.
- `docs/requests/db-data-dictionary-ci.md` (infra): keep `:db:build` (dictionary gate) required in CI.

## Decisions taken (to be copied to DECISIONS.md by the lead; the playbook forbids lanes to edit it)

| Date | Decision | Reason |
|---|---|---|
| 2026-10-05 | Enumerations are `text` + CHECK with the contract's values, not PostgreSQL enum types | adding a value is an ordinary additive migration |
| 2026-10-05 | `cfg_value.scope_id` is 0 for global, the role ordinal for `role`, the geo_class ordinal for `geo_class` | contract `scope_id` is an integer (request filed) |
| 2026-10-05 | No `cfg_version` 0 row: no row means version 0; version 1 (by the disabled `aron.system` user) carries the s9.5 role defaults: `cfg.auth.password_min_len` 12 and `cfg.auth.access_ttl_min` 15 for DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN | s9.5 states those defaults per role; ConfigVersion needs a committing user. TSO counts as a field role (8, 60 min) because it captures on the phone |
| 2026-10-05 | `cfg_key.bounds` holds only ConfigBounds members; free-text bounds (time ranges, rules) go to `cfg_key.bounds_rule`; `risk_class` is the lowest class s9.5 names, `risk_rule` keeps the escalation text | ConfigBounds is `additionalProperties: false` |
| 2026-10-05 | `route` daily requires mask 127, 3f three bits, 2f two bits | contract Route says daily = 127 |
| 2026-10-05 | Master rows: `version` always moves forward on UPDATE (trigger) | If-Match must never match a stale version |
| 2026-10-05 | Tables listed in `app.partition_policy` are never the target of a foreign key; children reference parents by `client_uuid` | re-routing default-partition rows detaches the default partition |
| 2026-10-07 | `worker_rw` gets table-level UPDATE on the worker-owned app tables (route_day, visit, media, ...); the guard triggers limit the columns | column grants would have to be kept in step with every new column; the triggers already enforce it |
| 2026-10-07 | `worker_rw` reads every app table except credentials and one-time secrets (`mfa_secret`, `device_otp`, `refresh_token`, `enrolment_token`, `app_user.password_hash`); `push_token` stays readable | the worker sends pushes |
| 2026-10-07 | Code-list migration (V0027) `day_exception_reason` codes follow docs/16 (`dh_out_of_stock`, `sick`) plus docs/19's `other`; channel/geo_class codes are lower case with the canonical value in `attrs.value` | docs/16 owns the data model; the code pattern is lower case (request to the lead: `docs/requests/db-code-list-decisions.md`) |
| 2026-10-07 | `jobs_rw` = `worker_rw` + `ensure_partitions`; no rights on `stg` or job bookkeeping yet | no job table exists; added with the first job that needs one |
| 2026-10-07 | `v_geo_integrity` covers every visit kind of the user; `v_daily_sr` counts SR calls only | integrity is about a person's fixes |
| 2026-10-07 | Domain-event catalogue enforced by a trigger, not a foreign key; `payload_version` nullable (only pre-V0017 rows) | PG16 cannot add a NOT VALID FK to a partitioned table (squawk gate); the outbox is append-only so it cannot be backfilled |
| 2026-10-07 | Event payloads carry ids, codes and amounts only, never names, phones, NIDs or coordinates | keeps the outbox pii: none; consumers read personal fields under their own grants |

## Notes for other lanes

- Backend (outbox producers): only catalogued events (`docs/data-events.md`), `payload_version` explicit (default 1);
  required keys are enforced per event once `enforce_required` is on (V0018; `docs/requests/db-event-payload-v1.md`);
  a new event or a breaking payload change is a db migration, ask through docs/requests.
- Every lane adding a table or column: `COMMENT ON` it in the same migration with the metadata line (V0016 header),
  then `tools/data-dictionary/render.sh`; a migration that creates a table calls `SELECT app.apply_db_role_grants();`.

- Backend: `app.ensure_partitions()` daily; prune `app.ingest_registry` by `received_at` after
  `cfg.retention.ingest_registry_days`; write the registry row first in the ingest transaction (it is the
  concurrency-safe uniqueness point); use `app.mark_dirty(kind, subject, date)` for bundle and aggregate refresh.
- Backend: the domain-event id is not commit order; the projector should read with a small lag window or track gaps.
- Anyone with a local database migrated before 2026-10-05 13:00 UTC: drop and recreate it (V0007 to V0011 changed
  before they were first pushed).

- Backend: run Flyway with the defaults (`validateOnMigrate` on, `cleanDisabled` true). Call
  `SELECT app.ensure_partitions()` daily from the worker and alert on `app.partition_policy.last_error`.
- Backend: master updates need no version arithmetic; `UPDATE ... WHERE id = :id AND version = :ifMatch` and the
  trigger sets `version + 1` and `updated_at`.
- Tests of the db lane need a role with CREATEDB (each test class creates and drops its own database).

| 2026-10-07 | New cfg keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`, `cfg.sale.require_printer_before_sale`: scope global, delivery device, risk 1, effect B, editor `cfg.edit.field` | lead ruling (android-print-integration.md); `cfg.edit.field` as `cfg.memo.reprint_max` and `cfg.print.template_version` |
| 2026-10-07 | `restrictive_dir` set only where break-glass can compare (numbers, ordered enums); `cfg.release.blocked_version_codes` stays `none` (JSON object) | the backend comparator handles numbers and enum order only |
| 2026-10-07 | V0024 rewrites a not-yet-in-force cfg_value row in place (trigger lifted inside the migration only) | a closed stub would still be listed as scheduled by the config delta; nobody ever resolved the row |

## Lead note, 2026-10-07 ~17:10 UTC (for the active db session)

A parallel db session (started during the stall recovery) built V0039-V0045 before your V0039-V0041 landed,
then stood down under the dedupe rule. Its finished, checked work is on **origin/claude/db-s3-salvage**
(head 8560bc98): PERF-08 index hygiene, DA-05 PII envelope, DA-02 capture context + route zone history,
DA-06 retention/archive manifest, DA-07 build_dim_date, PERF-07 trigram (held), 4 request files and answers
to two backend-core requests (task columns; device integrity columns). Cherry-pick and **renumber to V0042+**
rather than rebuilding; delete the salvage branch when absorbed.

## Lead ruling addendum (2026-10-07 ~17:20 UTC)

- **D-DB-PART-01:** capture-table partitioning is DEFERRED to a scheduled pre-staging migration batch
  (after the SR slice runs on dev, before the final-account staging deploy). See DECISIONS.md. Keep
  `db-partitioning-ruling.md` open with status "scheduled: pre-staging gate".
- The salvage branch also carries three routable requests (infra PG_TRGM allow-list; backend-admin
  owner_name/address audit redaction; backend-reports capture-context projection). They route normally
  once absorbed onto lane/db.
- DA-02 note stands: dims are NOT SCD2; backend-reports' ~40 queries read them live. The capture-context
  columns are the bridge. Revisit at the staging gate with the partitioning batch.
