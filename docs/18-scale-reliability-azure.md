# 18 — Scale, Reliability and Azure Architecture

> **What this doc decides.** (1) The load model is re-based on the Apsis data profile, not on docs/01: 4.5 lakh successful calls and 5 lakh visits a day, 3.0 M business rows a day, 8,000 rows per second proved on the fleet SKU, with every storm derived in s1 (D-125, D-245, D-246).
> (2) The Azure topology and SKU ladder for the pilot, wave 1 and the full fleet: Front Door, zone-redundant Container Apps with a separate `auth` app, PostgreSQL Flexible Server with built-in PgBouncer, Managed Redis, Blob, a warm DR region, with list prices fetched on 2026-10-04 (s2, s3; D-04, D-06, D-07, D-410 to D-426).
> (3) Ingest and aggregation capacity: synchronous ingest with a fast and a slow path, a transactional outbox, aggregates recomputed by dirty key with a write guard, pre-generated bundles, three-layer rate limiting and a job DAG with deadlines (s4; D-61, D-62, D-129, D-411 to D-418, D-429).
> (4) Reliability: 35 named failure modes, herd and token-expiry control, DR and PITR with the server-generation re-sync, a rollback for every change type, SLOs measured at the edge, and the sync-health spec (s5, s6; D-63, D-127, D-130, D-135, D-419 to D-428).
> (5) The launch-day operating model and its proof: war room, wave timeline, numeric rollback triggers, runbook index, load scenarios S1 to S10 with pass criteria mapped to sub-milestones, and 22 owned gaps resolved (s7 to s10; D-126, D-133, D-134, D-148).

Scope. This document owns the numbers behind R3 SCALE and the operations side of R5. It consumes the wire contract and device behaviour of doc 17, the table and dw design of doc 16, the config registry of doc 19, the gate register of doc 20 and the auth and PII design of doc 21; it states what it needs from them. It does not restate docs/01 to 13 or docs/22. Markers: ASSUMPTION (our call, with the reason), "unknown; confirm with the business" (only AKTCL can answer; the default is given), PARITY, IMPROVEMENT, DELIBERATE CHANGE. Money is milli-taka in the platform; this document uses USD list prices only for Azure cost and says so.

Sources of Azure facts. Every limit relied on is cited as a Microsoft Learn URL in the table of s2.10. Prices were fetched on 2026-10-04 from the public Azure Retail Prices API (https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices) for region southeastasia and are list prices, pay-as-you-go, 730 hours a month. A number marked "unverified" was not found on a Learn page or in the price API and must be checked before a budget is signed.

Local ids. FM-nn is a failure mode (s5.1), ST-nn is a load event (s1.4), RB-nn is a runbook (s7.6), SH-nn is a sync-health tile (s6.4). They are local to this document; doc 14 may reference them from risk rows RK-nn.

## 1 Load model

### 1.1 Inputs, design point and reconciliation notes

The design point is D-125. "Measured" is docs/22; "design" is what is sized and tested.

| Input | Measured (docs/22) | Design value | Note |
| --- | --- | --- | --- |
| SRs, AMOs, TSOs | 8,500 SRs; 1,051 zones; 291 territories | 8,500 + 1,051 + 291 = 9,842 app users | ASSUMPTION: one AMO per zone, one TSO per territory (docs/07, docs/08) |
| Web and dashboard audience | none measured | NOT 130: HQ, admin, support, finance about 80; DMO 50, WM 10, top about 10; TSO 291 and AMO 1,051 with web logins (D-185, D-207) PLUS the Live Dashboard and Team Performance of the AMO and TSO apps; about 1,500 concurrent readers at 17:00 to 19:00 (s1.3b, D-575) | ASSUMPTION; docs/01 hierarchy; the first model counted the HQ audience only while S5 already tested 1,500 readers |
| Routes | 11,336; about 6,953 planned per trading day; 1.33 routes per SR | same | P-13, P-17; one SR covers several routes (D-257) |
| Visits opened a day | about 400,000 | 500,000 | includes all-zero outlet-days at 10 to 16 percent (P-05) |
| Successful calls a day | average 275,000 to 306,000; peak 346,772 on 25 Jun | 450,000 | the 1.2 lakh figure of docs/01 is a snapshot, not a day (P-01, D-245) |
| SKU lines per call | 1.95 in July; 1.26 in May; p99 4; max 40 | 2.5; memo supports 60 lines | D-246 |
| Business rows a day | about 2.2 M | 3.0 M | rows per day grew 53 percent May to July |
| Rows per SR-day | 259 (2.2 M / 8,500) to 280 | 350 | 3.0 M / 8,500 = 353 |
| Row on the wire | about 450 B raw including the outbox snapshot (doc 17 s2) | same; gzip about 10 to 1 | doc 17 quotes about 12 KB gz for 280 rows |
| Share of visits uploaded online (trickle) | not measurable from the export | 60 percent | ASSUMPTION; Bangladesh coverage is patchy in rural areas; measured by fleet telemetry in the pilot (D-138) |
| Photos a day | none: docs/22 has no photo data (the 130,000 of the skeleton was labelled "measured" in error and is an ASSUMPTION) | 150,000, 150 KB each, 17.6 per SR-day at the average route of 66 outlets; ONE source for the doc 17 scripted days (13, 17 and 30 photos for 50, 64 and 112 outlets, D-508) | D-75, D-508 |
| Calendar | Friday off; Eid break 27 to 31 May; single holidays | about 26 trading days a month | D-28; baselines are calendar-aware (s6.2) |
| Growth headroom | +53 percent rows per day in three months | full-fleet gate is 1.5 times the fleet; rows gate is 8,000 per second | D-134 |

Reconciliation notes. These are arithmetic differences between the sources; none changes the architecture.

| # | Observation | Treatment |
| --- | --- | --- |
| 1 | The component sum 47 visits + 41 memos + 82 lines + 110 others = 280 rows per SR-day gives 2.38 M rows for 8,500 SRs, but the headline is 2.2 M (259 per SR-day), 8 percent lower | The design value 350 per SR-day (3.0 M) covers both |
| 2 | Skeleton s6.1 lists 240,000 trickle batches a day at the design point; 0.6 x 500,000 visits is 300,000 | S2 runs at 21 batches per second (300,000 / 28,800 s x 2), which covers both (OI-18-01) |
| 3 | lens-scale estimated 150 M API requests a month; the derivation in s1.3 and s1.3b gives about 14.5 M | Cost table keeps 13 M (the difference is inside the rounding of the request lines); Front Door request cost is negligible |
| 4 | lens-scale estimated 6 to 20 GB a day of ops telemetry; with 0.5 M requests a day and sampling the derivation in s6.3 gives 3 to 6 GB | Cost table uses 5 GB a day for ops plus 0.5 GB a day for the sync workspace |
| 5 | docs/22 P-01 says the 1.2 lakh figure may count a snapshot; whether the export includes sales made outside the apps is unknown | Plan for 4.5 lakh; D-245 MUST-CONFIRM by 1c |

### 1.2 One SR-day, rows and bytes

| Record family | Measured per SR-day | Design per SR-day | Fleet per day (design) |
| --- | --- | --- | --- |
| visit (plus visit_close) | 47 | 59 | 500,000 |
| memo (successful calls) | 41 | 53 | 450,000 |
| memo_line | 82 | 132 | 1.125 M |
| QC, survey, DRP, stock issue, attendance, events, print jobs, zero-sale memo rows | 110 | 106 | 0.9 M |
| Total business rows | 280 | 350 | 3.0 M |

| Quantity | Value | Basis |
| --- | --- | --- |
| Raw bytes per SR-day | 350 rows x 450 B = 157 KB | doc 17 row size |
| Gzipped bytes per SR-day | about 15 KB | 10 to 1 on repetitive JSON; fleet about 128 MB a day of ingress |
| Rows in one trickle batch | about 6 (3.0 M / 500,000 visit families) | one POST per visit (D-59, D-261) |
| Catch-up batches per fully offline SR-day | 2 to 3 | 350 rows at 200 per batch; a family is never split, the largest family is about 175 rows (doc 17 D-382) |
| Photos per SR-day | 17.6 | 150,000 / 8,500; Wi-Fi first, so mobile bytes stay below the doc 17 budget (OI-18-06) |

### 1.3 Daily volumes (design)

| Stream | Per day | Derivation |
| --- | --- | --- |
| Trickle batches | 300,000 | 0.6 x 500,000 visits |
| Trickle rows | 1.8 M | 300,000 x 6 |
| Catch-up rows | 1.2 M | 3.0 M - 1.8 M |
| Catch-up batches | 6,000 | 1.2 M / 200 |
| Morning requests | 39,400 | 9,842 users x 4 (refresh or login, bundle, home, release check) |
| Home, config delta, release check, support ping, other | 20,000 | ASSUMPTION; doc 17 limits deltas to 15 a day per device and 100 KB |
| Dashboards, web and app (s1.3b) | about 117,500 | role-derived audience table of s1.3b (D-575); replaces the 62,400 of 130 users x 60 s x 8 h |
| SAS requests for photos | 15,000 | 150,000 photos / 10 per call (D-390) |
| API requests a day | about 0.50 M; 0.55 M with 10 percent retries and replays | about 14.5 M a month at 26 trading days (13 M before s1.3b); the cost and Redis lines are unchanged within rounding |
| Photo uploads direct to Blob | 150,000 | not through the API (D-75) |
| Photo bytes | 22.5 GB | 150,000 x 150 KB |

### 1.3b Dashboard and web audience from the real role counts (D-575, G-qa-111)

The request model counted about 130 web users, while S5 tested 1,500 readers and D-185 and D-207 give AMOs and TSOs web logins on top of the admin audience. The model and the test described different audiences, and the Redis and read-replica sizing followed the smaller one. The audience below is derived from the role counts and a stated refresh policy: live tiles (Login %, Submit %, Daily Tracking) refresh every 60 s only while the tab or the screen is in the foreground (a hidden tab stops, `visibilitychange`); every other page loads on demand; app screens load on open and refresh at most once per 5 minutes in the foreground.

| Audience | Users | Daily active share | Hours | Refresh | Requests a day |
| --- | --- | --- | --- | --- | --- |
| HQ, admin, support, finance, analysts (web) | 80 | 100 percent | 8 | live tiles 60 s | 38,400 |
| DMO, WM, top (web) | 70 | 80 percent | 2 | 60 s | 6,720 |
| TSO (web: 41 pages, Daily Tracking, Final Submit) | 291 | 80 percent | 3 | 60 s on two tiles, the rest on demand | 41,940 |
| TSO (app dashboard, Target Status) | 291 | 100 percent | 1.5 | on open, then at most every 5 min | 5,238 |
| AMO (app Live Dashboard, Team Performance) | 1,051 | 100 percent | 1.5 | on open, then at most every 5 min | 18,918 |
| AMO (web, light) | 1,051 | 20 percent | 0.5 | on demand | 6,300 |
| **Total** | | | | | **about 117,500** |

Peak: 1,051 AMOs + 291 TSOs + about 150 HQ and managers = about 1,490 concurrent readers at 17:00 to 19:00, which is the S5 audience (1,500); at 60 s that is 25 req/s on the replica and on the primary live tiles (1.8). The primary live-tile read is the declared exception to the replica rule (doc 16 s8.6b): `dw.agg_daily_zone` rows, about 1,051 per date, with their own capacity line of at most 50 queries/s (S5 asserts it).

### 1.4 Load events (storms)

| ST | Event | Window | Arithmetic | Rate | Design-for |
| --- | --- | --- | --- | --- | --- |
| ST-01 | Morning refresh, steady state (refresh token plus 304 or delta) | Real window 06:30 to 07:45 (stock issue at the distribution house); worst case 5 min | 9,842 sessions x 4 requests = 39,368 | 75 min: 8.7 req/s; 20 min: 33 req/s; 5 min: 131 req/s | 400 req/s (3 x the 5-minute case) |
| ST-02 | First morning of wave 1 | 60 min | 1,155 new users (1,000 SRs, about 120 AMOs, about 35 TSOs, the Q51 case): 1,155 password logins, binds, full bundles, APK 30 MB each (the R4 gate; the first draft sized 35 MB, D-508) | 1,155 x 5 / 3,600 = 1.6 req/s; APK 35 GB; Argon2id about 87 CPU-s in total | a correctness and support day, not a load day. With the doc 14 default of about 335 users: 0.5 req/s |
| ST-03 | First morning of the full fleet with no pre-bind day | 60 min, 10-minute cluster | 7,350 new + 1,150 veterans: 7,350 logins; 36,750 requests | 10.2 req/s average; 12.3 logins/s in the cluster; APK 7,350 x 30 MB = 220 GB = 49 MB/s average over 75 min | unbounded Argon2id concurrency is the failure: 30 in flight x 64 MiB = 1.9 GiB on a 2 GiB replica (FM-02) |
| ST-04 | Pre-bind day (T-1, Wi-Fi at the distribution house) | one working day | 7,350 binds / 8 h = 0.26 per second; 25 OTP pre-issues per TSO (7,350 / 291) | 0.26 bind/s | removes ST-03; day one becomes ST-01 (D-126) |
| ST-05 | Trickle sync (R5) | 09:00 to 17:00 | 300,000 batches / 28,800 s = 10.4 per second average | peak 2 x = 21 batches/s = 125 rows/s | 50 batches/s |
| ST-06 | Evening typical wave | 17:00 to 17:20 | 6,000 catch-up batches in 1,200 s = 5/s (1,000 rows/s); trickle tail 21/s; 20,000 home and config requests = 17/s; check-out and Sales Submit ride inside batches (D-64) | about 43 req/s and 1,125 rows/s | 160 req/s; 3,000 rows/s |
| ST-06b | The 17:00:00 check-out spike (D-505, G-qa-29) | 60 seconds from `cfg.day.checkout_earliest_time` | 60 percent of the fleet (5,100 SRs) press check-out and Sales Submit within 60 s of 17:00:00 because they finished early and the gate has just opened; 80 percent of those carry a 12-row batch and 20 percent a 200-row catch-up (ASSUMPTION): 4,080 x 12 + 1,020 x 200 = 252,960 rows; the 1,051 AMOs and 291 TSOs open Daily Tracking, Live Dashboard and Final Submit previews in the same minute (about 1,342 x 2 reads) | without jitter: 5,100 batches in 60 s = 85 batches/s, with one home or config read each 170 req/s, 4,216 rows/s, plus about 45 dashboard req/s on the replica; with the client jitter U(0, 90 s) (`cfg.sync.checkout_upload_jitter_max_s`) the same load spreads over about 150 s: 34 batches/s, 68 req/s, 1,700 rows/s | 200 req/s and 4,500 rows/s with no jitter (the jitter is a margin, not the design); gate S3b with the S5 readers, T-4-155 |
| ST-07 | Worst case, all phones offline until 17:00, then one 30-minute catch-up | 17:00 to 17:30 | 8,500 x 350 rows = 3.0 M rows / 1,800 s; 17,000 to 25,500 batches | 1,667 rows/s sustained, 3,333 at 2 x burstiness; measured basis 1,322 and 2,644; 9.4 to 14.2 batches/s | 8,000 rows/s (2.4 x design peak, 3.1 x measured peak) |
| ST-08 | DR or PITR re-sync of the last 24 h | after the generation change | 8,500 x 350 = 2.98 M rows spread by U(0, 900 s) (doc 17 D-406) | 3,306 rows/s; coinciding with ST-06: 4,400 rows/s | inside the 8,000 gate |
| ST-09 | Photo wave on home Wi-Fi | 20:00 to 22:00 | 150,000 PUT / 7,200 s; 22.5 GB in 2 h | 20.8 PUT/s, 3.1 MB/s; 2.1 SAS calls/s | 50 PUT/s, 5 SAS calls/s (S6) |
| ST-10 | Access-token expiry wave | on the hour | about 5,900 online devices log in within 10 min; TTL 60 min | un-jittered: 9.8 refreshes/s; with plus or minus 10 min jitter the window is 30 min: 3.3/s; with piggyback refresh: about 0 standalone | 10 refreshes/s (S10) |
| ST-11 | Urgent push (kill switch, revert) | seconds | 8,500 phones | pull_after_s U(0, 20): 425 req/s; ordinary U(0, 120): 71 req/s | pre-scale before an urgent push |
| ST-12 | Mass re-assignment by an admin | minutes | 1,000 users get a scope_version bump | regeneration capped at 20 per second (cfg.bundle.regen_max_per_s) = 50 s | bumps coalesced per user per 5 min |
| ST-13 | APK release to the fleet | days | 8,500 x 30 MB = 255 GB staged by wave_pct 1, 10, 100 percent | 85 devices at 1 percent = 2.6 GB | served from Blob behind Front Door, never the API |
| ST-15 | Absolute refresh expiry of a wave cohort (D-518, G-qa-42) | one calendar day, about 90 days after a pre-bind day | wave 4 is about 4,250 SRs bound on the same pre-bind day; with a fixed 90-day absolute lifetime they all need a password login on the same morning (offline unlock is also blocked for them, doc 21 s2.7) | 4,250 Argon2id logins in the first hour of the day = 1.2 logins/s average, a cluster of 12 logins/s if they start together; with a per-family random lifetime of 90 days plus or minus 15 (75 to 105) the same cohort spreads over 30 days: about 140 a day | the `auth` limiter of 4 per replica; gate T-4-162 (4,250 families expire on one simulated day, login p95 at most 3 s, no lockout) |
| ST-14 | Recovery after a 30-minute API outage | seconds to minutes | backoff cap 300 s with U(0.5, 1) jitter (doc 17 D-381): first retries spread over 150 to 300 s | up to 57 retries/s, then normal trickle | inside ST-01 design |

### 1.5 Bundles and egress

| Bundle | Size | Basis |
| --- | --- | --- |
| SR (all routes of the day) | median 25 KB gz, p95 60 KB gz | ASSUMPTION from lens-scale (110 outlets median); measured by T-1-36 (doc 17) |
| AMO, average zone | about 90 KB gz (10.8 routes x 64 outlets = 690 outlets x about 100 B gz + 12 KB fixed) | 11,336 routes / 1,051 zones |
| AMO, largest zone | 3,500 outlets: 350 KB gz; hard cap 2 MB gz; sections above 2,000 rows are paged | doc 17 D-386, D-72 |
| TSO | login snapshot plus pickers for about 2,500 outlets: planning 300 KB gz, cap 1 MB gz | doc 17 s6; ASSUMPTION for the planning figure |
| Fleet, one full morning | 8,500 x 25 KB + 1,051 x 90 KB + 291 x 300 KB = 0.39 GB | 212 + 95 + 87 MB |
| Stored snapshots | 0.39 GB a day, kept 3 days = 1.2 GB in Blob; 0.4 GB hot in Redis (36 h TTL) | s2.7 |

### 1.6 Online-only behaviours from the manual register (section 2.6) and what they cost

The register finds that only four things are stated as online in the SR, AMO and TSO manuals; everything else is a CLAUDE.md requirement. No G-man entry is owned by doc 18 (skeleton s7.4: all 104 are owned by docs 15, 16, 17, 19 and 21). The rows below are the register entries that change load or capacity, with the treatment here.

| Surface (register 2.6) | Register entry | Class | Load and treatment | Design rate | Section |
| --- | --- | --- | --- | --- | --- |
| First login, device OTP verify | G-man-020, 021 | ONLINE-FIRST, ONLINE-ONLY | Password hash and bind run in the separate `auth` app with a hash limiter; pre-bind day removes the storm | ST-02 to ST-04 | s2.5, s5.2 |
| Update check, APK download | G-man-022 | ONLINE-ONLY | APK from Blob through Front Door cache, per-ABI split APK, wave_pct staging; never the API | ST-13: 255 GB | s2.4, s2.8 |
| Attendance address | G-man-029, 059 | HYBRID | Device-side geocoder when online; no bulk server-side reverse geocoding; on demand with a 24 h cache by coordinates rounded to 3 decimals (D-425) | 0 server calls by default | s3.3 |
| Sale History beyond the local window | G-man-013 | HYBRID | F-API-025, indexed read of memo by outlet and date on the replica; only for dates older than 7 days (D-83) | below 0.1 req/s (ASSUMPTION: 5 percent of SRs, about 10 lookups each) | s2.6 |
| Sync Data and Sales Deposit | G-man-031 | ONLINE-ONLY in the manual | Becomes outbox events with the settle rule (D-64); the single end-of-day moment of the old app becomes ST-06 and ST-07 | 5/s typical; 14/s worst | s1.4 |
| Team Location, My Team, Retailer maps | G-man-058, 059, 078 | ONLINE-ONLY, HYBRID | F-API-023 reads the last synced fix per SR (a column, not a scan); Google tiles are billed outside Azure (D-08, D-425) | about 8 SRs per zone; below 1/s | s4.5 |
| AMO Live Dashboard, SR Stock, STD Memo Report, Sales Summary | G-man-061, 062, 063 | ONLINE-ONLY | Explicit open, no timer (doc 17 D-387); dw aggregates on the replica; 30 s response cache | 1,051 AMOs x about 6 opens = 6,300 a day; about 1/s at 17:00 | s4.5, s6.4 |
| TSO dashboard, Not Logged In, Target Status | G-man-069 | CACHED | Login snapshot plus explicit refresh; as-of stamp | 291 x about 10 = 2,900 a day | s4.8 |
| Final Submit preview and submit | G-man-070, 071 | ONLINE-ONLY | F-API-039 and F-API-009; validation reads the primary; once per zone-day by primary key | at most 3,200 a day, 18:00 to 20:00: 0.5/s | s4.5 |
| Web, all screens | G-man-095 | ONLINE-ONLY | Interactive reads up to a row limit on the replica; larger ranges are export jobs (D-429, F-SYS-064) | about 130 users; 50 concurrent exports | s4.9 |
| Web delete or void against a late phone upload | G-man-086 | ONLINE-ONLY | Voided client uuids stay in the registry as tombstones; a late upload is rejected `voided_by_admin` through the same dedupe lookup | negligible | s4.2 |
| Sales Plan, outlet approval, wholesale marking, target approval, gift choice | G-man-088, 034, 036, 068, 047 | ONLINE-ONLY | Changes reach phones at the next bundle; a bulk edit bumps many scope_versions, so regeneration is capped (ST-12) and bulk operations are frozen 07:00 to 09:30 and 16:30 to 19:30 (D-100) | 20 snapshots/s | s4.8, s5.6 |
| Web Entry, Astha Web Entry, QC entry | G-man-085, 046, 089 | ONLINE-ONLY | Each submission has a client uuid; low volume; usage unknown (MQ-46) | at most 1,051 a day | s4.2 |
| Photos for force sale, Astha, campaigns | G-man-016, 042 | QUEUED | Direct SAS upload, Wi-Fi first | ST-09 | s2.8 |
| Tutorial videos | G-man-043 | HYBRID | List in the bundle; playback from Blob behind Front Door; sizes unknown; confirm with the business | unknown | s2.4 |
| PDA to Support | G-man-025 | ONLINE-FIRST | F-API-030, SAS to a `support/` container, size cap from cfg.support.max_upload_mb; ASSUMPTION 1 percent of the fleet a day at 20 MB = 1.7 GB | 85 uploads a day | s2.8 |

### 1.7 Write amplification

| Item | Naive (per memo, in place) | Design (D-412) |
| --- | --- | --- |
| Aggregate rows touched per memo of 5 lines | about 70 (lens-scale s1.9: route-SKU 5, outlet 1, route 1, five rollup levels 5, month-to-date route product about 12, month-to-date rollups about 48) | not applicable |
| Aggregate row-writes a day | 350,000 memos x 70 = 24.5 M (skeleton s6.1) | recompute by (business_date, route) dirty key with `IS DISTINCT FROM` so unchanged rows write nothing: about 15 changed rows per visit family (2.5 route-SKU, 1 outlet, 1 route, 7.5 month-to-date) = 4.5 M a day |
| Rollups above route | one hot national row updated 120,000 times a day | recomputed from the zone rows at most once per cfg.agg.rollup_min_interval_s (15 s) per touched node: 1,402 nodes (1,051 zones, 291 territories, 50 divisions, 10 wings) |
| Locking | row-lock serialisation and deadlocks with `supersedes_memo_id` edits | no increments; a retry or a late batch recomputes the same value |

ASSUMPTION: the 15 changed rows is an estimate from the grain list of docs/10 and lens-scale; T-4-54 measures dead tuples per hour and WAL per second and replaces it. The common case is the trickle: a route receives one batch about every 8 minutes, so coalescing helps only in the evening wave, which is why the write guard, not the window, carries the design.

### 1.8 Peak table (design-for)

| Window | API req/s (expected peak) | DB write rows/s | DB TPS (ingest + worker + auth) | Replica read QPS | Design-for |
| --- | --- | --- | --- | --- | --- |
| 06:30 to 07:45 morning, 5-minute concentration | 131 | about 130 (bundle_download 33, route_day 28, refresh rotation 66) | about 150 | 30 | 400 req/s; 500 TPS |
| 09:00 to 17:00 trickle peak | 21 + 17 other = about 40 | 125 ingest + about 80 aggregate | about 60 | 30 | 50 batches/s |
| 17:00 to 17:20 typical | about 43 + 10 dashboard pages | 1,125 ingest + about 300 aggregate | about 80 | 50 | 160 req/s; 3,000 rows/s |
| 17:00:00 to 17:01:00 check-out spike (ST-06b) | 85 batches + 85 reads = 170 (68 with jitter) + 45 dashboard pages | 4,216 ingest + about 600 aggregate (about 4 dirty-queue rows per batch) | about 300 | 100 | 200 req/s; 4,500 rows/s; capacity line of its own, not folded into ST-06 |
| 17:00 to 17:30 worst case | 14 batches + 17 other | 3,333 ingest + about 400 aggregate | about 60 | 50 | 8,000 rows/s |
| Photo wave | 2.1 SAS/s | 0 | 0 | 0 | 5 SAS/s; 50 PUT/s direct |
| Dashboards 17:00 to 19:00 | 25 req/s (about 1,490 readers at 60 s, s1.3b) | 0 | 0 | about 50 light + 0.5 heavy; live tiles at most 50 QPS on the primary | 150 QPS |

### 1.9 Storage growth and runway

| Item | Value | Basis |
| --- | --- | --- |
| App and dw rows including indexes | about 2.8 GB a day at the design volumes (app about 1.66 GB, dw about 1.18 GB), 2.3 GB at the July call mix; the bottom-up table of widths is doc 16 s8.11 and replaces the first draft's 295 B per row, 1.26 GB a day and 585 GB (D-520, G-qa-44) | doc 16 s8.4 counts (fact_visit about 182 M rows a year, fact_memo about 168 M at 70 columns, fact_memo_line about 400 M) and the DDL widths; every width is an ASSUMPTION until T-1-150 measures it |
| Ingest registry | about 135 M rows and about 12 GB at the 45-day prune (3.0 M a day x 45 days x 90 B), kept flat; the "1.2 B rows, 120 GB" of the first draft assumed no prune (OI-18-04 resolved) | doc 16 s6.1 |
| Apsis history import | about 20 GB for 37.3 M sales rows plus about 80 to 130 GB if photos' metadata, visits and QC of 8 months are imported at memo level (ASSUMPTION) | docs/22, docs/11 |
| Year-1 total | about 1.0 TB (2.8 GB x 365) plus the import and the registry. THE sizing decision (D-568, G-qa-103): the production server that receives the import is created at 7a with 1,024 GiB of Premium SSD v2 and the matching IOPS on the primary, the in-region replica and the cross-region replica; a 512 GiB server (about 550 GB) would reach the 70 percent alert after about 4.5 months of full-fleet growth, or 3.3 months with the import counted, and read-only at 95 percent after about 6.1 months. At 1,024 GiB (972 GiB usable): 60 percent after about 185 to 225 days, 70 percent after about 225 days, read-only after about 323 days (arithmetic in doc 16 s8.11). The extra disks cost about USD 283 a month at the full fleet (s3.3) | doc 16 s8.11 |
| Steady state after month 25 | about 1.6 TB (app 13 months, dw 25 months) | doc 16 s8.11 |
| Storage alerts | 60 percent used (warn), 70 percent (grow now), a projected-full date within 90 days (warn), 90 percent (Sev1), all four in the alert table of s6.6; SSD v2 has no autogrow and the server goes read-only at 95 percent, which is a total write outage, so the replica and the DR replica are sized with the primary | D-131, D-520, D-568 |
| First grow | by month 6 of full-fleet running, to 2,048 GiB, triggered by the 60 percent alert (D-131 and its change-log row, FM-29 and OI-18-03 said month 9 and are corrected to month 6; the first draft said month 9 on 1.26 GB a day); IOPS tier and throughput bought with the capacity on the primary, the in-region replica AND the cross-region replica, which are sized together | D-520, D-568 |
| Photos | 22.5 GB a day, 8.2 TB a year; at day 365: Hot 0.68 TB, Cool 1.35 TB, Cold 6.2 TB | lifecycle Hot 30 d, Cool 60 d, Cold to 365 d, Archive after (D-132) |

### 1.10 Sensitivity

| If this input changes | Effect | Headroom |
| --- | --- | --- |
| Online share 100 percent instead of 60 percent | 500,000 trickle batches a day, 34.7 batches/s peak, no evening catch-up | inside the 8,000 rows/s gate |
| Online share 20 percent | catch-up rows 2.4 M, 2,000 rows/s in 20 min, 3,333 at 30 min | inside the gate |
| Lines per call 5 (lens-scale value) | rows 4.0 M a day (+33 percent); 465 rows per SR-day | inside the gate |
| Rows grow another 53 percent | 4.6 M a day; worst-case peak 5,100 rows/s | 1.6 x headroom to the gate; re-run S4 |
| Wave 1 is 1,000 SRs (Q51) | ST-02 as above | SKU ladder is sized for this case |

### 1.11 Location-change request volume (D-545, G-qa-76)

D-163 raises a location request on every SR force sale and every AMO manual override at a confirmed outlet. The only earlier sizing was docs/22 P-11, "under 500 a day", which is today's outlet-creation trickle and not this stream. This section models it; the queue rules (one open request per outlet, minimum move, accuracy filter, aging, bulk approve) are doc 16 s4.4.

| Input | Value | Basis |
| --- | --- | --- |
| Planned visits a day | 460,000 | 500,000 visits at the design point, successful calls 450,000 |
| Force-sale share of visits | 3 percent expected, 25 percent is the default alert threshold (doc 19 `cfg.sec.fraud.*`); stress case 10 percent | ASSUMPTION; the observed Apsis rate is unknown (unknown; confirm with the business, Q-NEW volume basis) |
| Requests a day without control | 460,000 x 3 percent = 13,800 (stress 46,000, alert case 115,000) | multiplication |
| Distinct outlets behind them | about 20 to 30 percent of the force sales are repeats at the same outlet within a week (ASSUMPTION), and 80 percent of outlets share a 55 m cell (docs/22 P-10) so many are noise | ASSUMPTION |
| Requests a day with the controls | one open request per outlet: at most the distinct outlets, about 9,000 expected (stress 28,000); minimum move 25 m and accuracy filter 50 m remove a further 30 percent (ASSUMPTION): about 6,300 expected, 19,600 stress | doc 16 s4.4 |
| Per AMO | 6,300 / 1,051 = about 6 a day expected, 19 stress (the uncontrolled stress case was 44 to 110 a day per AMO) | arithmetic |
| TSO escalation | requests older than 72 h (`cfg.sla.location_request_escalate_h`); with the bulk approve of consistent proposals most clear in one action | doc 16 s4.4 |
| Load on the system | negligible (rows of `outlet_change_request` and evidence; a few writes a second at peak); the cost is human queue time, so the alert is a backlog, not a rate: `cfg.sla.quarantine_backlog_alert` stays 500 for quarantine and the location queue has its own `cfg.sla.location_request_backlog_alert` (default 25 per AMO, 250 per TSO) | doc 19 |

Gate: T-4-152 loads 19,600 requests a day into the queue model, asserts one open request per outlet, the aging and escalation clocks and a bulk approve of a consistent 3-visit group, and measures the AMO queue length.

Proved by: T-1-36 (bundle size and rows per SR-day baseline), T-4-51, T-4-52, T-4-53, T-4-54, T-7-56.

## 2 Azure architecture

### 2.1 Topology

Phase: skeleton in 0a (Bicep), pilot-sized in 1c, fleet-sized by 4d. Offline class: not applicable (server side); every box below degrades without breaking offline capture (s5.3).

```
 Field phones: ARON SR / AMO / TSO (Flutter, offline-first)             Browsers: HQ, DMO, WM, TSO web
 outbox -> POST /v1/sync/batch (one per visit), GET /v1/sync/bundle     Next.js (BFF, HttpOnly cookie session)
        | HTTPS gzip JSON                  | HTTPS PUT (SAS, write-only, 15 min)       |
        v                                  +-------------------------------+           v
+------------------------------------------------------------------------------------------------------+
| AZURE FRONT DOOR   PREMIUM from the pilot (7b) + private endpoint to ACA (D-573, D-06)                |
|  api.<domain>   app.<domain>   dl.<domain>   + default *.azurefd.net hostname (baked-in fallback)      |
|  WAF: custom per-IP backstop rules; managed rules in LOG mode through the pilot, enforce at wave 1     |
|  cache: dl/* (APK, thumbnails, content), web static, GET /config/public 60 s; never /sync/bundle        |
+----------+----------------------------+--------------------------+------------------------+-----------+
           | /v1/auth/login*, bind,     | /v1/*                    | /*                     | /dl/*
           | change-password            |                          |                        |
           v                            v                          v                        v
+--------------------------------------------------------------------------------+   +------------------------+
| AZURE CONTAINER APPS  workload-profiles environment, zone-redundant, own VNet   |   | BLOB STORAGE (GPv2)    |
|  auth    NestJS 2 vCPU/4 GiB  hash limiter 4 per replica   own DB role          |   |  account "evidence":   |
|  api     NestJS 1 vCPU/2 GiB  HTTP rule 30 req/s per replica + cron pre-scale   |-->|   photos/  audit-export|
|  worker  1 vCPU/2 GiB  KEDA: outbox depth (postgresql), photo events (queue)    |   |   (RA-GZRS, WORM)      |
|  web     Next.js 0.5 vCPU/1 GiB  HTTP rule                                      |   |  account "content":    |
|  jobs    ACA Jobs, cron in UTC: pregen, route_day, re-agg, partitions, exports  |   |   bundles/ apk/ thumbs/|
+----------+---------------------+---------------------+-------------------------+   |   content/ support/ ZRS|
           | 6432 PgBouncer      | 6432 replica         | TLS 10000                   +------------^-----------+
           v                     v                      v                                          | Event Grid BlobCreated
+----------------------------+ +-------------------+ +---------------------------+              | -> Storage queue -> worker
| PG FLEXIBLE primary        | | in-region replica | | AZURE MANAGED REDIS       |
| GP Ddsv5, zone-redundant HA| | (async, same SKU) | | Balanced, HA              |
| SSD v2, PgBouncer txn mode | | dashboards, BI,   | | snapshots, replay copy,   |
| PITR 35 d, geo backup      | | reports, exports  | | rate limits, cfg version  |
+-------------+--------------+ +-------------------+ +---------------------------+
              | async physical replication
              v
+---------------------------------- East Asia (DR, warm standby) ------------------------------------------+
| cross-region replica (D4, scaled to D8 on promotion) | ACR geo-replica | Key Vault with the same signing keys |
| VNet + ACA environment (apps at min 0) | Front Door DR origin (disabled until RB-13; ready only if not in recovery)|
+--------------------------------------------------------------------------------------------------------+
 Cross-cutting: Key Vault, managed identities, Azure Monitor (ops workspace, sync workspace, Application Insights),
 Azure Load Testing, GitHub Actions (OIDC) -> ACR -> ACA revisions, Bicep.  Outside Azure: FCM (urgent only), Google Maps, Sentry.
```

### 2.2 Region

| Item | Decision | Why |
| --- | --- | --- |
| Primary | Southeast Asia (Singapore), 3 availability zones | zone-redundant ACA and PostgreSQL HA need zones; SSD v2, geo-redundant backup and cross-region replicas are supported there (Learn, s2.10) |
| DR | East Asia (Hong Kong), the paired region | same service coverage; SSD v2 with geo features is listed for East Asia (Learn) |
| Alternative | Central India; also supports SSD v2 | choose only if the bake-off (D-422) or residency (Q19) says so |
| Bangladesh | no Azure region exists | D-05 MUST-CONFIRM (by 7a entry since D-570): data localisation, Q19, G-sec-01 |
| Latency | Dhaka to Singapore and to Pune: unknown; measure | D-422: Dhaka lab phones on three carriers, 200 requests each to a probe endpoint in both regions; choose Southeast Asia unless Central India is at least 25 percent lower at p50 and Q19 does not decide (ASSUMPTION: the threshold) |
| Dev, staging | same region as prod | D-05 |

### 2.3 Components, redundancy and reasons

| Component | Role | Redundancy | Why this and not the alternative |
| --- | --- | --- | --- |
| Azure Front Door | Edge: TLS, WAF, caching of APK and static, routing, fallback hostname | global anycast; Premium from the pilot (D-573); the SLA is the one Microsoft publishes for the tier | one place to hold WAF Log mode, per-version hold (D-130) and the second hostname; Premium adds Private Link so the ACA environment has no public ingress (Learn: Private Link origins are Premium only) |
| Container Apps (workload-profiles environment) | api, auth, worker, web, jobs | zone-redundant; minimum 2 replicas to spread across zones (Learn) | scale on request rate and queue depth without a cluster to run; App Service has no KEDA-style backlog scaler for the worker and no scheduled-job model as simple as ACA Jobs (D-04 already fixes Container Apps) |
| PostgreSQL Flexible Server | System of record | zone-redundant HA, RPO 0, failover typically 60 to 120 s (Learn) | relational aggregation at this size; built-in PgBouncer (Learn) |
| Read replica | dashboards, reports, exports, BI | async; no HA on a replica (Learn) | keeps 1,500 viewers off the primary; same SKU as the primary because WAL replay must keep up and promotion needs symmetry (Learn) |
| Cross-region replica | DR | async; promotion is manual and forced in a region outage (Learn); the SAME SKU as the primary (D8ds_v5 from wave 1), because replay is single-threaded and RPO 15 minutes depends on keeping up with WAL at the 17:00 peak (D-522, G-qa-46); the WAL-versus-replay arithmetic is s5.4 | RTO 4 h and RPO 15 min (D-127) without a second writer |
| Azure Managed Redis | cache and counters only | HA; every use fails open or falls back to the database (s2.7) | Azure Cache for Redis cannot be created by new customers from 2026-04-01 and retires 2028-09-30 (Learn); Managed Redis is the successor |
| Blob Storage, two accounts | photos and audit in "evidence"; bundles, APK, content in "content" | evidence RA-GZRS; content ZRS | evidence stays readable in a region outage (D-132); separate accounts isolate request limits and lifecycle |
| Event Grid and Storage queue | photo-linking events | queue is at-least-once; nightly sweep is the safety net | no Service Bus: ingest is synchronous (s4.1) and photo events need no ordering (D-424) |
| Key Vault, managed identities | secrets, signing keys, SAS delegation | soft delete; second vault in DR with the same signing keys | no secrets in the repo (CLAUDE.md); OIDC for CI |
| ACR Premium | images | geo-replicated to East Asia | a region outage must not strand image pulls (Learn: geo-replication) |
| Azure Monitor | metrics, logs, alerts, workbooks | two Log Analytics workspaces (s6.3) | the business sync-health screen reads Postgres, not logs, so a log cap never blinds the war room |
| Azure Load Testing | S1 to S10 | n/a | runs Locust with server-side metrics from Azure services (Learn) |
| FCM, Google Maps, Sentry | outside Azure | n/a | D-09, D-08, D-13 |

### 2.4 Front Door, WAF and hostnames

| Item | Setting | Source |
| --- | --- | --- |
| Hostnames | `api.<domain>` (device API), `app.<domain>` (web), `dl.<domain>` (APK, content); the Front Door default `*.azurefd.net` endpoint is baked into the app as the second hostname after cfg.net.fallback_after_failures (3) TLS or DNS failures; custom-domain DNS TTL at most 300 s; the domain is unknown; confirm with the business (OI-18-05) | D-81, G-sre-12 |
| Tier ladder (D-573, G-qa-109: ONE decision, one date; docs 14, 18, 21 and D-06 agree) | PREMIUM from the start of the pilot (7b), with a private endpoint to the ACA environment and public network access disabled on the origin. Microsoft Learn (read 2026-10-04): managed rule sets and bot protection exist only on Premium (Standard supports custom WAF rules only) and only Premium has a Private Link origin; Standard cannot reach a private origin, so a Standard origin is public and protected only by a header check and an IP restriction that are unverified (G-18-06). Premium costs a base fee of about USD 330 a month against about 35 for Standard (s3.3), a small price against a first wave that would otherwise meet managed rules with no log-mode history. The Private Link and origin-lockdown spike (G-18-06) moves BEFORE the pilot (due 2e); if it fails, the fallback is a public origin restricted by the `AzureFrontDoor.Backend` service tag and the `X-Azure-FDID` header, tested by a direct-origin request that must be refused (T-7-162) | D-06, D-423, D-573; Learn: Private Link needs Premium and a workload-profiles environment |
| Zone-redundant note | Learn: a zone-redundant ACA environment cannot be reached through Private Link Service to an internal load balancer; use the environment private endpoint, which works with zone redundancy | s2.10 |
| Routes | `/v1/auth/login*`, `/v1/auth/bind-device`, `/v1/auth/change-password` to the `auth` app; `/v1/*` to `api`; `/*` to `web`; `/dl/*` to the content account | D-414 |
| Caching | cached: `/dl/*` (immutable, versioned names), web static, `GET /config/public` for 60 s (F-API-042). Never cached: `/v1/sync/bundle` (per user, Authorization header) | lens-scale s2.3 |
| WAF managed rules | Log mode on `/v1/sync/*`, `/v1/auth/*` and `/v1/media/*` through the whole PILOT (7b) on the real payload corpus, which is possible because the pilot already runs Premium; request-body inspection excluded for `/sync` and `/media`; enforcement at wave 1 for the rule groups with no false positive in the pilot log, the HTML 403 to `/sync/batch` being the failure to find (G-sre-10) | D-81, D-573, G-sre-10 |
| WAF rate rules | per-socket-IP backstop only; thresholds in s4.4; Front Door counts per fixed 1 or 5 minute window and lets requests above a low threshold through (Learn), so a high threshold is a backstop, not a control | Learn: waf-front-door-rate-limit |
| Origin response timeout | set explicitly to 60 s for the profile (range 16 to 240 s; the troubleshooting page says the default is 30 s, other pages say 60 s: unverified); the API request timeout is 25 s so the API always answers first | Learn: how-to-configure-origin |
| Origin group | one SEA origin; one DR origin disabled until RB-13; the probe path is `/healthz/live`, interval 60 s | D-420 |
| Logs | access and WAF logs to the ops workspace for `/sync`, `/auth`, `/media` at 100 percent; other paths sampled | D-419 |
| Platform 429 | Front Door applies default platform rate limits and returns 429 above them; the numbers are not published in the sources read; ask Microsoft before wave 1 (OI-18-07) | Learn: troubleshoot-issues |

### 2.5 Container Apps

| Setting | Value | Why or source |
| --- | --- | --- |
| Environment | workload-profiles (v2), zone-redundant (chosen at creation, cannot change), own VNet, subnet at least /27 (use /26), consumption profile (4 vCPU and 8 GiB maximum per replica) | Learn: reliability-container-apps, structure |
| Revision mode | `api`, `auth`, `web`: multiple revisions with traffic weights 10, 50, 100 and auto-abort (D-14). `worker`: single revision, because it uses non-HTTP KEDA rules (Learn note) | D-14 |
| `api` | 1 vCPU / 2 GiB; HTTP rule 30 requests per second per replica (ACA computes requests in the past 15 s divided by 15, Learn); min 1 / 2 / 3 and max 3 / 10 / 30 by tier (s3.1) | D-139 |
| `auth` | 2 vCPU / 4 GiB; password verification, bind and change-password only; hash limiter cfg.auth.hash_concurrency_per_replica = 4 (256 MiB of hash memory per replica); queue at most 2 s then 503 with `Retry-After` 5 to 30 s; HTTP rule 8; min 1 / 2 / 2, max 2 / 6 / 12; own DB role and pool | D-414 implements D-126 without a revision swap; `/auth/refresh` stays in `api` because it hashes nothing |
| `worker` | 1 vCPU / 2 GiB; KEDA `postgresql` rule on unprocessed outbox rows, target 200 per replica; KEDA `azure-queue` rule for photo events, target 100; min 1 / 1 / 2, max 2 / 4 / 10 | lens-scale s2.4 |
| `web` | 0.5 vCPU / 1 GiB; HTTP rule 50; min 1 / 2 / 2, max 2 / 4 / 6 | |
| `jobs` | ACA Jobs; cron in UTC (Dhaka is UTC+6, no DST); table in s4.7 | |
| Probes | startup `/healthz/startup` (initial delay 5 s, period 5 s, failure threshold 30); liveness `/healthz/live` checks the process only so a DB failover never restarts every replica (FM-16); readiness `/healthz/ready` checks the database through PgBouncer with `SELECT 1` in 2 s and, for the DR environment, `pg_is_in_recovery() = false`. Redis is pinged and reported as `degraded`, not as unready, because every Redis use fails open; D-139 reads "DB and Redis" and this is the interpretation (OI-18-02) | D-139, D-417 |
| Shutdown | NestJS shutdown hooks and a 30 s drain; items claimed by a dying worker are reclaimed after cfg.agg.claim_timeout_s (300) | D-137, T-0-51 |
| Scale behaviour to plan with | scale-up step is min(max, desired, max(4, 2 x current)): from 3 replicas the sequence is 6, 12, 24, 30; scale-down waits a 300 s stabilisation window; adding or editing a scale rule creates a new revision (Learn) | s2.10 |
| Pre-scale | KEDA `cron` rule in the template: `api` and `web` raised for 06:15 to 10:00 and 16:45 to 20:00 Dhaka, `auth` raised on wave mornings; schedule rendered from cfg.ops.prescale_schedule at deploy, so a schedule change is a pipeline run, not a live edit (a scale-rule edit creates a revision). ASSUMPTION: a `cron` rule coexists with multiple-revision weights; verified in a spike (G-18-07). Fallback A: a static higher `minReplicas` on wave weeks (about +USD 550 a month at full fleet) | D-133, D-413 |
| Quotas | s3.2 | D-133 |

### 2.6 PostgreSQL Flexible Server

| Item | Value | Why or source |
| --- | --- | --- |
| Compute | General Purpose Ddsv5: D2 (from 7a, ZONE-REDUNDANT HA and geo-redundant backup ON, because this is the server that receives the Apsis import; D-569), D4 (wave 1, zone-redundant HA), D8 (full fleet, zone-redundant HA); D16 is the next step (s3.5). Ddsv6 sizes exist with higher IOPS caps; availability in Southeast Asia is unverified | D-06; Learn: concepts-compute |
| IOPS ceilings by size | D2ds_v5 3,750; D4ds_v5 6,400; D8ds_v5 12,800 (290 MiB/s); D16ds_v5 25,600 (600 MiB/s) | Learn: concepts-compute |
| Storage | Premium SSD v2: 1,024 GiB from 7a on the primary and every replica, then 2,048 GiB at the first grow (D-568; the 128 / 256 / 512 GiB ladder of the first draft contradicted the 2.8 GB a day sizing of s1.9). Baseline is 3,000 IOPS and 125 MB/s up to 399 GiB, and 12,000 IOPS and 500 MB/s from 400 GiB, at no extra cost; no storage autogrow; IOPS and throughput can be changed up to 4 times in 24 h (3 in the first 24 h of a new disk); the server goes read-only at 95 percent full | Learn: concepts-storage-premium-ssd-v2, concepts-storage |
| Consequence | Wave 1 on 1,024 GiB has a 12,000 IOPS baseline against a D4 cap of 6,400, so the cap, not the disk, binds and no IOPS purchase is needed; the full fleet on 1,024 GiB has 12,000 against a D8 cap of 12,800 | s3.5 |
| HA | zone-redundant; standby cannot serve reads; failover can exceed 120 s if WAL recovery is behind (standby recovers WAL at about 40 MB/s). Premium SSD v2 DISK HYDRATION (Learn, read 2026-10-04): a new disk, or a server created by a point-in-time restore, does not accept compute scaling, storage scaling, enabling HA or quick successive failovers until hydration finishes ("disk is still being hydrated"); during an unplanned failover the server may run without a standby while the disk hydrates. Every scale-up or HA step in this document therefore waits for hydration and the procedure checks it (RB-12, RB-13, s5.4) | Learn: concepts-high-availability; concepts-storage-premium-ssd-v2 |
| Backups | PITR 7 days (from 7a), 35 days (wave 1 and full). GEO-REDUNDANT BACKUP can be set ONLY when a flexible server is created (Learn, read 2026-10-04), so it cannot be "turned on at wave 1": the production server that receives the import at 7a is created with geo-redundant backup and zone-redundant HA already on (D-569, G-qa-104); the first draft started the pilot on a D2 server with no geo backup and a wave-1 rebuild that no step, runbook or gate covered. Premium SSD v2 supports HA, geo-redundant backup and geo replicas in Southeast Asia and East Asia (Learn). Replicas have no backups and no HA | D-06, D-569; Learn: concepts-geo-disaster-recovery |
| Maintenance window | custom, weekly, Friday 01:00 to 02:00 Dhaka (the weekly off-day, D-28); a planned failover of 60 to 120 s then never meets a trading day | ASSUMPTION; separates maintenance from the 02:15 re-aggregation (s4.7) |
| PgBouncer | built-in, port 6432, transaction mode; the same hostname after a failover. `default_pool_size` 20 per (user, database) pair (Learn default 50); `max_client_conn` 5,000 (default); `query_wait_timeout` 5 s (default 120, so a saturated pool answers 503 quickly); `max_prepared_statements` 0 (no named prepared statements, D-137); not available on Burstable | Learn: concepts-pgbouncer; D-416 |
| `max_connections` | leave the default (Learn lists 1,718 for D4; the D8 value of 3,437 is unverified) or lower it to 400; PgBouncer pools hold the real number to about 100 | Learn: concepts-limits |
| Roles and pools | Login identities `app_api`, `app_auth`, `app_worker`, `app_web`, `app_jobs`, `app_export`, `bi_reader` pooled; `app_migrator` direct on 5432 with `lock_timeout`. These are members of the privilege roles of the ONE role map, doc 16 s13.4b (`api_rw`, `auth_rw`, `worker_rw`, `jobs_rw`, `web_ro`, `export_ro`, `bi_reader`, owners and `migrator`); this document does not define a role or a grant (D-566) | D-416, D-566 |
| Role limits | `statement_timeout`: api 15 s, auth 5 s, worker 10 min, web 60 s, jobs 30 min, export 10 min; `lock_timeout` 3 s for api and auth; `idle_in_transaction_session_timeout` 30 s for all pooled roles | D-139 |
| Application pools | api 8 per replica, auth 2, worker 4, web 4, jobs 2 | D-139 |
| Extensions | `postgis` (spatial index for `GET /outlets/nearby` F-API-019 and the density view of docs/22 P-10), `pg_stat_statements`; Query Store and `track_io_timing` on; `log_min_duration_statement` 500 ms | docs/22 open items |
| Autovacuum | per-table settings on the aggregate tables (scale factor 0.02) | ASSUMPTION; tuned from T-4-54 dead-tuple numbers |
| Read replica | up to 5 per primary (Learn); one in-region from wave 1; virtual endpoints so the app uses a reader name; replica lag metric `physical_replication_delay_in_seconds` drives the degraded mode (s6.4) | D-128 |
| Behaviour after an HA failover | whether replication to the read replica re-attaches by itself and for how long lag spikes is not stated in the sources read; unknown; verified in S7 (G-18-11) | |

Little's law check for the pools: the API holds a server connection about 2 to 6 at a time at the worst-case ingest (40 batches/s x 60 ms = 2.4) and about 8 at the morning peak (150 req/s x 50 ms), so a pool of 20 per role leaves a margin of 2.5; Learn recommends 2 to 5 times the vCores (16 to 40 for D8) as a conservative start for server connections.

### 2.7 Redis

Redis holds copies only. The database or Blob is the source of truth for every key, so a flush loses time, not data (D-417).

| Use | Key | TTL | Source of truth | If Redis is down |
| --- | --- | --- | --- | --- |
| Bundle snapshot cache | `bundle:{user}:{business_date}` | 36 h | Blob `bundles/` plus the `bundle_snapshot` row | read Blob, about +30 ms (ASSUMPTION) |
| Batch replay copy | `replay:{device}:{batch_uuid}` | 2 h | `sync_batch.response`, 24 h, written inside the ingest transaction (D-62) | read the database |
| Per-device and per-user rate counters | `rl:dev:{device}`, `rl:usr:{user}` | 5 min | none | fail open on `/sync`, `/bundle`, `/config`; on `/auth/login` an in-process per-replica counter applies and doc 21 owns the lockout store |
| Config version and invalidation | `cfg:ver`, pub/sub `cfg:inv` | none | cfg tables | 30 s poll (D-137; `LISTEN` is not used because it does not work through PgBouncer transaction mode) |
| Device observed-state gate | `dev:obs:{device}` set if absent | 10 min | none | per-replica LRU; at most 30 x the writes, still below 40 per second |
| Response cache | `rc:{endpoint}:{scope_hash}:{date}:{agg_version}` | 30 s | dw on the replica | direct queries with the degraded-mode SLO (s6.4) |

Sizing. Working set: snapshots 0.39 GB + replay copies 0.08 GB (300,000 batches x 1 KB x 2/8 of the day) + counters and caches about 0.05 GB = about 0.55 GB. Choose Balanced B3 (3 GB) for wave 1 and the full fleet; B1 (1 GB) leaves too little after the system reserve. Names and the hourly price (B3 USD 0.081, B5 USD 0.193) come from the price API; whether HA doubles the price is unverified, so the budget doubles it. Redis is required before wave 1 (D-07); phases 1 to 3 run without it (Postgres and Blob serve the same paths).

### 2.8 Blob Storage

| Account, container | Content | Redundancy and tier | Lifecycle | Access |
| --- | --- | --- | --- | --- |
| evidence, `photos/{business_date}/{device_uuid}/{client_uuid}.jpg` | force-sale, outlet, gift, survey photos | RA-GZRS; Hot | Cool at 30 d, Cold at 90 d, Archive at 365 d; reference photos exempt; retention unknown (Q21, D-132) | device: user-delegation SAS, write-only, 15 min, pinned to the exact path; supervisors: read SAS issued by the API, 5 min |
| evidence, `audit-export/` | daily hash-chain export (D-113) | RA-GZRS; immutable time-based retention, 7 years ASSUMPTION pending legal | none | write by the audit job only |
| content, `bundles/` | per-user snapshots | ZRS; Hot | delete after 3 days | API through a managed identity |
| content, `apk/`, `thumbs/`, `content/` | APK releases, SKU thumbnails, AV and KV | ZRS; Hot | `apk/` versioned, old releases kept 90 days for the rescue release | Front Door cache; public by hostname only |
| content, `support/` | PDA to Support files | ZRS; Hot to Cool | 30 days | SAS from F-API-030 |
| Both | soft delete 14 days; the user-delegation key is cached per replica and refreshed at 6 days or on a 403 (D-132) | | | one storage queue `photo-events` in evidence |

APK distribution is a platform risk, not only a storage design (D-562, G-qa-94, RK-29). The `apk/` container serves a signed APK to be installed outside Google Play on 8,500 shared phones; Google has announced a developer-verification requirement for such installs (first countries from September 2026, global in 2027; scope and any enterprise path to be confirmed at 0c), Play Protect scans and may warn on or block an unknown-developer APK, and newer Android versions add "install unknown apps" friction. Wave 1 (June 2027) is inside that window. The channel is decided at 0c (D-10 moves from 2e): AKTCL registered as a verified developer for the three package ids, a Managed Google Play private app, or a lightweight MDM; `dl.<domain>` and `apk/` remain the fallback and the pilot channel. The 2e gate T-2-164 installs and updates the APK on current Android 14, 15 and 16 devices with Play Protect on, and T-7-166 is the wave go/no-go line "install success on the pre-bind day at or above `cfg.cutover.install_success_pct`" (95).

Throughput check against Learn: Southeast Asia is in the list with a default of 40,000 requests per second, 60 Gbps ingress and 200 Gbps egress per GPv2 account (lens-scale quoted 20,000). The design needs 21 PUT/s (50 in the test) and 0.46 Gbps for the APK day. Storage answers 503 Server Busy above a partition limit; the SDK retries with backoff and a photo never blocks a sale (D-390).

### 2.9 Network, identity and policy

| Item | Decision | Phase |
| --- | --- | --- |
| VNet per environment and region | prod Southeast Asia 10.40.0.0/16, DR East Asia 10.41.0.0/16, staging 10.50.0.0/16, dev 10.51.0.0/16 (ASSUMPTION: non-overlapping so peering stays possible) | 0a |
| Subnets (prod) | `snet-aca` /26; `snet-pg` delegated /28; `snet-pe` /27 for private endpoints (Blob, Key Vault, Redis, ACR); `snet-gw` /28 delegated to the Power BI VNet data gateway (D-560) | 0a, 4c |
| BI path (D-560, G-qa-92) | Postgres is VNet-injected and public access is denied by policy, so the Power BI service cannot reach the replica directly. The path is a VNet data gateway in `snet-gw` (or Fabric mirroring of the replica where the Flexible Server is supported: OI-16-40), with `bi_reader` on the replica only. Event facts older than 25 months and raw fixes older than their hot window are Parquet in Blob, read by a Microsoft Fabric Lakehouse (OneLake shortcut) that Power BI reads without a gateway. Quotas: Fabric capacity F2 to start, 10 Power BI Pro licences for analysts (both ASSUMPTION, priced at 0c); the gateway counts against the Fabric or Premium capacity limits | 4c |
| Key Vault layout (D-574) | `kv-sign` (root signing key only), `kv-pii` (PII DEK wrapping, OTP, pepper, support-bundle keys), DR copy `kv-sign-dr` in East Asia restored by backup in the same subscription and Azure geography; separate vaults so a throttle on one cannot starve the other | 0c |
| Public access | Postgres, Redis, Key Vault, both storage accounts: public network access denied in prod by Azure Policy; Front Door reaches ACA through the environment private endpoint from Premium; before Premium, external ingress is locked to Front Door by the header check (G-18-06) | 0a, 2e |
| Identity | user-assigned managed identities per app: api, auth, worker, web, jobs; roles on Key Vault secrets, Blob data, Redis data, Postgres Entra roles. Postgres Entra authentication through PgBouncer is supported (Learn); the token lifetime means pooled connections must survive a token-service outage; doc 21 owns the choice between Entra and a Key Vault password | 0c |
| Egress | ACA outbound to FCM, Sentry and the Google geocoder over the VNet default route; a NAT gateway for a stable egress IP only if a partner needs an allow-list (not needed today) | 1c |
| Policy | deny public network access on data services; allowed locations southeastasia and eastasia; required tags `env`, `owner`, `cost-center`, `data-class`; diagnostic settings deployed if not present | 0a |

### 2.10 Sources and verification status

| Limit or fact relied on | Value | Status | URL |
| --- | --- | --- | --- |
| ACA managed-environment quotas are per environment; defaults depend on subscription age and type | requests via Quota page or QMS | verified | https://learn.microsoft.com/azure/container-apps/quotas ; https://learn.microsoft.com/azure/container-apps/quota-requests |
| ACA HTTP rule, scale steps, stabilisation, rules create a revision | 15 s window; step min(max, desired, max(4, 2 x current)); 300 s scale-down window | verified | https://learn.microsoft.com/azure/container-apps/scale-app |
| ACA zone redundancy: at creation only, VNet needed, min 2 replicas, subnet /27 for workload profiles | | verified | https://learn.microsoft.com/azure/reliability/reliability-container-apps |
| ACA billing: free grants 180,000 vCPU-s, 360,000 GiB-s, 2 M requests a month; idle rate rules; Dedicated Plan Management charge with private endpoints | | verified | https://learn.microsoft.com/azure/container-apps/billing ; https://learn.microsoft.com/azure/container-apps/private-endpoints-with-dns |
| ACA environment: consumption profile 4 vCPU and 8 GiB per replica | | verified | https://learn.microsoft.com/azure/container-apps/structure |
| Front Door Premium Private Link to ACA; Standard has no Private Link; zone-redundant environments use the environment private endpoint | | verified | https://learn.microsoft.com/azure/container-apps/how-to-integrate-with-azure-front-door |
| Front Door WAF rate limits: per socket IP, fixed 1 or 5 minute window, Log and Block only, leaks at low thresholds | | verified | https://learn.microsoft.com/azure/web-application-firewall/afds/waf-front-door-rate-limit |
| Front Door origin response timeout 16 to 240 s | default 30 s or 60 s | unverified default | https://learn.microsoft.com/azure/frontdoor/how-to-configure-origin |
| PgBouncer: port 6432, defaults, restart on failover, transaction mode, version 1.25.2 | | verified | https://learn.microsoft.com/azure/postgresql/connectivity/concepts-pgbouncer |
| PostgreSQL max connections by SKU | D4 1,718; D8 not in the excerpt read | partly verified | https://learn.microsoft.com/azure/postgresql/configure-maintain/concepts-limits |
| PostgreSQL SKU IOPS and bandwidth caps | D2 3,750; D4 6,400; D8 12,800 and 290 MiB/s; D16 25,600 and 600 MiB/s | verified | https://learn.microsoft.com/azure/postgresql/compute-storage/concepts-compute |
| SSD v2: baselines, no autogrow, 4 changes in 24 h | | verified | https://learn.microsoft.com/azure/postgresql/compute-storage/concepts-storage-premium-ssd-v2 |
| HA: zone-redundant 60 to 120 s, no reads on the standby, WAL recovery 40 MB/s | | verified | https://learn.microsoft.com/azure/postgresql/high-availability/concepts-high-availability |
| Read replicas: up to 5, async, no HA or backups on a replica; promotion is manual; forced promotion in a region outage; server symmetry | | verified | https://learn.microsoft.com/azure/postgresql/read-replica/concepts-read-replicas ; https://learn.microsoft.com/azure/postgresql/read-replica/concepts-read-replicas-promote ; https://learn.microsoft.com/azure/postgresql/read-replica/concepts-read-replicas-geo |
| Standard storage account targets in Southeast Asia | 40,000 req/s; 60 Gbps in; 200 Gbps out | verified | https://learn.microsoft.com/azure/storage/common/scalability-targets-standard-account |
| Single block blob target request rate | 3,000 req/s | verified | https://learn.microsoft.com/azure/storage/blobs/scalability-targets |
| Azure Cache for Redis: new-customer creation blocked from 2026-04-01; retirement 2028-09-30; existing customers may keep creating until retirement | | verified | https://learn.microsoft.com/azure/azure-cache-for-redis/cache-whats-new |
| Azure Load Testing: Locust, 500 users per engine recommended | | verified | https://learn.microsoft.com/azure/app-testing/load-testing/how-to-high-scale-load |
| Log Analytics daily cap: collection stops, some excess is still billed, reset hour not configurable, Auxiliary plan not capped | | verified | https://learn.microsoft.com/azure/azure-monitor/logs/daily-cap |
| Retail prices | list, USD, southeastasia, fetched 2026-10-04 | verified by API | https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices |
| Not found in the sources read: Front Door platform 429 limits; Android trust of the managed certificate chain; KEDA `cron` rule behaviour in ACA; replica behaviour after HA failover; Azure Load Testing price; Redis HA price; GRS backup price | | unverified | |

Proved by: T-0-50, T-0-51, T-2-54, T-2-57, T-4-53, T-4-55, T-4-56, T-7-54, T-7-55.

## 3 Capacity, quotas and cost

### 3.1 SKU ladder

D-06 gives the ladder; this table adds the replicas and the two additions of this document: the `auth` app and an earlier geo-redundant backup. Wave 1 is sized for the Q51 case of 1,000 SRs (the doc 14 default of about 290 SRs fits inside it).

| Component | Pilot (about 30 users) | Wave 1 (up to 1,155 users) | Full fleet (9,842 app users, about 130 web) |
| --- | --- | --- | --- |
| Front Door | Premium with Private Link to ACA from the pilot (D-573) | Premium | Premium |
| `api` (1 vCPU, 2 GiB) | min 1, max 3 | min 2, max 10; pre-scale 4 | min 3, max 30; pre-scale 8 at 06:15 and 16:45 |
| `auth` (2 vCPU, 4 GiB) | min 1, max 2 | min 2, max 6 | min 2, max 12; 6 on wave mornings |
| `worker` (1 vCPU, 2 GiB) | min 1, max 2 | min 1, max 4 | min 1, max 10; pre-scale 3 |
| `web` (0.5 vCPU, 1 GiB) | min 1, max 2 | min 2, max 4 | min 2, max 6; pre-scale 4 |
| Jobs, bundle pre-generation | 2 parallel | 4 parallel | 8 parallel |
| PostgreSQL primary | D2ds_v5 created at 7a with zone-redundant HA, geo-redundant backup, SSD v2 1,024 GiB (12,000 IOPS baseline, D2 cap 3,750), PITR 7 d (D-568, D-569) | the same server scaled to D4ds_v5 (compute only, no rebuild), 1,024 GiB, PITR 35 d | D8ds_v5 zone-redundant HA, 1,024 GiB (12,000 IOPS, cap 12,800), grown to 2,048 GiB by month 6, PITR 35 d |
| In-region read replica | none | D4, 1,024 GiB | D8, 1,024 GiB |
| Cross-region replica (East Asia) | none | none; geo-redundant backup is the fallback (RPO about 1 h; RTO MEASURED by the timed geo-restore drill into East Asia on staging before wave 1, T-4-174, and written into RK-05 and the day-one checklist; the sponsor accepts the wave-1 DR position in writing, D-569) | D8 (same SKU as the primary, D-522), 1,024 GiB with the same IOPS tier; before wave 2 |
| BI (D-560) | none | Fabric F2, VNet data gateway, 10 Pro licences (ASSUMPTION) | same |
| Redis | none | Managed Redis Balanced B3, HA | B3, HA |
| Blob | two small accounts | two accounts | two accounts, photos in RA-GZRS |
| ACR | Standard | Standard | Premium, geo-replicated to East Asia |
| Log Analytics ingestion (ops + sync) | 0.3 GB a day | 2.1 GB a day | 5.5 GB a day |
| Azure Load Testing | none | one test window (T-7-51, T-7-52) | S1 to S10 windows (s8.4) |

DELIBERATE CHANGE to D-06, for sign-off through doc 14: geo-redundant backup is ON from the creation of the production server at 7a (it can be set only at creation, D-569) instead of "at the full fleet" or "at wave 1", because it costs about USD 28 a month at wave-1 size (GRS price assumed twice LRS, unverified) and gives the pilot and wave 1 a fallback; and the cross-region replica starts before wave 2 so the 7c entry rule "4d exited" has a production DR to match the drill.

| Tier | Users | Worst-case rows/s (ST-07 scaled) | Rows/s gate at the tier | SKU IOPS cap | Reading |
| --- | --- | --- | --- | --- | --- |
| Pilot | 15 SRs (0.2 percent) | 6 | 1.5 x the tier | 3,750 | no capacity question |
| Wave 1 | 1,000 SRs (11.8 percent) | 392 | S4 at 1,500 simulated SRs = 588 rows/s | 6,400 | comfortable; the D4 cap binds before the 12,000 IOPS of the 1,024 GiB disk (s3.5) |
| Full fleet | 8,500 SRs | 3,333 | S4 at 12,750 SRs = 5,000 rows/s; probe to 8,000 rows/s | 12,800 | the gate that can fail |

### 3.2 Quota requests (G-scale-11)

The default quotas depend on subscription age and type (Learn), so none is assumed. The requests go in during 0a and are re-checked by T-7-55; the approval lead time is not stated in the sources read: unknown, plan 4 weeks (ASSUMPTION) and track each request as a Phase 0 task.

| Quota | Scope | Request | Arithmetic |
| --- | --- | --- | --- |
| ACA consumption cores, prod environment | one environment | 64 before wave 1; 128 for the full fleet | max replicas: api 30 x 1 + auth 12 x 2 + worker 10 x 1 + web 6 x 0.5 + jobs 8 x 1 = 75 vCPU; a canary runs two revisions at once, so 128 leaves 1.7 x; alert at 80 percent |
| ACA consumption cores, staging environment | one environment | 128 during test windows | the same shape (D-421) |
| Managed environments | subscription and region | 2 per subscription (prod SEA and DR East Asia; staging and dev in the non-prod subscription) | one per environment |
| PostgreSQL vCores, Southeast Asia | subscription and region | 64 if one subscription (50 in use at the peak: prod 8 + 8 standby + 8 replica = 24; staging in a test window 8 + 8 + 8 = 24; dev 2); 32 each if prod and non-prod are separate subscriptions | D-133 asks at least 48; whether a standby counts against the quota is unverified, so it is counted |
| PostgreSQL vCores, East Asia | subscription and region | 24 | replica D8 now (8); D8 plus an HA standby after promotion = 16 |
| SSD v2 capacity | subscription and region | default 32 TiB is enough | Learn |
| Azure Load Testing engines | resource | 10 now; 30 for S1 at 1.5 x the fleet | 9,842 users / 500 per engine = 20; 14,763 / 500 = 30. D-133 says at least 10: S1 and S2 are idle-heavy (one request per user every 4 minutes), so a calibration run decides the users per engine; request 30 if it stays near 500 (OI-18-08) |
| Storage accounts | per region | 4 | two per environment, within the 250 default (Learn) |

### 3.3 Cost (list prices, southeastasia, 730 hours, USD a month)

Prices come from the Azure Retail Prices API on 2026-10-04: PostgreSQL D2ds_v5 0.244, D4 0.488, D8 0.976 per hour; SSD v2 storage 0.138 per GiB-month; backup LRS 0.095 per GB-month; ACA consumption vCPU active 0.1224 and idle 0.0144 per hour, memory 0.0144 per GiB-hour; Dedicated Plan Management 0.10 and environment private endpoint 0.14 per hour; Managed Redis B3 0.081 per hour; Front Door Standard base 35 and Premium base 330 per month; Log Analytics ingestion 2.99 per GB; Blob Hot RA-GZRS 0.0588, Cool RA-GZRS 0.0309, Cold RA-GZRS 0.0119 per GB-month; ACR Premium 1.67 per day per unit. HA doubles PostgreSQL compute and storage. Unverified: the Redis HA price, GRS backup price, Front Door traffic price for the Bangladesh edge, and the Azure Load Testing price. Reservation (price API, priceType Reservation): Ddsv5 is USD 641 per vCore for 1 year against 0.122 x 8,760 = 1,069 pay-as-you-go (40 percent off) and USD 1,282 for 3 years (60 percent off).

| Line | Pilot | Wave 1 | Full fleet | Notes |
| --- | --- | --- | --- | --- |
| PostgreSQL primary (compute, storage, backup) | 650 | 1,020 | 1,780 | D2 HA / D4 HA / D8 HA on 1,024 GiB; HA doubles compute and storage; was 200, 810 and 1,640 on 128, 256 and 512 GiB without HA at the pilot (D-568, D-569) |
| PostgreSQL in-region replica | - | 500 | 850 | same SKU as the primary, 1,024 GiB (was 390 and 780) |
| PostgreSQL cross-region replica | - | - | 850 | D8 plus 1,024 GiB, the same SKU as the primary (D-522); was 780 on 512 GiB and 430 for a D4 |
| Container Apps (api, auth, worker, web, jobs) | 160 | 880 | 1,000 | all replicas billed at the active rate except `auth` mostly idle; free grants (180,000 vCPU-s a month) ignored |
| ACA fixed fees (private endpoint, Dedicated Plan Management), prod and DR | 175 | 175 | 350 | Private Link from the pilot (D-573); DR environment added at the full fleet |
| Managed Redis B3, HA | - | 120 | 120 | HA price unverified, doubled |
| Front Door | 340 | 360 | 430 | Premium base 330 from the pilot (D-573; was 40 and 60 on Standard) plus traffic, WAF and 255 GB for an APK release (egress price by zone, unverified) |
| BI: Fabric capacity F2, VNet data gateway, 10 Power BI Pro licences (D-560) | 400 | 400 | 400 | ASSUMPTION, unverified list prices (about 263 for F2 pay-as-you-go and about 140 for ten licences), priced at 0c; needed from 4c |
| Blob (both accounts) | 5 | 15 | 190 | full fleet at month 12: photos Hot 0.68 TB, Cool 1.35 TB, Cold 6.2 TB plus 4.5 M PUT |
| ACR | - | 20 | 100 | Standard, then Premium with one replica |
| Log Analytics (ops and sync workspaces) | 30 | 190 | 490 | at 5.5 GB a day for the fleet |
| Key Vault, Event Grid, queue, alerts, budgets | 15 | 30 | 60 | |
| Prod total | about 1,780 | about 3,690 | about 6,630 | list price (was about 450, 2,500 and 5,950 before round 3; the changes are 1,024 GiB disks and HA from 7a, Premium Front Door and Private Link from the pilot, and the BI path; 5,600 before the DR replica was set to the primary's SKU, D-522) |
| Non-prod steady (staging at D2, dev) | about 590 | about 590 | about 590 | staging D2 plus small ACA; dev stoppable |
| Test windows | - | about 25 per window plus Load Testing | about 75 per window plus Load Testing | D8 HA plus replica for 8 h is USD 23; ACA at maximum for 8 h about USD 50; Azure Load Testing price unverified |
| Reservation | - | none | PostgreSQL compute is about USD 2,850 of the total (32 vCores with the same-SKU DR replica); a 1-year reservation is 40 percent cheaper (price API), about USD 1,000 a month, and a 3-year one 60 percent | D-426: buy only after wave 1 has been stable for 30 days and 4d has confirmed D8 and D4 |

Cost drivers and corrections. The largest lines are PostgreSQL (51 percent at the full fleet) and Front Door Premium. Observability is not the sleeper lens-scale feared: at 0.5 M requests a day and sampling it is about 9 percent. Two corrections to lens-scale: the 150 M requests a month is 13 M (s1.3), and the Redis memory need is 0.55 GB (s2.7). One cost trap outside Azure: bulk server-side reverse geocoding of every attendance event would be 17,000 calls a day, and at a per-request price in the range of several USD per 1,000 calls (unverified, Google Maps Platform) is on the order of USD 2,500 a month; D-425 forbids it (on demand only, cached by coordinates rounded to 3 decimals, billing alert on the key).

Governance (D-426): an Azure budget per environment with alerts at 80 and 100 percent; tags `env`, `owner`, `cost-center`; a monthly cost review in the war-room agenda during waves; the Log Analytics cap and the Google key budget alert are separate controls.

### 3.4 Autoscale and pre-scale rules

| App | Rule | Target | Behaviour to plan with | Arithmetic |
| --- | --- | --- | --- | --- |
| `api` | HTTP, requests per second per replica | 30 | evaluated every 15 s; up-step doubles (3 to 6 to 12 to 24 to 30); down after a 300 s window; replica start time 30 to 60 s (ASSUMPTION) | ST-01 peak 131 req/s needs ceil(131 / 30) = 5 replicas; at 3 replicas each carries 44 req/s against an estimated 150 to 300 req/s a vCPU (lens-scale ASSUMPTION), so min 3 is safe without pre-scale; max 30 = 900 req/s = 6.9 x ST-01 |
| `api` | cron (pre-scale) | 8 replicas 06:15 to 10:00 and 16:45 to 20:00 Dhaka | removes cold-start and JIT variance; leaves headroom for a lost zone; schedule from cfg.ops.prescale_schedule at deploy | 8 replicas = 3 per zone minus one zone still 5 |
| `auth` | HTTP, concurrent hashes | 8 per replica (limiter 4 plus queue) | extra replicas only on wave mornings | ST-03 12.3 logins/s x 75 ms = 0.9 busy; 4 per replica x 2 replicas = 106 logins/s capacity |
| `worker` | KEDA postgresql, unprocessed outbox rows | 200 per replica | polling 30 s default for non-HTTP rules (Learn); the worker also polls its own queue every 5 s (D-140) | trickle backlog about 0 to 100 rows; a 30-minute worker outage leaves 38,000 rows; 10 replicas x 66 recomputes/s (ASSUMPTION 15 ms) drain it in 58 s |
| `worker` | KEDA azure-queue, photo events | 100 messages per replica | at-least-once; idempotent link | 21 events/s peak |
| `web` | HTTP | 50 | pre-scale at 16:45 for 1,500 viewers | 25 pages/s x 6 queries; cache hit target 90 percent |
| Jobs | cron, parallelism | s4.7 | a job that fails mid-chunk resumes from its chunk | |

Rate limits are in s4.4. The pre-scale schedule is DEPLOY-TIME data (D-552, G-qa-84): `cfg.ops.prescale_schedule` is marked effect R in the registry, is not console-editable at runtime and reaches the template only by a pipeline run, because editing a scale rule creates a revision (Learn); the console shows it read-only with the date of the last deploy. An urgent same-day change is a break-glass `az containerapp update` recorded in the ops journal. Replica counts have numeric safe ranges per app: `api` 3 to 30 (pre-scale 4 to 12), `auth` 2 to 12 (6 to 8 on wave mornings), `worker` 1 to 10 (pre-scale 2 to 5), `web` 2 to 6 (pre-scale 3 to 6), and a pipeline check rejects a value outside them or above the quota of s3.2.

### 3.5 When to move to the next SKU (D-410)

| Signal (measured in S4 and in production) | Warn | Move |
| --- | --- | --- |
| Primary CPU, 15-minute average at 8,000 rows/s or in the worst real evening | 60 percent | above 80 percent: D8 to D16 (D16ds_v5 HA is about USD 2,850 for the primary; with a symmetric D16 replica the fleet total rises by about USD 2,100 a month) |
| Data IOPS against provisioned | above 60 percent of provisioned: buy IOPS up to the SKU cap | above 60 percent of the SKU cap (7,700 on D8): next SKU |
| Catch-up ack p95 | 6 s | above 8 s (SLO): find the cause; scale if it is CPU or IOPS |
| PgBouncer `cl_waiting` | any for 1 min | raise `default_pool_size` 20 to 30 once; then fix the slow query |
| Replica lag | 30 s | above 60 s for 10 min: degraded mode (s6.4); a same-SKU replica is the only size that keeps up with WAL replay (the DR replica too, D-522) |
| Worker backlog age | 2 min | above 5 min: raise worker max, check for a poison row |
| Storage used | 60 percent, or a projected-full date within 90 days | 70 percent: grow now (no autogrow); first grow planned by month 6 (D-520) |

At wave 1 the same rule applies to D4: the 1,024 GiB disk has a 12,000 IOPS baseline against the D4 cap of 6,400, so S2 and S3 at 1.5 x the wave decide only whether to move to D8 (D-568). Every move waits for disk hydration to finish and checks it first (s2.6).

Proved by: T-4-51, T-4-52, T-4-53, T-4-54, T-7-51, T-7-55, T-7-56, T-7-57.

## 4 Ingest, aggregation and bundle capacity design

### 4.1 Decision: synchronous ingest, not a queue (D-61)

| Criterion | A. Synchronous insert, outbox after commit (chosen) | B. Service Bus queue before the database | C. Event Hubs log before the database | D. Storage queue before the database |
| --- | --- | --- | --- | --- |
| What the response says | authoritative accepted counts, because rows are committed (D-384) | "accepted for processing"; counts are not true yet | same as B | same as B |
| Reconciliation screen | works from the same response | needs a second call or polling: an extra round trip and radio wake (R4) | same as B | same as B |
| Failure modes added | none | dual write (API to queue, queue to DB), duplicate delivery, poison messages, a dead-letter queue nobody sees | partitions, consumer lag, ordering across a visit family | visibility timeout, 64 KB message limit on batches |
| Capacity needed | 40 batches/s at the 8,000 rows/s gate x 60 ms = 2.4 busy connections of 8 vCPU | not a limit; the queue is idle capacity | idle capacity | idle capacity |
| Cost | none | paid per namespace (unverified) | paid per throughput unit (unverified) | small |
| Verdict | adopt | reject: breaks the truthfulness of reconciliation | reject | reject for ingest; adopted only for photo events (D-424) |

The queue belongs after the commit. The ingest transaction writes one outbox row per batch; the worker drives aggregation, the geo re-check, plausibility flags, photo linking and the submit-settle evaluation from it. Each step is an idempotent recompute, so exactly-once delivery is not needed.

### 4.2 Ingest transaction (F-API-006, F-SYS-048, F-SYS-055)

| Step | Action | Notes |
| --- | --- | --- |
| 1 | Edge and envelope checks | body at most 1 MiB compressed, 8 MiB decompressed with a 20:1 ratio guard, at most 500 rows, memo lines at most 60, unknown JSON keys rejected, schema_version N, N-1, N-2 (D-116, D-150) |
| 2 | Authenticate | ES256 verify; 60 s grace on `/sync/batch` for a token expired by seconds (D-101); device proof (D-104); per-device limiter (s4.4) |
| 3 | Replay lookup | Redis, then `sync_batch.response`, keyed by (device_id, batch_uuid); a hit returns the stored response and touches no row; the same batch_uuid with another row set returns 409 (D-62) |
| 4 | Begin | `SET LOCAL app.user_id` for row-level security (D-106); transaction-scoped settings are safe through PgBouncer |
| 5 | Registry | multi-row insert into the ingest registry with `ON CONFLICT DO NOTHING RETURNING`; duplicates are identified, a payload-hash mismatch on a known uuid is a conflict; a voided uuid is rejected `voided_by_admin` (D-22) |
| 6 | Typed inserts in rank order | parents before children inside the batch; cheap arithmetic checks reject; price, geo and plausibility checks are flags done by the worker (D-117) |
| 7 | Fast path and slow path (D-411, D-521) | the whole batch runs in one savepoint with multi-row statements; on any exception the savepoint rolls back and the batch is BISECTED: split in halves, each half retried in its own savepoint, depth at most 6, never more than `cfg.sync.max_savepoints_per_tx` (60) savepoints in one transaction. A savepoint per record is forbidden: more than 64 subtransactions in one transaction overflow the 64-entry subxid cache of the backend, after which every snapshot visibility check on the primary falls back to pg_subtrans and contends on its SLRU for every concurrent query (the 17:00 wave, the dashboards and the worker all feel it). A record that throws becomes `rejected(server_error)`, a `sync_rejected` row and one Sentry event per error class per hour, never a 500 (D-65). Alerts on `pg_stat_slru` subtransaction counters and on `pg_stat_database` subxact overflow (Sev2, RB-06) |
| 8 | Outbox | one outbox row per batch with the business dates and route ids touched; one dirty-key upsert per (business_date, route) |
| 9 | Side writes | `route_log` upsert once per route per batch with first and last semantics, never a per-request counter; device observed state gated to once per 10 min (D-138, G-scale-09) |
| 10 | Persist the response | `sync_batch.response` is written inside the transaction (D-62, G-sre-14) |
| 11 | Commit, then cache | Redis replay copy is written after commit; response carries `X-Server-Generation`, `X-Config-Version` and `hold_s` |

Expected database time per 200-row batch: 20 to 60 ms (lens-scale ASSUMPTION); T-1-51 measures it with k6 and T-4-53 at fleet scale. A crash between steps 10 and 11 replays from the database, never from a Redis entry for a rolled-back transaction (T-0-51).

### 4.3 Timeout ladder

| Layer | Setting | Rule |
| --- | --- | --- |
| Front Door origin response | 60 s | longer than every API path |
| API request timeout | 25 s | the API answers before the edge does, so the device sees an API envelope, not an edge page (D-81) |
| Statement timeout | api 15 s, auth 5 s, worker 10 min, web 60 s, export 10 min | D-139 |
| `lock_timeout` | 3 s for api and auth | a lock wait becomes a 503 with `Retry-After`, never a queue |
| PgBouncer `query_wait_timeout` | 5 s | a saturated pool answers fast |
| Transient database errors (57P01, 08006, 40001, 40P01) | retried inside the request up to 3 times with 20 to 100 ms jitter; a connection that stays down for 10 s returns 503 with `Retry-After` 5 to 30 s | lens-scale held requests for 2 minutes, which exceeds the 60 s edge timeout; devices ride out the 60 to 120 s failover on their own backoff (doc 17 D-381) |
| Idle in transaction | 30 s | kills a leaked transaction |

### 4.4 Rate limits and load shedding

Three layers plus brakes. A per-IP control alone is harmful here: carrier-grade NAT can put thousands of SRs behind one address (G-scale-04, D-116).

| Layer | Scope | Limit | Action | Derivation |
| --- | --- | --- | --- | --- |
| L1 Front Door WAF custom rule | per socket IP, 5-minute window | `/v1/sync/*` 60,000; `/v1/auth/*` 20,000; `/v1/media/*` 20,000; web 20,000 | Log in the first wave, then Block | worst case 50 percent of the fleet behind one address (ASSUMPTION; the carrier mix is a census question, Q31) x 150 req/s peak = 75/s x 300 s = 22,500, so 60,000 is 2.7 x; auth: 12.3 logins/s x 5 requests x 50 percent x 300 s = 9,200, so 20,000 is 2.2 x. High thresholds are enforced near exactly (Learn); low ones leak |
| L2 API per device and per user | sliding window in Redis | 120 per minute per device, 300 per minute per user (cfg.api.rl.*, D-116) | 429 with `Retry-After` U(5, 60) s | a device sends at most 12 batches a minute (5 s debounce) plus about 20 other requests, so 120 is about 4 x |
| L3 API per-replica in-flight limiter | 64 concurrent `/sync/batch` per replica (cfg.api.inflight_batches_per_replica, proposed) | | 503 with `Retry-After` 5 to 60 s | expected in flight is 6 to 20; 64 protects the pool |
| L4 `auth` hash limiter | 4 concurrent hashes per replica, queue at most 2 s | cfg.auth.hash_concurrency_per_replica | 503 with `Retry-After` 5 to 30 s | memory bound 256 MiB |
| Brake 1 | `hold_s` in a 200 response | cfg.ops.sync_hold_s, 0 to 900 s (proposed), randomised per device | devices pause uploads; capture continues | emergency, C3 |
| Brake 2 | hold by app version at the edge | cfg.ops.sync_hold_by_version (D-130) | Front Door rule on `X-App-Version` returns 429 for one build; reversible in a minute; auto-expiring | the only exception to "never block upload" |
| Brake 3 | `read_only_mode` | cfg.ops.read_only_mode (proposed) | writes return 503 with `Retry-After` during a migration | |

Semantics for the client (doc 17 D-81, D-381): 429 means "you are too fast", 503 means "the server is shedding"; both are retryable with the same batch_uuid and `Retry-After` plus or minus 20 percent; neither is terminal; a response without the API envelope is a transport failure. For the SLO, a 429 from L2 is excluded (client misuse) and a 503 from L3, L4 or a pool wait is bad (s6.1). When Redis is down L2 is off for field paths (fail open); L1, L3 and L4 stay in force.

### 4.5 Outbox worker and aggregation (D-61, D-140, D-412)

| Mechanic | Rule |
| --- | --- |
| Claim | `FOR UPDATE SKIP LOCKED`, 500 rows per claim; poll 5 s between 06:00 and 23:00 Dhaka, 60 s otherwise; claim timeout 300 s so a worker killed by a deploy releases its items |
| Unit of work | one (business_date, route) slice per transaction, target under 100 ms: read the route-day raw rows, then `INSERT ... SELECT ... ON CONFLICT DO UPDATE ... WHERE excluded IS DISTINCT FROM current` for route-SKU, outlet, route and month-to-date rows |
| Rollups | zone, territory, division, wing and national rows are recomputed from their child rows for touched nodes at most once per cfg.agg.rollup_min_interval_s (15, proposed); no row is incremented |
| Late and edited data | the dirty key carries the business date of the data, so a 23:55 sale synced at 00:10 recomputes yesterday; a memo edit recomputes the same slice; a retry recomputes the same value |
| Post-commit work on the same item | server geo re-check (F-SYS-012), plausibility flags (F-SYS-013), photo link by event, submit-settle evaluation (D-64) |
| Failure | an item that fails 5 times becomes a dead item shown on sync-health with a one-click re-drive; staleness per route-day is `max(received_at) - computed_at` and alerts above 5 min (G-sre-17) |
| Repair | the nightly reconcile (02:15) and `dw.rebuild(range, scope)` at low priority fix a bad formula without touching the transaction log |
| Capacity | NOT a fixed 15 ms (D-528, G-qa-53): one recompute writes about 9 daily aggregates, about 3 facts and a restatement row per changed key, so ms per key is measured on a synthetic day (gate T-4-54: p95 ms per key, dead tuples, catalog bloat) and the measured value replaces the placeholder; the arithmetic with the ASSUMPTION of 15 ms is 300,000 recomputes a day x 15 ms = 4,500 CPU-s, about 0.16 core averaged over 8 h, one worker replica covers the trickle peak (21 x 15 ms = 0.3 core); at 100 ms per key the trickle peak is 2.1 cores and the evening catch-up (about 6,000 batches in 20 min touching about 6,900 route keys plus cascades) needs about 6 worker replicas, which is why the replica maximum is set from the measurement. The function uses no temp table (doc 16 s8.6) |
| Reads | dw only, on the replica, with "as of"; live-state tiles (route_day, final-submit validation, device page) read the primary at a budget of 50 queries/s (D-128) |

### 4.6 Partition and storage operations (D-23, D-131)

| Operation | Rule |
| --- | --- |
| Partitions | monthly RANGE by business_date on the tables of D-23; a job creates the next 3 months on the 25th at 01:30 Dhaka; a default partition exists and an alert fires if it holds any row (G-sre-24); `ensure_partitions()` failing silently is the failure this catches |
| Archive | monthly after 13 months: write a manifest, upload, verify counts and hashes, then detach and drop; never drop before the verified upload |
| Registry | the ingest registry is the hottest index: 3.0 M inserts a day, about 135 M rows and about 12 GB at the 45-day prune (the 1.2 B rows and 120 GB of the first draft assumed no prune, doc 16 s6.1, D-520, OI-18-04 resolved), random UUID v4 keys (G-sre-26; v7 is not adopted, D-21). Doc 16 owns the partitioning and retention; the capacity test T-4-53 measures read IOPS from random leaf pages at 3,300 to 8,000 inserts a second against the 60 percent rule (G-18-01, OI-18-04) |
| Storage | alert at 70 percent, grow online; SSD v2 has no autogrow and the server turns read-only at 95 percent (Learn) |
| Statistics | `ANALYZE` after a failover (Learn) as the last step of RB-12 |

### 4.7 Job DAG with deadlines (D-71, D-418; G-sre-08, G-sre-09)

Every job is an ACA Job on a cron in UTC, writes a `job_run` row (job, business_date, status, coverage, started, finished; doc 16 names the table), is idempotent per chunk and resumable, and refuses to run when its precondition row is missing. There is no external orchestrator: seven jobs and a gate table do not justify one.

| # | Job | Dhaka time (UTC cron) | Precondition | Work | Parallel | Deadline | On failure |
| --- | --- | --- | --- | --- | --- | --- | --- |
| J1 | Wave-night delta import | wave nights from 20:00 | the previous import run is `reconciled` | docs/11 step 3 through the doc 16 s12 importer | 1 | `reconciled` by 23:00 (17:00 UTC) | wave is deferred at the go or no-go (not the next day at 21:00) |
| J1b | Late delta (DL-1b) | day T at `cfg.cutover.late_delta_time` (06:00), then T+1 to T+3 | J1 reconciled for the wave | dues, loyalty and outlet rows changed in Apsis since the cut (doc 16 s12.6); the changed users' bundles receive a delta | 1 | 06:20 | the straggler sheet carries the rows; Sev2 |
| J2 | Next-WORKING-day bundle snapshots | ordinary nights 22:00 (0 16 * * *); on a wave night it is TRIGGERED BY J1 `reconciled`, not by the clock (D-557) | the calendar says the next working day exists; on wave nights J1 `reconciled` | per-route outlet blocks once (about 6,953 routes), then per-user assembly; targets read from dw; a last-working-day evening builds the snapshot of the first working day after the break (D-584) | 8 | ordinary 22:30; wave nights 23:30 (the go or no-go moves to 23:30) | retry once; Sev2 |
| J2w | Wave pre-snapshot | wave eve, 14:00 | the wave's `rollout_wave_member` list exists | the D snapshots of the wave's users from the state at 14:00 (stage `pre`), so the pre-bind pre-fetch at the distribution house has a snapshot to fetch; the final J2 replaces it (stage `final`) and phones receive the difference as a delta | 4 | 14:30 | the pre-fetch tick is a human check against the J2w row; Sev2 |
| J3 | `route_day` and `supervisor_day` creation | 00:05 (5 18 * * *) | none (calendar and assignments) | planned routes for D from visit days, working-day calendar and overrides; denominators exist from midnight | 1 | 00:15 | Sev1 (Login % denominator missing); creation on first contact stays as the safety net (F-SYS-056) |
| J4 | PostgreSQL maintenance window | Friday 01:00 to 02:00 | none | Azure-managed | n/a | n/a | n/a |
| J5 | Re-aggregation and reconcile | 02:15 (15 20 * * *) | J3 done for D-1 | rebuild D and D-1 slices in chunks of 1,000 routes; `dw.reconcile()` | 4 | 03:15 | resume from the chunk; Sev2 |
| J6 | Snapshot refresh for dirtied users | 03:30 (30 21 * * *) | J5 done | users whose inputs changed since J2: assignments, prices, offers, targets, month-to-date, config, approved outlet edits | 8 | 04:15 | keep J2 snapshots plus live delta |
| J7 | Coverage check | 04:30 (30 22 * * *) | J6 done | assert `valid_for_business_date = D` and `users_written = users_in_scope`; emit coverage percent | 1 | 04:35 | coverage 95 to 99 percent: Sev2; below 95 percent: set the bundle hold (serve yesterday's snapshot plus delta) and page L2 |
| J8 | Photo sweep | 23:30 | none | link blobs missing an event; report photo-pending over 24 h; remove orphan blobs after 7 days | 1 | 00:30 | Sev3 |
| J9 | Partition create and archive | 25th 01:30; monthly | none | s4.6 | 1 | 03:00 | Sev2 |
| J10 | Cert and hostname probe | 06:00 daily | none | TLS chain and expiry of the custom domain and the default hostname; alert at 30, 14, 7 days (Azure Monitor has no Front Door certificate-expiry metric) | 1 | 06:10 | Sev2 |

Wave-night timeline (D-557, G-qa-89). The first draft let J2 start at 22:00 with the precondition "on wave nights J1 reconciled", while J1's deadline was 23:00 and the go or no-go at 23:00 required J2 done: a J1 that reconciled at 22:45 left J2 no valid start. Doc 17 s6.5 also said phones fetch D+1 from 20:00 "if the 22:00 snapshot exists", while the pre-bind readiness line required tomorrow's bundle at 20:00, before any D+1 snapshot existed. The fix: J2w builds a wave pre-snapshot at about 14:00 on T-1 so the distribution-house pre-fetch is possible; J1 keeps its 23:00 deadline; J2 is triggered by J1 `reconciled` with a 23:30 deadline and the go or no-go moves to 23:30; J6 (03:30) and J1b (06:00) change the bundle again, so day one is a small DELTA, not "refresh plus 304" (about 25 KB gzip per user, about 29 MB for the 1,155 users of wave 1, about 106 MB for 4,250; sized in S1' below). T-7-161 replays the wave-night timeline with J1 finishing at 22:55.

The J2 estimate: 9,842 users x about 50 ms of SQL and gzip each, over 8 parallel replicas, is about 1 minute plus start-up; lens-scale said about 2 minutes; ASSUMPTION, measured by T-0-50 and T-1-36. The 22:00 snapshot carries month-to-date figures that miss late Wi-Fi uploads between 22:00 and 02:15; J6 refreshes the users whose routes received rows, and a device that pre-fetched at 22:00 receives the correction as a delta at login.

### 4.8 Bundle serving (D-129, F-API-005, F-SYS-067)

| Item | Design |
| --- | --- |
| Normal path | `GET /v1/sync/bundle`: verify the token; read the `bundle_snapshot` row; stream the gzip snapshot from Redis or Blob with an `ETag`; `If-None-Match` equal returns 304; `?since=` returns a small live delta; one `bundle_download` row is the login event (first of the Dhaka date, 304 included, D-30, D-385) |
| Database cost | one read and one insert per request, against 10 to 40 scoped queries per bundle if generated live (1,300 queries/s at ST-01): the reason for pre-generation |
| Invalidation | by scope on assignment, outlet, price, offer, target or config change, debounced 60 s, capped at cfg.bundle.regen_max_per_s (20); user-level bumps coalesced per 5 min (D-100) |
| Failure | pre-generation failure serves yesterday's snapshot plus delta (`bundle_hold`), never live generation for the fleet (D-129); a single user without a snapshot is generated live and counted in `aron_bundle_served_total{source="live"}`, alert above 5 percent |
| Paging | sections above 2,000 rows are paged at about 1,000 rows, 2 MB gzip hard cap (doc 17 D-386); the AMO of a 54-route zone is the case that needs it on day one |
| Egress | 0.39 GB a morning (s1.5); APK and thumbnails never pass the API |

### 4.9 Reports, exports and heavy reads (D-429; F-SYS-064)

| Rule | Value |
| --- | --- |
| Interactive reports | read dw on the replica; up to 50,000 rows (cfg.report.interactive_row_limit, proposed); p95 at most 5 s |
| Exports | above the limit, and every Excel or PDF over 10,000 rows, run as an ACA Job with its own `app_export` role on the replica; concurrency 8 by KEDA; the result goes to the content account `support/exports/` with a 24 h SAS and a log row; Excel generation of the 734,789-outlet Browse Retailer list is the memory case: 2 vCPU and 4 GiB per job |
| Concurrency at peak | 50 concurrent heavy readers (lens-scale); the queue shows position and time; the interactive path is never blocked by an export |
| Replica protection | `statement_timeout` 10 min for exports and 60 s for web; a long export that holds a snapshot can delay vacuum on the primary, so `hot_standby_feedback` stays off and a conflict cancels the export, which retries (ASSUMPTION; tested in S5) |

Proved by: T-0-50, T-0-51, T-1-51, T-1-53, T-1-54, T-2-51, T-2-52, T-4-53, T-4-54, T-4-58, T-4-59, T-7-59.

## 5 Reliability

### 5.1 Failure modes on launch day

Sources: lens-scale s4 (16 modes) and the SRE critic's incident game-out (17 incidents), merged and re-based on s1. "Detection" gives the signal and the time to detect; Sev is in s6.6. Blast radius is for the full fleet; at wave 1 divide users by 8.5.

| FM | Failure mode | Detection (signal, time) | Blast radius | Mitigation (design) | Gate |
| --- | --- | --- | --- | --- | --- |
| FM-01 | Bundle pre-generation fails, is partial, or builds for the wrong date (cron timezone error) | J7 coverage and the date assertion at 04:30; wrong-date snapshots otherwise look like success (every device gets 409 `new_business_date`) | 9,842 users; live generation would be about 1,300 scoped queries/s | J2 and J6 snapshots, ETag and 304, refresh-token login, `login_jitter_s` on automatic refresh, `bundle_hold` below 95 percent coverage, regeneration cap | T-0-50, T-4-51, T-4-52 |
| FM-02 | First-morning storm: password hash, bind, full bundle, APK; Argon2id memory times concurrency | `auth` memory above 80 percent, restart count, 5xx above 2 percent, within 1 to 2 min | the whole switch on a full-fleet day with no pre-bind day | pre-bind day (D-126), separate `auth` app, hash limiter 4 per replica with 503, APK from Blob, wave 1 as the rehearsal | T-7-57 |
| FM-03 | Database connection exhaustion | PgBouncer `cl_waiting`, `too many clients`, within 1 min | ingest and bundles | pools 8 and 4 per replica, `default_pool_size` 20, `query_wait_timeout` 5 s to 503, no session state; at max replicas 240 client connections against 5,000 | T-4-53 |
| FM-04 | Hot rows: aggregate rows, device last-seen, `route_log`, memo sequence, `final_submit` | lock waits, `deadlock_count`, `lock_timeout` errors | ingest latency | recompute by key, 10-minute observed-state gate, one `route_log` upsert per route per batch, device-prefixed memo numbers (D-35), `final_submit` once-only by primary key | T-2-51 |
| FM-05 | Long transaction (recompute for everyone, big export) | transaction age, replica lag, WAL growth | all writers, replica | one slice per transaction, nightly chunks of 1,000 routes, role statement timeouts, `idle_in_transaction_session_timeout` 30 s, exports on the replica | T-4-54 |
| FM-06 | Retry storm: duplicate batches, a client that ignores `Retry-After` | 429 or 503 above 5 percent, `X-Batch-Attempt` histogram, replay hits | fleet: 8,500 devices at 1 req/s is 8,500 req/s at the edge | batch replay without touching rows, backoff 2 s to 300 s with jitter, L2 and L3 limits, `hold_s`, hold by version | T-1-51, T-2-52, T-2-56 |
| FM-07 | One poison row stalls a device | 5xx rate, `rejected(server_error)` count, pending-rows p95 | every device holding that row type | batch-level savepoint with bisection (never one per record, D-521), quarantine, client skip-ahead after 5 | T-1-53 |
| FM-36 | One bad client build puts a poison row into EVERY batch: every batch of every device takes the slow path at 17:00 while the dashboards and the worker are hot (D-521, G-qa-45) | `pg_stat_slru` subtransaction counters, subxact overflow, ingest ack p95, `rejected(server_error)` rate per `X-App-Version` | the fleet on that build | bisection keeps savepoints at most 60 per transaction and about 7 rounds per poisoned batch; `cfg.ops.sync_hold_by_version` stops the build at the edge; rescue release | S11, T-1-151, T-4-163 |
| FM-37 | A 17:00:00 check-out spike (every SR who finished early presses check-out at the same second) lands with the supervisors' dashboards (D-505) | ingest ack p95 at 17:00 to 17:02, `aron_checkout_spike_rps` | the evening wave | client jitter 0 to 90 s, capacity line ST-06b, S3b | T-4-155 |
| FM-38 | The absolute refresh lifetime expires for a whole wave cohort on one day: a password-login herd and blocked offline unlock (D-518) | `auth` queue wait, login 503 rate, `needs_relogin` count | one wave cohort | per-family random lifetime, warning from day 75, renewal by device key, 7-day offline grace | T-4-162 |
| FM-39 | Wave rollback or a switched-route estate makes login, submit and day-completion alerts fire falsely (the unswitched routes never log in; a declared off-day looks like an outage) (D-548, D-542) | Sev1 login alert on a wave day or a hartal | the war room | alerts and SH-01 to SH-03 scope over switched routes only; an `emergency_off` declaration suppresses the alerts for its scope and date | T-4-153, T-4-154 |
| FM-08 | `day_submit` reaches the server before its rows, so Submit % (of logged-in) is wrong at 17:00 and a false fleet rollback is triggered | `submit_pending_rows` count; mismatch read after settle | the war-room screen and the rollback decision | `day_submit` last in the sequence, settle rule, trigger reads after settle (D-64) | T-1-54 |
| FM-09 | Access-token expiry wave; clock-skew refresh loops | `/auth/refresh` sawtooth, 401 rate, skew histogram | all online devices, worst at 17:00 | TTL jitter, piggyback refresh, 60 s grace, expiry on corrected time (s5.2) | T-1-55, S10 |
| FM-10 | Urgent push becomes a `GET /config/delta` storm | request spike seconds after a push | fleet | `pull_after_s` jitter, push off in the pilot, pre-scale before an urgent push | T-2-55 |
| FM-11 | Mass re-assignment bumps `scope_version` for 1,000 users | 401 `scope_changed` rate, regeneration queue depth | the fixed users, others if the regeneration worker saturates the primary | regeneration cap 20 per second, bumps coalesced per 5 min, bulk operations frozen 07:00 to 09:30 and 16:30 to 19:30 (D-100) | T-7-59 |
| FM-12 | Config mistake: radius 0, check-out 05:00, `min_version` typo | geo-valid % falls more than 30 points against the same weekday last week within 15 min; config audit | the scope of the change | bounds, canary scope, two-person approval for C3, one-click revert, anomaly watch (doc 19) | T-2-60 to T-2-69 (doc 19) |
| FM-13 | Bad app build: corrupt capture, crash loop, upload loop | crash rate per version, rejection rate per version, a steady 1 req/s per device | one wave | staged APK channel, `min_version` and `blocked_versions` semantics (D-130), edge hold by version, rescue release within 24 h | T-2-56, T-7-53 |
| FM-14 | Bad API or worker deploy; in-flight batches killed; stale claims | canary abort (5xx at most 0.5 percent, p95, rejected rate), claim age | fleet at 10, 50, 100 percent | blue-green weights, replay by `batch_uuid`, claim timeout 300 s, 30 s drain | T-0-51 |
| FM-15 | PostgreSQL zone failover, 60 to 120 s (can exceed 120 s if WAL recovery is behind) | HA state not Healthy for 3 min, connection errors | about 1,200 failed batches in 90 s at 13 batches/s; no selling impact | readiness not liveness, 10 s in-request retry then 503, device backoff, same hostname, `ANALYZE` after | T-4-55 |
| FM-16 | Liveness coupled to the database restarts every replica | restart count | self-inflicted | liveness checks the process only; tolerant startup probe | T-4-55 |
| FM-17 | Redis outage | Redis availability metric, replay-hit drop | none for correctness | every use fails open or reads the source of truth (s2.7) | T-4-55 |
| FM-18 | The edge lies: certificate renewal fails, a managed WAF rule returns an HTML 403, a DNS problem | probe fails 3 times, Front Door 403 count, J10; TLS errors never reach the API | every device, or every device whose payload matches a rule | second hostname after 3 failures, envelope rule (doc 17 D-81), WAF Log mode, expiry probe at 30, 14, 7 days | T-2-57 |
| FM-19 | Region outage (Southeast Asia) | Resource Health, Front Door origin health, probes, within minutes | API, bundles, dashboards, final submit; selling continues offline | offline-first, stale bundle up to 2 days (D-70), cached verifier, DR protocol s5.4 | T-7-54 |
| FM-20 | DR or PITR forgets rows the devices hold as synced | reconcile mismatch after a generation change, digest mismatch buckets | silent loss | server generation carrying `restore_point_utc`, re-send of every synced row acked at or after the restore point minus 6 h whatever the device's own clock says, an outbox purge hold, and a count-plus-UUID-bucket digest (D-63, D-517) | T-1-56, T-1-152 |
| FM-21 | Blob throttling, SAS misuse, key-fetch limits | Storage 503 or `ServerBusy`, SAS p95 | photos only | cached user-delegation key, path-pinned write-only SAS, SDK retries | T-2-53 |
| FM-22 | Replica lag, stale dashboards | `physical_replication_delay_in_seconds` | tiles | "as of" stamp, live tiles on the primary, degraded mode above 60 s | T-4-56 |
| FM-23 | Business-date rollover and late sync | per-date reconcile drift | one day's numbers | dirty key carries its own business date, J5 after the evening tail | T-1-52 |
| FM-24 | Telemetry cap reached or flood | cap-reached event, 80 percent alert | ops visibility only | cap sized 3 x, sync workspace uncapped, adaptive sampling | T-4-57 |
| FM-25 | Aggregation dead item leaves a tile stale | per-key staleness above 5 min | one route to one zone | re-drive, claim timeout, lag histogram | T-4-59 |
| FM-26 | Sync-health page slow at 17:00 | page p95 above 1.5 s | the screen the business watches | replica, response cache, degraded mode | T-4-58 |
| FM-27 | A zone cannot final-submit (TSO absent, null house, API down, mis-click) | final-submit coverage below 100 percent at 21:00 | one zone of 4 to 54 routes | picker tolerates a null house, on-behalf submit with reason, optional auto-close (D-262, Q53) | doc 20 T-3-25 to T-3-28 |
| FM-28 | Quota stop (ACA cores, vCores, Load Testing engines) | quota alert at 80 percent, scale events | scaling stops silently | s3.2 requests before wave 1 | T-7-55 |
| FM-29 | Disk full (no autogrow); read-only at 95 percent | storage alerts at 60 percent, at a projected-full date within 90 days, at 70 percent and at 90 percent (s6.6) | all writes | grow at the 60 percent warning; first grow by month 6 to 2,048 GiB (D-568); RB-51 | T-7-55, T-0-159 |
| FM-30 | Partition creation fails silently; rows land in the default partition | default-partition row count above 0 | performance decay, no loss | alert, J9 | T-0-50 |
| FM-31 | A bad delta import | import control-total diff | the wave's master data | deferral at the 23:00 go or no-go, `rollback_import(run_id)` (doc 16 s12) | T-7-58 |
| FM-32 | Device clock skew; TLS "not yet valid" | skew histogram, TLS errors by Android version | the skewed devices | corrected time, banner above 10 min skew | T-2-54 |
| FM-33 | Photo wave on home Wi-Fi; photos pending | photo-pending above 24 h, link-lag histogram | evidence only | S6, J8 sweep, evidence mobile fallback after 6 h (doc 17) | T-2-53 |
| FM-34 | Support cannot see a device that cannot sync | L1 lookup takes more than 2 min | one call each, 250 to 425 on a full-fleet day (doc 20) | observed headers, `/support/ping`, device-lookup workbook | T-2-58 |
| FM-35 | Maintenance window collides with the nightly chain | overlapping `job_run` rows | one night's re-aggregation | window on Friday 01:00; chain after 02:15 | T-0-50 |

### 5.2 Thundering herds and token expiry

Capacity is rarely the issue (s1.4); synchronisation is. Every herd has a spread mechanism with a number.

| Herd | Size | Mechanism and key | Before and after | Gate |
| --- | --- | --- | --- | --- |
| Morning refresh (ST-01) | 9,842 | snapshots, 304, refresh-token login, cfg.sync.login_jitter_s (120) on automatic refresh only (a user tap is never delayed), pre-scale | live generation 1,300 queries/s becomes 1 read and 1 insert per request | T-4-51, T-4-52 |
| First morning (ST-02, ST-03) | 1,155; 7,350 | pre-bind day, `auth` app, hash limiter, APK on Wi-Fi at T-1 | 12.3 logins/s with unbounded memory becomes 0.26 binds/s on the day before and refresh plus 304 on day one | T-7-57 |
| Access-token expiry (ST-10) | 5,900 online | TTL 60 min plus or minus 10 (cfg.auth.access_ttl_jitter_min), piggyback refresh when under 5 min remain and a request is about to leave, 60 s grace on `/sync/batch`, expiry on corrected time, cfg.auth.min_refresh_interval_s (300) | 9.8 refreshes/s becomes 3.3/s, and standalone refresh about 0; refresh costs about 6 ms of database time, so 10/s is 6 percent of one connection: the risk is loops and false reuse detection, not capacity | T-1-55, S10 |
| Urgent push (ST-11) | 8,500 | `pull_after_s` U(0, 20) for kill switch and `min_version`, U(0, 120) otherwise (cfg.ops.push_jitter_urgent_s, cfg.ops.push_jitter_s) | 8,500 requests in one second becomes 425/s and 71/s; pre-scale first | T-2-55 |
| Re-sync after DR or PITR (ST-08) | 8,500 x 350 rows | U(0, cfg.sync.resync_jitter_s) = 900 s | 8,500 batches of 200 rows in seconds becomes 3,306 rows/s | T-1-56 |
| Re-assignment (ST-12) | 1,000 | regeneration cap 20 per second, coalescing 5 min, freeze windows | 1,000 regenerations at once becomes 50 s | T-7-59 |
| APK release (ST-13) | 8,500 x 30 MB | `wave_pct` 1, 10, 100 percent, Blob behind Front Door cache, never the API | 255 GB staged | T-7-57 |
| Outage recovery (ST-14) | 8,500 | backoff cap 300 s with U(0.5, 1) jitter; periodic WorkManager phase differs per install | 57 first retries/s at most | T-4-55 |
| Absolute refresh expiry (ST-15, D-518) | up to 4,250 families of one wave cohort | per-family random absolute lifetime (cfg.auth.refresh_absolute_days 90 plus or minus cfg.auth.refresh_absolute_jitter_days 15, drawn at mint), a warning from day 75, renewal by device-key proof or a password prompt on Wi-Fi, offline-unlock grace of 7 days after expiry with upload-only behaviour (doc 21 s2.3, s2.7) | 4,250 password logins on one morning becomes about 140 a day over 30 days | T-4-162, S10 |
| 17:00:00 check-out spike (ST-06b, D-505) | up to 5,100 in 60 s | client jitter U(0, 90 s) on check-out and Sales Submit when only the clock gate triggers the upload (doc 17 T7), capacity line of s1.8 | 85 batches/s becomes 34 batches/s | T-4-155 |
| Evening wave (ST-06, ST-07) | 3,400 to 8,500 | 5 s debounce plus family hold; check-out and Sales Submit ride in batches; 200-row batches | covered by the 8,000 rows/s gate | T-4-53 |
| Photo wave (ST-09) | 150,000 | Wi-Fi first, 2 concurrent uploads on Wi-Fi, direct to Blob | 20.8 PUT/s | S6 |

Token policy as implemented (D-101, D-518): access JWT ES256 60 min, web 15 min; refresh token opaque, rotated on each use with a 60 s grace that replays the stored response, sliding 30 days and an absolute lifetime of 90 days plus or minus a per-family random jitter of up to 15 days drawn at mint (so a wave cohort does not expire on one day). A device whose clock is 3 hours ahead would refresh on every request and trip reuse detection; judging expiry on corrected time (doc 17 D-389) removes both. T-1-55 issues 1,000 tokens in one second and expects the refreshes spread over at least 8 minutes and no loop for a device 3 hours ahead.

### 5.3 What each dependency failure does

| Dependency down | Degrades | Keeps working | Alert | Recovery |
| --- | --- | --- | --- | --- |
| Redis | bundle served from Blob (+30 ms), replay from the database, no per-device limiter on field paths, config on a 30 s poll, no response cache | sale, capture, ingest, auth | Sev2 | recreate; nothing to restore |
| PostgreSQL primary (HA failover) | writes fail 60 to 120 s; 503 with `Retry-After` | capture on devices, reads from the replica with "as of" | Sev1 after 3 min | automatic; `ANALYZE` after |
| Read replica | dashboards and reports fall back to "unavailable, last as of" or to the primary for live tiles only | everything else | Sev2 | rebuild; unknown whether it re-attaches after a primary failover (G-18-11) |
| Blob (evidence) | photo uploads queue on the device | sales, sync | Sev3 | SDK retry |
| Blob (content) | APK and thumbnails; bundles fall back to live generation per user at a counted cost | sync | Sev2 | |
| Key Vault (`kv-sign`, `kv-pii`; D-574) | no new secret reads. "Running replicas keep working" is true for cached SECRETS and DEKs, not for token signing: with the delegated signing keys of doc 21 s2.2b each replica signs locally under a 24-hour delegation renewed at 12 h, so minting, refresh and uploads continue through a Key Vault outage or throttle of up to about 12 hours and the upload grant lives 6 h (`cfg.auth.upload_access_ttl_min`); a replica that restarts during the outage cannot obtain a delegation and leaves the minting pool, so replicas are NOT restarted (RB-48). Beyond that margin devices cannot refresh and uploads stop at the 401 even though the data is safe on the phones | running replicas | Sev2; Sev1 when a delegation has less than 3 h left | RB-48; soft-delete recovery is part of the DR drill (D-127); T-4-173 |
| ACR | new replicas cannot pull an image; running replicas continue | running service | Sev2 | geo-replica in East Asia |
| Entra ID or the Postgres token service | new database connections fail after the token lifetime; pooled connections continue | existing pooled traffic | Sev2 | doc 21 owns the fallback credential |
| FCM | urgent config rides the next request instead of a push | everything | none | |
| Google Maps | AMO and TSO map screens fall back to lists (D-08) | SR flavour has no map | none | |
| Sentry | the SDK drops events | everything | none | |
| Log Analytics cap | ops dashboards go blank; sync-health reads Postgres, not logs | the business screen | Sev2 | the sync workspace has no cap |
| Front Door | no ingress; devices use the second hostname after 3 failures | offline selling | Sev1 | see FM-18 |

### 5.4 DR and PITR protocol (D-127, D-63, D-420; G-scale-12, G-qa-18)

Stages. Pilot and wave 1: geo-redundant backup only, ON FROM THE CREATION of the 7a production server (D-569); a region outage means a geo-restore into East Asia (RTO MEASURED by the timed drill T-4-174 on staging before wave 1 and recorded in RK-05 and the day-one checklist; RB-52); devices keep selling and hold up to 3 days of rows (doc 17 D-380). The sponsor accepts the wave-1 DR position in writing (D-569). The earlier staging claimed the replica path, which does not exist until before wave 2, so the path wave 1 actually has was never drilled. Before wave 2: the cross-region replica, the warm East Asia environment and the drill. RTO 4 h and RPO 15 min are D-127 (MUST-CONFIRM by 7c, Q20); RPO equals the replication lag at the forced promotion, so lag above 5 minutes alerts.

DR replica sizing and the lag proof (D-522, G-qa-46). The cross-region replica is the same SKU as the primary (D8ds_v5) because replay is single-threaded and RPO 15 minutes equals the replication lag at the forced promotion. The WAL arithmetic, all ASSUMPTION until T-7-52 measures it on the 1.5 x fleet: ingest writes about 1.5 KB of WAL per row (row about 500 bytes, two indexes, the registry insert of about 90 bytes, headers and amortised full-page images); dirty-queue, aggregate and fact writes add about 40 percent. At the proven peaks:

| Load | Rows/s | WAL MB/s (ASSUMPTION) | Standby replay headroom at about 40 MB/s (doc 18 s2.6) |
| --- | --- | --- | --- |
| Typical evening (ST-06) | 1,125 | about 2.4 | 16 x |
| Worst-case catch-up (ST-07) | 3,333 | about 7 | 5.7 x |
| 1.5 x fleet peak | 5,000 | about 10.5 | 3.8 x |
| Gate probe | 8,000 | about 16.8 | 2.4 x |
| 17:00:00 spike (ST-06b) | 4,216 for 60 s | about 8.9 | 4.5 x |

A D4 replica (6,400 IOPS cap, half the D8 12,800) would replay random full-page-image writes at roughly half that speed, leaving 1.2 x to 1.9 x at the gate probe and a lag that grows through the 17:00 wave, so the replica is the same SKU as the primary. Controls: alert on cross-region lag above 5 minutes (Sev2) and above 12 minutes (Sev1, RPO at risk); a lag gate (maximum 5 minutes at the end of every run) in S7, S8 and the 17:00 wave of T-7-52, measured as the replica replay position against the primary's `pg_current_wal_lsn`, not only in the one-off rehearsal of T-7-54 (T-4-164, T-7-52).

Region outage (RB-13). Times are from the declaration; the budget sums to 3 h 30 min, inside the 4 h RTO.

| Step | Time | Action | Owner | Check |
| --- | --- | --- | --- | --- |
| 1 | 0:00 to 0:30 | Declare: Service Health, Resource Health and probes agree for 15 min and no provider ETA under 1 h; the incident commander decides | IC | decision logged |
| 2 | 0:30 to 0:45 | Forced promotion of the cross-region replica (the only option in a region outage; the loss equals the lag at that moment, Learn) | DB | lag recorded as the RPO |
| 3 | 0:45 to 1:00 | Mint a new `X-Server-Generation` (a row in cfg, read by every API replica) WITH its `restore_point_utc` (the replica's replay position recorded at step 2) and `lost_after_utc`; devices re-send every synced row acked at or after the restore point minus 6 h whatever their own clock says (doc 17 s4.12, D-517); no step raises the window ahead of the flip any more | API | `GET /sync/generation` returns the restore point on a test call |
| 4 | 1:00 to 1:45 | Scale up the East Asia apps from min 0 (api 3, auth 2, worker 1, web 2); images from the ACR geo-replica; secrets from the DR Key Vault; start without Redis (every use fails open) and create it afterwards | infra | readiness green |
| 5 | 1:45 to 2:15 | On the promoted server: FIRST check that disk hydration has finished (Premium SSD v2 refuses compute scaling, storage scaling and enabling HA while the disk is hydrating, and a quick successive failover may leave the server without a standby; Learn, read 2026-10-04: `az postgres flexible-server show` and retry on "disk is still being hydrated"), then scale compute D4 to D8, THEN enable HA after the scale completes (never together), configure PgBouncer and parameters (they do not replicate, Learn), set backup retention, create the new replica later. The step budget gains a hydration wait of up to 30 min (ASSUMPTION, measured in T-7-54), so the RB-13 total is 4 h in the worst case and the sponsor accepts that against the 4 h RTO | DB | `pg_is_in_recovery() = false`; hydration complete; `SHOW POOLS` |
| 6 | 2:15 to 2:30 | Smoke script: login, bundle, a test batch from the lab phone, a dashboard read | QA | all pass |
| 7 | 2:30 to 2:45 | Enable the DR origin in Front Door and disable the Southeast Asia origin; publish a banner through `/config/public` | infra | traffic on the DR origin |
| 8 | 2:45 to 3:15 | Devices see the new generation and re-send within U(0, 900 s) (ST-08); tell supervisors counts may rise (Q54) | support | rows per second below 4,400 |
| 9 | 3:15 to 3:30 | Reconcile: device counts against server totals; verification checklist below | QA | mismatch at most 0.1 percent after settle |

Failback is a planned switchover in a Friday window with no data loss and no generation change (Learn: switchover with virtual endpoints; unverified for a promoted cross-region server, so it is drilled).

Geo-restore into East Asia (RB-52, D-569, G-qa-104). The path wave 1 actually has. Steps: (1) declare as step 1 of RB-13 with the same criteria; (2) restore the latest geo-redundant backup to a NEW server in East Asia at the primary's size (RPO about 1 h, the backup replication lag, Learn); (3) WAIT for disk hydration before enabling HA, scaling or creating a replica; (4) apps from the ACR geo-replica, secrets from `kv-sign-dr` and `kv-pii` restored copies, mint a new generation with `restore_point_utc`; (5) smoke script; (6) switch Front Door origin. The time of every step is recorded in a timed drill on staging (T-4-174; the staging server is created with geo-redundant backup) and the measured RTO replaces "RTO hours: unknown" in RK-05, the ladder of s3.1 and the day-one checklist.

Point-in-time restore (RB-14, RTO 2 h assumed).

| Step | Action |
| --- | --- |
| 1 | Pick the restore time T and write down the lost window (now minus T) |
| 2 | Restore to a new server (time depends on size and WAL: unknown, measured in the drill) and run the verification checklist |
| 3 | Record `restore_point_utc` = T and `lost_after_utc`; a PITR distance above 72 h is unsupported and is run as a data-loss incident (doc 17 s4.12); no window is raised |
| 4 | Repoint the connection secret, mint a generation, restart the apps |
| 5 | Devices re-send under jitter; reconcile; keep the old server read-only for 7 days |

Verification checklist (G-qa-18), run after every restore, promotion and drill:

| # | Check | Pass |
| --- | --- | --- |
| 1 | Row counts per business date and record type: business tables against the ingest registry and `sync_batch` counts | equal |
| 2 | Control totals per zone for the restored dates: memo count, qty_base per category, net_mtk, open dues, against dw and the last reconcile snapshot | differences only inside the lost window |
| 3 | Audit hash chain verifies end to end; last WORM export manifest matches | verified |
| 4 | Memo-number gap report (F-SYS-069) | no unexplained gaps |
| 5 | cfg version and scheduled values against the config audit | equal |
| 6 | Next 3 monthly partitions exist; default partition empty | yes |
| 7 | 200 random registry uuids resolve to business rows | 200 of 200 |
| 8 | Roles, grants, row-level-security policies and PgBouncer settings present | yes |
| 9 | After the re-sync: device against server counts | at most 0.1 percent of route-days differ |
| 10 | Wall-clock time of each step recorded | RTO measured |

Cadence: a restore drill with the checklist every month on staging; a DR promotion drill twice a year and before wave 2 (T-7-54).

### 5.5 Rollback for every change type (G-sre-20, G-sre-21)

| Change | Rollback | Time | Field data in flight | Limit |
| --- | --- | --- | --- | --- |
| API, auth or web revision | traffic weight to the previous revision (multiple-revision mode) | seconds | safe: batches replay by `batch_uuid`; the old revision accepts the new revision's stored responses within one contract version | add live per-revision canary signals (G-sre-19) |
| Worker revision | redeploy previous (single-revision mode) | minutes | safe: idempotent recompute; claims reclaimed after 300 s | |
| Config value | revert = a new version, pushed with jitter | online devices within one trickle cycle (about 4 min), at most 15 min; offline devices at next contact | rows keep `config_version` and the values used | future-dated-only keys cannot be reverted mid-day by design |
| Schema, expand | forward fix only; the previous revision is compatible by construction | n/a | safe | |
| Schema, contract | none; runs only after revision N+1 is everywhere and no build older than N-2 still uploads | n/a | the app's accepted `schema_version` window is the API's, not the database's | `contract-phase` workflow checks both |
| Data: PITR | RB-14 | about 2 h | rows ACKed after T are re-sent from devices (D-63) | up to the re-sync window |
| Data: bad aggregation formula | `dw.rebuild(range, scope)` at low priority | hours | safe | |
| Data: bad import | `rollback_import(run_id)` retracts unreferenced rows, quarantines referenced ones with a report | per run | | doc 16 s12; drill T-7-58 |
| App build | roll forward: a rescue release within 24 h; the local schema is additive and the previous build can open it for one release; `blocked_versions` stops new captures only; PDA to Support export before any reinstall (reinstall wipes private storage and pending rows) | hours | an app rollback with pending rows loses data, so downgrade is unsupported (D-79) | `cfg.sync.engine_mode` keeps the previous engine for one release |
| Wave | `cfg.flag.new_app_login_enabled` false at wave scope, pushed; captured rows keep uploading; export in the Apsis dump shape (D-148) | about 5 min online | safe | the Apsis app cannot be set read-only by AKTCL |
| Edge hold by version | remove the Front Door rule | about 1 min; Front Door propagation time is unverified | rows stay on devices | auto-expires |
| Front Door route or WAF change | restore the previous profile configuration from Bicep | minutes (unverified) | safe | change-freeze applies |
| Infra (Bicep) | redeploy the previous template after a `what-if` gate | minutes to hours | | environment and VNet changes are not quick to undo: treated as C3 with a window |
| PostgreSQL parameter or compute change | revert; a restart or scale causes a short connection break | minutes | device backoff | in HA, scale applies to the standby first (Learn) |
| Redis | flush or recreate | minutes | none: copies only | |

### 5.6 Change freeze, deploy windows and kill-switch semantics

| Rule | Value |
| --- | --- |
| Deploy windows | not within 07:00 to 10:00 and 16:30 to 19:30 Dhaka; not in a wave's first three days unless a P1 hotfix (doc 14 s9, D-14) |
| Change freeze | cfg.sys.change_freeze_windows 07:00 to 09:30 and 16:30 to 19:30 blocks C2 and C3 config and bulk master-data operations; break-glass is excepted and reviewed (D-100); at most 5 applied C3 versions an hour fleet-wide |
| Why | a radius change at 16:55 invalidates every pre-built bundle's config exactly when 9,842 phones check out |

| Switch | Effect | Never |
| --- | --- | --- |
| cfg.release.min_version | blocks a new day's login | blocks capture or upload; wipes data (D-130) |
| cfg.release.blocked_versions | stops new captures of that build | blocks upload |
| cfg.ops.sync_hold_by_version | edge 429 for one build; rows stay on the device; mandatory duration | an open-ended block |
| cfg.ops.sync_hold_s (proposed) | `hold_s` in responses, randomised | |
| cfg.ops.read_only_mode (proposed) | writes get 503 and `Retry-After` during a migration | |
| cfg.flag.new_app_login_enabled | wave rollback | |

### 5.7 Edge hardening

| Item | Rule | Gate |
| --- | --- | --- |
| Second hostname | the Front Door default endpoint in a Microsoft-managed DNS zone is tried after 3 TLS or DNS failures (cfg.net.fallback_after_failures); no pinning (D-81) | T-2-57 |
| Certificate | managed certificates; J10 probes expiry at 30, 14, 7 days | T-2-57 |
| Trust chain | check the chain on the oldest supported Android: 8.0 under D-11, 7.x only if the census lowers minSdk (G-scale-14); the new Front Door chain on old Android trust stores is unverified | T-2-54 |
| Clock | banner above 10 min skew (cfg.sync.max_clock_skew_min); business date uses trusted time (doc 17 D-389) | T-2-54 |
| WAF | Log mode for a wave; body inspection excluded on `/sync` and `/media` | T-2-57 |

Proved by: T-0-50, T-0-51, T-1-52, T-1-53, T-1-54, T-1-55, T-1-56, T-2-53, T-2-54, T-2-55, T-2-56, T-2-57, T-4-55, T-4-56, T-7-53, T-7-54, T-7-58.

## 6 SLOs, observability and alerting

### 6.1 SLOs (D-135) and how each is measured

Window: 07:00 to 21:00 Dhaka on working days (and every wave day), monthly. The availability budget is 14 h x 30 d = 420 h, so 0.1 percent is 25.2 minutes a month (lens-scale said 43 minutes, which assumed 24 hours).

| SLO | Target | SLI (good over valid) | Measured at | Hole closed (G-sre-19) |
| --- | --- | --- | --- | --- |
| Availability of `/v1/auth/*`, `/v1/sync/*`, `/v1/day/*` | 99.9 percent | requests that are not 5xx and not an edge failure (Front Door 5xx, WAF false block, TLS, DNS); excluded: 429 from the per-device limiter; bad: 503 from L3, L4 or a pool wait | Front Door access and WAF logs plus the API; a Dhaka lab phone with a real SIM runs login, bundle and a test batch every 15 min as the third signal | edge failures never reach the API's counters, so the SLI is computed at the edge too |
| Bundle latency | p95 at most 2 s server time; p95 at most 10 s end to end on 3G | server: `aron_http_duration_ms{route="bundle",kind}`; end to end: `bundle_download.duration_ms` reported by devices | API and fleet | the lab probe alone had 96 samples a day |
| Trickle ack | p95 at most 1.5 s | `/sync/batch` with `size_class="small"` (at most 50 rows) | API | one histogram could not separate the shapes |
| Catch-up ack | p95 at most 8 s | `size_class="large"` (200 rows) | API | |
| Sync success | at least 99.5 percent of batches accepted within 3 attempts | batches whose first accepted attempt has `X-Batch-Attempt` at most 3, over distinct batch_uuids | `sync_batch` and the header (added to the contract, F-SYS-050) | the wire contract had no attempt header; "0 lost" is measured by the nightly device-against-server job and by 21:30 on wave days |
| Reconciliation mismatch | at most 0.1 percent of route-days | `submit_count_mismatch` after settle (D-64) | `route_day` | meaningless before the settle rule |
| Aggregation lag | p95 at most 60 s; p99 at most 5 min | `processed_at - enqueued_at` per item | worker histogram | lag was a gauge and dead items were invisible |
| Dashboards | p95 at most 1.0 s (`/dashboard/*`, `/app/home`) | server time | API, with `cache_hit` label | |
| Sync-health page | p95 at most 1.5 s at 1,500 viewers | page time | API | D-427 |
| Reports and exports | interactive p95 at most 5 s; export ready within 120 s for up to 100,000 rows (ASSUMPTION; lens-scale said Excel within 30 s before exports became jobs) | | API and job log | |
| Photos (R5(f), D-510) | 95 percent present within 24 h of the record; this is the stated exception to "immediately" | `linked_at - captured_at` histogram from `media_object` | worker | a threshold count is not a distribution |
| Config reach (D-563, doc 19 s4.1b owns the definition) | MEASURED PER COHORT, in wall-clock terms: 95 percent of SELLING devices (a batch or bundle request in the last `cfg.sla.online_window_min` = 20 min) hold a non-urgent version within 15 min; idle devices at the next foreground; an urgent revert with push OFF reaches 95 percent of selling devices within 15 min (the published figure for the pilot and any wave without push) and with push ON 95 percent of push-enabled devices within 5 min. The SLO is NOT "ALL active devices (online or not)": the tail metric `aron_config_reach_tail` reports the share of ALL active devices reached within 24 h and the unreached count by reason (offline, push off, old app, no bundle contact, silent since). The first draft said "all devices" and measured online ones, which a device that makes any request meets by definition (D-526, G-qa-51, G-qa-96) | `aron_config_ack_pct{version}`, `aron_config_reach_tail` | API | D-89, S9 |
| Immediacy, online (R5, D-509, G-qa-33) | 95 percent of rows captured with validated connectivity are ACKed within 60 s and 99 percent within 5 min; the 0 to 90 s check-out jitter is excluded from the clock (it starts at the end of the jitter) | `ack_time - max(captured_at, connectivity_regained_at)` from `fact_memo.sync_latency_s` and the device stamp | `sync_batch`, `fact_device_day` | immediacy was proved only in the lab |
| Immediacy after reconnect | rows captured offline are ACKed within 3 min of `connectivity_regained_at` for 90 percent of devices | as above, per device-day | `fact_device_day` | |
| Held rows | no device holds pending rows without contact for more than 4 h (cfg.sla.pending_rows_alert_h) at the 17:30 read; a device beyond 24 h is escalated | `X-Pending-Rows` and last contact | `device` | R5 condition D of doc 17 s4.1b |
| Field R4 budgets | p95 mobile bytes per device-day at or below 1.25 x the doc 17 s8.4 gate; no canary regression above 20 percent on CPU, starts or battery drop | `fact_device_day` | sync-health SH-19 to SH-21 | R4 "abruptly" needs a field signal (D-507) |

### 6.2 Error budget, burn rate and calendar-aware baselines

| Alert | Windows | Burn rate | Error ratio at 99.9 percent | Action |
| --- | --- | --- | --- | --- |
| Fast burn | 1 h and 5 min | 14.4 x | 1.44 percent | page (Sev1) |
| Medium burn | 6 h and 30 min | 6 x | 0.6 percent | page in business hours (Sev2) |
| Slow burn | 1 day and 2 h | 3 x | 0.3 percent | ticket |
| Budget | 3 days and 6 h | 1 x | 0.1 percent | ticket |

Only business-hour minutes count in these windows. If half of the monthly budget (12.6 min) is spent in one week, feature deploys freeze and only hotfixes ship; with these alerts the freeze is a rule, not a meeting (G-sre-19). Baselines for every "against yesterday" alert are the same weekday last week taken from the working-day calendar (D-28), because Saturday against Friday compares a trading day with a day of zero (P-03); on a wave day the baseline is the pre-switch figure for the same routes (doc 14 s8.5).

### 6.3 Telemetry plan (D-13, D-136, D-419)

| Item | Rule |
| --- | --- |
| Server | OpenTelemetry to Application Insights; one `request_id` end to end; pino JSON with `user_id` and `device_id` hashed; no names, phones or coordinates |
| Sampling | adaptive on the server: 100 percent of errors, 429, 5xx and requests over 2 s; 5 percent of 2xx; Front Door logs at 100 percent for `/sync`, `/auth`, `/media` only |
| Device | operational telemetry only inside the sync batch, at most cfg.telemetry.device_max_bytes_per_day (1,024) bytes; no extra network |
| Workspaces | ops workspace (sampled; daily cap 15 GB = 3 x the 5 GB budget; alert at 12 GB) and a sync workspace for `sync.*` summaries and the device lookup (no cap; about 0.3 GB a day; 100 percent retention, 30 days). A capped workspace stops collecting and some excess is still billed (Learn), so the business screen never reads logs |
| Volume estimate | API logs and dependencies sampled about 80 MB; container console and platform logs about 0.6 GB; Front Door access and WAF about 0.7 GB; PostgreSQL and PgBouncer about 0.5 GB; other diagnostics about 0.3 GB: 2.2 to 3 GB a day, 5 GB budgeted, 3 x on a wave day |
| Cost | 5.5 GB a day at USD 2.99 per GB is about USD 490 a month (s3.3) |
| PII test | a scrub test replays 1,000 payloads and fails the build if a name, phone or coordinate appears (T-4-57) |

Metric catalogue (prefix `aron_`, doc 17 and doc 20 consume it):

| Metric | Type | Labels | Feeds |
| --- | --- | --- | --- |
| aron_http_requests_total, aron_http_duration_ms | counter, histogram | route, status, size_class, revision, cache_hit | availability, latency, canary |
| aron_sync_rows_total | counter | type, outcome, reason | rejection rate |
| aron_sync_batch_attempt | histogram | | sync success |
| aron_sync_replay_total | counter | source (redis, db) | replay hits |
| aron_sync_pending_rows_reported | histogram | | support, SH-09 |
| aron_agg_lag_s | histogram | | aggregation SLO |
| aron_agg_staleness_s | gauge, top-k | route_day | staleness alert |
| aron_agg_dirty_depth, aron_agg_dead_total | gauge, counter | | worker scaling, SH-11 |
| aron_bundle_served_total | counter | kind (full, delta, 304), source (snapshot, live) | FM-01 |
| aron_bundle_pregen_users, aron_bundle_pregen_coverage_pct | gauge | | J7 |
| aron_routes_by_state | gauge | state | SH-01 to SH-05 |
| aron_final_submit_zones | gauge | | SH-06 |
| aron_device_clock_skew_s | histogram | | SH-16 |
| aron_config_ack_pct | gauge | version | SH-15 |
| aron_auth_hash_inflight, aron_auth_hash_queue_wait_ms | gauge, histogram | | FM-02 |
| aron_rate_limit_total | counter | layer | s4.4 |
| aron_photo_link_lag_s | histogram | | photo SLO |
| aron_partition_default_rows | gauge | table | FM-30 |
| aron_job_run | gauge | job, status, duration | job DAG |
| aron_import_control_total_diff | gauge | | FM-31 |

### 6.4 Sync-health dashboard specification (F-SYS-026, F-WEB-045, F-API-028; D-427)

Purpose: the one screen the business and the war room watch. Phase: minimal version in 1c (tiles SH-01 to SH-03 and the quarantine list, F-API-028), full in 4a. It reads Postgres, not logs.

| Tile | Definition | Read from | Colour rule (ASSUMPTION, calibrated at 7b) |
| --- | --- | --- | --- |
| SH-01 | Login % (K-01) = logged_in_routes / target_routes, by wing, territory, zone | primary, `dw.agg_daily_zone.logged_in_routes` (fed by the route-day trigger of doc 16 s8.6b; never `app.route_day`, D-567) | green at or above 95 percent of the baseline (same weekday last week; on a wave day, the pre-switch figure for the same routes); red below 70 percent at 09:00 |
| SH-02 | Submit % (of logged-in) (K-02), counted only after settle | primary, `dw.agg_daily_zone.submitted_routes` (D-567) | amber below the baseline from 18:00 |
| SH-03 | Day-completion % (K-03), always labelled with the qualifier | primary | information |
| SH-04 | Routes in `submit_pending_rows`, shown separately from mismatches | primary | information; amber above 30 min |
| SH-05 | Reconciliation mismatch after settle: route-days and percent | primary | red above 2 percent of routes (the rollback trigger), amber above 0.1 percent |
| SH-06 | Final-submit status (K-15): zones submitted over zones with target routes, plus the list of remaining zones | primary | amber from 20:00 |
| SH-07 | Last batch age per in-field route; routes with no batch for 2 h | primary | amber above 2 h |
| SH-08 | Ingest health: trickle and catch-up ack p95, 429 and 503 rate | Azure Monitor metrics | amber above SLO |
| SH-09 | Pending rows reported by devices (`X-Pending-Rows`); devices above 100 | replica | amber |
| SH-10 | Rejected and quarantined rows by reason, with a link to the quarantine list | replica | amber above 0.5 percent of rows |
| SH-11 | Dead aggregation items and the maximum staleness, with a re-drive button for admins | primary | red above 5 min |
| SH-12 | Photos pending over 24 h | replica | amber |
| SH-13 | Geo-validation % (K-09) against the baseline; flagged and mock visit counts | replica | amber 30 points below the baseline |
| SH-14 | App-version and Android mix; crash rate per version (link to Sentry) | replica | red above 2 percent of sessions |
| SH-15 | Config reach: acknowledged percent per version | replica | amber below 95 percent after 15 min |
| SH-16 | Devices with clock skew above 10 min | replica | amber |
| SH-17 | Bundle served by kind and J7 coverage | replica | red below 95 percent |
| SH-18 | Replica lag and the "as of" stamp; degraded-mode banner | Azure Monitor | banner |
| SH-19 | Mobile data per device-day: p95 of non-media bytes and of non-media plus media bytes, by role and wave (doc 17 s8.10) | replica, `fact_device_day` | amber above 1.25 x the gate for 2 days; the release console freezes `wave_pct` on a canary regression above 20 percent |
| SH-20 | App CPU ms and engine starts per device-day by app version | replica | amber on a canary regression above 20 percent |
| SH-21 | Whole-device battery drop 08:00 to 17:00 (median, p95) for devices not charged, by model and version; p10 of the 17:00 level | replica | amber on a canary regression above 20 percent or p10 below `cfg.telemetry.bat17_floor_pct` |
| SH-22 | Immediacy online: share ACKed within 60 s and 5 min | replica | red below 95 percent within 60 s over a rolling hour |
| SH-23 | Immediacy after reconnect: share of devices ACKed within 3 min | replica | red below 90 percent over a day |
| SH-24 | Held rows: devices with pending rows and no contact for more than 4 h, with user, route, zone, rows, age and model (the TSO and helpdesk list) | primary | amber at the first device beyond 4 h |
| SH-25 | Coverage banner: percent of planned routes on Aron, per wing, wave and day; scope toggle switched or all routes (doc 16 s9.10, D-548) | primary | information |
| SH-26 | Apsis residual (D-556): Apsis memos or collections on routes that are `on_aron`, by route, wave and zone | `dw.agg_daily_route` rows of `source = apsis_residual` | any value above `cfg.sla.apsis_residual_alert` (0) is red and alerts the AMO, TSO and L1; RB-44 |
| SH-27 | Held binds (D-586): count and age of the oldest bind held by the takeover rule | `app.device` held state | amber above `cfg.auth.held_bind_sla_min` (30), red above `cfg.sla.held_bind_alert_min` (45); RB-46 |

| Feature | Rule |
| --- | --- |
| Drill | wing, territory, house, zone, route, device card (SR name, last request time and status, `X-Last-Sync-Error`, pending claim, app version, battery band); the device id is shown as a short hash |
| Filters | Wing to Zone, bounded by the viewer's scope (server-side); date back 14 days |
| Access | admin, ops, TSO and above within scope; the L1 support role sees SH-07, SH-09, SH-24 and the device card only (the Support desk page P19 of doc 19 carries the rest, D-541) |
| Date stamp and reason chip (D-544) | every tile shows its business date and an "as of hh:mm" stamp; a tile that is not live today carries a chip from the fixed enum `replica_lag`, `agg_stale`, `non_working_day`, `no_data_yet`, `degraded_mode` (the same enum as doc 16 s9.6), readable on P19 so support can say why a tile shows an old figure |
| Scope (D-548) | SH-01 to SH-06 default to switched routes (routes with `on_aron`); SH-25 shows the coverage; an unswitched route is never "not logged in" |
| Path | `GET /v1/ops/sync-health` (F-API-028); Redis response cache key (endpoint, scope_hash, date, agg_version), 30 s; about 1,400 distinct keys for 1,500 viewers |
| Refresh | 60 s, enforced by the server (cfg.ops.dashboard_refresh_min_s 60, proposed, bounds 30 to 300); the war-room wall may use 30 s for at most 20 viewers |
| Budget | page p95 at most 1.5 s at 1,500 viewers; cache hit ratio at least 90 percent; at most 50 queries/s on the primary |
| Degraded mode | when replica lag exceeds 60 s, primary CPU exceeds 85 percent or p95 exceeds 3 s with a cache miss ratio above 50 percent: zone-level tiles only, a server-directed 120 s refresh, a banner; exit after 10 clear minutes |
| Role | the `app_web` role touches no `app.*` transactional table (T-4-43) |

### 6.5 Other dashboards (Azure Monitor workbooks)

| Workbook | Content |
| --- | --- |
| Traffic and latency | requests per second by route group, p50, p95, p99 per endpoint, 429 and 503 by layer, retry-attempt histogram, replay-hit ratio, replicas, pre-scale state |
| Database | CPU, IOPS against provisioned and against the SKU cap, PgBouncer clients, servers and waiting, lock waits, longest transaction, replica lag, storage percent, WAL size, autovacuum lag on partitioned tables |
| Worker and jobs | outbox depth and age, recompute duration, dead items, J1 to J10 status and coverage |
| Devices (aggregated, no PII) | version and Android mix, failures by reason, batch size, photos pending, clock skew, battery band if reported |
| Edge | Front Door status codes by route, WAF Log hits by rule, certificate days left, probe results from the Dhaka phone |
| Cost and quota | spend against budget, cores and vCores against quota, Log Analytics ingestion against the cap |

### 6.6 Alerts, routing and runbooks (G-qa-17)

Severity: Sev1 pages the on-call engineer and the incident commander and needs an acknowledgement in 5 min; Sev2 pages in business hours with 15 min; Sev3 is a ticket. The notification channel is DECIDED, not left open (D-552, G-qa-84): Sev1 and Sev2 pages go through an Azure Monitor action group with SMS and a voice call to named phones (e-mail and push are copies, not pages: e-mail is not a pager); the action group, the SMS provider's Bangladesh delivery and a real test page are part of T-7-55 and must exist before 4d; the on-call TOOL (rota, escalation timers) is an AKTCL choice (OI-18-09, default: the action-group escalation plus a shared on-call sheet). Action groups: `ag-sev1` (primary then secondary after 10 min; the business owner on wave days), `ag-sev2`, `ag-sev3`. ONE time per trigger: the config key is the source of truth and the runbook reads it, so a console edit and a runbook never drift: `cfg.sla.login_pct_alert_time` default 09:00 (the 08:45 of RB-03, the 10:00 of the first config default and the 09:00 here are one value; 08:45 is a human check at half the threshold, not an alert).

| Sev | Alert | Threshold | Runbook |
| --- | --- | --- | --- |
| 1 | API availability | 5xx above 2 percent for 5 min, or Front Door origin unhealthy; fast burn | RB-01 |
| 1 | Database primary unavailable or failing over | HA state not Healthy for 3 min | RB-02 |
| 1 | Login % at `cfg.sla.login_pct_alert_time` (09:00) | below 70 percent of the baseline for any wing, evaluated over SWITCHED routes only and not for a scope with an `emergency_off` declaration for the date (D-548, D-542) | RB-03 |
| 1 | Reconciliation mismatch after settle | above 2 percent of routes | RB-04 |
| 1 | Sync success below `cfg.sla.sync_success_pct_alert` (97) for 1 h, wave days; rollback trigger of s7.5 (D-596, G-qa-135) | rolling hour, over distinct batch_uuids | RB-06, RB-07 |
| 1 | Crash rate above `cfg.sla.crash_rate_pct_alert` (2) for the wave's build; rollback trigger of s7.5 | per version | RB-28, RB-01 |
| 1 | Apsis residual selling on a switched route (SH-26, D-556) | above `cfg.sla.apsis_residual_alert` (0) per route-day | RB-44 |
| 1 | Key Vault delegation (D-574) | a replica with less than 3 h of delegation left, or the first failed `sign` | RB-48 |
| 1 | Config urgent reach (D-563) | an urgent change below 95 percent of the SELLING cohort after 15 min with push off, or of push-enabled devices after 5 min | RB-49 |
| 2 | Bundle p95 | above 3 s for 10 min | RB-05 |
| 2 | 429 or 503 rate | above 5 percent for 5 min (storm profile: 10 percent) | RB-06 |
| 2 | Outbox age or per-key staleness | above 5 min | RB-07 |
| 2 | Replica lag | above 60 s for 10 min | RB-08 |
| 2 | Cross-region replica lag | above 5 min (Sev1 above 12 min: RPO at risk) | RB-08 |
| 2 | Subtransaction overflow | `pg_stat_slru` subtransaction reads rising or `subxact overflowed` sessions above 0 for 5 min | RB-06 |
| 2 | Geo-valid % for a territory | falls more than 30 points against the baseline in 15 min | RB-09 |
| 2 | Submit % (of logged-in) at `cfg.sla.submit_pct_alert_time` (21:00) | below `cfg.sla.submit_pct_alert_threshold` (floor 30) of the baseline | RB-04 |
| 2 | Config acknowledgement | below `cfg.sla.config_ack_pct_alert` (floor 50) after `cfg.sla.config_ack_window_min` | RB-49 |
| 2 | Force-sale share | above `cfg.sla.force_sale_pct_alert` for a scope | RB-09 |
| 2 | Mock-GPS share | above `cfg.sla.mock_gps_pct_alert` (floor 1) for a scope | RB-09 |
| 2 | Quarantine backlog | above `cfg.sla.quarantine_backlog_alert`; open location requests above `cfg.sla.location_request_backlog_alert` | RB-10 |
| 2 | Crash-free sessions of a version | below `cfg.sla.crash_free_min_pct` (the staged rollout pauses itself) | RB-28 |
| 2 | DSS data-as-of stamp older than `cfg.sla.dss_stale_alert_s` (120) in selling hours (D-578) | stamp age | RB-07 |
| 3 | Held bind older than `cfg.sla.held_bind_alert_min` (45; SH-27, D-586) | queue age | RB-46 |
| 3 | A retention hold about to expire with a job waiting (D-600) | 7 days before expiry | RB-12 |
| 2 | Rejected or quarantined rows | rejected above 0.5 percent of rows, or quarantine above 100 an hour | RB-10 |
| 2 | Bundle coverage (J7) or any job failed or late | below 99 percent; past deadline | RB-11 |
| 2 | `auth` memory or hash queue | memory above 80 percent, or queue wait above 1 s | RB-02 |
| 2 | Certificate days left | below 14 | RB-15 |
| 2 | Log Analytics ingestion | above 80 percent of the cap | RB-16 |
| 2 | Default-partition rows | above 0 | RB-11 |
| 3 | Storage used | above 60 percent, or a projected-full date within 90 days (Sev2 above 70 percent: grow now; Sev1 above 90 percent), all four rows live in the alert table (D-568; FM-29) | RB-12, RB-51 |
| 3 | Cores or vCores | above 80 percent of quota | RB-12 |
| 3 | Budget | 80 percent of the monthly budget | RB-16 |

Hygiene rules (D-428): every alert has an owner, a runbook id, a dedupe window and an auto-resolve; the storm profile (07:00 to 09:30 and 16:30 to 19:30) doubles the 429 and 503 thresholds and raises latency thresholds by 50 percent but never changes a Sev1 threshold; an alert nobody acted on in 90 days is removed; every Sev1 gets a post-incident review within 5 working days. On-call: two engineers (primary and secondary) and a business owner for every wave day; outside waves one engineer from 07:00 to 21:00 (ASSUMPTION for a team of 8 to 10).

### 6.7 Device visibility, support and error reporting (G-qa-12, G-qa-13, G-scale-13)

| Item | Decision |
| --- | --- |
| Why | a device that cannot sync is exactly the one whose telemetry cannot arrive, so server-observed state matters most |
| Server-observed state | from headers the device already sends: `X-App-Version`, `X-Config-Version`, `X-Pending-Rows`, `X-Last-Sync-Error`, `X-Batch-Attempt`; written to the device row at most once per 10 min (D-138, F-SYS-050) |
| On demand | `POST /v1/support/ping` (F-API-046): at most 1 KB, device-proof auth, separate from `/sync/batch` so it works when ingest is what fails |
| Device lookup | a workbook on the sync workspace: filter by device hash, last 50 requests with status, batch_uuid, rows and reason codes; a read-only L1 role if Q55 says yes (D-138, MUST-CONFIRM by 7c), otherwise L2 runs lookups |
| Support code | every blocking screen shows the code of doc 17 D-396 |
| Error reporting | Sentry for Flutter and web with PII scrubbed, device id and stack only, at most 10 events an hour per device; server telemetry to Application Insights. Application Insights has no first-party Flutter SDK (not confirmed in the sources read: unknown), which is why Sentry; residency is MUST-CONFIRM (D-13, by 2e) and the fallback is a self-hosted Sentry-compatible service on Container Apps (USD 150 to 250 a month, unverified) |
| Crash trigger | a crash rate above 2 percent of sessions for one build is a rollback trigger (s7.5) |
| Cost control | device telemetry only inside the sync batch; adaptive sampling; the daily cap sized for a wave day (G-scale-13) |

Proved by: T-1-55, T-2-58, T-4-41, T-4-51, T-4-52, T-4-57, T-4-58, T-4-59, T-7-59.

## 7 Launch-day operating model

### 7.1 Readiness before a wave

| When | Item | Owner | Evidence | Gate |
| --- | --- | --- | --- | --- |
| T-14 | Quotas confirmed (s3.2); Sev1 action group tested with a real page | infra | quota screenshots, test page log | T-7-55 |
| T-14 | Game day run on prod-SKU staging this quarter | QA, infra | game-day report (s8.2) | T-7-59 |
| T-14 | Restore drill and checklist passed in the last 30 days; DR promotion drill in the last 6 months (before wave 2) | DB | checklist sheet | T-7-54 |
| T-7 | Load tests at 1.5 x the wave size on prod-SKU staging passed | QA | Azure Load Testing report | T-7-51, T-7-52 |
| T-7 | On-call rota published: two engineers and a business owner; war room booked; helpdesk staffed (D-149) | IC, support lead | rota | doc 20 |
| T-3 | Rollback artefacts in place: previous APK in `apk/`, previous API revision active, config version noted, rescue-release path pre-authorised | release | checklist | T-7-53 |
| T-1 | Certificate probe above 14 days; fallback hostname probed from a lab phone; `sync_hold_by_version` rule prepared with the current build pre-filled | infra | probe results | T-2-57 |
| T-1 14:00 | Wave pre-snapshot J2w built for the wave's users (D-557) | infra | `job_run` row | T-7-161 |
| T-1 19:00 | Pre-bind day executed (D-126): 100 percent of the wave's devices bound, logged in, the bundle for D fetched and VERIFIED from the 14:00 pre-snapshot, test memo printed, install succeeded (at least `cfg.cutover.install_success_pct`, 95, D-562), read from the readiness screen (doc 17 s7.1); every SR absent from the pre-bind day is listed and its route stays `held` on Apsis | support lead, TSOs | readiness counts; the bind-at-the-distribution-house list | T-7-84 (doc 20), T-7-166 |
| T-1 19:00 | Every wave SR has completed the Apsis end-of-day upload and Sales Submit, and the Apsis TSO Final Submit is done for 100 percent of the wave's zones (`cfg.cutover.apsis_complete_by_time`); a GO/NO-GO condition (D-556, G-qa-88) | TSOs, sales ops | the Apsis Final Submit log | T-7-84, T-7-163 |
| T-1 | Freeze armed; storm alert profile loaded; cfg.ops.prescale_schedule set for the wave's wings | infra | config version | |
| T-1 | Wave calendar rule holds: starts Sunday to Tuesday, not Thursday, not within 3 days of a month end, Eid or a holiday (D-147, D-311) | doc 14 | calendar | |
| T-2 | Delta dry run: the delta contract of doc 16 s12.6 delivered (Apsis or the AKTCL-staff fallback) and imported in staging; the nightly Apsis route-day feed for unswitched routes arrived for the last 3 nights (D-514, D-548) | data lead, Apsis delta owner | import report | T-7-150, T-7-151 |
| T-1 20:00 | Final delta files and the same-date control totals received by 20:00; if not, the fallback starts and runs until 21:00 | Apsis delta owner, data lead | manifest and checksums | T-7-80 |
| T-1 23:30 | Delta import reconciled and signed (J1, by 23:00), J2 done (triggered by J1, by 23:30), probes green, the bind-at-the-distribution-house list complete and every unbound SR's route `held`; otherwise the wave is deferred (D-557) | data lead, business owner | `job_run` rows | T-7-80 to T-7-82 (doc 20), T-7-161 |
| T 06:00 | Late delta DL-1b imported and reaching phones as a bundle delta before the first sale, with "balance as of <time>"; the straggler sheet opened with a named clerk (D-556) | data lead, clerk | `job_run` J1b, the sheet | T-7-163 |
| T-1 | Switched routes cannot keep selling in Apsis unnoticed: DL-6 `apsis_residual` live for the wave, or "old app disabled or uninstalled on every phone of the wave" ticked by the TSO on the readiness screen (D-556, RB-44) | data lead, TSOs | SH-26; readiness ticks | T-7-164 |
| T-1 | The measured geo-restore RTO and the sponsor's written acceptance of the wave-1 DR position are on the checklist (D-569, RB-52); the day-one checklist shows the config reach bounds measured with push on and off (D-563) | infra, sponsor | T-4-174, T-4-161 | T-7-84 |
| T-3 (before a break) | Pre-holiday item (RB-54, D-584): the snapshot of the first working day after the break exists and was pre-fetched by Wi-Fi phones; `calendar.break_overrides` set; the P1 readiness board shows no route without a bundle | infra, sales ops | board | T-2-163 |

### 7.2 War room and decision rights

| Role | Holds | Decides |
| --- | --- | --- |
| Incident commander | the timeline, the log, every Sev1 | technical rollbacks (traffic weight, config revert, hold by version); declares a region outage |
| Infrastructure and database lead | s2 to s5 components, quotas, RB-02, RB-12 to RB-14 | scaling, failover handling |
| API and sync lead | ingest, bundle, worker, rate limits, RB-05 to RB-07 | limiter and hold settings |
| App lead | build, updater, crash data | `blocked_versions`, rescue release |
| Data lead | importer, reconcile, aggregates | `rollback_import`, `dw.rebuild` |
| Business owner (sales ops) | the reconciliation mismatch list, Login % and Submit % (of logged-in) reading | with the IC: stop or continue the wave |
| Support lead | helpdesk, TSOs, pre-bind day | what is told to reps |
| Scribe | the ops journal | none |
| Break-glass holders | at least TWO named holders per shift (primary and alternate) on every wave day, with an audited hand-over at each shift change recorded in the ops journal (the single named account of the first draft was a single point of failure at 07:30 on wave morning, D-527, G-qa-77) | restore or restrict at once; the emergency-widen lane and the temporary-relief path of doc 19 s7.6b, with the second approver after the fact |
| Data steward and L2 on-call | one named steward for master-data and assignment fixes, one for security (unlock, OTP, scope) and one for ops_admin actions on every wave day (D-553); the authority matrix is doc 19 s5.1b | route assignment and scope fixes, quarantine accept, submit void, reopen |

The sponsor's delegate is informed at 07:00, 12:00, 17:00 and 21:30. Nobody changes C2 or C3 config inside a freeze window without the IC and a break-glass record (D-100).

### 7.3 Wave-day timeline (Dhaka)

| Time | Action | Check or decision |
| --- | --- | --- |
| T-1 14:00 | J2w wave pre-snapshot for the pre-bind pre-fetch (D-557) | `aron_bundle_pregen_users` equals the wave's users |
| T-1 19:00 | Pre-bind readiness and Apsis completion checks (s7.1) | any line missing is read at 23:30 |
| T-1 20:00 to 23:00 | J1 final delta import; the clerk reconciles | `reconciled` by 23:00 |
| T-1 on J1 `reconciled` to 23:30 | J2 builds the final D snapshots (triggered by J1; deadline 23:30) | `aron_bundle_pregen_users` equals users in scope |
| T-1 23:30 | Go or no-go: J1 reconciled, J2 done, probes green, bind list complete | defer the wave if any is missing |
| 00:05 | J3 creates `route_day` for the wave and the fleet | count equals planned routes; Login % denominator visible on SH-01 |
| 02:15 and 03:30 | J5 re-aggregation, J6 refresh for dirtied users | `job_run` complete before 04:15 |
| 04:30 | J7 coverage check | at least 99 percent and `valid_for_business_date = D`; below 95 percent set `bundle_hold` and page L2 (RB-11) |
| 06:00 to 06:20 | J1b late delta DL-1b; changed users receive a bundle delta before the first sale | straggler sheet opened; "balance as of" marker (D-556) |
| 06:15 | Pre-scale: api 8, worker 3, web 4, `auth` 6 on a wave morning | replicas ready; Redis healthy |
| 06:30 | The on-call engineer watches the login histogram; the real window starts (stock issue at the distribution house) | |
| 07:00 | War room opens; SH-01 to SH-18 on the wall | |
| 07:00 to 09:30, every 10 min | Login % per zone against baseline; bundle p95; 429 and 503; TLS errors by Android version; replay hits (should be low); primary CPU below 60 percent; `auth` memory; live-bundle share below 5 percent; at 08:00 no `/auth/refresh` sawtooth; no bulk master-data operation (freeze) | decision point: Login % below 50 percent at 08:45 is RB-03 (version block, Front Door, WAF hits, carrier NAT); a WAF rule that blocks is switched to Log |
| 09:30 | Freeze ends; day-one data errors (route assignments) fixed one user at a time before, in bulk after (regeneration cap 20 per second) | |
| 10:00 to 16:00 | Trickle health: ack p95 below 1.5 s, outbox age below 1 min, staleness list empty, dead items 0, geo-valid % in line with the pilot, mock flags reviewed by AMOs, quarantine reasons triaged (most on day one are assignment or scope data errors) | `sync_hold_by_version` ready |
| 12:00 | Sponsor update | |
| 16:30 | Freeze starts; 16:45 pre-scale again (`web` too, for the supervisors at 17:00); confirm no token sawtooth at 17:00 | |
| 17:00 to 19:00 | Submit % (of logged-in) read after settle; `submit_pending_rows` shown separately; the business owner works the mismatch list (route, type, device count, server count): each entry is a bug or an unsent child row; final-submit progress by zone; photos pending | rollback triggers of s7.5 |
| 20:00 to 22:00 | Photo wave: SAS p95, Storage 503 count 0 | |
| 21:30 | Day close: device-count claims against the registry for today (not nightly), aggregation lag, error budget used; go or no-go for tomorrow; day log into the ops journal | |
| T+1 09:30 | Daily sheet, defects triaged (doc 20 s7) | go or hold for the next wave only after the gates of doc 14 s8.5 |

### 7.4 Wave 1 and the full fleet

| Aspect | Wave 1 | Full fleet |
| --- | --- | --- |
| New users on day one | 1,155 (Q51 case) or about 335 (doc 14 default) | 7,350 new plus 1,150 veterans, only with the pre-bind day |
| First morning | a correctness and support day (ST-02); 1.6 req/s | the real storm if the pre-bind day is skipped (ST-03); with it, ST-01 |
| Pre-scale | api 4, `auth` 2, worker 2, web 2 | api 8, `auth` 6, worker 3, web 4 |
| APK on Wi-Fi at T-1 | 35 GB | 220 GB |
| DR | geo-redundant backup only | cross-region replica and warm environment |
| What it proves | parallel-run parity, support load, data errors, the 17:00 settle | capacity at 1.5 x; the first-morning procedure |
| Gates | T-7-51, T-7-52, T-7-53, T-7-55, T-7-57, T-7-59 | T-7-56 and the wave gates T-7-88, T-7-89 (doc 20) |

### 7.5 Rollback triggers and levers

The trigger list is fixed before the wave. The first trigger is the one named in doc 14 s8.4 and is read only after submit settle (D-64); the others are from lens-scale s5.4 and the SRE critic.

| Trigger | Threshold | Read | Decision | Action |
| --- | --- | --- | --- | --- |
| Reconciliation mismatch after settle | above 2 percent of the wave's routes | at 17:30 to 19:00 and 21:30 | IC with the business owner | find the cause (RB-04); not found within 60 min: wave rollback |
| Sync success | below 97 percent for 1 h | rolling hour | IC | RB-06, RB-07, RB-15; not fixed within 60 min: wave rollback |
| Crash rate for the wave's build | above 2 percent of sessions | per version | app lead and IC | `blocked_versions` and a rescue release; wave rollback if data is affected |
| Data-corruption class bug (wrong totals, doubled numbers, lost rows) | any | any time | IC | stop the wave at once |
| Login % at 09:00 | below 70 percent of the baseline | 09:00 | IC | RB-03; unresolved at 10:00: the wave works on the old app for the day |
| Wave rollback | `cfg.flag.new_app_login_enabled` false at wave scope, pushed; captured rows keep uploading; export in the Apsis dump shape (D-148, F-SYS-042, F-SYS-065) | | | the Apsis app cannot be set read-only by AKTCL, so the rollback is a flag and a briefing, and the return path of the next table |

Wave rollback return path (D-549, MUST-CONFIRM by 7b, G-qa-81). After several days on Aron a rolled-back wave returns to an Apsis that has not seen the new credit memos, the dues collected and the stock; retailers dispute dues, the case docs/11 calls most dangerous. The default procedure, owned by the data lead and staffed on the war-room roster: (a) for the first 3 trading days after a wave, ROLLBACK means re-keying the wave's open dues, credit memos since the switch and stock into Apsis through a named, staffed procedure using the export of F-SYS-065 and the Apsis data-entry route of the roster (about 2 staff per 100 routes for the first day; unknown; confirm with the business how Apsis accepts keyed dues and who may key them); (b) from day 4, rollback is FIX-FORWARD ONLY (the wave stays on Aron and the defect is fixed, because a return after day 3 would mean re-keying more than a day's work per route); (c) Apsis reads are mirrored nightly (the DL-6 feed of doc 16 s12.6 in reverse for dues: Aron's dues per retailer are exported nightly to a sheet the business can read), so a dispute can be answered from either side. Same-day sales after a 17:30 rollback: the rollback takes effect at the next login; sales made on Aron that day are kept on Aron and keyed into Apsis by the same procedure before the next morning. Drill T-7-153: roll back a pilot wave on staging after 2 days of data and assert that retailer dues in the Apsis-shaped export equal Aron's to the paisa.

Per-wave rollback capacity (D-592, MUST-CONFIRM by 7b, G-qa-131, RK-33). At about 2 staff per 100 routes for the first day the re-key procedure needs about 8 people for wave 1 (390 routes), about 38 for the 1,880 routes added by wave 2, about 68 for wave 3 (3,400 added routes) and about 113 for wave 4 (5,670 routes), and it is still unknown how Apsis accepts keyed dues; the rollback lever of the larger waves therefore existed on paper only, and the go/no-go and the 2 percent trigger were written as if it worked. The rule is now stated per wave and depends on what AKTCL actually staffs:

| Wave | Routes added | Re-key staff needed (2 per 100) | Rollback by re-key possible? | If not |
| --- | --- | --- | --- | --- |
| 1 | about 390 | about 8 | yes, inside AKTCL's committed capacity | |
| 2 | about 1,880 | about 38 | only if AKTCL commits at least 38 trained keyers for 3 days (`rekey_capacity` signed at 7b) | fix-forward only, or split into sub-waves of at most the committed capacity |
| 3 | about 3,400 | about 68 | no, unless an Apsis bulk-import path exists | fix-forward only from day 1, or split |
| 4 | about 5,670 | about 113 | no, unless an Apsis bulk-import path exists | fix-forward only from day 1, or split |

The default (proceed-with) is: wave size is capped by the signed re-key capacity, or by an Apsis bulk-import path obtained before any wave above about 1,000 routes; a wave above that cap is FIX-FORWARD ONLY and its go/no-go says so; the 2 percent mismatch trigger of that wave then means "stop the next wave, hold, fix forward" and the rollback levers of doc 14 s8.4 that apply are the app, API, config and import levers, not the data return. T-7-153 is extended to a MEASURED keyer throughput (memos and collections per hour per trained keyer), from which the capacity of each wave is computed; the sponsor signs the per-wave statement with the go/no-go sheet (T-7-168).

Which lever first: app problem, roll forward with a rescue release unless the bug is cosmetic; API problem, traffic weight; config problem, revert with a jittered push; aggregate problem, `dw.rebuild`; import problem, `rollback_import`; schema problem, forward fix; region problem, RB-13 with a new generation. The next-wave gate is not a rollback: day-3 Login % at least 95 percent of the pre-switch figure, Submit % (of logged-in) not below it, mismatch at most 0.1 percent (doc 14 s8.5).

### 7.6 Runbook index

Each runbook is a one-page procedure kept in the repo under `/docs/runbooks/`, with the commands, the dashboards and the rollback; they are written in 1c to 4d and rehearsed in T-7-59.

| RB | Runbook | First steps |
| --- | --- | --- |
| RB-01 | API availability | Front Door origin health; ACA replicas and revisions; last deploy, then traffic weight to the previous revision; database and Redis state; edge |
| RB-02 | Database failover, unavailable, `auth` memory | watch HA state; verify PgBouncer reconnect; after 5 min open a provider ticket; `ANALYZE` afterwards; for `auth` check the limiter queue and add replicas |
| RB-03 | Login % low | J7 result; Front Door and WAF hits (switch to Log); version blocks; app-version mix; carrier NAT limiter hits; bind and OTP problems with the TSOs; stale-bundle path |
| RB-04 | Mismatch after settle | split `submit_pending_rows` from mismatch; list by route and type; check quarantine and poison rows; confirm the settle timer; business owner works the list; decide rollback |
| RB-05 | Bundle p95 | confirm snapshots exist, live share, scale `api`, Redis state |
| RB-06 | 429 or 503 | which layer (`aron_rate_limit_total`); expected during a storm; raise replicas or limits; `hold_s`; hold by version for a loop |
| RB-07 | Outbox or staleness | scale the worker; find the poison item; re-drive |
| RB-08 | Replica lag | find long replica queries; pause exports; degraded mode; replica SKU |
| RB-09 | Geo-valid % drop | config change log for the territory; revert a radius change; check the mock policy |
| RB-10 | Rejects and quarantine | reasons; scope or assignment data; fix and regenerate the users' bundles; fix-and-accept |
| RB-11 | Jobs, coverage, default partition | re-run J2 or J6 (resumable); `bundle_hold`; create the partition |
| RB-12 | Capacity | grow storage; buy IOPS; request quota; `ANALYZE` after a failover |
| RB-13 | Region outage | s5.4 steps 1 to 9 |
| RB-14 | Point-in-time restore | s5.4 PITR steps |
| RB-15 | Certificate or edge | renew; fallback hostname; WAF rule to Log |
| RB-16 | Telemetry and cost | Log cap, sampling, budget alerts |

Support runbooks (D-550, G-qa-82). RB-01 to RB-16 are infrastructure. The top-20 helpdesk issues of doc 20 s7.8 were titles only, with no steps and no tooling, and the actions that actually arrive on launch day were missing. Each runbook below is a one-page script in `/docs/runbooks/` with the tool (a page of doc 19), the permission (a bundle of doc 19 s5.1b), the audit evidence and the Bangla script; T-7-85 runs all of them with the REAL L1 persona, and T-6-42 uses the same list with a named persona per task.

| RB | Issue | Who acts | Tool (doc 19) | Permission | Audit evidence |
| --- | --- | --- | --- | --- | --- |
| RB-17 | Cannot log in (password, OTP, `min_version` banner) | L1, then TSO | P19 decode, P11 | account.unlock, device.otp.issue with TSO confirm | unlock and OTP rows in P16 |
| RB-18 | Bundle not downloading, route empty | L1 | P19 (bundle freshness, route assignment, route_day state) | device.view, support.decode | lookup row |
| RB-19 | "Not within range", force sale | AMO coaches; L1 explains | P19 (radius used) | device.view | none |
| RB-20 | Printer not found | L1 script, TSO swap | printer line of P19 | none | ticket |
| RB-21 | Memo totals look wrong (which offer) | L1 with the offer list | memo search on P19 | device.view, memo.view | lookup row |
| RB-22 | Rejected-row reasons (each DQ code) | L1 | P14 read, P19 | quarantine.view | none |
| RB-23 | Sync pending for hours | L1, then L2 | SH-24 held rows, P19 | device.view | ticket |
| RB-24 | Phone date or time wrong | L1 script | clock flag on P19 | none | ticket |
| RB-25 | Check-out before 17:00 | L1 script | key `cfg.day.checkout_earliest_time` shown on P19 | none | none |
| RB-26 | Sales Submit tapped by mistake, or with dues | TSO, or L1 with TSO confirm, or L2 | P15 submit void | submit.void | `fact_submit_void` row and P16 |
| RB-27 | Shared phone, second user | L1 script | P11 | device.view | none |
| RB-28 | App update | L1, TSO | P12 adoption view | device.view | none |
| RB-29 | Storage full | L1 script | free storage on P19 | none | ticket |
| RB-30 | Permission denied (location, camera, Bluetooth) | L1 script | none | none | none |
| RB-31 | Which radius applies | L1 | P2 resolved value for the outlet | cfg.view | none |
| RB-32 | Final Submit refused (already done), reopen, on-behalf submit | TSO, L2 | P15, B2 | day.reopen, web.final_submit | reopen row and P16 |
| RB-33 | The old Apsis app is still open | L1 script | none | none | none |
| RB-34 | Stale-bundle banner | L1 | P19 (bundle freshness) | device.view | none |
| RB-35 | Battery concern | L1, then L2 | device card, SH-21 | device.view | ticket |
| RB-36 | Where to find help, decode a support code | L1 | P19 decode | support.decode | none |
| RB-37 | Correct an outlet location | AMO proposes, TSO approves | B10, location queue | outlet.verify, outlet.approve | request and event rows |
| RB-38 | Revert or roll back a config | config_editor, or break-glass | P6, P4 | cfg.edit, breakglass.use | P16 |
| RB-39 | Declare an emergency non-working day | ops_admin, L2 proposes | P1 calendar tile (F-ADM-073) | calendar.emergency | holiday row `emergency_off`, P16 |
| RB-40 | Assign cover, an exception or a bulk "mark absent" | AMO, TSO approves | app screens F-AMO-037, F-AMO-045, F-AMO-047 | day.exception | exception rows |
| RB-41 | Offboard or disable an SR (the day's rows still upload) | TSO or DMO proposes, checker approves | P9 user wizard (F-ADM-076) | user_admin | P16 |
| RB-42 | Accept or fix a quarantined row | L1 (non data-entry reasons), four-eyes for the rest | P14 | quarantine.review | accept row through the normal ingest path |
| RB-43 | Dead or lost phone with unsynced rows: supervised paper-memo backfill | support enters, zone TSO approves | P9 paper backfill (F-ADM-075) | backfill.enter, backfill.approve | `paper_backfill` rows and P16 |

Round-3 runbooks (D-554 to D-601). Same shape as RB-01 to RB-16; rehearsed in T-7-59 and T-7-85.

| RB | Runbook | First steps |
| --- | --- | --- |
| RB-44 | Apsis residual selling on a switched route (D-556, SH-26) | read the route and wave on SH-26; AMO and TSO ask the SR to stop and to hand over the Apsis memos; the data lead loads the rows through DL-1b and the straggler sheet; hold the route's further switching; if the old app is still on the phone, the TSO disables or uninstalls it and ticks the readiness screen |
| RB-45 | Phone replaced (D-586) | P11 replace-device wizard (F-ADM-078): pending rows count, upload first or revoke now, OTP for the new phone, checker approval, bind; the old device's rows arrive as `revoked_device` and are parked for the TSO |
| RB-46 | Bind held (D-586) | held-binds queue (F-ADM-079): age, reason, one-click release or reject by the DMO or a delegate; at 15 minutes call the delegate; at 45 minutes the alert fires |
| RB-47 | Late delta and stragglers (D-556) | confirm DL-1b ran at 06:00; open the straggler sheet; the clerk reconciles with finance each morning for 3 days; a row older than the cut that is not on the sheet is a defect |
| RB-48 | Key Vault signing outage (D-574) | check vault status and `delegation_expires_at` per replica; do NOT restart replicas; tell support uploads continue; escalate at 3 h left; restore from backup into the DR vault if the region is lost |
| RB-49 | Config not reaching phones, or a silent phone (D-563, D-594) | read the reach widget by cohort; confirm push on or off; queue a directive for the silent phone from P19; the server judges its rows by D-431 and D-571 meanwhile |
| RB-50 | Replay a decrypted device bundle (D-587) | P20: decrypt (F-API-066), dry run, review `replay_excess`, second approval by the zone TSO, apply, confirm the counts; apply twice equals once |
| RB-51 | Storage projected full (D-568) | confirm the alert (60 percent, projected-full within 90 days, 70 percent); check hydration state; grow primary, replica and DR replica together to 2,048 GiB; buy the IOPS tier with them; `ANALYZE` afterwards |
| RB-52 | Geo-restore into East Asia (D-569) | s5.4: declare, restore the geo-redundant backup to a new server, WAIT for hydration, apps and keys from the DR copies, new generation, smoke, origin switch; time every step |
| RB-53 | Price correction (D-589) | P9 price page: confirm the wrong row was published within 24 h; finance_admin plus finance_approver correct it; or break-glass restore of the previous `list_version`; read the memos flagged `price_corrected_after` |
| RB-54 | Pre-holiday readiness (D-584) | the snapshot of the first working day after the break exists and was pre-fetched; `calendar.break_overrides` set; board shows no route without a bundle; communicate the first-morning rule |

### 7.7 Support visibility on the day

The helpdesk and TSOs use the support code on blocking screens (doc 17 D-396) and the Support desk page P19 (doc 19 s5.2, D-541), which decodes the code and searches by username, phone, employee code, memo number, outlet code and batch uuid; L1 device lookup is DEFAULT-ON as a web role, not an Azure role (D-540, resolving Q55). Staffing arithmetic, scripts and tiers are doc 20 s7 (D-149); the contact rate is MEASURED in the pilot by category (SR, AMO, TSO, retailer dispute) and wave 1 is staffed for the measured rate and a 15 to 25 percent day-1 stress case, with a named L2 on call for each master-data, security and ops_admin action (D-553, G-qa-85).

Proved by: T-7-51, T-7-52, T-7-53, T-7-54, T-7-55, T-7-56, T-7-57, T-7-59, T-7-84, T-7-88, T-7-89.

## 8 Load-test plan

### 8.1 Tooling and environments

| Item | Rule |
| --- | --- |
| Tool | Azure Load Testing running Locust (Learn: Locust is supported; 500 users per engine recommended): a device simulator with a class per role that builds UUID batches, duplicates, out-of-order children, gzip bodies, backoff, token lifetimes and the `X-` headers. A k6 smoke of S2 runs on every main build (D-134) |
| Target | staging scaled to the production SKU for the booked window only (D-421); a synthetic fleet of 11,336 routes, 734,789 outlets and 9,842 users (D-151); no production data |
| Data | the generator plants the defects P-05 to P-16 (doc 20 s9) |
| Evidence | each run writes its report under `/docs/evidence/phase-N/<T-id>/` and a pass or fail against the criteria below (D-141) |
| Failure rule | a run is stopped by the service if the endpoint starts throttling (Learn); a stopped run is a fail |

### 8.2 Scenarios S1 to S10, rebased on D-125

| ID | Scenario | Shape | Pass criteria |
| --- | --- | --- | --- |
| S1 | Morning refresh | 9,842 users, each refresh, bundle, home, release check; mix: 8,500 SR (25 KB), 1,051 AMO (median 90 KB, 40 at 350 KB), 291 TSO (300 KB); arrival over 5 min and over 20 min; 10 percent send `If-None-Match` | 131 req/s peak; bundle p95 at most 2 s; 0 5xx; primary CPU at most 60 percent; no quota stop; live-bundle share below 5 percent |
| S1' | First-morning storm | 7,350 password logins, binds, full bundles and a 30 MB APK through Front Door in 20 min (12.3 logins/s for 10 min); then the same population on the pre-bind-day procedure: each phone requests a small DELTA bundle (about 25 KB gzip, the changes since the 14:00 pre-snapshot: final delta, J6, J1b), 1,155 users for wave 1 (about 29 MB) and 4,250 for a half-fleet wave (about 106 MB) (D-557, G-qa-89) | `auth` memory below 80 percent, 0 OOM restarts, login p95 at most 3 s; with the pre-bind procedure the delta bundle p95 at most 2 s and egress inside the 0.39 GB of s1.5 |
| S2 | Trickle | 5,100 users, one 6-row batch every 4 min each (21 per second) with a 2 x midday shape; 5 percent of batches duplicated; 2 percent with children before parents; 1 percent re-sent under a new `batch_uuid` with the same `client_uuid`s | ack p95 at most 1.5 s; server rows equal distinct `client_uuid`s; aggregation lag p95 at most 60 s |
| S3 | Evening wave, typical | 3,400 users upload 6,000 batches of 200 rows in 20 min; trickle tail 21 per second; 20,000 home and config requests; 1,051 final-submit previews; 3 percent of users retry each batch 3 x | catch-up ack p95 at most 8 s; 503 at most 5 percent; 0 lost rows; replay-hit rate about the duplicates sent; lock wait p95 below 50 ms |
| S3b | The 17:00:00 spike (D-505) | 5,100 users submit check-out and Sales Submit inside 60 s of 17:00:00: 4,080 batches of 12 rows and 1,020 catch-up batches of 200 rows (252,960 rows, 4,216 rows/s), once without and once with the 0 to 90 s client jitter; 1,342 supervisor sessions open Daily Tracking, Live Dashboard and Final Submit previews in the same minute; S5 readers running | ack p95 at most 8 s without jitter and at most 3 s with it; 503 at most 5 percent; 0 lost rows; settle (`day_submit`) correct for every route; dashboard p95 at most 1 s; primary CPU at most 80 percent; subtransaction counters flat |
| S4 | Worst-case catch-up | 8,500 users x 350 rows in 30 min (3,333 rows/s), then a ramp to 8,000 rows/s and to 1.5 x the fleet (12,750 users, 5,000 rows/s) | primary CPU at most 80 percent; data IOPS at most 60 percent of provisioned (7,200 of 12,000); ack p95 at most 8 s at 3,333 and at most 12 s at 8,000; 0 lost rows; PgBouncer `cl_waiting` 0; ingest-registry read IOPS inside the same bound |
| S5 | Dashboards with S3 | 1,500 readers at 60 s (the audience of s1.3b: 1,051 AMO and 291 TSO app and web users plus about 150 HQ and managers; D-575) on the replica and the primary live tiles; 50 export jobs | dashboard p95 at most 1 s; sync-health p95 at most 1.5 s; cache hit at least 90 percent; replica lag at most 30 s; S3 unaffected; at most 50 queries/s from tiles on the primary |
| S6 | Photo burst | 5 SAS calls per second (10 each) and 50 PUT/s of 150 KB for 10 min (7.5 MB/s) | 0 API bytes proxied; SAS call p95 at most 300 ms; Storage 503 count 0; at most one user-delegation key fetch per replica per 6 days |
| S7 | Chaos during S3 | planned PostgreSQL failover; kill 50 percent of `api` replicas; flush Redis; set `hold_s` to 60 | failover error window at most 120 s; no lost rows; devices resume by themselves; `hold_s` observed within one trickle cycle; no restart caused by readiness; replica re-attach time recorded |
| S8 | Soak | S2 shape for 10 h | memory growth at most 10 percent an hour in `api` and `worker`; outbox drains; connection counts flat; dead-tuple ratio below 20 percent (ASSUMPTION) |
| S9 | Config propagation | change cfg.geo.radius_m for one territory during S2, run TWICE: push OFF and push ON (D-563, D-09); an FCM data message to 1,000 simulated devices; a cohort of silent devices and a cohort of idle devices that open the app later | per cohort (doc 19 s4.1b): 95 percent of the SELLING cohort inside 15 min with push off and of push-enabled devices inside 5 min with push on; idle devices at the next foreground through the resume check; the unreached by reason incl. `silent since`; `X-Config-Version` seen only in that territory; deltas spread over at least 100 s (ordinary) and at most 20 s (urgent) |
| S11 | Version-wide poison row (D-521) | every batch of all 8,500 devices contains one poison record for 30 min during S3 | ack p95 at most 8 s; 0 lost good rows; savepoints per transaction at most 60; `pg_stat_slru` subtransaction counters and subxact overflow flat; each poisoned batch is isolated in at most 7 rounds; `sync_hold_by_version` stops the build |
| S10 | Token expiry wave | 1,000 devices given tokens in one second, then 5,900 over 10 min; a device 3 h ahead; PLUS the absolute-expiry herd (D-518): 4,250 families of one wave cohort expire on one simulated day | refreshes spread over at least 8 min; no loop; a token expired by 30 s is accepted on `/sync/batch`; refresh p95 at most 100 ms; the absolute-expiry cohort: login p95 at most 3 s, `auth` memory below 80 percent, no lockout, offline unlock still works for 7 days after expiry |
| S12 | Key Vault outage (D-574, T-4-173) | `kv-sign` unreachable for 90 minutes during S2; a replica restarted inside the window | minting, refresh and uploads continue; the restarted replica leaves the minting pool and verification still works; the first failed `sign` alerts; no 401 storm |
| S13 | Timed geo-restore (D-569, T-4-174) | restore the geo-redundant backup of staging into East Asia, wait for hydration, bring up apps and keys from the DR copies, run the smoke script | every step timed; the measured RTO is written into RK-05 and the day-one checklist |
| S14 | Wave-night timeline replay (D-557, T-7-161) | the J1 to J2 to J6 to J1b chain on the wave scope with J1 reconciling at 22:55 | J2 finishes by 23:30 and the go or no-go holds at 23:30; the phones' first-morning delta is under 2 s p95 |

### 8.3 Gates by sub-milestone

The placement is proposed here and settled in doc 20 s3. Ids T-1-53 to T-1-56 and T-2-55 to T-2-58 are placed by doc 17 s12 as shown.

| Sub-milestone | Gates | What |
| --- | --- | --- |
| 0b | T-0-50, T-0-51 | job DAG preconditions, resume and date assertion; PgBouncer conformance and replay write ordering with crash injection |
| 1b | T-1-51, T-1-52, T-1-53, T-1-54, T-1-55, T-1-56 | 10,000 random duplicate, reordered and partial batches converge and replay returns an identical body; business-date rollover; poison row; submit settle; token wave and skew; server-generation re-sync |
| 1c | T-1-36 (doc 17) | the bundle-size and rows-per-SR-day baseline that replaces the lens-scale numbers in the simulator; k6 smoke of S2 in CI |
| 2e | T-2-155 (new), T-2-51, T-2-52, T-2-53, T-2-54, T-2-55, T-2-56, T-2-57, T-2-58 | an S4 SMOKE at about 2,000 rows/s on the 2e release candidate (T-2-155, D-504) so a defect in the ingest or registry design surfaces before the Drift schema freezes; hot-row test (500 memos/s into one zone, no deadlocks, aggregates equal raw sums); client backoff conformance; SAS misuse; TLS and clock on the oldest supported Android; push jitter; hold by version; hostname and certificate failover; support visibility |
| 4a | T-4-58, T-4-59 | sync-health under load and the aggregation staleness proof (the placement of docs 14 and 20, D-558; the first draft of this table had T-4-51 and T-4-52 here and T-4-58 and T-4-59 at 4d, the reverse) |
| 4d | T-4-51 to T-4-57 and T-4-155 to T-4-164, plus T-4-173 and T-4-174 (all BLOCKING, D-504, G-qa-28) | T-4-51 S1 at wave-1 size (1,000 users) and T-4-52 S1 at 9,842 users (decides whether the Redis fast path is needed beyond B3, it is already required, D-07); T-4-173 the 90-minute Key Vault outage; T-4-174 the timed geo-restore; T-4-155 S3b (the 17:00:00 spike with the S5 readers); T-4-156 S2 trickle at the fleet shape with duplicates and children before parents; T-4-157 S4 including the 8,000 rows/s ramp and the 1.5 x fleet of 12,750 users and the ingest-registry read IOPS (G-18-01); T-4-158 S5 under S4; T-4-159 S6 photo burst; T-4-160 S8 soak of 10 h at production size (the pilot's own 10-hour soak is T-2-176 at 2e, D-558); T-4-161 S9 config propagation measured on all active devices; T-4-162 S10 including the absolute-expiry herd; T-4-163 S11 version-wide poison row; T-4-164 DR replica lag during S4, S8 and S3b; plus connection exhaustion with `api` at 30 replicas under S3; aggregation under S3 plus S5 and the write-amplification measure; S7 chaos; replica lag and the stale banner; telemetry at most 0.6 GB a day per 1,000 users and the PII scrub; sync-health under load; staleness and dead items |
| 7 (before wave 1) | T-7-51, T-7-52, T-7-53, T-7-55, T-7-57, T-7-58, T-7-59 | S1, S2, S3, S5 at 1.5 x the wave on prod-SKU staging; S4 and S7; kill-switch drill on a device lab; quota checklist; S1'; import rollback drill; the game day |
| 7 (before wave 2) | T-7-54 | DR promotion drill with the generation change and the device re-sync |
| 7 (before the full fleet) | T-7-56 | S1 to S5 at 1.5 x the fleet (12,750 SRs) and the S8 soak (the 1.5 x proof is run at 4d first, T-4-157, and repeated at 7d on the production SKU) |

The lens-scale gate T-6-51 (config bounds, two-person approval, canary scope, revert, radius 0 rejected) is proved by the doc 19 gates T-2-60 to T-2-69; the id is an alias and is not used here.

### 8.4 Test windows, budget and approval (G-qa-09, G-scale-17; D-421)

| Window | When | Environment | Duration | Runs | Cost note |
| --- | --- | --- | --- | --- | --- |
| W1 | 4a | staging scaled to the wave-1 SKU (D4 HA plus replica) | 4 h | S1 at 1,000 users, S2 | about USD 12 plus Load Testing |
| W2 | 4d | staging scaled to the fleet SKU (D8 HA plus the same-SKU replica, ACA at maximum) | 2 days | S1 to S11 and S3b | D8 HA plus replica is USD 23 for 8 h; ACA at maximum about USD 50 for 8 h; Load Testing price unverified (OI-18-11) |
| W3 | before wave 1 | same | 1 day | T-7-51, T-7-52, S1' | as W2 |
| W4 | before the full fleet | same | 2 days | T-7-56 and the S8 soak | as W2 plus 10 h |

Procedure: the `load-test` workflow scales staging up, seeds the synthetic fleet, runs, and scales back down after 8 h without a manual step; each window has a recorded approval by the sponsor's delegate with the cost estimate; windows are not booked inside a wave's first three days; staging uses its own subscription or its own quota headroom (G-18-02). Any SKU change after a window re-opens W2.

### 8.5 Capacity decision

If S4 fails a criterion: first fix the query or index; second buy IOPS up to the SKU cap; third move to the next SKU (D-410); record the decision and re-run the scenario. The decision is taken at 4d and reviewed at W3 and W4.

Proved by: T-0-50, T-0-51, T-1-51 to T-1-56, T-2-51 to T-2-58, T-4-51 to T-4-59, T-7-51 to T-7-59.

## 9 Environments and IaC

### 9.1 Environment matrix

| | dev | staging | prod | dr |
| --- | --- | --- | --- | --- |
| Subscription | non-prod (recommended: separate from prod, G-18-02) | non-prod | prod | prod |
| Region | Southeast Asia | Southeast Asia | Southeast Asia | East Asia |
| PostgreSQL | D2, stoppable | D2 always on; D8 HA and replica in test windows | s3.1 | cross-region replica |
| Containers | min 0 or 1 | small; maximum in test windows | s3.1 | min 0 until RB-13 |
| Front Door | none or Standard | Premium (the pilot profile, D-573) | Premium from the pilot (D-573) | DR origin, disabled |
| Redis | none | B1 or B3 | B3 HA | none until RB-13 |
| Data | synthetic | synthetic fleet (D-151) | pilot, then real | replica of prod |
| Retention | 7 d PITR | 7 d PITR | 35 d PITR | n/a |
| Deploy | on merge | on merge to main (soak 24 h, 72 h for ingest or Drift schema releases, D-14) | manual promote with two approvers | by infra workflow |

### 9.2 Bicep layout (`/infra`)

| Path | Content |
| --- | --- |
| `infra/bicep/modules/` | `network`, `containerapps` (environment, apps, jobs, scale rules), `postgres` (server, replica, PgBouncer parameters, roles), `redis`, `storage` (two accounts, lifecycle, queue, Event Grid), `frontdoor` (profile, WAF, origins, rules), `keyvault`, `monitor` (workspaces, alerts, workbooks, action groups), `acr`, `loadtest` |
| `infra/bicep/env/` | `dev.bicepparam`, `staging.bicepparam`, `prod.bicepparam`, `dr.bicepparam`; the SKU ladder of s3.1 is parameters, not code |
| `infra/bicep/policy/` | the policies of s2.9 |
| `infra/workflows/` | `infra` (what-if on PR, deploy on approval), `load-test` (scale up, run, scale down), `contract-phase`, `release-app` (doc 20 s5) |
| `infra/runbooks/` | the RB-nn procedures and the scripts they call, including the DR promotion script and the drift check (G-18-03) |

### 9.3 Identity, secrets and policy

| Item | Rule |
| --- | --- |
| CI identity | GitHub Actions with OIDC federated credentials; no stored cloud secrets; one identity per environment with the least role on its resource group (D-04, D-122) |
| Runtime identity | user-assigned managed identities per app (s2.9) |
| Secrets | Key Vault only; the signing ROOT key is non-exportable and a vault backup restores only inside the same subscription and Azure geography, so the DR vault receives it by backup and restore (or a second root key, doc 21 s2.2b); rotation with overlapping `kid` (doc 21) |
| Locks | `CanNotDelete` on prod data services |
| Change control | a `what-if` is a required check; Front Door, ACA environment and VNet changes are C3 with a window (s5.5) |

### 9.4 Config keys used or proposed

Keys marked "proposed" are minted here for the doc 19 registry (owner doc 19, class C1 unless stated). All other keys exist in docs 17 or 19 and the skeleton.

| Key | Default | Bounds | Status |
| --- | --- | --- | --- |
| cfg.ops.prescale_schedule | 06:15 and 16:45, api 8, worker 3, web 4, `auth` 6 on wave mornings | api 3 to 30, auth 2 to 12, worker 1 to 10, web 2 to 6 (s3.4) | D-133, D-552; DEPLOY-TIME (effect R), read-only in the console |
| cfg.sla.login_pct_alert_time | 09:00 | 08:00 to 10:30 | D-552: the single source of the Login % trigger; the runbook reads it |
| cfg.sync.checkout_upload_jitter_max_s | 90 | 0 to 120 | D-505 |
| cfg.sync.max_savepoints_per_tx | 60 | 8 to 60 | D-521 |
| cfg.sla.pending_rows_alert_h, cfg.release.auto_freeze_regression_pct | 4; 20 | 1 to 24; 5 to 50 | D-511, D-507 |
| cfg.auth.refresh_absolute_jitter_days | 15 | 0 to 30 | D-518 |
| cfg.sla.location_request_backlog_alert | 25 per AMO, 250 per TSO | 5 to 200; 50 to 2,000 | D-545 |
| cfg.sync.login_jitter_s | 120 | 0 to 600 | doc 17 |
| cfg.sync.resync_window_h, cfg.sync.resync_jitter_s | 24; 900 | 1 to 72; 0 to 3,600 | D-63, D-406 |
| cfg.sync.debounce_s, cfg.sync.batch_max_rows, cfg.sync.retry_backoff_s, cfg.sync.max_clock_skew_min | 5; 200; 2 s to 300 s; 10 | doc 17 | doc 17 |
| cfg.auth.hash_concurrency_per_replica | 4 | 1 to 16 | D-126, C2 |
| cfg.auth.access_ttl_jitter_min, cfg.auth.min_refresh_interval_s | 10; 300 | 0 to 30; 60 to 3,600 | D-101 |
| cfg.api.rl.* | device 120 a minute, user 300 a minute | | D-116 |
| cfg.api.inflight_batches_per_replica | 64 | 16 to 256 | proposed |
| cfg.ops.sync_hold_s | 0 | 0 to 900 | proposed, C3 |
| cfg.ops.read_only_mode | false | | proposed, C3 |
| cfg.ops.sync_hold_by_version | empty | list of versions, auto-expiring | D-130, C3 |
| cfg.ops.push_enabled, cfg.ops.push_jitter_s, cfg.ops.push_jitter_urgent_s | false; 120; 20 | 0 to 600; 0 to 120 | D-09 |
| cfg.ops.dashboard_refresh_min_s | 60 | 30 to 300 | proposed |
| cfg.bundle.regen_max_per_s, cfg.bundle.stale_max_days | 20; 2 | 1 to 200; 1 to 3 | D-100, D-70 |
| cfg.bundle.coverage_min_pct, cfg.bundle.hold_below_pct | 99; 95 | 90 to 100 | proposed (D-71 states 99; the hold threshold is from the SRE critic) |
| cfg.agg.poll_interval_s, cfg.agg.claim_timeout_s | 5 (day) and 60 (night); 300 | | D-140, G-sre-17 |
| cfg.agg.rollup_min_interval_s | 15 | 5 to 120 | proposed |
| cfg.report.interactive_row_limit, cfg.report.export_concurrency | 50,000; 8 | | proposed (doc 15 lists cfg.ops.report_export_max_rows for F-SYS-064; doc 19 reconciles the two names) |
| cfg.sys.change_freeze_windows | 07:00 to 09:30, 16:30 to 19:30 | | D-100, C3 |
| cfg.net.fallback_after_failures | 3 | 1 to 10 | D-81 |
| cfg.telemetry.device_max_bytes_per_day | 1,024 | | D-136 |
| cfg.day.submit_settle_timeout_min | 30 | 5 to 240 | D-64 |

Proved by: T-0-50, T-4-57, T-7-51.

## 10 Gaps owned, decisions and keys minted here

### 10.1 The 22 owned gaps and their resolution

Skeleton s7.2 gives doc 18 two blockers, sixteen major and four minor gaps. Each row states where it is resolved, the decision and the gate that proves it.

| Gap | Aliases | Title | Sev | Phase | Resolved in | Decision | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G-scale-03 | none | Bundle generation path not designed for the morning storm | blocker | 2e | s4.7, s4.8, s1.4 ST-01 | D-71, D-129, D-418 | T-0-50, T-4-51, T-4-52 |
| G-sre-04 | G-scale-06 | Wave first-morning storm: password hash, bind, full bundle, APK | blocker | 2e | s1.4 ST-02 to ST-04, s2.5, s5.2, s7.4 | D-126, D-414 | T-7-57 |
| G-scale-11 | none | Quotas not pre-requested | major | 0a | s3.2 | D-133 | T-7-55 |
| G-sre-12 | none | Single hostname, certificate and DNS zone | major | 0a | s2.4, s5.7 | D-81, D-423 | T-2-57 |
| G-scale-09 | none | Per-request hot writes (device last-seen, `route_log`) | major | 1b | s4.2 step 9, s5.1 FM-04 | D-138, D-411 | T-2-51 |
| G-scale-10 | none | No timeouts, pool sizes, concurrency limits or 429 semantics | major | 1b | s2.6, s4.3, s4.4 | D-139, D-415, D-416 | T-4-53 |
| G-sre-05 | none | Load model and S1 to S9 predate docs/22 | major | 1c | s1, s8.2 | D-125, D-245, D-246 | T-1-36, T-4-51 to T-4-53 |
| G-sre-08 | G-scale-19 | Nightly job chain has no orchestration, preconditions or date assertion | major | 1c | s4.7 | D-71, D-418 | T-0-50, T-7-59 |
| G-sre-09 | none | `route_day` rows created by the pre-generation job | major | 1c | s4.7 J3 | D-71 | T-0-50 |
| G-sre-15 | none | `LISTEN` does not work through PgBouncer transaction pooling | major | 1c | s2.6, s2.7 | D-137 | T-0-51 |
| G-sre-19 | none | SLO measurability holes | major | 1c | s6.1, s6.2, s6.3 | D-135, D-419 | T-4-57 to T-4-59 |
| G-sre-13 | none | Blob ZRS makes evidence unreadable in a region outage; key fetch per SAS | major | 2c | s2.8 | D-132 | T-2-53 |
| G-qa-12 | none | No error-reporting tool chosen | major | 2e | s6.7 | D-13 | T-4-57 |
| G-qa-13 | G-sre-18 | No device telemetry design | major | 2e | s6.7 | D-136, D-138 | T-2-58 |
| G-scale-13 | none | Telemetry cost and PII exposure at fleet scale | major | 2e | s6.3 | D-136, D-419 | T-4-57 |
| G-sre-16 | none | Web read-path conflict; no response cache; sync-health has no SLO | major | 4a | s4.5, s6.4 | D-128, D-427 | T-4-58 |
| G-qa-17 | G-sre-24 | Alerting has thresholds but no routing, rota or runbook linkage | major | 4d | s6.6, s7.6 | D-428 | T-7-59 |
| G-scale-12 | none | DR posture undefined | major | 4d | s5.4 | D-127, D-420 | T-7-54 |
| G-data-37 | none | Growth and seasonality in the load model | minor | 1c | s1.1, s1.9, s1.10 | D-125, D-247 | T-4-53 |
| G-scale-14 | none | TLS trust and device clock on old Android untested | minor | 2e | s5.7 | D-11 | T-2-54 |
| G-qa-18 | none | Restore drills without a data-verification step | minor | 4d | s5.4 checklist | D-127 | T-7-54, T-1-56 |
| G-sre-25 | none | Pre-scale schedule later than the real login window; `web` not pre-scaled | minor | 7c | s3.4, s7.3 | D-133, D-413 | T-7-59 |

### 10.2 Gaps owned elsewhere that this document touches

| Gap | Owner | What doc 18 contributes |
| --- | --- | --- |
| G-data-08 (G-scale-18) | 17 | device-prefixed memo numbers remove the hot sequence (FM-04) |
| G-scale-01 (G-sync-05, G-sre-14) | 17 | ingest steps 3 and 10 write the stored response inside the transaction (s4.2) |
| G-cfg-04 (G-scale-15) | 19 | blast-radius bounds, freeze windows and the geo-valid anomaly alert (FM-12, s5.6) |
| G-sync-03 (G-scale-05) | 17 | region-outage behaviour with the stale bundle (FM-19) |
| G-data-24 (G-scale-08) | 16 | partition jobs and the registry capacity measure (s4.6) |
| G-field-07 (G-sre-23) | 15 | final-submit failure mode and runbook entry (FM-27) |
| G-qa-09 (G-scale-17, G-cfg-17) | 20 | test windows, budget and approval record (s8.4, D-421) |
| G-data-19 (G-sre-21) | 16 | import job J1, the 23:00 deferral and the rollback row (FM-31, s5.5) |

### 10.3 Manual register

No G-man entry is owned by doc 18 (skeleton s7.4 assigns all 104 to docs 15, 16, 17, 19 and 21). The register entries of section 2.6 that change load are costed in s1.6; none is dropped. The verified corrections of skeleton s7.3 that matter here are G-man-022 (APK files 74 to 80 MB, installed 92 to 101 MB, so the new 30 MB per-ABI gate is a 60 percent cut, s1.4 ST-13), G-man-021 (OTP server-created, TSO view-only: the first-morning storm is the bind plus the TSO lookup, ST-02), G-man-059 (Google Maps, outside Azure, D-425) and G-man-086 (late upload after a web void: registry tombstone, s4.2).

### 10.4 New gaps raised here (block G-18-01 to G-18-40)

| ID | Sev | Phase | Gap | Closing action |
| --- | --- | --- | --- | --- |
| G-18-01 | major | 4d | The ingest registry is keyed by random UUID v4: at 3,300 to 8,000 inserts a second its index reads can exceed the IOPS bound | doc 16 fixes partitioning and retention; T-4-53 measures read IOPS against the 60 percent rule; D-410 is the fallback |
| G-18-02 | major | 0a | One subscription would share the vCore and core quota between prod and a staging scaled to prod for tests | AKTCL decides the subscription split (D-421); request quotas per subscription |
| G-18-03 | major | 4d | A promoted replica does not inherit HA, backups, PgBouncer or all parameters | the DR script and a drift check in `infra/runbooks/`; step 5 of RB-13; T-7-54 |
| G-18-04 | minor | 4a | Azure Load Testing needs about 20 to 30 engines at 500 users each; the quota in D-133 is 10 | calibration run; request 30 if needed |
| G-18-05 | major | 1c | Pre-generation cost per user and for the largest AMO zone are estimates | T-0-50 and T-1-36 measure; the J2 deadline of 22:30 is adjusted |
| G-18-06 | major | 2e (before the pilot) | With Front Door Standard the ACA ingress is public; locking it by the `X-Azure-FDID` header is unverified and service tags in ACA IP restrictions are unconfirmed | Resolved by D-573: Premium with Private Link from the pilot; the spike (Private Link to ACA, and the fallback service-tag plus header check) runs before the pilot and is gated by T-7-162 |
| G-18-07 | major | 2e | A KEDA `cron` pre-scale rule must coexist with multiple-revision traffic weights | spike; fallback A (static minimum on wave weeks) or a scheduled workflow |
| G-18-08 | minor | 4a | Calendar-aware alert baselines need the working-day calendar as data in the dw date dimension | doc 16 and doc 19 provide it (D-28) |
| G-18-09 | major | 4d | The alert channel and on-call tool (SMS, voice, push) are not chosen | confirm with the business; test the Sev1 page in T-7-59 |
| G-18-10 | minor | 2e | Front Door default platform 429 limits are not published in the sources read | ask Microsoft before wave 1 |
| G-18-11 | minor | 4d | Replica behaviour after a primary HA failover is not stated | measured in S7 |
| G-18-12 | minor | 3a | Bulk reverse geocoding would be a cost trap outside Azure | D-425; budget alert on the key |

### 10.5 Decisions minted here (D-410 to D-429)

| ID | Decision | Status | Why | Docs |
| --- | --- | --- | --- | --- |
| D-410 | Capacity rule: if S4 or production shows primary CPU above 80 percent, data IOPS above 60 percent of the SKU cap, or catch-up ack p95 above 8 s, fix the query, then buy IOPS, then move D8 to D16 (D16ds_v5 caps 25,600 IOPS); taken at 4d, reviewed at W3 and W4 | DEFAULT | s3.5; Learn IOPS caps | 18, 20 |
| D-411 | Ingest has a fast path (one savepoint, multi-row statements) and a slow path (a savepoint per record) used only after an exception | DEFAULT | keeps p95 low while D-65 isolation holds | 17, 18 |
| D-412 | Aggregates are recomputed per (business_date, route) with `IS DISTINCT FROM` so unchanged rows write nothing; rollups above route are recomputed at most every 15 s per touched node | DEFAULT | write amplification 70 rows per memo becomes about 15 changed rows per visit family | 16, 18 |
| D-413 | `api` scales on 30 requests per second per replica (ACA computes requests over 15 s); pre-scale is a KEDA `cron` rule rendered from cfg.ops.prescale_schedule at deploy; multiple-revision mode for `api`, `auth`, `web`; single revision for `worker`; fallbacks in s2.5 | DEFAULT; ASSUMPTION: cron coexists with weights (G-18-07) | Learn: rules create revisions | 18, 20 |
| D-414 | Password verification, bind and change-password run in a separate `auth` app (2 vCPU, 4 GiB, hash limiter 4, own role and pool); `/auth/refresh` stays in `api` | DEFAULT | implements D-126 without a revision swap and keeps an OOM away from ingest | 18, 21 |
| D-415 | Rate limiting is layered: WAF per-IP backstop derived from a CGNAT worst case, per-device and per-user limits in Redis, a per-replica in-flight limiter, the hash limiter; 429 means too fast, 503 means shedding; both retryable | DEFAULT | s4.4 | 17, 18, 21 |
| D-416 | PgBouncer: `default_pool_size` 20, `query_wait_timeout` 5 s, `max_prepared_statements` 0, `max_client_conn` 5,000; roles api, auth, worker, web, jobs, export pooled; migrator direct | DEFAULT; measured by T-4-53 | s2.6 | 16, 18 |
| D-417 | Redis holds copies only, with TTLs: snapshots 36 h, replay copy 2 h, rate counters 5 min, response cache 30 s; every use fails open or reads the source of truth; readiness reports Redis as degraded, not unready | DEFAULT | s2.7; resolves OI-18-02 | 18 |
| D-418 | The nightly chain is ACA Jobs with a `job_run` gate, preconditions, resumable chunks and deadlines; no external orchestrator | DEFAULT | s4.7 | 16, 18 |
| D-419 | Two Log Analytics workspaces: ops (sampled, capped at 3 x, alert at 80 percent) and sync (summaries and device lookup, uncapped); SLIs measured at the edge as well as the API; baselines are the same weekday last week | DEFAULT | s6.3 | 18, 20 |
| D-420 | DR is a warm standby in East Asia: replica, ACR geo-replica, vault with the same signing keys, apps at min 0, a disabled Front Door DR origin whose readiness needs `pg_is_in_recovery() = false`; wave 1 has geo-redundant backup only; the replica starts before wave 2; drills as in s5.4 | DEFAULT; DELIBERATE CHANGE to D-06 timing; RTO and RPO per D-127 MUST-CONFIRM (by 7c) | Learn: promotion is manual and forced in a region outage | 18, 20 |
| D-421 | Staging is a small twin scaled to the production SKU only inside booked windows by the `load-test` workflow, with an automatic scale-down after 8 h; non-prod in a separate subscription | DEFAULT; MUST-CONFIRM (by 0a): subscription split | quota isolation; D-134 | 18, 20 |
| D-422 | Region bake-off in 0c from Dhaka lab phones on three carriers; Southeast Asia unless Central India is at least 25 percent lower at p50 and Q19 does not decide | DEFAULT; the threshold is an ASSUMPTION | D-05 | 18 |
| D-423 | Front Door Premium with the environment private endpoint from the pilot (amended by D-573; the first draft ran Standard with the ingress locked by header until wave 2); WAF Log mode for a wave; origin response timeout set to 60 s | DEFAULT | Learn: Private Link needs Premium | 18, 21 |
| D-424 | Photo linking: Event Grid `BlobCreated` to a Storage queue to the worker, with a nightly sweep; no Service Bus anywhere | DEFAULT | s4.1 | 17, 18 |
| D-425 | No bulk server-side reverse geocoding; on demand only, cached 24 h by coordinates rounded to 3 decimals; Google Maps Platform key restricted, with a billing alert | DEFAULT; MUST-CONFIRM (by 3a) with D-08 | cost trap, s3.3 | 15, 17, 18 |
| D-426 | Cost governance: budgets at 80 and 100 percent per environment; reservations only after wave 1 is stable for 30 days and 4d confirmed D8 and D4 | DEFAULT | s3.3 | 14, 18 |
| D-427 | Sync-health: p95 at most 1.5 s at 1,500 viewers; response cache key (endpoint, scope_hash, date, agg_version) 30 s; primary budget 50 queries/s; degraded mode triggers and exit as in s6.4 | DEFAULT | D-128 with numbers | 16, 18 |
| D-428 | Alert hygiene: Sev1, Sev2, Sev3 routing; every alert has an owner, a runbook and an auto-resolve; storm profile never changes a Sev1; 90-day pruning; post-incident review in 5 working days | DEFAULT; MUST-CONFIRM (by 4d): channel and tool (G-18-09) | G-qa-17 | 18, 20 |
| D-429 | Interactive reports up to 50,000 rows on the replica; larger ranges and every Excel over 10,000 rows run as ACA Jobs with an `app_export` role, concurrency 8, results in Blob for 24 h | DEFAULT | G-man-095; F-SYS-064 | 15, 18 |

### 10.6 Round-2 gaps closed in this document (skeptic review, D-500 to D-553)

| Gap | Decision | Where | Gate |
| --- | --- | --- | --- |
| G-qa-28 | D-504 | s8.2 (S3b, S11), s8.3, s8.4 | T-2-155, T-4-155 to T-4-164 |
| G-qa-29 | D-505 | s1.4 (ST-06b), s1.8, s5.2, s8.2 | T-4-155 |
| G-qa-31, G-qa-33 | D-507, D-509 | s6.1, s6.4 (SH-19 to SH-25) | T-2-153, T-7-155 to T-7-157 |
| G-qa-32 | D-508 | s1.1, s1.4 (30 MB APK) | T-1-36 |
| G-qa-41 | D-517 | s5.4, FM-20 | T-1-56, T-1-152, T-7-54 |
| G-qa-42 | D-518 | s1.4 (ST-15), s5.2, FM-38, S10 | T-4-162 |
| G-qa-44 | D-520 | s1.9, s4.6, s3.5 | T-1-150, T-2-150 |
| G-qa-45 | D-521 | s4.2, FM-07, FM-36, S11 | T-1-151, T-4-163 |
| G-qa-46 | D-522 | s2.3, s3.1, s3.3, s5.4 | T-4-164, T-7-52 |
| G-qa-53 | D-528 | s4.5 | T-4-54 |
| G-qa-76 | D-545 | s1.11 | T-4-152 |
| G-qa-77, G-qa-52 | D-527 | s7.2 | T-6-10, T-2-68 |
| G-qa-80, G-qa-73 | D-548, D-542 | s6.4, s6.6, FM-39 | T-4-153, T-4-154 |
| G-qa-81 | D-549 | s7.5 | T-7-153 |
| G-qa-82, G-qa-85 | D-550, D-553 | s7.6 (RB-17 to RB-43), s7.7 | T-6-42, T-7-85 |
| G-qa-84 | D-552 | s3.4, s6.6 | T-7-55 |
| G-qa-37 | D-514 | s7.1 (delta rehearsal, T-1 20:00) | T-7-150, T-7-151 |

### 10.6b Round-3 gaps closed in this document (skeptic review, D-554 to D-601)

| Gap | Decision | Where | Gate |
| --- | --- | --- | --- |
| G-qa-88, G-qa-122 | D-556 | s4.7 (J1b), s7.1, s7.3, s6.4 (SH-26), RB-44, RB-47 | T-7-163, T-7-164 |
| G-qa-89 | D-557 | s4.7 (J2, J2w), s7.1, s7.3, S1', S14 | T-7-161 |
| G-qa-90 | D-558 | s8.3 placement aligned to doc 20 | T-0-152 (rule 10) |
| G-qa-92 | D-560 | s2.9 (BI path), s3.1, s3.3 | T-4-165, T-4-169 |
| G-qa-94 | D-562 | s2.8 (APK distribution) | T-2-164, T-7-166 |
| G-qa-96, G-qa-106 | D-563 | s6.1 (config reach), s7.5, S9 | T-2-174, T-4-161 |
| G-qa-100, G-qa-101 | D-566 | s2.6 (roles) cites doc 16 s13.4b | T-0-158, T-1-153 |
| G-qa-102 | D-567 | s6.4 SH-01, SH-02 source | T-1-155 |
| G-qa-103 | D-568 | s1.9, s2.6, s3.1, s3.3, FM-29, s6.6 | T-0-159 |
| G-qa-104 | D-569 | s2.6, s3.1, s5.4 (hydration, RB-52) | T-4-174 |
| G-qa-109 | D-573 | s2.1, s2.4, s3.1, s3.3 | T-7-162 |
| G-qa-110 | D-574 | s5.3, s2.9, s9.3, RB-48 | T-4-173 |
| G-qa-111 | D-575 | s1.1, s1.3, s1.3b, s1.8, S5 | T-4-158 |
| G-qa-123 | D-584 | s4.7 (J2 next working day), RB-54 | T-2-163 |
| G-qa-125 | D-586 | RB-45, RB-46, SH-27 | T-2-167 |
| G-qa-126 | D-587 | RB-50 | T-2-168 |
| G-qa-131 | D-592 | s7.5 (per-wave rollback capacity) | T-7-153, T-7-168 |
| G-qa-135 | D-596 | s6.6 (rollback-trigger and cfg.sla alerts) | T-4-45 (extended) |

## Open items

| ID | Item | Why open | Owner role | Needed by | Proceeds on the default meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-18-01 | Skeleton s6.1 gives 240,000 trickle batches a day at the design point; 0.6 x 500,000 is 300,000 | arithmetic disagreement; this document follows the skeleton's inputs and tests at 21 batches/s, which covers both | doc 14 author | 1c | S2 at 21 per second |
| OI-18-02 | D-139 reads readiness as "DB and Redis"; with Redis failing open that would remove every replica when Redis fails | the sentence needs an amendment | doc 14 author | 1b | D-417: Redis is reported degraded, not unready Resolved at the editorial merge: D-139 in DECISIONS.md now reads "readiness: DB only; Redis degraded, never unready" (change log). |
| OI-18-03 | D-06 changes proposed here: geo-redundant backup at wave 1, cross-region replica before wave 2, the `auth` app, Redis B3, first storage grow by month 6 (D-568) | DELIBERATE CHANGE sign-off | sponsor, doc 14 | 1c | s3.1 ladder Recorded at the editorial merge: D-06 is amended in DECISIONS.md (change log) with the five changes; the sponsor signs them at 1c with the D-420 row. |
| OI-18-04 | Ingest registry partitioning and retention (hash by uuid keeps global uniqueness; 1.2 B rows in 13 months) | doc 16 owns the table; G-18-01 | doc 16 | 1b | measure at T-4-53 |
| OI-18-05 | Domain names for `api`, `app`, `dl` and DNS ownership | unknown; confirm with the business | AKTCL IT | 0a | placeholders `<domain>` |
| OI-18-06 | Design photos are 17.6 per SR-day; doc 17 budgets 3 MB with 13 photos for a 50-outlet route and scales it to 3.7 and 6.0 MB for the median and p99 routes (D-508); the load model uses the same 17.6 | photos are Wi-Fi first, so mobile bytes stay low, but the scenario should be restated | doc 17 | 2e | no change |
| OI-18-07 | Front Door platform 429 limits and the in-place Standard to Premium upgrade (moot since D-573: the pilot starts on Premium) are unverified | not in the sources read | infra, Microsoft | 2e | assume a rebuild is possible |
| OI-18-08 | Load Testing engines: quota 10 against 20 to 30 needed at 500 users each | calibration run | infra, QA | 4a | request 30 |
| OI-18-09 | Alert channel and on-call tool (G-18-09) | unknown; confirm with the business | sponsor, IT | 4d | e-mail and push to named phones |
| OI-18-10 | Placement of T-2-51, T-2-52, T-4-53 to T-4-59 and the T-6-51 alias | doc 20 s3 places every gate | doc 20 | 0a | s8.3 Checked at the editorial merge: T-2-51, T-2-52, T-4-53 to T-4-59 and T-6-51 are all registered in doc 20 s3 (placement of each in s8.3 matches doc 20). |
| OI-18-11 | Unverified prices: Redis HA, GRS backup, Front Door traffic by zone, Azure Load Testing | not found in the price API | infra | 0a | the list in s3.3, doubled where unsure |
| OI-18-12 | PITR restore time and promoted-replica configuration time are unknown | measured in the drills | DB | 4d | RTO 2 h and 4 h assumed |
| OI-18-13 | Wave-1 size (Q51) decides ST-02 and the SKU headroom; whether wave 1 has a production DR replica | MUST-CONFIRM D-147 by 7b | sponsor, sales ops | 7b | 1,000 SR sizing; backup only |
| OI-18-14 | The lens-scale gates name Android 7; D-11 sets minSdk 26 (Android 8.0) | census (Q31) | IT, sales ops | 0c | gate on Android 8.0 |
| OI-18-15 | Names for `job_run`, `bundle_snapshot`, the outbox and dirty-key tables, the `route_day` last-fix column; registration of the keys proposed in s9.4 | doc 16 and doc 19 own names and the registry | doc 16, doc 19 | 1b | names of this document |
| OI-18-16 | This document is about 160 KB against the 60 to 100 KB planning budget of skeleton rule 19 (an ASSUMPTION there) | 35 failure modes, 22 owned gaps, S1 to S10 with criteria, three cost tiers and the 20 decisions are the content; nothing is restated from docs/01 to 13 | doc 14 author | none | kept whole; trim only if doc 14 asks |
| MUST-CONFIRM D-05 | Region and data localisation (Q19) | legal | sponsor, legal | 7c | Southeast Asia |
| MUST-CONFIRM D-08, D-425 | Maps provider, quota, geocoding cost | | sponsor | 3a | Google, on-demand geocoding only |
| MUST-CONFIRM D-09 | FCM for urgent config (Q22) | | sponsor, security | 2d | off in the pilot |
| MUST-CONFIRM D-13 | Sentry residency | | security | 2e | SaaS in the pilot; self-host if refused |
| MUST-CONFIRM D-23, D-132 | Statutory retention, photo retention (Q21) | | legal | 6c | 7 years rows, tiered photos |
| MUST-CONFIRM D-63 | Counts rising again after a failover (Q54) | | sponsor | 7c | accept |
| MUST-CONFIRM D-126 | Pre-bind day feasible (Q52) | | sales ops | 7b | assumed yes |
| MUST-CONFIRM D-127 | RTO 4 h and RPO 15 min (Q20) | | sponsor | 7c | as stated |
| MUST-CONFIRM D-138 | L1 read-only access to the device lookup (Q55) | | support, security | 7c | L2 runs lookups |
| MUST-CONFIRM D-147 | Wave sizes and order (Q38, Q51) | | sponsor, sales ops | 7b | doc 14 ladder |
| MUST-CONFIRM D-245 | Whether the export includes sales outside the apps | | sales ops | 1c | plan for 4.5 lakh calls |
| MUST-CONFIRM D-421 | Subscription split for non-prod | | IT | 0a | separate subscription |
| MUST-CONFIRM D-428 | Alert channel and tool | | IT | 4d | OI-18-09 |
| OI-18-12 | Same-SKU DR replica and its cost of about USD 350 a month more (D-522) | needs sponsor sign-off of the cost line | sponsor | 4d | the same-SKU replica; the D4 alternative is rejected until T-7-52 shows the lag stays under 5 minutes |
| OI-18-13 | The location-request force-sale share (3 percent expected, 10 percent stress) is an ASSUMPTION (D-545) | no Apsis figure | sales ops | 2d | the model of s1.11 |
| OI-18-14 | How Apsis accepts keyed dues for the rollback return path, and who may key them (D-549) | unknown; confirm with the business | sales ops, data lead | 7b | fix-forward only from day 4; the staffed procedure for days 1 to 3 |
| OI-18-15 | The pager channel is DECIDED as SMS plus voice through an Azure Monitor action group (D-552); the on-call TOOL and the provider's Bangladesh delivery are not | needs a real test page | IT | 4d | action-group escalation and a shared sheet |
| OI-18-16 | The WAL bytes per row and the replay speed of the same-SKU replica are ASSUMPTION (D-522) | measured only at T-7-52 | infra lead | 7c | the table of s5.4 |
| OI-18-17 | Prices and licensing of the BI path (Fabric F2, VNet data gateway, Power BI Pro) and whether Fabric mirroring of the Flexible Server replica is available (D-560); the 400 USD a month line is an ASSUMPTION | unverified list prices | IT, engineering | 0c | the replica read through a VNet data gateway |
| OI-18-18 | Whether AKTCL commits a re-key capacity (trained keyers) per wave or obtains an Apsis bulk-import path (D-592) | only AKTCL can staff it | sponsor, sales ops, finance | 7b | waves above the committed capacity are fix-forward only |
| OI-18-19 | The Premium Front Door base fee, the Private Link origin behaviour with a zone-redundant ACA environment and the hydration time of a 1,024 GiB Premium SSD v2 disk are measured, not assumed (D-568, D-569, D-573) | spikes at 0c and 2e | engineering | 2e | the figures of s2.4, s2.6 and s3.3 |

## Traceability

| Requirement | Mechanism in this document | Sections | Gates |
| --- | --- | --- | --- |
| R1 DATA | aggregates recomputed from raw rows by key, never incremented, so any report can be rebuilt without touching the log; dw reads on the replica; restore checklist proves counts and control totals | s1.7, s4.5, s5.4 | T-4-54, T-7-54, T-1-56 |
| R2 FEATURES | online-only surfaces of register 2.6 costed so none is dropped; the sync-health tiles and the war-room screens | s1.6, s6.4 | T-4-58 |
| R3 SCALE | the re-based load model; synchronous ingest; pre-generated bundles; three-layer limits; pre-bind day and auth app; herd mechanisms; 8,000 rows/s gate; failure modes; DR | s1 to s5, s8 | T-4-51 to T-4-59, T-7-51 to T-7-59 |
| R4 BATTERY | no polling; device telemetry inside the batch; jittered pulls; photos Wi-Fi first; APK from Blob; 15 KB a day of records | s1.2, s5.2, s6.7 | T-2-55, T-4-57 |
| R5 OFFLINE AND IMMEDIATE SYNC | the trickle load (ST-05) is the cost of immediate sync and is carried by one POST per visit; offline selling continues through every dependency failure | s1.4, s5.3 | T-4-51, T-4-55 |
| R6 ADMIN CONFIG | change freeze, kill-switch semantics, config reach SLO, the proposed keys | s5.6, s6.1, s9.4 | S9, T-2-55 |
| Process requirement | every section ends with its gate line; gates by sub-milestone in s8.3; test windows with approval in s8.4 | s8 | all |

Constraint check (CLAUDE.md): 1 offline-first (s5.3: every dependency failure leaves capture and selling intact); 2 idempotent sync (s4.2, s5.4 re-sync); 3 battery and data (s1.2, s5.2); 4 server-side scope (s6.4 scoped drill; `app_web` role limits); 5 geo and anti-spoofing (FM-12 geo-valid alert; flags are doc 21); 6 no-hiccup cutover (s7); 7 Dhaka business date (s4.5 dirty key by own date, s4.7 cron in UTC with Dhaka times); 8 bilingual (not applicable to this document).

Ids this document uses, by section. Ids minted here: D-410 to D-429, G-18-01 to G-18-12, and the local FM, ST, RB and SH labels. All other ids are in the skeleton, DECISIONS.md or docs 14 to 17; none is invented here.

| Section | Features | Gaps | Decisions | Gates |
| --- | --- | --- | --- | --- |
| s1 | F-SYS-064, F-API-023, F-API-025, F-API-039, F-API-009, F-API-030 | G-sre-05, G-data-37, G-scale-04 | D-125, D-245, D-246, D-247, D-257, D-28, D-75, D-83, D-390, D-412, D-425 | T-1-36, T-4-51 to T-4-54, T-7-56 |
| s2 | F-API-042, F-API-019, F-API-030, F-API-057 | G-sre-12, G-sre-13, G-sre-15, G-scale-10 | D-04, D-05, D-06, D-07, D-08, D-14, D-81, D-132, D-133, D-137, D-139, D-413, D-414, D-416, D-417, D-420, D-422, D-423, D-424 | T-0-51, T-2-54, T-2-57, T-4-53, T-7-54 |
| s3 | none | G-scale-11, G-sre-25 | D-06, D-133, D-410, D-421, D-425, D-426 | T-4-51 to T-4-53, T-7-51, T-7-55 |
| s4 | F-API-006, F-API-005, F-SYS-048, F-SYS-055, F-SYS-056, F-SYS-067, F-SYS-064, F-SYS-012, F-SYS-013 | G-scale-09, G-scale-10, G-scale-03, G-sre-08, G-sre-09, G-sre-16, G-sre-17, G-sre-14, G-sre-26 | D-61, D-62, D-65, D-71, D-100, D-116, D-128, D-129, D-140, D-411, D-412, D-415, D-418, D-427, D-429 | T-0-50, T-0-51, T-1-51, T-2-51, T-4-53, T-4-54, T-4-58, T-4-59 |
| s5 | F-SYS-047, F-SYS-042, F-SYS-065, F-SYS-069 | G-scale-12, G-qa-18, G-scale-14, G-sre-01 to G-sre-03, G-sre-06, G-sre-07, G-sre-10, G-sre-11, G-sre-20, G-sre-21 | D-11, D-63, D-64, D-65, D-70, D-79, D-100, D-101, D-127, D-130, D-148, D-406, D-420 | T-1-52 to T-1-56, T-2-53 to T-2-57, T-4-55, T-4-56, T-7-53, T-7-54, T-7-58 |
| s6 | F-SYS-026, F-WEB-045, F-API-028, F-API-046, F-SYS-050 | G-sre-19, G-qa-12, G-qa-13, G-scale-13, G-qa-17, G-sre-16 | D-13, D-135, D-136, D-138, D-419, D-427, D-428 | T-2-58, T-4-57 to T-4-59 |
| s7 | F-SYS-041, F-SYS-042 | G-sre-04, G-qa-17 | D-126, D-133, D-147, D-148, D-149, D-311 | T-7-51 to T-7-59, T-7-84, T-7-88, T-7-89 |
| s8 | none | G-qa-09, G-scale-17 | D-134, D-141, D-151, D-421 | T-0-50 to T-7-59 as listed in s8.3 |
| s9 | none | none | D-04, D-14, D-122, D-133 | T-0-50, T-4-57, T-7-51 |
| s10 | none | the 22 owned gaps and 8 touched gaps | D-410 to D-429 | as listed |

Config keys used: cfg.ops.prescale_schedule, cfg.ops.push_enabled, cfg.ops.push_jitter_s, cfg.ops.push_jitter_urgent_s, cfg.ops.sync_hold_by_version, cfg.ops.sync_hold_s (proposed), cfg.ops.read_only_mode (proposed), cfg.ops.dashboard_refresh_min_s (proposed), cfg.sync.login_jitter_s, cfg.sync.resync_window_h, cfg.sync.resync_jitter_s, cfg.sync.debounce_s, cfg.sync.batch_max_rows, cfg.sync.retry_backoff_s, cfg.sync.max_clock_skew_min, cfg.sync.engine_mode, cfg.auth.hash_concurrency_per_replica, cfg.auth.access_ttl_jitter_min, cfg.auth.min_refresh_interval_s, cfg.api.rl.*, cfg.api.inflight_batches_per_replica (proposed), cfg.bundle.regen_max_per_s, cfg.bundle.stale_max_days, cfg.bundle.coverage_min_pct (proposed), cfg.bundle.hold_below_pct (proposed), cfg.agg.poll_interval_s, cfg.agg.claim_timeout_s, cfg.agg.rollup_min_interval_s (proposed), cfg.report.interactive_row_limit (proposed), cfg.report.export_concurrency (proposed), cfg.sys.change_freeze_windows, cfg.net.fallback_after_failures, cfg.telemetry.device_max_bytes_per_day, cfg.day.submit_settle_timeout_min, cfg.release.min_version, cfg.release.blocked_versions, cfg.flag.new_app_login_enabled, cfg.geo.radius_m, cfg.support.max_upload_mb.

### Added at the editorial merge

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-144, D-170, D-263, D-268, D-309. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-28, G-qa-29, G-qa-31, G-qa-32, G-qa-33, G-qa-41, G-qa-42, G-qa-44, G-qa-45, G-qa-46, G-qa-53, G-qa-61, G-qa-73, G-qa-76, G-qa-77, G-qa-80, G-qa-81, G-qa-82, G-qa-84, G-qa-85 | s1.1, s1.4, s1.8, s1.9, s1.11, s2.3, s3.1, s3.4, s4.2, s4.5, s5.1, s5.2, s5.4, s6.1, s6.4, s6.6, s7, s8, s10.6 |
| Decisions | D-504, D-505, D-507, D-508, D-509, D-514, D-517, D-518, D-520, D-521, D-522, D-527, D-528, D-542, D-545, D-548, D-549, D-550, D-552, D-553 | as above |
| Gates | T-2-155, T-4-152, T-4-155 to T-4-164, T-1-150, T-1-151, T-1-152, T-7-150, T-7-151, T-7-153, T-7-155 to T-7-157 | s8.3, s10.6 |
| Features | F-SYS-079, F-SYS-084, F-ADM-072, F-ADM-073, F-ADM-075 | s6, s7 |
| Config keys | cfg.sla.login_pct_alert_time, cfg.sync.checkout_upload_jitter_max_s, cfg.sync.max_savepoints_per_tx, cfg.sla.pending_rows_alert_h, cfg.release.auto_freeze_regression_pct, cfg.auth.refresh_absolute_jitter_days, cfg.sla.location_request_backlog_alert | s9.4 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-88, G-qa-89, G-qa-90, G-qa-92, G-qa-94, G-qa-96, G-qa-100, G-qa-101, G-qa-102, G-qa-103, G-qa-104, G-qa-106, G-qa-109, G-qa-110, G-qa-111, G-qa-122, G-qa-123, G-qa-125, G-qa-126, G-qa-131, G-qa-135 | s10.6b |
| Decisions | D-556, D-557, D-558, D-560, D-562, D-563, D-566, D-567, D-568, D-569, D-573, D-574, D-575, D-584, D-586, D-587, D-592, D-596 | s10.6b |
| Runbooks | RB-44 to RB-54 | s7.6 |
| Scenarios | S12, S13, S14 and the changed S1', S5, S9 | s8.2 |
| Health tiles | SH-26, SH-27 | s6.4 |
| Gates | T-0-158, T-0-159, T-1-153, T-1-155, T-2-163, T-2-164, T-2-167, T-2-168, T-2-174, T-4-173, T-4-174, T-7-161, T-7-162, T-7-163, T-7-164, T-7-166, T-7-168 | s7, s8 |
| Config keys | cfg.cutover.*, cfg.sla.sync_success_pct_alert, cfg.sla.crash_rate_pct_alert, cfg.sla.held_bind_alert_min, cfg.sla.apsis_residual_alert | doc 19 s3.2.11 |
