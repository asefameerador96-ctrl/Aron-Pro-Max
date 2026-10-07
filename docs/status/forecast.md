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
