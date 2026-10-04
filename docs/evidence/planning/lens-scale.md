# Lens: Scale, Reliability and Azure Architecture (requirement R3)

Date: 2026-10-04. Author: scale/reliability specialist. Inputs read in full: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13, db/schema.sql, db/seed/README.md + sku_catalog.csv header, plan/seed-findings.md, plan/lens-features.md (for F-IDs). Azure facts were checked against Microsoft Learn on 2026-10-04; each limit cites its page. Prices are approximate pay-as-you-go list prices and are marked unverified.

Scope of this lens: the shape of load, the Azure target architecture, capacity/cost, launch-day failure modes, observability/runbook, and the load-test plan. It does not re-derive the seed findings (they are referenced as SF-n) or the feature inventory (F-xxx). Test gates from this lens use the 50-range (T-p-5n) to avoid collisions with other lenses.

How to read: §0 is the one-page conclusion. §1 is the load model (every number derived from the spec; assumptions labelled). §2 the architecture. §3 capacity and cost. §4 failure modes. §5 observability/runbook. §6 the load-test plan. §7 config keys, proposed decisions, gaps.

---

## 0. Conclusions (one page)

1. **This is not a high-throughput system; it is a bursty, correctness-critical one.** 8,500 SRs produce ~1.5 M transaction rows/day (~290 MB of JSON before gzip, ~60 MB after). Spread evenly that is 40 rows/s. The whole day's records, if every phone were offline all day and synced in the same 30 minutes, is ~1,700 rows/s — a single General Purpose Postgres (8 vCores) with multi-row upserts handles that with >3× headroom. **The risks are the storms (login at 08:00, check-out at 17:00), retry loops, hot aggregate rows, connection exhaustion, and quota/config mistakes — not steady-state capacity.**
2. **Ingest synchronously into Postgres; aggregate asynchronously.** The device compares its counts against the server's accepted counts in the same response (docs/04 step 3). That acknowledgement is only truthful if the rows are committed before the response, so a queue between the API and the DB would break reconciliation and force a second round trip (violates R4). Peak ingest is ≤ 30 batches/s; Postgres does not need a buffer. The queue belongs *after* the commit: a transactional outbox row per batch drives the aggregation / geo-recheck / plausibility worker. Aggregates are **recomputed per dirty (business_date, route) key**, never incremented, so duplicates, edits and late batches cannot double-count and the national rollup row is never a lock hotspot.
3. **Pre-generate the bundle.** At 04:00 Asia/Dhaka a job writes one gzipped snapshot per user (~25 KB median) to Blob (and Redis when present). `GET /sync/bundle` streams the snapshot; `?since=` deltas are computed live but are tiny. The morning storm then costs the DB almost nothing (route_log + device writes only). A naive live bundle at 130 req/s (5-minute storm) would mean ~1,300 scoped queries/s against the outlet table — the one thing that could actually fall over on launch morning.
4. **Target topology (full fleet):** Front Door Premium (WAF, Private Link origin, caching of APK/thumbnails/web assets) → Container Apps workload-profiles environment, zone-redundant, in a custom VNet, with `api` (min 3 / max 30 replicas, HTTP rule concurrentRequests=30), `worker` (KEDA on outbox depth), `web`, and ACA Jobs (04:00 bundle pre-gen, nightly re-aggregation, lifecycle) → Azure Database for PostgreSQL Flexible Server GP D8ds_v5, zone-redundant HA (RTO 60–120 s, RPO 0), Premium SSD v2 512 GiB (12,000 IOPS baseline), built-in PgBouncer on 6432, PITR 35 days, geo-redundant backup, 1 in-region read replica for dashboards/reports → Azure Managed Redis Balanced (small SKU, HA) for bundle cache, batch-response replay, per-device rate limits, config version → Blob Storage GPv2 ZRS with user-delegation SAS direct photo upload and Hot→Cool→Cold lifecycle → Key Vault, Managed Identity, App Insights + Log Analytics (sampled), Azure Load Testing (Locust). Region: Southeast Asia (3 AZs) with East Asia as paired/DR region (ASSUMPTION; Central India is the equal alternative — no Azure region exists in Bangladesh; confirm data-residency position with AKTCL).
5. **Cost order of magnitude (unverified list prices):** pilot USD 300–600/month; wave 1 (~1,000 SRs) USD 1,500–2,500; full fleet USD 4,000–7,000 pay-as-you-go, ~30–35 % less with 1-year reservations on Postgres and Redis. Observability ingestion is the sleeper cost (it can exceed the database if unsampled).
6. **Launch-day protections that must be code, not hope:** batch-level idempotency key with stored response (replay without touching rows); client backoff with jitter capped at 5 attempts then WorkManager periodic; server 429 + `Retry-After` from a per-replica concurrency limiter; per-device rate limiting in the API (carrier-grade NAT makes per-IP WAF limits dangerous); min-version kill switch and blocked-version list that **never** stop offline capture or the upload of already-captured data; bounds-checked, scope-staged, two-person config changes for critical keys (`cfg.geo.radius_m` ∈ [20, 2000]); offline re-login against cached credentials and a stale-bundle allowance so a region outage at 08:00 does not stop selling.
7. **Twenty gaps (G-scale-01..20), three of them blockers:** no batch-level idempotency/replay (G-scale-01), aggregation strategy unspecified (G-scale-02), bundle path not storm-proof (G-scale-03).

---

## 1. Quantified load model

### 1.1 Population and daily volume (from the spec)

| Quantity | Value | Source / derivation |
| --- | --- | --- |
| SRs | 8,500 | docs/01 |
| AMOs | ~1,051 (one per zone, login = zone) | docs/07 "The AMO works one zone"; ASSUMPTION 1 AMO/zone |
| TSOs | ~291 (one per territory) | docs/08; ASSUMPTION 1 TSO/territory |
| DMO / WM / top / HQ-admin | 50 / 10 / ~20 / ~50 | docs/01 hierarchy; ASSUMPTION for HQ users |
| Zones / territories / divisions / wings | 1,051 / 291 / 50 / 10 | docs/01 |
| Outlets on a day's plan | ~460,000 | docs/01 ("4.6 lakh") |
| Outlets per route | ~50 (460,000 / 8,500 ≈ 54 per SR-day) | docs/01, task brief |
| Routes | 9,000–17,000 (8,500 active per day; alternate-day routes mean many SRs own 2) | derived from `route.visit_days` (docs/03) |
| Successful calls (memos) / day | ~120,000 (≈14 per SR; strike rate ≈ 26 %) | docs/01 |
| Visits incl. zero-sale and no-sale opened / day | **300,000** (ASSUMPTION: SR opens ~65 % of planned outlets; range 120k–460k) | docs/06 home strip counts "Non-visit", "No-sale" |
| SKUs | 42 (4 categories, 6 segments, 17 brands, 30 variants) | db/seed |
| Lines per memo | **5** (ASSUMPTION; range 3–8 of 42 SKUs) | — |
| Business day | Attendance 08:00–17:00 Dhaka; check-out opens 17:00; final submit evening | docs/04, docs/06 |

### 1.2 Transaction rows per day and wire size

Row sizes are compact JSON on the wire (short keys, ISO timestamps, UUID strings), before gzip. Server storage ≈ 60 % of wire size plus indexes.

| Table | Rows/day | Basis | Wire B/row | MB/day |
| --- | --- | --- | --- | --- |
| `visit` | 300,000 | §1.1 | 350 (uuid, user, outlet, route, time, lat/lng, accuracy, mock, flags, integrity) | 105 |
| `memo` | 120,000 | docs/01 | 300 | 36 |
| `memo_line` | 600,000 | 5 × memo | 120 | 72 |
| `qc_entry` | 36,000 | ASSUMPTION 0.3 per memo | 100 | 3.6 |
| `survey_response` | 150,000 | ASSUMPTION 0.5 per visit (POSM) | 200 | 30 |
| `drp_collection` | 60,000 | ASSUMPTION 0.2 per visit | 100 | 6 |
| `due_collection` | 12,000 | ASSUMPTION 10 % of memos are credit | 200 | 2.4 |
| `attendance` (in + out) | 17,000 | 8,500 × 2 writes | 200 | 3.4 |
| `stock_issue` | 130,000 | 8,500 × ~15 SKUs | 100 | 13 |
| `outlet_change_request` + `outlet_photo` meta | 4,000 | ASSUMPTION ~0.5 % outlets/day incl. force-sale location fixes | 600 | 2.4 |
| `redemption`, `loyalty_ledger`, `gift_photo` | 4,000 | ASSUMPTION (programs) | 200 | 0.8 |
| `task` (assign/resolve) | 5,000 | ASSUMPTION | 200 | 1 |
| `call_assessment` (AMO joint, TSO questionnaire) | 8,000 | 1,051 AMOs × ~6 + TSOs | 600 (jsonb scores) | 4.8 |
| `distribution_check` | 80,000 | 1,051 AMOs × 5 control calls × 15 brands | 100 | 8 |
| **Subtotal (business rows)** | **~1.53 M** | | | **~290 MB** |
| `activity_log` | 300,000–450,000 | ASSUMPTION 40–50 screen actions per SR-day | 80 | 30 |
| **Total** | **~1.9 M rows/day** | | | **~320 MB/day uncompressed; ~65 MB gzipped (≈5:1 on repetitive JSON)** |

Per SR per day: ~180 business rows, **~34 KB uncompressed, ~7 KB gzipped**. Records are irrelevant to the 2 GB/day data pack (R4); **photos dominate** (below).

### 1.3 Photos

| Source | Count/day | Basis |
| --- | --- | --- |
| Force-sale outlet photo | 30,000 | ASSUMPTION 10 % of visits out of range / no fix (docs/05) — launch-day value likely higher while outlet coordinates are dirty |
| POSM survey photo | 60,000 | ASSUMPTION photo on 20 % of visits |
| Outlet request photos (new / info / update-base) | 4,000 | §1.2 |
| Gift / redemption verify photos | 2,000 | programs |
| **Total** | **~95,000/day (range 50k–200k)** | at **150 KB** each (docs/04 target ≤100–200 KB) |

= **~14 GB/day** (range 7–30 GB), ≈ 420 GB/month, ≈ 5 TB/year. Per SR: ~11 photos ≈ 1.7 MB/day — inside the "tens of MB" budget. Peak upload rate if photos go as they are taken: ~10/s; if held for Wi-Fi, an evening burst of ~50/s. Blob account default target is 20,000 requests/s and 60 Gbps ingress in Southeast Asia / Central India (https://learn.microsoft.com/azure/storage/common/scalability-targets-standard-account), so Blob is never the bottleneck; the API must simply not proxy bytes (§2.6).

### 1.4 The reference bundle

Per-user bundle contents (docs/04, docs/09). An SR typically owns 1–2 routes (alternate days), so the bundle carries all assigned routes: **~110 outlets median, ~300 p95**.

| Section | Rows | B/row (JSON) | KB |
| --- | --- | --- | --- |
| outlets (code, name Bangla UTF-8 ~90 B, owner, phone, lat/lng, cluster, channel, sub-channel, geo class, status, open-due balance, loyalty balance, suggested qty, Astha flag) | 110 | 450 | 50 |
| open credit memos per outlet (for due collection) | 30 | 150 | 4.5 |
| product hierarchy + 42 SKUs (no images; image URLs only) | 42 + 57 | 200 / 60 | 12 |
| prices (42 × 5 types, effective-dated) | 210 | 60 | 12.6 |
| sales plan for the zone | 42 | 20 | 1 |
| targets + MTD achievement (category 4 + brand 17 + SKU 42) × routes | 126 | 80 | 10 |
| Astha per-outlet brand targets (ASSUMPTION 10 % outlets Astha × 8 brands) | 90 | 60 | 5.4 |
| offers / promotions (~22 groups, docs/10) | 22 | 300 | 6.6 |
| tasks, gift assignments, pending own requests | ~15 | 200 | 3 |
| geo config + runtime config snapshot (R6) | 1 | 2,000 | 2 |
| **Total uncompressed** | | | **~110 KB (p95 ~250 KB)** |
| **gzip (ratio 4–6 on JSON)** | | | **~25 KB median, ~60 KB p95** |

Not in the daily bundle: product thumbnails (42 × ~15 KB ≈ 0.6 MB, cached once per device with ETag/immutable URLs), AV/KV marketing content and tutorial videos (Wi-Fi-only, bounded LRU per docs/04), the APK.

### 1.5 The morning download storm

Logins per morning: 8,500 SR + 1,051 AMO + 291 TSO ≈ **9,850**. Requests per login session: `POST /auth/refresh` (or `/auth/login`), `GET /sync/bundle`, `GET /app/home`, `GET /app/release-check` ≈ **4 requests** (docs/06 "In-app update", docs/09).

| Concentration | Bundles/s | Total API req/s | Egress MB/s (25 KB gz) | DB load if bundle is **pre-generated** | DB load if bundle is **live** |
| --- | --- | --- | --- | --- | --- |
| 1 hour (07:30–08:30) | 2.7 | 11 | 0.07 | ~5 writes/s (route_log, device.last_seen) + ~3 reads/s | ~30 scoped queries/s |
| 20 minutes | 8.2 | 33 | 0.2 | ~15 writes/s | ~90 queries/s |
| 5 minutes (everyone at 08:00 sharp; wave-day realistic) | 33 | 130 | 0.8 | ~50 writes/s | **~350–1,300 queries/s** (10–40 queries per bundle), the launch-morning killer |

Two non-obvious costs hide in the login storm:

- **Password hashing CPU.** argon2id/bcrypt at a sane cost is 50–100 ms CPU per verification. At 33 logins/s that is 2–3 vCPU of pure hashing. Make the daily login a **refresh-token exchange** (no hash) and reserve password login for first use / expiry (docs/13 Q4 token lifetimes → `cfg.auth.refresh_ttl_days` ≥ 30). (G-scale-06)
- **APK + thumbnail downloads on a wave day.** 1,000 new SRs × ~35 MB APK + 0.6 MB thumbnails = 35 GB in one morning (8,500 × 35 MB = 300 GB on a full-fleet day). Served from Blob through Front Door cache, Wi-Fi-preferred; never through the API (G-scale-16).

### 1.6 The "immediate sync" trickle (R5)

Rule: when the device is online, a pending record is uploaded within seconds of being saved (debounced `cfg.sync.trickle_debounce_s` = 10 s so a visit + memo + lines + QC + survey leave as **one** batch, not five). Batch ≈ 1 visit + 1 memo + 5 lines + 0–3 children ≈ 12 rows ≈ 2 KB gzipped.

| Parameter | Value | Basis |
| --- | --- | --- |
| Share of SRs with connectivity in the field | 60 % (ASSUMPTION; Bangladesh mobile coverage is good in towns, patchy rural) | — |
| Trickle batches/day | 0.6 × 300,000 visits ≈ 180,000 | one batch per visit |
| Average rate 09:00–17:00 | 6.3 batches/s (75 rows/s) | 8 h |
| Peak (10:00–12:00 selling peak, 2×) | **~13 batches/s (~160 rows/s)** | |
| Postgres transactions | 13 ingest + 13 outbox-consume + ~13 aggregate recompute ≈ 40 TPS | |

Battery cost of the trickle (R4): one HTTPS POST of ~2 KB every ~4 minutes per SR, piggy-backed on a radio that is already awake for the sale. This is cheaper than holding records until evening and then uploading 50 KB on a weak signal with retries. WorkManager expedited one-shot job with `NetworkType.CONNECTED`; no foreground service; no socket kept open.

### 1.7 The evening wave (17:00 check-out + sales submit + offline catch-up)

Offline 40 % of SRs (3,400) each upload ~115 visits-worth of rows (~180 rows, ~45 KB gzipped) in a few batches of `cfg.sync.batch_max_rows` = 200. Everyone checks out and sales-submits from 17:00 (docs/06 step 7); TSO final submit per zone follows.

| Request | Count 17:00–18:30 | 20-min concentration (17:00–17:20) |
| --- | --- | --- |
| Large catch-up batches | ~4,000 | 3.3/s, ~600 rows/s |
| Attendance check-out | 9,850 | 8/s |
| Sync-then-reconcile (`POST /sync/batch` with empty/leftover + counts read) | 9,850 | 8/s |
| `POST /day/sales-submit` | 9,550 (SR + AMO) | 8/s |
| `POST /day/final-submit` | 1,051 | 1/s (later, 18:00–20:00) |
| `GET /app/home` refreshes | ~20,000 | 17/s |
| **Total API** | **~55,000** | **~45 req/s avg, ~90 req/s peak minute** |
| DB rows upserted | ~600,000 | **~500–1,000 rows/s**, ~60 TPS |

**Worst case (design point):** a launch-day network incident means all 8,500 SRs are offline until 17:00, then all sync in 30 minutes: 8,500 × ~180 rows = 1.53 M rows in 1,800 s = **~850 rows/s sustained, ~1,700 rows/s peak**, ~12 batches/s, plus 17k check-out/submit calls. Multi-row `INSERT … ON CONFLICT (client_uuid) DO UPDATE` on an 8-vCore GP server with 12,000 IOPS comfortably exceeds 5,000 rows/s. **Capacity is not the issue; connection count and retry behaviour are** (§4.2, §4.5).

### 1.8 Dashboard and report reads

~1,500 supervisory users (§1.1). Heaviest window 17:00–19:00 (Daily Tracking "take action after 17:00", docs/09; final-submit watching, docs/08).

| Reader | Users | Refresh | Pages/s | Queries/page | Queries/s |
| --- | --- | --- | --- | --- | --- |
| AMO Live Dashboard (app) | 1,051 | every 2 min while open (ASSUMPTION 50 % open) | 4.4 | 4 | 18 |
| TSO dashboard (app) | 291 | 2 min | 2.4 | 6 | 15 |
| Web dashboards (DMO/WM/top/HQ) | ~130 | 1 min (auto-refresh) | 2.2 | 8 | 17 |
| Reports (on-screen + Excel export) | ~50 concurrent | ad hoc | 0.5 | 1 heavy (1–10 s) | 0.5 heavy |
| **Total** | | | **~10 pages/s** | | **~50 light + 0.5 heavy queries/s** |

Every number comes from `fact_*` / rollup tables (docs/10), never the transaction log. National dashboard = SUM over ~8,500 `fact_daily_route` rows or 10 `rollup_daily_wing` rows: < 50 ms. All of this goes to the **read replica**; the primary serves only ingest + aggregation + admin writes. Replica lag is acceptable (seconds); show "as of HH:MM:SS" on every tile.

### 1.9 Write amplification into aggregates

Per memo (5 lines) the raw write is 7 rows. Aggregates touched (docs/10 grains + the MTD grain the achievement bars need):

| Aggregate | Rows touched per memo |
| --- | --- |
| `fact_daily_route_sku` (date × route × SKU) | 5 |
| `fact_daily_outlet` | 1 |
| `fact_daily_route` | 1 |
| `rollup_daily_{zone,territory,division,wing,national}` | 5 |
| `fact_mtd_route_product` (route × {category, brand, SKU} × month) — needed so target achievement is a lookup, not a 30-day scan | ~12 |
| `rollup_mtd_{zone,territory,division,wing}_product` | ~48 |
| **Total** | **~70 aggregate rows per memo ≈ 10× amplification; ~8.5 M aggregate row-writes/day** |

If done as **in-place increments** inside the ingest transaction, the national and wing rows receive 120,000 and ~12,000 updates/day each, i.e. 500+/s on one row during the evening wave → row-lock serialisation, deadlocks with edits (`supersedes_memo_id`), and double counting on any retry that slips past idempotency. Therefore (D-scale-2): the worker **recomputes** the affected `(business_date, route)` slice from the raw tables with one `INSERT … SELECT … ON CONFLICT DO UPDATE` per aggregate (idempotent, edit-safe, late-batch-safe), coalescing dirty keys over a 5–10 s window so the evening wave's 3.3 batches/s per route collapse into one recompute per route per window. Rollups above route are recomputed from the route facts for the touched zones only. Resulting steady write rate on the primary: ≤ 300 aggregate rows/s in the wave.

### 1.10 Peak RPS / DB TPS summary (design targets include 3× headroom)

| Window | API req/s (expected peak) | DB write rows/s | DB TPS | Read QPS (replica) | Design-for (3×) |
| --- | --- | --- | --- | --- | --- |
| 08:00 login storm, 5-min concentration | 130 | 50 (pre-gen) | 150 | 30 | 400 req/s; 500 TPS |
| 10:00–12:00 trickle peak | 15 | 160 | 40 | 30 | 50 req/s |
| 17:00–17:20 evening wave | 90 | 1,000 | 60 ingest + 300 aggregate | 50 | 300 req/s; 3,000 rows/s |
| Worst case all-offline catch-up | 40 | 1,700 | 40 + 400 | 50 | 5,000 rows/s |
| Photos (SAS issuance + blob PUT, not via API bytes) | 10 SAS/s (50/s burst) | — | — | — | 150 SAS/s |
| Dashboards 17:00–19:00 | 10 pages/s | — | — | 50 | 150 QPS |

Storage growth: raw ~1.9 M rows/day × ~250 B stored (incl. indexes) ≈ 475 MB/day ≈ 170 GB/year; aggregates ≈ 60 GB/year (fact_daily_outlet is the big one: 460k rows/day = 170 M rows/year); 8-month Apsis import ≈ 100–150 GB. **512 GiB SSD v2 covers ~2 years; partition by month from day one** (G-scale-08).

---

## 2. Target Azure architecture

### 2.1 Diagram

```
                    Bangladesh mobile networks (carrier-grade NAT, 3G/4G, patchy rural)      HQ / supervisors (browser)
                 ┌──────────────────────────────────────────────────────────────┐          ┌──────────────────────┐
                 │  SR / AMO / TSO Flutter app (offline-first local SQLite)      │          │  Next.js web (SSR)   │
                 │  WorkManager: trickle sync, Wi-Fi photo queue, 1 fix/visit   │          └──────────┬───────────┘
                 └───────┬───────────────────────────┬───────────────────────────┘                     │
                         │ HTTPS gzip JSON           │ HTTPS PUT (SAS)                                 │
                         ▼                           │                                                 ▼
   ┌──────────────────────────────────────────────────┼─────────────────────────────────────────────────────────┐
   │  AZURE FRONT DOOR Premium  (WAF: managed DRS + custom rules; TLS 1.2+; rate-limit HIGH per IP;            │
   │  caching ONLY for /static, /apk, /content, /thumbs; compression; Private Link to ACA internal ingress)   │
   └───────────────┬──────────────────────────────────┼────────────────────────────────────┬────────────────────┘
                   │ Private Link                     │ direct to Blob (SAS, 15-min, write-only, fixed path)     │
   ┌───────────────▼──────────────────────────────────┼──────────────────┐     ┌───────────▼────────────────────┐
   │ AZURE CONTAINER APPS  env: workload profiles, ZONE-REDUNDANT, VNet │     │ BLOB STORAGE  GPv2 ZRS         │
   │                                                                     │     │  photos/   (Hot→Cool 30d→Cold │
   │  api     NestJS  min 3 / max 30  HTTP rule concurrentRequests=30    │     │            90d→Archive 365d)   │
   │          probes: startup /healthz/startup, live, ready(DB+Redis)    │────▶│  bundles/  (04:00 snapshots)   │
   │  worker  aggregation + geo re-check + plausibility + photo link      │     │  apk/ thumbs/ content/        │
   │          KEDA postgresql scaler on outbox depth, min 1 / max 10      │     │  support/ (PDA to Support)    │
   │  web     Next.js SSR  min 2 / max 6                                 │     │  soft-delete + versioning     │
   │  jobs    ACA Jobs (cron, Dhaka = UTC+6): 22:00Z bundle pre-gen,     │     └────────────────────────────────┘
   │          20:00Z nightly re-aggregate, 21:00Z partition/lifecycle     │
   └──────┬───────────────────────┬───────────────────────────┬──────────┘
          │ 6432 PgBouncer        │                           │ 10000 TLS
   ┌──────▼───────────────────────▼────────┐      ┌───────────▼─────────────────────────────┐
   │ AZURE DATABASE FOR POSTGRESQL FLEX    │      │ AZURE MANAGED REDIS  Balanced, HA        │
   │  primary GP D8ds_v5, ZONE-REDUNDANT HA │      │  bundle snapshot cache (~250 MB)         │
   │  PG16, SSD v2 512 GiB 12k IOPS         │      │  batch_uuid → response replay (24 h)     │
   │  PITR 35 d, geo-redundant backup       │      │  per-device/user rate-limit counters     │
   │  built-in PgBouncer (transaction mode) │      │  config version, token revocation list   │
   │  ──async──▶ read replica D8 (dashboards│      └──────────────────────────────────────────┘
   │             reports, BI, Excel export) │
   │  ──async──▶ cross-region replica (DR)  │─ ─ ─▶  East Asia (paired): geo-backup + DR replica
   └────────────────────────────────────────┘
   ┌──────────────────────────────────────────────────────────────────────────────────────────────────────────┐
   │ KEY VAULT (secrets, JWT keys, SAS signing via MI) · MANAGED IDENTITY everywhere · APP INSIGHTS + LOG     │
   │ ANALYTICS (sampled, PII-free) · AZURE MONITOR alerts/Workbooks · AZURE LOAD TESTING (Locust) · GITHUB   │
   │ ACTIONS (OIDC) → ACR → ACA revisions (blue/green) · BICEP IaC for dev/staging/prod                       │
   └──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Region choice

| Option | AZs | Notes |
| --- | --- | --- |
| **Southeast Asia (Singapore)** — recommended | 3 (https://learn.microsoft.com/azure/reliability/regions-list) | Full service coverage incl. Postgres zone-redundant HA, SSD v2, Managed Redis, ACA; paired with East Asia; ~55–75 ms RTT from Dhaka (ASSUMPTION, measure) |
| Central India (Pune) | 3 | Equivalent; paired with South India; similar RTT; sometimes preferred for data-residency optics in South Asia |
| Bangladesh | none | No Azure region. **Confirm with AKTCL that outlet PII (NID/TIN) may be hosted outside Bangladesh** (unknown; confirm with the business). |

All AZ-dependent features (ACA zone redundancy, Postgres zone-redundant HA) require a region with availability zones; both candidates qualify.

### 2.3 Front Door

- **Tier:** Standard for pilot and wave 1 (custom WAF rules incl. rate limiting; USD 35 base), **Premium at full fleet** (managed DRS rule set, bot protection, **Private Link origin** so the ACA environment has no public ingress; USD 330 base) — https://learn.microsoft.com/azure/frontdoor/understanding-pricing and https://learn.microsoft.com/azure/networking/design-guide/web-application-firewall. Front Door supports Standard→Premium upgrade in place.
- **What to cache at the edge:** `/apk/*`, `/thumbs/*`, `/content/*`, web `_next/static/*` (immutable, versioned URLs, 1-year max-age). **Never** cache `/sync/bundle` (per-user, `Authorization` header) — caching is done server-side as pre-generated snapshots (§2.4).
- **Rate limiting caveat (G-scale-04):** Front Door rate limits count per **socket IP** over fixed 1- or 5-minute windows (https://learn.microsoft.com/azure/web-application-firewall/afds/waf-front-door-rate-limit). Bangladeshi mobile carriers put thousands of subscribers behind one NAT IP; a "reasonable" per-IP threshold would block an entire carrier's SRs at 08:00. Set WAF rate limits only as a DDoS backstop (e.g., 20,000 requests / 5 min per IP on `/sync/*`; 300 / 5 min per IP on `/auth/login` is still risky — prefer 2,000) and do real limiting per device/user in the API with Redis.
- Health probes from Front Door to the origin at a low frequency (one origin → disable or 120 s) to avoid probe noise.

### 2.4 Container Apps

| Setting | Value | Why / source |
| --- | --- | --- |
| Environment | Workload-profiles environment, **zone-redundant**, custom VNet (/23 infra subnet), internal ingress, private endpoint for Front Door | Zone redundancy must be chosen at creation, needs a VNet, needs minReplicas ≥ 2 (https://learn.microsoft.com/azure/reliability/reliability-container-apps) |
| `api` | 1 vCPU / 2 GiB per replica; pilot min 1 / max 3; wave 1 min 2 / max 10; **fleet min 3 / max 30**; HTTP scale rule `concurrentRequests` = 30 | Node/NestJS does ~150–300 light RPS per vCPU; bundle streaming and 200-row upserts are ~20–50 ms each; 30 in-flight per replica keeps p95 under budget. Rule evaluated every 15 s (https://learn.microsoft.com/azure/container-apps/scale-app). Max configurable replicas is 1,000 |
| `worker` | 1 vCPU / 2 GiB; min 1 / max 10; custom KEDA `postgresql` scaler on `SELECT count(*) FROM sync_outbox WHERE processed_at IS NULL` (target 200 per replica) | Scale on backlog, not CPU |
| `web` | 0.5 vCPU / 1 GiB; min 2 / max 6; HTTP rule 50 | SSR dashboards |
| `jobs` | ACA Jobs (scheduled, cron in UTC: `0 22 * * *` = 04:00 Dhaka bundle pre-gen; `0 20 * * *` = 02:00 Dhaka nightly re-aggregation + partition maintenance) | Dhaka has no DST; UTC+6 fixed |
| Probes | startup: `/healthz/startup` (initialDelay 5 s, period 5 s, failure 30); liveness: `/healthz/live` (process only); readiness: `/healthz/ready` (DB `SELECT 1` via PgBouncer + Redis PING, 2 s timeout) | Readiness must drop a replica that lost its pool; liveness must not depend on the DB or a DB blip restarts every replica (§4.6) |
| Revisions | Single-revision mode with blue/green via traffic weights for the API (10 % → 50 % → 100 %) | Launch-day safety |
| Quota | Consumption cores per environment is a per-environment quota whose default "depends on subscription age/type" (https://learn.microsoft.com/azure/container-apps/quotas). Check `az containerapp env list-usages`; **request ≥ 64 cores before wave 1** | G-scale-11 |

### 2.5 PostgreSQL Flexible Server

| Setting | Pilot | Wave 1 | Full fleet | Source |
| --- | --- | --- | --- | --- |
| Compute | GP D2ds_v5 (2 vCore, 8 GiB) | GP D4ds_v5 (4 vCore, 16 GiB) | **GP D8ds_v5 (8 vCore, 32 GiB)**; scale to D16 if p95 ingest > budget | https://learn.microsoft.com/azure/postgresql/compute-storage/concepts-compute |
| HA | none (same-zone optional) | **zone-redundant** | **zone-redundant** (RTO 60–120 s, RPO 0, SLA 99.99 %) | https://learn.microsoft.com/azure/postgresql/high-availability/concepts-high-availability |
| Storage | SSD v2 128 GiB (3,000 IOPS baseline) | SSD v2 256 GiB | **SSD v2 512 GiB → 12,000 IOPS / 500 MB/s baseline at no extra cost; tunable to 80,000 IOPS** | https://learn.microsoft.com/azure/postgresql/compute-storage/concepts-storage-premium-ssd-v2 (note: SSD v2 has no storage autogrow; alert at 70 %) |
| Backups | PITR 7 d | PITR 35 d | **PITR 35 d + geo-redundant backup** | https://learn.microsoft.com/azure/postgresql/backup-restore/concepts-business-continuity |
| Read replica | none | optional D2 | **1 in-region D8 (dashboards, reports, Excel, BI) + 1 cross-region (DR, §4.9)**; up to 5 per primary; use virtual endpoints | https://learn.microsoft.com/azure/postgresql/read-replica/concepts-read-replicas |
| Pooling | built-in PgBouncer, transaction mode, port 6432 | same | same; `pgbouncer.default_pool_size` 100–150 (≈ 15× vCores is too high; docs suggest 2–5× vCores for server connections, we need more for burst — load-test it), `max_client_conn` 5,000 | https://learn.microsoft.com/azure/postgresql/connectivity/concepts-pgbouncer ; https://learn.microsoft.com/azure/postgresql/configure-maintain/concepts-limits |
| Connections budget | api 30 replicas × pool 8 = 240 client conns + worker 10 × 4 = 40 + web 6 × 4 = 24 + jobs → **~320 PgBouncer clients → ≤ 150 server connections**; D8 default max_connections 3,437 is irrelevant once pooled | § 4.2 |
| Server parameters | `statement_timeout` 15 s (api role), 10 min (worker role), 60 s (web role); `lock_timeout` 3 s (api); `idle_in_transaction_session_timeout` 30 s; `synchronous_commit` on (HA already synchronous); `log_min_duration_statement` 500 ms; Query Store on | § 4.4 |
| Partitioning | `visit`, `memo`, `memo_line`, `activity_log`, `fact_daily_outlet`, `fact_daily_route_sku` RANGE-partitioned by `business_date` month; default partition present | G-scale-08 |

Why not Burstable for pilot: credit exhaustion under a load test produces misleading results (https://learn.microsoft.com/azure/postgresql/compute-storage/concepts-compute). Staging must be able to scale to the prod SKU for the load-test windows (G-scale-17).

### 2.6 Blob Storage

- One GPv2 account per environment, **ZRS**, Hot default tier, soft delete 14 d, versioning on `apk/`.
- **Direct upload by SAS:** `POST /media/upload-url` returns a **user-delegation SAS** (Managed Identity, no account key), **write-only, 15-minute TTL, pinned to the exact path** `photos/{business_date}/{device_uuid}/{client_uuid}.jpg`. The device PUTs straight to Blob (Wi-Fi-preferred; `cfg.media.wifi_only_default`). The record already carries the deterministic path, so the sale syncs without waiting for the photo (docs/04 step 4). The worker marks `photo_state = present` when it sees the blob (Event Grid `BlobCreated` → Service Bus/outbox, or a nightly sweep) and reports **photo-pending > 24 h** per route on the sync-health screen (G-scale-20).
- Lifecycle: Hot → Cool at 30 d → Cold at 90 d → Archive at 365 d; retention/deletion policy unknown (**confirm with the business**: force-sale photos are audit evidence for geo disputes).
- Throughput is a non-issue (§1.3). SDK retry on 503/500 with exponential backoff; never block the sale.

### 2.7 Redis (Azure Managed Redis)

- Use **Azure Managed Redis**, not Azure Cache for Redis: Basic/Standard/Premium creation is blocked for new customers from 1 April 2026 and all retire 30 September 2028 (https://learn.microsoft.com/azure/azure-cache-for-redis/retirement-faq). Balanced tier, smallest HA SKU that holds ~1 GB (bundle cache 250 MB + replay cache + counters); ~20 % memory is reserved for system use (https://learn.microsoft.com/azure/redis/overview). OSS cluster policy; TLS; Entra ID auth via Managed Identity; zone-redundant by default.
- Uses: (1) bundle snapshot cache (Blob is the durable copy; Redis is the fast path), (2) `batch_uuid → stored response` replay (24 h TTL), (3) per-device/user sliding-window rate limits and concurrency tokens, (4) `cfg.version` + revoked-token set, (5) `device.last_seen` throttling.
- **Phase gating:** not required for Phase 1–3 (pilot) — the snapshot table in Postgres + Blob suffices; **required before wave 1** if load test T-4-52 shows bundle p95 > 2 s or if the per-device rate limiter is needed (it is).

### 2.8 Queue: Service Bus vs transactional outbox — and the ingest decision

**Decision D-scale-1: `POST /sync/batch` writes synchronously to Postgres and returns authoritative accepted counts.** Numbers: the heaviest realistic ingest is ~12 batches/s of 200 rows (§1.7 worst case); one batch is one transaction of ~10 multi-row `INSERT … ON CONFLICT` statements ≈ 20–60 ms on D8 → < 1 replica-second of DB time per second. A queue in front of the DB would (a) turn the response into "accepted for processing", so the device-vs-server reconciliation screen would be lying or need polling (R4 violation), (b) add a dual-write failure mode, (c) cost ~USD 10–700/month for nothing. Service Bus Standard is capped at 1,000 operations/s per namespace (https://learn.microsoft.com/azure/service-bus-messaging/service-bus-quotas) — adequate here, but irrelevant.

**Decision D-scale-2: aggregation and all post-ingest processing run from a transactional outbox.** The ingest transaction also inserts one `sync_outbox` row `(batch_id, business_date, route_ids[], outlet_ids[], kinds[])`. The worker claims rows with `FOR UPDATE SKIP LOCKED`, recomputes the dirty slices (§1.9), runs the server geo re-check and plausibility flags (F-SYS-012/013), links photos, and marks `processed_at`. Exactly-once is unnecessary because every step is an idempotent recompute. Poison rows (exception 5×) are parked in `sync_outbox_dead` and surfaced on the ops screen; **data quarantine (F-SYS-014) is a table (`sync_quarantine`) with an admin UI, not a message DLQ** — supervisors need to see and resolve it.

Service Bus Standard enters later, if at all, for fan-out of notifications (FCM push on task assignment, config-version pings) and Event Grid blob events; its DLQ then backs `sync_outbox_dead`. KEDA can scale the worker from either source.

### 2.9 Config distribution (R6) without polling (R4)

- `app_config` table with effective-dated, scoped (global → wing → … → territory → zone → outlet) values, `version` monotonic, audit rows (`changed_by`, `reason`, `approved_by`). The bundle embeds the resolved snapshot for that user's scope.
- Every API response carries `X-Config-Version`. The app compares with its snapshot version and, if newer, fetches `GET /config?since=<version>` on its next natural request (trickle sync) — **zero extra polling**. Urgent changes (kill switch, radius revert) additionally go out as an FCM data message (ASSUMPTION: Firebase Cloud Messaging is acceptable in the Flutter app; it is the only battery-neutral push path on Android). Mid-day config changes apply to **new** captures only; the server re-check uses the value effective on the visit's `business_date` (F-SYS-012).

### 2.10 Secrets, identity, CI/CD

Key Vault for JWT signing keys (rotated with `kid`, two active), DB passwords (or Entra auth to Postgres via Managed Identity — preferred), FCM credentials; Managed Identity for ACA → Key Vault / Blob / Redis / Postgres; GitHub Actions with OIDC federated credentials (no stored cloud secrets); Bicep for everything in `/infra`; one subscription, three resource groups (dev / staging / prod); Azure Policy to deny public network access on Postgres/Storage in prod.

---

## 3. Capacity and cost

Monthly, USD, pay-as-you-go **list prices as remembered on 2026-10-04 — UNVERIFIED; regenerate with the Azure Pricing Calculator for the chosen region before budgeting.** Reservations (1-year) cut Postgres and Redis by roughly 30–35 %.

| Component | Pilot (≤ 20 routes, ~30 users) | Wave 1 (~1,000 SRs + their AMOs/TSOs) | Full fleet (8,500 SRs, ~11,000 users) |
| --- | --- | --- | --- |
| Container Apps `api` | 0.5 vCPU, min 1 / max 3 → 40–80 | 1 vCPU, min 2 / max 10, avg 3 → 230–300 | 1 vCPU, min 3 / max 30, avg 8 → 600–900 |
| `worker` + `web` + jobs | 30–60 | 120–200 | 300–450 |
| Postgres primary | D2ds_v5, no HA, 128 GiB SSD v2 → 150–200 | D4ds_v5 zone-redundant HA (2×), 256 GiB → 650–800 | D8ds_v5 zone-redundant HA (2×), 512 GiB, 35 d PITR, geo-backup → 1,200–1,500 |
| Postgres read replicas | — | optional D2 → 0–160 | in-region D8 → 500–600; cross-region D4 (DR) → 300–350 |
| Managed Redis (Balanced, HA, ~1 GB) | — | 60–150 | 100–250 |
| Front Door | Standard 35 + traffic → 40–60 | Standard 35 + 30 M req + APK egress → 100–200 | Premium 330 + 150 M req/month + egress → 550–800 |
| Blob (photos + APK + content) | < 10 | 50–80 | 100–200 (year-1 average ~2.5 TB mixed tiers) |
| Log Analytics + App Insights | 20–50 | 150–400 (3–6 GB/day) | **400–1,200 (6–20 GB/day at ~USD 2.3–2.8/GB; cap with sampling)** |
| Service Bus Std, Key Vault, Event Grid, Monitor alerts | 10–20 | 20–40 | 40–80 |
| Azure Load Testing (test months only) | 20–50 | 100–300 | 200–600 |
| **Total** | **≈ 300–600** | **≈ 1,500–2,500** | **≈ 4,000–7,000 (≈ 3,000–5,000 with reservations)** |

Autoscale rules (fleet): `api` HTTP concurrentRequests 30, cooldown default (KEDA 300 s scale-in, we set 120 s), min 3 so each AZ has a replica; `worker` outbox depth 200/replica; `web` HTTP 50. Pre-scale for known storms: an ACA Job at 07:30 and 16:45 Dhaka sets `minReplicas` to 8 (and back to 3 at 10:00 / 20:00) — KEDA's 15-s evaluation reacts in time, but pre-warming removes the first-minute p95 spike and the cold Node.js JIT.

What could not be verified: current unit prices; the default Consumption-cores quota of AKTCL's subscription; the exact Managed Redis SKU letters and prices; Postgres vCore quota per subscription/region; measured RTT Dhaka→Singapore vs Dhaka→Pune; Azure Load Testing engine quota per test.

---

## 4. Failure modes and mitigations for launch day

| # | Failure mode | What breaks | Detection | Mitigation (build) | Test gate |
| --- | --- | --- | --- | --- | --- |
| 4.1 | **Thundering herd at login** (§1.5): 8,500 bundle requests in 5 min, each hitting the DB | bundle p95 ≫ 2 s, DB CPU 100 %, timeouts → retries → worse | Front Door req/s, api p95, DB CPU, `pg_stat_activity` | Pre-generated snapshots at 04:00 Dhaka (ACA Job, 8 parallel workers, ~2 min for 9,850 users; falls back to live generation + alert if the job fails); `ETag`/`If-None-Match` 304; `?since=` delta for re-login; refresh-token login (no hashing); client-side jitter of the auto-login (`cfg.sync.login_jitter_s` = 0–120 s, applied only to automatic morning refresh, never to a user tap); pre-scaled replicas at 07:30 | T-4-51, T-7-51 |
| 4.2 | **DB connection exhaustion**: each replica opens N connections; scale-out multiplies; Postgres process-per-connection | `FATAL: sorry, too many clients already`; p95 cliff | `pg_stat_activity` count, PgBouncer `SHOW POOLS`, app pool wait time | All traffic via built-in PgBouncer (transaction mode); app pool per replica = 8 (api), 4 (worker/web); `max_client_conn` 5,000; server pool ≤ 150; connection acquire timeout 3 s → 503 + `Retry-After` rather than queueing forever; no `SET`/session state (transaction pooling) | T-4-53 |
| 4.3 | **Hot rows**: national/wing rollup rows updated per memo; `device.last_seen_at` and `route_log` written per request; a server-side per-zone memo sequence (if Q5 picks it) | lock waits, deadlocks, serialisation | `pg_locks` waits, `deadlock_count`, lock_timeout errors | Recompute-by-dirty-key aggregation (D-scale-2); `device.last_seen_at` written at most every 10 min (Redis gate); `route_log` upserted once per batch (already per route = per SR, not hot); **device-prefixed memo numbers** (Q5 → G-scale-18); `final_submit` is one row per zone/day — not hot, but enforce once-only with the PK, not a SELECT-then-INSERT | T-2-51 |
| 4.4 | **Long transactions in aggregation** (a "recompute today for everyone" statement during the wave) | bloat, lock queues behind it, replica lag, PITR WAL growth | `pg_stat_activity` xact age, replica lag metric, `statement_timeout` hits | Worker recomputes one (date, route) slice per transaction (< 100 ms); zone+ rollups per touched zone; nightly full recompute only at 02:00 Dhaka in 1,000-route chunks; `statement_timeout` per role (§2.5); `idle_in_transaction_session_timeout` 30 s | T-4-54 |
| 4.5 | **Duplicate / retry storms**: 5xx or timeout → every phone retries at once; a 200-row batch retried 5× re-touches 1,000 rows and recomputes aggregates 5× | self-inflicted DoS; aggregate churn | retry ratio (`X-Retry-Attempt` header histogram), 429/503 rate, `batch replay hits` | **Batch-level idempotency** (G-scale-01): `batch_uuid` from the device; server stores the response for 24 h and replays it on a repeat without touching rows; per-row `client_uuid` upsert remains the safety net. Client: debounced trickle; exponential backoff 2/4/8/16/32 s + full jitter, max 5 attempts, then WorkManager periodic 15 min; **never retry on 4xx**; honour `Retry-After`. Server: per-replica in-flight limiter (64 batches) → 429 `Retry-After: 5–60 s` with jitter; emergency `cfg.ops.sync_hold_s` tells devices to pause uploads for N s (randomised) | T-1-51, T-2-52, T-7-52 |
| 4.6 | **Liveness probe coupled to the DB**: a 90-s HA failover restarts every API replica | self-inflicted outage during failover | restart count, probe failure logs | Liveness checks the process only; readiness checks DB/Redis with 2-s timeout; startup probe tolerant (150 s) | T-4-55 |
| 4.7 | **Blob throttling / SAS misuse** | photo uploads fail; or a leaked SAS lets a device overwrite others' photos | Storage 503 metric, `ServerBusy`, unexpected paths | 95k/day is 0.5 % of the account target; SDK retries; SAS pinned to exact blob path, write-only, 15 min; path includes `device_uuid` so misuse is attributable; photo failure never blocks the sale | T-2-53 |
| 4.8 | **Bad app build** shipped to a wave | corrupt captures, crash loops, battery drain | crash rate per version (App Insights), sync rejection rate per version, battery complaints | `app_release` with `rollout_wave`/`wave_pct` (F-SYS-020); `cfg.release.min_version` (blocks **login for a new day**, never local capture or upload of already-captured rows); `cfg.release.blocked_versions` (hard stop of new captures, still allows upload); staged APK channel 1 % → 10 % → wave; keep old APK downloadable for rollback; server validates every row regardless of client version | T-7-53 |
| 4.9 | **Config mistake** (radius 0, checkout time 05:00, min_version typo, 2 Tk/pt → 200) | every sale becomes a force sale; or nobody can log in | geo-valid % drop per territory within 15 min; login % anomaly; config change audit | Bounds in the config schema (`cfg.geo.radius_m` 20–2,000; `cfg.day.checkout_earliest_time` 15:00–20:00; `cfg.release.min_version` must be a published release); scope-staged application (one territory first, `cfg.*.canary_scope`); two-person approval for keys tagged `critical`; one-click revert to previous version; alert "geo-valid % fell > 30 points vs yesterday same hour" | T-6-51 |
| 4.10 | **Region outage** (Southeast Asia down for hours) | login/bundle, sync, dashboards, final submit unavailable | Resource Health, Front Door origin health, synthetic probe | **The app keeps selling** (R1/R5 design) — but only if (a) SRs can re-login offline against cached credentials and (b) yesterday's bundle may be reused: `cfg.bundle.stale_max_days` = 2 with an on-screen warning (G-scale-05). Pending rows queue and flush when the API returns (R5). Supervisors lose dashboards; final submit waits. DR: cross-region async read replica in East Asia (RPO minutes) + Bicep redeploy of ACA/Redis/Front Door origin there; **RTO target 4 h, RPO 15 min** (ASSUMPTION — confirm tolerance); geo-redundant backup as the fallback (RPO < 1 h). Rehearse quarterly | T-7-54 |
| 4.11 | **DB failover** (planned maintenance or AZ loss) | 60–120 s of write errors (https://learn.microsoft.com/azure/postgresql/high-availability/concepts-high-availability) | HA status metric, connection errors spike | API retries transient errors (57P01, connection reset) with backoff up to 2 min; PgBouncer restarts on the new primary with the **same hostname** (documented); devices see 503 + `Retry-After`; maintenance window set to 02:00–03:00 Dhaka (20:00–21:00 UTC) | T-4-55 |
| 4.12 | **Certificate / trust issues** | TLS handshake failures on old phones; expired cert; wrong device clock | TLS error rate by Android version; synthetic checks from an Android 7 device | Front Door managed certificates auto-renew; verify the chain is trusted on **Android 7.0/8.0** fleet devices (no custom roots, no pinning); app shows "set the phone's date/time" when it detects clock skew > 10 min (also protects business_date, F-SYS-017); APK signing key in Key Vault / Play App Signing; JWT key rotation with overlapping `kid` | T-2-54 |
| 4.13 | **Quota limits**: ACA consumption cores per environment; Postgres vCores per region; storage account count; Log Analytics daily cap; Load Testing engines | scale-out silently stops at the quota ("Maximum Allowed Cores exceeded"); load test refuses to start | Quota alerts (Azure Quota Management System); scale events in system logs | Request quotas 4 weeks before wave 1: ACA ≥ 64 cores (fleet ≥ 128), Postgres ≥ 48 vCores (primary 8 + HA 8 + replicas 8+4, staging 16), Load Testing ≥ 10 engines; set alerts at 80 % (https://learn.microsoft.com/azure/container-apps/quotas) | T-7-55 |
| 4.14 | **Replica lag / stale dashboards** | numbers differ between app home strip and dashboard; TSO final-submits on stale data | `physical_replication_delay_in_seconds` | "as of" timestamp on every tile; final-submit validation reads the **primary**; alert lag > 60 s | T-4-56 |
| 4.15 | **Business-date rollover** (00:00 Dhaka = 18:00 UTC) and late syncs | a 23:30 sale aggregated into the wrong day; nightly job collides with evening tail | per-date reconciliation drift | `business_date` computed from device capture time (F-SYS-017); worker recomputes the batch's own `business_date` slices; nightly job at 02:00 Dhaka after the tail; dashboards key off business_date not `created_at` | T-1-52 |
| 4.16 | **Log/telemetry flood** (8,500 devices × verbose) | App Insights bill, sampling of the signals you need, PII leakage | daily ingestion GB, cost alert | Server-side adaptive sampling (keep 100 % of errors, 429/5xx, slow > 2 s; 5 % of 2xx); device telemetry batched in the sync payload only; daily cap on the workspace; no PII (docs/02) | T-4-57 |

---

## 5. Observability and the launch-day runbook

### 5.1 SLOs (measured at the API, monthly window, business hours 07:00–21:00 Dhaka)

| SLI | SLO | Notes |
| --- | --- | --- |
| Availability of `/auth/*`, `/sync/*`, `/day/*` | 99.9 % (≈ 43 min/month) | DB HA is 99.99 %; ACA zone-redundant; Front Door 99.99 % |
| Bundle latency (`GET /sync/bundle`, full) | p95 ≤ 2.0 s server time; p95 ≤ 10 s end-to-end on 3G (synthetic from Dhaka) | pre-generated |
| Trickle batch ack (≤ 50 rows) | p95 ≤ 1.5 s server time | |
| Catch-up batch ack (200 rows) | p95 ≤ 8 s | |
| Sync success rate | ≥ 99.5 % of batches accepted within 3 attempts; 0 batches lost | from `sync_batch` vs device telemetry |
| Reconciliation mismatch rate | ≤ 0.1 % of route-days with device ≠ server counts at sales-submit | the business's own screen |
| Aggregation lag (batch commit → facts updated) | p95 ≤ 60 s; p99 ≤ 5 min | outbox age |
| Dashboard reads (`/dashboard/*`, `/app/home`) | p95 ≤ 1.0 s | from replica |
| Reports (`/reports/*` json) | p95 ≤ 5 s; Excel ≤ 30 s | |
| Photo upload completion | 95 % of photos present within 24 h of the record | photo-pending metric |
| Error budget policy | if 50 % of the monthly budget is burned in a week, freeze feature deploys; hotfixes only | |

### 5.2 Dashboards (Azure Monitor Workbooks + the in-product sync-health screen)

1. **Sync-health (business-facing, F-SYS-026):** per wing → territory → zone: target routes, logged-in routes (login %), uploaded routes (submit %), sales-submitted, **final-submitted (zone/day)**, last batch age, pending quarantine rows, photo-pending > 24 h, mock-GPS flagged visits, geo-valid % vs yesterday, device app-version mix. Refresh 60 s from rollups. This is the screen the business already expects; make it the first thing in Phase 4.
2. **Traffic & latency (ops):** req/s by route group; p50/p95/p99 per endpoint; 429/5xx rate; retry-attempt histogram; replay-cache hit ratio; active replicas; pre-scale state.
3. **Database:** CPU, IOPS vs provisioned, connections (PgBouncer clients/servers/waiting), lock waits, longest transaction, replica lag, storage % (no autogrow on SSD v2), WAL size, autovacuum lag on the partitioned tables.
4. **Worker:** outbox depth, age of oldest unprocessed row, recompute duration, dead rows.
5. **Devices (aggregated, no PII):** app version share, Android version share, sync failures by reason, average batch size, photos pending, battery-pack telemetry if the app reports it (opt-in, batched).

### 5.3 Alerts (severity, threshold, first action)

| Sev | Alert | Threshold | First action |
| --- | --- | --- | --- |
| 1 | API availability | 5xx > 2 % for 5 min, or Front Door origin unhealthy | Runbook 5.4 step 3 |
| 1 | DB primary unavailable / HA failing over | HA state ≠ Healthy for > 3 min | Watch failover; if > 5 min open Sev A ticket; verify PgBouncer reconnect |
| 1 | Login % at 09:00 Dhaka < 70 % of yesterday for any wing | daily, 09:00 | Check bundle job success, Front Door, app version blocks |
| 2 | Bundle p95 > 3 s for 10 min | | Confirm snapshots exist for today; scale `api` min to 8 |
| 2 | 429 rate > 5 % for 5 min | | Expected during storm if brief; if sustained, raise limiter and replicas |
| 2 | Outbox age > 5 min | | Scale worker; check for a poisoned batch |
| 2 | Replica lag > 60 s | | Dashboards show stale banner automatically; investigate long queries on replica |
| 2 | Geo-valid % for a territory fell > 30 points vs yesterday same hour | | Check config change log for that territory; revert if radius changed |
| 2 | Quarantine rows > 100/h or rejected rows > 0.5 % of batch rows | | Look at reasons; likely scope/assignment data problem |
| 3 | Storage > 70 % (SSD v2 has no autogrow) | | Grow disk (online) |
| 3 | Consumption cores at 80 % of quota | | Request increase |
| 3 | Log Analytics ingestion > budget | | Tighten sampling |
| 3 | Pre-gen job failed or ran > 15 min | 04:20 Dhaka | Re-run; API is already falling back to live generation |

### 5.4 Launch-day runbook (per wave; T-7-5x gates must be green first)

**T-7 days:** quotas confirmed (§4.13); load test T-7-51/52 passed on staging at 1.5× the wave's size; DR rehearsal done this quarter; on-call rota (two engineers + one business owner per wave) published; rollback APK and config version noted.

**T-1 day:** final delta import (docs/11) reconciled; `rollout_wave` set; `cfg.release.min_version` unchanged; pre-scale schedule enabled; synthetic Android-7 probe green; "known-good" dashboard snapshot saved for comparison.

**04:00 Dhaka:** bundle pre-gen job → verify count = users in wave + existing; spot-check 3 bundles (sizes, business_date, config version).

**07:00:** `api` minReplicas 8; war-room open; sync-health screen on the wall.

**07:30–09:30 checklist (every 10 min):** login % per zone of the wave vs target; bundle p95; 429/5xx; TLS error rate by Android version; replay-cache hits (should be low); DB CPU < 60 %. **Decision points:** login % < 50 % at 08:45 → check app-version block, Front Door, carrier NAT rate-limit hits; if a WAF rule is blocking, switch it to Log mode (Front Door custom rules support Log).

**10:00–16:00:** trickle health: batches/s roughly flat, ack p95 < 1.5 s, outbox age < 1 min, geo-valid % in line with pilot, mock-GPS flags reviewed by AMOs, quarantine reasons triaged (most will be assignment/scope data errors on day one — have an admin ready to fix route assignments and regenerate those users' bundles).

**16:45:** `api` minReplicas 8 again; `worker` min 3.

**17:00–19:00:** submit % climbing per zone; reconciliation mismatch list (route, type, device count, server count) worked live by the business owner — every mismatch on day one is either a bug or an un-synced child row; final-submit progress by zone; photo-pending counts.

**21:00:** day close: counts (device totals from telemetry vs server), aggregation lag, error budget consumed; go/no-go for next day; write the day log into `DECISIONS.md`/ops journal.

**Rollback triggers (pre-agreed):** reconciliation mismatch > 2 % of routes; sync success < 97 % for 1 h; crash rate > 2 % of sessions for the wave's app version; any data-corruption class bug. Rollback = revert that wave to the old app (docs/11 step 5); new-app data already synced is preserved and exported if needed (G-feat-67 in the features lens).

---

## 6. Load-test plan

### 6.1 Tooling

**Azure Load Testing with Locust** (Python) — Azure Load Testing runs JMeter or Locust natively, collects server-side metrics from ACA/Postgres/Redis/Front Door during the run, and can be wired into GitHub Actions with pass/fail criteria (https://learn.microsoft.com/azure/app-testing/load-testing/overview-what-is-azure-load-testing). Locust lets us write a **device simulator** (class per role) that generates realistic UUID batches, duplicates, out-of-order children, gzip, and backoff — JMeter cannot do that cleanly. Recommended ≤ 500 users per engine instance; engines are D4d_v4 VMs (https://learn.microsoft.com/azure/app-testing/load-testing/how-to-high-scale-load). k6 remains the developer-laptop tool for the Phase 1–2 fuzz tests (CI, no Azure cost). Target environment: **staging scaled to the prod SKU for the test window** (G-scale-17), seeded with the imported Apsis data (or synthetic 460k outlets, 1,051 zones, 9,850 users).

### 6.2 Scenarios

| ID | Scenario | Shape | Pass criteria |
| --- | --- | --- | --- |
| S1 Login storm | 9,850 virtual users, each: refresh → bundle → home → release-check; arrival over 5 min (worst) and 20 min; 10 % send `If-None-Match` | 130 req/s peak | bundle p95 ≤ 2 s, 0 5xx, DB CPU ≤ 60 %, no quota scale stop |
| S2 Immediate-sync trickle | 5,100 users online, 1 batch (12 rows) every 4 min each with 2× midday peak; 5 % of batches duplicated; 2 % children before parents; 1 % resent with a different `batch_uuid` but same `client_uuid`s | 13 batches/s | ack p95 ≤ 1.5 s; server row counts == distinct client_uuids (no doubles); aggregation lag p95 ≤ 60 s |
| S3 Evening wave | 3,400 users upload 180 rows in 200-row batches + 9,850 check-outs + 9,550 sales-submits + 1,051 final-submits in 20 min; 3 % of users retry every batch 3× (simulated bad network) | 90 req/s, 1,000 rows/s | catch-up ack p95 ≤ 8 s; 429 ≤ 5 %; 0 lost rows; replay-cache hit rate ≈ duplicates sent; lock waits p95 < 50 ms |
| S4 Worst-case catch-up | 8,500 users × 180 rows in 30 min | 1,700 rows/s | DB CPU ≤ 80 %, IOPS ≤ 60 % of 12k, ack p95 ≤ 12 s, 0 lost rows |
| S5 Dashboards concurrent with S3 | 1,500 readers at 1–2 min refresh on the replica; 50 Excel exports | 50 QPS + heavy | dashboard p95 ≤ 1 s; replica lag ≤ 30 s; S3 criteria unaffected |
| S6 Photo burst | 50 SAS/s + 50 Blob PUT/s of 150 KB for 10 min | 7.5 MB/s | 0 API bytes proxied; SAS p95 ≤ 300 ms; Storage 503 = 0 |
| S7 Chaos | During S3: planned Postgres failover; kill 50 % of api replicas; set `cfg.ops.sync_hold_s`=60 | | failover error window ≤ 120 s; no lost rows; devices resume automatically; sync_hold observed within 1 trickle cycle |
| S8 Soak | S2 shape for 10 h at 1× | | no memory growth > 10 %/h in api/worker; outbox drains; connection counts flat |
| S9 Config propagation | change `cfg.geo.radius_m` for one territory during S2 | | `X-Config-Version` seen by 95 % of that territory's simulated devices within 2 trickle cycles; no change elsewhere |

### 6.3 When each runs (phase gates, 50-range IDs)

| Gate | Phase | What | Tool |
| --- | --- | --- | --- |
| T-1-51 | 1 | Idempotency fuzz: 10,000 random duplicate / reordered / partial batches converge to one server state; `batch_uuid` replay returns identical body | k6 + property tests in CI (CLAUDE.md requirement) |
| T-1-52 | 1 | Business-date rollover: sales at 23:59/00:01 Dhaka land correctly; late batch re-aggregates yesterday | unit + k6 |
| T-2-51 | 2 | Hot-row test: 500 memos/s into one zone; no deadlocks; aggregates equal raw sums | k6 vs staging |
| T-2-52 | 2 | Client backoff conformance: simulated 503/429 storms; attempts ≤ 5; jitter present; no retry on 4xx | device integration test |
| T-2-53 | 2 | SAS misuse: wrong path / expired / read attempt rejected; photo failure never blocks sale sync | integration |
| T-2-54 | 2 | TLS chain on Android 7.0 and 8.0 physical devices; clock-skew message | manual device lab |
| T-4-51 | 4 | S1 at 1,000 users (wave-1 size) 5-min concentration | Azure Load Testing |
| T-4-52 | 4 | S1 at 9,850 users; decide Redis fast-path | Azure Load Testing |
| T-4-53 | 4 | Connection exhaustion: scale api to 30 replicas under S3; PgBouncer waits = 0 | Azure Load Testing + pg metrics |
| T-4-54 | 4 | Aggregation under S3 + S5: lag p95 ≤ 60 s; longest txn < 1 s | |
| T-4-55 | 4 | S7 failover chaos | |
| T-4-56 | 4 | Replica lag and stale banner | |
| T-4-57 | 4 | Telemetry volume per 1,000 devices ≤ 1 GB/day | |
| T-6-51 | 6 | Config bounds / two-person / canary scope / revert; radius 0 rejected | admin e2e |
| T-7-51 | 7 (pre-wave 1) | S1+S2+S3+S5 at 1.5× wave size on prod-SKU staging | Azure Load Testing, CI gate |
| T-7-52 | 7 (pre-wave 1) | S4 worst-case + S7 chaos | |
| T-7-53 | 7 | Kill-switch drill: block a version; capture continues; upload continues; login refused | device lab |
| T-7-54 | 7 | DR rehearsal: promote East Asia replica, redeploy via Bicep, point Front Door; measure RTO | quarterly |
| T-7-55 | 7 | Quota check-list signed off | manual |
| T-7-56 | 7 (pre-full fleet) | S1–S5 at 1.5× full fleet (12,750 SRs) + S8 soak | Azure Load Testing |

---

## 7. Config keys, proposed decisions, gaps

### 7.1 Config keys this lens introduces or constrains (R6; all audited, scoped, bounds-checked)

| Key | Default | Bounds / notes |
| --- | --- | --- |
| `cfg.sync.trickle_debounce_s` | 10 | 2–60; coalesces a visit's children into one batch |
| `cfg.sync.batch_max_rows` | 200 | 50–500 |
| `cfg.sync.retry_backoff_s` | [2,4,8,16,32] + full jitter | max 5 attempts |
| `cfg.sync.periodic_min` | 15 | 15–120 (WorkManager minimum is 15) |
| `cfg.sync.login_jitter_s` | 120 | 0–600; automatic morning refresh only |
| `cfg.sync.max_clock_skew_min` | 10 | flags row; prompts user |
| `cfg.bundle.pregen_time` | 04:00 Asia/Dhaka | |
| `cfg.bundle.stale_max_days` | 2 | 1–3; offline start on yesterday's bundle with warning |
| `cfg.media.photo_max_kb` / `long_edge_px` / `wifi_only_default` | 150 / 1024 / true | |
| `cfg.release.min_version` / `blocked_versions` / `wave_pct` | — | min_version must reference a published `app_release`; critical (two-person) |
| `cfg.ops.sync_hold_s` | 0 | 0–900; emergency brake, randomised per device |
| `cfg.ops.read_only_mode` | false | API refuses writes with 503 + Retry-After during a migration |
| `cfg.ops.dashboard_refresh_min_s` | 60 | 30–300; server-enforced minimum |
| `cfg.geo.radius_m` | 100 | **20–2,000**; critical; canary scope first |
| `cfg.day.checkout_earliest_time` | 17:00 | 15:00–20:00; critical |
| `cfg.auth.access_ttl_min` / `refresh_ttl_days` | 60 / 30 | refresh must outlive a weekend + holiday (≥ 7 d) |
| `cfg.api.rate_limit_per_device_per_min` | 120 | per device in Redis; WAF per-IP limits stay ≥ 20,000/5 min |

### 7.2 Proposed decisions for DECISIONS.md (numbers assigned on merge)

- **D-scale-1** Synchronous ingest to Postgres with authoritative accepted counts; no queue in front of the DB. (§2.8)
- **D-scale-2** Transactional outbox + worker; aggregates recomputed per dirty (business_date, route) slice, never incremented. (§1.9, §2.8)
- **D-scale-3** Per-user bundle snapshots pre-generated at 04:00 Dhaka to Blob (+ Redis), served by the API with ETag; live `?since=` deltas. (§2.4)
- **D-scale-4** Batch-level idempotency (`batch_uuid`) with 24-h stored response, on top of row-level `client_uuid`. (§4.5)
- **D-scale-5** Region Southeast Asia, DR East Asia, RTO 4 h / RPO 15 min — pending the data-residency answer (Q-new). (§2.2, §4.10)
- **D-scale-6** Azure Managed Redis (not Azure Cache for Redis); required before wave 1. (§2.7)
- **D-scale-7** Front Door Standard until wave 1, Premium with Private Link origin at full fleet. (§2.3)
- **D-scale-8** Daily login is a refresh-token exchange; password login only on first use/expiry. (§1.5)
- **D-scale-9** Kill switch semantics: `min_version` blocks a new day's login; `blocked_versions` blocks new captures; neither ever blocks upload of captured rows or wipes local data. (§4.8)
- **D-scale-10** Memo numbers are device-prefixed (offline-safe, no hot sequence); server adds a report series if the business insists (Q5). (§4.3)
- **D-scale-11** Monthly range partitioning of transaction and daily-fact tables from Phase 1. (§2.5)
- **D-scale-12** Azure Load Testing + Locust device simulator; prod-SKU staging for test windows. (§6)

New open questions for docs/13: **Q19** data residency (PII outside Bangladesh acceptable?); **Q20** business tolerance for RTO/RPO during a region outage; **Q21** photo retention period; **Q22** whether FCM push is acceptable in the app.

### 7.3 Gap list (G-scale-NN)

| ID | Sev | Title | Where | Detail / what to add |
| --- | --- | --- | --- | --- |
| G-scale-01 | blocker | No batch-level idempotency or response replay | docs/04 §sync protocol, docs/09 `POST /sync/batch`, `sync_batch` | Spec keys idempotency on row `client_uuid` only. A retried 200-row batch re-executes 200 upserts and re-triggers aggregation; a half-received body with a dropped response is indistinguishable from a new batch. Add `batch_uuid` (device-generated), store `(batch_uuid → response, 24 h)`, replay on repeat. |
| G-scale-02 | blocker | Aggregation strategy unspecified; increments would create hot rows and double counts | docs/10 "incremental upserts keyed by business_date", schema `fact_*` | Mandate recompute-by-dirty-key from raw tables via outbox worker; add `rollup_daily_*`, `fact_mtd_route_product`, `rollup_mtd_*` tables; define aggregation-lag SLO. |
| G-scale-03 | blocker | Bundle generation path not designed for the morning storm | docs/04 "reference bundle", docs/09 `GET /sync/bundle` | Add per-user pre-generated snapshots (04:00 Dhaka job), ETag/304, invalidation on assignment/config change, fallback to live generation with alert. |
| G-scale-04 | major | Per-IP rate limiting is harmful behind carrier-grade NAT | docs/02 (no rate-limit design), Front Door WAF | Define per-device/user limits in the API (Redis); WAF per-IP thresholds ≥ 20,000/5 min; Log mode first. |
| G-scale-05 | major | No offline re-login or stale-bundle policy | docs/04 day state machine, docs/06 setup/session | If the API is unreachable at 08:00, an SR who has not downloaded today's bundle cannot start. Add cached-credential login and `cfg.bundle.stale_max_days`; define how Login % treats a stale-bundle day. |
| G-scale-06 | major | Password-hash CPU at login storm; token lifetimes undefined | docs/13 Q4, docs/09 `/auth/login` | Daily login must be a refresh; set `refresh_ttl_days` ≥ 7 (recommend 30); budget argon2 parameters. |
| G-scale-07 | major | `activity_log` volume unbounded (~0.4 M rows/day) | docs/03 sync & logs, schema `activity_log` | Batch inside sync payload, partition monthly, 90-day retention, sample non-critical screens; never on the hot path. |
| G-scale-08 | major | No partitioning or retention for transaction and fact tables | schema.sql | `fact_daily_outlet` alone is 170 M rows/year; partition by month, archive/drop policy, index strategy per partition. |
| G-scale-09 | major | Per-request hot writes: `device.last_seen_at`, `route_log` | schema `device`, `route_log`; F-SYS-025 | Throttle `last_seen` to once per 10 min (Redis gate); `route_log` once per batch; define columns as "first/last" not counters incremented per request. |
| G-scale-10 | major | No timeouts, pool sizes, concurrency limits or 429 semantics defined | docs/02, docs/09 rules | Add §2.5 parameters per DB role, per-replica in-flight limiter, `Retry-After`, client contract for 429/503. |
| G-scale-11 | major | Quotas not pre-requested; defaults unknown | docs/02 environments, docs/12 Phase 0 | Add a Phase 0 task: check and request ACA cores, Postgres vCores, Load Testing engines, Log Analytics cap; quota alerts. |
| G-scale-12 | major | DR posture undefined (RTO/RPO, paired region, redeploy) | docs/02 hosting, docs/13 Q3 | Decide D-scale-5; cross-region replica; Bicep redeploy runbook; quarterly rehearsal T-7-54. |
| G-scale-13 | major | Telemetry cost and PII exposure at fleet scale | docs/02 observability | Sampling policy, daily cap, device telemetry only inside sync batches, PII scrubbing tests. |
| G-scale-14 | minor | TLS trust / device clock on old Android untested | docs/06 permissions, docs/04 clock-skew tests | Add Android 7/8 device-lab gate T-2-54; clock-skew UX. |
| G-scale-15 | major | Config-change blast radius (radius 0, min_version typo) | SF-6, R6 | Bounds schema, canary scope, two-person approval for `critical` keys, one-click revert, anomaly alert on geo-valid %. |
| G-scale-16 | major | APK and thumbnail distribution on wave day via the API would saturate it | docs/06 in-app update, docs/11 distribution | Serve from Blob behind Front Door cache; Wi-Fi-preferred; stagger by `wave_pct`. |
| G-scale-17 | minor | Staging must be prod-sized for load tests; procedure and budget undefined | docs/02 environments | Scale-up/scale-down runbook for test windows; cost line in §3. |
| G-scale-18 | major | Server-assigned memo sequence (one option in Q5) is a hot sequence and is not printable offline | docs/06 offline behaviour, docs/13 Q5 | Decide device-prefixed numbering (D-scale-10). |
| G-scale-19 | minor | Business-date rollover and nightly job scheduling | docs/04 clock tests, docs/10 aggregation | Cron in UTC for Dhaka times; nightly jobs after the evening tail; late-batch recompute of its own date. |
| G-scale-20 | major | Photo linkage lifecycle undefined (record acked before photo exists) | docs/04 step 4, schema `*_photo` | `photo_state` (pending/present/missing), deterministic blob path in the record, Event Grid/nightly sweep to confirm, "photo pending > 24 h" on sync-health, orphan-blob cleanup. |
