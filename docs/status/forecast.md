# Forecast (lead, updated each lane check)

Day 1 = 2026-10-05, Day 7 = 2026-10-11, hard cap Day 10 = 2026-10-14. Written 2026-10-07 03:50 UTC (about Day 3 morning).

## Method
Rows done: 26 of 446. Calibration from the Day-1 time logs: S 18 min, M 34 min, L 48 min per row including the checker (thin sample: 18 rows, all foundation work). Real work will be slower (UI rows, device checks, integration, fix cycles), so the table also shows a x2.5 pessimistic factor. Lane-days assume about 14 productive hours a day per lane.

| Sub-lane | Rows left | Optimistic lane-hours | Pessimistic (x2.5) | Pessimistic lane-days |
|---|---|---|---|---|
| backend-core | 48 | 21.5 | 53.8 | 3.8 |
| web-config | 40 | 18.8 | 46.9 | 3.4 |
| backend-admin | 41 | 17.7 | 44.3 | 3.2 |
| web-dashboard | 48 | 16.8 | 41.9 | 3.0 |
| android-amo | 40 | 16.3 | 40.7 | 2.9 |
| android-core | 34 | 15.0 | 37.4 | 2.7 |
| android-sr-a | 35 | 14.5 | 36.2 | 2.6 |
| backend-reports | 27 | 14.1 | 35.2 | 2.5 |
| android-sr-b | 22 | 11.6 | 28.9 | 2.1 |
| web-admin | 23 | 10.1 | 25.2 | 1.8 |
| qa | 18 | 9.2 | 23.1 | 1.6 |
| android-tso | 22 | 8.7 | 21.8 | 1.6 |
| android-geo-dpc | 9 | 5.8 | 14.4 | 1.0 |
| android-print | 8 | 3.9 | 9.8 | 0.7 |
| infra | 4 | 2.0 | 5.0 | 0.4 |
| shared | 1 | 0.8 | 2.0 | 0.1 |
| **total** | 420 | 186.7 | 466.8 | |

## Reading
- With the 8 lanes started on 2026-10-07 and the 6 existing lanes working continuously, the longest pessimistic chain is backend-core (about 4 lane-days) and the SR app lanes; the rest run in parallel. That fits inside Day 7 (2026-10-11) **if lanes never idle again**. Overnight on 2026-10-06 to 07 two critical lanes (backend-core, android-core) sat idle for about 15 hours waiting for a go-ahead: that cost roughly one lane-day each and is the main reason Day 2 is not finished.
- Not parallelisable by adding lanes: the owner's device checks (printing on the MP-58N, GPS and spoofing apps on the phones, device-owner enrolment on a factory-reset phone, the 8-hour battery run) and integration of the SR slice end to end. These need the owner's hands; they are listed in docs/status/device-checks.md. Schedule risk is concentrated there.
- Wave 2 lanes (android-amo, android-tso, qa) start when the SR slice (login, bundle, visit, sale, memo, sync) runs end to end on dev, expected within Day 3 to Day 4.
- The full 8,500-user proof depends on the final Azure account (docs/28); until then the claim is designed and tested small.

## Status of the plan
Day 7 holds if: (1) no lane idles more than 2 hours (the lane check runs every 2 hours), (2) the owner runs the device checks the evening they are posted, (3) usage limits do not stop work for more than a few hours (docs/29 s6). Day 8 to 10 stay the buffer, not the plan.

## Update 2026-10-07 04:58 UTC (Day 3, lead lane check)
- Rows time-logged as finished: **175 of 446** (was 26 at 03:50). Per lane: web-dashboard 49, web-config 40, android-sr-a 24, infra 14, android-core 9, backend 8, shared 7, db 7, android-geo-dpc 6, backend-admin 5, backend-reports 3, android-print 2, web-admin 1, android-sr-b 0 logged (its batches are on INT but not in a csv yet; lead asked for the csv).
- Honest reading: "finished" means built and checked by an independent checker against the contract, mocks and fakes. The web lanes run against mocks, so their count is ahead of the integrated truth; the real risk moves to **integration** (contract v1.2, bundle on a real device, sync end to end on dev) and to **CI throughput** (runs queued 13+ min; infra asked to fix concurrency, CodeQL and Dependabot load at 04:50).
- Critical path now: backend-core (new session, 0 rows logged since recycle), android-core (Room v3, F-SYS-049/006), android-sr-a Compose screens (kit slices 1 to 3 on INT, told to proceed), then the SR slice on dev (login, bundle, visit, sale, memo, sync). Wave 2 (android-amo, android-tso, qa) starts when that slice runs on dev.
- Recycles requested at the 450k context limit: db (662k), backend-admin (512k), android-print (482k), web-dashboard (483k).
- Contract v1.2 (additive batch of 8 requests, rulings R10 to R17) is being applied by an Opus agent; lanes told to pull it and wire.

## Update 2026-10-07 10:50 UTC (Day 3, lead lane check)
- Rows from the backlog: **266 of 490 BUILD rows built and checked (54.3%), 236 on INT (48.2%)**; the 490 is the current backlog (33 deferred, 96 dropped). Web 98 to 100 percent (against mocks), shared 100, SR 79 built but 42 on INT, shared Android core 54, backend 41, db 56, infra 12 (its work is mostly lead tasks without backlog rows), AMO, TSO and QA 0 (wave 2 not started).
- INT is green again since 10:18 UTC (12a823e, then 903e092). The integration train works: two promotions in 20 minutes. The SR slice end to end waits on lane/android-core 85365484 and lane/android-sr-b reaching INT, then android-sr-a wires the OTP screen and hosts Sale, Memo and Summary.
- Usage: the seven-day window is at allowed_warning (resets 2026-10-13 18:00 UTC), five-hour is allowed; critical lanes only.
- Owner moved the USB phone and every device check to 2026-10-08. Schedule risk: all device-only checks (printing, GPS and spoofing, device owner, 8-hour battery, outdoor legibility) now start on Day 4. Day 7 holds only if the owner can do them in one block tomorrow.

## Update 2026-10-07 16:50 UTC (Day 3, lead lane check)
- Rows built (time-logged on any lane head or INT): **301 of 490 BUILD rows (61.4%)**; 298 already on INT (60.8%). Was 266 / 236 at 10:50.
- INT is moving: 4fd5c4d5 at 16:33 (db afe03d1, android-core 414087e, android-sr-a 6f3b7ab). Four more candidates are in CI (infra 0cd495f, backend-core d5d7ca2e, android-core 0a0add38, others).
- **First green dev deploy: run 143 (c992c9c), 2026-10-07 16:04 to about 16:30**: what-if guard, images by digest, migrations, apps, health gate (build=c992c9c, ready 200, web /login 200), storage CORS preflight proven, recovered alert created. dblogins job still fails (non-blocking, per-app logins off); a probe on lane/infra 0cd495f will say image, secret ref or SQL.
- SR slice end to end on dev is still to be proven (login, bundle, visit, sale, memo, sync); wave 2 (android-amo, android-tso, qa) waits for it and for usage `allowed` (now `allowed_warning`).
- Recycles: android-core to session 8 (16:46). Asked to hand over and recycle: backend-core (583k), infra (599k).
- Device checks all moved to 2026-10-08 (owner has no phone for USB debugging today); Day 7 holds only if they run in one block tomorrow.
- Risk to watch: wall-clock tests (gate blocks CI 2026-10-09; RequestIsolationTest now deterministic on lane/backend-core).

## Update 2026-10-07 18:50 UTC (Day 3, lead lane check)
- Rows built: **326 of 490 BUILD rows (66.5%)**; 322 on INT (65.7%). Was 301 / 298 at 16:50. By area (built/total): Android SR 54/57, Android shared core 50/61, Backend 75/135, DB 11/18, Web dashboards 49/50, Web admin 64/64, Infra 15/16, Shared 7/7; AMO 1/40, TSO 0/22, QA 0/20 (wave 2 not started).
- INT b85d81fd (18:46): candidate zt green (android-core 1493b0f7, db 6f9b6dcf, backend-core a7e79655, infra b8a83d87, lead-docs). The whole SR slice code (login, bundle, visit, sale, memo, sync) is on INT.
- Dev deploys: 143 and 145 green, 144 failed (Maven Central 429, fixed in 5f37940), 146 green; 147 (f8ba8a16) running. dblogins cause named (7 KB env value; file mount in b8a83d87). Infra is building an API-level slice smoke test with a dev seed user (device protections stay ON; only a smoke-scoped relaxation allowed); its first green run is the gate for wave 2.
- Backend is the long pole: 60 of 135 backend rows remain (backend-core, backend-admin, backend-reports; the last two are on hold). Decision needed by 2026-10-08 evening: release backend-reports and backend-admin (acceptor for quarantined check-outs, audit writes) so Day 7 holds.
- Recycles this window: android-core 8 and 9, backend-core 6 and 7, infra 4. Integrator asked to recycle (534k).
- Risks: usage `allowed_warning` until 2026-10-13 18:00 UTC; wall-clock test gate blocks CI 2026-10-09; device checks all on 2026-10-08.

## Update 2026-10-07 20:50 UTC (Day 3, lead lane check)
- Rows built: **333 of 490 BUILD rows (68.0%)**, all 333 on INT (was 326 / 322 at 18:50). INT bde7719c at 20:20; the train moves about every 45 minutes; reds this window were all lane-owned and fixed by their lanes (core-ui module lint, CI job timeout).
- Dev: deploys 143, 145, 146, 147, 148, 149 green (144 failed on Maven 429, fixed). dblogins fixed; per-app db logins ON in the next infra head. First slice smoke (deploy 149): login, bundle, outlet and baseline tile pass; upload failed on device-key proof (enrolment ON, as ruled); infra gave the smoke device a real key (ff7579c4). Memo read and totals were server gaps, now served (BC-66); the smoke can make them hard checks.
- Server side: bundle content and surveys, sync totals, memos, digest, media SAS are on INT. 68 contract operations still have no route (backend-core handover): phone-facing ones first (sync/delta, sync/bundle/page).
- Wave 2 still waits for the slice smoke's first full green run and for usage `allowed`. Owner reply on the weekly usage % is still needed to release backend-admin and backend-reports.
- Open reds: none attributable. CI "Shared, db and backend" job: timeout raised to 45 and a split into two jobs is in a candidate (infra 11cd544b).

## Update 2026-10-07 22:50 UTC (Day 3 night, Dhaka 04:50 of Day 4; lead lane check)
- Rows built: **355 of 490 BUILD rows (72.4%)**, 353 on INT (was 345 / 344 at 21:56). Backend 93/135, SR app 56/57, shared core 52/61, DB 17/18, web 49/50 and 64/64, infra 16/16, shared 7/7; AMO 1/40, TSO 0/22, QA 0/20 (wave 2 not started).
- INT f4945a81 (22:36). Dev: deploys 150, 151, 153 green; the SR slice smoke PASSED end to end on dev (deploy 151, API level, device proof signed, enrolment ON; run on INT 0e5c3dc4). Web CI job hang (apt stall) fixed in lane/infra 2fe687b9 (now on INT); deploy 152 was skipped by it.
- Recycles this window: integrator 5, backend-core 10, db 5; android-core parked; android-sr-a finishing F-SR-020/021.
- Ruling: on dev the integrity and enrolment gates stay OFF (accepted and flagged) until the owner links the app in Play Console (owner action 13); quarantined rows cannot be released yet.
- Wave 2 and the held lanes still wait for the owner's weekly-usage reading (usage `allowed_warning`). Backend remaining: 42 rows; AMO 39, TSO 22, QA 20. Day 7 is at risk unless wave 2 starts by Day 4 midday.
- Device checks: Day 4 (2026-10-08). The owner needs a fresh laptop Claude session first (owner action 12) and the lab phone on USB.
- 2026-10-08 00:50 UTC: 357/490 rows built = 72.9% (all 357 on INT 2b76764f). SR app 56/57, shared core 52/61, backend 94/135 (41 left), db 18/18, web admin 64/64, web dashboards 49/50, infra 16/16, contract 7/7; AMO 1/40, TSO 0/22, QA 0/20.
- Enrolment on dev is fixed in code (android-core 1d48cddb, infra release-seed b3cd9bb4 and smoke-device enrolment 2a97ca75, all on INT); the SR slice smoke should be green after the deploy of 2b76764f (infra confirming). Real-phone login still needs the owner's call: dev-only require_enrolled=false (path A) or enrol by token with the release-signed APK (path B). Wave 2 stays held until usage reads `allowed`; Day 7 remains at risk unless it starts today.
- 2026-10-08 06:50 UTC: 363/490 rows built = 74.1%. Backend 100/135, SR app 56/57, shared core 52/61, dashboards 49/50, db/web-admin/infra/contract 100%; AMO 1/40, TSO 0/22, QA 0/20. backend-core, db, infra, android-core, android-sr-a/b, android-core-ui, android-print have NO unblocked rows left. The remaining 127 rows are almost all behind the hold: wave 2 (81 rows, about 254 size-hours) and backend-admin/backend-reports (about 35 rows) plus 9 shared-core rows. Day 7 (2026-10-11) is lost unless the owner's usage reading releases them by Day 4 evening; the Day 10 cap (2026-10-14) holds if they start by 2026-10-09 morning with three parallel Android lanes.

## Reforecast 2026-10-15 06:20 UTC (Day 11): restart after the stall
Facts: INT did not move from 2026-10-08 08:37 to 2026-10-15 (about 165 hours); Day 7 and the Day 10 hard cap were missed. Cause: the usage hold on seven lanes (about 116 rows) plus owner-only device steps; the owner returned on 2026-10-15 and started the build again. Final Azure account: still not available; everything stays on the test account at pilot size (docs/28).
Proposed target (needs the owner's yes): **feature-complete on dev by 2026-10-20 (Day 16), buffer to 2026-10-22**. Basis: the released web and admin lanes have about 3 to 4 pessimistic lane-days left (forecast of 2026-10-07: web-config 3.4, backend-admin 3.2, web-dashboard 3.0, backend-reports 2.5, web-admin 1.8, now smaller); wave 2 (android-amo 2.9, android-tso 1.6, qa 1.6) starts after the SR slice runs on a real phone (owner test, 2026-10-15) and runs in parallel; integration and fix cycles and the owner's device checks (printer, outdoor, device owner, 8-hour battery) are the schedule risk. Holds only if lanes run continuously and usage stays `allowed`; a five-hour limit stop of a few hours moves it by a day at most, a weekly limit stop does not fit.
The 8,500-user proof needs the final account and stays DESIGNED, not proven (docs/28, docs/33).
- 2026-10-15 12:47 UTC: INT 8eb43660 has not moved since 10:52 UTC (about 1h55m): candidates zzn and zzq failed on lane-side contract/slice defects (duplicate 404/409 keys, stale slices), all fixed by backend-admin; zzs in CI, zzt queued. Checked heads waiting: backend-core 025e0ed, backend-admin 81b754e9, db 20040eb7. Built since restart: dues adjustment, SKU image, data-entry endpoint, MEMO_DUPLICATE_SUSPECT, quarantine accept, backfill-match helper, sync money cap. Target unchanged (feature-complete on dev 2026-10-20) and still needs the owner's yes.
- 2026-10-15 14:47 UTC: INT 6e45b770 (zzc) after a 2 h stall caused by lane-side contract/slice defects and CI runner contention (5 parallel runs); the integrator now builds one combined candidate per round. Landed on INT today: dues adjustments (API + web), SKU image (API + web), paper data entry (API + web, flag off), backfill match (ingest + API + web), MEMO_DUPLICATE_SUSPECT, quarantine accept, sync money cap, dw entered_by_user_id, AMO zone bundle, pre-generation worker. Parked: bundle serve-snapshot worker (needs an infra container), dw.fact_supervisor_day (wave 2). Target unchanged and unconfirmed by the owner (feature-complete on dev 2026-10-20). The phone test on the Honor X5c Plus is still the gate for wave 2.
- 2026-10-17 22:47 UTC: wave 2 running overnight on the owner's word (hardening held). INT e50d3065: android-amo 917773e7 (purged-UUID tombstones, Room v7), web-dashboard 8a406e90 and qa f5976bf6 promoted tonight; candidate with android-tso afbd26f6 in CI; qa 5b8b1826 checked and next; lead-contract's merge (ApiWorld.kt overlap with qa) in progress; lead-backend working the request list (jackson pin, bundle rubrics, route names). Target unchanged (needs the owner's yes): feature-complete on dev 2026-10-20, buffer 2026-10-22. Risks: lane contexts above 450k (integrator, lead-contract, android-amo) need recycling tonight; android-tso's Android job was red for ~45 min on HardcodedStringScanTest (lane ran only its module tests), fixed in afbd26f6.
- 2026-10-18 01:15 UTC: INT 0a228647 (gitleaks ignore line + web-dashboard be9b078; run 1037 in CI). Landed overnight: web dashboard re-laid to the owner's screenshot with keyboard/axe fixes, android-tso (SR parity, dashboard block, QC-only), contract conformance 55 to 0 (waits for its promotion), purged-UUID tombstones and Room v7 (amo), streamed outlet ingest (heap 157 MB to 9 MB at zone scale, waits). Queue behind INT: lead-contract e5ffa87b, android-amo 5d064824, qa d8d638ca, lead-backend (replay-first, config scope, TSO bundle with AMO routes; needs a regenerated openapi.d.ts first). Incidents tonight, all fixed: android-tso Android job red on HardcodedStringScanTest (lane ran only its module tests), Web e2e filter-popover hydration race, whole-history gitleaks finding on a test placeholder blocking every lane run (reviewed ignore line), stale openapi.d.ts on lead-backend. Target unchanged and still needs the owner's yes: feature-complete on dev 2026-10-20, buffer 2026-10-22. Still held: hardening (N-059 PITR generation mint is only on the held branch).
- 2026-10-18 02:47 UTC: INT 54ff64f2 (android-tso APK trim + SR parity batch 3 + QC-only, web-dashboard BigInt money / Western grouping / SUPPORT scope / MFA re-sign-in) on top of 5650b43b (contract conformance 55 to 0, AMO streamed ingest and Task assign). In CI behind it: lead-backend merge head f5342ad (replay-first sweep, TSO bundle with AMO routes), then its batch 2 (QA-MNY-01, paper backfill, KD-1/KD-2), qa money gate + authz audit (ratchets need two deletions), backend-authz (SUPPORT, config scope, MFA per request). Target unchanged and still needs the owner's yes: feature-complete on dev 2026-10-20, buffer 2026-10-22. Owner items: MQ-03, E4, target, Honor test, hardening, grouping (Western chosen per F-SYS-051), D-SIZE-01 device smoke.
- 2026-10-18 05:47 UTC: INT 7d935aef; the next promotion (amolb: qa authz audit + android-amo + lead-backend batch 2 with the replay sweep, QA-MNY-01, requireWriter) is in CI. Next in line: android-amo 3858a720, web-dashboard SUPPORT pages, qa's date-bomb/AZ-7 head, then backend-authz (SUPPORT/config scope, per-request MFA, finance.backdate at approval), then its follow-ups (calendar allowlist, C3 when loosened, listAudit). New decision D-MIG-RANGE-01: lane migrations take V0084+, hardening renumbers when promoted. Seven-day allowance is at warning level (resets 2026-10-20 18:00 UTC), so long-context lanes are being recycled. Target unchanged and still needs the owner's yes: feature-complete on dev 2026-10-20, buffer 2026-10-22.
- 2026-10-18 08:47 UTC: INT a8e5be48. On INT since 05:56 UTC: qa authz audit/money gates (86 findings baseline), lead-backend batch 2 (replay sweep, QA-MNY-01, requireWriter, PDA age limit V0084, seed date fix), android-amo (Sales Submit nine rows display, Report tile from route-memo/route-std, rubric pin), android-tso (item 8 bundle outlets/zone names/SR names, Final Submit five-level cascade), web-dashboard (SUPPORT scoping, audit/calendar kept reachable, APK hint). Next: backend-authz 31ede218 (authz/config scope/MFA per request/backdate at approval, audit 0 findings), then its follow-ups (calendar allowlist, C3 when loosened or windowed, listAudit with default-deny redaction, editor_permission check), qa's last push (CFG_SWITCH, oracle D-AUTHZ-Q2), web-dashboard calendar/audit switch after backend lands. Left for devices (owner): Honor walkthrough SR/TSO/AMO (docs/status/qa-device-lab-honor.md), D-SIZE-01. Left for owner decisions: MQ-03, E4, M8, A1, target date, hardening. Seven-day allowance at warning level (resets 2026-10-20 18:00 UTC); long-context lanes recycled. Target unchanged and still needs the owner's yes: feature-complete on dev 2026-10-20, buffer 2026-10-22.
- 2026-10-18 12:47 UTC: INT 184ea98f. On INT since 08:47 UTC: backend-authz (per-request MFA, SUPPORT/config scope, calendar allowlist), qa 638468fe (oracles, date-bomb), web-dashboard (SUPPORT calendar resolve). In CI: backend-authz Q3/Q4 option B (C3 when loosened or windowed, no migration) with web-dashboard dashboard polish on top. Next: backend-authz listAudit (default-deny masking), editor_permission enforcement (V0085), DMO/WM enrolment-token read, PII reveal (V0086 if needed); one small additive backend field for the validated-visit count (web request filed by web-dashboard). Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes); weekly allowance at warning level until 2026-10-20 18:00 UTC, so no new sessions beyond backend-authz #2.
- 2026-10-18 14:47 UTC: INT dee3d5a9. On INT since 12:47 UTC: backend-authz authz-c3 (C3 when loosened or windowed), listAudit (default-deny masking, MFA per request), web-dashboard polish (team map empty note, SUPPORT calendar). Next: backend-authz geo_valid_visits (CI due 15:22), DMO/WM enrolment-token read, editor_permission (V0085), PII reveal (V0086 if needed, with the audit-image redaction proposal D-AUDIT-PII-01); web-dashboard switches the audit page on once listAudit is deployed. Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes); allowance at warning level until 2026-10-20 18:00 UTC.
- 2026-10-18 16:47 UTC: INT 7401ef14. On INT since 14:47 UTC: web-dashboard audit viewer on listAudit (4 roles), backend-authz geo_valid_visits (validated-visit count for the dashboard, no migration). In CI: editor_permission with V0085 (candidate authzperm). Next: DMO/WM enrolment-token read (CI due 17:09), web-dashboard wires "N of M validated", then PII reveal (V0086 if needed, with the D-AUDIT-PII-01 redaction proposal). Role-matrix backend work (listAudit, editor_permission, token read) is nearly complete; remaining owner-facing items are unchanged (target date yes, Honor upgrade + release smoke, MQ-03, E4, M8, hardening word). Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes); allowance at warning level until 2026-10-20 18:00 UTC.
- 2026-10-18 18:47 UTC: INT 26db9b0b. On INT since 16:47 UTC: DMO/WM enrolment-token read, editor_permission (V0085, applied on dev), web-dashboard validated-visit count, BundlePreGenerationTest race fix. In CI: web-dashboard design leftovers (checked, held for one candidate with PII reveal). Next: backend-authz PII reveal (V0086, re-auth and masked reads, with the D-AUDIT-PII-01 redaction proposal), then web-dashboard re-auth UX and per-role e2e; AMO/TSO device checks wait for a phone. Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes); owner items unchanged (Honor upgrade and release smoke, MQ-03, E4, M8/A1, row F, "promote hardening" when ready).
- 2026-10-18 20:47 UTC: INT c91d9cc6. On INT since 18:47 UTC: web-dashboard design leftovers and self-review (card-by-card match to the owner's reference; intentional differences: share-of-sales rings, no Target/Astha/Superstar, Calm Glass shell). In CI: backend-authz PII reveal (run 1160, V0086 re-auth, pii_until, audited unmask), then web-dashboard re-auth UX with per-role e2e and the D-AUDIT-PII-01 ruling (write-time redaction for new audit rows only). Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes); owner items unchanged (Honor upgrade and release smoke, MQ-03, E4, M8/A1, row F, "promote hardening" when ready).
- 2026-10-18 23:40 UTC: INT 0a8523b0. On INT since 20:47 UTC: PII reveal backend and web UX, audit write-time redaction, ui-amo Home and Settings. Owner directive 22:10 UTC: apps and web must look like the existing Aron; seven UI parity lanes working overnight; one batched INT push per deploy cycle. Target for the morning: signed dev APK with SR (Home, Attendance, Stock, Sale, Summary, Submit, Outlet, Memo, Tasks, Auth, Settings), AMO and packshot support on INT, TSO and web shell/dashboard as soon as their runs are green. Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes).
- 2026-10-19 02:50 UTC: INT 9f1b58d7. On INT since 00:47 UTC: UI parity kit window (all lanes re-recorded), SR Home/Stock/Sale/Attendance/Summary/Submit/Outlet/Tasks on the Aron look, AMO and TSO homes and forms on the kit, web dashboard shell and cards, dev pack pictures (dev-only). Next: batch 6 (ui-sr-b, ui-tso), batch 7 (pack-picture server path), second vision QA, owner screenshots for AMO/TSO/web. Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes).
- 2026-10-19 04:46 UTC: INT 0675990b. On INT since 02:50 UTC: batches 6 and 7 (SR Memo/Summary/Submit/Outlet/Tasks/Auth on the kit, TSO screens, pack-picture server path with Room 7 to 8), morning report and QA round 2. Latest signed dev APK aron-release-signed-dev-1255 (CI #1255 and dev deploy #228 green). Waiting on the owner: AMO/TSO/web screenshots, phone check of 1255 (pack pictures), compact-scale decision. No new UI wave until then; polish backlog is the 93-item round-2 list. Owner target unchanged (feature-complete on dev 2026-10-20, buffer 2026-10-22, still awaiting the owner's yes).

## 2026-10-22 10:30 UTC — after the owner directive (docs/34), wave 4

The owner's original dates (feature-complete on dev 2026-10-20, buffer 2026-10-22) are superseded by the
2026-10-22 directive, which adds the seven portal levels, the national dashboard screen for screen, the
field counting units, the printer fix, the Gulshan/Banani seed, the inline Sale map, MDM and the reports.
Honest per-item dates (test account, dev):

| Item | State 10:30 UTC | Expected on dev |
|---|---|---|
| Inline Sale map and distance (SR, AMO out-of-range) | on INT (batch 1), artifact aron-release-signed-dev-1282 | today; owner phone check pending (needs the Maps key in the build) |
| Portal users/grants/brand pages | on INT (web); backend-authz API in progress | API today; end to end once db's role tables land (today) |
| Counting units (packets, boxes, dozens) in the apps | lane head bd042729, CI running | next signed build today; pack sizes from the server after db's migration and backend-admin's SKU fields |
| Printer MP-58N | diagnostics + fixes built, awaiting a green lane CI | signed build today; root cause needs the owner's export (owner-actions row M) |
| National dashboard (web) | built on the real contract, lane CI red to fix | web today; live numbers once db's summary tables and backend-reports land (today/tomorrow morning) |
| Dashboard backend (summaries, 14 endpoints) | code at ec0976ed, CI red to fix, blocked on db DDL | today evening to 2026-10-23 morning |
| Route targets, calendar, house de-dup, outlet channel, SKU pack sizes (admin API + pages) | API at 1be69cc6, pages on lane/web-admin | today |
| MDM section | first candidate 3e3a76e5 (behind a flag) | data layer and plain pages today; the look after the owner's screenshots |
| Gulshan/Banani seed | queued after db's migrations | 2026-10-23 (dev re-seed via CI) |
| Data-flow trace (qa) | not started | 2026-10-23 after the seed and summaries |
| Reports (100% from the explanation docs) | blocked on the owner's uploads (row J) | 2 days after the uploads arrive |

Risk: ten lanes push many short-lived heads; red lane runs are now gated (a candidate is declared only on a
green run of the exact sha). Usage stays `allowed` on the five-hour window.

## Update 2026-10-22 14:55 UTC (wave 4, lead lane check): honest date per directive item (docs/34)
- On dev now (INT 2c10a157, deploy of batch 6 pending): portal user levels and token claims (backend-authz 8ed44597), admin APIs for route targets, calendar, house de-dup, outlet import, user access and SKU pack fields (backend-admin 98651d47), web-admin users/grants, target upload, calendar, house, channel and pack preview (d0b92230, c60ad04b, d43e58f7), inline Sale map SR + AMO (android-map), MP-58N diagnostics (android-print, build 1331), packets / boxes / dozens in the apps (android-units, build 1346).
- Today, 2026-10-22, before 18:00 UTC: db V0087 to V0106 plus the Gulshan-Banani seed (a60d941e in CI; lands with the gate fix after batch 6 deploys; the dev deploy then runs 20 migrations), backend-reports dashboard contract 1.5.0 and summaries (re-merge then one CI cycle), mdm portal section + dpc command channel (candidate after the gate fix), backend-authz checker rounds and the two auth test fixes.
- 2026-10-23: national dashboard screen for screen on dev (web-dashboard, held behind backend-reports; Linux goldens to record), web-admin outlet-import and user-access UI (#5), AMO/TSO entry-unit aggregates (backend-reports, then android-units restart), qa data-flow trace (Gulshan-Banani) once db + backend-reports + units are on INT, backend-admin follow-ups (#6).
- Owner-dependent, date open: printer fix (needs the diagnostics export from build 1331), reports (needs the report docs upload), MDM look (needs the Aron MDM screenshots), packshots (loader or admin upload), Q1-Q4 answers, `promote hardening`. Nothing in the directive is blocked by lanes; these four wait on uploads or a decision.
- Risk: five sessions are past the 450k recycle line (web-admin, backend-admin, mdm, android-units, android-print); each is being handed over at its next natural stop, which costs about 20 minutes per lane; the integrator is handed over after batch 6. Rate limits: five_hour allowed on every session.

## Update 2026-10-22 17:25 UTC (wave 4, lead): one cycle lost to the OSV incident
- The Next.js advisories cost one CI+deploy cycle (about 75 minutes): train 8's deploy was skipped and train 8.1 (next 16.4.2 + two test fixes) is deploying now. Everything that was due "today before 18:00" moves to about 20:00 UTC: backend-reports 1.5.0 dashboard contract and summaries (train 9 or 10), backend-authz module grants + Opus rounds (the other train), web-admin's house/working-days/SKU-pack UI (train 9), db N3 + seed 07 (its own train). mdm 1.6.0 is on INT (train 8, redeployed with 8.1).
- 2026-10-23 unchanged: national dashboard on dev (web-dashboard #8 after 1.5.0), web-admin outlet-import and user-access UI, android-units AMO/TSO aggregates (server slice done by backend-reports), qa data-flow trace, backend-admin follow-ups. Owner-dependent items unchanged.

## Update 2026-10-22 18:20 UTC (wave 4, lead)
- Train 9 (web-admin house / working-days / SKU-pack UI and the Users+Credentials warning; backend-reports 1.5.0 dashboard contract, national summaries, active-house filter; db V0107 + seed 07) pushes when its two remaining lane runs are green, about 18:45 UTC, on dev about 19:40 UTC. Train 10 (backend-authz module grants, Me.modules) on dev about 20:30 UTC. Then web-dashboard #8 starts (national dashboard on dev 2026-10-23 morning), backend-admin #6 and qa's data-flow trace (2026-10-23).
- Owner-dependent items unchanged (printer export from build 1331, report docs, MDM screenshots, Q1-Q4, packshots, `promote hardening`).

## Update 2026-10-22 19:20 UTC (wave 4, lead)
- Train 9 (web-admin UI, backend-reports 1.5.0 dashboard contract and national summaries, db V0107 + seed 07) is in INT CI since 18:37; dev deploy about 19:35 UTC. Train 10 (backend-authz module grants, brand and house scope) about 20:30 UTC; train 11 (mdm: token-based MDM grants, breadcrumb install, sampling seam; plus db V0108 checked_in_at and backend-reports F-DASH-010) about 21:30 UTC.
- Tonight after train 9's deploy: web-dashboard #8 and qa (Gulshan-Banani data-flow trace) start. 2026-10-23 unchanged: national dashboard on dev, web-admin outlet-import and user-access UI, android-units AMO/TSO aggregates, backend-admin #6 follow-ups (after train 10). Owner-dependent items unchanged.

## Update 2026-10-22 20:20 UTC (wave 4, lead): one more cycle lost to a CI job timeout
- Train 9 content (web-admin UI, backend-reports 1.5.0 national summaries, db V0107 + seed 07) reaches dev with train 9.1's deploy at about 20:30 UTC (CI #1403 green 20:14; the first run was cancelled by the 45-minute job limit, raised to 75). Train 10 (backend-authz module grants, brand and house scope; db V0108) on dev about 22:10 UTC. Train 11 (mdm token grants + breadcrumb install, or backend-reports F-DASH-010, whichever is green first) about 23:30 UTC; the other on 2026-10-23 morning.
- web-dashboard #8 and qa start right after train 9.1's deploy report (tonight); national dashboard on dev and the data-flow trace stay 2026-10-23 morning. Owner-dependent items unchanged, plus the two new rows O and P in owner-actions.

## Update 2026-10-22 20:50 UTC (wave 4, lead)
- Train 9 content is on dev (20:27 UTC). Train 10 (backend-authz module grants, brand and house scope; db V0108) pushed about 20:55, on dev about 21:50 UTC. Train 11 (mdm token grants + breadcrumb install, or backend-reports F-DASH-010; db smoke fallback) about 23:00 UTC; the other re-merges and lands 2026-10-23 morning with web-dashboard #8's first candidate and qa's findings.

## Update 2026-10-22 22:15 UTC (wave 4, lead)
- Train 11 (web-dashboard national cards on 1.5.0, mdm token grants + breadcrumb install, db smoke fallback) on dev about 23:10 UTC. Train 12 (backend-admin module-list fix, backend-reports dashboard unit fixes, web-admin menu per module) about 00:30 UTC 2026-10-23. Train 13 (web-dashboard no-access note, qa trace test, backend-core sync fixes, db V0109) about 01:45 UTC. Morning report to the owner about 06:30 UTC with what is on dev and the owner-dependent rows J-P.

## Update 2026-10-22 22:40 UTC (wave 4, lead)
- Unchanged: train 11 on dev about 23:10 UTC; train 12 (backend-admin fixes, backend-reports unit fixes, web-admin menu per module, mdm status) about 00:30 UTC 2026-10-23; train 13 (backend-authz round 3, qa trace test, web-dashboard long-Bangla pass, backend-core sync fixes, db V0109 last) about 01:45 UTC, each after a re-merge of train 12. Morning report to the owner about 06:30 UTC.

## Update 2026-10-22 22:50 UTC (wave 4, lead): dates hold
- Train 11 on dev about 23:15 UTC (INT CI slowed by seven concurrent lane runs). Train 12 about 00:30 UTC, train 13 about 01:45 UTC 2026-10-23 (backend-core's sync fixes join 13 only if its run is green by then, else 14 in the morning). backend-reports' V0109 fill and db's V0110 follow in the morning trains. Owner report about 06:30 UTC.

## Update 2026-10-22 23:55 UTC (wave 4, lead): trains 11 and 12 on dev
- Train 12 reached dev at 23:43 UTC (45 minutes earlier than forecast). Train 13 (backend-authz round 3, qa trace re-merge, web-dashboard long-Bangla pass and mock split, web-admin mock split, backend-core sync fixes, db V0109 last) about 01:45 UTC 2026-10-23, depending on six lane runs sharing the runners. backend-reports' V0109 fill and db's V0110 in the morning trains. Owner report about 06:30 UTC.

## Update 2026-10-23 00:50 UTC (wave 4, lead)
- Train 13 pushes about 01:20 UTC with seven heads if the authz and qa runs are green by then (else five, the rest in 14); CI about 02:15, dev about 02:30. Train 14 (web-admin mock split, any head that missed 13) about 03:45 on dev. backend-reports' V0109 fill: morning. Owner report about 06:30 UTC.

## Update 2026-10-23 01:25 UTC (wave 4, lead)
- Train 13 (seven heads incl. backend-authz round 3, qa trace, backend-core sync fixes, web-dashboard pass, db V0109) pushed 01:20; dev about 02:30 UTC. Train 14 (web-admin mock split, backend-reports' V0109 fill if ready) about 04:00 on dev. Owner report about 06:30 UTC.

## Update 2026-10-23 01:58 UTC (wave 4, lead): train 13 red, fix in flight
- Train 13 is on INT but undeployed (one qa test encoded the pre-ruling preview semantics). Train 13.1 (qa's test fix) pushes about 02:50 UTC; dev about 04:00. Train 14 (web-admin mock split, backend-reports' V0109 fill) about 05:30 on dev. Owner report about 06:30 UTC.

## Update 2026-10-23 02:50 UTC (wave 4, lead)
- Train 13.1 (train 13 + qa's test fix) pushing now; dev about 03:50 UTC. Train 14 (web-admin mock split, backend-reports' V0109 fill) about 05:30 on dev. Owner report about 06:30 UTC.

## Update 2026-10-23 03:30 UTC (wave 4, lead): train 13 on dev
- Train 13 content reached dev at 03:15 UTC via 13.1. Train 14 (web-admin mock split, backend-reports' V0109 fill if its run is green by then, five status handovers) pushes about 05:00, dev about 06:00. db V0110 and the contract 1.7 batch follow in the morning sessions. Owner report about 06:30 UTC.

## Update 2026-10-23 04:40 UTC (lead sweep)
- Train 14 (016427a2) on dev 04:34. Train 15 (qa Sunday/Monday trace, web-admin read-only gating, backend-reports login/void + no-write of sold_qty_base, lead docs) pushes once the three code heads are checked, about 06:15; dev about 07:30. Train 16 (db V0110 last, qa slice 2) dev about 09:30. Owner morning report 06:30. No slip against the wave-4 plan; the two-train expand/contract for V0110 is by design.

## Update 2026-10-23 06:00 UTC (lead sweep)
- Train 15 (02a23471) pushed 05:55; CI #1455 about 06:25, deploy about 06:40, dev about 06:45. Train 16 (qa slices 2+3, web-admin states audit, backend-reports handover, lead docs, db V0110 last) forms after that: push about 08:00, dev about 09:15. Owner morning report posts at 06:30 with train 15's CI state.

## Update 2026-10-23 06:45 UTC (lead sweep)
- Train 15 on dev 06:40 (no slip). Train 16 forms now (qa slices 2+3, web-admin states audit, backend-reports handover, lead docs, db V0110 last): push about 08:00, CI about 08:45, dev about 09:15. Then the contract 1.7 batch (four rows) with new backend-authz and backend-core sessions, and the stale-branch cleanup. Owner-owed items unchanged (rows J to Q, H, I, 1).

## Update 2026-10-23 08:30 UTC (lead sweep)
- Train 16 on dev 08:20 (no slip; db's V0110 moved to train 17 after a container reboot in its local run, by the lead's decision at 07:55). Train 17 (qa BRAND, web-admin handover, lead docs, db V0110 last): GO about 09:15, dev about 10:30. Then contract 1.7 batch and branch cleanup. Evening report about 18:30 UTC.

## Update 2026-10-23 10:50 UTC (lead lane check)
- Train 17 on dev 10:17 (no slip). Train 18 (qa cover/holiday/parking slice, lead contract 1.7.0 batch, db docs, lead docs): the batch pushes once its local backend suites finish (about 11:00), its CI runs about 40 minutes (contract, web and Android jobs all run), GO about 11:50, dev about 12:50. Then backend-authz #8, backend-core #15, mdm #2, web-admin #7 and an android-tso session wire rows 1 to 4 (small rows; candidates through the afternoon; train 19 about 16:00). Evening report 18:30.

## Update 2026-10-23 13:00 UTC (lead)
- Train 18 on dev 12:46 (no slip; contract 1.7.0 live). Wiring heads (backend-authz #8 rows 1–2, backend-core #15 row 4, mdm #2 row 3, web-admin #7, android-tso #3) are small: candidates expected 14:30–17:00; train 19 forms when two or more are checked, push about 17:00, dev about 18:00; a second wiring train tomorrow morning if any candidate slips. Integrator 19 is created at integrator 18's READY TO RECYCLE (about train 19 or 20). Evening report 18:30.

## Update 2026-10-23 15:32 UTC (lead)
- Train 19 on dev 15:31 (no slip; the five contract-1.7 wiring rows are live). Train 20 (web-admin AMO widening, android-tso parity slices, docs-only handovers, lead docs): GO about 17:30, dev about 18:30. Evening report 18:30. Tomorrow: parity slices (android-tso), qa #3 on request, backend-admin #7 / backend-reports #6 on request or the owner's docs, v1.8 batch when rows accumulate.

## Update 2026-10-23 16:35 UTC (lead)
- Train 20 on dev 16:33 (no slip). Train 21 (android-tso SR parity slices): today only if a slice is checked by about 17:30 (dev about 18:30), otherwise first train tomorrow. Evening report 18:30. Tomorrow: android-tso parity slices, qa #3 on request (dev-side trace rerun still needs a workflow job carrying the smoke device key: lead/infra item), backend-admin #7 and backend-reports #6 on request or the owner's report docs, contract v1.8 batch when rows accumulate.

## Update 2026-10-23 16:48 UTC (lead)
- Train 21 (android-tso parity slice 2) in CI since 16:39; dev about 17:40. Train 22 (android-tso slice 3 or 3+4): today only if checked by about 17:45 after train 21 lands, otherwise first train tomorrow. Evening report 18:30. No slip.

## Update 2026-10-23 16:52 UTC (lead)
- Train 21 landed 16:47 (signed dev APK 1488; dev API unchanged by design). Train 22 decision 17:35–17:45. Evening report 18:30. No slip.

## Update 2026-10-23 17:30 UTC (lead)
- Train 22 landed 17:25 (signed dev APK 1492; dev API unchanged by design). No train 23 today. Evening report 18:30. Tomorrow: train 23 (android-tso slice 5 spelling + any checked head) first thing; android-tso continues the parity audit rows that need no owner answer; qa #3 on request; backend-admin #7 / backend-reports #6 on request or the owner's report docs; v1.8 batch when rows accumulate. No slip.

## Update 2026-10-23 18:48 UTC (lead)
- Day closed with nine trains, no slip. Tomorrow 06:10 UTC: morning sweep, GO train 23 (android-tso 92e0667c + lead docs), morning report 06:30. android-tso #3 hands over to #4 after its next one or two parity slices (context ~377k).

## Update 2026-10-24 07:08 UTC (lead)
- Train 24 (zod hotfix) on dev 07:05. Train 25 (print-crash fix, signed APK) GO 07:08, CI green about 07:30. Train 26 (android-tso zero-price slice + handover) after that; android-tso #4 then takes the print call-site guards. Evening report 18:30. No slip; the owner's P0 build is about 30 minutes later than the 07:30 said in the morning report because of the advisory.

## Update 2026-10-24 08:49 UTC (lead)
- Train 27 (backend-core smoke/zero-price server rule + android-tso print guards/X3/O5 + lead docs) pushed 08:34; CI, then the deploy with the slice smoke, expected green by about 09:25 (signed APK 1515). Train 28 (android-tso docs + feature-outlet controls, lead docs) about 10:30. Contract 1.8 batch head today (lead sub-lane), checked and then a train of its own; db V0111 dw.v_memo_flags rides the next train after it is green; backend-core's flagged-memos endpoint and web-admin's page follow the batch. Evening report 18:30. No slip.

## Update 2026-10-24 10:01 UTC (lead)
- Train 27 landed 09:05 (deploy green, slice smoke PASSED, signed APK 1515 = owner's device-check build). Train 28 (android-tso parity slices, backend-core zero-price tests, lead docs, db V0111 dw.v_memo_flags) GO about 10:15, landed about 11:05 (deploy with migration). Train 29 = contract 1.8 batch after lead checks and an Opus review, about 12:00; then backend-core's flagged-memos endpoint and web-admin's page (trains 30+ this afternoon). Evening report 18:30. No slip.

## Update 2026-10-24 10:48 UTC (lead)
- Train 28 pushed 10:11; CI (full db chain) about 11:00, deploy with V0111 + smoke about 11:20 (signed APK 1528). Train 29 = contract 1.8 batch (committed, report + CI + Opus review pending) about 12:00–12:30. Then backend-core's flagged-memos endpoint and web-admin's page (trains 30+ this afternoon). Integrator 19 successor after train 29. Evening report 18:30. No slip.

## Update 2026-10-24 11:06 UTC (lead)
- Train 28 landed 11:05 (deploy green, smoke PASSED, signed APK 1528). Train 29 = contract 1.8 batch: the sub-lane's report, lane CI and an Opus review first, GO about 12:00, landed about 12:45. Then backend-core's flagged-memos endpoint (train 30/31) and web-admin's page (train 31/32) this afternoon; android-tso's cleanup slice rides train 30. Evening report 18:30. No slip.

## Update 2026-10-24 12:49 UTC (lead)
- Train 29 (contract 1.8) pushed 12:28; CI about 12:55, deploy + smoke about 13:10. Then "contract 1.8 landed": backend-core's flagged-memos endpoint slice (train 31, about 15:00) and web-admin's Flagged memos page (train 31/32). Train 30 (android-tso state coverage, lead docs, db V0112/V0113) GO right after train 29 lands, about 13:15, landed about 14:05. Recycles this afternoon: integrator 19 → 20 before train 30; android-tso #5 → #6 at 450k; db #10 hands over after train 30. Evening report 18:30. No slip.
