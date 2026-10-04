# Lens: Feature Inventory (requirement R2 — nothing missed)

Date: 2026-10-04. Source of truth: docs/06 (SR), 07 (AMO), 08 (TSO), 09 (web + admin + API), 10 (programs), 11 (migration), with 03/04/05 for data and rules. Builds on `seed-findings.md` (SF-1..SF-10 below refer to its numbered items; they are not re-derived here).

## 0. How to read this document

| Column | Meaning |
| --- | --- |
| ID | `F-<ROLE>-<nnn>`; ROLE in SR, AMO, TSO, WEB, ADM, API, SYS |
| Role(s) | who uses it. `all-app` = SR+AMO+TSO app modes |
| Writes / Reads | server tables (docs/03 names); `L:` prefix = local SQLite mirror only; `+` = table the schema lacks today (see SF-5) |
| Off | offline requirement: **Y** fully offline, **P** partial (cached read / action queued), **N** network required |
| Prt | prints: `memo`, `stock`, `summary`, `-` |
| Pho | takes/uploads a photo |
| Geo | geo-gated: **Y** = blocked/branched on distance check, **fix** = records a GPS fix but no gate, **-** |
| Rules | validations stated in the spec, plus `cfg.*` keys that R6 must expose (ASSUMPTION where marked) |
| Q | docs/13 question numbers the feature depends on |
| Ph | build phase from docs/12 (0–7); `2*` = needed before the Phase 7 pilot even though docs/12 leaves it unplaced |

Totals per role and per phase are in §7 and were produced by counting the rows below, not by hand.

Legend for gaps: `G-feat-NN` defined in §8; severity `blocker` (cutover-day parity breaks or a sale cannot be completed), `major` (a daily workflow or a report is wrong/missing), `minor` (cosmetic or confirm-only).

---

## 1. Cross-cutting system features (F-SYS)

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Off | Prt | Pho | Geo | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SYS-001 | Username/password login | all-app, web | Authenticates; returns JWT (access+refresh) with role + resolved scope | App launch / web login page | `activity_log`, `device.last_seen_at` | `app_user`, `+password_hash`, `user_scope` | N (first login); P (offline re-entry with cached session, ASSUMPTION: current app allows re-open of a downloaded day without signal) | - | - | - | Same logins as Apsis where possible (docs/01). Lockout and forgot-password unspecified → G-feat-34. `cfg.auth.access_ttl_min`, `cfg.auth.refresh_ttl_days`, `cfg.auth.lockout_attempts` | F-API-001 | 4 | 0 |
| F-SYS-002 | Token refresh | all-app, web | Silent refresh of access token | Expiry | - | `+refresh_token` | N | - | - | - | Refresh must not interrupt an offline day; refresh failure must never block local capture or local reads (R5) | F-API-002 | 4 | 0 |
| F-SYS-003 | Device binding by TSO OTP | SR, AMO (TSO issues) | Binds `device_uuid` to user; unbound device cannot sync | First login on a new phone | `device`, `+device_otp` (issued, used_at, issued_by) | `+device_otp` | N | - | - | - | OTP single-use, TSO-issued (docs/06). TTL, one-device-per-user vs many, re-bind on shared phones unspecified → G-feat-11, G-feat-41. `cfg.auth.otp_ttl_min`, `cfg.auth.max_devices_per_user` | F-API-003, F-ADM-022 | 4 | 0 |
| F-SYS-004 | Change password + policy | all roles | Enforces: ≥12 chars, mixed case + number, not one of last 10, not within 24 h of last change | Credentials page / app settings | `+password_hash`, `+password_history` | `+password_history` | N | - | - | - | Rules exact in docs/09. `cfg.auth.pw_min_len=12`, `cfg.auth.pw_history=10`, `cfg.auth.pw_min_age_h=24` | F-API-004 | 4 | 0 |
| F-SYS-005 | Server-side scope resolution | API | Expands `user_scope` nodes to the subtree (wing→…→route) and intersects every query; client never sends scope IDs | Every request | - | `user_scope`, geography | n/a | - | - | - | Non-negotiable #4. SR scope = active `route_assignment` rows for the business date, not static | all reads | - | 0 |
| F-SYS-006 | Reference bundle download (= login event) | all-app | One gzip payload: routes, outlets (+lat/lng, cluster, dues, loyalty, suggested qty), products, prices, sales plan, targets+achievement, offers, geo radius, tasks, gift assignments, config | App login / Home refresh | `route_log.download_*`, `L:*` | all reference tables, `target`, aggregates, `+app_config` | N | - | - | - | Counts as "logged in" for Login % (SF-9: define: first full bundle of the business date for a route). Bundle must carry the **business date it is valid for** and the cfg snapshot (R6). | F-API-005 | 7, 8 | 1 |
| F-SYS-007 | Bundle delta refresh | all-app | `?since=` returns only changed reference rows | Re-login, manual refresh, periodic constrained job | `route_log` (not a login) | `updated_at` on all reference tables | N | - | - | - | Must not count as a login (SF-9). Price/offer changes mid-day: device applies them only to new memos (G-feat-52) | F-API-005 | - | 1 |
| F-SYS-008 | Idempotent batch upload | all-app | `POST /sync/batch` groups pending rows by type; server upserts by `client_uuid`; returns accepted counts + rejected list | Sale saved, manual Sync, end-of-day, constrained background job, connectivity regained (R5) | all field tables, `sync_batch`, `route_log.upload_*` | `L:*` where `sync_state=pending` | N (send) / Y (queue) | - | - | - | Parent-before-child ordering; tolerate out-of-order; SF-2 tables need `client_uuid` added (memo_line, qc_entry, survey_response, drp_collection, loyalty_ledger, gift_photo, outlet_photo, task). Immediate sync on connectivity = WorkManager `NetworkType.CONNECTED` constraint + explicit trigger after each save (R4+R5) | F-API-006 | - | 1 |
| F-SYS-009 | Device-vs-server reconciliation screen | SR, AMO | Shows per-type counts (device vs accepted) and the mismatch | After each sync; before Sales Submit | - | `L:*`, response of F-SYS-008 | Y (shows last known) | - | - | - | SR types: outlet, sale, stock, QC, promotion. AMO adds: distribution & OOS, price compliance, joint call, survey. Mismatch is visible, never silent | F-API-006 | - | 1 |
| F-SYS-010 | Media queue + photo upload | all-app | Separate queue of compressed photos (≤100–200 KB, long edge ~1024 px); uploads Wi-Fi-preferred; links blob URL by `client_uuid` | After any photo capture | Blob, `outlet_photo.blob_url`, `survey_response.photo_url`, `redemption.photo_url`, `gift_photo.photo_url`, `+feedback.image_url` | `L:media_queue` | Y (queue) | - | Y | - | A slow photo never blocks a record from syncing; record syncs with photo pending. `cfg.media.photo_max_kb`, `cfg.media.long_edge_px`, `cfg.media.wifi_only_default` | F-API-007 | 18 | 2 |
| F-SYS-011 | Constrained background sync | all-app | WorkManager job: network connected + battery not low; coalesced; wake lock only during the batch | OS scheduler + connectivity change | as F-SYS-008 | `L:*` | Y | - | - | - | No persistent socket, no polling (docs/04). `cfg.sync.min_interval_min`, `cfg.sync.batch_max_rows`, `cfg.sync.retry_backoff` | F-API-006/007 | - | 1 |
| F-SYS-012 | Server geo re-check on ingest | API | Recomputes Haversine from stored fix vs outlet coordinates and radius; sets authoritative flag | On `POST /sync/batch` | `visit.geo_validated_server` (SF-4), `+visit.geo_mismatch` | `outlet`, `territory_geo_config`/`+app_config` | n/a | - | - | Y | Device flag kept separately (SF-4). Mock fix can never be geo-valid. Radius used = the radius effective on the **visit's business_date**, not today's | F-API-006 | 7 | 2 |
| F-SYS-013 | Anti-spoofing plausibility flags | API, WEB, AMO, TSO | Teleport speed, perfect accuracy, zero jitter over a route, all outlets from one coordinate → flag visit/route; surface counts to supervisors | After batch ingest (job) | `+visit_flag` (visit_id, flag_type, score) | `visit` fixes | n/a | - | - | - | Report, don't just block; `cfg.geo.mock_policy = flag|block`, `cfg.geo.max_speed_kmh`, `cfg.geo.min_accuracy_m`, `cfg.geo.jitter_threshold` | F-API-016, F-API-034 | - | 2 |
| F-SYS-014 | Quarantine of invalid references | API | Records referencing outlets/SKUs not in the SR's scope or unknown are quarantined, not dropped | Batch ingest | `+sync_quarantine` (client_uuid, payload, reason, resolved_by) | scope | n/a | - | - | - | Rejected list returned to app; admin review UI → F-ADM-030 | F-API-006 | - | 2 |
| F-SYS-015 | Aggregation into fact tables | API/worker | Rolls synced rows into `fact_daily_route_sku`, `fact_daily_outlet`, `fact_daily_route` + zone/territory/division/wing rollups | After each batch (incremental), re-run for late batches by `business_date` | `fact_*` | field tables, `target` | n/a | - | - | - | Late-arriving batch re-aggregates its own business_date (seed scale note). Force sale = photo-valid, never geo-valid in any KPI | - | 8, 9, 10 | 1 |
| F-SYS-016 | Day state machine | API, SR, AMO, TSO | `not_started → logged_in → in_field → synced → sales_submitted → final_submitted` per route; drives Login %, Submit %, final-submit status | Bundle, check-in, upload, Sales Submit, Final Submit | `route_log`, `fact_daily_route.login_state`, `final_submit` | - | P (device knows its own state) | - | - | - | SF-10: state entity ambiguity (route vs user vs zone) must be fixed before Phase 1 DoD. Check-out ≥ 17:00 (`cfg.day.checkout_earliest_time`), final submit once/zone/day | F-API-008/009 | 11 | 1 |
| F-SYS-017 | Business-date stamping | all-app, API | Every transaction gets UTC time + Asia/Dhaka `business_date`; a late-evening sale lands on the right date; "today's route" keyed off it | Every write | all transaction tables | - | Y | - | - | - | Device clock skew: server stores device time and server receive time; business_date derived from device capture time unless skew > `cfg.sync.max_clock_skew_min` → flag | - | - | 1 |
| F-SYS-018 | Localization layer + bundled fonts | all-app, web | Bangla-first UI, English labels where current app has them; one Bengali + one Latin font | Build | - | string tables | Y | - | - | - | No hardcoded strings (CLAUDE.md DoD) | - | - | 1 |
| F-SYS-019 | Language toggle | all-app | Settings → English / বাংলা; persists per device | Settings | `L:prefs` | - | Y | - | - | - | Printed memo language follows memo template, not UI language (ASSUMPTION; confirm) | - | 5 | 1 |
| F-SYS-020 | In-app updater | all-app | Checks for new version; "new update available → download & install"; fleet side-loads APKs | App start (once/day) / Settings | `L:prefs` | `+app_release` (version, min_version, apk_url, notes, rollout wave) | N (check) | - | - | - | Download Wi-Fi-preferred; `min_version` forces update; staged by wave for Phase 7 (`cfg.release.min_version`, `cfg.release.wave_pct`). APK size budget (docs/04) | F-API-029 | 3 | 2* |
| F-SYS-021 | "PDA to Support" diagnostic upload | all-app | Uploads the device's local DB/sync file + logs to support | Settings | Blob (`support/` container), `+support_upload` | `L:*` | N | - | - | - | Must strip nothing the support team needs but no PII in logs (docs/02); Wi-Fi-preferred; size cap `cfg.support.max_upload_mb` | F-API-030 | - | 2* |
| F-SYS-022 | Logout | all-app | SR/AMO: ends session (keeps local day data; ASSUMPTION). TSO: **wipes all local app data** with warning "your all app data will be removed" | Settings / drawer | `L:*` (TSO wipe) | - | Y | - | - | - | TSO wipe must refuse while pending uploads exist, or warn with count (G-feat-41). SR logout with pending data: confirm | F-API-031 | - | 2 |
| F-SYS-023 | Runtime permission flow | all-app | Requests location (while using), camera, Bluetooth, (microphone — Q15) with rationale screens | First use of each feature | - | - | Y | - | - | - | No background location permission (R4). Microphone only if Q15 confirms | - | 15 | 1 |
| F-SYS-024 | Activity log | all-app, API | Screen/action audit per user | Every significant action | `activity_log` (+`client_uuid`, batched) | - | Y (queue) | - | - | - | Must ship in the sync batch, not chatter (R4) | F-API-006 | - | 2 |
| F-SYS-025 | Route log (Data Entry Log source) | API | First/last download and upload time and counts per route/day | Bundle + batch | `route_log` | - | n/a | - | - | - | Feeds Data Entry Log report, Login %, Submit % | - | - | 1 |
| F-SYS-026 | Sync-health / ops dashboard | ops, ADM | Login %, submit %, final-submit per zone, batch latency, rejected/quarantined counts, error rates | Ops | - | `route_log`, `sync_batch`, `+sync_quarantine` | N | - | - | - | Privacy-aware logs (no PII). Launch-day storm visibility (R3) | F-API-028 | - | 1 |
| F-SYS-027 | Memo numbering | SR, AMO, API | Identity = `client_uuid`; human memo number server-assigned on ingest **or** device-prefixed local number printed offline | Memo save | `memo.+memo_no` | `+memo_sequence` | Y (local number) | memo | - | - | Retailer-facing. Must be printable offline → device-prefixed local number is the only offline-safe option; server may add a series number for reports. Continuity with Apsis series = Q5. Sale edit → new memo row; reprint shows which number? (G-feat-04) | F-API-006 | 5 | 1 |
| F-SYS-028 | Local working-data purge after clean final submit | all-app | Clears synced per-day data when server confirms counts match; keeps audit server-side | After final submit confirmed in bundle refresh | `L:*` | - | Y | - | - | - | Never purge rows with `sync_state != synced`. Retention of local history for "View previous sale data" (F-AMO-013) → `cfg.app.local_history_days` | - | 11 | 2 |
| F-SYS-029 | Bounded image cache (LRU) | all-app | Product thumbnails, AV/KV content cached on disk with max size | Content download | `L:cache` | - | Y | - | - | - | `cfg.app.image_cache_mb`; never only in memory | - | - | 2 |
| F-SYS-030 | Photo capture + compression pipeline | all-app | Capture → compress → queue → release camera | Any photo feature | `L:media_queue` | - | Y | - | Y | fix | Stamp lat/lng/accuracy/mock on every photo (outlet photos update location) | F-API-007 | - | 2 |
| F-SYS-031 | Integrity signals at login | all-app, API | Mock-location capability, developer options, rooted hint, Play Integrity verdict; stored and weighted, not hard-blocked | Login / bundle | `device.+integrity_json`, `+device_integrity_log` | - | N | - | - | - | Weighted into F-SYS-013; `cfg.geo.integrity_weight` | F-API-001/005 | - | 2 |
| F-SYS-032 | Error reporting (privacy-aware) | all-app, web, API | Crash/error reporting (e.g. Sentry) with PII scrubbed | Runtime | external | - | P (buffered) | - | - | - | No PII; batched upload (R4) | - | - | 0 |
| F-SYS-033 | Role-aware single app | all-app | One Flutter codebase; Home/tiles/theme by role (TSO dark theme) | Login role | - | token role | Y | - | - | - | Or three builds if business prefers (docs/13 "deliberately changed") | - | 1 | 1 |
| F-SYS-034 | Target sanity guards | API, web, app | Reject target < 0 at entry; clamp/flag at read; divide-by-zero → "-" | Target entry + every % | `target` | `target`, `fact_*` | Y (local % math) | - | - | - | The −37,500 % bug (docs/06, 10) | F-API-021/032 | - | 1 |
| F-SYS-035 | Suggested order quantity hook | API, SR | Per-outlet suggested qty computed server-side from history, delivered in bundle, shown at order entry | Bundle | `+outlet_suggestion` (outlet_id, sku_id, qty, basis) | history facts | Y (from bundle) | - | - | Y (shown on geo-valid open) | Formula unknown (Q6); ship the field empty until confirmed | F-API-005 | 6 | 2 |
| F-SYS-036 | APK size budget | all-app | Per-ABI splits / app bundle, stripped assets, one Bengali + one Latin font | Release | - | - | - | - | - | - | Materially < 90 MB; asset audit each release | - | - | 1 |
| F-SYS-037 | "Sync photos on Wi-Fi only" setting | all-app | Per-device toggle for media queue transport | Settings | `L:prefs` | - | Y | - | - | - | Default from `cfg.media.wifi_only_default` | - | - | 2 |
| F-SYS-038 | Apsis dump importer + ID crosswalk | ADM/eng | Imports reference → products → users/routes → outlets → targets → dues/loyalty → transactions → media; idempotent re-import via `+id_crosswalk` | CLI / admin job | all tables, `+id_crosswalk` | dump files | n/a | - | - | - | Order fixed in docs/11; seed data keeps build unblocked | - | 18 | 0 (skeleton), 7 (full) |
| F-SYS-039 | Post-import reconciliation report | ADM/eng | Row counts + control totals per zone (outlets, MTD STD, memos, open dues, loyalty balances) vs old reports | After import | `+import_reconciliation` | all | n/a | - | - | - | No cutover until it matches | F-API-033 | - | 7 |
| F-SYS-040 | Delta re-import before a wave | ADM/eng | Re-imports latest Apsis delta so balances are current at switch | Pre-wave | as F-SYS-038 | dump delta | n/a | - | - | - | Idempotent via crosswalk | - | 18 | 7 |
| F-SYS-041 | Wave rollout control | ADM | Marks territories/wings as "on new system"; gates bundle and updater by wave; old app set read-only for the wave | Admin | `+rollout_wave` (scope node, start, status) | geography | n/a | - | - | - | AKTCL cannot flip the Apsis app itself → G-feat-67 | F-API-029/033 | - | 7 |
| F-SYS-042 | Wave rollback | ADM | Reverts a wave to the old app; new-app data already synced is preserved | Admin | `+rollout_wave.status` | - | n/a | - | - | - | Data captured in new app during the wave must be exportable to Apsis format or kept as system-of-record → G-feat-67 | F-API-033 | - | 7 |
| F-SYS-043 | Parallel-run comparison report | ADM | Daily compare for pilot routes: memos, STD, dues, geo %, submit % — new vs old | Pilot period | `+parallel_compare` | facts + imported Apsis daily | n/a | - | - | - | Pilot keeps Apsis as system of record | F-API-033 | - | 7 |
| F-SYS-044 | Side-by-side coexistence | all-app | Different package id so both apps run on one phone during pilot | Build | - | - | - | - | - | - | Same Bluetooth printer must be usable by both | - | - | 7 |
| F-SYS-045 | Price snapshot on memo | API, SR, AMO | Memo lines store `unit_price_minor` and price_type used at capture; bundle carries effective-dated prices | Sale | `memo_line` | `sku_price` | Y | memo | - | - | SF-1: 3-decimal prices → store milli-taka or numeric(14,3); rounding rule for line and memo totals must match the current printed memo (G-feat-46) | - | 8 | 1 |
| F-SYS-046 | Immediate-sync trigger on connectivity regained | all-app | When the device comes online, the pending queue flushes within seconds (R5) without a timer loop | OS connectivity callback | as F-SYS-008 | `L:*` | Y | - | - | - | Use WorkManager network constraint + a one-shot expedited job; cap retries; no foreground service (R4) | F-API-006 | - | 1 |

---

## 2. SR app (F-SR)

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Off | Prt | Pho | Geo | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-001 | Install, login, first bundle | SR | Login → bundle download → Home | App launch | `route_log`, `L:*` | bundle | N (first of day), Y afterwards | - | - | - | Login = bundle (F-SYS-006). Must survive relaunch mid-download (resumable) | F-API-001/005 | 4 | 1 |
| F-SR-002 | Device OTP binding prompt | SR | On an unbound phone, asks for the TSO-issued OTP before sync is allowed | First login on device | `device` | `+device_otp` | N | - | - | - | Capture still allowed before binding? ASSUMPTION: no — binding precedes bundle. Shared phones → G-feat-41 | F-API-003 | 4 | 0 |
| F-SR-003 | Permissions onboarding | SR | Location (while using), camera, Bluetooth, (mic) | First run | - | - | Y | - | - | - | See F-SYS-023 | - | 15 | 1 |
| F-SR-004 | In-app update prompt | SR | "New update available → download & install" | App start | - | `+app_release` | N | - | - | - | F-SYS-020 | F-API-029 | - | 2* |
| F-SR-005 | Settings: language | SR | English / বাংলা | Settings | `L:prefs` | - | Y | - | - | - | F-SYS-019 | - | - | 1 |
| F-SR-006 | Settings: PDA to Support | SR | Upload local data/sync file to support | Settings | Blob | `L:*` | N | - | - | - | F-SYS-021 | F-API-030 | - | 2* |
| F-SR-007 | Settings: Logout | SR | Ends session | Settings | - | - | Y | - | - | - | Warn if pending rows (ASSUMPTION) | F-API-031 | - | 2 |
| F-SR-008 | Home header | SR | `SR - <name> (<code>)`, route name + date | Home | - | `L:route_assignment`, business date | Y | - | - | - | Multi-route SR: which route shows? → G-feat-40 | - | - | 1 |
| F-SR-009 | Home tile grid | SR | Attendance, Stock, Sale, Memo, Summary, Sales Submit (বিক্রয় জমা), Outlet, Tutorial, Task Delegation (badge), Astha, Loyalty Point, Photo Capture | Home | - | `L:task` (badge) | Y | - | - | - | Same tile order/labels as today (no retraining) | - | - | 1 |
| F-SR-010 | Home KPI strip | SR | Target & Achievement (STD progress %), Outlets visited x/y, Strike rate %, Issue qty, Current stock, Non-visit, No-sale — from local data, reconciled on sync | Home | - | `L:target`, `L:visit`, `L:memo`, `L:stock_issue`; `/app/home` on sync | Y | - | - | - | Non-visit/No-sale definitions unstated → G-feat-38. Current stock = issued − sold (− returned). Guard % (F-SYS-034) | F-API-018 | 8 | 1 |
| F-SR-011 | Attendance check-in | SR | Records GPS + time | Attendance tile | `attendance` | - | Y | - | - | fix | One per user per business date (UNIQUE). Must check-in before Stock/Sale? ASSUMPTION: yes, as current flow order. Mock flag stored on fix | F-API-006 | - | 1 |
| F-SR-012 | Attendance check-out | SR | Enabled only from 17:00 Dhaka | Attendance tile | `attendance.check_out_at` | - | Y | - | - | fix | `cfg.day.checkout_earliest_time=17:00`; device clock vs server time (G-feat-18) | F-API-006 | - | 2 |
| F-SR-013 | Bluetooth printer pairing + state | SR | Pair/connect RPP02N-class 58 mm ESC/POS; show connected/disconnected | Stock screen (and print actions) | `L:prefs` (last printer MAC) | - | Y | - | - | - | Reconnect automatically to last printer; print failure path → G-feat-43 | - | - | 1 |
| F-SR-014 | Stock load (issue by SKU) | SR | Enter issued qty per SKU from distributor | Stock tile | `stock_issue.issued_qty` | `L:sku`, `L:sales_plan` | Y | - | - | - | Only sales-plan SKUs; multiple loads/day (top-up) → sum or new row? (G-feat-02). Unit of entry Q8 | F-API-006 | 8 | 2 |
| F-SR-015 | Print stock memo | SR | Prints issued stock by SKU | Stock screen | `L:print_log` | `stock_issue` | Y | stock | - | - | Layout must match current (docs/01 "same memo") | - | 5 | 2 |
| F-SR-016 | Route outlet list with alphabet filter | SR | Lists today's route outlets labelled `name (code-phone-cluster)`; A–Z filter; visited/pending state | Sale tile | - | `L:outlet`, `L:visit` | Y | - | - | - | Bangla vs Latin alphabet filter (names may be Bangla) → confirm; sort order; outlets with `status != active` hidden | - | - | 1 |
| F-SR-017 | Open visit + geo check | SR | Single fused fix; Haversine vs outlet coords vs territory radius; in range → call starts | Select outlet | `visit` (client_uuid, lat, lng, accuracy, mock, geo_validated_device, route_id per SF-3) | `L:outlet`, radius from bundle | Y | - | - | Y | Radius per territory (`cfg.geo.radius_m` with zone/outlet override per SF-6); mock → never valid; no saved outlet location → Q7 / G-feat-39; fix timeout `cfg.geo.fix_timeout_s`; repeat visit same outlet same day → G-feat-08 | F-API-006 | 7 | 1 |
| F-SR-018 | Force Sale | SR | Out of range/no fix → pick reason (`internet_problem`, `location_change`) + outlet photo → proceed; `photo_validated=true`, `geo_validated=false` | Geo check fails | `visit.force_reason`, `outlet_photo`, `outlet_change_request(type=info/location)` | - | Y | - | Y | Y | Photo updates outlet location via verification flow, never silently. `cfg.sale.force_reasons` (list, R6). Reason list is only 2 today; keep as config | F-API-006/007 | 7 | 2 |
| F-SR-019 | Refresh GPS fix | SR | Retry a single fix | Geo-fail dialog | - | - | Y | - | - | Y | Max retries / cool-down `cfg.geo.refresh_max` to protect battery | - | - | 1 |
| F-SR-020 | AV/KV marketing content during call | SR | Shows audio-visual / key-visual content for the outlet/campaign | During call | `activity_log` (viewed) | `L:cache` content, `+campaign_content` | Y (cached) | - | - | - | Content management absent from spec → G-feat-12; cache bounded (F-SYS-029) | F-API-005 | - | 2 |
| F-SR-021 | POSM survey + photo | SR | Answers survey questions, attaches photo | During call | `survey_response` (+client_uuid), media queue | `+survey_question` | Y | - | Y | - | Question set absent → G-feat-12; one survey per visit? | F-API-006/007 | - | 2 |
| F-SR-022 | DRP empty-pack/slide collection | SR | Records empty packs/slides collected; offer granted | During call | `drp_collection` (+client_uuid) | `offer` | Y | - | - | - | DRP → discount rule absent → G-feat-13 | F-API-006 | 13 | 2 |
| F-SR-023 | Sale: SKU quantity entry | SR | Enter quantities per sellable SKU; suggested qty shown | Sale → outlet | `L:memo_draft` | `L:sku`, `L:sales_plan`, `L:stock`, suggestion | Y | - | - | Y (only after visit opened) | Unit (pack vs stick) Q8/SF-8; can qty exceed current stock? → G-feat-44 | - | 6, 8 | 1 |
| F-SR-024 | Offers auto-apply | SR | Active promotions apply to lines/memo | Qty entry | `memo_line.offer_id`, `memo.discount_minor` | `offer`, `promotion` | Y | - | - | - | Rule engine undefined (~22 groups) → **G-feat-13 (blocker for memo parity)** | - | 13 | 2 |
| F-SR-025 | Review (নিরীক্ষণ) | SR | Shows lines, gross, discount, net before commit | Sale | - | `L:memo_draft` | Y | - | - | - | Totals/rounding must equal printed memo (G-feat-46) | - | 8 | 1 |
| F-SR-026 | Credit (বাকি) with partial payment | SR | Mark memo credit; enter paid amount; due = net − paid | Review | `memo.is_credit`, `paid_minor`, `due_minor` | outlet open dues | Y | - | - | - | Credit limits / eligibility unspecified → G-feat-14 (`cfg.credit.max_due_minor`, `cfg.credit.max_days`) | - | - | 2 |
| F-SR-027 | Product QC (fault quantities) | SR | Per-SKU production-fault and transport-fault quantities | After credit step | `qc_entry` (+client_uuid) | - | Y | - | - | - | QC locks memo against edit (docs/06). What QC does to stock/replacement → G-feat-05 | F-API-006 | - | 2 |
| F-SR-028 | Print memo | SR | Prints memo on 58 mm printer; stamps `printed_at` | After QC | `memo.printed_at`, `L:print_log` | memo | Y | memo | - | - | Same layout + totals as Apsis (docs/01, 11); memo number (F-SYS-027); printer failure → G-feat-43 | - | 5 | 1 |
| F-SR-029 | Zero sale | SR | No SKU → confirm "do zero sale?" → review → print → done | Sale with no qty | `visit.is_zero_sale`, `memo` (zero) ? | - | Y | memo (zero) | - | Y | Does a zero sale create a memo row and print? docs/06 says print → ASSUMPTION: zero-memo printed, excluded from Memo count (G-feat-38) | - | - | 2 |
| F-SR-030 | Memo menu (outlet → memos) | SR | Lists today's (and recent) memos per outlet with Print, Edit, Mark paid | Memo tile | - | `L:memo`, `L:due_collection` | Y | - | - | - | History depth `cfg.app.local_history_days` | - | - | 2 |
| F-SR-031 | Memo reprint | SR | Re-prints an existing memo | Memo menu → Print | `L:print_log` (+count) | memo | Y | memo | - | - | Reprint marking/limit unspecified → G-feat-04 | - | 5 | 2 |
| F-SR-032 | Due collection (বাকি পরিশোধ) | SR | Mark paid (full or partial) against prior credit memo; confirm; recompute outlet due | Memo menu → outlet → Mark paid | `due_collection` | `L:memo` open dues, imported balances | Y | - | - | - | Against which memo (FIFO vs chosen) → G-feat-45; receipt print? ASSUMPTION none | F-API-006 | - | 2 |
| F-SR-033 | Sale edit | SR | Inside outlet geofence AND memo not yet QC'd; pick 1 of 3 reasons; re-enter → new memo row `supersedes` old | Memo menu → Edit | `memo` (new), `memo_line`, `qc_entry` | `L:memo` | Y | memo | - | Y | Reasons list unnamed → G-feat-15 (`cfg.memo.edit_reasons`); server re-checks geofence; old memo marked superseded in aggregates; reprint of edited memo (G-feat-16) | F-API-006 | 7 | 2 |
| F-SR-034 | Sync | SR | Flush pending rows; show device-vs-server counts (outlet, sale, stock, QC, promotion) | Home/Sales Submit | as F-SYS-008 | `L:*` | Y (queue) | - | - | - | F-SYS-008/009 | F-API-006 | - | 1 |
| F-SR-035 | Sales Submit (বিক্রয় জমা) | SR | Closes the SR's day → `sales_submitted`; warns if any retailer still has dues | Sales Submit tile | `route_log`/day state | `L:memo.due_minor` | P (request queued if offline → G-feat-65) | - | - | - | Warning only or block? docs: "warns". Requires sync complete? Must not double-count on retry | F-API-008 | 11 | 2 |
| F-SR-036 | Summary + summary print | SR | Per-SKU memo count, qty, value, discount, discounted value, return qty (issue − sold); per-category totals (Cigarette, Bidi, Lighter, Match); grand total; Print | Summary tile | `L:print_log` | `L:memo_line`, `L:stock_issue` | Y | summary | - | - | Serves as end-of-day return statement? → G-feat-02 | - | 8 | 2 |
| F-SR-037 | Outlet request: new shop | SR | Route, shop name, owner, mobile → Capture GEO & photo → Save → pending | Outlet tile | `outlet_change_request(type=new)`, `outlet_photo` | `L:route` | Y | - | Y | fix | Sell to the new outlet the same day before approval? → G-feat-42; duplicate-outlet check (same phone/name on route) ASSUMPTION none today | F-API-006/007 | - | 2 |
| F-SR-038 | Outlet request: permanently closed | SR | Route, outlet → Save → confirm | Outlet tile | `outlet_change_request(type=close)` | `L:outlet` | Y | - | - | - | Outlet hidden from list only after approval (ASSUMPTION) | F-API-006 | - | 2 |
| F-SR-039 | Outlet request: info change | SR | Edit name/owner/mobile; must re-capture GEO + photo | Outlet tile | `outlet_change_request(type=info)`, `outlet_photo` | `L:outlet` | Y | - | Y | fix | Route/cluster change not covered → G-feat-61 | F-API-006/007 | - | 2 |
| F-SR-040 | Own-request pending state | SR | Shows pending / verified / approved / rejected for own requests | Outlet tile | - | bundle `outletRequests` | Y | - | - | - | Rejection reason shown? ASSUMPTION yes | F-API-005 | - | 2 |
| F-SR-041 | Astha: Route info | SR | Filters Year / Quarter / months; STD target per brand (Target, Achv, Remaining, %); memo target "All Brand" | Astha tile | - | `+astha_target`, facts | Y (bundle snapshot) | - | - | - | Target ≥ 0 and %-guard (F-SYS-034); brands list from product tree | F-API-005/021 | 13 | 5 |
| F-SR-042 | Astha: Shop info | SR | Astha retailer list (name, owner, phone, sub-channel tier, wing/division/territory/zone) | Astha tile | - | `L:outlet` where channel=Astha | Y | - | - | - | Phone is PII — SR is allowed (needs it to call) | - | 13 | 5 |
| F-SR-043 | Diamond League redemption | SR | Pick outlet → points remaining → catalog (cash 2 Tk/pt ≤199; rack 200; chair 200; fan 400) → +/- → Confirm → points deducted | Loyalty Point tile | `redemption`, `loyalty_ledger` (−points, +client_uuid) | `+loyalty_gift` catalog, balance from bundle | Y | - | - | - | Offline double-spend guard: local balance decremented; server rejects if balance < spent → shown as rejected (G-feat-20). Catalog + rates as config: `cfg.loyalty.cash_per_point`, `cfg.loyalty.cash_max_points`, gift catalog table | F-API-006 | 13 | 5 |
| F-SR-044 | Photo Capture → Astha gift photo | SR | Shows gift chosen for the outlet (e.g. "27 pcs Dinner Set"); one hand-over photo per outlet ("photo already captured") | Photo Capture tile | `gift_photo` (+client_uuid) | bundle `giftAssignments` | Y | - | Y | fix | One per outlet per program period; re-take rules unspecified | F-API-006/007 | 13 | 5 |
| F-SR-045 | Photo Capture → Campaign gift verify | SR | Per redeemed gift, a verification photo (e.g. "Diamond League (March) Gift Verify") | Photo Capture tile | `gift_photo`/`redemption.photo_url` | `redemption` | Y | - | Y | fix | Links to redemption `client_uuid` | F-API-006/007 | 13 | 5 |
| F-SR-046 | Task list + badge | SR | Assigned tasks (type OOS/General/Irregular Visit, outlet, text, due date) | Task Delegation tile | - | bundle `tasks` | Y | - | - | - | Badge count = pending tasks | F-API-005 | - | 5 |
| F-SR-047 | Task resolve (swipe) | SR | Swipe → Resolve → Completed | Task list | `task.status`, `resolved_at` (+client_uuid per SF-2) | - | Y | - | - | - | Resolution evidence? (photo/comment) unspecified → G-feat-19 | F-API-006 | - | 5 |
| F-SR-048 | Tutorial video list | SR | List of videos from backend (may be empty) | Tutorial tile | - | `+tutorial_video` | P (list cached; playback online) | - | - | - | Playback must not autoplay/download in background (R4) | F-API-027 | - | 2 |
| F-SR-049 | Outlet detail card | SR | Name, owner, phone, cluster, channel, open dues, loyalty points, last visit | From outlet list | - | `L:outlet`, dues, loyalty | Y | - | - | - | Implicit in today's list label + due flows | - | - | 1 |
| F-SR-050 | Current stock tracker | SR | Running stock per SKU = issued − sold (− returned) | Every sale | `L:stock_view` | `stock_issue`, `memo_line` | Y | - | - | - | Feeds KPI strip, summary return qty, sale validation (G-feat-44) | - | 8 | 2 |

---

## 3. AMO app (F-AMO)

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Off | Prt | Pho | Geo | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-001 | Home header + tiles | AMO | `amo5001 (amo5001)` + `<Zone>AMO, <date>`; tiles: Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation (badge), Live Dashboard, SR Stock, Outlet (badge), Stock, Sale, Memo, Summary, Sales Submit, Astha, Report, Settings | Login | - | bundle (zone scope) | Y | - | - | - | Badges: Task Delegation = pending SR requests; Outlet = pending verifications | F-API-005 | - | 3 |
| F-AMO-002 | Home KPI tiles (MTD) | AMO | Today's target (done/total), Total call target, Control-call target, Joint-call target | Home | - | `/app/home`, `+supervisor_target` | P | - | - | - | Where control/joint-call targets are set → G-feat-57 | F-API-018 | - | 3 |
| F-AMO-003 | Attendance (press-and-hold) | AMO | Reverse-geocoded address + refresh; hold green = check-in; red check-out from 17:00; then disabled ("Already Checked-In/Out") | Attendance tile | `attendance` | - | Y (geocode P) | - | - | fix | Reverse geocoding needs network → show coords offline; `cfg.day.checkout_earliest_time` | F-API-006 | - | 3 |
| F-AMO-004 | Control Call: route + outlet (alphabet filter) + geo gate | AMO | Pick route+outlet; outside radius → "AMO not within retailer range" → Manual Override or Refresh | Control Call tile | `visit` (kind=control_call) | `L:outlet`, radius | Y | - | - | Y | Same radius as SR; `visit.+kind` needed (control/joint/sr) | F-API-006 | 7 | 3 |
| F-AMO-005 | Manual Override (location update photo) | AMO | Outlet photo updates location (through change flow) | Geo fail | `outlet_photo`, `outlet_change_request(type=info/location)` | - | Y | - | Y | Y | Equivalent of SR Force Sale; AMO's own photo may be auto-verified? ASSUMPTION no — still web approval | F-API-006/007 | 7 | 3 |
| F-AMO-006 | Control Call → Sale | AMO | Same as SR sale | Outlet menu | as F-SR-023..028 | - | Y | memo | - | Y | AMO sells against own stock (F-AMO-029) | F-API-006 | 8 | 3 |
| F-AMO-007 | SR Perf. Assessment: distribution performance | AMO | Tick brands present/OOS (Maxim, Avon, ARIS, Marise, Black Diamond, Sunmoon, Supreme, Special Abul Bidi, Existing Abul Bidi/42 No., Ananda Bidi, Abul Bidi Gold, Abul Bidi Style, Aster, Flame Box, Salmon) | Outlet menu | `distribution_check` (one row per brand) | `product_brand` | Y | - | - | Y | Brand list = product tree, not hardcoded | F-API-006 | 14 | 3 |
| F-AMO-008 | SR Perf. Assessment: OOS performance | AMO | Tick brands OOS | Same screen | `distribution_check.oos` | `product_brand` | Y | - | - | Y | - | F-API-006 | - | 3 |
| F-AMO-009 | SR Perf. Assessment: POSM | AMO | Sticker/banner present Yes/No; Save | Same screen | `distribution_check.posm` | - | Y | - | - | Y | - | F-API-006 | - | 3 |
| F-AMO-010 | Control Call → Survey | AMO | Same POSM survey as SR | Outlet menu | `survey_response` | `+survey_question` | Y | - | Y | Y | G-feat-12 | F-API-006/007 | - | 3 |
| F-AMO-011 | Joint Call assessment | AMO | Route+outlet, geo gate; 1–5 star rubric: 5-step sales call (summarise situation [OHS count, OOS, quality check, opportunity & issue]; describe idea [SOQ, present offer, campaign comms]; explain how it works [offer modality, retailer benefit]; steps 4–5 …), relationship (greeting, knows name, cordiality), service quality (timely visit, correct memo & records, resolving complaints) → Save | Joint Call tile | `call_assessment(kind=joint_call)`, `visit(kind=joint)` | `+assessment_rubric` | Y | - | - | Y | Steps 4–5 unspecified → G-feat-56; rubric as config table | F-API-006 | - | 3 |
| F-AMO-012 | AMO Sale (standalone) | AMO | SKU qty → review → credit + partial → QC → print | Sale tile | as SR | - | Y | memo | - | Y | As F-SR-023..028 | F-API-006 | 8 | 3 |
| F-AMO-013 | View previous sale data (date picker) | AMO | Pick a past date → memos/lines for that day | Sale screen | - | `L:memo` or `GET /memos` | P (local history days; else online) | - | - | - | `cfg.app.local_history_days`; online fallback scoped | F-API-025 | - | 3 |
| F-AMO-014 | Memo menu: Print / Edit / Mark paid | AMO | As SR F-SR-030..033 | Memo tile | `memo`, `due_collection` | `L:memo` | Y | memo | - | Y (edit) | Same rules as SR | F-API-006 | 7 | 3 |
| F-AMO-015 | Summary + print | AMO | Per-SKU memo count, qty, value, discount, discounted value, return qty; per-category totals; grand total; Print | Summary tile | `L:print_log` | `L:memo_line`, `L:stock_issue` | Y | summary | - | - | As F-SR-036 | - | 8 | 3 |
| F-AMO-016 | Team Location map | AMO | SR list → last synced fixes on a map | Team Location tile | - | `+user_last_fix` (from attendance/visit fixes) | N | - | - | - | Not continuous tracking (R4); "live" = last synced fix + age label (G-feat-36) | F-API-023 | - | 3 |
| F-AMO-017 | Team Performance | AMO | Tabs Monthly Target / Till Date Target; zone card + route cards: 4 category bars (Cigarette, Bidi, Lighter, Match; achieved/target, %; green/amber/red); Details → brand table | Team Performance tile | - | rollups, `target` | P (cached) | - | - | - | Till-date target needs working-day calendar → G-feat-09; bands `cfg.kpi.bands` | F-API-018/021 | 8, 12 | 3 |
| F-AMO-018 | Task Delegation: assigned list | AMO | Outlet, text, status, completion date | Task Delegation tile | - | `task` | P | - | - | - | - | F-API-026 | - | 5 |
| F-AMO-019 | Task Delegation: assign (+) | AMO | Route/section, outlet, task type, due date, description → Save | Task list | `task` (+client_uuid) | `L:outlet`, `route_assignment` (assignee = route's SR) | Y (queued) | - | - | - | Assignee derivation: outlet's route's SR on due date (ASSUMPTION) | F-API-006/026 | - | 5 |
| F-AMO-020 | Live Dashboard (zone) | AMO | Zone + route filters; Sales (memos, taka); Live strike rate; Geo-fencing status (target outlets, geo-validated, photo-validated, total visited, geo %); Login & submit status | Live Dashboard tile | - | `fact_daily_route`, rollups | N (P with last cache) | - | - | - | Submit % definition SF-7; "live" = as of last aggregation | F-API-016 | 9 | 3 |
| F-AMO-021 | SR Stock | AMO | SR list (code, route, phone, total outlets) → lifted stock by SKU + total | SR Stock tile | - | `stock_issue` (today) | N/P | - | - | - | Shows only synced loads | F-API-024 | - | 3 |
| F-AMO-022 | Verify: new outlet | AMO | Pending list (badge); set Sub-Channel + Geo Classification; optional re-capture GEO + photo; Cancel (reject) / Save (approve → `verified`) | Outlet tile | `outlet_change_request.status/verified_by`, `outlet_photo` | pending requests in bundle | Y (queued) | - | opt | fix | Sub-channel list from `sub_channel`; then web approval (F-WEB-032) | F-API-006 | - | 3 |
| F-AMO-023 | Verify: outlet closure | AMO | Approve/reject close requests | Outlet tile | `outlet_change_request` | - | Y | - | - | - | - | F-API-006 | - | 3 |
| F-AMO-024 | Verify: info change | AMO | Approve/reject info-change requests | Outlet tile | `outlet_change_request` | - | Y | - | opt | - | - | F-API-006 | - | 3 |
| F-AMO-025 | AMO own: New shop | AMO | As F-SR-037 | Outlet tile | `outlet_change_request`, `outlet_photo` | - | Y | - | Y | fix | Does AMO's own request skip AMO verification? ASSUMPTION yes → straight to web | F-API-006/007 | - | 3 |
| F-AMO-026 | AMO own: Permanent close | AMO | As F-SR-038 | Outlet tile | `outlet_change_request` | - | Y | - | - | - | - | F-API-006 | - | 3 |
| F-AMO-027 | AMO own: Info change | AMO | As F-SR-039 | Outlet tile | `outlet_change_request`, `outlet_photo` | - | Y | - | Y | fix | - | F-API-006/007 | - | 3 |
| F-AMO-028 | Update Base (location recalibration) | AMO | Route + outlet → confirm → outlet photo → pick exact point on map → Confirm | Outlet tile | `outlet_change_request(type=+location)`, `outlet_photo` | map tiles (cached?) | P (map needs tiles) | - | Y | fix | New request type `location` needed (enum has new/close/info); who approves | F-API-006/007 | 7 | 3 |
| F-AMO-029 | AMO Stock load + stock memo | AMO | As F-SR-014/015 | Stock tile | `stock_issue` | - | Y | stock | - | - | - | F-API-006 | 8 | 3 |
| F-AMO-030 | AMO Sales Submit (extended reconciliation) | AMO | Counts: Outlet, Sale, Stock, QC, Promotion, Distribution & OOS performance, Price compliance, Joint call, Survey → Sync → Sales Submit (dues warning) | Sales Submit tile | day state | `L:*` | P | - | - | - | **Price compliance** has no capture screen anywhere → G-feat-01 | F-API-006/008 | 11 | 3 |
| F-AMO-031 | Astha with route selector | AMO | As SR Astha for each route in zone | Astha tile | - | `+astha_target`, facts | P | - | - | - | - | F-API-021 | 13 | 5 |
| F-AMO-032 | Report: STD Memo Report | AMO | Date range; per-route STD by category | Report tile | - | `fact_daily_route_sku` | N | - | - | - | Guard % | F-API-017 | 8 | 3 |
| F-AMO-033 | Report: Sales summary till now | AMO | Route list with CPR + total memos → route detail: CPR, total memo, brand table Target (fractional) / Sales / % / Memo | Report tile | - | rollups, `target` | N | - | - | - | Fractional targets (Q12) | F-API-017 | 12 | 3 |
| F-AMO-034 | Settings | AMO | Language, PDA to Support, Logout | Settings tile | `L:prefs` | - | Y | - | - | - | As SR | - | 15 | 3 |
| F-AMO-035 | Price compliance check | AMO | (Implied by reconciliation list) record whether outlet sells at the printed/outlet price | Control call (ASSUMPTION) | `+price_compliance` (visit_id, sku_id, observed_price, compliant) | `sku_price` | Y | - | - | Y | **No screen, fields or rules in spec** → G-feat-01 | F-API-006 | - | 3 |
| F-AMO-036 | Visit kind tagging | AMO | Every AMO outlet open is tagged control/joint so CPR for SRs excludes AMO calls | Any AMO visit | `visit.+kind`, `visit.+subject_sr_id` | - | Y | - | - | - | Needed so AMO sales don't inflate route CPR (ASSUMPTION on today's behaviour) | - | - | 3 |

---

## 4. TSO app (F-TSO)

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Off | Prt | Pho | Geo | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-001 | Login (dark theme) + logout wipe | TSO | Username/password; logout removes all local data with warning | Launch / drawer | `L:*` wipe | - | N/Y | - | - | - | Refuse or warn when pending uploads exist (G-feat-41) | F-API-001/031 | - | 3 |
| F-TSO-002 | Dashboard: sales by category + achievement % | TSO | Cigarette (sticks), Bidi, Lighter (pieces/box), Match (dozen) for territory, today | Dashboard | - | territory rollups, `target` | P (cache) | - | - | - | Units Q8 | F-API-016/018 | 8 | 3 |
| F-TSO-003 | Dashboard: By Channel STD pie | TSO | Channel mix of STD (GT/DCC/Astha/RCC/MT/HoReCa) | Dashboard | - | rollups by channel (needs `fact_daily_outlet` × channel) | P | - | - | - | - | F-API-016 | - | 3 |
| F-TSO-004 | Dashboard: CPR card | TSO | Target outlets, successful calls, strike rate % | Dashboard | - | `fact_daily_route` rollup | P | - | - | - | CPR def docs/10 | F-API-016 | - | 3 |
| F-TSO-005 | Dashboard: By Segment Value Contribution | TSO | Value vs volume by segment | Dashboard | - | `fact_daily_route_sku` × price | P | - | - | - | Which price type for "value" (reporting?) → confirm | F-API-016 | 8 | 3 |
| F-TSO-006 | Dashboard: By Brand Call/Memo Ratio | TSO | BSR per brand | Dashboard | - | facts | P | - | - | - | Denominator Q9 | F-API-016 | 9 | 3 |
| F-TSO-007 | Dashboard: Login & Sales-submit status + drill-downs | TSO | Login % = logins / target routes; submit % = submitted / logins; "Not Logged In" / "Not Uploaded" lists (SR/AMO name, username, route, dep name) | Dashboard | - | `fact_daily_route.login_state`, `route_assignment`, `app_user` | P | - | - | - | SF-7, SF-9; holiday/visit-day denominators G-feat-09/10 | F-API-016 | - | 3 |
| F-TSO-008 | Leave: list | TSO | Applications (date range, days, reason, status e.g. "DMO approval pending") | Drawer → Leave | - | `+leave_application` | P | - | - | - | - | F-API-022 | 16 | 5 |
| F-TSO-009 | Leave: apply | TSO | Type (Casual, Sick, Earn), date(s), number of days, reason | Leave screen | `+leave_application` | `+leave_balance`? | P (queued) | - | - | - | Balances/limits unspecified; `cfg.leave.types`. Approval by DMO → F-WEB-046 / G-feat-25 | F-API-022 | 16 | 5 |
| F-TSO-010 | Final Submit (per zone per day) | TSO | Wing → Division → Territory → House → Zone → Get Sales Data → date + routes with FF name or "Not Set" → Submit; second attempt refused ("already given FINAL SUBMIT for today") | Drawer → Final Submit | `final_submit` | `fact_daily_route`, `route_assignment` | N | - | - | - | Once/zone/day (PK). Routes not yet submitted → G-feat-64; reopen Q11 / G-feat-17; scope-bounded pickers | F-API-009 | 11 | 3 |
| F-TSO-011 | My Periphery: My Team map | TSO | Zone → SR last fixes on map | Drawer | - | `+user_last_fix` | N | - | - | - | As F-AMO-016 | F-API-023 | - | 3 |
| F-TSO-012 | My Periphery: Retailer radius map | TSO | Zone + radius (50/100/300 m) → outlets around TSO's current location | Drawer | - | `outlet` (geo query) | N (P with bundle outlets) | - | - | fix | `cfg.periphery.radius_options_m=[50,100,300]`; single fix | F-API-019 | - | 3 |
| F-TSO-013 | My Call: Set Plan | TSO | Date, zone, route → Show Outlet → multi-select (name, code, cluster, owner, phone) → Set Plan | Drawer | `+visit_plan`, `+visit_plan_outlet` | `outlet` | P (queued) | - | - | - | Plan for past date? ASSUMPTION no | F-API-020 | - | 3 |
| F-TSO-014 | My Call: My Visit Plan | TSO | Date, zone, route → Pending / Completed tabs (code e.g. DHK-344-011, owner, contact, channel, cluster) | Drawer | - | `+visit_plan_outlet` | P | - | - | - | Completed when questionnaire submitted (ASSUMPTION) → G-feat-49 | F-API-020 | - | 3 |
| F-TSO-015 | Visit Query (retailer questionnaire) | TSO | "Does the SR visit regularly?", "Does the SR print memos regularly?" + "Delegate task?" Y/N → Submit | Visit plan outlet | `call_assessment(kind=retailer_questionnaire)`, `visit_plan_outlet.status` | - | Y (queued) | - | - | fix? (geo gate unspecified → G-feat-49) | Question set as config | F-API-006 | - | 3 |
| F-TSO-016 | Assign Task from visit | TSO | Type (e.g. Irregular Visit), date, comment → Assign | Questionnaire "Delegate task = Yes" | `task` (+client_uuid) | `route_assignment` | Y (queued) | - | - | - | Assignee = route SR | F-API-006/026 | - | 5 |
| F-TSO-017 | Target Status | TSO | Monthly / Till Date; territory card + zone cards (Cigarette/Bidi/Lighter/Match bars); Details → item table at SKU/variant level (Target, Achv, Remaining, %) | Drawer | - | rollups, `target` | P | - | - | - | Till-date → G-feat-09; product scope Q14 | F-API-021 | 8, 12, 14 | 3 |
| F-TSO-018 | My Feedback | TSO | Category (e.g. Suggestion), Title, Description, image from gallery → Save | Drawer | `+feedback` (+client_uuid), media queue | - | P (queued) | - | Y (gallery) | - | Who reads it → G-feat-48; gallery image compressed too | F-API-032 | - | 3 |
| F-TSO-019 | Device OTP issuance (as actor) | TSO | Issues one-time code to bind an SR/AMO phone | Web "SR Device OTP" page (no TSO-app screen in spec) | `+device_otp` | `app_user` in scope | N | - | - | - | Launch-day volume (8,500 bindings) and whether TSO must be at a PC → **G-feat-11** | F-API-003/036 | 4 | 0 |
| F-TSO-020 | Astha gift choice per outlet ("TSO portal") | TSO | Chooses the gift each Astha outlet receives (feeds F-SR-044) | Unknown surface (Q13) | `+astha_gift_choice` | `outlet` (Astha), `+gift_catalog` | N | - | - | - | → G-feat-21 | F-API-021 | 13 | 5 |

---

## 5. Web dashboards, reports and portals (F-WEB)

Standard behaviours on every data page: scope filter Wing → Division → Territory → House → Zone defaulted/bounded to the token scope (F-WEB-041); render on screen first, Excel export as a formatting step (F-WEB-040); PII gating (F-WEB-042). Offline = N for all web rows (omitted).

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Prt | Pho | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-WEB-001 | Dashboard (home) | TSO, DMO, WM, top, admin | Sales vs target by category, live CPR, calls by channel, geo-validation %, login/submit %, final-submit status, sales by brand/channel, SR positions map; loads from rollups without a button | Menu: Dashboard | - | rollups, `fact_daily_route`, `+user_last_fix` | - | - | < 1 s national load (docs/01); no client aggregation | F-API-014 | 9, 10 | 4 |
| F-WEB-002 | Browse Retailer (list) | TSO+, admin | Outlet list with filters, search by code/name/phone | Menu: Retailer | - | `outlet` | - | - | PII columns gated (NID/TIN/phone) | F-API-019 | 16 | 4 |
| F-WEB-003 | Retailer detail + edit | TSO+, admin | Sections: basic info, address, business, additional detail; edit | Retailer row | `outlet` (+audit) | `outlet`, `outlet_photo`, visit history | - | view | Field lists per section unknown → G-feat-54; edit bypasses AMO verification? → confirm | F-API-019/035 | 16 | 4 |
| F-WEB-004 | Products: Category | all web | Reference view | Menu: Products | - | `product_category` | - | - | - | F-API-035 | - | 4 |
| F-WEB-005 | Products: Segment | all web | Reference view | Menu | - | `product_segment` | - | - | - | F-API-035 | - | 4 |
| F-WEB-006 | Products: Brand | all web | Reference view | Menu | - | `product_brand` | - | - | - | F-API-035 | - | 4 |
| F-WEB-007 | Products: Variant | all web | Reference view | Menu | - | `product_variant` | - | - | - | F-API-035 | - | 4 |
| F-WEB-008 | Products: SKU | all web | SKU list with 5 price types, pack size/type, unit, sales_enable | Menu | - | `sku`, `sku_price` | - | - | Price precision SF-1 | F-API-035 | 8 | 4 |
| F-WEB-009 | Products: Product Tree | all web | Category → Segment → Brand → Variant → SKU tree | Menu | - | product tables | - | - | - | F-API-035 | - | 4 |
| F-WEB-010 | Route Planning: Browse Routes | TSO+, admin | Route → SR/SS by zone, visit days | Menu: Route Planning | - | `route`, `route_assignment` | - | - | SS (substitute) rows need role/valid dates → G-feat-07 | F-API-020b | - | 4 |
| F-WEB-011 | Report: Task Planner | TSO+ | Tasks assigned/resolved by scope/date | Menu: Reports | - | `task` | - | - | Excel | F-API-017 | - | 4 |
| F-WEB-012 | Report: By-Route Geo Capture | TSO+ | Outlets per route with/without coordinates; captured today | Reports | - | `outlet`, `outlet_photo` | - | view | Excel | F-API-017 | 7 | 4 |
| F-WEB-013 | Report: STD Memo Report | TSO+ | Date range; STD + memo by route × category/brand | Reports | - | `fact_daily_route_sku` | - | - | Excel; units Q8 | F-API-017 | 8 | 4 |
| F-WEB-014 | Report: SR Efficiency | TSO+ | Per SR: target outlets, visited, successful, CPR, STD, memos, geo % | Reports | - | `fact_daily_route`, `fact_daily_outlet` | - | - | Excel; exact column set unknown → G-feat-53 | F-API-017 | - | 4 |
| F-WEB-015 | Report: Route-wise STD | TSO+ | STD by route × product level × date range | Reports | - | `fact_daily_route_sku` | - | - | Excel | F-API-017 | 8 | 4 |
| F-WEB-016 | Report: Data Entry Log | TSO+, admin | Download/upload first/last time + counts per route/day | Reports | - | `route_log` | - | - | Excel | F-API-017 | - | 4 |
| F-WEB-017 | Report: Final Submit Log | TSO+, admin | Who final-submitted which zone when; missing zones | Reports | - | `final_submit` | - | - | Excel | F-API-017 | 11 | 4 |
| F-WEB-018 | Report: CPR & BSR | TSO+ | CPR and brand-strike-rate by scope/date | Reports | - | facts | - | - | BSR denominator Q9; Excel | F-API-017 | 9 | 4 |
| F-WEB-019 | Report: By Outlet Report | TSO+ | Per outlet: visits, memos, STD, dues over a range | Reports | - | `fact_daily_outlet`, `memo` | - | - | PII gate; Excel | F-API-017 | 16 | 4 |
| F-WEB-020 | Report: Astha Report | TSO+ | Astha outlets: per-brand target/achv/remaining/% + memo target by quarter | Reports | - | `+astha_target`, facts | - | - | Target ≥ 0 guard; Excel | F-API-017 | 13 | 5 |
| F-WEB-021 | Report: GIGO (attendance) | TSO+ | Check-in/out times and locations per user/day | Reports | - | `attendance` | - | - | Excel | F-API-017 | - | 4 |
| F-WEB-022 | Report: Campaign Gift Redemption | TSO+ | Diamond League redemptions, points, photo-verified flag | Reports | - | `redemption`, `gift_photo`, `loyalty_ledger` | - | view | Excel | F-API-017 | 13 | 5 |
| F-WEB-023 | Report: Discount Report | TSO+ | Discounts by promotion group (~22) × scope × date | Reports | - | `memo_line.offer_id`, `+promotion` | - | - | Offer definitions → G-feat-13; Excel | F-API-017 | 13 | 5 |
| F-WEB-024 | Report: By Outlet By Day | TSO+ | Outlet × day matrix (visited/sold/STD) | Reports | - | `fact_daily_outlet` | - | - | Excel | F-API-017 | - | 4 |
| F-WEB-025 | Report: Online/Offline Sales | TSO+ | Memos captured with vs without connectivity (or synced same-day vs late) | Reports | - | `memo` + `sync_batch` timing, `+visit.captured_online` | - | - | Definition of "online" unknown → G-feat-58; Excel | F-API-017 | - | 4 |
| F-WEB-026 | Report: Free Sample | TSO+ | Free samples by SKU by route | Reports | - | `+free_sample` | - | - | No capture screen in SR app → G-feat-23; Excel | F-API-017 | 13 | 5 |
| F-WEB-027 | Report: TSO Top Sheet Performance | DMO+ | TSO-level summary: STD vs target by category, CPR, login/submit, geo % | Reports | - | territory rollups | - | - | Excel | F-API-017 | - | 4 |
| F-WEB-028 | Report: TSO Daily Tracking Dashboard | DMO+ | Routes bucketed 100 / 90–100 / 80–90 / <80 % of sales & memo targets | Reports (same engine as F-WEB-038) | - | `fact_daily_route`, `target` | - | - | `cfg.kpi.bands`; Excel | F-API-015 | - | 4 |
| F-WEB-029 | Target: Target Allocation Report | TSO+ | Monthly targets by route/zone × product level, with split | Menu: Target | - | `target` | - | - | Split formula Q12; Excel | F-API-021 | 12 | 5 |
| F-WEB-030 | Target: Target Revise List (approval queue) | TSO, DMO, WM, admin | Pending revisions with config, date range, status, next approver level; approve/reject | Menu: Target | `target_revision`, `target` | `target_revision` | - | - | Multi-level approvals Q12; target ≥ 0 | F-API-021 | 12 | 5 |
| F-WEB-031 | Outlet: SR Outlets Reports | TSO+ | Outlets created/changed by SR over a range, with status | Menu: Outlet | - | `outlet_change_request` | - | view | Excel | F-API-017 | - | 4 |
| F-WEB-032 | Outlet Approval Panel | TSO+, admin | Pending new-outlet requests + who verified; approve → creates `outlet` (code assigned), reject with reason | Menu: Outlet | `outlet`, `outlet_change_request.approved_by/status` | requests, photos | - | view | Outlet code assignment rule (e.g. DHK-344-011) unknown; closure/info approvals included? → G-feat-55 | F-API-035 | - | 4 |
| F-WEB-033 | Credentials: change password | all web | Policy as F-SYS-004 | Menu: Credentials | `+password_hash/history` | - | - | - | - | F-API-004 | 4 | 0 |
| F-WEB-034 | Astha Gift Panel: Gift Choice Report | TSO+ | Gift chosen per Astha outlet; hand-over photo status | Menu | - | `+astha_gift_choice`, `gift_photo` | - | view | Excel | F-API-017 | 13 | 5 |
| F-WEB-035 | Tutorial (manuals) | all web | The four manuals as docs/videos | Menu | - | `+tutorial_video`, static docs | - | - | Content mgmt → G-feat-47 | F-API-027 | - | 4 |
| F-WEB-036 | Performance Leaderboard | DMO+, top | Achievement % by wing → territory, by product level; views mtd/target/volume; live | Menu | - | rollups, `target` | - | - | Guard %; Excel | F-API-013 | 8 | 4 |
| F-WEB-037 | Superstar Campaign Report | TSO+ | Per outlet: category, incentive slab, base target, STD & memo targets with achievement, criteria-met flag | Menu | - | `+superstar_enrolment`, facts | - | - | Enrolment/slab rules → G-feat-22; Excel | F-API-017 | 13 | 5 |
| F-WEB-038 | Daily Tracking Dashboard | DMO+, TSO | Route buckets 100 / 90–100 / 80–90 / <80 % of sales & memo targets, live | Menu | - | `fact_daily_route`, `target` | - | - | `cfg.kpi.bands` | F-API-015 | - | 4 |
| F-WEB-039 | Daily Tracking "take action" (after 17:00) | DMO+, TSO | Action on under-performing routes | Daily Tracking row | `+tracking_action` (route, date, action, note, by) | - | - | - | What the action is (notify TSO? reassign? note?) unspecified → G-feat-29; `cfg.day.take_action_after=17:00` | F-API-015 | - | 4 |
| F-WEB-040 | Excel export of every report | all web | Same query → xlsx formatting step | Every report | - | - | - | - | 11 of 18 reports are download-only today; column sets must be captured from the manuals/exports → G-feat-53 | F-API-017 `format=xlsx` | - | 4 |
| F-WEB-041 | Standard scope filter | all web | Wing → Division → Territory → House → Zone cascade defaulted and bounded by token | Every data page | - | geography in scope | - | - | Never accept client scope IDs | all reads | - | 4 |
| F-WEB-042 | PII gating per role | all web, API | NID, TIN, trade licence, phone shown only to roles that need them | Every outlet read | - | `outlet` | - | - | Role matrix undefined → G-feat-59 (`cfg.pii.roles_allowed`) | F-API-019 | 16 | 4 |
| F-WEB-043 | Web login + session | all web | Username/password → scoped session | / | - | `app_user` | - | - | As F-SYS-001 | F-API-001 | 4 | 0 |
| F-WEB-044 | Suspicious-location report | AMO (app), TSO+, admin | Visits/routes flagged by F-SYS-013 with pattern over time; mock-GPS counts | Menu: Reports (new) | - | `+visit_flag`, `visit` | - | view | Improvement from docs/05 (not parity); Excel | F-API-034 | - | 4 |
| F-WEB-045 | Sync-health dashboard (ops) | admin | As F-SYS-026 | Admin menu | - | `route_log`, `sync_batch` | - | - | - | F-API-028 | - | 1 |
| F-WEB-046 | Leave approval (DMO) | DMO | Approve/reject TSO leave | Web (ASSUMPTION; no DMO app) | `+leave_application.status/approved_by` | `+leave_application` | - | - | → G-feat-25 | F-API-022 | 16 | 5 |
| F-WEB-047 | Final-submit status panel | TSO+, admin | Zones submitted vs remaining, today | Dashboard tile / report | - | `final_submit`, `zone` | - | - | Part of F-WEB-001 but also a standalone drill | F-API-014 | 11 | 4 |

---

## 6. Admin, master data and configuration (F-ADM)

Admin pages named in docs/09: QC, Sales Plan, Data Entry, Supervisory Module, Retailer/Wholesale Outlet, SR Device OTP, Diamond League setup, Set Target, master-entry CRUD. §9(c) expands CRUD to every entity. Every admin write produces an `+admin_audit` row (who, when, before/after JSON) — R6.

| ID | Name | Role(s) | What it does | Trigger / entry | Writes | Reads | Rules / validations / cfg keys | API | Q | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-ADM-001 | Geography CRUD | admin | Wing, Division, Territory, House, Zone (dep codes from 5001), Cluster | Admin: Master entry | geography tables, `+admin_audit` | - | Codes unique; cannot delete with children (soft-delete `status`) | F-API-035 | - | 6 |
| F-ADM-002 | Route CRUD | admin, TSO? | Route (ids from 10001), code, name, zone, `visit_days` | Master entry | `route` | `zone` | visit_days enum Sun..Sat / Daily | F-API-035 | - | 6 |
| F-ADM-003 | Route assignment (SR/SS) | admin, TSO? | Assign SR and substitute (SS) to a route with valid_from/valid_to | Route Planning | `route_assignment` | `app_user`, `route` | Overlap rules; mid-month transfer effects → G-feat-06/07 | F-API-035 | - | 6 |
| F-ADM-004 | Product hierarchy CRUD | admin | Category / Segment / Brand / Variant / SKU with `sales_enable`, `sort`, image, unit, pack size/type | Master entry | product tables, `sku` | - | Unit Q8; image compressed | F-API-035 | 8 | 6 |
| F-ADM-005 | Price management (5 types, effective-dated) | admin | outlet / cc / distributor / reporting / nto with `valid_from` | Master entry | `sku_price` | `sku` | No overlapping ranges; 3-decimal precision SF-1; takes effect in next bundle (G-feat-52) | F-API-035 | - | 6 |
| F-ADM-006 | Sales Plan (zone × SKU) | admin, TSO? | Which SKUs are sellable per zone | Admin: Sales Plan | `sales_plan` | `zone`, `sku` | Bulk edit by territory; effect on device (G-feat-51) | F-API-035 | - | 6 |
| F-ADM-007 | User CRUD | admin | Create/disable users (`sr334001`, `amo5001`), role, name, phone, status; reset password | Master entry | `app_user`, `+password_hash` | - | Username scheme preserved from Apsis (same logins); onboarding/offboarding → G-feat-63; admin reset → G-feat-34 | F-API-035 | 4 | 6 |
| F-ADM-008 | User scope assignment | admin | Attach geography nodes to a user | Master entry | `user_scope` | geography | Role-node consistency (AMO = zone, TSO = territory, DMO = division, WM = wing) | F-API-035 | 16 | 6 |
| F-ADM-009 | Device management | admin, TSO | List devices per user; revoke; last seen; integrity signals | Admin | `device.status` | `device` | Revoke blocks sync, not local capture; pending data recovery (G-feat-41) | F-API-035 | 4 | 6 |
| F-ADM-010 | Outlet CRUD (Retailer / Wholesale Outlet) | admin, TSO? | Create/edit/close outlets; wholesale type | Admin: Retailer/Wholesale Outlet | `outlet` | classifications | Wholesale not in channel enum → G-feat-33; PII gating | F-API-035 | 17 | 6 |
| F-ADM-011 | Classification CRUD | admin | Channel, Sub-channel (Astha tiers Platinum/Gold/Diamond/Silver), Geo classification | Master entry | `sub_channel`, enums → tables | - | Enums should become tables so admin can add without deploy | F-API-035 | - | 6 |
| F-ADM-012 | Geofence configuration | admin | Radius per territory with zone/outlet override, effective-dated, audited; default + hard bounds | Admin: Config | `territory_geo_config` → `+app_config` scoped rows | geography | **R6 core**: `cfg.geo.radius_m` (scope=territory/zone/outlet), `cfg.geo.radius_min_m`, `cfg.geo.radius_max_m`; reaches apps via bundle/delta (F-SYS-006/007) | F-API-037 | 7 | 6 (minimum viable in 2) |
| F-ADM-013 | Operating-parameter console | admin | All `cfg.*` keys: check-out time, edit reasons, force reasons, photo compression, loyalty rates/catalog, password policy, token TTLs, mock-GPS policy, KPI bands, periphery radii, leave types, sync intervals, local history days, release min version | Admin: Config | `+app_config` (key, scope, value, valid_from, by), `+admin_audit` | - | Typed values + validation ranges; apps receive snapshot in bundle; web reads live | F-API-037 | - | 6 (minimum viable in 2) |
| F-ADM-014 | Set Target | admin, TSO? | Monthly targets by route/zone × category/brand/SKU; fractional STD; memo target; bulk upload | Admin: Set Target | `target` | product, geography | ≥ 0; split formula Q12 | F-API-021 | 12 | 5 |
| F-ADM-015 | Target revision workflow config | admin | Approval levels, who approves at each level | Admin | `+approval_level_config` | - | Q12 | F-API-021 | 12 | 5 |
| F-ADM-016 | Offer / promotion CRUD | admin | Promotion groups (~22), rules, SKU/brand scope, date ranges, DRP offers, free-sample rules | Admin: Programs | `+offer`, `+promotion` | product | **G-feat-13**: rule schema must reproduce current memo discounts exactly | F-API-035 | 13 | 5 |
| F-ADM-017 | Diamond League setup | admin | Point earning rules, monthly period, gift catalog with point costs, cash-back rate/cap | Admin: Diamond League setup | `+loyalty_program`, `+loyalty_gift` | - | Earning rules unspecified → G-feat-20 | F-API-035 | 13 | 5 |
| F-ADM-018 | Astha program setup | admin, TSO | Quarter definition, per-outlet per-brand STD targets + memo target, gift catalog, gift choice per outlet ("TSO portal") | Admin / TSO portal | `+astha_target`, `+astha_gift_choice`, `+gift_catalog` | `outlet` Astha | ≥ 0 targets; G-feat-21 | F-API-021 | 13 | 5 |
| F-ADM-019 | Superstar setup | admin | Monthly enrolment per outlet: category, incentive slab, base target, STD & memo targets | Admin | `+superstar_enrolment`, `+superstar_slab` | `outlet` | Criteria-met computation job → G-feat-22 | F-API-035 | 13 | 5 |
| F-ADM-020 | Survey / questionnaire / rubric definitions | admin | POSM survey questions, TSO Visit Query questions, joint-call rubric, AV/KV content | Admin | `+survey_question`, `+assessment_rubric`, `+campaign_content` | - | → G-feat-12, G-feat-56 | F-API-035 | - | 5 |
| F-ADM-021 | Task type CRUD | admin | OOS / General / Irregular Visit (+ new types) | Admin | `task_type` enum → table | - | - | F-API-035 | - | 5 |
| F-ADM-022 | SR Device OTP panel | TSO, admin | Pick user → issue OTP (shown to TSO to relay); list issued/used | Admin: SR Device OTP | `+device_otp` | `app_user` in scope | TTL `cfg.auth.otp_ttl_min`; one active OTP per user; **launch-day throughput** G-feat-11 | F-API-036 | 4 | 0 |
| F-ADM-023 | QC admin page | admin, TSO? | Review QC fault quantities by SKU/route/date; (claims?) | Admin: QC | `qc_entry.+status`? | `qc_entry` | Purpose unknown → G-feat-32 | F-API-017/035 | - | 6 |
| F-ADM-024 | Data Entry (manual backfill) | admin, TSO? | Enter sales/attendance/stock manually for a route/day when a device failed | Admin: Data Entry | field tables with `+source=manual`, `+admin_audit` | - | Content unknown → **G-feat-30**; must flow through same ingest + aggregation; flagged in Online/Offline report | F-API-038 | - | 6 |
| F-ADM-025 | Supervisory Module | admin, DMO? | Unknown (likely supervisor targets, control/joint-call targets, AMO/TSO assignments) | Admin | `+supervisor_target` | - | → G-feat-31 | F-API-035 | 16 | 6 |
| F-ADM-026 | Tutorial content management | admin | Upload/list tutorial videos and manuals per role | Admin | `+tutorial_video` | - | → G-feat-47 | F-API-027 | - | 6 |
| F-ADM-027 | App release management | admin | Upload APK / set version, min_version, notes, wave | Admin | `+app_release` | - | F-SYS-020 | F-API-029 | - | 2* |
| F-ADM-028 | Feedback inbox | admin, DMO? | Read/triage TSO feedback | Admin | `+feedback.status` | `+feedback` | → G-feat-48 | F-API-032 | - | 6 |
| F-ADM-029 | Day reopen / final-submit override | admin | Reopen a final-submitted zone-day; audited | Admin | `final_submit.+reopened_by/at`, `+admin_audit` | - | Q11 → G-feat-17 | F-API-009 | 11 | 6 |
| F-ADM-030 | Sync quarantine review | admin, TSO? | Inspect quarantined records; re-map outlet/SKU; accept or discard | Admin | `+sync_quarantine.resolved_*`, target tables | - | F-SYS-014 | F-API-028 | - | 2 |
| F-ADM-031 | Rollout wave + rollback console | admin | F-SYS-041/042 UI | Admin | `+rollout_wave` | - | - | F-API-033 | - | 7 |
| F-ADM-032 | Import + reconciliation console | admin | Run import, view reconciliation report, parallel-run compare | Admin | `+import_run`, `+import_reconciliation` | - | F-SYS-038/039/043 | F-API-033 | 18 | 7 |
| F-ADM-033 | Holiday / non-selling-day calendar | admin | National + per-wing holidays; defines working days for Login %, till-date targets | Admin | `+holiday_calendar` | - | Absent from spec → **G-feat-09** | F-API-037 | - | 4 |
| F-ADM-034 | Admin audit log viewer | admin | Every master-data/config change: who, when, before/after | Admin | - | `+admin_audit` | R6 | F-API-037 | - | 6 |
| F-ADM-035 | Loyalty balance adjustment | admin | Manual +/- points with reason (disputes, migration fixes) | Admin | `loyalty_ledger`, `+admin_audit` | - | Day-one balances must be exactly right (docs/11) | F-API-035 | 13 | 5 |
| F-ADM-036 | Dues adjustment / write-off | admin | Manual due correction with reason | Admin | `due_collection(+kind=adjustment)`, `+admin_audit` | `memo` | Retailers dispute wrong dues (docs/11) → G-feat-45 | F-API-035 | - | 5 |
| F-ADM-037 | Outlet code assignment rule | admin | Generates `DHK-344-011`-style codes on approval | Approval | `outlet.outlet_code` | `+code_sequence` | Rule unknown → G-feat-55 | F-API-035 | - | 4 |

---

## 7. API endpoints (F-API)

| ID | Endpoint | Caller(s) | What it does | Reads / Writes | Rules | Used by | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-API-001 | `POST /auth/login` | all | → `{ token, refreshToken, user, scope }` | `app_user`, `user_scope`, `device` | Rate-limit per username/IP for launch morning (R3); integrity payload optional | F-SYS-001 | 0 |
| F-API-002 | `POST /auth/refresh` | all | New access token | `+refresh_token` | Rotation; revoke on device revoke | F-SYS-002 | 0 |
| F-API-003 | `POST /auth/bind-device` `{deviceUuid, otp}` | SR, AMO | Bind phone | `device`, `+device_otp` | Single-use OTP, TTL | F-SYS-003 | 0 |
| F-API-004 | `POST /auth/change-password` | all | Policy-checked change | `+password_hash/history` | F-SYS-004 rules | F-SYS-004 | 0 |
| F-API-005 | `GET /sync/bundle?since=` | all-app | Day bundle (routes, outlets, products, prices, salesPlan, targets, offers, loyaltyBalances, geoConfig, tasks, giftAssignments, +config, +outletRequests status, +suggestions, +campaignContent refs, +businessDate) | reference + aggregates | gzip; ETag; delta; CDN-cacheable shared parts (products/prices) to survive the morning storm (R3) | F-SYS-006/007 | 1 |
| F-API-006 | `POST /sync/batch` | all-app | Upsert by `client_uuid`; groups: visits, memos, memoLines, qc, surveys, drp, dues, redemptions, attendance, stockIssues, outletRequests, assessments, distributionChecks, +tasks, +priceCompliance, +activityLog, +feedback, +giftPhotos, +outletPhotos (metadata) | field tables, `sync_batch`, `route_log` | Idempotent; parent-first; quarantine; returns `accepted{}` + `rejected[]`; size cap `cfg.sync.batch_max_kb`; 429 with Retry-After on storm | F-SYS-008 | 1 |
| F-API-007 | `POST /media/upload` | all-app | Multipart photo → `{ blobUrl }`, keyed by record `client_uuid` + purpose | Blob, photo tables | Idempotent by (client_uuid, purpose); Wi-Fi-preferred; prefer SAS direct-to-blob to keep API out of the byte path (R3) | F-SYS-010 | 2 |
| F-API-008 | `POST /day/sales-submit` `{routeId, businessDate}` | SR, AMO | Route → `sales_submitted` | day state | Idempotent; dues warning payload; offline queue (G-feat-65) | F-SR-035 | 2 |
| F-API-009 | `POST /day/final-submit` `{zoneId, businessDate}` | TSO | Zone → `final_submitted` once/day | `final_submit` | 409 on second attempt; reopen via admin (F-ADM-029) | F-TSO-010 | 3 |
| F-API-010 | `GET /outlets` | web, app | Scoped, paginated, `updated_since`, PII-gated | `outlet` | - | F-WEB-002, F-TSO-012 | 1 |
| F-API-011 | `GET /routes` | web, app | Scoped routes + assignments | `route`, `route_assignment` | - | F-WEB-010 | 1 |
| F-API-012 | `GET /targets` | web, app | Scoped targets at product level | `target` | - | F-TSO-017 | 1 |
| F-API-013 | `GET /leaderboard?view=&level=&productLevel=` | web | Achievement ranking | rollups | - | F-WEB-036 | 4 |
| F-API-014 | `GET /dashboard/national?date=` | web | Dashboard rollups (scoped) | rollups | < 1 s; cache per scope-node × date | F-WEB-001 | 1 (tile), 4 |
| F-API-015 | `GET /dashboard/daily-tracking?date=` (+`POST .../action`) | web | Buckets + take-action | `fact_daily_route`, `+tracking_action` | - | F-WEB-038/039 | 4 |
| F-API-016 | `GET /dashboard/live?zone=` | AMO, TSO app | Zone/territory live tiles | rollups | Same aggregates as web | F-AMO-020, F-TSO-002..007 | 3 |
| F-API-017 | `GET /reports/<name>?filters&format=json|xlsx` | web, AMO | One handler per report; xlsx is a formatter | facts | Async export job + download link for big ranges (R3) | F-WEB-011..037 | 4 |
| F-API-018 | `GET /app/home?role=&date=` | all-app | KPI strip / KPI tiles | rollups | Reconciles local KPI after sync | F-SR-010, F-AMO-002 | 1 |
| F-API-019 | `GET /outlets/nearby?lat&lng&radius` | TSO | Periphery query | `outlet` (geo index) | radius ∈ cfg list | F-TSO-012 | 3 |
| F-API-020 | `GET/POST /visit-plans` | TSO | Set/list visit plans | `+visit_plan*` | - | F-TSO-013/014 | 3 |
| F-API-020b | `GET /routes/:id/assignments` | web | Route → SR/SS | `route_assignment` | - | F-WEB-010 | 4 |
| F-API-021 | `GET/POST /targets/*`, `/target-revisions/*`, `/programs/astha/*` | web, app | Targets, revisions, approvals, Astha targets + gift choice | `target`, `target_revision`, `+astha_*` | ≥ 0 | F-WEB-029/030, F-ADM-014/018 | 5 |
| F-API-022 | `GET/POST /leave`, `POST /leave/:id/decision` | TSO, DMO | Apply / approve | `+leave_application` | DMO only approves | F-TSO-008/009, F-WEB-046 | 5 |
| F-API-023 | `GET /team/locations?zone=` | AMO, TSO | Last synced fix per SR with age | `+user_last_fix` | No continuous tracking | F-AMO-016, F-TSO-011 | 3 |
| F-API-024 | `GET /team/stock?zone=&date=` | AMO | SR lifted stock | `stock_issue` | - | F-AMO-021 | 3 |
| F-API-025 | `GET /memos?outlet=&date=` | AMO, SR | Previous sale data | `memo`, `memo_line` | Scoped | F-AMO-013 | 3 |
| F-API-026 | `GET/POST /tasks`, `POST /tasks/:uuid/resolve` | AMO, TSO, SR | Assign / list / resolve (also via batch) | `task` | Idempotent by client_uuid | F-AMO-018/019, F-SR-047 | 5 |
| F-API-027 | `GET /tutorials` | all | Video/manual list | `+tutorial_video` | - | F-SR-048, F-WEB-035 | 2 |
| F-API-028 | `GET /ops/sync-health`, `GET/POST /ops/quarantine` | admin | Sync health + quarantine | `route_log`, `+sync_quarantine` | - | F-SYS-026, F-ADM-030 | 1 |
| F-API-029 | `GET /app/update-check?version=&role=&abi=` | all-app | Latest release, min version, APK URL | `+app_release` | Wave-aware; CDN/Blob for APK bytes | F-SYS-020 | 2* |
| F-API-030 | `POST /support/pda-upload` | all-app | Diagnostic upload (SAS to blob) | `+support_upload` | Size cap | F-SYS-021 | 2* |
| F-API-031 | `POST /auth/logout` | all | Revoke refresh token | `+refresh_token` | - | F-SYS-022 | 2 |
| F-API-032 | `POST /feedback`, `GET /admin/feedback` | TSO, admin | Feedback | `+feedback` | - | F-TSO-018, F-ADM-028 | 3 |
| F-API-033 | `/admin/migration/*` (import run, reconcile, waves, compare) | admin | Migration tooling | `+import_*`, `+rollout_wave`, `+parallel_compare` | - | F-SYS-038..043 | 7 |
| F-API-034 | `GET /reports/suspicious-locations` | web, AMO, TSO | Flags from F-SYS-013 | `+visit_flag` | - | F-WEB-044 | 2 |
| F-API-035 | `/admin/*` CRUD (geography, products, prices, sales-plan, routes, route-assignments, users, user-scope, outlets, outlet-approvals, targets, target-revisions, programs, device-otp, classifications, offers, surveys, rubrics, task-types, tutorials, releases) | admin | Master data | all reference tables, `+admin_audit` | Every write audited (R6); optimistic concurrency | F-ADM-* | 6 (subset in 0/4) |
| F-API-036 | `POST /admin/device-otp` | TSO, admin | Issue OTP | `+device_otp` | TTL, scope | F-ADM-022 | 0 |
| F-API-037 | `GET/PUT /admin/config`, `GET /config/snapshot` | admin, all-app | Read/write `cfg.*` with scope + effective date; apps fetch snapshot in bundle | `+app_config`, `+admin_audit` | Typed validation; version stamp so apps can report which config they ran | F-ADM-012/013/033 | 2 (read), 6 (console) |
| F-API-038 | `POST /admin/data-entry` | admin | Manual backfill through the same ingest | field tables (`source=manual`) | Same idempotency + aggregation path | F-ADM-024 | 6 |

---

## 8. (a) Features the spec names but under-specifies — the missing rule

| # | Feature (IDs) | Missing rule | Gap |
| --- | --- | --- | --- |
| a1 | Offers auto-apply (F-SR-024, F-ADM-016) | Rule schema for ~22 promotion groups: trigger (qty/brand/mix/DRP), effect (per-line %/amount, free SKU, memo-level), stacking order, rounding, date/zone scope. Without it the printed memo total cannot match Apsis. | G-feat-13 |
| a2 | Sale edit reasons (F-SR-033) | The 3 reason labels; whether edit is allowed after print; whether old memo is voided on print of the new one | G-feat-15, G-feat-16 |
| a3 | Credit sale (F-SR-026) | Who may grant credit; per-outlet max due; max days; block new credit when overdue; whether dues warning at submit blocks | G-feat-14 |
| a4 | Due collection (F-SR-032) | Allocation to memos (FIFO vs chosen), partial across memos, receipt, collection by a different SR/SS than the seller | G-feat-45 |
| a5 | Zero sale (F-SR-029) | Whether a memo row is created and printed; counted in Memo KPI?; counts as "successful call" for CPR? | G-feat-38 |
| a6 | KPI strip Non-visit / No-sale (F-SR-010) | Exact definitions (planned-not-visited vs not-yet-visited; zero-sale vs no memo) | G-feat-38 |
| a7 | Check-out at 17:00 (F-SR-012, F-AMO-003) | Device clock or server time; what if device clock is wrong offline; forgotten check-out handling | G-feat-18 |
| a8 | Sales Submit (F-SR-035) | Whether allowed while offline/with pending rows; whether it blocks further sales; undo | G-feat-65 |
| a9 | Final Submit (F-TSO-010) | Allowed with routes not submitted ("Not Set")? What happens to late syncs after; reopen (Q11) | G-feat-64, G-feat-17 |
| a10 | Login % / Submit % (F-TSO-007, F-AMO-020) | Denominators on holidays and non-visit-days; delta refresh ≠ login (SF-7, SF-9) | G-feat-09, G-feat-10 |
| a11 | Device OTP (F-SYS-003, F-ADM-022) | TTL, re-bind flow, number of devices per user, shared phone with several SRs | G-feat-11, G-feat-41 |
| a12 | Force Sale photo updates location (F-SR-018) | Which flow approves the location update; does it also create an info request; what if outlet has no location (Q7) | G-feat-39 |
| a13 | Update Base (F-AMO-028) | Request type (not in enum); approver; offline map tiles | G-feat-39 |
| a14 | Price compliance (F-AMO-030/035) | Entire screen, fields, rules | G-feat-01 |
| a15 | Joint call rubric (F-AMO-011) | Steps 4–5 items; weighting/score aggregation; per-SR trend report | G-feat-56 |
| a16 | AMO KPI tiles (F-AMO-002) | Where control-call and joint-call targets are set | G-feat-57 |
| a17 | Team Location "live" (F-AMO-016, F-TSO-011) | Source and freshness of the fix (attendance only? each visit?) | G-feat-36 |
| a18 | Task Delegation (F-SR-047, F-AMO-019) | Status set beyond pending/completed, overdue, reassign, evidence on resolve, badge semantics | G-feat-19 |
| a19 | Diamond League (F-SR-043, F-ADM-017) | How points are **earned**; monthly reset; offline double-spend arbitration | G-feat-20 |
| a20 | Astha gift choice (F-TSO-020) | The "TSO portal" surface; when choices lock; one gift per outlet per quarter? | G-feat-21 |
| a21 | Superstar (F-WEB-037) | Enrolment, slab config, criteria computation | G-feat-22 |
| a22 | Free Sample report (F-WEB-026) | Capture screen (none in SR app) | G-feat-23 |
| a23 | Target split + revision approvals (F-ADM-014/015, F-WEB-030) | Split formula to route/SKU; approval levels and who | G-feat-24 |
| a24 | Leave (F-TSO-009) | DMO UI; leave types/balances; SR/AMO leave | G-feat-25 |
| a25 | Daily Tracking "take action" (F-WEB-039) | The action itself | G-feat-29 |
| a26 | Admin pages Data Entry / Supervisory Module / QC (F-ADM-023..025) | Their content | G-feat-30, G-feat-31, G-feat-32 |
| a27 | Retailer edit sections (F-WEB-003) | Field lists; whether web edit bypasses verification | G-feat-54 |
| a28 | Outlet Approval Panel (F-WEB-032) | Code assignment rule; closure/info approvals in same panel? | G-feat-55 |
| a29 | Excel exports (F-WEB-040) | Column sets of the 11 download-only reports | G-feat-53 |
| a30 | Online/Offline Sales report (F-WEB-025) | Definition of "online" | G-feat-58 |
| a31 | PII gating (F-WEB-042) | Role × field matrix | G-feat-59 |
| a32 | Stock load (F-SR-014) | Multiple loads per day; unit of entry; can sales exceed stock | G-feat-02, G-feat-44 |
| a33 | Memo numbering (F-SYS-027) | Series continuity (Q5); what the retailer sees on a reprint/edit | G-feat-04 |
| a34 | Summary print (F-SR-036) | Layout; whether it is the official return statement | G-feat-02, G-feat-60 |
| a35 | Tutorial / AV-KV / survey content (F-SR-020/021/048) | Content management and question sets | G-feat-12, G-feat-47 |
| a36 | Logout on SR/AMO (F-SYS-022) | Data retained or wiped; pending rows | G-feat-41 |
| a37 | Microphone (F-SYS-023) | Whether call audio is recorded (Q15) | G-feat-28 |
| a38 | Wholesale / distributor (F-ADM-010) | Whether `bex` order pages / back-margin letters are in scope (Q17) | G-feat-27 |
| a39 | DMO / WM / Top views (F-WEB-001, 046) | Exact pages and approvals (Q16) | G-feat-26 |

## 8. (b) Features the business implies but the spec omits

| # | Feature (proposed ID) | Why it is needed on day one | Gap |
| --- | --- | --- | --- |
| b1 | **End-of-day stock return / reconciliation** (F-SR-051 proposed) | `stock_issue.returned_qty` exists; Summary shows "return qty (issue − sold)" but nobody enters the actual physical return or the distributor's confirmation; unsold vs damaged vs short must be recorded for the distributor settlement | G-feat-02 |
| b2 | **Cash deposit / settlement with distributor** (F-SR-052) | Cash collected (memos paid + dues collected) must be handed to the distributor and matched; today it is implicit. Without it dues/cash disputes cannot be reconciled | G-feat-03 |
| b3 | **Memo reprint rules** (F-SR-031) | Reprint exists via Memo → Print; reprint count, "duplicate" marking, dashboard exclusion | G-feat-04 |
| b4 | **Returns / damaged goods from outlet** (F-SR-053) | QC captures fault quantities but no replacement/credit path or stock effect | G-feat-05 |
| b5 | **SR transfer between routes mid-month** (F-ADM-003) | Targets, MTD achievement attribution, bundle re-download, device re-bind, open dues ownership | G-feat-06 |
| b6 | **Substitute SR (SS) cover day** (F-ADM-003, F-SR-008) | `route_assignment` hints SS; the SS needs the route bundle for that day, memo numbering, KPI attribution to route vs person | G-feat-07 |
| b7 | **Multiple visits to one outlet per day** (F-SR-017) | `fact_daily_outlet` PK collapses; re-open after zero sale; CPR counting | G-feat-08 |
| b8 | **Holiday calendar / non-selling days** (F-ADM-033) | Fridays/Eid: Login %/Submit % denominators, till-date target proration, Daily Tracking buckets | G-feat-09 |
| b9 | **Route visit-day exceptions** (F-SR-008) | "Today's route" when `visit_days` excludes today; ad-hoc extra visit day; market closed day | G-feat-10 |
| b10 | **Shared phone / multi-user per device** (F-SYS-003/022) | Phones are shared-ownership (docs/11); two SRs on one phone same day; local DB isolation; device binding model | G-feat-41 |
| b11 | **Sell to a newly captured outlet before approval** (F-SR-037) | SR captures a new shop and the shop buys immediately; memo must reference a provisional outlet (client_uuid) and be re-linked on approval | G-feat-42 |
| b12 | **Printer failure path** (F-SR-028) | Sale saved without print; reprint later; `printed_at` null; dashboards should not depend on print | G-feat-43 |
| b13 | **Stock insufficiency validation** (F-SR-023) | Whether qty > current stock is blocked, warned or allowed (top-up loads) | G-feat-44 |
| b14 | **Credit limits and dues aging** (F-SR-026) | Max due, days, aging buckets for AMO/TSO | G-feat-14, G-feat-45 |
| b15 | **Rounding / 3-decimal prices on memo** (F-SYS-045) | Line and total rounding must equal the current printed memo to the paisa | G-feat-46 |
| b16 | **Password reset / forgot password / lockout** (F-SYS-001) | 8,500 first logins on cutover morning; TSO-assisted reset | G-feat-34 |
| b17 | **Notifications to the app** (task assigned, request approved, update available) | No mechanism specified; must be battery-safe (piggyback on bundle delta / FCM, no polling) | G-feat-35 |
| b18 | **Outlet with no saved location** (F-SR-017) | Imported outlets may lack coordinates; first visit must capture, not force-sale forever (Q7) | G-feat-39 |
| b19 | **Outlet route/cluster change request** (F-SR-039) | `info` request covers name/owner/mobile only | G-feat-61 |
| b20 | **Outlet reopen / reactivation** | After a wrong "permanently closed" | G-feat-62 |
| b21 | **SR onboarding / offboarding** (F-ADM-007/009) | New SR: create → scope → route → OTP → first bundle; leaver: revoke device, recover pending data, reassign dues | G-feat-63 |
| b22 | **Sales Submit offline queueing** (F-SR-035) | Dead zone at end of day: the submit must queue and apply on sync (R5) | G-feat-65 |
| b23 | **Final Submit with routes not submitted** (F-TSO-010) | Policy for "Not Set" routes and late syncs after zone close | G-feat-64 |
| b24 | **Day reopen** (F-ADM-029) | Q11; audited correction path | G-feat-17 |
| b25 | **Credential migration + first-login reset at cutover** (F-SYS-038) | "Same logins where possible" requires importing usernames and forcing a reset or re-hash path | G-feat-66 |
| b26 | **Old app read-only per wave** (F-SYS-041) | AKTCL does not control the Apsis app; needs Apsis cooperation or a SIM/MDM lever | G-feat-67 |
| b27 | **Admin audit trail** (F-ADM-034) | R6 requires it for config and master data | G-feat-50 |
| b28 | **Config change propagation** (F-ADM-012/013) | A radius change must reach a phone already in the field (delta refresh) and be versioned on each visit row | G-feat-50 |
| b29 | **Manual backfill (Data Entry)** (F-ADM-024) | Lost/broken phone: the day's sales still need to exist for dues and targets | G-feat-30 |
| b30 | **Price change mid-day** (F-ADM-005) | Which price a memo uses; bundle snapshot vs delta | G-feat-52 |
| b31 | **Sales-plan change effect on device** (F-ADM-006) | SKU removed from plan while SR has stock of it | G-feat-51 |
| b32 | **Supervisor (AMO/TSO) attendance & leave** | Only TSO has Leave; AMO/SR leave handling and its effect on Login % | G-feat-25, G-feat-18 |
| b33 | **Visit kind for AMO calls** (F-AMO-036) | AMO sales must not inflate SR CPR | (covered by F-AMO-036; confirm) |
| b34 | **Feedback triage** (F-ADM-028) | TSO feedback has a sink | G-feat-48 |

## 8. (c) Admin / master-data CRUD — every entity in docs/03

`C/R/U/D` = create / read / update / soft-delete; `Imp` = imported from Apsis (docs/11); `Aud` = audited write (R6). Transactional tables are **read-only + correction-row** for admins (never edit in place).

| Entity (docs/03) | Admin ops | Who | Notes / validation | Feature |
| --- | --- | --- | --- | --- |
| `wing` | CRUD, Imp, Aud | admin | code unique | F-ADM-001 |
| `division` | CRUD, Imp, Aud | admin | parent wing | F-ADM-001 |
| `territory` | CRUD, Imp, Aud | admin | parent division; geo radius lives in config | F-ADM-001/012 |
| `house` | CRUD, Imp, Aud | admin | parent territory | F-ADM-001 |
| `zone` | CRUD, Imp, Aud | admin | dep codes from 5001; territory + house | F-ADM-001 |
| `cluster` | CRUD, Imp, Aud | admin, TSO? | type (e.g. Transit Hub), zone | F-ADM-001 |
| `route` | CRUD, Imp, Aud | admin, TSO? | ids from 10001; visit_days | F-ADM-002 |
| `route_assignment` | CRUD, Imp, Aud | admin, TSO? | SR/SS, valid_from/to, no overlapping SR on same route/day | F-ADM-003 |
| `product_category` / `segment` / `brand` / `variant` | CRUD, Imp (seed CSV), Aud | admin | sales_enable, sort, image | F-ADM-004 |
| `sku` | CRUD, Imp (seed), Aud | admin | code unique, unit, pack size/type | F-ADM-004 |
| `sku_price` | CRU (no delete; end-date), Imp, Aud | admin | 5 types; effective-dated; 3-decimal | F-ADM-005 |
| `sales_plan` | CRUD (bulk), Imp, Aud | admin, TSO? | zone × SKU | F-ADM-006 |
| `channel` | CRUD (enum → table), Aud | admin | GT/DCC/Astha/RCC/MT/HoReCa + Wholesale? | F-ADM-011 |
| `sub_channel` | CRUD, Imp, Aud | admin | Astha tiers | F-ADM-011 |
| `geo_classification` | CRUD (enum → table), Aud | admin | Hill/Urban/SemiUrban/Rural | F-ADM-011 |
| `app_user` | CRUD, Imp, Aud; reset password; disable | admin | role, username scheme | F-ADM-007 |
| `user_scope` | CRUD, Imp, Aud | admin | role-node consistency | F-ADM-008 |
| `device` | R, U (revoke), Aud | admin, TSO | bound via OTP; last seen; integrity | F-ADM-009 |
| `+device_otp` | C, R | TSO, admin | issue/list | F-ADM-022 |
| `territory_geo_config` → `+app_config` | CRU (versioned), Aud | admin | radius with zone/outlet override | F-ADM-012 |
| `offer` / `promotion` | CRUD, Imp?, Aud | admin | rule schema (G-feat-13) | F-ADM-016 |
| `outlet` | CRUD, Imp, Aud | admin, TSO?; web edit | PII gating; code rule | F-ADM-010, F-WEB-003 |
| `outlet_change_request` | R, U (approve/reject), Aud | TSO+, admin | approval panel | F-WEB-032 |
| `outlet_photo` | R (view), D (privacy) | admin | - | F-WEB-003 |
| `attendance` | R; correction row via Data Entry | admin | GIGO report | F-ADM-024 |
| `stock_issue` | R; correction via Data Entry | admin | - | F-ADM-024 |
| `visit` | R; flag review | admin, TSO+ | suspicious flags | F-WEB-044 |
| `memo` / `memo_line` | R; void/supersede row only | admin | never edit in place | F-ADM-024 |
| `qc_entry` | R (QC page) | admin | G-feat-32 | F-ADM-023 |
| `survey_response` | R | admin | + `+survey_question` CRUD | F-ADM-020 |
| `drp_collection` | R | admin | - | F-WEB-023 |
| `due_collection` | R; adjustment row | admin | G-feat-45 | F-ADM-036 |
| `loyalty_ledger` | R; adjustment row | admin | - | F-ADM-035 |
| `redemption` | R | admin | - | F-WEB-022 |
| `gift_photo` | R | admin | - | F-WEB-022/034 |
| `task` | CRUD | AMO, TSO, admin | + `task_type` table | F-ADM-021, F-AMO-019 |
| `call_assessment` | R | admin | + `+assessment_rubric` CRUD | F-ADM-020 |
| `distribution_check` | R | admin | - | reports |
| `sync_batch` | R | admin | sync health | F-ADM-030 |
| `route_log` | R | admin | Data Entry Log | F-WEB-016 |
| `final_submit` | R; reopen (Aud) | admin | Q11 | F-ADM-029 |
| `activity_log` | R | admin | audit | F-ADM-034 |
| `target` | CRUD (bulk), Imp, Aud | admin, TSO? | ≥ 0; fractional | F-ADM-014 |
| `target_revision` | C (request), U (approve), R | TSO+, admin | levels | F-WEB-030, F-ADM-015 |
| `+app_config` | CRU (versioned), Aud | admin | every `cfg.*` | F-ADM-013 |
| `+holiday_calendar` | CRUD, Aud | admin | - | F-ADM-033 |
| `+leave_application` | R, U (approve) | DMO, admin | - | F-WEB-046 |
| `+visit_plan*` | R | TSO, admin | - | F-TSO-013 |
| `+feedback` | R, U (status) | admin | - | F-ADM-028 |
| `+tutorial_video`, `+campaign_content` | CRUD, Aud | admin | - | F-ADM-026 |
| `+app_release` | CRUD, Aud | admin | - | F-ADM-027 |
| `+astha_target`, `+astha_gift_choice`, `+gift_catalog` | CRUD, Imp, Aud | admin, TSO | - | F-ADM-018 |
| `+loyalty_program`, `+loyalty_gift` | CRUD, Aud | admin | - | F-ADM-017 |
| `+superstar_enrolment`, `+superstar_slab` | CRUD, Aud | admin | - | F-ADM-019 |
| `+supervisor_target` | CRUD, Aud | admin | control/joint-call targets | F-ADM-025 |
| `+sync_quarantine` | R, U (resolve) | admin | - | F-ADM-030 |
| `+rollout_wave`, `+import_run`, `+import_reconciliation`, `+id_crosswalk` | CR, Aud | admin | - | F-ADM-031/032 |
| `+admin_audit` | R | admin | immutable | F-ADM-034 |

---

## 9. Totals (counted from the inventory rows above)

See §9.1 (per role) and §9.2 (per phase). Phase counting rule: a row with two phases (e.g. "0 (skeleton), 7 (full)" or "6 (minimum viable in 2)") is counted under its **first** listed phase; `2*` counts as phase 2.

### 9.1 Features per role

| Role | Rows | Notes |
| --- | --- | --- |
| SYS | 46 | cross-cutting: auth, sync, aggregation, migration tooling |
| SR | 50 | SR app screens/actions |
| AMO | 36 | AMO app screens/actions |
| TSO | 20 | TSO app screens/actions (+2 actor rows for OTP and Astha gift choice) |
| WEB | 47 | web dashboards, reports, portals (37 current pages + 10 new/implicit) |
| ADM | 37 | admin, master data, config console |
| API | 39 | endpoints (docs/09 set + implied) |
| **Total** | **275** | |

### 9.2 Features per build phase (docs/12 numbering)

| Phase | SYS | SR | AMO | TSO | WEB | ADM | API | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 Foundations | 7 | 1 | 0 | 1 | 2 | 1 | 5 | 17 |
| 1 Vertical slice | 19 | 16 | 0 | 0 | 1 | 0 | 8 | 44 |
| 2 Full SR day (incl. 2* pre-pilot items) | 14 | 26 | 0 | 0 | 0 | 2 | 8 | 50 |
| 3 Supervisors | 0 | 0 | 33 | 15 | 0 | 0 | 8 | 56 |
| 4 Web dashboards & reports | 0 | 0 | 0 | 0 | 35 | 2 | 4 | 41 |
| 5 Programs | 0 | 7 | 3 | 4 | 9 | 10 | 3 | 36 |
| 6 Admin / master data | 0 | 0 | 0 | 0 | 0 | 20 | 2 | 22 |
| 7 Migration & cutover | 6 | 0 | 0 | 0 | 0 | 2 | 1 | 9 |
| **Total** | 46 | 50 | 36 | 20 | 47 | 37 | 39 | **275** |

### 9.3 Gaps

67 gap IDs issued (G-feat-01..G-feat-67; G-feat-37 reserved to the seed finding SF-9): 6 blocker, 39 major, 21 minor, 1 reserved. Under-specified named features (§8a): 39 items. Implied-but-absent features (§8b): 34 items. Entities with admin CRUD defined (§8c): 58.

---

## 10. Gap list (G-feat-NN)

| ID | Title | Severity | Where | Why it matters / what to decide |
| --- | --- | --- | --- | --- |
| G-feat-01 | Price compliance capture has no screen, fields or rules | major | docs/07 Sales Submit counts; F-AMO-030/035 | AMO reconciliation lists it; without a definition the AMO count can never match. Decide: per-SKU observed price at control call? |
| G-feat-02 | End-of-day stock return / reconciliation flow absent | blocker | docs/03 `stock_issue.returned_qty`; docs/06 Summary; F-SR-036/050 | The distributor settles unsold stock daily; no entry point for actual returns, damaged, short. Needed for "no disruption to a single day of selling". |
| G-feat-03 | Cash deposit / distributor settlement absent | major | docs/06 day flow; F-SR-052 (proposed) | Cash collected vs memos vs dues is never closed; disputes are unresolvable. |
| G-feat-04 | Memo reprint rules undefined | minor | docs/07 Memo menu Print; F-SR-031 | Reprint marking ("duplicate"), count, and which number an edited memo prints. |
| G-feat-05 | Returns / damaged goods path after QC undefined | major | docs/06 Product QC; F-SR-027 | QC records faults; no replacement, credit note or stock effect. |
| G-feat-06 | SR transfer between routes mid-month | major | docs/03 `route_assignment`; F-ADM-003 | Target/achievement attribution, bundle refresh, dues ownership. |
| G-feat-07 | Substitute SR (SS) cover day flow | major | docs/03 route_assignment "SR (and SS)"; docs/09 Browse Routes; F-ADM-003 | SS must get the bundle and sell under the route; KPI attribution to route vs person. |
| G-feat-08 | Multiple visits to one outlet per day | major | schema `fact_daily_outlet` PK; F-SR-017 | Collapse rule for CPR/visited; re-open after zero sale. |
| G-feat-09 | Holiday / non-selling-day calendar absent | major | docs/10 Login %, till-date targets; F-ADM-033 | Fridays/Eid make Login % and till-date % wrong without a working-day calendar. |
| G-feat-10 | Route visit-day exceptions | major | docs/03 `route.visit_days`; F-SR-008 | "Today's route" when none scheduled; ad-hoc days; denominators. |
| G-feat-11 | Device OTP issuance surface and launch-day throughput | blocker | docs/06 bind; docs/09 SR Device OTP; F-ADM-022, F-TSO-019 | 8,500 bindings on cutover; TSO has no app screen for it; TTL/re-bind undefined. |
| G-feat-12 | Survey questions, AV/KV and campaign content management absent | major | docs/06 call step c; F-SR-020/021, F-ADM-020 | No tables or admin; content cannot change without a release. |
| G-feat-13 | Offer/promotion rule engine undefined (~22 groups, DRP offers) | blocker | docs/10 Promotions; F-SR-024, F-ADM-016, F-WEB-023 | Printed memo totals must equal Apsis; impossible without the rule schema. |
| G-feat-14 | Credit sale eligibility and limits | major | docs/06 credit (বাকি); F-SR-026 | Who may give credit, max due/days, block on overdue. |
| G-feat-15 | The 3 sale-edit reasons are unnamed | minor | docs/06 Sale edit; F-SR-033 | Config key `cfg.memo.edit_reasons`. |
| G-feat-16 | Edit after print: void/reprint semantics | major | docs/06 Sale edit; F-SR-033 | Retailer holds the old paper memo. |
| G-feat-17 | After final submit: reopen path and late syncs | major | docs/13 Q11; F-ADM-029, F-TSO-010 | Late-syncing phone after zone close must land somewhere auditable. |
| G-feat-18 | Attendance edge cases (missed check-in, forgotten check-out, device clock) | major | docs/06 step 1/7; F-SR-011/012 | GIGO report integrity; 17:00 gate on a wrong clock. |
| G-feat-19 | Task lifecycle beyond pending/completed | minor | docs/06 Task Delegation; F-SR-047 | Overdue, reassign, evidence. |
| G-feat-20 | Diamond League point earning rules absent | blocker (Phase 5) | docs/10 Diamond League; F-SR-043, F-ADM-017 | Only spending is specified; balances cannot be computed or migrated forward. |
| G-feat-21 | Astha gift choice surface ("TSO portal") undefined | major | docs/13 Q13; F-TSO-020, F-ADM-018 | Feeds F-SR-044 photo capture. |
| G-feat-22 | Superstar enrolment/slab/criteria rules | major | docs/10 Superstar; F-WEB-037, F-ADM-019 | Report cannot be computed. |
| G-feat-23 | Free sample capture screen missing | major | docs/10 free samples; F-WEB-026 | Report exists, capture doesn't. |
| G-feat-24 | Target split formula and approval levels | major | docs/13 Q12; F-ADM-014/015, F-WEB-030 | Fractional route/SKU targets drive every achievement %. |
| G-feat-25 | Leave approval by DMO has no UI; SR/AMO leave absent | major | docs/08 Leave; F-WEB-046 | DMO is web-only? (Q16). |
| G-feat-26 | DMO / WM / Top Management views unspecified | major | docs/13 Q16; F-WEB-001 | Scope of their dashboards and approvals. |
| G-feat-27 | Wholesale / distributor (`bex`, back-margin) scope | minor (until Q17 answered) | docs/13 Q17; F-ADM-010 | May be a whole module. |
| G-feat-28 | Microphone / voice recording | minor | docs/13 Q15; F-SYS-023 | Privacy + battery if kept. |
| G-feat-29 | Daily Tracking "take action" undefined | major | docs/09 Daily Tracking; F-WEB-039 | What action, by whom, to whom. |
| G-feat-30 | "Data Entry" admin page = manual backfill? | major | docs/09 admin pages; F-ADM-024 | Needed for broken-phone days; must use same ingest. |
| G-feat-31 | "Supervisory Module" content unknown | major | docs/09 admin pages; F-ADM-025 | Possibly supervisor targets. |
| G-feat-32 | "QC" admin page content unknown | minor | docs/09; F-ADM-023 | - |
| G-feat-33 | Wholesale outlet type not in channel enum | minor | docs/09 Retailer/Wholesale Outlet; schema | - |
| G-feat-34 | Password reset / forgot / lockout flow absent | major | docs/09 Credentials; F-SYS-001, F-ADM-007 | Cutover morning support load. |
| G-feat-35 | No notification mechanism to apps | major | docs/06 badges, tasks; F-SR-046 | Must be battery-safe: ride on bundle delta or FCM, no polling. |
| G-feat-36 | Team Location "live" source and freshness | minor | docs/07 Team Location; F-AMO-016 | Define as last synced fix + age. |
| G-feat-37 | (reserved — covered by seed finding SF-9: login event definition) | - | - | - |
| G-feat-38 | KPI strip Non-visit / No-sale and zero-sale memo semantics | minor | docs/06 Home, zero sale; F-SR-010/029 | Affects CPR and Memo counts. |
| G-feat-39 | Outlet without saved location; location-update approval path | major | docs/13 Q7; docs/05 force sale; F-SR-017/018, F-AMO-028 | Imported outlets may lack coordinates. |
| G-feat-40 | SR with several routes on one day (route picker, header) | major | docs/03 route_assignment; F-SR-008 | Alternate-day cover; bundle scope. |
| G-feat-41 | Shared phones: multi-user per device, logout/wipe with pending data | blocker | docs/11 shared-ownership; F-SYS-003/022, F-TSO-001 | Data loss risk on logout; binding model. |
| G-feat-42 | Selling to a newly captured outlet before approval | major | docs/06 New shop; F-SR-037 | Provisional outlet id → re-link on approval. |
| G-feat-43 | Printer failure path | major | docs/06 print; F-SR-028 | Sale must persist without print; reprint later. |
| G-feat-44 | Stock insufficiency validation at sale | major | docs/06 KPI "Current stock"; F-SR-023/050 | Block / warn / allow. |
| G-feat-45 | Dues allocation, aging and adjustments | major | docs/06 due collection; docs/11 open dues; F-SR-032, F-ADM-036 | Retailers dispute wrong dues. |
| G-feat-46 | Memo rounding with 3-decimal prices | blocker | seed SF-1; F-SYS-045, F-SR-025 | Printed totals must match Apsis to the paisa. |
| G-feat-47 | Tutorial / manual content management | minor | docs/06 Tutorial; F-ADM-026 | - |
| G-feat-48 | Feedback triage sink | minor | docs/08 My Feedback; F-ADM-028 | - |
| G-feat-49 | Visit plan completion rule and geo gate for Visit Query | minor | docs/08 My Call; F-TSO-014/015 | - |
| G-feat-50 | Admin audit trail + config propagation/versioning | major | R6; F-ADM-012/013/034 | Radius change must reach phones in the field and be recorded per visit. |
| G-feat-51 | Sales-plan change effect on a device holding stock | minor | docs/03 sales_plan; F-ADM-006 | - |
| G-feat-52 | Price change mid-day: which price a memo uses | major | docs/03 sku_price effective dating; F-SYS-045, F-ADM-005 | Bundle snapshot vs delta refresh. |
| G-feat-53 | Column sets of the 11 download-only reports unknown | major | docs/09 Reports; F-WEB-040 | Analysts' parity; capture from manuals/exports before Phase 4. |
| G-feat-54 | Retailer web edit field lists; bypass of verification | minor | docs/09 Browse Retailer; F-WEB-003 | - |
| G-feat-55 | Outlet code assignment rule; closure/info approvals on panel | minor | docs/09 Outlet Approval Panel; F-WEB-032, F-ADM-037 | - |
| G-feat-56 | Joint call rubric steps 4–5 and scoring | minor | docs/07 Joint Call; F-AMO-011 | - |
| G-feat-57 | Control-call / joint-call targets origin | minor | docs/07 KPI tiles; F-AMO-002 | - |
| G-feat-58 | Online/Offline Sales report definition | minor | docs/09 Reports; F-WEB-025 | Store `captured_online` on visit. |
| G-feat-59 | PII role × field matrix | major | docs/03 PII note, docs/09 rules; F-WEB-042 | Needed before any outlet read ships. |
| G-feat-60 | Summary print layout | minor | docs/07 Summary Print; F-SR-036 | - |
| G-feat-61 | Outlet route/cluster change request type | minor | docs/06 Info change; F-SR-039 | - |
| G-feat-62 | Outlet reopen / reactivation | minor | docs/06 Permanently closed; F-SR-038 | - |
| G-feat-63 | SR onboarding / offboarding runbook features | major | docs/09 admin; F-ADM-007/009 | Device revoke + pending data recovery + dues reassignment. |
| G-feat-64 | Final Submit with unsubmitted ("Not Set") routes | major | docs/08 Final Submit; F-TSO-010 | Allowed? Forces? |
| G-feat-65 | Sales Submit while offline must queue | major | docs/06 step 7; R5; F-SR-035 | Dead zone at end of day. |
| G-feat-66 | Credential migration at cutover (same logins) | major | docs/01 "same logins"; F-SYS-038 | Password hashes won't transfer; plan forced reset or TSO-assisted first login. |
| G-feat-67 | Old Apsis app cannot be set read-only by AKTCL | major | docs/11 wave rollout; F-SYS-041/042 | Needs Apsis cooperation or MDM/APN lever; otherwise double-selling in both apps during a wave. |
