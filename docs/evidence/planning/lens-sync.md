# Lens: OFFLINE-FIRST, IMMEDIATE SYNC AND BATTERY (requirements R4, R5)

Author: sync/offline/battery specialist. Date: 2026-10-04. Status: planning document; no code.
Inputs read in full: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13, db/schema.sql, db/seed/*, seed-findings.md, and the sibling lenses already in this folder (lens-data.md, lens-features.md, lens-scale.md). Where this lens relies on a sibling's decision it cites it (D-04, D-scale-1..12, M-NN migrations, F-SYS-NNN features) instead of re-deciding.

Conventions: config keys `cfg.<area>.<name>`; decisions proposed here `D-sync-NN`; gaps `G-sync-NN`; test gates `T-<phase>-NN` in the **20–39 range** per phase (lens-data uses 01–19, lens-scale uses 50–59). Local (device) tables are prefixed `L:` in prose and live in the per-user SQLite file (§1). "Dhaka" = Asia/Dhaka, UTC+6, no DST.

---

## 0. Conclusions (read this if nothing else)

1. **The device is the system of record for a day until the server acknowledges each row.** Every capture is one local SQLite transaction that writes the domain row *and* an immutable outbox entry (§1.3). Nothing in the day flow — attendance, stock, visit, sale, QC, print, due collection, outlet capture, **Sales Submit** — needs the network. Sales Submit and check-out are themselves outbox events (D-sync-07), so a Wi-Fi-only phone can finish its day in the field and upload at home.
2. **"Immediate when online" is event-driven, never polled.** Four triggers only: a debounced push after the last local write (`cfg.sync.trickle_debounce_s` = 5, with a *family hold* that keeps a visit and its children together), a push on app foreground, a WorkManager one-off job with the `NetworkType.CONNECTED` constraint whenever the outbox is non-empty while offline (the OS wakes it the moment connectivity returns — this is R5 at zero polling cost), and a 15-minute WorkManager periodic fallback that exits in <50 ms when the outbox is empty. No foreground service, no socket, no timer loop (§2).
3. **Three idempotency layers, one truth:** row `client_uuid` (ingest registry, M-30), batch `batch_uuid` with a stored response replayed for 24 h (D-scale-4), and *family-atomic* batches (a visit and all its children are always in the same batch, parents first), so the only parent-missing case left is a foreign client. A dropped response is resent with the **same** `batch_uuid`; the server replays, the device converges (§2.6–2.8).
4. **Row state machine:** `pending → syncing → synced | rejected | conflict`, with `syncing → pending` on any transport failure and `rejected(retryable) → pending` after a bundle/config refresh. `rejected` and `conflict` are terminal on the device, visible to the SR as "needs attention" and to the AMO/admin through `sync_rejected`/`sync_conflict`; they **never block Sales Submit** (§3.1, §2.9).
5. **The day state belongs to `route_day`** (M-32), attendance to the user, final submit to the zone; the device holds a `L:day_local` mirror and shows *device state* and *server state* side by side. Offline day starts are recorded by a `day_open` outbox event so Login % stays truthful even when the bundle came from yesterday (§3.2, §4.5).
6. **Clock skew is corrected, not trusted:** the device stores `server_time_offset_ms` from every response and stamps each row with device time *and* the offset in force; `business_date` is computed from corrected time. A sale at 23:55 Dhaka synced at 00:10 lands on the earlier date, re-aggregates that date, and never touches "today" (§3.4).
7. **Budgets are numbers, not adjectives** (§5): app-attributed non-screen drain ≤ 6 % of a 5,000 mAh battery per scripted 8-h day on a Redmi 9A-class device; background drain ≤ 1 %/8 h; GPS on-time ≤ 15 min/day, ≤ 80 fixes; wake-locks ≤ 10 min/day and ≤ 90 s per sync; mobile data ≤ 1 MB/day without photos, ≤ 3 MB/day with 10 photos; APK ≤ 30 MB per ABI (CI gate), target 22 MB.
8. **Memo number = `<username>-<yyMMdd>-<seq3>`** (confirms D-04), with a device-bind *block* so a phone replaced mid-day cannot collide, kept verbatim by the server; continuity with the Apsis series is a secondary printed `memo_serial` if the business wants it (§7).
9. **Twenty gaps (G-sync-01..20), four blockers:** multi-user shared-phone binding and logout semantics (G-sync-01), the current memo layout is not captured anywhere (G-sync-02), stale-bundle / offline new-day policy is undefined (G-sync-03), and the 3-decimal price → printed rounding rule has no owner (G-sync-04, same root as G-feat-46).

---

## 1. Local store (Drift on SQLite)

### 1.1 Files, isolation, durability

| Item | Decision | Why |
| --- | --- | --- |
| One database file **per user** on the device: `aron_u<user_id>.db` in app-private storage (`getApplicationSupportDirectory()`), plus `aron_device.db` for device-wide state (binding, printer, prefs, per-user index) | **D-sync-01** | Shared phones (docs/11): user A's pending rows must survive user B's login and be uploaded under A's credentials (§8.1). A `user_id` column in a shared file would make "wipe on TSO logout" and "purge after final submit" error-prone. |
| `PRAGMA journal_mode=WAL; synchronous=FULL; foreign_keys=ON; busy_timeout=5000` | fixed | FULL costs ~1 ms per commit on eMMC; a sale must survive a battery pull. WAL lets the WorkManager isolate read while the UI writes. |
| No SQLCipher | ASSUMPTION (G-sync-13) | Adds ~7 MB native code and CPU on every read; Android app-private storage plus file-based encryption on Android ≥ 7 is the baseline. Outlet phone numbers are the only PII on the device; NID/TIN are never in the bundle (docs/09 PII rule). Business/security to confirm. |
| Schema version in `L:sync_meta.schema_version`; Drift `MigrationStrategy` with forward-only steps; **outbox payloads carry `schema_version`** | fixed | App update while rows are pending (§8.2): the server accepts payload schema N-2..N. |
| Size guard: `PRAGMA page_count*page_size` checked at app start; `incremental_vacuum` after the daily purge; refuse new photo capture when free storage < 200 MB (§8.5) | fixed | Low-end phones ship with 16–32 GB mostly full. |

### 1.2 Standard local column block (`@LOCAL`), on every captured table

```sql
client_uuid        TEXT PRIMARY KEY,          -- UUID v4 generated on device at creation (CLAUDE.md conv.)
family_uuid        TEXT NOT NULL,             -- the visit's client_uuid for visit-children; own uuid for roots (attendance, stock, day events)
business_date      TEXT NOT NULL,             -- 'YYYY-MM-DD' Asia/Dhaka from CORRECTED time (§3.4)
captured_at_device INTEGER NOT NULL,          -- device clock, epoch ms UTC
clock_offset_ms    INTEGER,                   -- server_time − device_time known at capture; NULL = never synced
captured_offline   INTEGER NOT NULL,          -- 1 if no validated connectivity at capture
bundle_version     TEXT NOT NULL,             -- bundle in force at capture (§4)
config_version     INTEGER NOT NULL,          -- config in force at capture (R6 audit; lens-data P3)
app_version        TEXT NOT NULL,
sync_state         TEXT NOT NULL DEFAULT 'pending',   -- pending|syncing|synced|rejected|conflict  (§3.1)
attempt_count      INTEGER NOT NULL DEFAULT 0,
last_error_code    TEXT, last_error_at INTEGER,
server_ack_at      INTEGER,                   -- epoch ms when the ACK was applied
```

Rows are **immutable after commit** except the `sync_*`/`attempt_*`/`last_error_*`/`server_ack_at` columns (mirrors lens-data P2). A correction is a new row (`supersedes_client_uuid`, `against_memo_client_uuid`, `*_event`).

### 1.3 Outbox (the one queue)

```sql
CREATE TABLE outbox (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,   -- global local order = send order
  client_uuid    TEXT NOT NULL UNIQUE,
  record_type    TEXT NOT NULL,            -- visit|memo|memo_line|qc_entry|survey_response|drp_collection|due_collection|attendance_event|stock_movement|outlet_change_request|outlet_photo_meta|redemption|redemption_line|gift_photo_meta|task_event|call_assessment|distribution_check|distribution_check_line|price_compliance|content_view|activity_log|day_open|day_submit|config_ack|media_meta|leave_application|visit_plan|visit_plan_outlet|feedback|device_integrity
  family_uuid    TEXT NOT NULL,
  family_rank    INTEGER NOT NULL,         -- 0 parent … n child; see §2.5 ordering table
  business_date  TEXT NOT NULL,
  payload_json   TEXT NOT NULL,            -- immutable snapshot of the row as sent (schema_version inside)
  payload_sha256 TEXT NOT NULL,            -- server stores it in ingest_registry; conflict detection
  state          TEXT NOT NULL DEFAULT 'pending',     -- pending|held|syncing|synced|rejected|conflict
  hold_until     INTEGER,                  -- family hold (§2.2); NULL = none
  attempt_count  INTEGER NOT NULL DEFAULT 0,
  next_attempt_at INTEGER,                 -- backoff (§2.7)
  batch_uuid     TEXT,                     -- batch it was last sent in (reused on resend of an identical set, §2.8)
  last_error_code TEXT, last_error_msg TEXT,
  created_at     INTEGER NOT NULL, acked_at INTEGER
);
CREATE INDEX outbox_state_next ON outbox(state, next_attempt_at, seq);
CREATE INDEX outbox_family     ON outbox(family_uuid);
CREATE INDEX outbox_bdate      ON outbox(business_date, state);
```

**Invariant (tested, T-1-20):** every write of a captured row is `BEGIN; INSERT domain_row; INSERT outbox; COMMIT` in one Drift `transaction()`. The domain row's `sync_state` is updated **from** the outbox (same transaction as the ACK). The outbox is the only thing the sync engine reads; it never joins domain tables to build a payload. Purge: `synced` outbox rows older than `cfg.app.outbox_keep_days` (3) are deleted; domain rows follow the local-history rule (`cfg.app.local_history_days`, F-SYS-028).

### 1.4 Media queue

```sql
CREATE TABLE media_queue (
  client_uuid TEXT PRIMARY KEY,            -- = media_object.client_uuid on the server (M-31)
  purpose     TEXT NOT NULL,               -- outlet_capture|force_sale|base_update|survey|redemption|gift|feedback|support_dump
  ref_type    TEXT, ref_client_uuid TEXT,  -- the record that owns this photo (visit, outlet_change_request, …)
  local_path  TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, width INTEGER, height INTEGER, mime TEXT NOT NULL DEFAULT 'image/jpeg',
  lat REAL, lng REAL, accuracy_m REAL, is_mock INTEGER, captured_at_device INTEGER NOT NULL, business_date TEXT NOT NULL,
  evidence    INTEGER NOT NULL DEFAULT 0,  -- 1 = force-sale / outlet-location evidence → mobile fallback after cfg.media.evidence_mobile_fallback_h
  state       TEXT NOT NULL DEFAULT 'queued',   -- queued|sas_requested|uploading|uploaded|linked|failed|abandoned
  blob_path   TEXT,                         -- deterministic: photos/{business_date}/{device_uuid}/{client_uuid}.jpg (lens-scale §2.6)
  sas_url TEXT, sas_expires_at INTEGER,
  attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER, last_error_code TEXT,
  uploaded_at INTEGER, linked_at INTEGER
);
CREATE INDEX media_state ON media_queue(state, next_attempt_at);
```

The owning record carries `photo_client_uuid` (and the deterministic `blob_path`) at capture, so the record syncs first and the photo follows (docs/04 step 4, G-scale-20). After a successful PUT the device appends a `media_meta` outbox row (sha256, bytes, dims, fix) so the server can mark `media_object.state='linked'` without waiting for Event Grid. Local file deleted only after `linked` ACK **and** `cfg.media.local_keep_days` (2) have passed.

### 1.5 Reference-data cache

| L: table | Mirrors | Key | Notes |
| --- | --- | --- | --- |
| `ref_route`, `ref_route_assignment_today` | route, route_assignment (resolved for the bundle date) | id | includes `visit_days_mask`, `is_planned_today`, `acting_role` (SR/SS) |
| `ref_outlet` | outlet (+ open-due total, loyalty balance, suggested qty, Astha flag, provisional flag) | id (server) or `client_uuid` for locally created, not-yet-approved outlets (G-feat-42) | `lat/lng NULL` allowed (Q7 → §8.11) |
| `ref_open_memo` | memos with `due_mtk>0` for the user's outlets | memo client_uuid | due collection targets; recomputed locally with pending `due_collection` rows |
| `ref_sku`, `ref_price`, `ref_sales_plan`, `ref_offer` | product tree, effective-dated prices, zone plan, promotions | id (+ `valid_from`) | `price_list_date` snapshot stored on each memo (F-SYS-045) |
| `ref_target`, `ref_achievement_mtd` | target + MTD agg for the home strip | scope/product/month | MTD from `dw` at bundle time; local rows add to it |
| `ref_task`, `ref_gift_assignment`, `ref_survey_def`, `ref_rubric_def`, `ref_content` | tasks, Astha gifts, survey questions, assessment rubrics, AV/KV manifest | id | content files via bounded LRU cache (F-SYS-029) |
| `ref_config` | resolved `cfg.*` values for this user's scope | `key, scope_type, scope_id` | plus `config_version` in `sync_meta`; resolution order outlet>route>zone>territory>division>wing>role>global (M-45) |
| `ref_meta` | per section: `section, server_max_updated_at, row_count, fetched_at` | section | drives the `?since=` delta (§4.3) |

### 1.6 Device/session tables

```sql
-- in aron_device.db
device_state (device_uuid TEXT, bound_users_json TEXT, bind_ordinal_json TEXT, printer_mac TEXT, printer_name TEXT, last_app_version TEXT, integrity_json TEXT, free_storage_mb INTEGER, checked_at INTEGER);
user_index   (user_id INTEGER PRIMARY KEY, username TEXT, role TEXT, db_file TEXT, last_login_at INTEGER, pending_rows INTEGER, refresh_token_ref TEXT /* key in flutter_secure_storage */, offline_verifier_ref TEXT);
prefs        (key TEXT PRIMARY KEY, value TEXT);          -- language, wifi_only_photos, etc.

-- in aron_u<user_id>.db
sync_meta    (id INTEGER PRIMARY KEY CHECK (id=1), user_id, username, role, schema_version, bundle_version, bundle_business_date, bundle_fetched_at,
              config_version, config_fetched_at, server_time_offset_ms, offset_measured_at, last_batch_uuid, last_sync_at, last_sync_result_json,
              batch_max_rows_effective INTEGER /* halved on 413 */, consecutive_failures INTEGER, next_attempt_at INTEGER, sync_hold_until INTEGER);
day_local    (route_id, business_date, device_state TEXT, server_state TEXT, opened_at, checked_in_at, first_visit_at, last_visit_at, pending_rows, rejected_rows,
              last_server_counts_json, submitted_local_at, submit_client_uuid, server_submitted_at, final_submitted_at, PRIMARY KEY (route_id, business_date));
memo_counter (business_date TEXT, bind_ordinal INTEGER, next_seq INTEGER, PRIMARY KEY (business_date, bind_ordinal));   -- §7
print_job    (id INTEGER PRIMARY KEY, kind TEXT /* memo|stock|summary */, ref_client_uuid TEXT, attempt INTEGER, state TEXT, error TEXT, at INTEGER); -- §6
sale_draft   (visit_client_uuid TEXT PRIMARY KEY, step TEXT, draft_json TEXT, updated_at INTEGER);                        -- §8.4 kill-and-relaunch
sync_journal (batch_uuid TEXT PRIMARY KEY, row_set_sha256 TEXT, rows INTEGER, bytes_raw INTEGER, bytes_gz INTEGER, trigger TEXT, network_type TEXT, battery_pct INTEGER,
              started_at INTEGER, finished_at INTEGER, http_status INTEGER, result TEXT, duration_ms INTEGER, replayed INTEGER);
geo_fix_local(fix_uuid TEXT PRIMARY KEY, purpose TEXT, lat, lng, accuracy_m, is_mock INTEGER, provider TEXT, fix_age_ms INTEGER, time_to_fix_ms INTEGER, at_device INTEGER); -- sent as part of the owning record; feeds M-17 geo_fix
```

### 1.7 Local footprint (50-outlet route, worst case kept 7 days)

| Component | Per day | 7-day retention |
| --- | --- | --- |
| Captured rows (visit 55, memo 50, lines 250, qc 50, survey 30, drp 20, dues 10, attendance 2, stock 45, events 10) ≈ 520 rows × ~400 B incl. outbox snapshot | ~0.4 MB | 3 MB |
| Reference cache (300 outlets p95, prices, offers, targets) | 0.5 MB (replaced, not accumulated) | 0.5 MB |
| Photos awaiting Wi-Fi (10 × 150 KB) | 1.5 MB | ≤ 10 MB (cap `cfg.media.local_queue_max_mb` = 50) |
| Image cache LRU (`cfg.app.image_cache_mb` = 40) | — | 40 MB |
| **Total** | | **< 60 MB** on disk; the DB itself < 10 MB |

---

## 2. "Immediate when online" (R5) without burning battery (R4)

### 2.1 Trigger matrix

| # | Trigger | Mechanism | Runs where | Cost | Notes |
| --- | --- | --- | --- | --- | --- |
| T1 | Local write (any outbox append) | Debounce timer in the app process: fires `cfg.sync.trickle_debounce_s` (5) after the **last** append; held rows excluded (§2.2) | Dart isolate in the running app | one HTTPS POST per visit, ~2 KB gz | The sponsor's "immediately"; the radio is already awake for the sale |
| T2 | App to foreground | `AppLifecycleState.resumed` → run if outbox non-empty **or** last refresh older than `cfg.bundle.delta_min_interval_min` (30) | app | one POST (+ one GET delta at most twice an hour) | Also re-reads connectivity |
| T3 | Connectivity regained while app alive | `connectivity_plus` stream → validate (a 1-KB `HEAD /health`, 3 s timeout) → run | app | negligible | Captive/dead Wi-Fi is treated as offline until validated |
| T4 | Connectivity regained while app is **not** running | WorkManager **one-off** task enqueued whenever a sync attempt fails for lack of network, with `NetworkType.CONNECTED` (+ `requiresBatteryNotLow`), `ExistingWorkPolicy.KEEP`, `BackoffPolicy.EXPONENTIAL` | WorkManager isolate (`workmanager` plugin `callbackDispatcher`) | the OS wakes us once, when the constraint is met | **This is R5 at zero polling cost.** Expedited (`setExpedited`) on Android 12+ so Doze does not defer it; quota-aware fallback to normal |
| T5 | Periodic fallback | WorkManager periodic, `cfg.sync.periodic_min` = 15 (platform minimum), `NetworkType.CONNECTED`, `requiresBatteryNotLow`, flex 5 min | WorkManager isolate | < 50 ms CPU when outbox empty (one indexed `SELECT 1 … LIMIT 1`) | Catches OEM-killed processes and missed callbacks; registered only while `pending_rows > 0` **or** media queued; cancelled otherwise |
| T6 | Manual "Sync" tile / pull-to-refresh | user | app | one POST + one GET | Always allowed; shows the reconciliation screen (F-SYS-009) |
| T7 | End-of-day: Sales Submit pressed | appends `day_submit` event → T1 fires immediately (no debounce) | app | one POST | Device shows "submitted (uploading)" until ACK (§3.2) |
| T8 | Wi-Fi connected (media) | `connectivity_plus` reports Wi-Fi **and** `media_queue` non-empty → media worker; also a WorkManager one-off with `NetworkType.UNMETERED` when queue non-empty and the toggle is Wi-Fi-only | app or WorkManager | photos only | `cfg.media.wifi_only_default` = true; user toggle F-SYS-037 |

Explicitly **not** used: foreground service, persistent socket/WebSocket, FCM-triggered uploads (FCM only for config pings if Q22 says yes), `AlarmManager` timers, `setInexactRepeating` shorter than 15 min, location-based geofence callbacks.

### 2.2 Debounce and the family hold

- A visit's children (memo, lines, QC, survey, DRP, due collection in the same call) are written over 1–4 minutes. Pushing the visit alone after 5 s and then its memo later costs two POSTs and produces a visible "visited, no sale" window on the Live Dashboard. So: an outbox row whose `family_uuid` is an **open** visit is inserted with `state='held'`; when the visit closes (memo printed / zero sale confirmed / call abandoned per §8.4) all its rows flip to `pending` in the same transaction and the debounce timer starts. `hold_until = now + cfg.sync.family_hold_max_s` (180) is the safety valve: if the SR leaves a visit open, its rows are released anyway (the visit is then marked `outcome='abandoned'` locally by the abandonment rule, §8.4).
- Reconciliation with lens-scale: lens-scale §1.6 proposed `cfg.sync.trickle_debounce_s` = 10 to coalesce a family. With the family hold, coalescing no longer depends on the timer, so this lens sets the default to **5 s** (bounds 2–60) for a tighter R5 while keeping lens-scale's load numbers (one batch per visit) unchanged. Flagged as a reconcile item in the merged plan (G-sync-14).
- Single-flight: a process-wide mutex; if a trigger fires during a run, a `rerun_requested` flag causes one more pass. Between the app process and the WorkManager isolate, a file lock (`aron_sync.lock`, `flock`) prevents two concurrent batches from one device; the loser exits immediately.

### 2.3 Batch builder

| Rule | Value | Why |
| --- | --- | --- |
| Rows per batch | ≤ `cfg.sync.batch_max_rows` (200; bounds 50–500); effective value halves on HTTP 413 and recovers by +25 % per success up to the config | lens-scale §1.7 sizing |
| Bytes per batch | ≤ `cfg.sync.batch_max_kb_raw` (256 KB uncompressed) | keeps one POST under ~60 KB gz even on 2G |
| Family atomicity | the builder adds whole families; it never splits a family across batches even if that overshoots `batch_max_rows` by one family (a family is ≤ ~40 rows) | makes `parent_missing` impossible from our own client |
| Order | by `outbox.seq` (creation order) → within a family by `family_rank` (§2.5) | parents before children; chronological across visits |
| Priority classes | `day_submit`, `attendance_event`, `day_open` first; then visit families oldest-first; `activity_log`/`content_view` last and only when the batch has room | the Live Dashboard and Submit % reflect the day earliest |
| Reuse of `batch_uuid` | if the chosen row set equals `sync_journal.row_set_sha256` of the last unfinished batch → reuse its `batch_uuid`; else new UUID v4 | lets the server replay instead of re-ingest (§2.8) |

### 2.4 Wire contract — `POST /sync/batch`

```
POST /sync/batch                 Content-Type: application/json; Content-Encoding: gzip; Accept-Encoding: gzip
Authorization: Bearer <access>   X-Batch-UUID: <uuid>   X-Device-UUID: <uuid>   X-App-Version: 1.4.2+142   X-Schema-Version: 7
X-Config-Version: 318   X-Bundle-Version: 2026-10-04:3   X-Device-Time: 2026-10-04T05:12:44.120Z   X-Network-Type: cellular|wifi   X-Battery-Pct: 63   X-Sync-Trigger: sale_saved|foreground|connectivity|periodic|manual|end_of_day

{ "batch_uuid": "…", "device_uuid": "…", "user_id": 334001, "app_version": "…", "schema_version": 7,
  "device_time": "…", "business_date_device": "2026-10-04", "trigger": "sale_saved", "network_type": "cellular", "battery_pct": 63,
  "device_counts": { "2026-10-04": { "visit": 23, "memo": 21, "memo_line": 104, "qc_entry": 21, "survey_response": 12, "drp_collection": 6, "due_collection": 2, "attendance_event": 1, "stock_movement": 38, "outlet_change_request": 1, "redemption": 0 } },
  "records": [
    { "type": "visit",     "client_uuid": "v1", "family_uuid": "v1", "rank": 0, "captured_at_device": "…", "clock_offset_ms": -1840, "business_date": "2026-10-04", "bundle_version": "…", "config_version": 318, "payload": { … } },
    { "type": "memo",      "client_uuid": "m1", "family_uuid": "v1", "rank": 1, "payload": { "visit_client_uuid": "v1", "memo_no": "sr334001-261004-017", … } },
    { "type": "memo_line", "client_uuid": "l1", "family_uuid": "v1", "rank": 2, "payload": { "memo_client_uuid": "m1", "line_no": 1, … } },
    …
  ] }
```

Deviation from docs/09 (allowed: "the new system defines its own API"): records are one **ordered flat list** with a `type` field instead of per-type arrays, because ordering across types (visit → memo → memo_line → qc) is the point. The server groups by type internally and still tolerates out-of-order via parking (lens-data §2). F-API-006 should be updated to this shape (noted in G-sync-15).

```
200 OK   X-Config-Version: 319   X-Bundle-Version-Current: 2026-10-04:3   X-Server-Time: …
{ "batch_uuid": "…", "replayed": false, "server_time": "…", "config_version": 319,
  "accepted": { "visit": 1, "memo": 1, "memo_line": 5, "qc_entry": 1 },
  "rejected":  [ { "client_uuid": "s7", "type": "survey_response", "reason_code": "unknown_question", "retryable": false, "detail": "question_key POSM_09 not in survey v3" } ],
  "conflicts": [ { "client_uuid": "m0", "type": "memo" } ],
  "parked":    [ { "client_uuid": "l9", "type": "memo_line", "waiting_for": "m9" } ],
  "server_totals": { "2026-10-04": { "visit": 23, "memo": 21, "memo_line": 104, "qc_entry": 21, "survey_response": 11, "drp_collection": 6, "due_collection": 2, "attendance_event": 1, "stock_movement": 38, "outlet_change_request": 1, "redemption": 0, "rejected": 1, "conflict": 1 } },
  "day_states": [ { "route_id": 10231, "business_date": "2026-10-04", "state": "synced", "sales_submitted_at": null, "final_submitted_at": null } ],
  "hold_s": 0 }
```

`server_totals` are the server's cumulative counts for this user and business date(s) present in the batch, so the reconciliation screen (F-SYS-009) is filled from the same response without a second request. Rejected and conflict rows are counted separately so `device == accepted + rejected + conflict` is the equality the screen checks.

### 2.5 Ordering within a family (`family_rank`)

| Rank | Types | Parent reference carried in payload |
| --- | --- | --- |
| 0 | `visit` | — (carries `outlet_id` or `outlet_client_uuid` for provisional outlets, `route_id`, fix) |
| 1 | `memo`, `survey_response`, `drp_collection`, `qc_entry`, `distribution_check`, `call_assessment`, `content_view` | `visit_client_uuid` |
| 2 | `memo_line`, `distribution_check_line`, `due_collection` (when taken during a call) | `memo_client_uuid` / `check_client_uuid` / `against_memo_client_uuid` |
| 3 | `media_meta` | `ref_client_uuid` |
| root families | `attendance_event`, `stock_movement`, `day_open`, `day_submit`, `config_ack`, `outlet_change_request` (+ its `outlet_photo_meta`), `redemption` (+ lines), `task_event`, `leave_application`, `visit_plan`, `feedback`, `activity_log` | own `family_uuid` |

### 2.6 Response handling (per HTTP outcome)

| Outcome | Device action | Row state effect | Backoff |
| --- | --- | --- | --- |
| **200** | apply ACK (§2.9); update `server_time_offset_ms`, `config_version` check (§4.6), `day_local.server_state`, `sync_journal`; `consecutive_failures=0` | per row | none; if more pending → next batch immediately |
| **200 `replayed:true`** | identical to 200 (the server returned the stored response for this `batch_uuid`) | per row | — |
| **400** malformed body / unknown `schema_version` | mark batch `failed_permanent` in journal; rows back to `pending` with `last_error_code='malformed'`; **stop automatic retries for this row set until app version changes**; surface "update required" | pending | — |
| **401** | `POST /auth/refresh`; on success retry the same `batch_uuid` once; on refresh failure keep rows `pending`, set `needs_relogin=true` (UI banner), **selling continues** (F-SYS-002) | pending | until re-login |
| **403** device unbound / user disabled / wrong wave (`reason` in body) | stop sync for this user; banner with reason; rows stay | pending | until binding fixed |
| **409** `batch_uuid` seen with a **different** `row_set_sha256` (client bug) | generate a new `batch_uuid`, resend once; if 409 again → `failed_permanent` + telemetry | pending | — |
| **413** | `batch_max_rows_effective = max(25, floor(n/2))`; rebuild; resend immediately | pending | — |
| **422** validation errors for the whole batch (e.g. `business_date` out of window for every row) | treat body like 200's `rejected[]` | per row | — |
| **429** / **503 with `Retry-After`** / body `hold_s>0` (`cfg.ops.sync_hold_s`) | sleep `Retry-After` (± 20 % jitter), max 15 min; counts as a failure for T4 handoff but not for `attempt_count` | syncing→pending | server-directed |
| **5xx** other / TLS error / timeout / connection reset / DNS failure | rows `syncing→pending` (same `batch_uuid` kept); `consecutive_failures++`; schedule per §2.7 | pending | exponential |
| **No network** (OS says disconnected or validation HEAD fails) | no attempt; enqueue T4 one-off; rows stay `pending` | pending | none (constraint-driven) |

### 2.7 Backoff

- In-process retry schedule: `delay = min(cap, base × 2^k) × U(0.5, 1.0)` with `base = cfg.sync.retry_base_s` (2), `cap = cfg.sync.retry_cap_s` (300), k = `consecutive_failures − 1`; sequence ≈ 2, 4, 8, 16, 32, 64, 128, 256, 300 s.
- After `cfg.sync.retry_max_inprocess` (5) consecutive failures the engine stops in-process retries and **hands over** to WorkManager (T4 with network constraint + T5 periodic), as lens-scale §7.1 specifies ("max 5 attempts then WorkManager"). WorkManager's own backoff is exponential from 30 s, capped by the OS at 5 h; our periodic task keeps a 15-min ceiling.
- Per-row `attempt_count` increments only on `rejected(retryable)` reprocessing, not on transport failures (a transport failure is not the row's fault). A row with `attempt_count ≥ cfg.sync.row_max_retries` (10) becomes `rejected(reason='retry_exhausted')` and is surfaced.

### 2.8 Half-uploaded batch, dropped response, and resume

| Case | What happened | Device behaviour | Server behaviour | Converges because |
| --- | --- | --- | --- | --- |
| Body never reached the server (connection reset during upload) | nothing stored | rows `syncing→pending`, same `batch_uuid`; resend | first sight of `batch_uuid` → ingest | — |
| Body received, server processed, response lost | rows are in Postgres; `sync_batch.response` stored | resend same `batch_uuid` (row set unchanged) | `replayed:true` with the stored response (D-scale-4) | device applies the same ACK |
| Body received, server crashed mid-transaction | transaction rolled back; nothing stored | as above | ingest as new | — |
| App killed mid-send | rows left in `syncing` | at next engine start: rows in `syncing` for > 2 min → `pending`; `batch_uuid` reused if row set unchanged | either replay or ingest | — |
| New rows captured between the failed send and the resend | row set changed | new `batch_uuid` for the new set | old `batch_uuid` may or may not be stored; new batch rows are deduped **row-by-row** via `ingest_registry` (`client_uuid`) | row-level idempotency |
| Batch partially accepted (some rows rejected) | normal | rejected rows handled per §2.9; others `synced` | — | — |
| Server received the batch twice concurrently (T1 and T4 race across isolates) | prevented by the file lock (§2.2); if it still happens | — | `sync_batch.batch_uuid UNIQUE` → second request waits on the row lock then replays | — |

### 2.9 Applying the ACK and surfacing rejections

Algorithm (one local transaction):
1. For each sent `client_uuid` **not** in `rejected`, `conflicts` or `parked` → `outbox.state='synced'`, `acked_at=now`; domain row `sync_state='synced'`, `server_ack_at=now`.
2. `parked` → stays `pending` (the server will auto-accept when the parent lands; the device also re-sends the parent, which the registry dedupes). Only possible for rows written by a different client version; counted in telemetry.
3. `rejected` with `retryable=true` (`scope_stale`, `price_list_unknown`, `config_version_unknown`, `outlet_pending_approval`) → `pending`, `attempt_count++`, `next_attempt_at = after next bundle/config refresh` (the engine triggers a delta GET first).
4. `rejected` with `retryable=false` (`unknown_sku`, `unknown_outlet`, `business_date_out_of_window`, `schema_invalid`, `duplicate_attendance`, `retry_exhausted`…) → `rejected` (terminal on device). `conflicts` → `conflict` (terminal).
5. Update `day_local` from `day_states` and `server_totals`; recompute `pending_rows`, `rejected_rows` per route-day.
6. Bump `sync_meta.last_sync_at/result`; if `config_version` in the response > local → schedule config delta (§4.6); if `X-Bundle-Version-Current` > local → schedule bundle delta (§4.3).

Surfacing, without blocking the day:

| Audience | Where | What they see | Action available |
| --- | --- | --- | --- |
| SR (device) | Sync tile badge + reconciliation screen row "needs attention (n)" + the affected memo in the Memo list marked with a warning icon | Bangla reason text from a code→string table (`cfg.sync.reason_texts` ships in the bundle so new codes need no release), memo number, outlet, amount | "Show details", "Retry after refresh" (for retryable), "Report to supervisor" (adds a note into the next `activity_log`). The paper memo already exists; money changed hands; nothing is deleted |
| AMO (app) | Team Performance / Live Dashboard: "quarantined records" count per route (from `sync_rejected`); Task badge if the admin reassigns a fix to the AMO | per-record list (read-only) | Verify/annotate; the admin resolves |
| Admin (web) | Quarantine panel (F-ADM-030) over `sync_rejected` / `sync_conflict`: accept-with-fix (e.g. map an unknown SKU), re-run, discard-with-reason; everything audited | row payload, reason, DQ rule, history | resolve; resolution re-ingests through the same path (lens-data P9) |
| Sales Submit | allowed when `pending_rows == 0` for the route-day; `rejected_rows` are recorded on the submit event (`rejected_count`) and shown in the warning dialog with the dues warning | — | the day closes; the quarantine queue is the supervisor's job, not the SR's |

### 2.10 Transport details

| Item | Setting |
| --- | --- |
| HTTP client | one `dio` instance per process with `dart:io` `HttpClient` (`idleTimeout` 60 s, `maxConnectionsPerHost` 2, keep-alive on), TLS session resumption; HTTP/1.1 (Front Door terminates; HTTP/2 brings nothing for one POST per visit) |
| Compression | request bodies gzip level 6 (`Content-Encoding: gzip`); `Accept-Encoding: gzip`; bundle/delta served pre-gzipped from Blob (D-scale-3) |
| Timeouts | batch: connect 10 s, send 30 s, receive 30 s; bundle full: receive 90 s; media PUT: 60 s per 150 KB (retry per blob) |
| Certificates | Front Door managed cert with a DigiCert chain (trusted on Android 7); **no pinning** (rotation on 8,500 shared phones is not operable); Android 7.0 trust-store caveat tested in T-2-34 (G-scale-14) |
| Headers | listed in §2.4; `X-Device-Time` lets the server compute skew without trusting the body |
| Payload hygiene | no PII beyond outlet phone in `outlet_change_request.proposed`; no free-text logs in batches; `activity_log` rows ≤ 120 B each, ≤ 50 per batch |

### 2.11 Media upload path

1. `media_queue.state='queued'` → eligibility: Wi-Fi, **or** mobile allowed (`prefs.wifi_only_photos=false`), **or** `evidence=1` and `now − captured_at > cfg.media.evidence_mobile_fallback_h` (6) and mobile data available, **or** user pressed "upload photos now".
2. `POST /media/upload-url` `{client_uuid, purpose, sha256, bytes, business_date}` → `{sas_url, blob_path, expires_at}` (user-delegation SAS, write-only, 15 min, pinned to the path — lens-scale §2.6). Batch up to 10 requests in one call (`POST /media/upload-urls`) to save round trips.
3. `PUT sas_url` with `x-ms-blob-type: BlockBlob`, `Content-Type: image/jpeg`, `x-ms-blob-content-md5`; 2 concurrent uploads max on Wi-Fi, 1 on mobile.
4. Success → `uploaded`, append `media_meta` outbox row → next trickle batch → server marks `linked` → ACK → `linked`. 403 (SAS expired) → re-request URL. 5xx/timeout → backoff as §2.7, independent of the record queue. `attempt_count ≥ 20` → `abandoned` + surfaced ("photo could not be uploaded; keep the phone on Wi-Fi") and listed on the sync-health screen as photo-pending.

---

## 3. State machines

### 3.1 Per record (`outbox.state` mirrored to the domain row's `sync_state`)

```
            capture (closed family)            send                     ACK accepted
  [new] ──────────────────────────▶ pending ──────────▶ syncing ─────────────────────────▶ synced ──(purge after keep_days)──▶ [gone]
    │  capture (open visit family)     ▲                 │  │  transport failure / 5xx / 429 / 401 / app killed
    └──────────▶ held ─────────────────┘                 │  └────────────────────────────────────────▶ pending   (same batch_uuid if row set unchanged)
         visit closed OR hold_until                      │
                                                         ├── ACK rejected(retryable=true) ───▶ pending (attempt_count++, after refresh)
                                                         ├── ACK rejected(retryable=false) ──▶ rejected   (terminal; surfaced §2.9)
                                                         ├── ACK conflict ────────────────────▶ conflict   (terminal; surfaced)
                                                         └── ACK parked ──────────────────────▶ pending    (resend with parent)
  pending ── attempt_count ≥ row_max_retries ──▶ rejected(retry_exhausted)
```

| Guard / rule | Detail |
| --- | --- |
| Only `pending` rows with `next_attempt_at ≤ now` and `hold_until IS NULL or ≤ now` are eligible for a batch | — |
| `syncing` is transient; any engine start converts `syncing` older than 120 s to `pending` | handles kill mid-send |
| A domain row shows `synced` only after its own ACK, never because its parent synced | reconciliation counts are per row |
| `rejected`/`conflict` rows are never deleted by purge until `cfg.app.rejected_keep_days` (30) and never while unacknowledged by the SR ("seen" flag) | evidence |
| A locally superseded memo (edit) keeps its own state; the superseding memo is a new row | lens-data P2 |

### 3.2 Per route-day (resolves seed #10; aligned with M-32 `route_day`)

Entity ownership (**D-sync-02**):

| State (docs/04) | Owning entity | Set by (server) | Device mirror (`day_local.device_state`) and trigger |
| --- | --- | --- | --- |
| `not_started` | `route_day(route_id, business_date)` — one row per planned route per day, created by the 04:00 pre-generation job (D-scale-3) or on first contact | job | `no_bundle` (no bundle valid for this date on device) |
| `logged_in` | `route_day` | first `bundle_download` of the date for a user assigned to the route (D-08), **or** a `day_open` event captured offline on a stale bundle (§4.5) | `ready` — set when the device opens the date: bundle for the date loaded, or stale bundle accepted; appends `day_open{route_ids, bundle_version, online}` |
| `in_field` | `route_day`, driven by a **user**-level fact | first `attendance_event(kind=in)` of the user that day sets every planned route_day of that user to `in_field` (`first_check_in_at`); the first `visit` on the route fills `first_visit_at` | `checked_in` → `in_field` on first visit |
| `synced` | `route_day` | first accepted batch containing ≥ 1 row for that route-day (`first_sync_at`), `last_sync_at` on every later one | `uploaded` when `pending_rows==0` for the route-day; `pending_upload` otherwise |
| `sales_submitted` | `route_day` | `day_submit` event accepted; server verifies the device's claim `pending_rows==0` against its own counts for the route-day and stores `submitted_with_dues`, `dues_at_submit_mtk`, `rejected_count` | `submitted_local` (event pending) → `submitted` (ACK) |
| `final_submitted` | **zone** (`final_submit(zone_id, business_date)`, M-34) projected onto every `route_day` of the zone | TSO final submit; `final_submit_route` snapshots each route's state at that moment | `final_submitted` when seen in `day_states` of a sync response or in a bundle delta |
| attendance (check-in/out) | **user** (`attendance_event`, M-12) | not a route state; the 17:00 gate uses corrected time (§3.4) | `attendance` tile state |

Transitions and rules:

| From → To | Trigger | Rule |
| --- | --- | --- |
| `not_started → logged_in` | bundle download or `day_open` | Later deltas never change `logged_in_at` (D-08). An SR assigned to two routes gets two `route_day` rows flipped by one bundle. A substitute (SS) opening the route sets `acting_user_id`. |
| `logged_in → in_field` | check-in / first visit | Check-in without any visit still counts as in_field (the SR is at work). |
| `in_field → synced` | first accepted batch | A route with zero rows at 17:00 stays `in_field`; the Daily Tracking dashboard lists it. |
| `synced → sales_submitted` | `day_submit` accepted | Server-side guard: if `server_totals` for the route-day differ from the device's `device_counts` claimed in the submit event, the submit is **still accepted** but flagged `submit_count_mismatch` (the SR cannot be stuck in the field over a count), and the route appears on the sync-health screen. |
| `sales_submitted → final_submitted` | TSO final submit for the zone | Routes not yet `sales_submitted` are also set to `final_submitted` with `state_at_submit` recorded, so Submit % is frozen as of that moment. |
| `final_submitted → synced` (reopen) | admin action `route_day.reopened_*` (Q11) | Rows arriving after final submit are **accepted and flagged `after_final_submit`**, aggregated into their business_date, and listed in a "late data" report; the zone's final submit is not reverted automatically (G-feat-17). |
| any → `not_started` next day | midnight Dhaka | a new `route_day` row for the new date; the old row is never mutated by the new day. |

Device/server disagreement is displayed, not hidden: the Home header shows `device_state` and, when known, `server_state` (e.g. "জমা হয়েছে (সার্ভারে পৌঁছেনি)" = submitted, not yet on server).

### 3.3 Per device session

```
 [fresh install] ─install→ unbound ─(login + TSO OTP: POST /auth/bind-device)→ bound
 bound ─(POST /auth/login or /auth/refresh online)→ authenticated ─(GET /sync/bundle 200)→ day_ready(date D)
 bound ─(offline, cached verifier ok, refresh token not expired, bundle age ≤ stale_max_days)→ day_ready_stale(D) ──(online later)──▶ day_ready (delta applied)
 bound ─(offline, no bundle or bundle too old)→ blocked_no_bundle  (view-only: previous memos, dues list; no new sales)  [G-sync-03]
 day_ready ─(check-in)→ working ─(Sales Submit)→ day_closed_local ─(ACK)→ day_closed
 any ─(logout SR/AMO)→ bound (DB kept; pending rows still uploaded by the engine under this user's refresh token)
 any ─(logout TSO)→ wipe requested ─(pending==0 or PDA-to-Support uploaded)→ unbound-for-user (DB deleted)   [F-SYS-022]
 any ─(401 and refresh fails)→ needs_relogin (capture continues; sync paused for this user)
 any ─(403 device unbound)→ unbound (capture continues; sync paused; banner)
```

| Element | Specification |
| --- | --- |
| Daily login | refresh-token exchange (D-scale-8); password only on first use, expiry (`cfg.auth.refresh_ttl_days` 30) or password change |
| Offline unlock | local verifier `PBKDF2-HMAC-SHA256(password, device_salt, 100k)` in `flutter_secure_storage`, written on each successful online login; valid while the refresh token is unexpired; a server-side password change invalidates at the next online refresh (401 → forced online login) |
| Bundle at login | `GET /sync/bundle` with `If-None-Match: <bundle_version>` → 304 or full; then `?since` deltas (§4.3) |
| Delta refresh | on foreground if last refresh > 30 min; after any sync response advertising a newer bundle/config version; manual |
| Logout | SR/AMO: session ends, local DB kept (**D-sync-03**; ASSUMPTION — the current SR app's behaviour on logout with unsynced data is not documented; confirm). TSO: wipe per docs/08, refused with the count while `pending_rows>0` unless "PDA to Support" has uploaded the DB (G-feat-41) |
| Device integrity snapshot | at login/bundle: mock-location apps installed (`Settings.Secure.ALLOW_MOCK_LOCATION` is gone post-M; use `isMocked` per fix + package scan for known mock apps), developer options on, rooted hint, Play Integrity verdict (optional, `cfg.geo.integrity_check` off by default in pilot) → `device_integrity` outbox row (F-SYS-031) |

### 3.4 Time: business date, midnight, clock skew

| Rule | Specification |
| --- | --- |
| Corrected time | `t_corr = t_device + server_time_offset_ms` where the offset is measured on every response (`X-Server-Time` minus device time at receipt, minus half the RTT); stored with `offset_measured_at`. If never measured → offset unknown, `clock_offset_ms = NULL` on rows → server flags `clock_unknown`. |
| `business_date` | `date(t_corr in Asia/Dhaka)` at capture, written on the row; also sent as `captured_at_device` + `clock_offset_ms` so the server recomputes `business_date_server` (lens-data P7) and flags mismatches (DQ-09/11). |
| Skew threshold | `abs(offset) > cfg.sync.max_clock_skew_min` (10 min) → banner "ফোনের সময় ঠিক করুন" with a deep link to date/time settings; capture continues using corrected time; rows flagged `clock_skew`. |
| 17:00 check-out gate | evaluated on **corrected** time (`cfg.day.checkout_earliest_time`); if offset unknown, use device time and flag `checkout_unverified_clock`. |
| Midnight while offline | At 00:00 Dhaka (corrected) nothing is mutated. A capture after midnight is stamped with the **new** business date (never back-dated) and belongs to a new `day_local` row; if the open day (yesterday) is not submitted, Home shows both days: "গতকালের দিন জমা হয়নি" with a Submit button for yesterday (allowed up to `cfg.day.submit_grace_h` 10 h after midnight, then only via admin reopen — Q11) and today's route (stale-bundle rules §4.5). |
| 23:55 sale, 00:10 sync | row: `business_date=D`, `captured_at` 23:55 D; batch at 00:10 D+1 with `business_date_device=D+1` in the envelope (the envelope date is informational only). Server: ingest into partition of D, `route_day(D)` moves to `synced`, outbox marks `(D, route)` dirty → aggregates for D recompute (D-scale-2). `route_day(D+1)` untouched. Tested in T-1-27. |
| Device date far wrong (e.g. 2009) | server rejects rows with `business_date` outside `[today−cfg.sync.max_backdate_days (7), today+1]` as `business_date_out_of_window` (DQ-09) → **retryable=false**, but because the device stamped `clock_offset_ms`, the server can recompute the true date; so the rule is refined: if `clock_offset_ms` is present and the corrected date is in-window, accept with flag `clock_corrected`; otherwise quarantine for admin fix. |
| Daylight-saving | none in Bangladesh; `Asia/Dhaka` fixed +06:00; still use the IANA zone, not a constant. |

---

## 4. Reference bundle and config propagation

### 4.1 Contents (per user, all assigned routes for the date; sizes from lens-scale §1.4)

| Section | Rows (median / p95) | Includes | Delta-able |
| --- | --- | --- | --- |
| `meta` | 1 | `bundle_version` (`<business_date>:<snapshot_seq>`), `valid_for_business_date`, `generated_at`, `server_time`, `config_version`, `schema_version`, per-section `max_updated_at` | — |
| `routes`, `assignments` | 2 / 4 | planned-today flag, visit days, acting role | yes |
| `outlets` | 110 / 300 | code, name (bn), owner, phone, lat/lng (nullable), cluster, channel/sub-channel/geo class, status, open-due total, loyalty balance, suggested qty, Astha flag, pending-request flag | yes (+ tombstones) |
| `open_memos` | 30 / 90 | memo client_uuid, memo_no, date, net, due | yes |
| `products`, `prices`, `sales_plan` | 42 + 57 / 210 / 42 | tree, effective-dated prices (today's + next change if within 7 days), zone plan | yes |
| `offers` | 22 | rule JSON with `valid_from/to`, version | yes |
| `targets`, `achievement_mtd` | 126 | per route × product level | yes (achievement refreshed in deltas) |
| `astha_targets`, `gift_assignments`, `tasks`, `survey_defs`, `rubric_defs`, `content_manifest` | ~130 | — | yes |
| `config` | ~60 keys | **resolved** values for this user's scope at each level that matters (outlet overrides inline on outlets) + `reason_texts` | yes (§4.6) |
| `day_states` | 2 | `route_day` rows for today and yesterday (for the unsubmitted-yesterday case) | yes |
| **Size** | | **~110 KB raw / ~25 KB gz median; ~250 KB / ~60 KB p95**; budget: full ≤ 300 KB gz hard cap (server alerts above), delta ≤ 20 KB gz | |

### 4.2 Pre-generation and serving (confirms D-scale-3)

- 04:00 Dhaka job (`cfg.bundle.pregen_time`) builds one gzip JSON per **user** (not per route — the user's routes differ by day) into Blob `bundles/{business_date}/{user_id}.json.gz`, with `bundle_version = <business_date>:<seq>`; Redis caches the hot path before wave 1 (D-scale-6).
- Invalidation: assignment, outlet, price, offer, target or config change touching a scope → the affected users' snapshots are regenerated by the worker (debounced 60 s) and `snapshot_seq++`; the live `?since` path remains correct regardless.
- `GET /sync/bundle` → `304` if `If-None-Match` matches; else 200 with the snapshot. Automatic morning refreshes jitter by `cfg.sync.login_jitter_s` (120); a user-initiated login never waits.

### 4.3 Delta protocol

```
GET /sync/bundle?since=<max_updated_at ISO>&bundle_version=<local>   If-None-Match: <local bundle_version>
→ 304 Not Modified                                        nothing changed anywhere
→ 200 { meta{…new bundle_version…}, outlets:{upsert:[…], delete:[ids]}, prices:{…}, … , day_states:[…] }   changed rows per section since `since`
→ 410 Gone                                                `since` older than cfg.bundle.delta_max_age_h (72) or snapshot lineage broken → device does a full GET
→ 409 { reason: "new_business_date" }                     the device's bundle is for an older business date → full GET for today (keeps yesterday's cache for the unsubmitted-day case)
```

Rules: deltas are applied in one local transaction; each section's `ref_meta.server_max_updated_at` advances; a delta never counts as a login (D-08); price/offer changes apply to **new** memos only (G-feat-52) and the memo records `price_list_date`; an outlet moving route mid-day keeps today's visits on the route captured (`visit.route_id` at capture, seed #3).

### 4.4 Delta budget and cadence

| Source | Max per day | Typical bytes |
| --- | --- | --- |
| Foreground refresh (≥ 30 min apart) | ~8 | 304 (≈ 300 B) or ≤ 5 KB |
| Triggered by newer version in a sync response | as changes happen; ≤ 1 per 5 min (`cfg.bundle.delta_min_interval_version_min`) | ≤ 5 KB |
| Manual | unlimited | — |
| **Budget** | ≤ 15 deltas/day, ≤ 100 KB/day | |

### 4.5 Stale bundle: what the app does (G-sync-03, G-scale-05)

| Situation at day open (corrected Dhaka date = D) | Behaviour | Marks |
| --- | --- | --- |
| Online | full/delta fetch; `logged_in` | — |
| Offline; cached bundle `valid_for_business_date = D` (fetched earlier today or pre-fetched) | normal | — |
| Offline; cached bundle age `D − valid_for ≤ cfg.bundle.stale_max_days` (2) | **Sell anyway** with yesterday's prices/offers/targets (ASSUMPTION: a wrong price is far rarer and cheaper than a lost selling day). Banner "পুরনো তালিকা ব্যবহার হচ্ছে". `day_open{online:false, bundle_version(stale)}` appended → server sets `logged_in` with flag `offline_start` when it arrives (Login % stays truthful) | every row `bundle_stale=true` (derived server-side from `bundle_version` vs effective lists); DQ flag `stale_price` if the outlet price list changed between bundle date and capture; memo totals are **kept as printed** |
| Offline; bundle older than `stale_max_days` or no bundle | `blocked_no_bundle`: Home shows memos/dues read-only, attendance check-in allowed (user-level), no visits/sales. Retry button; T4 job fires the moment network returns | `day_open` is not appended; the route stays `not_started` |
| Route plan says "not planned today" but SR insists (ad-hoc) | allowed if `cfg.route.allow_unplanned_day` (true); visits flagged `unplanned_day` (G-feat-10) | — |

Pre-fetch: when online at ≥ 20:00 Dhaka (after Sales Submit) or on Wi-Fi, the app fetches **tomorrow's** bundle if the snapshot exists (`GET /sync/bundle?for=<D+1>`; the 04:00 job can be moved earlier to 22:00 for "tomorrow" snapshots — `cfg.bundle.pregen_time` becomes `pregen_for_next_day_time` = 22:00; ASSUMPTION, cheap and removes most stale-bundle mornings for Wi-Fi-only phones, §8.8).

### 4.6 Config propagation (R6 → field within one sync cycle)

1. `cfg.*` values live in `cfg.config_value` (M-45); every change bumps `cfg.config_version_seq` and writes `config_change_audit`.
2. **Every** API response carries `X-Config-Version` (and the batch body repeats it). The device compares with `sync_meta.config_version`.
3. If newer: `GET /config?since=<local_version>` → `{config_version, changes:[{key, scope_type, scope_id, value, effective_from, requires_ack}], removed:[…]}` → applied in one transaction to `ref_config`; the app's `ConfigService` re-reads (no restart). ≤ 2 KB.
4. Keys flagged `requires_ack` (critical ones: `cfg.geo.radius_m`, `cfg.day.checkout_earliest_time`, `cfg.release.*`, `cfg.geo.mock_policy`) produce a `config_ack{config_version}` outbox row → `cfg.config_ack` on the server → the admin dashboard shows "reached 93 % of devices in scope" (T-6-01 in lens-data).
5. Effective-dating: a change with `effective_from` in the future is stored and applied at that instant by the device (checked on each `ConfigService.get`), so "radius 150 m from tomorrow" is consistent across the fleet regardless of when each phone syncs.
6. Capture stamps `config_version` and the resolved `radius_m_used` on every visit (lens-data M-05), so the server's re-check uses the value in force on the visit's business date, not today's.
7. Propagation time = time to the device's next natural request: ≤ 5 s + one visit for online SRs (trickle), the next foreground/connectivity for others, hard ceiling 15 min (periodic) while pending rows exist, else the next app open. Urgent pushes (kill switch, radius revert) may additionally use an FCM data message (Q22; `cfg.ops.push_enabled`); FCM is battery-neutral (shared system socket).
8. Blast-radius protections live on the server (bounds, canary scope, two-person approval for critical keys — lens-scale G-scale-15); the device additionally refuses out-of-bounds values (`constraints` ship with the key) and keeps the last good value.

---

## 5. Battery, data and size budgets (testable numbers)

### 5.1 Reference devices

| Role | Device (ASSUMPTION: typical of the fleet; confirm from the MDM/inventory) | Why |
| --- | --- | --- |
| Primary | Xiaomi Redmi 9A — Helio G25, 2 GB RAM, 32 GB, 5,000 mAh, Android 10/11 MIUI | most common low-end phone in Bangladesh 2021–24; MIUI's aggressive background killing is the worst case for WorkManager |
| Secondary | Samsung Galaxy A03 Core — Unisoc SC9863A, 2 GB, 32 GB, 5,000 mAh, Android 11 Go | Go edition memory limits; One UI Core |
| Legacy | any Android 8.0/8.1 device with ≤ 2 GB RAM | TLS trust store and clock behaviour (G-scale-14); minSdk 26 proposed (**D-sync-04**; ASSUMPTION — confirm the oldest OS in the fleet) |

### 5.2 Scripted field day (the measurement workload, also the integration test script)

50 outlets on one route, 10 photos (5 force sales + 3 outlet captures + 2 survey), 2 attendance fixes, 60 GPS fixes total (50 opens + 5 refreshes + 5 force-sale fixes), 50 memos printed + 1 stock memo + 1 summary, 55 trickle batches (online 60 % of the time: connectivity toggled per a fixed pattern), 1 full bundle + 3 deltas, Sales Submit + check-out at 17:00, 90 min total screen-on at 50 % brightness, Bluetooth printer connected during the Stock screen and at each print. Duration 8 h wall clock (an accelerated 2-h variant × 4 is allowed for CI-adjacent runs but the gate numbers are for the 8-h run).

### 5.3 Battery budget (gates; measured with `adb shell dumpsys batterystats` + Battery Historian on the primary device, fresh `--reset` at 08:00)

| Metric | Gate | Basis |
| --- | --- | --- |
| App-attributed power, non-screen (Battery Historian "Device estimated power use" for the app uid, screen excluded) | **≤ 6 % of capacity (≤ 300 mAh on 5,000 mAh)** over the scripted day | CPU for 90 min foreground use at ~120 mA ≈ 180 mAh; GPS 15 min at ~40 mA ≈ 10 mAh; radio for ~60 small POSTs ≈ 20 mAh; BT SPP ≈ 15 mAh; margin |
| Whole-device level at 17:00 starting from 100 % at 08:00 | **≥ 60 %** | idle drain ~1.5 %/h (12 %) + screen 90 min (~8 %) + app 6 % + margin |
| Background drain, app not in foreground for 8 h with 50 pending rows and **no network** | **≤ 1 %** (≤ 50 mAh) | T4 job never runs (constraint unmet); T5 periodic ≤ 32 runs × < 50 ms CPU |
| Background drain, 8 h idle **with** network and empty outbox | **≤ 0.5 %** | periodic job is cancelled when nothing is pending |
| Wake-lock time attributable to the app | **≤ 10 min/day; longest single ≤ 90 s** | each sync batch ≤ 30 s send+receive; WorkManager's own 10-min cap is never approached |
| GPS: fixes/day and GPS-on time | **≤ 80 fixes; ≤ 15 min/day**; per fix: `desiredAccuracy=high` for ≤ 8 s then accept best fix with `accuracy_m ≤ cfg.geo.max_accuracy_m` (100) else `timeLimit` 15 s and accept/flag; **no `getPositionStream`**, no background location permission | docs/04/05 single-fix rule; the operation is 1 fix per outlet open + attendance + force sale + outlet capture |
| Mobile-radio active time attributable to the app | **≤ 20 min/day** | small POSTs on an already-awake radio; no keep-alive pings |
| WorkManager executions | **≤ 100/day** | 32 periodic + ≤ 60 one-offs |
| Foreground services | **0** | hard rule (docs/04) |
| Alarms | **0** | — |
| Jank / CPU | no sustained CPU > 25 % while idle on a screen (Flutter DevTools) | — |

### 5.4 Data budget (gates; `adb shell dumpsys netstats detail` per uid, mobile vs Wi-Fi split)

| Item | Bytes | Count/day | Total |
| --- | --- | --- | --- |
| Full bundle (gz) | 25 KB (60 KB p95) | 1 | 25–60 KB |
| Deltas | 0.3–5 KB | ≤ 15 | ≤ 75 KB |
| Trickle batches incl. TLS/HTTP overhead (~2 KB body + ~1.5 KB handshake/headers; keep-alive saves most handshakes) | 3.5 KB | 55 | ~190 KB |
| Responses | 1 KB | 55 | 55 KB |
| Home KPI / health / release check / upload-URL calls | 1 KB | ≤ 30 | 30 KB |
| **Mobile data without photos** | | | **≤ 0.5 MB typical; gate ≤ 1 MB/day** |
| Photos (10 × ≤ 150 KB) | 150 KB | 10 | 1.5 MB — Wi-Fi by default; mobile only via toggle or evidence fallback |
| **Mobile data with 10 photos on mobile** | | | **gate ≤ 3 MB/day** |
| One-time per device | APK 22–30 MB; thumbnails 0.6 MB; Bengali fonts in APK | | Wi-Fi-preferred; Front Door cache (G-scale-16) |
| **Monthly, 26 selling days, photos on Wi-Fi** | | | **≈ 15 MB mobile** — the 2 GB/day pack is not touched by the app (PROJECT-CONTEXT pain point) |

### 5.5 Photo pipeline (F-SYS-030)

1. Camera opened with `ResolutionPreset.medium` (≈ 1280×720) — never `max`; flash auto; the preview is torn down immediately after capture ("don't keep the camera warm").
2. Native resize (`flutter_image_compress`): long edge → `cfg.media.long_edge_px` (1024), JPEG `quality = cfg.media.jpeg_quality` (70); if bytes > `cfg.media.photo_max_kb` (150) → quality −10 down to 40 → then long edge 800; EXIF stripped except orientation; **no GPS in EXIF** (lat/lng/accuracy/mock stored in `media_queue` and sent in `media_meta`).
3. sha256 computed once; file written to app-private `media/` dir; `media_queue` row + the owning record's `photo_client_uuid`/`blob_path` set in the **same** transaction as the record.
4. Display uses the local file; thumbnails are generated lazily at 256 px into the LRU cache.
5. Upload per §2.11. Server validates `bytes ≤ cfg.media.max_bytes` (300 KB), dims ≤ 1600 px (lens-data DQ-29).
6. CPU budget: ≤ 1.5 s per photo on the primary device (measured T-2-30).

### 5.6 APK size plan (F-SYS-036)

| Step | Effect | Gate |
| --- | --- | --- |
| Android App Bundle for the Play internal track **and** per-ABI split APKs (`--split-per-abi`: `armeabi-v7a`, `arm64-v8a`; no `x86`/`x86_64`) for the side-load channel; the in-app updater picks the ABI from `Build.SUPPORTED_ABIS` (`app_release.abi`, M-44) | −40 % vs a fat APK | CI publishes both |
| Fonts: **one** Bengali family (Noto Sans Bengali, Regular + Bold, ~700 KB) subset to Bengali + Latin digits; Latin uses the platform font (Roboto) | −3–6 MB vs the current multiple fonts | font list audited in CI |
| No demo audio/images, no bundled tutorial videos (streamed, Wi-Fi), product thumbnails from Blob with immutable URLs | −20+ MB vs the current ~90 MB | asset manifest diff per release |
| R8 full mode + `shrinkResources`; `--obfuscate --split-debug-info`; `flutter build --release --tree-shake-icons` | −2–4 MB | — |
| Dependencies audit: one HTTP client, one DB, one BT plugin, no Firebase unless Q22 says yes (FCM adds ~2 MB), no maps SDK in the SR role (AMO/TSO maps use a lightweight tile widget on demand) | — | dependency allowlist in repo |
| **Targets** | **≤ 30 MB download per ABI (CI fails above), aim 22 MB; installed ≤ 70 MB; cold start ≤ 2.5 s on the primary device** | T-1-33 |

### 5.7 Other R4 rules enforced in code review

No `Timer.periodic` shorter than 60 s anywhere; no `Isolate.spawn` per sync (one long-lived worker isolate); no animations on the list screens beyond platform defaults; Bluetooth disconnected after `cfg.print.disconnect_idle_s` (120) of no printing except on the Stock screen; location permission requested as `whileInUse` only (never `always`); `connectivity_plus` stream subscribed only while the app is resumed; `WakelockPlus` never used by UI code (WorkManager holds the partial wake-lock during a job).

---

## 6. Bluetooth printing offline (F-SR-028, F-SR-031, G-feat-43)

### 6.1 Hardware and transport

| Item | Specification |
| --- | --- |
| Printer class | RPP02N-class 58 mm thermal, ESC/POS, **Bluetooth Classic SPP** (RFCOMM, UUID `00001101-…`); some clones also expose BLE — SPP is the supported path, BLE optional later |
| Flutter plugin | a Classic-SPP transport (e.g. `flutter_bluetooth_serial` or `bluetooth_print`) + `esc_pos_utils_plus` for byte generation; the transport is wrapped behind one `PrinterPort` interface so the plugin can be swapped (**D-sync-05**) |
| Permissions | Android ≤ 11: `BLUETOOTH`, `BLUETOOTH_ADMIN` (+ location permission is required by the OS for discovery on 10/11 — we already hold `whileInUse`); Android ≥ 12: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` with `neverForLocation` |
| Pairing | system pairing once per phone (PIN usually 0000/1234); the app stores `printer_mac`, `printer_name` in `aron_device.db` (**device-level, not user-level**: shared phones share the printer) and mirrors to `device.printer_id` (M-41) in the next `device_integrity`/device snapshot |
| Connection life cycle | `disconnected → connecting (≤ 8 s) → connected → printing → connected → (idle cfg.print.disconnect_idle_s 120) → disconnected`; auto-connect on entering the Stock screen and on any print request; two reconnect attempts with 2 s gap; a failure shows the printer picker (paired devices list) |
| Coexistence (docs/11 pilot) | both apps use the system pairing; only one can hold the RFCOMM socket at a time → disconnect on idle is also what makes side-by-side work (F-SYS-044) |

### 6.2 Memo template (same layout as today — **the current layout must be captured first, G-sync-02**)

| Zone | Content (58 mm paper = 384 dots; Font A 12×24 → 32 columns; Font B 9×17 → 42 columns) | Rendering |
| --- | --- | --- |
| Header | company/brand line; distribution house + zone name; route name; SR name + username; **memo no.** `sr334001-261004-017`; date/time (Dhaka, corrected); outlet name, code, owner, phone (last 4 digits only — ASSUMPTION, PII) | Bangla as **raster** (`GS v 0`) rendered from the bundled Noto Sans Bengali at 24 px — the printers' code pages have no Bengali; Latin/digits as text |
| Lines | `SKU short name | qty (entered unit) | unit price | amount` with Font B; qty+unit as entered (packs/sticks per D-02); offers shown as `-discount` lines below the SKU | text; amounts right-aligned, 2 decimals (paisa) from mtk per D-01 rounding |
| Totals | gross, discount, **net**, paid, due (this memo), previous due, total due; loyalty points earned/balance if Diamond League active; `round_adj` never printed, just absorbed | text, bold for net |
| Footer | reprint marker `পুনর্মুদ্রণ #n` when `print_count > 0`; `supersedes` line "আগের মেমো … বাতিল" on an edited memo; thanks line; 3 line feeds + partial cut if supported | raster for Bangla |
| QR (optional, `cfg.print.qr_enabled` false) | `memo_no|client_uuid|net` for retailer/audit lookup | `GS ( k` |

Also: **stock memo** (issue by SKU, totals, SR + distributor signature lines) and **summary memo** (per-SKU memo count/qty/value/discount/return, category totals, grand total — docs/07) use the same renderer. Template text and column widths are data (`ref_config` key `cfg.print.template_version` + a template JSON in the bundle) so the layout can be corrected without a release during pilot.

### 6.3 Print flow and failure handling

1. Sale committed (visit family closed, memo row + outbox) **before** any printer call; the sale is final regardless of printing (docs/06 "sale is saved before printing").
2. `print_job(kind='memo', ref=memo_client_uuid, attempt=1, state='queued')` → render bytes (cached per job) → connect → write in 512-B chunks with 20 ms pacing (cheap clones overrun) → `DLE EOT 1` status read if the firmware supports it (`cfg.print.status_query` false by default) → `state='done'`, memo `printed_at` (first success only), `print_count++` → a `memo_print` note is included in the memo payload if not yet synced, else as an `activity_log` row (`print_count` on the server is enrichment, lens-data M-06).
3. Failure modes: not paired → picker; connect timeout → retry ×2 → dialog "প্রিন্টার সংযোগ হয়নি" with Retry / Skip (skip leaves `print_job.state='failed'`, memo flagged `unprinted`, shown with an icon in the Memo list; **Reprint** from Memo menu any time); printer off mid-print → same dialog; paper out (when detectable) → message, job stays `failed`.
4. Reprint: Memo menu → Print → new `print_job` attempt → marker `#n`; reprint count visible to the AMO (G-feat-04). Edited memo prints the new number and the supersedes line; the old paper memo is the retailer's; **D-sync-06**: the app never prints a "void" slip for the old memo (ASSUMPTION; business to confirm whether a cancellation slip is expected).
5. Kill-and-relaunch during printing: `print_job` with `state='printing'` older than 60 s → `failed` on relaunch; the memo exists; the SR sees the unprinted icon.
6. Battery: ≤ 15 mAh/day (connected only while on the Stock screen or printing; idle disconnect 120 s).

---

## 7. Memo numbering offline (docs/13 Q5; confirms lens-data D-04 and lens-scale D-scale-10)

### 7.1 Proposal (**D-sync-07**)

`memo_no = <username>-<yyMMdd>-<seq>` e.g. `sr334001-261004-017`, where:

| Part | Source | Why |
| --- | --- | --- |
| `username` | the acting user (`sr334001`, `amo5001`); already encodes the zone/role in the current naming | unique per person; the retailer-facing identity of "who sold" — an SR covering two routes has one series, and a substitute selling on a route gets their own series (KPI attribution is by `route_id` on the memo, not by the number) |
| `yyMMdd` | corrected Dhaka **business date** of the memo | ties the number to the date it is counted on |
| `seq` | 3 digits, from `L:memo_counter(business_date, bind_ordinal).next_seq`, starting at `bind_ordinal × 500 + 1` (ordinal 0 → 001…500, ordinal 1 → 501…999) | a device replaced or re-bound mid-day gets a disjoint block, so numbers never collide even though the old device's unsynced rows are unknown to the server; 500 memos/day/device is 10× the realistic maximum (p95 ≈ 60) |

Uniqueness without the server: one user, one date, one device-bind ordinal, one monotonically increasing counter held in the per-user DB and incremented in the **same transaction** as the memo insert (never pre-allocated, never reused after a rollback — a failed save burns the number, which is acceptable and auditable). The server stores `memo_no` **verbatim**, validates the regex `^[a-z]+[0-9]+-[0-9]{6}-[0-9]{3}$`, enforces `UNIQUE (memo_no, business_date)` (M-06) and that the username prefix matches the authenticated user; a mismatch is `rejected(reason='memo_no_invalid')`, never rewritten. `bind_ordinal` is assigned by the server at `POST /auth/bind-device` (count of bindings for that user that business date; stored in `device_state.bind_ordinal_json`), and defaults to 0 offline for the first device.

Why not `<zoneCode>-<routeCode>-<yymmdd>-<seq>` (the alternative in the task): the route is not unique per device (one SR, two routes → two counters and the risk of the SR selling on the "wrong" route's number), a substitute SR and the regular SR on the same route same day would share a counter they cannot coordinate offline, and zone/route are already printed as header lines. The route code is still on the memo; it just is not the identity.

Zero-sale memos (G-data-31): if a zero sale creates a memo row it **does** consume a number (the retailer may receive a printed zero memo today — confirm), so "memo count" (D-06) excludes zero memos by `line_count = 0`, not by number gaps. Edited memos take the next number and reference `supersedes_client_uuid` (+ `supersedes_memo_no` printed).

### 7.2 Continuity at cutover (options for the business — Q5)

| Option | What the retailer sees | Server | Pros | Cons |
| --- | --- | --- | --- | --- |
| A. New series only (recommended default) | `sr334001-261004-017` | `memo_no` verbatim; `memo_serial` NULL | offline-safe by construction; zero coordination; obviously "new system" on day one of a wave (helps support) | the printed number format changes once |
| B. New `memo_no` + a **server** `memo_serial` continuing the Apsis series, printed as a second line on **reprints** only (the first print happens offline before the server exists) | both numbers after sync | `app.memo_serial_seq` started at Apsis max + 1 per the import (M-06) | reports can keep a single continuous series | the first print cannot show the serial; two numbers confuse retailers; a hot sequence is unnecessary for anything retailer-facing (G-scale-18) |
| C. Pre-allocated per-device blocks of the Apsis series (device gets a range at login) | continuous-looking numbers | server hands out ranges | continuity on first print | gaps and out-of-order numbers by design; a device that never comes back strands a block; offline rebind breaks it; most complex |

Recommendation: **A**, with `memo_serial` kept as an optional column so B can be switched on later without a schema change. Imported Apsis memos keep their original number in `memo_no` with `entry_source='migration'` (lens-data M-42), so dues and reprints of old memos show the number the retailer has on paper.

---

## 8. Edge cases

| # | Case | Behaviour | Keys / refs |
| --- | --- | --- | --- |
| 8.1 | **Shared phone, two SRs on alternate days** (user A pending rows, user B logs in) | Separate DB per user (D-sync-01). B logs in normally (device binding allows `cfg.auth.max_users_per_device` = 3 bound users; the TSO's OTP binds *user ↔ device*, not device exclusively). The engine iterates `user_index` and flushes **A's** outbox under **A's** stored refresh token whenever a trigger fires, while B works; Home for B shows a quiet line "অন্য ব্যবহারকারীর 12টি রেকর্ড আপলোড বাকি". If A's refresh token expired, A's rows wait until A logs in; after `cfg.sync.orphan_pending_alert_h` (24) the sync-health screen lists the device. Per-user memo counters and bundles never mix. **G-sync-01 blocker**: the binding model (one device ↔ many users; one user ↔ how many devices) is a business decision | `cfg.auth.max_users_per_device`, `cfg.auth.max_devices_per_user` (2) |
| 8.2 | **App update while rows pending** | Drift migration runs on first open; outbox rows are never transformed — `payload_json` carries its `schema_version`, the server accepts N-2..N (CI contract test with the previous two releases' fixtures, T-2-33). The updater refuses to install while a batch is `syncing` (waits ≤ 60 s). `cfg.release.min_version` blocks a **new day's** login but never upload (D-scale-9) | — |
| 8.3 | **Device replaced mid-day** (lost/broken) | TSO issues a new OTP; `bind_ordinal` for that user-date becomes 1 → memo numbers 501+. Bundle full download; `day_states` tell the new device the route is already `in_field/synced`. Rows pending on the old device: if it still works, **PDA to Support** uploads the whole `aron_u<id>.db` + logs to `support/` (F-SYS-021) and support replays the outbox through an admin import that goes through the same ingest (same `client_uuid`s → idempotent); if dead, the memos exist only on paper → manual **Data Entry** (G-feat-30) keyed by the printed `memo_no`; the reconciliation screen shows the gap honestly | `support_upload` (M-44) |
| 8.4 | **Kill-and-relaunch mid-sale** | `visit` is inserted at outlet open (fix, geo verdict) with `ended_at NULL`; `outbox` row `held`. Every order-entry change autosaves to `sale_draft` (≤ 100 ms debounce, one row per visit). On relaunch: if a `sale_draft` exists and the visit is < `cfg.visit.abandon_min` (120) old → "চলমান কল পুনরায় শুরু করুন?" resume at the saved step; else the visit is closed with `outcome='abandoned'`, its held rows released; abandoned visits sync and are **excluded from "visited"/CPR** by default (`cfg.kpi.count_abandoned_visits` false; ASSUMPTION — confirm). Memo insert + counter + outbox + draft delete are one transaction, so a kill during "Save" yields either a complete memo or none; printing happens after (§6.3). Gate T-1-24 | `sale_draft` |
| 8.5 | **Storage full** | At start and before camera: free space < 500 MB → warning + automatic image-cache eviction; < 200 MB → photo capture refused ("ছবি নেওয়ার জায়গা নেই"), force sale still allowed with `photo_pending_storage` flag (ASSUMPTION; business may prefer to block); `SQLITE_FULL` on a write → transaction rolled back, draft kept in memory + retried after eviction; the user is never shown a stack trace. Local queue cap `cfg.media.local_queue_max_mb` (50) | — |
| 8.6 | **Permission revoked mid-day** | Location: outlet open shows the OS prompt again; if denied → force-sale path with reason code `permission_denied` (reason codes are a config list, lens-data P14) and the visit flagged; mock detection n/a. Camera denied → force sale cannot complete (photo required) → the SR is told to re-grant; no sale without geo or photo (docs/05). Bluetooth denied → sale saves, print fails gracefully (§6.3). Permission state snapshot goes in `device_capability_snapshot` (M-41) | `cfg.geo.force_reasons` |
| 8.7 | **Device clock changed to pass the 17:00 gate** | gate on corrected time (§3.4); attendance event carries `clock_offset_ms`; server flags `checkout_before_allowed` (lens-data DQ-20) | `cfg.day.checkout_earliest_time` |
| 8.8 | **SIM-less Wi-Fi-only phone** | Whole day offline by design; morning bundle at home/distribution-house Wi-Fi (pre-fetch of tomorrow's bundle the previous evening, §4.5); T4 one-off with `NetworkType.CONNECTED` fires when Wi-Fi returns; photos upload on the same Wi-Fi; Sales Submit and check-out are outbox events so they complete offline (D-sync-08) and land in the evening — they are stamped with capture time, so GIGO/attendance are correct. Login %: `day_open` event. Submit % for the day is only known in the evening — the Daily Tracking dashboard shows "pending upload" distinctly from "not submitted" (G-sync-09) | — |
| 8.9 | **OEM background killers (MIUI, Realme, Vivo)** | WorkManager may be deferred for hours when the app is force-stopped by the user/OEM; mitigations: sync on every foreground; one-time "allow autostart / no battery restrictions" guidance screen with deep links (`cfg.app.oem_guidance` list); the pre-18:00 "pending rows" local notification (`cfg.sync.pending_reminder_time` 16:30, posted from the periodic job — notifications need `POST_NOTIFICATIONS` on 13+) | — |
| 8.10 | **Token expired offline / password changed** | offline unlock via local verifier until the refresh token's TTL; on reconnect 401 → refresh fails → `needs_relogin`; pending rows wait; capture continues. Password change by admin is therefore **not** a kill switch for the field (by design: a locked-out SR's rows still upload when they re-authenticate) | `cfg.auth.refresh_ttl_days` |
| 8.11 | **Outlet without coordinates** (Q7, G-feat-39) | geo check cannot run → the visit opens as force sale with reason `no_outlet_location`; the photo + fix propose the location through `outlet_change_request(type=info)`; after approval the delta carries lat/lng | — |
| 8.12 | **Selling to a newly captured outlet before approval** (G-feat-42) | `ref_outlet` row with `client_uuid` id and `provisional=1`; visits/memos carry `outlet_client_uuid`; server links them when the request is approved and re-ingests parked rows (`rejected(retryable, reason='outlet_pending_approval')` until then) | — |
| 8.13 | **Two devices, same user, same day** (forgot to unbind) | both are bound; both get `bind_ordinal`s (0 and 1); server accepts both streams; flagged `multi_device_day` on the sync-health screen; attendance events from two devices → first in / last out (M-12) | `cfg.auth.max_devices_per_user` |
| 8.14 | **Server `read_only_mode` / `sync_hold_s`** (migration window, incident) | 503 + `Retry-After` or `hold_s` in a 200 → treated per §2.6; randomised per device; capture unaffected | `cfg.ops.read_only_mode`, `cfg.ops.sync_hold_s` |
| 8.15 | **Local DB corruption** | `PRAGMA quick_check` at open (≤ 100 ms on a 10-MB DB); on failure: copy the file aside, offer **PDA to Support**, recreate the DB, re-download the bundle; the pending rows are in the support upload; the reconciliation screen shows the gap | — |
| 8.16 | **Duplicate taps / double submit** | all actions are idempotent locally (the visit/memo `client_uuid` is created when the screen opens, not when the button is pressed); the Save button is disabled during the transaction | — |
| 8.17 | **Price/offer changed mid-day** (G-feat-52) | delta applied; new memos use the new list; memo stores `price_list_date`; the home strip recomputes | — |
| 8.18 | **Unsubmitted yesterday + today's route** | both `day_local` rows shown; yesterday submittable until `cfg.day.submit_grace_h`; captures always go to today | — |
| 8.19 | **Bundle larger than budget** (an AMO with 20 routes) | server pages sections > 2,000 rows (`GET /sync/bundle?section=outlets&page=`), device assembles; hard cap 2 MB gz with an ops alert | `cfg.bundle.max_gz_kb` (2048) |
| 8.20 | **Low battery (< 15 %)** | WorkManager `requiresBatteryNotLow` holds background jobs; foreground sync still allowed (it is the SR's choice); photo uploads on mobile paused | — |

---

## 9. Sync test plan

### 9.1 Property / fuzz tests (server, TypeScript, `fast-check`; CI on every PR)

| ID | Property | Generator | Oracle |
| --- | --- | --- | --- |
| T-1-20 | Outbox invariant: for any sequence of capture operations, `count(domain rows) == count(outbox rows)` and every outbox payload hash equals the hash of the stored row | random day of 1–300 captures (Dart `glados`/hand-rolled) | equality after each op and after random `kill` points |
| T-1-21 | **Idempotent convergence**: any partition of a day's records into batches, with random duplication (0–50 %), reordering (children before parents 0–20 %), truncation (drop the tail of a batch 0–20 %) and `batch_uuid` replay, yields the same server state as one clean batch | 10,000 runs per CI, 100,000 nightly | row-by-row equality of `app.*` tables and `dw.agg_*` for the touched dates (lens-data T-1-01/02, lens-scale T-1-51 are the same property seen from their lenses) |
| T-1-22 | Replay returns a byte-identical response for the same `batch_uuid` within 24 h; a different row set under the same `batch_uuid` → 409 | — | — |
| T-1-23 | ACK application is a pure function: `apply(ack, outbox) ` never leaves `syncing`, never flips `synced → pending`, counts reconcile `device == accepted + rejected + conflict` | random ACKs incl. unknown `client_uuid`s | invariants |
| T-1-25 | Family atomicity: the batch builder never splits a family; overshoot ≤ one family | random outbox contents | — |
| T-1-26 | Backoff: sequence bounded by cap, jittered, hands over to WorkManager after 5 failures; `attempt_count` unaffected by transport failures | simulated failure streams | — |
| T-1-27 | Business date: for random corrected timestamps around midnight Dhaka and random offsets up to ±48 h, `business_date` on device == `business_date_server`, and the 23:55/00:10 case re-aggregates only date D | — | `dw.agg_daily_route` diff |
| T-2-20 | Memo numbers: for random (user, date, bind_ordinal ≤ 3, ≤ 500 memos, random rollbacks) no collision and the regex holds; server rejects a foreign prefix | — | — |
| T-2-21 | Edits/dues/stock as events: random supersede chains and due collections across batches never double a memo count, a due balance or a stock balance | — | ledger equality vs a reference model |

### 9.2 Kill tests (Flutter integration tests on the primary device + emulator; debug-only chaos hook `--dart-define=CHAOS_KILL_AT=<step>` and `adb shell am force-stop`)

| ID | Kill point | Expected after relaunch |
| --- | --- | --- |
| T-1-24 | between memo transaction commit and the first printer byte; during the transaction; during `sale_draft` autosave | exactly one memo or none; draft resume offered; memo number not duplicated; outbox == domain |
| T-1-28 | mid-batch send (after the request left, before the response) | rows back to `pending` within 120 s; resend reuses `batch_uuid`; server shows one copy |
| T-1-29 | mid-ACK application | ACK transaction is atomic: either all rows of the batch flipped or none; resend yields `replayed:true` |
| T-2-22 | mid-photo compression / mid-upload | no orphan files; `media_queue` state consistent; blob either absent or complete (block blob commit is atomic) |
| T-2-23 | during Drift migration on upgrade with 200 pending rows | migration completes or rolls back; pending rows intact and uploadable |
| T-2-24 | during bundle delta apply | `ref_*` either old or new, never mixed; `ref_meta` consistent |
| T-3-20 | during Sales Submit (event appended, app killed) | `submitted_local` persists; event uploads; server `route_day.sales_submitted` once |

### 9.3 Airplane-mode and connectivity integration tests (device farm; `adb shell cmd connectivity airplane-mode enable|disable`, Wi-Fi toggles, a throttling proxy at 2G profile 50 kbps/400 ms)

| ID | Scenario | Pass criteria |
| --- | --- | --- |
| T-1-30 | Full scripted day (§5.2) in airplane mode from 08:00; network at 17:05 | all rows synced within 3 min of connectivity (T4 fires); counts match; Login % shows the offline start; Sales Submit accepted |
| T-1-31 | Flapping network (30 s on / 90 s off for 2 h) | no duplicate rows server-side; ≤ 1 POST per 5 s; no batch > `batch_max_rows`; wake-lock total ≤ budget |
| T-1-32 | Captive Wi-Fi (portal) | treated as offline (validation HEAD fails); no error toast storm; T4 re-arms |
| T-2-25 | 2G throttle, 200-row catch-up | completes with 413/halving logic or timeouts + resume; ≤ 10 min total |
| T-2-26 | Wi-Fi-only photos: 10 photos on mobile all day, Wi-Fi at 19:00 | zero photo bytes on mobile until the evidence fallback (6 h) for the 5 force-sale photos; the rest upload on Wi-Fi |
| T-2-27 | Stale bundle: fetch D, airplane mode, device date → D+1 | sells with banner; `day_open{online:false}` recorded; D+2… blocked with the read-only screen; D+1 rows flagged `stale_price` when a price changed |
| T-2-28 | Config change mid-day (radius 100 → 60 m for the territory) | next sync response carries the version; delta applied; the next outlet open uses 60 m; visit stores `radius_m_used=60`, `config_version`; `config_ack` received; a visit captured before the change keeps 100 |
| T-2-29 | Clock skew −3 h and +40 min | corrected business date and 17:00 gate; rows flagged; banner shown; no rejections |
| T-3-21 | Shared phone: A captures 20 rows offline, logs out; B logs in online | A's rows upload under A's token within one sync cycle; B's bundle correct; no cross-contamination of counters/bundles |
| T-3-22 | Device replaced mid-day (new OTP) | `bind_ordinal=1`, memo numbers 501+; `day_states` show the route in_field; PDA-to-Support upload of the old DB replays idempotently |
| T-3-23 | TSO logout with 5 pending rows | refused with count; allowed after PDA upload or after sync |

### 9.4 Device-vs-server reconciliation

| ID | Test | Pass criteria |
| --- | --- | --- |
| T-1-34 | After T-1-30, the reconciliation screen's per-type device counts equal `server_totals` (accepted + rejected + conflict) | exact equality; mismatch path exercised by injecting one `unknown_sku` row → shows 1 rejected, Sales Submit still allowed |
| T-2-31 | Nightly server job `reconcile_device_vs_server(business_date)`: compares each device's last `device_counts` claim with `ingest_registry` counts; differences → `sync_health` rows | zero unexplained differences over a 10,000-batch fuzz day (lens-data T-2-04) |
| T-7-20 | Pilot parallel run: new-app counts vs Apsis daily report per route (F-SYS-043) | memo count, STD, dues equal for 10 consecutive days |

### 9.5 Battery and data protocol (gates in §5.3/5.4; run per release candidate on the primary device, once per phase on the secondary and legacy devices)

1. Fresh install of the RC; printer paired; `adb shell dumpsys batterystats --reset && adb shell dumpsys netstats --reset`(or record baseline counters); unplug; start the §5.2 script via Maestro/Appium with the connectivity pattern driven by `adb` from a host over Wi-Fi ADB (USB would charge the phone).
2. After 8 h: `adb bugreport` → Battery Historian: read the app uid's estimated power, wake-lock total and max, GPS time, mobile radio active time, job executions, alarms (must be 0), foreground service time (must be 0). `adb shell dumpsys netstats detail` → mobile and Wi-Fi bytes for the uid.
3. Background variant: 50 pending rows, airplane mode, app backgrounded 8 h → drain ≤ 1 %; then enable network → sync within 3 min.
4. Record the numbers in `/docs/perf/battery-<version>.md` (the build is not shippable without it); regressions > 20 % vs the previous release block the release.
5. APK size: CI step compares per-ABI APK size against the 30 MB gate and the previous release (+5 % → warning, +15 % → fail). Cold start measured with `adb shell am start -W`.

### 9.6 Printer tests

T-1-35: print the golden memo fixture on an RPP02N and a second clone; photograph and diff against the captured current memo (G-sync-02); T-2-32: printer off mid-print → sale intact, reprint works, `print_count` correct; T-2-33 (also §8.2): old-release fixture payloads accepted by the new server.

### 9.7 Gate summary by phase

| Phase | Gates from this lens |
| --- | --- |
| 1 | T-1-20..35 (outbox invariant, idempotent convergence, replay, ACK purity, kill mid-sale/mid-send/mid-ACK, airplane-mode day, flapping, captive Wi-Fi, APK ≤ 30 MB, reconciliation equality, golden memo print) |
| 2 | T-2-20..34 (memo numbers, event ledgers, photo kills, upgrade with pending rows, delta atomicity, 2G catch-up, Wi-Fi-only photos, stale bundle, config change mid-day, clock skew, photo CPU, nightly reconcile, printer failure, schema N-2 compatibility, Android 7/8 TLS) |
| 3 | T-3-20..23 (submit kill, shared phone, device replacement, TSO logout guard) |
| 7 | T-7-20 (pilot parallel reconciliation), plus the battery protocol on the actual pilot devices |

---

## 10. Config keys introduced or constrained by this lens (R6; all with bounds, audit and `delivery=bundle` unless noted)

| Key | Default | Bounds / notes |
| --- | --- | --- |
| `cfg.sync.trickle_debounce_s` | **5** (lens-scale proposed 10; see §2.2) | 2–60 |
| `cfg.sync.family_hold_max_s` | 180 | 60–900 |
| `cfg.sync.batch_max_rows` | 200 | 50–500 (lens-scale) |
| `cfg.sync.batch_max_kb_raw` | 256 | 64–1024 |
| `cfg.sync.retry_base_s` / `retry_cap_s` / `retry_max_inprocess` | 2 / 300 / 5 | cap 60–900; max 3–10 |
| `cfg.sync.row_max_retries` | 10 | 3–50 |
| `cfg.sync.periodic_min` | 15 | 15–120 (WorkManager minimum) |
| `cfg.sync.login_jitter_s` | 120 | 0–600; automatic refresh only |
| `cfg.sync.max_clock_skew_min` | 10 | 2–60 |
| `cfg.sync.max_backdate_days` | 7 | 1–30 (server-only) |
| `cfg.sync.orphan_pending_alert_h` | 24 | 6–72 (server-only) |
| `cfg.sync.pending_reminder_time` | 16:30 | 14:00–20:00 |
| `cfg.sync.reason_texts` | map code → {bn, en} | ships in bundle; new codes without a release |
| `cfg.bundle.pregen_for_next_day_time` | 22:00 Dhaka | replaces `pregen_time` 04:00 if §4.5 pre-fetch is accepted |
| `cfg.bundle.stale_max_days` | 2 | 1–3 (lens-scale) |
| `cfg.bundle.delta_max_age_h` | 72 | 24–168 |
| `cfg.bundle.delta_min_interval_min` / `delta_min_interval_version_min` | 30 / 5 | — |
| `cfg.bundle.max_gz_kb` | 2048 | alert at 300 |
| `cfg.media.photo_max_kb` / `long_edge_px` / `jpeg_quality` / `wifi_only_default` | 150 / 1024 / 70 / true | 60–300 / 640–1600 / 40–90 |
| `cfg.media.evidence_mobile_fallback_h` | 6 | 1–48; 0 = never |
| `cfg.media.local_queue_max_mb` / `local_keep_days` | 50 / 2 | — |
| `cfg.print.disconnect_idle_s` / `status_query` / `qr_enabled` / `template_version` | 120 / false / false / 1 | — |
| `cfg.memo.seq_block_size` | 500 | 100–999 (§7) |
| `cfg.visit.abandon_min` | 120 | 15–480 |
| `cfg.kpi.count_abandoned_visits` | false | confirm |
| `cfg.app.outbox_keep_days` / `local_history_days` / `rejected_keep_days` / `image_cache_mb` | 3 / 7 / 30 / 40 | — |
| `cfg.app.oem_guidance` | list of {manufacturer, deep link, text} | — |
| `cfg.auth.max_users_per_device` / `max_devices_per_user` / `refresh_ttl_days` | 3 / 2 / 30 | G-sync-01 |
| `cfg.day.submit_grace_h` | 10 | 0–24; Q11 |
| `cfg.route.allow_unplanned_day` | true | G-feat-10 |
| `cfg.ops.push_enabled` | false | Q22 (FCM) |
| `cfg.geo.max_accuracy_m` / `fix_timeout_s` / `refresh_max` | 100 / 15 / 3 | battery cap on retries |

## 11. Decisions proposed (to DECISIONS.md; numbers assigned on merge)

| ID | Decision | Rationale |
| --- | --- | --- |
| D-sync-01 | One SQLite file per user on the device + one device-level file; WAL + `synchronous=FULL`; no SQLCipher (pending G-sync-13) | shared phones; durability of a sale; CPU/size |
| D-sync-02 | State ownership: `route_day` owns not_started…sales_submitted; the **user** owns attendance; the **zone** owns final submit projected onto route_days; device keeps `day_local` mirror and shows both states | seed #10; M-32 |
| D-sync-03 | SR/AMO logout keeps the local DB and the engine keeps uploading that user's rows under their stored refresh token; TSO logout wipes but refuses while rows are pending unless PDA-to-Support has run | data-loss prevention; docs/08 |
| D-sync-04 | `minSdkVersion 26` (Android 8.0) — ASSUMPTION pending fleet inventory | WorkManager/expedited behaviour, TLS trust store |
| D-sync-05 | Printer transport behind a `PrinterPort` interface; Bluetooth Classic SPP is the supported path; Bangla printed as raster | clone printers lack Bengali code pages |
| D-sync-06 | No void slip for an edited memo; the new memo prints "supersedes <memo_no>" | ASSUMPTION; confirm |
| D-sync-07 | `memo_no = <username>-<yyMMdd>-<seq3>` with bind-ordinal blocks of 500; server keeps verbatim; option A at cutover, `memo_serial` optional | Q5; D-04; D-scale-10 |
| D-sync-08 | Sales Submit, check-out and day-open are **outbox events** (`day_submit`, `attendance_event`, `day_open`), not online-only endpoints; `POST /day/sales-submit` stays for web/admin use | offline-first for the whole day; Wi-Fi-only phones |
| D-sync-09 | `POST /sync/batch` body is one ordered flat `records[]` list with `type`, `family_uuid`, `rank`; response carries `server_totals` for reconciliation | ordering across types is the point; one round trip for the reconcile screen |
| D-sync-10 | Trickle debounce 5 s + family hold; one batch per visit | R5 wording "immediately"; same load as lens-scale |
| D-sync-11 | Backoff 2 s base, ×2, full jitter, cap 300 s, 5 in-process attempts then WorkManager (one-off with network constraint + 15-min periodic while pending) | R4; lens-scale §7.1 |
| D-sync-12 | Stale bundle ≤ 2 days: sell with banner and flags; older: read-only until online | ASSUMPTION; G-sync-03 |
| D-sync-13 | Corrected-time stamping (`captured_at_device` + `clock_offset_ms`), server recomputes; 17:00 gate on corrected time | docs/04 clock tests |
| D-sync-14 | Budgets in §5.3/5.4/5.6 are release gates recorded per RC | R4 as a hard rule |

## 12. Gap list (G-sync-NN)

| ID | Severity | Where | Gap | Fix / owner |
| --- | --- | --- | --- | --- |
| G-sync-01 | blocker | docs/06 bind, docs/11 shared ownership, F-SYS-003/022, G-feat-41 | Device binding model for shared phones is undefined: users per device, devices per user, what happens to user A's pending rows when B logs in, whether a TSO can unbind remotely | D-sync-01/03 + `cfg.auth.max_users_per_device`; business confirms before Phase 1 DoD (the per-user DB design is independent of the answer) |
| G-sync-02 | blocker | docs/01 "same memo", docs/06 print, docs/11 | The current printed memo layout (fields, order, Bangla/English mix, widths, totals rounding) is not captured anywhere in the spec; "same memo" cannot be tested | obtain photos/scans of 5 real memos (sale, credit, edited, zero, stock) from a pilot device before Phase 1 print test T-1-35; template JSON in bundle |
| G-sync-03 | blocker | docs/04 bundle, docs/06 setup, G-scale-05 | Behaviour when a new business day opens offline (stale bundle) is undefined; without a policy the SR either cannot sell or sells on unknown prices | D-sync-12 defaults; business confirms "sell on yesterday's list ≤ 2 days"; §4.5 pre-fetch |
| G-sync-04 | blocker | seed #1, G-feat-46, docs/06 print | Rounding rule from milli-taka prices to the printed paisa totals (per line or per memo; half-up or banker's) has no owner; it decides whether the printed total equals Apsis to the paisa | lens-data D-01 (`round_adj_mtk`) needs the actual rule from Apsis memos (G-sync-02 evidence); property test T-2-21 |
| G-sync-05 | major | docs/09 API | `POST /sync/batch` shape (per-type arrays) cannot express cross-type ordering or batch identity; no `batch_uuid`, `server_totals`, `day_states`, `hold_s`, `X-Config-Version` | D-sync-09; update F-API-006 in lens-features |
| G-sync-06 | major | docs/06 step 7, docs/09 `/day/sales-submit` | Sales Submit and check-out are specified as online calls; a Wi-Fi-only SR cannot close the day in the field | D-sync-08 outbox events; `route_day.sales_submit_client_uuid` already in M-32 |
| G-sync-07 | major | docs/04 "logged in = bundle downloaded", seed #9, D-08 | An offline day start on a cached bundle produces no login event → Login % undercounts exactly the reps with the worst connectivity | `day_open` event (§4.5); server sets `logged_in` with `offline_start` flag; D-08 amended |
| G-sync-08 | major | docs/06 sale flow | Abandoned/open visits (SR navigates away or app dies) have no defined outcome; they inflate "visited" or vanish | §8.4 `outcome='abandoned'`, `cfg.visit.abandon_min`, `cfg.kpi.count_abandoned_visits`; confirm KPI treatment |
| G-sync-09 | major | docs/09 Daily Tracking, docs/10 Submit % | "Not submitted" and "submitted on device, not yet uploaded" are indistinguishable to management at 17:00 | device `submitted_local` is unknowable server-side by definition; show "last contact" age per route and the `offline_start` flag; set expectations in the runbook |
| G-sync-10 | major | docs/04 media, G-scale-20 | Evidence photos (force sale, outlet location) on Wi-Fi-only policy may never upload for SIM-less phones or rural SRs | `evidence=1` + `cfg.media.evidence_mobile_fallback_h`; photo-pending > 24 h on sync-health |
| G-sync-11 | major | docs/06 permissions, docs/05 | Permission revoked mid-day (location/camera) has no defined path; force-sale reasons are a fixed enum | `permission_denied`, `no_outlet_location` reason codes (config list, P14); §8.6 |
| G-sync-12 | major | docs/04 battery | No reference device, no measurement protocol, no numeric gates | §5.1–5.4, §9.5; confirm fleet device mix from inventory |
| G-sync-13 | major | security / PII | Whether the on-device DB must be encrypted at rest (SQLCipher) is undecided; outlet phone numbers are on 8,500 shared phones | business/security decision; default no (D-sync-01) with Android FBE; if yes, budget +7 MB APK and re-run battery gates |
| G-sync-14 | minor | lens-scale §1.6/§7.1 vs this lens §2.2 | Debounce default 10 s vs 5 s | reconcile in the merged plan (D-sync-10 argues 5 s; load unchanged) |
| G-sync-15 | major | docs/06 "In-app update", F-SYS-020, G-scale-16 | Updater must not install while a batch is in flight and must handle the per-ABI choice; no spec for rollback of a bad APK with pending rows | §8.2; `app_release.abi`; `blocked_versions` never blocks upload (D-scale-9) |
| G-sync-16 | major | docs/08 TSO logout wipe, F-SYS-022 | Wipe with pending rows loses data | D-sync-03 guard; T-3-23 |
| G-sync-17 | minor | docs/04 cache "clear per-day working data on clean final-submit" | Purge rule conflicts with AMO "View previous sale data" and with reprints of yesterday's memo | `cfg.app.local_history_days` (7) + never purge non-synced or rejected rows; purge is by `business_date` age, not by final-submit |
| G-sync-18 | minor | docs/05 integrity signals | Mock-location detection on Android ≥ 6 cannot read a global "allow mock" setting; detection is per-fix (`isMocked`) plus package heuristics; Play Integrity needs Google Play services, which some fleet phones may lack | §3.3 `device_integrity`; `cfg.geo.integrity_check` off in pilot; weight, don't block |
| G-sync-19 | minor | docs/06 Astha/Loyalty redemption offline | Points balance from the bundle can be stale; two offline redemptions on two days could overspend | server rejects `redemption` beyond balance as `rejected(retryable=false, reason='insufficient_points')` and the SR is told; device shows balance age; confirm business tolerance |
| G-sync-20 | minor | docs/04 "Clear per-day working data" vs §1.7 | No cap on local photo queue / image cache growth on 16-GB phones | `cfg.media.local_queue_max_mb`, `cfg.app.image_cache_mb`, §8.5 storage thresholds |

## 13. Phase mapping (what of this lens lands when; docs/12 numbering)

| Phase | Deliverables | Gates |
| --- | --- | --- |
| 0 | `/packages` sync contract (batch envelope, record types, reason codes, `schema_version`), Drift schema v1 with `@LOCAL` block, outbox, `sync_meta`; server `sync_batch.batch_uuid` replay (M-31), `ingest_registry` (M-30) | contract tests compile in app/api/web |
| 1 | Engine v1: outbox → batch builder → POST → ACK; triggers T1/T2/T3/T6; backoff; bundle full+304; `day_open`; memo counter; printer v1 with golden memo; APK split + size gate; battery protocol run #1 | T-1-20..35 |
| 2 | Triggers T4/T5/T8 (WorkManager), media pipeline + SAS upload + evidence fallback, deltas + stale-bundle policy, config propagation + ack, sale_draft resume, clock-skew UX, edits/dues/stock events, storage/permission edge cases, updater guard | T-2-20..34 |
| 3 | `day_submit`/check-out events, `day_local` ↔ `route_day` mirror, AMO/TSO reconciliation types, shared-phone multi-user engine, device replacement path, TSO wipe guard, PDA-to-Support replay tool | T-3-20..23 |
| 4–6 | sync-health screen (pending > 24 h, photo-pending, orphan devices, rejected by reason), quarantine admin UI, config ack dashboard | lens-data T-6-01 |
| 7 | pilot battery/data measurements on real pilot phones; parallel-run reconciliation; wave gating via `cfg.release.*` | T-7-20 |
