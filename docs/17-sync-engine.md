# 17 — Offline-First Sync Engine, Battery and Data Budgets

> **What this doc decides.** (1) The outbox model: every capture is one SQLite transaction that writes the domain row and an immutable outbox row, the engine reads only the outbox, and three idempotency layers make retries harmless (s2, s4.5, D-380, D-21).
> (2) "Immediately when online" without polling (R5 with R4): triggers T1 to T8, a 5 s debounce with a family hold, backoff 2 s to 300 s, then a hand-over to WorkManager (s4.1, s4.7, D-59, D-381).
> (3) The wire contract and the three recovery protocols: flat ordered `records[]`, batch replay, poison-row isolation, submit settle and server-generation re-sync (s4.4 to s4.12, D-60 to D-65, D-382 to D-384).
> (4) Battery, data and size budgets as release gates with one measurement protocol, on named reference devices, plus the heavy-day variant (s8, D-73, D-398, D-399).
> (5) Printing and memo numbers: RPP02N over Bluetooth Classic, template as data with 7 memo kinds plus slips, `<username>-<yyMMdd>-<seq3>` with bind-ordinal blocks and an overflow range (s9, D-35, D-76, D-392, D-393).

Scope. This document defines the device side of the system and the wire contract between the device and the API. It does not define tables (doc 16), Azure capacity (doc 18), config registry mechanics (doc 19), gate execution (doc 20) or auth and fraud internals (doc 21); it states what it needs from them and cites their decisions. The apps are one Flutter codebase in three flavours (D-01). Markers used: PARITY, IMPROVEMENT, DELIBERATE CHANGE, ASSUMPTION, "unknown; confirm with the business". IDs D-380 to D-406 are minted here (s13.2); all other D-ids are in DECISIONS.md.

## 1 Principles

### 1.1 The twelve rules every later section applies

| # | Rule | Consequence in this document | Source |
| --- | --- | --- | --- |
| P1 | The device is the system of record for a day until the server acknowledges each row | No row is deleted, rewritten or re-derived on the device before its ACK; a server restore re-opens the ACKs (s4.12) | CLAUDE.md 1, 2; D-63 |
| P2 | One write path | A captured fact is `BEGIN; INSERT domain row; INSERT outbox row; COMMIT`; the sync engine reads only the outbox and never joins domain tables | D-380 |
| P3 | Three idempotency layers | Row `client_uuid` plus payload hash in `ingest_registry`; `batch_uuid` replay of the stored response; content fingerprint for a re-sent record with a regenerated uuid. Children carry the parent's uuid. UUID v4 only | D-21, D-62 |
| P4 | Nothing is polled | No foreground service, no socket, no AlarmManager, no repeating timer under 60 s; the 5 s debounce is a one-shot started by a write | D-59, D-73 |
| P5 | Nothing blocks a sale | Not the network, not the printer, not a photo upload, not a config pull, not a token refresh, not `min_version` on an open day, not a rejected row | D-77, D-79, D-130 |
| P6 | Rejections are visible, never silent, never blocking | A rejected or conflicted row is shown on the device and to supervisors; Sales Submit and check-out still work | UI-SR-36; D-64; s4.9 |
| P7 | The server is the authority for derived money and place facts | Device geo verdict, price and totals are conveniences; the server recomputes (geo, prices, points, business date) and stores both verdicts | D-117; docs/05 |
| P8 | Time is corrected, not trusted | `business_date` derives from the trusted capture time; the family date is fixed when the visit opens | D-20, D-389 |
| P9 | Budgets are release gates | A number without a measurement protocol and a gate id is not a budget | D-73, D-399 |
| P10 | Additive evolution | Payloads carry `schema_version`; the server accepts N, N-1, N-2; the local schema is additive so N-1 can open it for one release | D-150, D-79, D-401 |
| P11 | Parity first | Screens, labels, the printed memo and the Sales Submit table match the current apps unless a constraint is broken; each exception is a D-id | CLAUDE.md; sponsor rule |
| P12 | Scope never travels from the client | The batch carries no user, zone or scope ids; identity comes from the token | D-106 |

### 1.2 What this document changes in docs/04, 06, 08 and 09

| Statement in the spec | Replaced by | Why | Decision |
| --- | --- | --- | --- |
| docs/04: sync on "sale saved, manual sync, end-of-day" plus a periodic job | Eight triggers T1 to T8 including validated connectivity regained | R5: immediate, without polling | D-59 |
| docs/09: `POST /sync/batch` groups records in per-type arrays | One ordered flat `records[]` with `type`, `family_uuid`, `rank` | Ordering across types is the point; one round trip also fills the reconcile screen | D-60 |
| docs/04: one day state machine | Ownership split: `route_day`, user attendance, zone final submit, `supervisor_day` | Seed finding 10 | D-27 |
| docs/04: "Sales Submit (dues cleared)" | Dues only warn; submit is an outbox event completed offline | Blocking on legitimate credit breaks selling | D-64, D-173, D-383 |
| docs/04: "clear per-day working data on a clean final-submit" | Purge by business-date age, never rows that are unsynced or rejected | Reprints and Sale History need history | D-83 |
| docs/04: "~90 MB APK" | 74 to 80 MB is the Apsis APK file, 92 to 101 MB is its installed size; ours is at most 30 MB per ABI | Name which size the baseline is | D-224, D-73 |
| docs/06: server-assigned or device-prefixed memo number | Composed on the device in the memo transaction | Offline-safe | D-35 |
| docs/06: sale ends "QC then print" | Print is the commit; QC and Print are independent buttons | Manual flow | D-77 |
| docs/08: TSO logout wipes all local data | Wipe only on a fully reconciled device | CLAUDE.md 1 and 2 beat docs/08 | D-69 |
| docs/09: `POST /day/sales-submit` is an online call | Outbox event; the endpoint stays for web and admin | Wi-Fi-only phones close the day in the field | D-64 |
| docs/06: microphone "confirm need" | Not requested | The audio prompt is a camera-plugin side effect | D-115 |

Proved by: T-1-20, T-1-21, T-1-37.

## 2 Local store

### 2.1 Files, durability and encryption

| Item | Decision | Why | Gate |
| --- | --- | --- | --- |
| One SQLite file per user, `aron_u<user_id>.db`, in app-private storage (`getApplicationSupportDirectory()`), plus `aron_device.db` for device-wide state (binding, printer, preferences, user index) | DEFAULT | Shared phones: user A's rows must survive B's login and upload under A's token (D-66); wipe and purge are per user | T-3-21 |
| `PRAGMA journal_mode=WAL; synchronous=FULL; foreign_keys=ON; busy_timeout=5000` | Fixed | FULL costs about 1 ms per commit on eMMC (planning estimate; measured in T-1-36); a sale must survive a battery pull; WAL lets the WorkManager isolate read while the UI writes | T-1-24 |
| Encryption: SQLCipher with a per-user 256-bit key wrapped by the Android Keystore (not password-derived, so the engine can flush another user's rows) | D-67: DEFAULT; MUST-CONFIRM (by 1b): security owner sign-off | Outlet phone and owner are on 8,500 shared phones (docs/22 P-12). Fallback rule written in D-67: if the APK exceeds 30 MB per ABI or the battery gate fails, use Android file-based encryption only and record a new decision | T-1-36 |
| `android:allowBackup="false"` and data-extraction rules that exclude the DB, secure storage and media | DEFAULT | A restored DB on another phone would carry a bound device's outbox and counters; registry dedupe would absorb the rows but the memo counters and bind ordinal would collide | T-1-37 |
| `PRAGMA quick_check` at open (about 100 ms on a 10 MB DB, planning estimate) | Fixed | Corruption path in s11 (E-15) | T-2-23 |
| Forward-only Drift migrations; outbox payloads are never transformed; `schema_version` is inside each payload | D-79, D-401 | App update with pending rows | T-2-23, T-2-33 |
| Size guard: `page_count x page_size` checked at start; `incremental_vacuum` after the daily purge; storage thresholds in D-404 | DEFAULT | 16 to 32 GB phones are mostly full | T-2-30 |

### 2.2 The `@LOCAL` column block (every captured table)

| Column | Type | Meaning | Note |
| --- | --- | --- | --- |
| `client_uuid` | TEXT PK | UUID v4 generated when the screen opens, never when a button is pressed | Duplicate taps cannot create a second row (E-16) |
| `family_uuid` | TEXT | The visit's uuid for visit children; for root records its own uuid, or the shared uuid of the transaction that wrote it | s2.3 |
| `business_date` | TEXT `YYYY-MM-DD` | Asia/Dhaka date from the trusted capture time of the family (s5.5) | Fixed per family at visit open |
| `captured_at_device` | INTEGER | Wall clock, epoch ms UTC | Informational |
| `captured_elapsed_ms`, `boot_id` | INTEGER, INTEGER | `SystemClock.elapsedRealtime()` and `Settings.Global.BOOT_COUNT` at capture | Inputs of the trusted time (s5.5) |
| `clock_offset_ms` | INTEGER NULL | Server time minus device wall time known at capture; NULL means never synced | Fallback when the boot changed |
| `captured_offline` | INTEGER | 1 if no validated connectivity at capture | Telemetry, not a fraud signal |
| `route_id` | INTEGER | Route captured on, from the bundle assignment | Seed finding 3: never derived from the outlet later |
| `acting_for_user_id` | INTEGER NULL | Route assignee when the capturing user is a substitute, an AMO or a different user on the phone | D-85, D-66 |
| `bundle_version`, `config_version` | TEXT, INTEGER | Bundle and config in force | The server re-checks as of capture time (D-87) |
| `bundle_stale` | INTEGER | 1 when captured on a bundle older than its business date | D-70 |
| `parallel` | INTEGER | 1 when `cfg.flag.parallel_run_mode` is not `off` | D-152 |
| `app_version`, `schema_version` | TEXT, INTEGER | Build and payload shape | D-401 |
| `sync_state` | TEXT | `pending`, `syncing`, `synced`, `rejected`, `conflict` mirrored from the outbox in the ACK transaction | s5.1 |
| `attempt_count`, `last_error_code`, `last_error_at`, `server_ack_at` | INTEGER, TEXT, INTEGER, INTEGER | Delivery bookkeeping | The only columns that change after commit |

Rows are immutable after commit except the delivery columns. A correction is a new row (`supersedes_client_uuid`, `against_memo_client_uuid`, `memo_void`, `due_collection`, `visit_close`), never an update (D-22).

### 2.3 Outbox: the one queue (D-380)

| Column | Meaning |
| --- | --- |
| `seq` INTEGER AUTOINCREMENT | Global local order; the send order within a priority class |
| `client_uuid` UNIQUE, `record_type`, `family_uuid`, `family_rank` | Identity and place in the family (ranks are in the record-type table of this section) |
| `route_id`, `business_date` | For per-route-day pending counts and purge |
| `payload_json`, `payload_sha256`, `schema_version` | Immutable snapshot as sent; the hash is what the registry stores |
| `state` | `pending`, `held`, `syncing`, `synced`, `rejected`, `conflict` |
| `next_attempt_at`, `attempt_count`, `batch_uuid`, `last_error_code`, `last_error_msg` | Backoff and last outcome; `attempt_count` counts only retryable rejections (s4.7) |
| `created_at`, `acked_at`, `seen` | `seen` is the SR's acknowledgement of a rejected row; purge never removes an unseen rejected row |

Family-level state lives in `outbox_family(family_uuid PK, last_activity_at, hold_until, closed, fail_count, released_at)` so hold, valve and skip-ahead are one row per family, not per record.

Indexes: `(state, next_attempt_at, seq)`, `(family_uuid)`, `(business_date, state)`, `(route_id, business_date, state)`.

Invariants, each tested:

| Invariant | Test |
| --- | --- |
| For every captured row there is exactly one outbox row, in the same transaction; the domain row's `sync_state` changes only inside the ACK transaction | T-1-20 |
| `payload_sha256` equals the hash of the row as stored at commit | T-1-20 |
| The engine never reads a domain table to build a payload | T-1-20 (static check plus runtime trace) |
| `synced` rows are kept `cfg.app.outbox_keep_days` (3) so the re-sync window holds: `outbox_keep_days x 24 >= cfg.sync.resync_window_h + 24` (bounds 1 to 72 for the window, so the keep days must be at least 4 when the window is set to 72) | T-1-56 |
| `rejected` and `conflict` rows are kept `cfg.app.rejected_keep_days` (30) and never while unseen | T-2-31 |

Record types (one canonical enum, generated from `/packages/contract`; doc 16 tables follow these names; D-401 governs evolution). Rank is the position inside a family; "root" means a record that is not a child of a visit: it is its own family, or shares the `family_uuid` of the transaction that wrote it (one stock Save writes one family of `stock_movement` rows, so they leave in one batch).

| Type | Family and rank | Captured by | Note |
| --- | --- | --- | --- |
| `visit` | rank 0 of its family | SR, AMO, TSO | Immutable open facts: outlet, route, fix, geo verdict, force reason, kind (sr_call, amo_control_call, amo_joint_call, tso_visit), `acting_for_user_id` |
| `visit_close` | rank 1 | same | Outcome (D-38), `call_started_at`, `ended_at`, `is_zero_sale`; separate from `visit` so the visit is immutable at open (s3.3). Doc 16 decides storage (D-405, OI-17-04) |
| `memo` | rank 1 | SR, AMO | Kinds and zero sale per D-36; carries `memo_no`, components of D-18 |
| `memo_line` | rank 2 (parent memo) | SR, AMO | At most 60 lines (`cfg.sale.max_lines_per_memo`, D-246); the slide reward is a line with `is_free` |
| `qc_entry_line` | rank 1 | SR, AMO | One row per SKU and fault type (D-34) |
| `drp_collection`, `survey_response`, `price_compliance`, `content_view` | rank 1 | SR, AMO | Slide, POSM survey, price check, AV and KV views |
| `distribution_check` (rank 1), `distribution_check_line` (rank 2) | AMO control call | AMO | Per-brand present, OOS, POSM |
| `call_assessment` | rank 1 | AMO joint call, TSO visit query | `kind` joint_call or retailer_questionnaire |
| `print_job` | rank 2 (parent memo) | SR, AMO | Print attempts, `user_confirmed`, `print_count` enrichment (F-SR-073) |
| `due_collection` | rank 1 during a call, else root | SR, AMO | Whole-memo settlement (D-37) |
| `memo_void` | root | SR, AMO | Event with reason, fix, retailer acknowledgement (D-86) |
| `attendance_event`, `stock_movement`, `cash_handover`, `due_dispute` | root | SR, AMO | Check-in or out; issue, return, damaged, short; settlement; balance dispute (F-SR-051, F-SR-052, F-SR-071) |
| `day_open`, `day_submit`, `day_exception` | root | SR, AMO | `day_submit` is always the last record of the day's sequence (D-64) |
| `visit_skip` | root | SR | Outlet marked not reached without opening a visit (D-38) |
| `outlet_change_request` (+ `outlet_photo_meta` rank 1) | root | SR, AMO | new, close, info, cluster, base update; carries route and cluster (D-43) |
| `verification_event`, `cover_request`, `risk_review` | root | AMO, TSO | Verify an outlet request; assign same-day cover; review a fraud signal (D-85, D-109) |
| `redemption` (+ `redemption_line` rank 1), `gift_photo_meta` | root | SR | A redemption is a batch of lines (D-41) |
| `task` (create), `task_event` (resolve) | root | AMO, TSO create; SR resolves | |
| `leave_application`, `visit_plan` (+ `visit_plan_outlet` rank 1), `feedback` | root | TSO | Queued writes (D-82) |
| `config_ack`, `device_integrity`, `consent_accept`, `media_meta`, `activity_log` | root | all | `device_integrity` also carries the readiness block of s7.1; `activity_log` rows are at most 120 B and at most 50 per batch |

Not record types: `final_submit` (online-only, D-262), device telemetry (an envelope object capped at `cfg.telemetry.device_max_bytes_per_day` = 1,024 bytes, D-136), and server-side `attribution_event` (admin action).

### 2.4 Media queue

| Column | Meaning |
| --- | --- |
| `client_uuid` PK | Equals the server `media_object.client_uuid` |
| `purpose` | outlet_capture, force_sale, base_update, survey, redemption, gift, feedback, support_dump |
| `ref_type`, `ref_client_uuid` | The owning record; the record also carries `photo_client_uuid` and the deterministic `blob_path` at capture |
| `local_path`, `bytes`, `sha256`, `phash`, `width`, `height`, `mime` | File facts; `phash` is the perceptual hash of D-75 |
| `lat`, `lng`, `accuracy_m`, `is_mock`, `captured_at_device`, `business_date` | The fix at the shutter; never written into EXIF |
| `evidence` | 1 for force-sale and outlet-location photos: mobile fallback after `cfg.media.evidence_mobile_fallback_h` (6) |
| `state` | queued, sas_requested, uploading, uploaded, linked, failed, abandoned |
| `blob_path`, `sas_url`, `sas_expires_at`, `attempt_count`, `next_attempt_at`, `last_error_code`, `uploaded_at`, `linked_at` | Upload bookkeeping |

`blob_path` is `photos/{business_date}/{device_uuid}/{client_uuid}.jpg` (D-75). The local file is deleted only after the `linked` ACK and `cfg.media.local_keep_days` (2).

### 2.5 Reference cache

| Local table | Mirrors | Key | Note |
| --- | --- | --- | --- |
| `ref_route`, `ref_route_assignment_today` | route, assignments resolved for the bundle date | id | `visit_days_mask`, `is_planned_today`, `acting_role`, `route_kind` |
| `ref_outlet` | outlet plus open due, loyalty balance, suggested quantity, programme flags, provisional flag, `name_sort_key`, `location_confirmed`, `price_type` | server id, or `client_uuid` for a locally created outlet | `lat`, `lng` nullable (D-95) |
| `ref_open_memo` | credit memos with a due | memo `client_uuid` | Recomputed locally with pending `due_collection` rows; carries the balance source date (F-SYS-068) |
| `ref_sku`, `ref_price`, `ref_sales_plan`, `ref_offer` | product tree, effective-dated prices (outlet and cc lists), zone plan, promotions | id, `valid_from` | `price_list_date` is stored on each memo (F-SYS-045) |
| `ref_target`, `ref_achievement_mtd` | target and month-to-date achievement | scope, product, month | The home card adds local sales to the bundle's MTD (D-58) |
| `ref_task`, `ref_gift_assignment`, `ref_survey_def`, `ref_rubric_def`, `ref_content` | tasks, Astha gifts, surveys, rubrics, AV and KV manifest | id | Content files are in the bounded LRU cache |
| `ref_config` | resolved `cfg.*` for the user's scope plus scheduled values | key, scope | Plus `config_version` in `sync_meta` |
| `ref_template` | print template JSON per kind and `cfg.print.template_version` | kind | Template is data (D-392) |
| `ref_meta` | per section `server_max_updated_at`, `row_count`, `fetched_at` | section | Drives `?since=` (s6.3) |

### 2.6 Session and device tables

| File | Table | Holds |
| --- | --- | --- |
| `aron_device.db` | `device_state` | `device_uuid`, bound users, `bind_ordinal` per user and date, printer MAC and name, last app version, integrity snapshot, free storage, checked-at |
| | `user_index` | `user_id`, username, role, DB file, last login, pending rows, a key reference to the refresh token and offline verifier in secure storage |
| | `prefs` | language, `wifi_only_photos`, OEM guidance seen, last support code |
| `aron_u<id>.db` | `sync_meta` | Identity, `schema_version`, `bundle_version`, `bundle_business_date`, `config_version`, `server_time_offset_ms`, `last_batch_uuid`, `last_sync_at`, `batch_max_rows_effective`, `consecutive_failures`, `next_attempt_at`, `sync_hold_until`, `server_generation` |
| | `day_local` | Per route and date: device state, server state, opened, check-in, first and last visit, pending and rejected counts, last `server_totals`, submit uuid and times |
| | `memo_counter` | `(business_date, bind_ordinal, next_seq)` |
| | `print_job` | Local print attempts (s9.4) |
| | `sale_draft` | `(visit_client_uuid, step, draft_json, updated_at)` for kill-and-relaunch (s3.4) |
| | `sync_journal` | Per batch: `batch_uuid`, `row_set_sha256`, row counts, bytes raw and gz, trigger, network type, battery, times, HTTP status, result, duration, replayed |
| | `geo_fix_local` | Every fix: purpose, lat, lng, accuracy, `is_mock`, provider, `fix_age_ms`, `time_to_fix_ms`, passive radio environment (D-110) |
| secure storage | time anchors, refresh tokens, offline verifier, DB keys, device key alias | Never in SQLite |

### 2.7 Footprint and purge rules (D-245, D-83; resolves G-sync-20 and G-sync-17)

The planning draft sized a 50-outlet route at 520 rows. docs/22 measures about 280 rows per SR-day and the design value is 350 (doc 18 s1); a p99 route of 112 outlets at 2.5 lines per memo is about 800 rows. At about 450 bytes per row including the outbox snapshot:

| Component | Standard day (350 rows) | Heavy day (800 rows) | Retention | Source |
| --- | --- | --- | --- | --- |
| Captured rows plus outbox | 0.16 MB | 0.36 MB | 7 days (`cfg.app.local_history_days`) | computed from the row sizes above (ASSUMPTION: 450 B per row) |
| Reference cache | 0.5 MB, replaced not accumulated | 0.8 MB | one bundle | planning estimate; verified by T-1-36 |
| Photos awaiting Wi-Fi | 1.5 MB (10 x 150 KB) | 3 MB | cap `cfg.media.local_queue_max_mb` (50) | D-75 |
| Image cache | 40 MB | 40 MB | LRU, `cfg.app.image_cache_mb` | F-SYS-029 |
| Total on disk | below 60 MB | below 65 MB | | installed app at most 70 MB (D-73) is a separate gate |

Purge (F-SYS-028, D-83): by `business_date` age, never rows that are unsynced or rejected, never "on final submit"; synced outbox rows after `cfg.app.outbox_keep_days`; `incremental_vacuum` after the purge. The Sale History window is 7 days (`cfg.app.local_history_days`) with an online fallback `GET /memos?outlet=&date=` for older dates (resolves G-sync-17 and G-man-013's offline banner).

Proved by: T-1-20, T-1-24, T-1-39, T-2-23, T-2-30, T-3-21.

## 3 Capture rules

### 3.1 Commit points

| Step | Commit moment | Local transaction contents | Outbox rows | Decision |
| --- | --- | --- | --- | --- |
| Attendance check-in | Press-and-hold confirmed (`cfg.app.hold_to_confirm_ms`, 1000, ASSUMPTION) | `attendance_event(in)` with the fix, or no fix (allowed) | one root, priority class 1 | D-209, F-SR-011 |
| Stock load | Save | The screen shows today's loaded total per SKU READ-ONLY, and a Save posts ONLY the entered increment as one `stock_movement` per SKU with a non-zero increment (soft ceiling `cfg.stock.max_issue_qty`); `stock_slip_printed = false`. A same-values re-save (the same SKUs and quantities within `cfg.stock.resave_guard_window_min`, 5) is refused on the device as a double tap (a fingerprint on the movement family), and a repeated Save of different values adds a second increment on purpose. An explicit "correct total" action (`cfg.stock.correct_total_enabled`) posts a signed `adjustment` movement (new total minus loaded total, with a reason), never an overwrite; the AMO stock screen follows the same rule (D-580, F-SR-081, F-AMO-049; MQ-68 confirms the Apsis behaviour) | one family per Save (shared `family_uuid`) | Q-UI-03 default: Save works, Print is retryable; `cfg.stock.require_printed_slip`; T-2-161 |
| Open visit | Outlet selected | `visit` domain row with the fix and geo verdict; `outbox_family` row; outbox row for `visit` in `held` | `visit` held | D-78, D-74 |
| Start-call prompt (`cfg.sale.call_start_prompt` true) | "আপনি কি কল শুরু করতে চান?" | "হ্যাঁ": `call_started_at` set in the draft. "না": `visit_close(outcome = abandoned)` and the family is released; not counted as visited | `visit_close` | D-78, D-38 |
| Order entry | Every change | `sale_draft` upsert (debounce 100 ms); not an outbox write | none | s3.4 |
| Proceed ("এগিয়ে যান") | Tap | Draft memo and visit state persisted so a kill resumes at Review | none | D-77, G-man-006 |
| Print, dialog 1 "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে" (`cfg.sale.require_printer_before_sale` false) | "হ্যাঁ" | The commit: `memo`, `memo_line` x n, `qc_entry_line` x n, `drp_collection`, `survey_response`, `visit_close`, `memo_counter` increment, outbox rows set `pending` in rank order, `sale_draft` deleted, local stock and due ledger updated | the whole family | D-77 |
| Print, dialog 2 "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" | "হ্যাঁ" | `print_job(queued)`; `printed_at` set on the first success | `print_job` after the attempt | D-77; s9.4 |
| Product QC | Save in the QC sheet | Entries inside the draft; completing QC locks edit at that outlet | in the memo commit | D-203, D-201 |
| Zero sale | Confirm "আপনি কি জিরো (০) বিক্রয় করতে চান?" plus one outcome reason | `memo` with `line_count = 0` (consumes a number), `visit_close(zero_sale_*)` | the family | D-36, D-38; the AMO manual shows no zero-sale flow, so the AMO is assumed to follow the SR (G-man-006) |
| Due collection | Confirm | `due_collection` for the whole memo (D-37), local ledger | root or rank 1 | D-37 |
| Edit | Re-entered and confirmed inside the geofence, before QC at the outlet | New `memo` with `supersedes`, new number | the new memo's family | D-201, D-86 |
| Void | Confirmed with a reason | `memo_void` | root | D-86 |
| Outlet request | Save then confirm | Request, photo meta, `media_queue` row | root | D-43 |
| Day close | Sales Submit, check-out | `day_submit`, `attendance_event(out)` | root, last in sequence | D-64 |

### 3.2 Visit outcomes, skip and abandonment

| Rule | Value | Source |
| --- | --- | --- |
| Outcome codes (config list) | sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned | D-38, `cfg.visit.outcome_codes` |
| The zero-sale dialog asks one reason | one tap; default `zero_sale_stock_ok` is not preselected | D-38; whether Apsis records a closed-shop outcome is unknown; confirm with the business (Q50), proceed as IMPROVEMENT |
| Skip record | `visit_skip` with the outlet, reason `not_reached`, no fix, no geo gate | D-38, F-SR-057 |
| Abandonment | A visit with no close and a resumable `sale_draft` older than `cfg.visit.abandon_min` (120) is closed on the next engine start with `outcome = abandoned`; abandoned visits sync and are excluded from visited and CPR | D-38, `cfg.kpi.count_abandoned_visits` false |
| Closed-streak signal | Three consecutive `closed` for one outlet raises an AMO task server-side | `cfg.visit.closed_streak_task` |

### 3.3 Family atomicity, the hold and the valve (D-59, D-405)

1. A visit family is the `visit` record (rank 0), its `visit_close` and its children. The `visit` outbox row is written `held` at outlet open with a final payload (open facts only); `visit_close` and the children are written when the family closes (memo commit, zero-sale confirm, "না" at the start prompt, abandonment).
2. Closing flips every held row of the family to `pending` in the same transaction and starts the 5 s debounce (T1). One POST leaves per visit, which is the load model of doc 18 s1 (about 240,000 trickle batches a day).
3. The valve: `outbox_family.hold_until = last_activity_at + cfg.sync.family_hold_max_s` (180 s; bounds 60 to 900). Activity is any family write, `sale_draft` autosave or step change. If a visit is idle for 180 s the held `visit` row is released alone as `pending`; the later `visit_close` and children follow when the call closes. The dashboard then shows an open visit rather than hiding it. The valve exists so that an SR who walks away or an app that dies does not hold the visit forever; abandonment (120 min) then writes the close.
4. Once a family is released, later rows of that family are written `pending` directly.
5. The batch builder never splits rows that were written in one transaction across two batches (s4.3). A late addition such as a QC entry saved after the memo commit is a new row of the same family with rank above 0; the server accepts a child whenever its parent exists and parks it otherwise.

### 3.4 Kill-and-relaunch

| Kill point | State on relaunch | Behaviour | Gate |
| --- | --- | --- | --- |
| Visit open, nothing else | `visit` held, no draft | If the visit is under 120 min old and a draft exists the app offers "চলমান কল পুনরায় শুরু করুন?" (authored string); if `hold_until` has passed, the engine start releases the held `visit` as the valve would | T-1-24 |
| Mid order entry | `sale_draft` at the last autosave | Resume at the saved step; kill-safe because the draft write is one row | T-1-24 |
| Inside the memo commit transaction | Nothing or everything | SQLite atomicity: either a complete memo family with its number, or none and the draft intact; the counter is not advanced by a rolled-back transaction because it is incremented in the same transaction | T-1-24 |
| After commit, before the first printer byte | Committed memo, `print_job` queued or none | The memo shows an "unprinted" icon; Reprint from the Memo menu | T-1-24 |
| Mid-print | `print_job.state = printing` older than 60 s | Marked `failed`; the memo exists; no duplicate number | T-2-32 |
| Mid-batch send | rows `syncing` | At engine start `syncing` older than 120 s returns to `pending`; the same `batch_uuid` is reused if the row set is unchanged | T-1-28 |
| Mid-ACK application | one local transaction | All rows of the batch flip or none; the resend is `replayed: true` | T-1-29 |
| Mid-photo compression or upload | file and `media_queue` row consistent | No orphan file; a block blob commit is atomic, so a blob is absent or complete | T-2-22 |
| Mid-migration on update | Drift migration transactional | Pending rows intact and uploadable | T-2-23 |

### 3.5 Edit, void and supersede: device guards

| Rule | Device enforcement | Server re-check | Decision |
| --- | --- | --- | --- |
| Edit only inside the outlet geofence, same business date, before Sales Submit | Edit button disabled with a reason | geo and date re-checked at ingest | D-86, docs/06 step 6 |
| Edit blocked at an outlet once QC was done there (outlet level, not per memo) | Disabled | DQ rule in doc 16 s7 | D-201 |
| Reasons: the first is "ভুল SKU নির্বাচিত।" (wrong_sku); the other two are captured from the live app; `wrong_outlet` is dropped because the outlet is read-only on edit | List from `cfg.memo.edit_reasons` | reason code validated | D-200 (MUST-CONFIRM by 2b: MQ-18) |
| Chain depth at most 3 (edit of an edit) | Disabled beyond | rejected `chain_too_deep` | D-86 |
| Void is an event with reason, fix and retailer acknowledgement under the same guards; effects (status, due reversal, stock) happen on the server and in the local ledger; a printable cancel slip is available | `memo_void` | | D-86 (MUST-CONFIRM by 2b: Q43) |
| After the print the old memo is the retailer's paper: the new memo prints "supersedes <memo_no>" | Template field | | D-394 |

### 3.6 Identity on a shared phone

At the first capture of a business date on a device with more than one bound user the app asks "আপনি কি <name> (<code>)?" (F-SR-072, `cfg.auth.confirm_identity_on_first_capture`). "না" opens the user switch. When the capturing user is not the route's assignee (substitute, AMO selling for a dead-phone SR, wrong user caught later) the record stores `acting_for_user_id`; re-attribution is a server event applied at aggregation, never an UPDATE (D-66, D-85). The memo number keeps the capturing user's series: the number is identity, not attribution (D-393).

### 3.7 Fix acquisition (the only location sensor use)

| Rule | Value | Source |
| --- | --- | --- |
| Events that take a fix | outlet open, attendance in and out, force sale, outlet capture, Update Base; one fused balanced-power fix each | D-74 |
| Per-fix procedure | high accuracy for at most 8 s, accept the best fix with `accuracy_m <= cfg.geo.max_accuracy_m` (100); else stop at `cfg.geo.fix_timeout_s` (15) and accept or flag; at most `cfg.geo.refresh_max` (3) manual refreshes | D-74, D-264 |
| Warm-up | one request when the outlet list opens, at most once per 10 min (ASSUMPTION: avoids a second fix per outlet); counted in the fix budget | G-field-13 |
| Reuse | a fix at most `cfg.geo.fix_reuse_max_age_s` (60) old and with `age_s x max(speed_mps, 0.5) <= 30 m` may be reused (ASSUMPTION: only the fix's own speed field is available without another sensor or permission) | D-74 |
| Forbidden | `getLastKnownLocation` for a verdict, a position stream, background location, `ACCESS_BACKGROUND_LOCATION` | D-74, CLAUDE.md 3 |
| Stored per fix | lat, lng, accuracy, `is_mock`, provider, `fix_age_ms`, `time_to_fix_ms`, passive radio environment when a recent system scan exists | D-110 (privacy sign-off MUST-CONFIRM by 2d) |
| No fix | The visit opens as a force sale with reason `no_fix`; an outlet with no stored coordinates opens as a force sale with `no_outlet_location` (D-95) | G-field-13 resolves cold-GPS first outlet |

Proved by: T-1-20, T-1-24, T-1-28, T-1-29, T-2-22, T-2-23, T-2-32, T-2-96, T-3-21.

## 4 Sync engine

### 4.1 Triggers: R5 without polling (D-59)

| # | Trigger | Mechanism | Fires when | Cost | Gate |
| --- | --- | --- | --- | --- | --- |
| T1 | Local write | One-shot debounce timer in the app process, `cfg.sync.debounce_s` (5; bounds 2 to 60; D-261 retires the 10, G-sync-14) after the last outbox write that released rows; held rows excluded | A family closes or the valve releases it | One HTTPS POST per visit, about 2 KB gz body; the radio is already awake for the sale | T-1-31 |
| T2 | App to foreground | `AppLifecycleState.resumed` | Outbox non-empty, or the last bundle check is older than `cfg.bundle.delta_min_interval_min` (30); ALSO, when online and the last server contact is older than `cfg.sync.config_check_min_gap_min` (5), one conditional `GET /config/check` (F-API-083, a 304 of about 300 B or the delta inline), which a tap on "Refresh GPS" and the opening of a visit also send under the same gap rule (D-563, F-SYS-092) | One POST, at most one `GET /sync/bundle` delta per 30 min, at most `cfg.sync.config_check_max_per_day` (24) config checks | T-1-30, T-2-165 |
| T3 | Validated connectivity regained while the app is alive | `connectivity_plus` stream while resumed, then `HEAD /health` (3 s timeout); validation at most once per 10 s (ASSUMPTION: bounds flapping to the 1 POST per 5 s of T-1-31) | A captive or dead Wi-Fi counts as offline until validated | negligible | T-1-31, T-1-32 |
| T4 | Connectivity regained while the app is not running | WorkManager one-off, `NetworkType.CONNECTED`, `requiresBatteryNotLow`, `ExistingWorkPolicy.KEEP`, exponential backoff from 30 s, expedited on Android 12+ with a quota-aware fallback | Enqueued whenever a send failed for lack of network. The OS wakes the app once when the constraint is met: this is R5 at zero polling cost. Below Android 12 it is a plain JobScheduler job and is subject to Doze, App Standby buckets and OEM kills (MIUI, FuntouchOS, ColorOS, HiOS); the guaranteed bound per condition is s4.1b (D-511) | one engine start per wake | T-1-30, T-2-140 |
| T5 | Periodic fallback | WorkManager periodic, `cfg.sync.periodic_min` (15, the platform minimum), flex 5 min, `NetworkType.CONNECTED`, `requiresBatteryNotLow` | Registered only while rows or photos are pending, cancelled when none (D-59) | see the engine-start note below | T-1-37 |
| T6 | Manual Sync | The Sync tile or pull-to-refresh | Always allowed; ignores `next_attempt_at`; opens the reconciliation screen (s4.15) | one POST, one GET | T-1-34 |
| T7 | Sales Submit and check-out | Appends `day_submit` (or `attendance_event(out)`) last, then T1 without debounce, EXCEPT when the only reason the upload is happening now is that the clock gate `cfg.day.checkout_earliest_time` (17:00) has just opened: then the upload starts after `U(0, cfg.sync.checkout_upload_jitter_max_s)` (default 90 s, bound 0 to 120) so that 8,500 phones do not fire in the same second (D-505). The offline-completed local state (`submitted_local`, checked out) is set at the tap and does not wait for the jitter; rows captured earlier were already uploaded by T1 and are never delayed; Manual Sync (T6) ignores the jitter | Device shows "submitted, uploading" until the ACK (s4.11) | one POST | T-1-54, T-4-155 |
| T8 | Wi-Fi connected, media queue non-empty | `connectivity_plus` reports Wi-Fi, or a WorkManager one-off with `NetworkType.UNMETERED` when "photos on Wi-Fi only" is on | Photos only (s4.14) | photos | T-2-26 |

Not used: foreground service, WebSocket or any persistent socket, FCM-triggered uploads (FCM carries urgent config only, s6.7), AlarmManager, repeating timers under 60 s, geofence callbacks, `WakelockPlus` in UI code. A WorkManager job holds its own partial wake lock, at most 90 s per sync (D-73).

Engine-start note (RISK, measured in T-1-36). With the `workmanager` plugin each T4 or T5 run starts a Dart isolate in a headless engine. The planning draft assumed an empty-outbox run costs under 50 ms of CPU; an engine start is more (ASSUMPTION: about 300 ms and 30 to 40 MB of memory on the primary device). T5 is therefore registered only while rows are pending, so no empty run happens in steady state. Decision rule: if T-1-36 measures an empty-or-no-op run above 100 ms CPU, the T4 and T5 entry point becomes a thin native Kotlin `Worker` that reads two counters (`pending_rows`, `pending_media`) from a flag file the app updates on every outbox change and starts the Dart engine only when either is above zero.

### 4.1b OEM and platform conditions: the reach-time bound of pending rows (D-511, G-qa-48, G-qa-33)

R5 says captured rows reach the cloud as soon as signal returns, with no tap. Android limits what any app can promise after the user or the OEM has stopped it, so the promise is stated per condition, with a bound, a mitigation and a server-side backstop. T-2-140 measures every cell on the census top-10 models (D-12, Q31) and FAILS when a cell exceeds its bound with no mitigation that brings it back inside.

| Condition | What the OS does | Bound for pending rows after validated connectivity (P95) | Mitigation | Backstop |
| --- | --- | --- | --- | --- |
| A. App in the foreground | nothing | 60 s (T1, T3) | none needed | none |
| B. Backgrounded, not stopped, battery saver off | WorkManager runs T4 and T5; stock Android honours them; aggressive OEMs may delay or skip | 3 minutes (T4); 15 minutes worst case via T5 | the OEM guidance screen (`cfg.app.oem_guidance`: autostart on, battery "no restrictions" where the OEM offers it) and, if T-1-36 shows an engine start above 100 ms, the thin native Worker of s4.1 | held-rows list (below) |
| C. Backgrounded with Android Battery Saver on | background work deferred, no mobile background photo upload (D-403) | 15 minutes (T5 window) for records | none; records still sync in the foreground | held-rows list |
| D. Force-stopped by the user or an OEM cleaner | Android runs NO WorkManager job, receiver or alarm of the app until the user next opens it (a platform limit, not a defect) | NEXT FOREGROUND LAUNCH PLUS 5 SECONDS: the first foreground launch runs T2 at once, with no debounce, and the first batch starts within 5 s of the first frame | the first launch after a stop shows the pending count on Home | held-rows list |
| E. Rebooted with rows pending | WorkManager re-registers its jobs after boot on stock Android; some OEMs block autostart | 5 minutes after boot with network on stock; otherwise as D | OEM guidance | held-rows list |
| F. App Standby bucket `restricted` | jobs run at most once per 24 h | as D | OEM guidance; an SR who opens the app daily is in the `active` bucket | held-rows list |

R5 acceptance therefore reads (doc 14 s1.2): "within 60 seconds in the foreground and with validated connectivity; within 3 minutes in the background; after a force stop, the moment the app is next opened; and the platform limit is stated, not hidden". Held-rows list (F-SYS-084): the server derives from the headers every request already sends (`X-Pending-Rows`, `X-Last-Sync-Error`, last contact) the devices that reported pending rows and have not made contact for more than `cfg.sla.pending_rows_alert_h` (default 4 hours) or whose last contact left pending rows after 17:30; the list shows user, route, zone, rows, age and device model for the zone TSO and the helpdesk (doc 19 P19) so a human can phone the SR. No new network use is added on the phone.

### 4.2 Debounce, single flight and connectivity state

| Rule | Value | Why |
| --- | --- | --- |
| Single flight | A process-wide mutex plus an `flock` on `aron_sync.lock` shared by the app isolate and the WorkManager isolate; a trigger during a run sets `rerun_requested` for one more pass; the loser of the lock exits immediately | Prevents two concurrent batches from one device (a race the server also tolerates by `batch_uuid` UNIQUE) |
| Never concurrent with the updater | An install waits until no batch is `syncing`, at most 60 s (s10.2) | G-sync-15 |
| Connectivity validation | `HEAD /health` 3 s on a state change; the result is cached for 10 s | Captive Wi-Fi |
| Online indicator (D-400) | "অনলাইন" is shown only if the last successful server contact (any 2xx or 304) is at most 120 s old or a validation just passed; otherwise the indicator reads offline even if Android reports a network | UI-SR-38: "online but blocked" must read offline |
| Low battery (D-403) | Below 15 percent and not charging, or Android Battery Saver on: no background mobile photo upload and no pre-fetch; foreground record sync continues | R4; E-20 |
| Connectivity stamp (D-509) | The first validated success after an offline period writes `connectivity_regained_at` (trusted time) to `sync_meta`; the next batch carries it in the daily telemetry object (s4.4). The R5 SLO is `ack_time - max(captured_at, connectivity_regained_at)` | R5 |
| First foreground launch drains the outbox (D-511) | `AppLifecycleState.resumed` on a cold start runs T2 with no debounce; the first batch starts within 5 s of the first frame when rows are pending and the network validates | condition D of s4.1b |

### 4.3 Batch builder

| Rule | Value | Source |
| --- | --- | --- |
| Eligible rows | `state = pending`, `next_attempt_at <= now`, family released | D-382 |
| Rows per batch | at most `cfg.sync.batch_max_rows` (200; bounds 50 to 500); `batch_max_rows_effective` halves (floor 25) on an API 413 and recovers by 25 percent per success up to the config | planning draft |
| Bytes per batch | at most `cfg.sync.batch_max_kb_raw` (256 KB uncompressed; bounds 64 to 1,024), about 60 KB gz | |
| Family atomicity | Rows written in one transaction share a `family_uuid` and are never split across batches, even if that overshoots the row cap by one family | D-382 |
| Worst-case family | visit 1 + visit_close 1 + memo 1 + lines at most 60 (D-246) + QC lines at most 60 (device cap, ASSUMPTION) + drp at most 20 + survey at most 30 + print jobs 3 = about 175 rows; with the 200-row cap the largest batch is about 375 rows, below the server cap of 500 rows (D-116) | computed; the planning draft assumed a family of at most 40 rows, which memos of 60 lines break |
| Order | Priority class, then `seq` (creation order); inside a family by `family_rank` | the priority classes of this table |
| Priority classes | 1: `attendance_event(in)`, `day_open`, `config_ack` (tiny and time-sensitive). 2: families oldest first, including `day_submit` and `attendance_event(out)` in `seq` order. 3: `activity_log`, `content_view`, `device_integrity`, only when the batch has room. `day_submit` is never moved ahead of a lower-`seq` row (D-64) | G-sre-02 |
| `batch_uuid` reuse | If the chosen row set hashes to the `row_set_sha256` of the last unfinished journal entry, reuse that `batch_uuid`; otherwise a new UUID v4 | s4.8 |
| One user at a time | The engine builds a batch for one user DB and signs it with that user's token | s7.3 |

### 4.4 Wire contract: `POST /sync/batch`

Headers (names exactly as doc 15 s8; those marked + are doc 17 additions needed by this protocol):

| Header | Direction | Meaning |
| --- | --- | --- |
| `Authorization: Bearer <access>`; `Content-Encoding: gzip`; `Accept-Encoding: gzip` | request | gzip level 6 |
| `X-Device-Id`, `X-App-Version`, `X-Config-Version`, `X-Device-Proof` | request | Device identity, build, config in force, proof of the device key (doc 21 s2) |
| `X-Batch-Attempt`, `X-Pending-Rows`, `X-Last-Sync-Error` | request | Attempt number of this row set; pending count; last failure code. Feed SLI and support visibility (D-138, G-sre-19) |
| `X-Schema-Version`+, `X-Bundle-Version`+, `X-Sync-Trigger`+, `X-Device-Time`+ | request | Payload shape (the edge and the API read it before parsing), bundle in force, trigger name for telemetry, device wall clock for skew without trusting the body |
| `X-Config-Version`, `X-Server-Generation`, `X-Server-Time`+, `X-Bundle-Version-Current`+, `Retry-After`; on a generation change `GET /sync/generation` returns `{generation, kind, restore_point_utc, lost_after_utc, minted_at}` (F-API-070, s4.12) | response | Config and generation (s4.12, s6.7); server time for the anchor (s5.5); newest bundle (s6.3) |

Body. Identity comes from the token; the body carries no user, zone or scope id (P12).

```json
{ "batch_uuid": "b5c1…", "schema_version": 7, "app_version": "1.4.2+142", "trigger": "sale_saved",
  "device_counts": { "2026-10-04": { "visit": 23, "visit_close": 23, "memo": 21, "memo_line": 104, "qc_entry_line": 6,
                     "attendance_event": 1, "stock_movement": 38, "due_collection": 2, "outlet_change_request": 1 } },
  "time_anchors": [ { "boot_id": 412, "server_time": "2026-10-04T05:12:44.120Z", "elapsed_ms": 18340211 } ],
  "telemetry": { "network": "cellular", "battery_pct": 63, "free_mb": 1100, "pending_rows": 9,
                  "day": { "d": "2026-10-04", "b_mob": 412000, "b_mob_media": 0, "b_wifi": 1530000, "cpu_ms": 41000, "wake_ms": 215000, "starts": 7,
                           "gps": 62, "bat": [96, 81, 58], "plug": 0, "regained": "2026-10-04T09:41:07Z" } },
  "records": [
   { "type": "visit", "client_uuid": "v1…", "family_uuid": "v1…", "rank": 0, "route_id": 10231, "business_date": "2026-10-04",
     "captured_at_device": "…", "captured_elapsed_ms": 18330000, "boot_id": 412, "clock_offset_ms": -1840,
     "bundle_version": "2026-10-04:3", "config_version": 318, "sig": "…",
     "payload": { "outlet_id": 4417, "lat": 23.79, "lng": 90.41, "radius_m_used": 100, "mock_location": false, … } },
   { "type": "memo", "client_uuid": "m1…", "family_uuid": "v1…", "rank": 1,
     "payload": { "visit_client_uuid": "v1…", "memo_no": "sr334001-261004-017", "gross_mtk": 482500, … } },
   { "type": "memo_line", "client_uuid": "l1…", "family_uuid": "v1…", "rank": 2,
     "payload": { "memo_client_uuid": "m1…", "line_no": 1, "qty_entered": 100, "unit_entered": "stick", … } },
   { "type": "visit_close", "client_uuid": "c1…", "family_uuid": "v1…", "rank": 1, "payload": { "outcome_code": "sold", … } } ] }
```

Response (always the envelope `{ message, success, data }` of docs/09; `data` shown):

```json
{ "batch_uuid": "b5c1…", "replayed": false,
  "accepted":  { "visit": 1, "visit_close": 1, "memo": 1, "memo_line": 5 },
  "rejected":  [ { "client_uuid": "s7…", "type": "survey_response", "reason_code": "unknown_question", "retryable": false } ],
  "conflicts": [ { "client_uuid": "m0…", "type": "memo" } ],
  "parked":    [ { "client_uuid": "l9…", "type": "memo_line", "waiting_for": "m9…" } ],
  "resolved":  [ { "client_uuid": "q3…", "resolution": "accepted_with_fix" } ],
  "server_totals": { "2026-10-04": { "visit": 23, "memo": 21, "memo_line": 104, "rejected": 1, "conflict": 1 } },
  "day_states": [ { "route_id": 10231, "business_date": "2026-10-04", "state": "synced", "submit_pending_rows": 0 } ],
  "hold_s": 0,
  "directive": { "id": "d41…", "type": "send_pda", "expires_at": "2026-10-05T10:00:00Z", "sig": "…" } }
```

Directive (D-594, G-qa-133). The only server-to-device fields used to be `hold_s`, the config version, `day_states` and the generation, and push is off in the pilot, so a device that cannot sync (the one support most needs to reach) could be asked for nothing. `directive` is a SIGNED, idempotent per-device instruction from P19 (doc 19 s5.4b): `send_pda`, `send_ping` (return device state, F-API-046), `redownload_bundle`. It can appear in any response, in a 401 body or in a config delta; the app verifies the signature with the config public key, acts on it at the next FOREGROUND contact (never in the background, never a timer), answers with a `directive_ack` record and ignores an expired or already-acknowledged id. A directive cannot change data, grant rights or run code; a device that never makes contact leaves it `pending` until it expires (`cfg.support.directive_ttl_h`, 24).

Daily telemetry object `telemetry.day` (D-507, D-509; sent at most once per business date with the first batch after the date closes or the last batch of the day; the whole telemetry envelope stays at most `cfg.telemetry.device_max_bytes_per_day` = 1,024 bytes, about 250 bytes used):

| Field | Meaning | Source on the device | Feeds |
| --- | --- | --- | --- |
| `d` | business date of the sample | trusted clock | key |
| `b_mob`, `b_mob_media`, `b_wifi` | bytes sent and received by the APP UID on mobile (records, bundle, config, reads), on mobile for photos only, and on Wi-Fi | `TrafficStats.getUidTxBytes/RxBytes` sampled by the app at day start and day end, split by the app's own counters per request class | `fact_device_day.bytes_mobile_app`, `bytes_mobile_media`, `bytes_wifi_app`; SH-19 |
| `cpu_ms` | process CPU time of the app for the day (user plus system) | `Process.getElapsedCpuTime()` accumulated across process starts | `cpu_ms`; SH-20 |
| `wake_ms` | wake-lock time held by the app | the app's own wrapper around the sync wake lock (the only one) | `wake_lock_ms` (exists) |
| `starts` | engine starts (Dart isolate or process starts) | a counter in `sync_meta` | `engine_starts`; SH-20 |
| `gps` | fixes taken | `geo_fix_local` count | `gps_fixes` (exists) |
| `bat` | whole-device battery percent at 08:00, 12:00 and 17:00 Dhaka (first sample after each time, within 30 min) and `plug` (0 or 1: charged at any time since 08:00) | `BatteryManager` sticky intent, no polling: sampled at the next outbox write or foreground event | `battery_pct_08`, `_12`, `_17`, `charged_today`; SH-21 |
| `regained` | `connectivity_regained_at` of the day (the last one) | s4.2 | R5 SLO, s8.10 |

Whole-device battery cannot isolate the app, so `fact_device_day` also stores `app_energy_estimate_mah = a x cpu_ms + b x b_mob + c x wake_ms + d x gps_ms` with coefficients fitted per device class from the Battery Historian runs of T-1-36 and T-2-40 (ASSUMPTION: an estimate, used for fleet comparison and regression detection, never as a lab gate).

Limits (D-116): body at most 1 MiB compressed and 8 MiB decompressed with a 20:1 ratio guard, at most 500 rows, memo lines at most 60, unknown JSON keys rejected. Rate limits are per device and per user in Redis (doc 21 s6). This shape replaces the per-type arrays of docs/09 (D-60).

### 4.5 ACK semantics (what the device may conclude from a 200; resolves G-scale-01 with G-sync-05 and G-sre-14)

| Term | Meaning on the device | Server meaning |
| --- | --- | --- |
| `accepted` | Row becomes `synced`; `server_ack_at` set | Inserted or already present with the same payload hash; counted in `server_totals` |
| `rejected`, `retryable = true` | Back to `pending`, `attempt_count + 1`, after the next bundle or config refresh | Reason is repairable on the device side (stale scope, price list or config unknown, outlet pending approval) |
| `rejected`, `retryable = false` | Terminal `rejected`, shown to the SR | A REJECT-policy rule (doc 16 s7); the payload is kept in `sync_rejected` for supervisors |
| `conflict` | Terminal `conflict` | Same `client_uuid` already registered with a different payload hash (`sync_conflict`) |
| `parked` | Stays `pending`; the device re-sends the parent first | Waiting for a parent that the server does not have (possible only from a foreign client) |
| `replayed: true` | Identical to a fresh 200 | The stored response of this `batch_uuid` for this device (D-62) |
| `resolved` | Row flips to `synced` (accepted with a fix) or `discarded` | A supervisor or admin acted on a quarantined row (IMPROVEMENT) |

Guarantees, each tested: (a) a 200 means the batch transaction committed; (b) every record sent appears in exactly one of `accepted`, `rejected`, `conflicts`, `parked` (partition invariant, T-1-23); (c) a throwing record is isolated by batch-level savepoints and bisection, never a savepoint per record (D-65, D-411, D-521); (d) the response is persisted inside the ingest transaction and Redis is only a cache of it (D-62); (e) the same `batch_uuid` with a different row set returns 409; (f) a voided `client_uuid` stays in `ingest_registry` as a tombstone, so a late upload is rejected `voided_by_admin` and never resurrects a number (D-22).

### 4.6 Response handling per outcome

A response is an API response only if `content-type` is `application/json` and the envelope field `success` is present (D-81). Anything else (a WAF HTML 403, an edge 413, 502 to 504, an empty body) is a transport failure: backoff, same `batch_uuid`, never a terminal state, never a banner (G-sre-10).

| Outcome | Device action | Row effect | Retry |
| --- | --- | --- | --- |
| 200, 200 replayed | Apply the ACK (s4.9); update offset, anchors, config and generation; `consecutive_failures = 0`; if rows remain, send the next batch at once | per row | none |
| 304 on bundle | Nothing changed | none | none |
| 400 malformed or unknown `schema_version` | Journal `failed_permanent`; rows back to `pending` with `malformed`; stop automatic retries of this row set until the app version changes; surface "update required" | pending | until the build changes |
| 401 | `POST /auth/refresh`, then retry the same `batch_uuid` once; on refresh failure keep rows `pending`, set `needs_relogin`, **selling continues** | pending | until re-login |
| 401 with `token_scope_stale` | Same refresh; claims are re-issued; then a bundle delta (a cover assignment reaches the substitute this way) | pending | immediate |
| 403 with `device_unbound` or `wave_disabled` | Stop sync for this user; banner with the reason; capture continues | pending | until fixed |
| 403 with `user_disabled` (D-551) | Capture stops (no new sale or visit); rows ALREADY captured still upload: the device re-authenticates with the upload-only grant (doc 21 s2.5, the same grant a revoked device gets) and the server stores the rows with `entry_source = 'app'`, state `parked_user_disabled`, visible to the zone TSO and support for fix-and-accept, so a dismissal at 17:00 never strands the day's sales; the banner says "আপনার অ্যাকাউন্ট বন্ধ; জমা না হওয়া তথ্য পাঠানো হচ্ছে" (key `sync.banner.user_disabled_upload`) | pending, then parked on the server | immediate |
| 409 (same `batch_uuid`, different row set) | New `batch_uuid`, resend once; a second 409 is `failed_permanent` plus telemetry | pending | once |
| 413 from the API (envelope present) | `batch_max_rows_effective = max(25, floor(n/2))`; rebuild; resend at once | pending | immediate |
| 413 from the edge (no envelope) | Transport failure; also halve once (the edge cap may be lower than ours) | pending | backoff |
| 422 whole-batch validation | Treat the body as `rejected[]` | per row | |
| 429, 503 with `Retry-After`, or a 200 with `hold_s > 0` | Sleep `Retry-After` plus or minus 20 percent jitter, at most 15 min; not counted against `attempt_count`; a version-scoped hold (D-130) is the same signal | syncing to pending | server-directed |
| 500 with envelope (`ERR_INTERNAL`) | Bisect (s4.10), `family_fail_count + 1` for families in the failed set | pending | s4.7 |
| Edge or infrastructure 5xx, TLS error, DNS failure, timeout, reset | `consecutive_failures + 1`; after `cfg.net.fallback_after_failures` (3) TLS or DNS failures try the second hostname (the Front Door default endpoint, D-81); no family counts | pending | s4.7 |
| No network | No attempt; enqueue T4; rows stay `pending` | pending | constraint-driven |

### 4.7 Backoff and hand-over (D-381)

| Item | Rule |
| --- | --- |
| In-process delay | `delay_k = min(cap, base x 2^k) x U(0.5, 1.0)`, `base = 2 s`, `cap = 300 s`, `k = consecutive_failures - 1` (keys `cfg.sync.retry_backoff_s`, `cfg.sync.retry_cap_s`; doc 19 owns the final names, OI-17-06). Nominal sequence 2, 4, 8, 16, 32 s |
| Hand-over | After `cfg.sync.retry_max_inprocess` (5) consecutive failures the engine stops in-process retries and relies on T4 (OS wake on connectivity, backoff from 30 s, capped by the OS at 5 h) and T5 (15 min ceiling while rows are pending). T2 and T6 bypass the schedule |
| `attempt_count` | Counts only `rejected(retryable)` reprocessing. A transport failure is not the row's fault. At `cfg.sync.row_max_retries` (10) the row becomes `rejected(retry_exhausted)` and is surfaced |
| `family_fail_count` | Incremented only for API-originated 500s (the server saw the family and failed); reset on any ACK that contains the family. At `cfg.sync.family_skip_after` (5) the family is skipped (s4.10) |
| Server-directed | `Retry-After` and `hold_s` override the schedule; never shorter than the server says |
| Reset | A 2xx resets `consecutive_failures` and the schedule |

### 4.8 Half-uploaded batch, dropped response and resume

| Case | What happened | Device behaviour | Server behaviour | Converges because |
| --- | --- | --- | --- | --- |
| Body never reached the server | Nothing stored | Rows `syncing` to `pending`; same `batch_uuid` | First sight: ingest | |
| Server processed, response lost | Rows committed; response stored | Resend the same `batch_uuid` | `replayed: true` with the stored response | Same ACK |
| Server crashed mid-transaction | Rolled back | As above | Ingest as new | |
| App killed mid-send | Rows left in `syncing` | At engine start `syncing` older than 120 s returns to `pending`; `batch_uuid` reused if the set is unchanged | Replay or ingest | |
| New rows captured between failure and resend | Row set changed | New `batch_uuid` for the new set | Old uuid may or may not be stored; new rows deduped per row by `ingest_registry` | Row idempotency |
| Re-sent record with a regenerated uuid (a client bug) | Different `client_uuid`, same content | | Content fingerprint catches it (D-21) | Third layer |
| Two concurrent requests from one device | Prevented by the file lock; if it still happens | | `sync_batch.batch_uuid UNIQUE`: the second waits on the row lock then replays | |

### 4.9 Applying the ACK and surfacing rejections

One local transaction:

1. Every sent `client_uuid` not in `rejected`, `conflicts` or `parked`: outbox `synced`, `acked_at = now`; domain row `sync_state = synced`, `server_ack_at = now`.
2. `parked`: stays `pending`; the parent is re-sent first.
3. `rejected(retryable)`: `pending`, `attempt_count + 1`, `next_attempt_at` after the next bundle or config refresh (the engine runs a delta GET first). Retryable codes the device must know: `scope_stale`, `price_list_unknown`, `config_version_unknown`, `outlet_pending_approval`.
4. `rejected(non-retryable)` and `conflict`: terminal, `seen = 0`. Codes the device must know by name: `unknown_sku`, `unknown_outlet`, `business_date_out_of_window`, `schema_invalid`, `duplicate_attendance`, `memo_no_invalid`, `voided_by_admin`, `insufficient_points` (a redemption the device knew was over balance; an over-balance redemption the device could not know is accepted and flagged, D-266), `server_error`, `retry_exhausted`. The authoritative list and the REJECT, PARK, FLAG or CLAMP policy of every code are doc 16 s7 and the generated contract; a FLAG code never reaches the device (D-123).
   A final-submitted zone-day NEVER produces a reject: D-55, DQ-57 and doc 19 s8.4 accept the row, aggregate it and flag it `after_final_submit`, so `day_closed` is not a reject code and the device does not need to know it (D-579, G-qa-115; the first draft listed it here, and an implementer following this list would have rejected an SR's late sales and lost them from the dw). `voided_by_admin` is produced by a tombstone or by the route-day void barrier (doc 16 s6.1, D-577); a month closed by finance parks the row (DQ-40). Gate T-3-159.
5. `resolved`: flip the named rows.
6. Update `day_local` from `day_states` and `server_totals`; recompute pending and rejected counts per route-day.
7. `sync_meta.last_sync_at/result`; if `X-Config-Version` is newer schedule a config delta (s6.7); if `X-Bundle-Version-Current` is newer schedule a bundle delta (s6.3); if `X-Server-Generation` changed run s4.12.

Surfacing without blocking the day:

| Audience | Where | What they see | Action |
| --- | --- | --- | --- |
| SR | Sync tile badge, reconciliation row "needs attention (n)", warning icon on the memo in the Memo list | Bangla reason from `cfg.sync.reason_texts` (ships in the bundle so a new code needs no release; REJECT-policy codes only, D-123), memo number, outlet, amount | Show details; Retry after refresh (retryable only); Report to supervisor (adds an `activity_log` note). The paper memo exists and money changed hands: nothing is deleted |
| AMO | Live Dashboard and Team Performance: quarantined count per route | read-only list | Annotate; the admin resolves |
| Admin | Quarantine panel (F-ADM-030 in doc 15) over `sync_rejected` and `sync_conflict` | payload, reason, rule, history | Accept with fix (four-eyes for data-entry-class reasons), retry, discard with reason; resolution re-ingests through the same path and returns in `resolved[]` |
| Sales Submit | Allowed with rejected rows; the count is recorded on the submit event (`rejected_count`) and shown in the dues-warning dialog | | The quarantine queue is the supervisor's job (D-383) |

### 4.10 Poison-row isolation and skip-ahead (D-65, resolves G-sre-03)

| Side | Rule |
| --- | --- |
| Server | Ingest runs the batch in ONE savepoint (fast path); when any record raises, the batch is bisected (halves retried, depth at most 6) and a transaction never holds more than `cfg.sync.max_savepoints_per_tx` (60) savepoints, because more than 64 subtransactions overflow the subxid cache and slow every concurrent query on the primary (D-411, D-521). The isolated record's exception becomes `rejected(server_error, retryable = false)` plus a `sync_rejected` quarantine row with the error class and a stack hash, one error event per class per hour, and the batch still returns 200 with everything else accepted. Admin actions: retry, fix-and-accept, discard |
| Client, API-originated 500 | The next attempt of that set is sent as two halves by `seq` (bisection, at most `log2(rows)` extra POSTs, 8 for 200 rows). Families that ACK reset to zero; the family that keeps failing accumulates `family_fail_count`. Infrastructure failures never bisect |
| Client, skip-ahead | At `family_fail_count >= cfg.sync.family_skip_after` (5) the builder sends every other pending family first, then the suspect family alone. A lone family failing `row_max_retries` becomes `rejected(retry_exhausted)`, surfaced with "report to supervisor" |
| Outcome | The rest of the day uploads within two cycles; the memo exists on paper; the day is not blocked |

### 4.11 Submit settle (D-64, resolves G-sre-02, G-sync-09 and G-sync-06)

The defect: a 280-row day is two batches. If `day_submit` reached the server in batch 1 while batch 2 was still on the phone, every offline SR would show a count mismatch at 17:00 and the fleet-wide rollback trigger ("mismatch above 2 percent of routes") would fire on a healthy system.

| Step | Rule |
| --- | --- |
| Ordering | `day_submit` is appended after every row of the route-day in `outbox.seq` and is never prioritised ahead of a lower-`seq` row (s4.3). It carries `device_counts` for the route-day and `rejected_count`, `submitted_with_dues`, `dues_at_submit_mtk` |
| Device state | `submitted_local` after the tap; the Home header reads "জমা হয়েছে (সার্ভারে পৌঁছেনি)" until the ACK settles; further captures on that route-day are locked locally when `cfg.day.sales_submit_locks_capture` is true (default; ASSUMPTION: whether Apsis allows selling after Sales Submit is unknown; confirm with the business). The lock is not a trap: a submit void (below) unlocks it |
| Server | On receiving `day_submit` the server defers `route_day.state = sales_submitted` until `server_totals >= device_counts` for every type, re-evaluating as later batches land (an idempotent worker check). Meanwhile the state is `submit_pending_rows` and the screens show it distinctly |
| Timeout | After `cfg.day.submit_settle_timeout_min` (30) the state becomes `sales_submitted` flagged `submit_count_mismatch`; the flag is evaluated only after settle |
| Rollback trigger | Doc 18 s7 reads `submit_count_mismatch` after settle, never before; Submit % (of logged-in) counts a route only after settle (K-02, doc 16 s9) |
| Offline submit | Allowed when `cfg.day.sales_submit_offline_queue` is true (default), see D-383; the dues dialog follows `cfg.day.sales_submit_dues_warning` (warn) |
| Submit void (D-539, F-SR-080, F-API-069) | An accidental tap at 10:00 must not cost the rest of the day. A TSO of the zone, L1 support with TSO confirmation (inside `cfg.day.submit_undo_window_min`, default 120) or ops_admin (before Final Submit) voids the submit from the web console or the TSO app (doc 19 P15, doc 16 s11.4). The server clears the effective `sales_submitted_at` and returns `day_states[].state = in_field` with `submit_voided = true` and `submit_seq` n+1 in the next response or bundle delta. The device then flips `submitted_local` or `submitted` back to `ready_to_capture`, shows "জমা বাতিল হয়েছে; আবার বিক্রি করা যাবে" (key `day.submit.voided`), unlocks capture, keeps the old `day_submit` row as `superseded` in the outbox history and uses a NEW `client_uuid` for the next `day_submit` (the old uuid replays as `voided`). No local data is lost and no row is re-sent. A zone that is already final-submitted must be reopened first (existing audited reopen, D-55) |

Sales Submit button rule (D-383, resolves the tension between D-64 and UI-SR-37):

| Condition | Button "বিক্রয় জমা" | Dialog |
| --- | --- | --- |
| Online, every row of the route-day acknowledged, counts equal | Enabled (PARITY: the Apsis button is grey until the sync completes) | The dues warning with the dynamic count of retailers still owing (D-173); never blocks |
| Rows pending or no network, `cfg.day.sales_submit_offline_queue` true | Enabled | "N records not yet sent; the submit will complete when they arrive" (authored string) plus the dues warning |
| Rows pending, key false | Disabled with the reason | |

The manual notice "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।" stays, rewritten for the automatic case (UI-SR-35): the screen first shows the live Server column, and the "ডাটা সিঙ্ক করুন" button remains as a manual retry. Which rule Apsis uses for enabling is unknown; confirm with the business (Q-UI-08), proceed with this default.

### 4.12 Server-generation re-sync (D-63, D-406; resolves G-sre-01)

The defect: a DR failover (RPO 15 min) or a PITR to T minus x makes the server forget batches it ACKed. The device marked those rows `synced` and would never resend them.

| Step | Rule |
| --- | --- |
| Signal | Every API response carries `X-Server-Generation` (a UUID minted at promotion or restore). The device stores the last value in `sync_meta` (first contact only stores it). On a change the device calls `GET /sync/generation` (F-API-070), which returns `{generation, kind (failover or pitr), restore_point_utc, lost_after_utc, minted_at}`: the server's own statement of the instant after which acknowledged data may be missing (D-517) |
| Device action on change | In one transaction flip every outbox row with `state = synced` and `server_ack_at >= restore_point_utc - cfg.sync.resync_safety_margin_h` (default 6; bounds 1 to 24) back to `pending` with `trigger = resync`, family-wise so families stay whole, REGARDLESS OF THE DEVICE'S OWN `now`. `server_ack_at` is the server time of the ACK (the anchor of s5.5), so the comparison is on the server's timeline. `attempt_count` is untouched. The first draft anchored the window at the device's `now - 24 h`: a phone that acked at 16:55 on Thursday, stayed offline over a Friday off-day or in a dead zone for more than 24 h, and met the new generation on Saturday would have left its Thursday rows outside the window while the server had restored to 16:45; the data would be lost for good and purged after `outbox_keep_days` (G-qa-41) |
| Purge hold | The outbox purge NEVER deletes a `synced` row whose `server_ack_at >= restore_point_utc` of the latest generation the device has seen, nor any row acked after `sync_meta.last_clean_reconcile_at` of the current generation, until the device has recorded a clean reconcile (digest below) under the current generation. `cfg.app.outbox_keep_days` (3) applies only after that; the D-380 rule `keep x 24 >= resync_window_h + 24` is replaced by this hold |
| Digest check | `POST /sync/digest` (F-SYS-080) sends, per `(business_date, type)` of the last `cfg.sync.digest_days` (default 3) and for `type` in the reconciled types, `{count, bucket_hash[16]}`, where a bucket is the first hex digit of the `client_uuid` and the hash is the sum of the first 8 bytes of the uuids modulo 2^64 (commutative, so order does not matter). The server answers with the buckets whose count or hash differs: `resend[{date, type, buckets[]}]`. The device re-sends only the rows in those buckets. The digest runs after a generation change, at Sales Submit, once a day on Wi-Fi and from the reconciliation screen; it costs about 3 KB. Any residual mismatch is therefore repaired by content, not by a time window |
| Convergence | Row idempotency absorbs the re-send: rows the server still has are deduped by `ingest_registry`, rows it lost are re-inserted. Device counts equal server counts again; supervisors may see counts rise again after a failover (Q54, MUST-CONFIRM by 7c) |
| Herd control | Re-sync batches start after a random delay `U(0, cfg.sync.resync_jitter_s)` (default 900 s, OI-17-06); 8,500 devices x about 350 rows is about 3 M rows, so 900 s spreads it to about 3,300 rows/s, under the 8,000 rows/s design capacity (doc 18 s1). Config reaches a device only on contact and the generation header arrives on that same contact, so a widened window cannot be delivered ahead of the flip: that is why the restore point travels WITH the generation and no operator step has to raise `cfg.sync.resync_window_h` any more (D-406 is superseded in that part by D-517) |
| Window | `cfg.sync.resync_window_h` (24) remains only as the fallback when `restore_point_utc` is missing (an old server) and as the cap of the safety margin. A PITR distance above 72 h is unsupported: operations treat it as a data-loss incident, the digest still repairs what the phones hold, and the `lost interval` dashboards (doc 18 RB-06) are expected to show the gap until it is repaired |
| Late rows after a restore (D-572, G-qa-108) | A phone that acked rows up to 72 h before the restore point and then stays offline for 4 days or more would re-send them as `trigger = resync` OUTSIDE the 7-day business-date window if the server measured the window from receipt, and DQ-09 would quarantine exactly the sales the server lost, which is manual work at fleet scale. The server therefore measures the window of a resync batch from the announced `restore_point_utc` (doc 16 DQ-09): rows within `cfg.sync.resync_late_max_days` (14) of that anchor are ACCEPTED and flagged `resync_late` (DQ-71), never quarantined; the restore report counts them. Rows beyond even that are quarantined as usual and an admin recovers them |
| Photos | Blobs live in RA-GZRS storage; `media_meta` rows are re-sent with the same rule and re-link them |

Gates: T-1-56 (extended: devices offline 72 h across the flip, devices that acked within the last minute before the restore point, a PITR distance above 24 h, zero missing UUIDs after all devices reconnect; the old-row case: acked rows older than the 7-day window re-sent after a PITR by a phone that stayed offline 4 days, zero rows quarantined, all flagged `resync_late`, T-1-156), T-1-152 (digest), T-7-54 (extended in doc 20).

### 4.13 Transport

| Item | Setting |
| --- | --- |
| HTTP client | One `dio` instance with `dart:io` `HttpClient`, `idleTimeout` 60 s, `maxConnectionsPerHost` 2, keep-alive on, TLS session resumption; HTTP/1.1 (one POST per visit gains nothing from HTTP/2) |
| Compression | Request gzip level 6; bundle and delta pre-gzipped from Blob |
| Timeouts | Batch: connect 10 s, send 30 s, receive 30 s. Full bundle: receive 90 s. Media PUT: 60 s per 150 KB |
| Hostnames | Two are baked in: the custom domain first, then the Front Door default endpoint after `cfg.net.fallback_after_failures` (3) TLS or DNS failures (D-81) |
| Certificates | Front Door managed certificate, TLS 1.2 minimum; no pinning (a rotation would strand offline phones for a release); user-added CAs refused by the network security config; the Android 7 trust-store caveat is tested (T-2-34) |
| Payload hygiene | No PII beyond the outlet phone inside `outlet_change_request.proposed`; no free-text logs in batches |

### 4.14 Media upload by SAS (D-75, D-390; resolves G-scale-20 and G-sync-10)

1. Eligibility: Wi-Fi, or mobile allowed (`prefs.wifi_only_photos` false), or `evidence = 1` and `now - captured_at > cfg.media.evidence_mobile_fallback_h` (6) with mobile data available, or the user pressed "upload photos now".
2. `POST /media/sas` (F-API-057) takes up to 10 items in one call, each `{client_uuid, purpose, sha256, bytes, business_date}`, and returns one user-delegation SAS per item, pinned to the path, write-only, 15 min (D-75). The batch form is a requirement on F-API-057 (OI-17-08).
3. `PUT` with `x-ms-blob-type: BlockBlob`, `Content-Type: image/jpeg`; 2 concurrent uploads on Wi-Fi, 1 on mobile.
4. Success: `uploaded`, then a `media_meta` outbox row (sha256, bytes, dimensions, fix); the next batch lets the server mark `media_object` linked; the ACK sets `linked`.
5. A 403 (expired SAS) requests a new URL; 5xx or timeout backs off as s4.7, independently of the record queue. `attempt_count >= 20` marks `abandoned`, surfaced as "photo could not be uploaded; keep the phone on Wi-Fi", and the photo is listed as photo-pending on sync-health when older than 24 h.
6. R5(f), the photo exception (D-510, signed by the sponsor at 2c): photos are NOT sent "immediately". Wi-Fi first, a mobile fallback after `cfg.media.evidence_mobile_fallback_h` (6) for evidence photos, and a 24-hour 95-percent SLO (doc 18 s6.1); the trade is against R4 (a photo is 150 KB, about 17.6 a day, so sending every photo on mobile would cost about 2.6 MB a day per rep) and is written into the R5 row of doc 14 s1.2 so that the sponsor decides it, not the build.
7. The record syncs first and the photo follows; the record carries `photo_client_uuid` and `blob_path` from capture. A photo never blocks a sale or a Sales Submit.

### 4.15 Reconciliation screen and Sales Submit table (D-384, F-SYS-009)

| Item | Contract |
| --- | --- |
| Device column | Local counts per record type and business date from the domain tables |
| Server column | The last `server_totals` for that date with its timestamp; blank with the timestamp "not yet synced" before the first sync (D-222) |
| Equality | Per type: `device = accepted + rejected + conflict` (parked rows count as pending). A mismatch is displayed, never silent; Sales Submit stays allowed (D-383) |
| Rows shown | Config-driven per role and app version, `cfg.sync.reconcile_types`: SR 5 rows (Outlet, Sale, Stock, QC, Promotion), AMO 8 or 9 (adds Distribution and OOS performance, Price compliance, Joint call, Survey) (D-222) |
| What each legacy row counts | The manual's Sale row is a quantity total or a line count and adds pieces and dozens together (UI-SR-36, V-day G-man-031). Default: `Outlet` = visits opened and not abandoned, `Sale` = memo lines, `Stock` = issued quantity per category shown separately with its unit, `QC` = QC lines, `Promotion` = offer discount in mtk. Unknown; confirm with the business; the exact checks below do not depend on it |
| Exact checks (gate) | Record counts per entity (visits, memos, memo lines, QC lines, stock lines, attendance, outlet requests) and money per category in mtk; these gate Sales Submit's "counts equal" and feed the nightly job |
| Header | "ডিভাইস স্ট্যাটাস" with the online indicator of s4.2 |
| Buttons | "ডাটা সিঙ্ক করুন" (T6) and "বিক্রয় জমা" (s4.11) |

Proved by: T-1-21, T-1-22, T-1-23, T-1-25, T-1-26, T-1-28, T-1-29, T-1-30, T-1-31, T-1-32, T-1-34, T-1-53, T-1-54, T-1-56, T-2-25, T-2-26, T-2-31, T-2-57.

## 5 State machines

### 5.1 Per record (`outbox.state`, mirrored to the domain row's `sync_state` in the ACK transaction)

```
 capture, family already released or closed
   [new] ──────────────────────────────────────────────────────────▶ pending
 capture, visit still open
   [new] ───────▶ held ── family closes, or valve (hold_until) ───▶ pending

 pending  ── eligible: next_attempt_at <= now, family released ───▶ syncing
 syncing  ── ACK accepted, or replayed ──────────────────────────▶ synced ── purge after outbox_keep_days ──▶ [gone]
 syncing  ── transport failure, 5xx, 429, 401, killed > 120 s ───▶ pending   (same batch_uuid if the row set is unchanged)
 syncing  ── ACK rejected, retryable ────────────────────────────▶ pending   (attempt_count + 1, after a refresh)
 syncing  ── ACK rejected, not retryable ────────────────────────▶ rejected  (terminal on the device, surfaced)
 syncing  ── ACK conflict ───────────────────────────────────────▶ conflict  (terminal, surfaced)
 syncing  ── ACK parked ─────────────────────────────────────────▶ pending   (parent re-sent first)
 pending  ── attempt_count >= row_max_retries ───────────────────▶ rejected  (retry_exhausted)
 synced   ── server generation changed, acked_at in window ──────▶ pending   (resync; registry dedupes)
 rejected ── resolved[] from the server ─────────────────────────▶ synced | discarded
```

| Guard | Rule |
| --- | --- |
| Eligibility | Only `pending` rows with `next_attempt_at <= now` whose family is released |
| `syncing` is transient | Any engine start returns `syncing` rows older than 120 s to `pending` |
| A row shows `synced` only after its own ACK | Never because its parent synced; reconciliation counts are per row |
| `rejected` and `conflict` retention | `cfg.app.rejected_keep_days` (30) and never while `seen = 0` |
| A locally superseded memo keeps its own state | The superseding memo is a new row |

### 5.2 Route-day: ownership and the device mirror (D-27, D-64; resolves seed finding 10)

| State | Owning entity | Set by the server when | Device mirror (`day_local.device_state`) |
| --- | --- | --- | --- |
| `not_started` | `route_day(route_id, business_date)`, created at 00:05 Dhaka from planned routes (D-71, F-SYS-056); "on first contact" creation stays as a safety net | the 00:05 job | `no_bundle` |
| `logged_in` | `route_day` | the first `GET /sync/bundle` request of the Dhaka date by a user assigned to the route, whether it answers 200, a delta or 304 (so the morning after a pre-bind day, "refresh plus 304", still counts: D-126, ASSUMPTION), or a `day_open` event with `offline_start` captured on a stale bundle. A pre-fetch of D+1 on the evening of D does not count for D+1 (D-30) | `ready` |
| `in_field` | `route_day`, driven by a user-level fact | the first `attendance_event(in)` of the user sets every planned route-day of that user; the first `visit` fills `first_visit_at` | `checked_in`, then `in_field` on the first visit |
| `synced` | `route_day` | the first accepted batch containing a row of the route-day | `uploaded` when `pending_rows = 0` for the route-day, else `pending_upload` |
| `submit_pending_rows` (sub-state of `synced`; doc 16 decides enum or column) | `route_day` | `day_submit` received while `server_totals < device_counts` | `submitted_local` |
| `sales_submitted` | `route_day` | server totals reach the device counts, or 30 min pass (flagged `submit_count_mismatch`); cleared by a submit void (s4.11, D-539) | `submitted`, back to `ready_to_capture` after a void |
| `final_submitted` | the zone (`final_submit`, once per zone and day), projected onto every route-day of the zone | the TSO's Final Submit | `final_submitted`, seen in `day_states` or a delta |
| attendance | the user (`attendance_event`) | not a route state | Attendance tile |
| `supervisor_day` | AMO and TSO, one row per user and date, separate from `route_day` (F-AMO-039) | check-in, sync, Sales Submit of the AMO | `day_local` keyed by user and date |

```
 not_started ─(first bundle request of D, or day_open offline_start)─▶ logged_in
 logged_in   ─(first check-in of the user)─────────────────────────▶ in_field
 in_field    ─(first accepted batch with a row of the route-day)───▶ synced
 synced      ─(day_submit received, rows still arriving)───────────▶ submit_pending_rows
 synced | submit_pending_rows ─(totals settle, or 30 min, flagged)─▶ sales_submitted
 any         ─(Final Submit of the zone)───────────────────────────▶ final_submitted
 any         ─(approved day_exception)─────────────────────────────▶ planned = false, labelled "exception"
 final_submitted ─(audited admin reopen, D-55)─────────────────────▶ synced
 sales_submitted ─(submit void by TSO, L1 with TSO confirmation, or ops_admin, D-539)─▶ in_field | synced (capture unlocked, submit_seq + 1)
```

| Rule | Detail |
| --- | --- |
| Later deltas never change `logged_in_at` | D-30 |
| An SR on two routes has two `route_day` rows flipped by one bundle | D-27 |
| A substitute opening the route sets `acting_user_id` | D-85 |
| Check-in with no visit still counts as `in_field` | The SR is at work |
| A route with zero rows at 17:00 stays `in_field` | Daily Tracking lists it |
| Rows after final submit are accepted, aggregated into their business date and flagged `after_final_submit`; the zone is not reopened automatically | D-55 |
| A new business date is a new `route_day`; yesterday's row is never mutated | D-388 |
| Device and server disagreement is displayed | Home header shows `device_state` and, when known, `server_state` |

### 5.3 Per device session

```
 [fresh install] ─install─▶ unbound ─(password login online + OTP: POST /auth/bind-device)─▶ bound
 bound ─(POST /auth/login or /auth/refresh online)─▶ authenticated ─(GET /sync/bundle 200 or 304)─▶ day_ready(D)
 bound ─(offline; verifier ok; refresh token live; last online auth within offline_unlock_max_days;
         bundle age <= stale_max_days)──────────────────────────────▶ day_ready_stale(D) ─(online)─▶ day_ready
 bound ─(offline; no bundle, or older than stale_max_days)──────────▶ blocked_no_bundle   (read-only memos and dues; check-in allowed)
 day_ready ─(check-in)─▶ working ─(Sales Submit)─▶ day_closed_local ─(settled ACK)─▶ day_closed
 any ─(logout, SR or AMO)────────────────▶ bound   (DB kept; the engine keeps uploading under the stored refresh token)
 any ─(logout, TSO, device reconciled)───▶ wiped   (DB, caches, secure storage, tokens, media files)
 any ─(logout, TSO, rows pending)────────▶ refused ("N items not yet sent": Sync now | Cancel; PDA to Support also clears the guard)
 any ─(401 and the refresh fails)────────▶ needs_relogin   (capture continues; sync paused for this user)
 any ─(403 device_unbound)───────────────▶ unbound         (capture continues; sync paused; banner)
```

Detail of each element is in s7.

### 5.4 Media queue

```
 queued ─(eligible, s4.14)─▶ sas_requested ─▶ uploading ─(PUT 201)─▶ uploaded ─(media_meta ACKed)─▶ linked ─(+ local_keep_days)─▶ [file deleted]
 uploading ─(403)─▶ sas_requested        uploading ─(5xx, timeout)─▶ queued (backoff)
 any ─(attempt_count >= 20)─▶ abandoned (surfaced, listed as photo-pending)
```

### 5.5 Time: trusted capture time, business date and midnight (D-20, D-389; resolves G-fraud-02, G-sec-12, G-cfg-20)

The defect in the offset-at-last-sync method: an SR who changes the wall clock after the last sync defeats it. The anchor method of this section uses a clock the user cannot set.

| Element | Rule |
| --- | --- |
| Anchor | After every 2xx or 304 that carries `X-Server-Time`, store `A = {S_a, E_a, B_a, W_a}` in secure storage: server time at receipt corrected by half the round trip, `SystemClock.elapsedRealtime()`, `Settings.Global.BOOT_COUNT`, wall clock. An anchor is accepted only if the round trip is at most 10 s (ASSUMPTION: bounds the error to 5 s on 2G); otherwise the previous anchor stays |
| Trusted now | If `B_now = B_a`: `S_a + (E_now - E_a)`. `elapsedRealtime` counts deep sleep and cannot be set by the user |
| After a reboot | `W_now + (S_a - W_a)` (offset at last contact), flagged `time_untrusted`. An honest reboot (battery death, OEM kill) is common, so this flag is informational, not a fraud signal, unless combined with skew (doc 21) |
| Same-boot wall-clock change | `drift = (W_now - W_a) - (E_now - E_a)`. If `abs(drift) > cfg.sync.max_clock_skew_min` (10) the app shows "ফোনের সময় ঠিক করুন" with a deep link to date and time settings and flags rows `clock_skew`; capture continues on trusted time. This detects a set-back even when the phone has been offline for days |
| Per record | `captured_at_device`, `captured_elapsed_ms`, `boot_id`, `clock_offset_ms`; the batch envelope carries `time_anchors` (up to 3 recent boots) |
| Server | `captured_at_trusted = anchor.server_time + (captured_elapsed_ms - anchor.elapsed)` when `boot_id` matches an anchor; otherwise `captured_at_device + clock_offset_ms`, bounded to `[last_server_contact, receipt_time]`, flagged `time_untrusted`. `business_date_server` is derived from it and compared with the device's (DQ rules in doc 16 s7) |
| Out-of-window rows | Trusted time later than receipt, or earlier than receipt minus `cfg.sync.max_backdate_days` (7): `business_date_out_of_window`. If the device's date is far wrong (for example 2009) but `clock_offset_ms` is present and the corrected date is in the window, accept with flag `clock_corrected`; otherwise quarantine. A row that claims a closed month on untrusted time is parked (DQ-40) |
| `business_date` | `date(trusted time, Asia/Dhaka)`, fixed per family at visit open and per root record at creation, so a visit that straddles midnight never splits across two dates. Use the IANA zone, not a constant (Bangladesh had DST in 2009 only) |
| 17:00 check-out gate | Evaluated on trusted time against `cfg.day.checkout_earliest_time` (17:00, inclusive, D-209); with `time_untrusted` the device time is used and the row is flagged `checkout_unverified_clock`; the server flags `checkout_before_allowed`. The value may differ per scope and be effective-dated (Ramadan hours, D-225) |
| Midnight while offline | Nothing is mutated at 00:00 Dhaka. A capture after midnight takes the new date and a new `day_local` row. If yesterday was not submitted Home shows both days ("গতকালের দিন জমা হয়নি") and yesterday stays submittable until `cfg.day.submit_grace_h` (10 h after midnight, so until 10:00 Dhaka), then only by audited admin reopen (D-388; MUST-CONFIRM by 3b with Q11) |
| 23:55 sale, 00:10 sync | Row `business_date = D`; the envelope date is informational; the server ingests into the partition of D, moves `route_day(D)`, marks `(D, route)` dirty and re-aggregates only D; `route_day(D+1)` is untouched (T-1-27) |

Proved by: T-1-20, T-1-27, T-1-28, T-1-29, T-1-30, T-1-38, T-1-54, T-1-56, T-2-27, T-2-29, T-3-20, T-3-21.

## 6 Bundle and config propagation

### 6.1 Bundle contents per role (D-72, D-257)

| Section | SR (rows median and p95) | AMO | TSO | Delta-able |
| --- | --- | --- | --- | --- |
| `meta` | 1: `bundle_version = <business_date>:<snapshot_seq>`, `valid_for_business_date`, `generated_at`, `server_time`, `config_version`, `schema_version`, per-section `max_updated_at` | same | same | no |
| `routes`, `assignments` | 2 and 4: planned-today flag, `visit_days_mask`, `route_kind`, acting role | every route of the zone | territory pickers | yes |
| `outlets` | 110 and 300 | zone-wide: all routes of the zone, 3,500 for a 54-route zone, up to 11,000 | about 2,500 for the territory (pickers only) | yes, with tombstones |
| `outlets` fields | code, name (bn), `name_sort_key`, owner, phone, lat and lng (nullable), cluster, channel, sub-channel, geo class, status, `location_confirmed`, `price_type`, open due and its as-of date, loyalty balance, suggested quantity (empty until Q6), programme flags, pending-request flag | plus route, request status | code, name, cluster, owner, phone | |
| `open_memos` | 30 and 90 | the AMO's own | none | yes |
| `products`, `prices` (both outlet and cc lists), `sales_plan`, `offers` | 42 SKUs, 210 prices, 22 offers | same | none | yes |
| `targets`, `achievement_mtd` | about 126 | zone and routes | territory snapshot via `GET /app/home` | yes |
| `astha_targets`, `gift_assignments`, `tasks`, `survey_defs`, `rubric_defs`, `content_manifest` | about 130 | plus pending verification requests, SR list, today's tasks | none | yes |
| `config` and `reason_texts` | about 60 resolved keys | same | same | yes |
| `templates` | print template JSON per kind (D-392) | same | none | yes |
| `day_states` | route-days of today and yesterday | plus `supervisor_day` | none | yes |

| Size budget | Value | Source |
| --- | --- | --- |
| SR full bundle | about 110 KB raw and 25 KB gz median; about 250 KB raw and 60 KB gz p95; alert above 300 KB gz | planning estimate (ASSUMPTION), measured by T-1-36 and alerted server-side (doc 18) |
| AMO full bundle | 3,500 outlets is about 1.6 MB raw and 350 KB gz; hard cap 2 MB gz; sections above 2,000 rows are paged (s6.4) | doc 18 s1, D-72 |
| TSO login snapshot plus pickers | under 1 MB gz | D-72 |
| Delta | at most 20 KB gz | planning estimate (ASSUMPTION), measured by T-1-36 |

### 6.2 Serving and version rules

| Rule | Detail |
| --- | --- |
| Snapshots | One gzip JSON per user, pre-generated by the job chain (22:00 for D+1, 03:30 refresh for users dirtied since, 04:30 coverage check; D-71, D-129) and served with ETag and 304 plus a live `?since` delta |
| `valid_for_business_date` | A response to `GET /sync/bundle?for=D` always carries `valid_for_business_date = D`; when pre-generation failed the server composes yesterday's snapshot plus a delta (`bundle_hold`) and still reports D. The device asserts the field and treats a mismatch as a stale bundle (s6.5) |
| Invalidation | Assignment, outlet, price, offer, target or config change touching a scope regenerates affected snapshots (debounced 60 s, capped at `cfg.bundle.regen_max_per_s` = 20) and increments `snapshot_seq` |
| Jitter | Automatic morning refresh is spread by `cfg.sync.login_jitter_s` (120); a user-initiated login never waits |
| Login event | Defined in s5.2: the first bundle request of the date, a delta included, never a later delta |

### 6.3 Delta protocol (D-385)

```
GET /sync/bundle?since=<max_updated_at>&bundle_version=<local>      If-None-Match: <local bundle_version>
  304                                     nothing changed anywhere
  200 { meta{new bundle_version}, outlets{upsert[], delete[]}, prices{…}, …, day_states[] }
  410 Gone                                `since` older than cfg.bundle.delta_max_age_h (72) or lineage broken: full GET
  409 { reason: "new_business_date" }     the local bundle is for an older date: full GET for today; keep yesterday's cache for an unsubmitted day
```

| Rule | Value |
| --- | --- |
| Atomic apply | One local transaction per delta; each section's `ref_meta.server_max_updated_at` advances inside it. A kill leaves old or new, never a mix (T-2-24) |
| Price and offer changes | Apply to new memos only; a memo stores `price_list_date`; an outlet that moves route mid-day keeps today's visits on the route captured |
| Cadence | Foreground refresh at most every `cfg.bundle.delta_min_interval_min` (30); version-triggered refresh at most every 5 min; manual unlimited |
| Budget | At most 15 deltas and 100 KB per day (typical 304 is about 300 B) |
| A delta is never a login | s5.2 |

### 6.4 Paged bundle for zone-wide AMO and large SR bundles (D-386; resolves G-sync-21 and G-man-065)

| Rule | Detail |
| --- | --- |
| Paging | Any section above 2,000 rows is served as `GET /sync/bundle?section=outlets&page=n` with about 1,000 rows per page (ASSUMPTION: about 100 KB gz at 460 B raw per outlet) |
| Assembly | Pages are written to staging tables; a final `meta` page swaps staging to live in one transaction. A kill mid-download leaves the old bundle intact and resumable from the last page (T-3-36) |
| Cap | 2 MB gz in total; above it the server alerts and the AMO bundle drops optional sections (content manifest) before outlets |
| Offline use | A Control Call at any outlet of the zone works in airplane mode (T-3-41, doc 20) |
| SR with several routes | An SR's bundle is one snapshot of all assigned routes for the date; most days one route is planned (about 6,953 planned routes for 8,500 SRs, docs/22 P-17), so p95 stays 60 KB gz |

### 6.5 Stale bundle, pre-fetch and the offline day start (D-70, D-30; resolves G-sync-03 and G-scale-05)

| Situation at day open (trusted Dhaka date D) | Behaviour | Marks |
| --- | --- | --- |
| Online | Full or delta fetch; `logged_in` | none |
| Offline; cached bundle with `valid_for_business_date = D` (pre-fetched) | Normal | `day_open(offline_start)` appended, because a pre-fetch does not count as a login (D-30) |
| Offline; bundle age `D - valid_for <= cfg.bundle.stale_max_days` (2), counted in WORKING days (`dw.prior_working_day`, D-584), and never above the calendar ceiling `cfg.bundle.stale_max_cal_days_ceiling` (7), with a declared break widening it by `cfg.calendar.break_overrides` | Sell on yesterday's prices, offers and targets with the banner "পুরনো তালিকা ব্যবহার হচ্ছে". ASSUMPTION: a wrong price is rarer and cheaper than a lost selling day; the business must accept (D-70, MUST-CONFIRM by 2e) | `day_open{online: false, bundle_version}`; every row `bundle_stale`; the server flags `stale_price` if the outlet's price list changed in between; printed totals are kept as printed |
| Offline; older than `stale_max_days`, or no bundle | `blocked_no_bundle`: memos and dues read-only; check-in allowed (user level); no visits or sales; the Retry button and T4 fire when the network returns | the route stays `not_started` |
| Route not planned today but the SR insists | Allowed if `cfg.route.allow_unplanned_day` (true); visits flagged `unplanned_day` | D-57 shows `unplanned_visits` |

Pre-fetch. When online after Sales Submit, or on Wi-Fi, from 20:00 Dhaka the app fetches the bundle of the NEXT WORKING day with `GET /sync/bundle?for=<next working day>` if a snapshot for it exists: the 22:00 snapshot of an ordinary night, the 14:00 wave pre-snapshot on the eve of a wave (doc 18 s4.7, J2w), or, on the last working evening before a break, the snapshot of the first working day after it (`cfg.calendar.prefetch_next_working_day`, D-584). The first draft built D+1 only "if the calendar says D+1 is a working day", so on the last evening before a nine-day break nobody pre-fetched the first morning after it and every offline rep returned to `blocked_no_bundle` on the day all 8,500 come back (G-qa-123). It does not count as a login. This removes most stale mornings for Wi-Fi-only phones (E-08) and is how the pre-bind day (s7.1) leaves a phone ready.

Working-day windows (D-584, G-qa-123). The stale-bundle age, the backdate window `cfg.sync.max_backdate_days` and the offline-unlock window `cfg.auth.offline_unlock_max_days` count WORKING days (the calendar of doc 16 s11.1) when `cfg.calendar.window_unit` is `working_days`, with calendar-day ceilings and per-break overrides in doc 19. The observed Eid gap of 27 to 31 May (docs/22 P-03) and a nine-day break both exceed 2 or 3 calendar days; a rep who last synced on the final working day before a break therefore still starts the first morning on the pre-fetched snapshot, and rows left unsynced on that last working day are not `business_date_out_of_window` in bulk. The accept window of D-431 counts working time too (doc 19 s2.3). The pre-holiday readiness line is item 2 of RB-54. Gate T-2-163 (a post-break first-morning test with a 5-day and a 9-day gap).

### 6.6 AMO and TSO read models and queued writes (D-82, D-387; resolves G-man-069)

| Role | Reads | Writes | Offline behaviour |
| --- | --- | --- | --- |
| AMO | Zone-wide bundle (s6.4); Team Location, Live Dashboard, SR Stock and reports online-only with a list fallback or last-view cache and an "as of hh:mm" stamp | Control and joint calls, sales, verification, tasks, assessments, cover and exception requests as outbox records | Same engine as the SR (s4); `supervisor_day` replaces `route_day` |
| TSO | `GET /app/home?role=tso` snapshot plus territory pickers, delta-refreshed; every aggregate screen shows "as of hh:mm" | Leave, Set Plan, Visit Query, Assign Task, Feedback queued with a client uuid and a sync-state badge | Final Submit and maps are ONLINE-ONLY: disabled with "needs internet"; `GET /day/final-submit/preview` feeds the already-submitted alert at "Get Sales Data" |

Aggregate screens refresh only on explicit open or pull-to-refresh, never on a timer (D-387, D-84). The TSO's only server round trip that cannot be queued is Final Submit (D-262).

### 6.7 Config pull, ack and scheduled values (D-89)

| Step | Rule |
| --- | --- |
| Signal | `X-Config-Version` on every response; the batch repeats it |
| Pull | If newer than `sync_meta.config_version`: `GET /config/delta?since=<local>` (F-API-040, ETag) returns `{config_version, changes[{key, scope_type, scope_id, value, effective_from, effective_to, requires_ack, bounds, dir}], scheduled[{key, value, effective_from, effective_to}], removed[]}` (`bounds` and `dir` let the device refuse an out-of-range value and pick the restrictive value when its clock is suspect, D-432; the shape of doc 19 s4.1 governs, G-19-03), at most about 2 KB, applied in one transaction to `ref_config`; `ConfigService` re-reads without a restart |
| Ack | Keys flagged `requires_ack` (critical: `cfg.geo.radius_m`, `cfg.day.checkout_earliest_time`, `cfg.release.*`, `cfg.geo.mock_policy`) produce a `config_ack` outbox row (F-API-041); the admin reach view shows the share of devices in scope that acknowledged |
| Scheduled values | A change with `effective_from` in the future is stored and applied at that instant on trusted time, within `cfg.sys.schedule_horizon_days`; when the clock is suspect (`time_untrusted` or skew) the device applies the more restrictive of the old and the scheduled value (the restrictive direction per key is doc 19's) |
| Stamping | Capture stores `config_version` and the resolved `radius_m_used`; the server re-checks with the value in force on the visit's business date and compares the stamp with the versions it delivered to this device: a stamp below a version served more than `cfg.sys.config_apply_grace_min` before the capture is `config_stamp_regress` and is judged under the as-of value (doc 19 s2.3b, D-571, DQ-73) |
| Device refuses | A value outside the bounds that ship with the key; it keeps the last good value |
| Propagation time | The wall-clock bounds are doc 19 s4.1b (D-563): selling phones 95 percent within 15 minutes (the visit sync that is coming plus the delta behind it); idle phones at the next foreground, where the RESUME CHECK (F-SYS-092, `GET /config/check`, a 304 of about 300 B when online and the last contact is older than `cfg.sync.config_check_min_gap_min` (5), also sent by "Refresh GPS" and by opening a visit) fetches the delta before the geo re-check, so an SR who sees "not within range" after an admin widened the radius is not stranded until T5; ceiling 15 min while rows are pending (T5). An already-open visit keeps its stamped `radius_m_used`. No polling and no timer exist to make this faster |
| Urgent changes | FCM data messages only for kill switch, `min_version`, blocked versions and reverts, with `pull_after_s` jitter (120 s ordinary, 20 s urgent); `cfg.ops.push_enabled` is false in the pilot; FCM never triggers an upload; if FCM is refused (no Google services) the change rides the next request (D-09, MUST-CONFIRM by 2d; FCM adds about 2 MB to the APK, ASSUMPTION, counted in the size gate) |
| Never | A kill switch never blocks upload and never wipes data (D-130). `GET /config/public` (unauthenticated, Front Door cached 60 s, F-API-042) serves `min_version`, the banner and the helpdesk number (`cfg.support.contacts`) to the login screen |
| Emergency non-working day (D-542) | A declared `emergency_off` day (doc 16 s11.1) rides the config delta as `calendar_changes[]`: the device shows the banner "আজ কর্মদিবস নয় ঘোষণা করা হয়েছে" (key `day.banner.emergency_off`) and still allows login, capture and upload (a day opened on a declared-off day is accepted and flagged); no forced logout, no bundle invalidation |
| Badges | Task and request badges come from the bundle delta and sync responses, never from a timer (D-84) |

Proved by: T-1-27, T-1-30, T-1-38, T-2-24, T-2-27, T-2-28, T-2-29, T-2-55, T-3-36, T-3-41, T-3-42.

## 7 Offline session, device binding and logout

### 7.1 First login, OTP and the pre-bind day (client side; D-103, D-80, D-126)

| Step | Client behaviour | Online? | Decision |
| --- | --- | --- | --- |
| Install | Signed APK with its own applicationId per flavour (D-01); the login footer shows the real installed build version from one source (D-208); AKTCL branding, never vendor strings (D-244) | no | F-SR-001 |
| Login | Username and password; uniform error text; the server hashes with Argon2id; lockout keyed by (username, device) | online once | D-102 |
| Unknown device | The "OTP যাচাইকরণ প্রক্রিয়া" screen: four boxes (`cfg.auth.otp_length` 4), "Verify"; the server created the OTP and the TSO reads it on the web panel (F-TSO-022). Five wrong attempts or `cfg.auth.otp_ttl_min` (120) expire it | ONLINE-ONLY | D-103; the creation rule is an inference, TTL unknown; MUST-CONFIRM by 0c |
| Bind | `POST /auth/bind-device {deviceUuid, otp}` with the EC P-256 public key (Keystore, StrongBox when present); the server assigns the device slot (`bind_ordinal`, s9.5) | online | D-104, D-105 |
| First-login notice | Employee-location notice in Bangla and English; acceptance stored (`consent_accept`) | queued | D-120 |
| Re-verify after an update | Off by default (`cfg.auth.reverify_on_new_version` false): about 8,500 TSO lookups per release and a broken offline day; the pilot may set it true for parity | | D-80 DELIBERATE CHANGE; MUST-CONFIRM by 2e (MQ-15) |
| AMO binding | Whether the AMO app binds with an OTP is not evidenced (the manual shows none); default: the same gate applies to all flavours | | MQ-15 |

Pre-bind day (T-1 of a wave, D-126). The TSO gathers the wave's SRs at the distribution house on Wi-Fi. On each phone: install, bind, log in once, pre-fetch D+1 (s6.5), pair the printer, print a test memo. A readiness screen shows five ticks (bound, "bundle for D fetched and verified", printer paired, test print done, permissions granted) plus free storage and battery; the result is carried in `device_integrity.readiness` so the war room can read "100 percent of the wave bound and pre-fetched by T-1 19:00" (doc 18 s7, T-7-84). The pre-fetch at the distribution house takes the 14:00 WAVE PRE-SNAPSHOT of the wave eve (doc 18 s4.7 J2w), because no D+1 snapshot of the usual 22:00 kind exists while the SRs are still there (D-557, G-qa-89); "verified" means the stored `snapshot_version`, `user_id` and `valid_for_business_date = D` match. The final delta import (to 23:00), J2 on its completion and J6 at 03:30 change the bundle afterwards, so day one is NOT "refresh plus 304": it is a small DELTA bundle (about 25 KB gzip per user, about 29 MB for 1,155 users, sized in doc 18 S1') plus the 06:00 late delta of doc 16 s12.6, and the opening balances carry the marker "balance as of <time>" (F-SYS-068). This screen is an IMPROVEMENT; it adds no network use beyond the pre-fetch.

### 7.2 Offline unlock, tokens and time (D-68, D-101, D-265)

| Element | Rule |
| --- | --- |
| Daily login | Refresh-token exchange (no hash). The password is typed only on first use, after refresh expiry or after a password change; after an explicit logout the user unlocks locally with the offline verifier (D-68) while the upload-only grant keeps sending the outbox (D-471, doc 21 s2.7) |
| Offline verifier | Argon2id (m = 19 MiB, t = 2, p = 1) written to secure storage at each online password login; not PBKDF2 (R-34) |
| Offline window | Valid while the refresh token is unexpired and the last online authentication is within `cfg.auth.offline_unlock_max_days` (7; 1 to 14). The older session-days key is retired in favour of this one (D-265) |
| Attempts | 10 failures with doubling cool-down on the device |
| Clock games | The window is judged on trusted time (s5.5); if the wall clock went backwards against the last recorded wall time the window ends after 24 h and the next sync raises the clock signal (doc 21 s7) |
| Access token | ES256, 60 min jittered by plus or minus 10 min; refresh piggybacked on any request under 5 min to expiry; `cfg.auth.min_refresh_interval_s` 300; 60 s grace on `/sync/batch`; expiry compared on trusted time so a skewed phone does not refresh in a loop (D-101, G-sre-06) |
| Refresh failure | Never blocks local reads or capture; sets `needs_relogin` (banner); pending rows wait (E-10) |
| Admin password change | Not a kill switch for the field: it takes effect at the next online refresh, and the SR's rows still upload after re-authentication |
| 401 and 403 on the device | The engine table of s4.6 decides the row effects. Banners: `needs_relogin` ("log in again to send your records", authored) and `unbound` ("this phone is not bound", authored), each with the support code (s10.7). The SR keeps selling; nothing is lost because rows wait in the outbox |
| Permissions and re-login | The permission matrix is s10.3; offline re-login with the cached session closes the offline half of G-man-020 |

### 7.3 Shared phones: one engine, several users (D-66, D-395; resolves G-sync-01, G-sec-02, G-feat-41)

| Rule | Detail |
| --- | --- |
| Binding model (MUST-CONFIRM by 1c) | `cfg.auth.max_users_per_device` 3 and `cfg.auth.max_devices_per_user` 2. The OTP binds a user to a device, not the device exclusively. The per-user DB design is independent of the answer |
| Isolation | One DB file per user; counters, bundles, drafts and memo series never mix |
| Flush order | One batch in flight device-wide. A trigger first serves the active user, then other users with pending rows, oldest `created_at` first, one batch per user per rotation, each signed with that user's stored refresh token |
| Quiet notice | Home for user B shows "অন্য ব্যবহারকারীর {n}টি রেকর্ড আপলোড বাকি" (authored string) when A has pending rows |
| Orphans | If A's refresh token expired, A's rows wait for A's next login; after `cfg.sync.orphan_pending_alert_h` (24) the device is listed on sync-health |
| Identity | First capture of a date on a multi-user device asks "আপনি কি <name> (<code>)?" (s3.6) |
| Policy unbind (D-585, G-qa-124) | When a user exceeds `cfg.auth.max_devices_per_user`, the OLDEST binding is NOT dropped silently with its unsent rows: it moves to `unbound (by policy)`, which carries the same upload-only grant as `revoked` for `cfg.auth.revoked_device_grace_upload_h` (doc 21 s2.5), its rows arrive as `source = revoked_device` and are parked for the zone TSO, and the bind screen and P11 show the displaced phone's pending-row count (the server already knows its `X-Pending-Rows`); with `cfg.auth.unbind_block_if_pending_rows` the bind is routed through the replace-device wizard (upload first). Gate T-3-157 (bind a third device while the oldest holds 20 unsent rows, all 20 arrive) |
| Replaced device | A new OTP binds the new phone to a different slot (the old phone's slot is held until the next business date), so its memo numbers start in another block (s9.5); `day_states` tell the new device the route is already in field; the old device's DB, if alive, is replayed through PDA to Support (E-03) |
| Two devices, one user | Both bound; flagged `multi_device_day` on sync-health; attendance is first in, last out |

### 7.4 Logout (D-69; resolves G-sync-16 and G-man-024)

| Role | Behaviour | Dialog |
| --- | --- | --- |
| SR, AMO | PARITY: the session ends; the local DB is kept and the engine keeps uploading under the stored refresh token. The token is kept in upload-only mode and revoked (`POST /auth/logout`, F-API-031) after the last pending row and queued photo are acknowledged (OI-17-09 for doc 21) | "আপনি কি নিশ্চিত যে লগআউট করতে চান?" with "হ্যাঁ" and "না" |
| TSO | DELIBERATE CHANGE (CLAUDE.md 1 and 2 beat docs/08 and the manual). The logout icon is in the header. If every local row is `synced` and the media queue is empty: wipe the DB, caches, secure storage, tokens and media files (SQLCipher key destruction makes it final). Otherwise refuse with "N items not yet sent" plus "Sync now" and "Cancel"; PDA to Support also clears the guard (s10.4) | The manual text "Your all app data will removed." as a bn and en pair with the grammar fixed; `cfg.app.logout_block_when_pending` true; `cfg.app.logout_wipes_data` {tso: true} |
| Rows at risk | TSO queued visit queries, plans, tasks, leave and feedback with images; a rejected or conflicted row also blocks the wipe (evidence) | |

Proved by: T-0-70, T-1-37, T-2-28, T-2-29, T-3-21, T-3-22, T-3-23, T-7-84.

## 8 Battery, data and size budgets

### 8.1 Reference devices and the OS floor (D-11, D-12; resolves G-qa-05 with G-sync-12 and G-man-026)

| Role | Device class | Why | Status |
| --- | --- | --- | --- |
| Primary | Xiaomi Redmi 9A class: Helio G25, 2 GB RAM, 32 GB, 5,000 mAh, Android 10 or 11 with MIUI | Assumed to be a typical low-end phone of the fleet; MIUI's background killing is the worst case for WorkManager | ASSUMPTION until the fleet census (Q31) |
| Secondary | Samsung Galaxy A03 Core class: Unisoc SC9863A, 2 GB, 32 GB, 5,000 mAh, Android 11 Go | Go-edition memory limits | ASSUMPTION |
| Legacy | Any Android 8.x device with 2 GB or less | TLS trust store and clock behaviour | ASSUMPTION |
| Device lab (Dhaka) | 2 of each plus 2 RPP02N-class printers (one genuine, one clone); the TSO flavour is also run on a Samsung J-series-class phone (Android 8 or 9) because the TSO manual's screenshots show one (G-man-026; ASSUMPTION) | Printer clones differ in dot width and code pages | D-12 (MUST-CONFIRM by 0c; the census is a hard 0c deadline, D-564) |

`minSdk 26` (Android 8.0), baseline layout 360 x 640 dp, hardware back key handled, no gesture-only navigation (D-11, MUST-CONFIRM by 0c; the gates that use it exit at 1c). The evidence for the real fleet is thin: the TSO manual shows J-series phone frames and a Material 2 date picker; the Apsis device table in the dump gives the true model and version mix (MQ-53, Q31). Until then every budget is measured on the primary device and recorded with its model, OS build and battery health; a battery under 85 percent health is not used for a gate run.

### 8.2 The scripted field day (the measurement workload and the integration-test script)

| Item | Standard day (S) | Heavy day (H, D-398) |
| --- | --- | --- |
| Route | 50 outlets, one route (below the median planned route of 64 and the average of 66, docs/22 P-13, P-17); the median route of 64 and the p99 route of 112 are run as the M and H days with the same script scaled by n | 112 outlets (p99 route, docs/22 P-13) |
| Memos | 50 printed, 2.0 lines each | 100 memos plus 12 zero sales, 2.5 lines each |
| Fixes | 60: 50 opens, 5 refreshes, 5 force-sale; plus 2 attendance | 132: 112 opens, 8 refreshes, 10 force-sale, 2 attendance; plus up to 10 warm-ups |
| Photos | `photos(n) = round(0.267 x n)`: 13 for 50 outlets (5 force-sale, 5 outlet capture, 3 survey), 17 for the median route of 64, 30 for the p99 route of 112. ONE source: the load model uses 150,000 photos a day over 8,500 SRs, 17.6 a day at the average route of 66 outlets (doc 18 s1.1, D-75; the first sizing's 130,000 "measured" photos came from no data in docs/22, so 150,000 is a DESIGN value and an ASSUMPTION). The earlier script used 10 and 14 and so hid the data cost (D-508) | 30 |
| Prints | 50 memos, 1 stock slip, 1 day summary | 112 memos and slips |
| Trickle batches | 55 over a connectivity pattern with 60 percent online time | 112 |
| Bundle | 1 full and 3 deltas | 1 full (p95, 60 KB gz) and 5 deltas |
| Close | Sales Submit and check-out at 17:00 Dhaka | same |
| Screen-on | 90 min at 50 percent brightness | 170 min (ASSUMPTION: scaled with outlets) |
| Printer | Connected on the Stock screen and at each print | same |
| Connectivity | Mobile data online for 36 min of every hour (minutes 0 to 36) and offline for the other 24 min (60 percent online); Wi-Fi only after 17:30 for the media queue (T8) | same |
| Duration | 8 h wall clock; an accelerated 2 h variant run four times is allowed for CI-adjacent runs but the gate numbers are for the 8 h run | same |

#### 8.2b Scripted days for the AMO and TSO flavours (D-506, G-qa-30)

The SR script above is the R4 oracle for the SR flavour only. The AMO and TSO flavours add the Google Maps SDK, paged zone bundles, Team Location, Update Base, the retailer map and a Final Submit, none of which the SR script exercises, so each flavour has its own script, its own blocking gates and its own section of the per-release perf file (T-3-151, T-3-152, T-3-153).

| Item | AMO day (A) | TSO day (T) |
| --- | --- | --- |
| Scope | a 54-route zone, about 3,500 outlets; zone bundle about 350 KB gz in pages (s6.4) | a territory with about 4 zones; login snapshot below 1 MB gz (s6.6) |
| Core actions | 5 control calls and 3 joint calls (8 supervisory visits, each with a geo gate and an assessment); 10 own sale calls with 10 printed memos; 6 outlet verifications (2 with a photo); 1 Update Base; attendance in and out at 17:00; Sales Submit | 1 login snapshot; 12 dashboard opens (cached, "as of" stamped); Set Plan for 1 route; 4 visit queries with a task assignment; 1 leave application; 1 feedback with 1 gallery image; 3 OTP panel views; Final Submit preview and Final Submit at 18:30 |
| Reads | Team Performance, Live Dashboard and SR Stock opened 3 times each (4 KB gz per read) | Target Status opened 3 times |
| Maps | Team Location opened 2 times (list first, then the map), Update Base map once | My Team once and Retailer (100 m) once |
| Fixes | 31 (8 + 10 + 6 + 1 + 2 attendance + 4 refreshes) | 8 (4 visit queries, 2 map opens, 2 refreshes) |
| Photos | 5 | 1 (gallery image, compressed to 150 KB) |
| Bundle | 1 paged zone bundle pre-fetched at 22:00 on Wi-Fi the evening before (not counted as mobile) and 5 deltas | 1 login snapshot (Wi-Fi when available), picker deltas 5 |
| Screen-on | 150 min at 50 percent brightness (ASSUMPTION: scaled for dashboard use) | 120 min (ASSUMPTION) |
| Connectivity | the s8.2 pattern (60 percent online); Wi-Fi after 17:30 | mobile online 80 percent (a TSO sits more in towns; ASSUMPTION) |
| Duration | 8 h wall clock | 8 h wall clock |

Blocking gates per flavour. A value marked ASSUMPTION becomes "first measured RC x 1.2" once the 3a (AMO) or 3b (TSO) baseline exists, and from then on a regression above 20 percent against the previous RC blocks the release (s8.8 step 7). Until the baseline exists a miss is NOT an alert: it blocks the 3a or 3b exit.

| Metric | AMO gate | TSO gate | Basis |
| --- | --- | --- | --- |
| App-attributed non-screen drain over the scripted day | at most 8 percent of 5,000 mAh | at most 4 percent | ASSUMPTION (SR 6 percent plus maps and dashboards; TSO is read-mostly) |
| Whole-device level at 17:00 from 100 percent at 08:00 | at least 55 percent | at least 62 percent | ASSUMPTION |
| GPS | at most 80 fixes and 15 min (script uses 31) | at most 80 fixes and 15 min (script uses 8) | D-73, D-74 |
| Mobile data per day without photos and without map tiles | at most 2 MB | at most 1.5 MB | s8.4 role gates, promoted from ASSUMPTION to gate by D-506 |
| Map tile traffic | at most 1 MB per map session and 5 sessions a day, counted separately | same | s10.5, T-3-42 |
| Download size per ABI with the Maps SDK | at most 30 MB (estimate 25.0 MB: SR 22.7 MB plus Maps SDK client about 2.0 MB plus paging and picker code about 0.3 MB; ASSUMPTION checked by T-3-153) | at most 30 MB (estimate 24.7 MB) | D-73; if a flavour exceeds 30 MB the D-08 fallback (lite static tiles, or MapLibre) applies before the exit |
| Installed size | at most 70 MB | at most 70 MB | D-73 |
| Cold start on the primary device | at most 2.5 s | at most 2.5 s | D-73 |
| Wake-lock time, WorkManager executions, foreground services, alarms | as the SR gates (10 min per day, 100, 0, 0) | same | D-73 |

### 8.3 Battery budget (gates; measured on the primary device)

D-73 fixes the standard-day numbers. The heavy-day column scales each by `0.2 + 0.8 x n / 50` (n = outlets in the script; ASSUMPTION: 20 percent of each figure is fixed cost such as attendance, bundle, submit and idle, and 80 percent scales with outlets; replace by measured slopes after two pilot weeks, T-7-20). The median planned route (64) gives a factor of 1.224, the p99 route (112) a factor of 1.992.

| Metric | Standard gate (n = 50) | Median (n = 64) | Heavy gate (n = 112) | How measured | Basis |
| --- | --- | --- | --- | --- | --- |
| App-attributed non-screen drain over the scripted day | at most 6 percent of 5,000 mAh (300 mAh) | 7.3 percent | 12.0 percent | Battery Historian estimated power for the app uid, screen excluded | D-73; planning decomposition: CPU for 90 min foreground about 180 mAh, GPS about 10, radio about 20, Bluetooth about 15, margin |
| Background drain, 8 h, 50 pending rows, no network | at most 1 percent (50 mAh) | | | App backgrounded, airplane mode | D-73; T4 cannot run (constraint unmet) |
| Background drain, 8 h idle with network and empty outbox | at most 0.5 percent | | | | planning draft (ASSUMPTION); T5 is not registered |
| Whole-device level at 17:00 from 100 percent at 08:00 | at least 60 percent | | at least 50 percent | Battery level | planning decomposition: idle about 12, screen about 8, app 6, margin (ASSUMPTION); heavy computed as 12 + 15 + 12 |
| Wake-lock time attributable to the app | at most 10 min per day, longest at most 90 s | 12.2 min | 19.9 min | Battery Historian | D-73; each batch at most 30 s |
| GPS | at most 80 fixes and 15 min per day | 98 and 18.4 min | 159 and 29.9 min | Battery Historian, `geo_fix_local` count | D-73, D-74; no position stream, no background location |
| Mobile radio active time attributable to the app | at most 20 min per day | 24.5 min | 39.8 min | Battery Historian | planning draft (ASSUMPTION); one POST per visit; a cellular tail of 5 to 12 s per burst is assumed (carrier dependent) and measured in T-1-36 |
| WorkManager executions | at most 100 per day | 122 | 199 | `dumpsys jobscheduler` | planning draft: 32 periodic plus at most 60 one-offs |
| Foreground services | 0 | 0 | 0 | `dumpsys activity services` | D-73 |
| Alarms | 0 | 0 | 0 | `dumpsys alarm` | D-73 |
| Sustained CPU while idle on a list screen | at most 25 percent | | | Flutter DevTools | planning draft |

The median (n = 64) and p99 (n = 112) columns are BLOCKING gates, not alerts (D-508, G-qa-32): they apply from the first RC after T-1-36 has produced a measured baseline and in every case from the 2e exit, because a Board-facing figure of 6 percent and 80 fixes holds for a 50-outlet route only; the plan's own formula gives 7.3 percent and 98 fixes for the median route and 12 percent and 159 fixes for the p99 route, and the doc 14 s1.2 R4 row shows all three. D-398 is amended accordingly.

### 8.4 Data budget (gates; `dumpsys netstats detail` per uid, mobile and Wi-Fi split)

| Item | Bytes | Count per day | Total |
| --- | --- | --- | --- |
| Full bundle (gz) | 25 KB median, 60 KB p95 | 1 | 25 to 60 KB |
| Deltas | 0.3 to 5 KB | at most 15 | at most 75 KB |
| Trickle batches including TLS and header overhead (about 2 KB body plus about 1.5 KB handshake and headers; ASSUMPTION, measured in T-1-36) | 3.5 KB | 55 (S), 70 (M) or 112 (H) | about 190 KB (S), 245 KB (M), 390 KB (H) |
| Responses | 1 KB | 55 or 112 | 55 or 112 KB |
| Home KPI, health, release check, SAS calls | 1 KB | at most 30 | 30 KB |
| Catch-up of a full offline day | about 12 KB gz | 2 batches | 24 KB |
| **Mobile data without photos** | | | **S about 0.4 MB, H about 0.7 MB (computed); gate at most 1 MB per day (D-73)** |
| Photos | at most 150 KB | `photos(n)`: 13 (S), 17 (M, the median route), 30 (H); one source, s8.2 | 1.95, 2.55 or 4.5 MB; Wi-Fi by default; on mobile only by the toggle or the evidence fallback |
| **Mobile data with every photo on mobile** | | | **gate at most 3.0 MB per day for a 50-outlet route (D-73); the gate scales with the route like the other budgets: 3.7 MB for the median route and 6.0 MB for the p99 route (3.0 x (0.2 + 0.8 n / 50)); computed S 2.35 MB, M 3.04 MB, H 5.2 MB (D-508, G-qa-32)**; typical mobile bytes are far lower because photos go on Wi-Fi first (about 15 MB a month) |
| One-time per device | APK at most 30 MB per ABI, thumbnails about 0.6 MB | | Wi-Fi preferred; served from Blob behind Front Door, not the API (G-scale-16) |
| Monthly, 26 selling days, photos on Wi-Fi | | | about 15 MB mobile: the 2 GB per day pack is not touched by the app (PROJECT-CONTEXT) |

| Role gate (a GATE since D-506; the values stay ASSUMPTION until the 3a and 3b baselines, G-17-03) | Mobile data per day without photos | Reason |
| --- | --- | --- |
| SR | 1 MB | D-73 |
| AMO | 2 MB | A 54-route zone bundle is about 350 KB gz and up to 1.1 MB at 11,000 outlets; pre-fetch on Wi-Fi at 22:00 keeps most of it off mobile |
| TSO | 1.5 MB | Login snapshot below 1 MB gz plus picker deltas and aggregate reads |

### 8.5 Size budget (gates)

| Gate | Value | Source |
| --- | --- | --- |
| Download size per ABI | at most 30 MB (CI fails above), aim 22 MB | D-73, T-1-33 |
| Installed size | at most 70 MB; the Apsis baseline is 92 to 101 MB installed and 74 to 80 MB as an APK file (D-224) | D-73 |
| Cold start | at most 2.5 s on the primary device (`adb shell am start -W`) | D-73 |
| Release-to-release | APK growth above 5 percent warns, above 15 percent fails | D-399 |

| Component | Estimate per ABI (ASSUMPTION, checked by T-1-33) |
| --- | --- |
| Flutter engine and framework | about 8 MB |
| Dart AOT code | about 5 MB |
| SQLCipher native library | about 3.5 MB (planning draft: about 7 MB for two ABIs) |
| Plugins (location, camera, Bluetooth, WorkManager, secure storage) | about 2 MB |
| One Bengali family (Noto Sans Bengali Regular and Bold, subset to Bengali plus Latin digits) | about 0.7 MB; Latin uses the platform font |
| Icons and static assets | about 1.5 MB; no demo audio or images, no bundled tutorial videos, no product thumbnails (served from Blob with immutable URLs) |
| FCM, if D-09 is accepted | about 2 MB |
| Total | about 22.7 MB |

The 30 MB per ABI gate applies to all three flavours; the AMO and TSO estimate with the Maps SDK is in s8.2b (D-506, T-3-153). The load model of doc 18 uses the same 30 MB, not the 35 MB of its first draft (D-508). Levers, in order: per-ABI split APKs (`armeabi-v7a`, `arm64-v8a`; no x86), R8 full mode with `shrinkResources`, `--obfuscate --split-debug-info`, `--tree-shake-icons`, an asset manifest diff per release, and a dependency allowlist (one HTTP client, one DB, one Bluetooth plugin, no maps SDK in the SR flavour). If SQLCipher pushes the APK above 30 MB the D-67 fallback applies.

### 8.6 Photo pipeline (D-75; F-SYS-030)

| Step | Rule |
| --- | --- |
| Capture | Camera-only for evidence photos (force sale, outlet capture, base update); `ResolutionPreset.medium` (about 1280 x 720), never `max`; flash auto; the preview is torn down at once after capture ("do not keep the camera warm") |
| Compress | Native resize: long edge 1024 px, JPEG quality 70; if bytes exceed `cfg.media.photo_max_kb` (150) lower quality by 10 down to 40, then long edge 800; EXIF stripped except orientation; no GPS in EXIF (lat, lng, accuracy and `is_mock` live in `media_queue` and `media_meta`) |
| Fingerprint | SHA-256 computed once; perceptual hash stored |
| Queue | The file goes to the app-private `media/` directory; the `media_queue` row and the owning record's `photo_client_uuid` and `blob_path` are written in the same transaction as the record |
| Retake | One retake before the record is saved |
| Display | From the local file; thumbnails generated lazily at 256 px into the LRU cache |
| CPU | At most 1.5 s per photo on the primary device (T-2-30) |
| Server limits | `bytes <= 300 KB`, dimensions at most 1,600 px (doc 16 s7) |
| Storage pressure | D-404: below 500 MB free the image cache is evicted automatically; below 200 MB photo capture is refused ("ছবি নেওয়ার জায়গা নেই") while force sale stays allowed with `photo_pending_storage` (ASSUMPTION: the business may prefer to block; confirm) |
| Upload | s4.14 |

### 8.7 Code-level R4 rules (enforced in review and by T-1-37)

| Rule | Check |
| --- | --- |
| No `Timer.periodic` under 60 s anywhere | Static search in CI |
| No `Isolate.spawn` per sync: one long-lived worker isolate | Code review |
| No foreground service, `AlarmManager`, `setInexactRepeating` under 15 min, geofence callback | Manifest and `dumpsys` in T-1-37 |
| Location permission `whileInUse` only; no `ACCESS_BACKGROUND_LOCATION` | Manifest lint |
| `connectivity_plus` subscribed only while the app is resumed | Code review |
| Bluetooth disconnected after `cfg.print.disconnect_idle_s` (120) of no printing except on the Stock screen | T-2-32 |
| No `WakelockPlus` in UI code | Static search |
| No animations on list screens beyond platform defaults | Design review |
| `allowBackup` false | Manifest lint |

### 8.8 Measurement protocol (run per release candidate on the primary device; once per phase on the secondary and legacy devices)

1. Fresh install of the RC; printer paired; `adb shell dumpsys batterystats --reset` and `dumpsys netstats --reset` (or record baseline counters); unplug.
2. Start the script of s8.2 through Maestro or Appium; drive connectivity with `adb` over Wi-Fi ADB from a host (USB would charge the phone), following the connectivity row of s8.2.
3. After 8 h: `adb bugreport`, then Battery Historian: the app uid's estimated power, wake-lock total and longest, GPS time, mobile radio active time, job executions, alarms (must be 0), foreground-service time (must be 0). `dumpsys netstats detail` for mobile and Wi-Fi bytes of the uid.
4. Background variant: 50 pending rows, airplane mode, app backgrounded 8 h: drain at most 1 percent; then enable the network and confirm sync within 3 min (T-1-30).
5. APK size gate and cold start (s8.5).
6. Record the numbers in `/docs/perf/battery-<version>.md` with device model, OS build and battery health, as three sections (SR, AMO, TSO flavours; the AMO and TSO sections use the scripts of s8.2b); the build is not shippable without it (D-399, D-506).
7. A regression above 20 percent against the previous release blocks the release; the first RC sets the baseline.

### 8.9 What the numbers do not cover

The app's budget excludes other apps on a shared phone and MDM or APN traffic: the known 2 GB per day burn is mostly non-app usage (PROJECT-CONTEXT), and the company-side lever is MDM or APN. The app must only never be the cause; the per-uid measurement is how that is shown. Battery wear of the phone, screen brightness and signal strength are held at the protocol's fixed values.

Proved by: T-1-33, T-1-36, T-1-37, T-2-26, T-2-30, T-2-34, T-7-20.

### 8.10 Field evidence: the same budgets measured on the fleet, with an automatic halt (D-507, D-509, G-qa-31, G-qa-33)

Lab phones prove R4 on lab phones. Wave go/no-go and the release console need the fleet's own numbers, and R4 says the app must never drain battery or data "abruptly", which only a field signal can show. The daily telemetry object (s4.4) is the source; fact_device_day (doc 16 s8.3) stores it; sync-health shows it (doc 18 s6.4).

| Signal (sync-health tile) | Definition | Alert and halt threshold | Source |
| --- | --- | --- | --- |
| SH-19 Mobile data | p95 over active SR devices of `b_mob` (non-media) per device-day and, separately, `b_mob + b_mob_media` | non-media above 1.25 x the route-size gate of s8.4 for 2 consecutive days in a wave, or above 3.7 MB with media | `fact_device_day.bytes_mobile_app` |
| SH-20 App CPU and starts | p95 `cpu_ms` and `engine_starts` per device-day by app version | a canary cohort above the previous release by more than `cfg.release.auto_freeze_regression_pct` (20 percent) | `cpu_ms`, `engine_starts` |
| SH-21 Battery | median and p95 of whole-device battery drop 08:00 to 17:00 for devices not charged since 08:00, by device model and app version | a canary cohort above the previous release on the SAME model mix by more than 20 percent, or the p10 of `battery_pct_17` below `cfg.telemetry.bat17_floor_pct` (default 35) for any wave | `battery_pct_08`, `_17`, `charged_today` |
| SH-22 Immediacy online | share of rows captured with validated connectivity that were ACKed within 60 s and within 5 min | below 95 percent within 60 s or below 99 percent within 5 min over a rolling hour of the 09:00 to 17:00 window | `fact_memo.sync_latency_s`, `connectivity_regained_at` |
| SH-23 Immediacy after reconnect | share of devices whose offline-captured rows were ACKed within 3 min of `connectivity_regained_at` | below 90 percent over a day | `regained` |
| SH-24 Held rows | devices with pending rows and no contact for more than `cfg.sla.pending_rows_alert_h` (4) | any device beyond 24 h is escalated to the TSO list (s4.1b) | `X-Pending-Rows` |

Automatic halt (release console, doc 19 s9.1): when a canary cohort (at least 100 device-days, stratified by model) regresses on SH-19, SH-20 or SH-21 by more than `cfg.release.auto_freeze_regression_pct` against the previous release, `wave_pct` is FROZEN at its current value, no further promotion is possible until a named release manager clears the freeze with a reason, and a Sev2 alert is raised. A bad build cannot be rolled back on a phone (the updater only moves forward), so the remedies are, in order: the remote brakes below (take effect on the next config pull), a forward-fix build within 24 h, and a re-release of the previous APK under a higher version code.

Remote brakes (C1 operational switches, doc 19 s3.2.5, effect on the next config pull, scopable to a wave or a canary cohort): `cfg.ops.prefetch_enabled` (the Wi-Fi bundle pre-fetch), `cfg.telemetry.enabled` (the daily telemetry object) and `cfg.geo.radio_env_enabled` (the passive radio read). Each is off-capable without a release; none touches capture, sync of records or printing.

Proved by: T-2-153, T-7-155, T-7-156.

## 9 Printing and memo numbering

### 9.1 Hardware, transport and pairing (D-76, D-391; resolves G-man-023 and G-feat-43)

| Item | Specification |
| --- | --- |
| Printer class | RPP02N-class 58 mm thermal, ESC/POS, **Bluetooth Classic SPP** (RFCOMM, UUID `00001101-0000-1000-8000-00805F9B34FB`); BLE is optional later. 58 mm paper is 384 dots |
| Code structure | One `PrinterPort` interface (connect, write, status, disconnect) so the plugin can be swapped; the transport plugin and `esc_pos_utils_plus` for byte generation sit behind it |
| Permissions | Android 11 and below: `BLUETOOTH`, `BLUETOOTH_ADMIN`. Android 12 and above: `BLUETOOTH_CONNECT` only, because the app lists bonded devices and never scans, so `BLUETOOTH_SCAN` is not requested. Bluetooth denied disables printing only (D-74) |
| Pairing | Once per phone in the Android settings (PIN usually 0000 or 1234: `cfg.print.pairing_pins`); an in-app "Pair printer" shortcut shows a bonded-device picker filtered by `cfg.print.models` (RPP02N); the app stores `printer_mac` and `printer_name` in `aron_device.db` (device level: shared phones share the printer) and reports them in `device_integrity` |
| State icon | On Stock, Review, Memo and Summary (a persistent header element, tap to reconnect, UI-SR-20): red slashed printer = not connected, green = connected; banner "প্রিন্টার কানেক্ট করা হয়েছে" on success |
| Lifecycle | `disconnected`, `connecting` (at most 8 s), `connected`, `printing`, `connected`, then idle disconnect after `cfg.print.disconnect_idle_s` (120) except while the Stock screen is open. Auto-connect on entering Stock and on any print request; two reconnect attempts 2 s apart; a failure opens the picker |
| Print enabled | Only when the printer is connected AND the document is saved (D-76) |
| Coexistence | Only one app can hold the RFCOMM socket; the idle disconnect is what lets the new app and the Apsis app run side by side during the pilot (F-SYS-044) |
| Battery | About 15 mAh a day (planning estimate): connected only on Stock and while printing |

### 9.2 Template contract (D-76, D-392; resolves G-sync-02 and G-man-014)

The printed layouts are never shown in any manual and no sample is in the repository (Q-UI-09, MQ-57). The template contract of this section is complete except for the sample-derived layout; Phase 1a cannot exit on the memo-parity gate (T-1-41) until AKTCL supplies physical 58 mm samples.

| Kind | Content beyond the common header and footer | Golden print test |
| --- | --- | --- |
| 1 Cash memo | Lines, totals, paid | T-1-35 |
| 2 Credit memo with partial payment | Credit panel: paid, due on this memo, previous due with the staleness marker, total due | T-1-35 |
| 3 Memo with offer | Offer discount lines (SKU, quantity, value, kind; D-343) | T-1-35 |
| 4 Memo with DRP or slide | The slide deduction line; fixture: 10 empty MaxR-10S packets give one reward pack, 10 sticks x 8.00 = 80.00 shown as a deduction (D-33) | T-1-35, T-2-41 |
| 5 Zero-sale memo | The zero record with its number; printable (D-36) | T-1-35 |
| 6 Edited memo | New number and "supersedes <memo_no>" | T-1-35 |
| 7 Stock memo | Issue by SKU per category with its unit, totals, SR and distributor signature lines (the physical hand-over document; Q-UI-03) | T-1-35 |
| Day summary | Per-SKU memo count, quantity, value, discount, discounted value and return; category totals; grand total (UI-SR-30) | T-1-35 |
| Due receipt | Outlet, memo(s) allocated, amount, remaining balance, collector, time, short code of the `due_collection` uuid (D-37, F-SR-070; IMPROVEMENT) | T-2-93 |
| Cancel slip | "বাতিল" with the voided `memo_no` (D-86; IMPROVEMENT; Q43) | T-2-92 |
| Parallel-run test print | Watermark "পরীক্ষামূলক - এটি রসিদ নয়", no previous-due line (D-152) | T-2-99 |

| Content rule | Specification | Source |
| --- | --- | --- |
| Header | Brand line (unknown, from samples), distribution house and zone, route label, SR name and username, `memo_no`, date and time (Dhaka, trusted), outlet name, code, owner; phone shown as the last 4 digits only (ASSUMPTION: PII; samples decide) | D-107 |
| Route label | "রুট" on review screens and "সেকশন" only where a physical sample prints it; one stored field (D-214) | I-07 |
| Lines | `SKU short name`, quantity with its unit (sticks, pieces, dozens: never a bare number), unit price, amount; Font B 42 columns; offers as discount lines | D-17, D-16 |
| Totals | Gross, offer discount, DRP or slide deduction, QC settlement, net payable: every non-zero component is printed (D-18); paid; due on this memo; previous due; total due; loyalty points earned and balance when Diamond League applies | D-18, D-158 |
| Money labels | One Bangla label per term, set signed by the sponsor's delegate (D-346); the Home cards use "সর্বমোট" for the net on one card and the spaced "সর্ব মোট" for the gross on the other (UI-SR-07) | D-346 (MUST-CONFIRM by 1a) |
| Rounding | The total is summed from unrounded line values and rounded once; `round_adj_mtk` is stored and never printed; a printed line amount can differ from `qty x displayed price` by rounding. Parity to the paisa with Apsis depends on the rule (MUST-CONFIRM by 2a; gate T-2-41) | D-19 |
| Digits | Follow the UI language by default, including the rasterised memo (`cfg.i18n.digit_script`); the printed digits of the current memo are unknown; stored values are ASCII | D-344, MQ-59 |
| Two on-screen layouts | Credit memo "রুট:" with category subtotals and a credit panel; cash memo "সেকশনঃ" with one "মোট" row and no subtotals. Whether they are two templates is unknown (MQ-58); default: one engine, two layout variants chosen by memo kind | G-man-014 |
| Footer | Duplicate marker on a reprint (s9.6), supersedes line on an edited memo, thanks line, three line feeds and a partial cut if supported; no QR in v1 (the planning option is not evidenced) | |
| Sanitising | Names reaching the printer have control characters (ESC and GS sequences), bidi and format characters stripped on the device as well as on the server (D-118) | D-118 |

Template is data: `cfg.print.template_version` and the template JSON of each kind in the bundle (`templates`, s6.1); the APK embeds one default of each kind as the fallback when the JSON is absent or fails schema validation; a corrected layout during the pilot needs no release. A template change is a config change (class C1 or higher in doc 19) and is future-dated for printed-document parity.

### 9.3 Rendering

| Item | Specification |
| --- | --- |
| Bangla text | Rasterised (`GS v 0`) from the bundled Noto Sans Bengali at 24 px, because the printers' code pages have no Bengali; static labels are rendered once per template version and cached; Latin text and digits as text where the code page supports them |
| Text | Font A 12 x 24 gives 32 columns; Font B 9 x 17 gives 42 columns; amounts right-aligned, two decimals from mtk |
| Chunking | Write in 512-byte chunks with 20 ms pacing (cheap clones overrun their buffer) |
| Status | `DLE EOT 1` read only if the firmware supports it (`cfg.print.status_query`, default off); paper-out is therefore usually undetectable (s9.4) |
| Length | A memo supports 60 lines (D-246); print time and bitmap memory (about 70 KB for 60 lines) are measured in T-1-35 (ASSUMPTION: 3 to 6 s per memo) |

### 9.4 Print flow and failure handling (D-77, D-391, D-394; resolves G-man-006 and G-field-20)

1. The sale is committed before any printer call (s3.1): the sale is final regardless of printing.
2. Dialog 2 "হ্যাঁ" creates `print_job(kind, ref_client_uuid, attempt, state = queued)`; the bytes are rendered and cached per job; connect; write; on success `done`, `printed_at` set on the first success only, `print_count + 1`.
3. After each print the app asks "ছাপা ঠিক আছে?" (`cfg.print.confirm_after_print`, F-SR-073). "হ্যাঁ" marks `user_confirmed`. "না" marks the job `failed_user`: the next print carries no duplicate marker and does not count toward `cfg.memo.reprint_max` (D-322). This covers the half-printed clone memo that the printer cannot report.
4. Skip after a failure leaves `print_job.state = failed`, the memo flagged unprinted with an icon in the Memo list, and Reprint available at any time.
5. A `print_job` is a sync record (rank 2 of the memo's family) so the server can compute `print_count` and see reprint patterns.

```
 queued ─(render, cached per job)─▶ connecting ─(<= 8 s)─▶ printing ─(all chunks written)─▶ done ─("হ্যাঁ")─▶ confirmed
 connecting ─(timeout; 2 retries, 2 s apart)─▶ failed ─(Retry)─▶ queued       printing ─(printer off, write error)─▶ failed
 done ─("না")─▶ failed_user         printing older than 60 s on relaunch ─▶ failed
```

| Failure | Behaviour | Gate |
| --- | --- | --- |
| Not paired | Picker; selling continues | T-1-35 |
| Connect timeout | Retry twice, then "প্রিন্টার সংযোগ হয়নি" (authored) with Retry and Skip | T-2-32 |
| Printer off or out of range mid-print | Same dialog; job `failed`; the memo exists | T-2-32 |
| Paper out | Detected only with `status_query`; otherwise caught by the confirmation | T-2-32 |
| Bluetooth denied | Sale saves, print fails gracefully with the denied message | T-2-32 |
| Template invalid | Embedded default template prints; telemetry row | T-1-35 |
| Stock slip unprinted | Save always works; the stock row carries `stock_slip_printed = false`; Sales Submit warns (doc 15 D-321, F-SR-015); with `cfg.stock.require_printed_slip` true Sales Submit is blocked unless a supervisor override exists (Q-UI-03 default) | T-2-32 |

### 9.5 Memo numbering (D-35, D-393; resolves G-data-08, G-scale-18 and Q5)

Format: `<username>-<yyMMdd>-<seq3>` (D-35), for example `sr334001-261004-017`; `seq3` is three digits for bind ordinals 0 and 1 and four digits for later ordinals (D-393).

| Part | Source | Why |
| --- | --- | --- |
| `username` | The capturing user (`sr334001`, `amo5001`, `ss344002`); a substitute or an AMO selling for an SR uses their own series; KPI attribution is by `route_id` and `acting_for_user_id`, not by the number | Unique per person; one series per user |
| `yyMMdd` | The family's business date (s5.5) | Ties the number to the date it is counted on |
| `seq` | From `memo_counter(business_date, bind_ordinal).next_seq`, incremented inside the memo commit transaction | Offline-safe without coordination |

| Block | Range | Width |
| --- | --- | --- |
| Ordinal 0 (first device that day) | 001 to 500 (`cfg.memo.seq_block_size` 500; bounds 100 to 999) | 3 digits |
| Ordinal 1 (replaced or second device) | 501 to 999 | 3 digits |
| Ordinal 2 | 1001 to 1500 | 4 digits |
| Ordinal 3 | 1501 to 2000 | 4 digits |
| Overflow for ordinal k | 3000 + 1000 k to 3999 + 1000 k, used only when a block is exhausted; the server flags `memo_no_overflow` | 4 digits |
| Ordinal 4 or more | Not issued: a fourth replacement of a user's phone on one date needs support | |

Why the table differs from the planning draft: a 3-digit `seq` allows only two disjoint 500-blocks (001 to 999). Ordinals 2 and 3, and an exhausted block (500 memos a day is more than 4 times the largest planned route of 112 outlets, docs/22 P-13), would otherwise collide or break the regex. D-35 states `seq3`; widening to 4 digits for those cases is a recorded extension (D-393, G-17-01). Which width the printed number shows for ordinals 2 and 3 is for the business to confirm with the printed samples.

| Rule | Detail |
| --- | --- |
| Uniqueness | One user, one date, one bind ordinal, one monotonic counter in the per-user DB. The counter is incremented in the same transaction as the memo insert: a rolled-back save restores it, so the numbers of a block have no gaps unless a row was lost or hidden |
| Gap detector | A nightly job reports missing numbers inside `[block_start, max_used]` per user and date (F-SYS-069); `sale_abort` activity rows explain legitimate cases; an unexplained gap is a lost-row or "cleared data" signal (G-field-14) |
| Server validation | The regex generated from the contract; the prefix equals the authenticated username; the date part equals `business_date`; `seq` lies in the block or overflow range of the device's `bind_ordinal`; `UNIQUE (memo_no, business_date)`. A mismatch is `rejected(memo_no_invalid)`, never rewritten |
| Usernames | A username must not contain "-" (it would break the split); raised to doc 16 and doc 21 (OI-17-10) |
| Zero sale and edit | A zero-sale memo consumes a number (D-36); an edited memo takes the next number and references `supersedes`; a void is an event and consumes no number; a reprint keeps the number |
| Bind ordinal (the device slot) | Assigned by the server at `POST /auth/bind-device` as the lowest slot (0 to 3) of that user that is neither active nor revoked on the same business date, and stable on later days. A revoked slot is held until the next business date, so two devices of one user never share a slot on one date; a fourth simultaneous or same-day slot is refused ("ask support"). The bind response carries the slot and the block size, which the device stores; a later change of `cfg.memo.seq_block_size` (class C3, future-dated) applies to new binds only |
| Imported memos | Apsis memos keep their original number in `memo_no` with `entry_source = migration`, so dues and reprints of old memos show the number the retailer holds |

Cutover options (Q5, MUST-CONFIRM by 1a, before the first golden print):

| Option | What the retailer sees | Server | Verdict |
| --- | --- | --- | --- |
| A. New series only | `sr334001-261004-017` | `memo_no` verbatim; `memo_serial` NULL | DEFAULT: offline-safe, zero coordination, obviously the new system on day one of a wave |
| B. New number plus a server `memo_serial` continuing the Apsis series, printed on reprints only | Both numbers after sync | `memo_serial` column filled from a sequence started at the Apsis maximum plus one | Optional later; the first print happens offline before the server exists; two numbers confuse retailers |
| C. Pre-allocated blocks of the Apsis series per device | Continuous-looking numbers | Server hands out ranges at login | Rejected: gaps and out-of-order numbers by design, a device that never returns strands a block, offline rebind breaks it |

### 9.6 Reprint (D-394, applying D-322)

| Rule | Detail |
| --- | --- |
| Marker | The paper carries "পুনর্মুদ্রণ #n" (n = `print_count`) on a reprint unless the previous print was `failed_user`; the exact text is taken from the physical samples (what Apsis prints is unknown; MUST-CONFIRM by 1a) |
| Limit | `cfg.memo.reprint_max` (5, ASSUMPTION per D-322); the AMO sees counts |
| Edited memo | Prints the new number and "supersedes <memo_no>"; the old paper memo is the retailer's; no cancel slip unless the void flow ran (D-86) |
| Reprint scope | Any memo in the local history window (7 days) offline; older ones need the online fallback (D-83) |

Proved by: T-1-24, T-1-35, T-1-41, T-2-20, T-2-32, T-2-41, T-2-92, T-2-93, T-2-99.

## 10 App lifecycle

### 10.1 Flavours, identifiers and file names (D-01, D-205)

One Flutter codebase, three flavours with distinct applicationIds that also differ from the Apsis apps (side-by-side on a pilot phone), launcher labels "ARON SR", "ARON AMO", "ARON TSO". The TSO callout "ARON SR" in the manual is a copy-paste; the app is ARON TSO (D-232). APK file name: `aron_<role>_app_<dd_mm_yyyy>_v<version>.apk` (IMPROVEMENT: the Apsis names end "_v1" for every build, so the suffix is not the version). The login footer, the drawer and Settings show the installed build version from one source; the post-update splash shows the pre-update number only as a processing label (D-208).

### 10.2 The updater (D-79, D-223; resolves G-man-022, G-sync-15 and G-sre-20; F-SYS-020)

| Stage | Rule | Evidence |
| --- | --- | --- |
| Check | `GET /app/update-check?version=&role=&abi=` (F-API-029) at login and at most once per 12 h on foreground (ASSUMPTION); `cfg.release.update_prompt_policy` default `prompt`, never `force` on mobile data; `cfg.release.update_wifi_only` | SR p7 shows the page, release notes and a "Network Status" line |
| Unknown sources | `canRequestPackageInstalls()`; if false, Bangla instructions and a deep link to "Install unknown apps" for this app | SR p7 PARITY |
| Distribution channel risk (D-562, G-qa-94, RK-29) | A sideloaded 30 MB APK on 8,500 shared phones meets three platform frictions the first draft ignored: (1) Google's announced developer-verification requirement for apps installed outside Google Play on certified Android devices (announced for first countries from September 2026 and global in 2027; the current scope and any enterprise or limited-distribution path are CONFIRMED against Google's current Android developer documentation at 0c, not assumed here); (2) Play Protect scanning and warnings or blocks for an unknown-developer APK; (3) "Install unknown apps" friction on newer Android versions. Wave 1 (June 2027) falls inside that window, so an install that is blocked or warned on Android 14, 15 or 16 would fail the pre-bind day for a whole wave. The options, decided at 0c (D-10 moves from 2e to 0c): register AKTCL as a verified developer for the three package ids; a Managed Google Play private app; or a lightweight MDM (the fleet is shared-ownership, so factory-reset enrolment is not viable, docs/11). The Bangla install guidance (Play Protect "install anyway", unknown sources) is part of the readiness screen. Gate T-2-164: install and update the signed APK on current Android 14, 15 and 16 devices with Play Protect on, through the Bangla guidance flow; T-7-166 is the install-success line of the wave go/no-go | T-2-164, T-7-166 |
| Download | Resumable (HTTP range), percent progress, "do not close the app while downloading", SHA-256 verified before the OS installer, free space of at least 2 x the APK size checked first (ASSUMPTION) | Percent PARITY (SR p8); resumable, SHA-256 and the space check are design additions |
| Install | OS installer; never while a batch is `syncing` (wait at most 60 s) | G-sync-15 |
| First launch | On-device migration with determinate n/m progress ("অ্যাপ আপডেট হচ্ছে", "প্রসেসিং(n/m)", "টিপস: অ্যাপটি বন্ধ করবেন না"); the migration is transactional and keeps every row not `synced`; the word "অবশ্যই" on the post-install splash is a mandatory local migration, not a policy gate | SR p9 to p10; V-device |
| Per-ABI | Pick from `Build.SUPPORTED_ABIS` (`app_release.abi`) | G-sync-15 |
| Gates | `cfg.release.min_version` blocks a new day's login only; an open offline day finishes (`cfg.release.finish_offline_day_before_force` true); `blocked_versions` stops new captures only; neither blocks upload or wipes data | D-79, D-130; the forced gate is a design addition, not observed |
| Rollback | Downgrade is unsupported (reinstalling N-1 wipes app-private storage and the pending rows with it). Rollback is a roll-forward rescue release within 24 h on a pre-authorised path; the local schema is additive so N-1 can open a newer DB for one release; a release that changes the sync engine keeps the previous engine behind `cfg.sync.engine_mode` for one release; PDA to Support export is a scripted desk step before any reinstall | D-79, G-sre-20 |
| Interrupted | Kill during download resumes; kill during migration is safe (T-2-23); a partial file is kept at most 7 days (ASSUMPTION) | T-2-23 |

Distribution (D-10, MUST-CONFIRM by 0c because the channel decides the updater design and the Google verification risk, D-562; the first-morning storm it protects is T-7-57): AKTCL's own signed APK channel, Blob behind Front Door, per-ABI split APKs, `app_release` registry with `wave_pct`. APK bytes never touch the API: a 22 to 30 MB APK to 7,350 new users is 160 to 220 GB on a full-fleet day, served from the Front Door cache and, with the pre-bind day, from Wi-Fi (G-scale-16). For scale: the 80.4 MB Apsis SR file is about 3.9 percent of a 2 GB day; a 30 MB APK is about 1.5 percent. A Play track is optional and a Play-distributed build cannot self-install (MQ-50).

Version-scoped sync hold (D-130, G-sre-11). When a build loops on upload, the edge answers 429 with `Retry-After` for that `X-App-Version`; the device treats it as server-directed backoff (s4.6): rows stay on the device, nothing is blocked or wiped, and the hold auto-expires.

### 10.3 Permissions gating matrix (D-74, D-115; resolves G-man-020 and G-sync-11)

| Permission | Needed for | If denied or revoked | Source |
| --- | --- | --- | --- |
| Location, precise, while in use (`cfg.geo.require_precise` true) | Outlet open, attendance, force sale, outlet capture | Sale and Attendance are blocked with a Bangla rationale and a Settings deep link; Approximate cannot meet a 100 m geofence; "Only this time" asks again at next open; device Location services off shows the same block with a deep link. The planning draft's `permission_denied` force-sale reason is retired: D-74 blocks instead, because a force sale needs a fix | `cfg.app.location_denied_policy`; D-74 |
| Camera | Force sale, outlet capture, base update, survey, gift photos | Those flows cannot complete (no sale without geo or photo on a force sale, docs/05); an in-range sale is unaffected | D-75 |
| Bluetooth (CONNECT on Android 12 and above) | Printing | Printing disabled, selling continues | D-74 |
| Notifications (Android 13 and above) | The local pending-rows reminder | No reminder | D-397 |
| Install unknown apps | The updater | Update cannot run; guidance shown | s10.2 |
| Microphone | not requested | | D-115 |
| Background location, battery-optimisation exemption | never requested | The OEM guidance screen is shown instead (E-09) | CLAUDE.md 3 |

Android 11 and above can auto-reset unused-app permissions: the next outlet open shows the OS prompt or the rationale; no sale is lost because re-granting takes seconds (E-06).

### 10.4 PDA to Support (F-SYS-021; resolves G-man-025)

| Item | Rule |
| --- | --- |
| Entry | Settings tile "PDA টু সাপোর্ট" on SR and AMO (F-SR-006); the TSO gets it in the Settings improvement (F-TSO-024). The manuals call the file "সেলস ফাইল", "ডাটা ফাইল" and "সিঙ্ক ফাইল"; one name is used everywhere |
| Content | A support bundle, not the raw DB (which is SQLCipher-encrypted under a Keystore key support cannot read): the user's unsynced and rejected outbox payloads, synced payloads inside the re-sync window, `sync_journal`, the last 200 log lines without PII, app and schema versions, last sync, counts. Gzip, then encrypted to the AKTCL support public key (key custody is doc 21 s5, OI-17-11) |
| Transport | SAS upload (F-API-030); `cfg.support.pda_upload_wifi_only`; size cap `cfg.support.max_upload_mb`; queued when offline ("will be sent when online"); progress and failure states |
| Effects | Clears the TSO logout guard (s7.4); support replays the bundle through an admin import that uses the same ingest and the same `client_uuid` values, so replay is idempotent (D-402) |
| Fallback | If the phone is dead the memos exist only on paper. The Web Entry aggregate of B1 (route-day totals per SKU) does NOT restore a credit memo, a retailer's due, outlet-level STD or loyalty, so the recovery is the supervised memo-level paper backfill (F-ADM-075, D-543): support keys each printed memo by its `memo_no`, the zone TSO approves (four-eyes), the server writes the memo, lines and dues with `entry_source = 'manual'` under a deterministic uuid derived from the printed number so a double keying creates nothing twice, and if the phone later uploads the same `memo_no` the device row wins; the reconciliation screen shows the gap honestly until then (doc 19 s8.3, doc 16 s5.12) |

### 10.5 Maps client rules (D-08; resolves G-man-059 and G-field-18)

| Rule | Value |
| --- | --- |
| Where | AMO (Team Location, Update Base) and TSO (My Team, Retailer) flavours only; no map SDK in the SR flavour; no map on any dashboard |
| Provider | Google Maps SDK (the manuals show it), 2D only, lite mode, loaded only when its screen opens; MapLibre with OSM is the fallback if cost or the offline rule blocks Google. D-08 is MUST-CONFIRM by 3a (provider, quota, cost; 3D not required) |
| Key | Restricted by package name and signing SHA with a billing alert; `cfg.map.api_key_ref` is a Key Vault reference and the key is injected at build, never in the repository; `cfg.map.3d_enabled` false |
| Tile cache | Bounded disk cache `cfg.map.tile_cache_mb` (20, ASSUMPTION); worst-case TSO map session budget 1 MB mobile and at most 5 sessions a day (ASSUMPTION; not counted in the TSO's 1.5 MB non-map gate); measured in T-3-42 |
| Offline | Team Location falls back to a list: last synced fix with "last seen HH:MM (n min ago)", its source (check-in, visit, sync), greyed after `cfg.tso.team_location_max_age_min` (120; the AMO uses the same). No continuous tracking (D-170) |
| Update Base offline | Accepts the current fix and lets the AMO adjust numerically; the move limits are doc 19 s6 |
| Attendance address | Display only, and only when online: at most one reverse lookup per attendance event (2 a day); offline shows "lat, lng, accuracy" and the last known address. Coordinates are the stored truth; no address is stored: reports show the coordinates and a `locality_hint` that the worker derives from the stored fix (nearest cluster name within 500 m, else the zone name, doc 16 s5.8), so no stored value depends on a device lookup and no server reverse-geocode provider, quota or cost exists unless the business asks for addresses (D-538, MUST-CONFIRM by 4b; UI-SR-11, D-08, V-outlets G-man-059) |

### 10.6 Notifications and badges (D-84, D-09; resolves G-feat-35)

| Item | Rule |
| --- | --- |
| No polling | Badges (tasks, pending requests) come from the bundle delta and sync responses; the Task screen shows "synced at <time>" so an empty list is not mistaken for a stale one (UI-SR-44) |
| Local reminder | The periodic job posts "আপলোড বাকি {n}টি" at `cfg.sync.pending_reminder_time` (16:30 Dhaka) when rows are pending; needs `POST_NOTIFICATIONS` on Android 13 and above; no network involved (D-397) |
| FCM | Data messages only for urgent config (kill switch, `min_version`, blocked versions, reverts) with jitter; off in the pilot (D-09, MUST-CONFIRM by 2d) |
| Task nudge | AKTCL states that an assigned task sends a notification to the SR (UI-SR-43). The design supports an FCM data nudge of type `task` with the same jitter; the task itself always arrives with the next delta, so a missed push loses nothing. It needs D-09 extended and Q-UI-07 answered (default: task assigned only); not enabled in the pilot |

### 10.7 App chrome that depends on sync state (per role; F-SR-064, F-SR-077; resolves G-field-15)

| Element | Rule |
| --- | --- |
| Connectivity indicator | "অনলাইন" or offline in the header of sync-related screens, from s4.2 |
| Sync badge | Pending count on the Sync tile; "needs attention (n)" for rejected rows |
| As-of stamps | Every aggregate screen on AMO and TSO shows "as of hh:mm" |
| Device-health line on Home | Battery percent, free storage, pending rows, last sync; warns below `cfg.app.health_warn_battery_pct` (40) and below 500 MB free |
| Support code (D-396) | Shown on every blocking screen (login failure, `needs_relogin`, unbound, blocked_no_bundle, update required): `SC-eee-bbb-ccc-ddd-k` where eee is the error or state code from a table, bbb the build number modulo 1000, ccc the `config_version` modulo 1000, ddd a 3-digit CRC of the device uuid, k a check digit; digits only so an SR can read it aloud in Bangla; the desk decodes it on the Support desk page P19 of doc 19 s5.2 (F-ADM-072, D-541; the ownership that OI-17-12 left circular is closed). The authoritative format is the 3-digit CRC `ddd` of the device uuid; the text of docs 15 and 20 that says "last 4 characters of device_uuid" is wrong and is corrected there |
| Locale | Per-role default (`cfg.app.default_locale`: SR and AMO bn, TSO en, D-181); digits follow the UI language (`cfg.i18n.digit_script`), Western grouping (`cfg.i18n.grouping`), ISO dates in lists (`cfg.i18n.date_style`); stored values are ASCII (D-344) |
| Per-role chrome | SR: header "SR - <name> (<username>)", route label and ISO date, tile grid (F-SR-008, F-SR-009). AMO: header "<name> (<username>)" with the zone and date, 17 tiles, a Settings tile (F-AMO-001, F-AMO-034). TSO: dark theme, hamburger, "Good day, <name>", header logout icon, Home button, seven drawer entries from `cfg.app.drawer_items.tso` and no Settings today (F-TSO-028, F-TSO-024 adds one) |
| Home tiles | From `cfg.app.home_tiles` per user; Sales Journey and KPI stay hidden until captured (D-342) |

Proved by: T-1-35, T-2-23, T-2-31, T-2-32, T-2-33, T-2-55, T-2-56, T-2-58, T-3-41, T-3-42.

## 11 Edge-case catalogue

Each case states the behaviour the device and server must show. "Gate" names the test of s12 that exercises it. Cases E-01 to E-20 are the planning catalogue (updated by the decisions), E-21 to E-31 come from the field critic's walk, E-32 to E-39 from the SRE critic's game-out, E-40 to E-50 are added here.

| # | Case | Behaviour | Refs | Gate |
| --- | --- | --- | --- | --- |
| E-01 | Shared phone, two SRs on alternate days; A left rows pending, B logs in | Per-user DB; B logs in normally; the engine flushes A's outbox under A's stored token while B works; quiet notice on B's Home; orphan alert after 24 h | s7.3, D-66, D-395 | T-3-21 |
| E-02 | App update while rows are pending | Migration on first open; outbox payloads never transformed; the server accepts N-2 to N; the updater waits at most 60 s for a batch in flight; `min_version` blocks a new day's login only | s10.2, D-79 | T-2-23, T-2-33 |
| E-03 | Device replaced mid-day (lost or broken) | New OTP; a different slot so memo numbers use another block; full bundle; `day_states` show the route in field; the old DB, if alive, is replayed through PDA to Support; if dead the memos exist on paper only and Data Entry keys them by `memo_no`; the reconciliation screen shows the gap | s7.3, s9.5, s10.4 | T-3-22 |
| E-04 | Kill-and-relaunch mid-sale | `sale_draft` resume; memo commit is atomic; visit under 120 min resumes, else abandoned | s3.4 | T-1-24 |
| E-05 | Storage full | D-404: below 500 MB warn and evict the image cache; below 200 MB refuse photo capture while force sale stays allowed with `photo_pending_storage`; `SQLITE_FULL` rolls the transaction back, keeps the draft in memory and retries after eviction; the user never sees a stack trace; media queue capped at 50 MB | s8.6 | T-2-30 |
| E-06 | Permission revoked mid-day (including the Android auto-reset of unused apps) | Location: block with rationale and deep link; camera: force-sale photo flows cannot complete; Bluetooth: printing off | s10.3 | T-1-30 variant |
| E-07 | Wall clock changed to pass the 17:00 gate or to back-date | Gate and business date use trusted time; skew banner; rows flagged; server flags `checkout_before_allowed` | s5.5 | T-1-38, T-2-29 |
| E-08 | SIM-less, Wi-Fi-only phone | Whole day offline by design; pre-fetched bundle; `day_open(offline_start)`; Sales Submit and check-out are outbox events stamped with capture time; T4 fires on Wi-Fi in the evening; photos upload on the same Wi-Fi; the dashboard shows last-contact age per route, never "not submitted" for a route that submitted locally | s6.5, s4.11 | T-1-30 |
| E-09 | OEM background killers (MIUI, Realme, Vivo) | Sync on every foreground; a one-time guidance screen with deep links (`cfg.app.oem_guidance`); the 16:30 pending reminder; no foreground service | s4.1, s10.6, D-397 | T-1-30 variant |
| E-10 | Token expired offline, or password changed | Offline unlock via the verifier; on reconnect a 401 whose refresh fails sets `needs_relogin`; capture continues; rows wait | s7.2 | T-2-28 |
| E-11 | Outlet without coordinates, or a placeholder pin (34,454 outlets share 11,222 points, docs/22 P-09) | Opens as a force sale `no_outlet_location`; the photo and fix propose the location through the change request; after approval the delta carries lat and lng; `location_confirmed` false routes the first visit through the correction path | D-95, D-253 | T-2-10..19 (doc 21) |
| E-12 | Selling to a newly captured outlet before approval | Provisional `ref_outlet` with a client uuid; visit and memo carry `outlet_client_uuid`; the server links them on approval and re-ingests parked rows (`outlet_pending_approval`, retryable) | D-326 | T-1-21 |
| E-13 | Two devices, one user, one day | Both bound with different slots; both streams accepted; flagged `multi_device_day`; attendance is first in, last out | s7.3 | T-3-22 |
| E-14 | Server `read_only_mode` or `sync_hold_s` (migration window, incident) | 503 plus `Retry-After`, or `hold_s` in a 200; randomised per device; capture unaffected | s4.6 | T-2-56 |
| E-15 | Local DB corruption | `quick_check` at open; on failure copy the file aside, offer PDA to Support, recreate the DB, re-download the bundle; the reconciliation screen shows the gap | s2.1 | T-2-23 |
| E-16 | Duplicate taps and double submit | The uuid is created when the screen opens; Save is disabled during the transaction | s2.2 | T-1-20 |
| E-17 | Price or offer changed mid-day | Delta applied; new memos use the new list; the memo stores `price_list_date`; the home strip recomputes | s6.3 | T-2-24 |
| E-18 | Yesterday unsubmitted, today's route open | Both `day_local` rows shown; yesterday submittable until 10:00 Dhaka; captures go to today | s5.5, D-388 | T-1-27 |
| E-19 | Bundle larger than budget (an AMO with 54 routes) | Paged sections; hard cap 2 MB gz; alert | s6.4 | T-3-36 |
| E-20 | Battery below 15 percent | Background jobs held by `requiresBatteryNotLow`; mobile photo upload and pre-fetch paused; foreground sync allowed | s4.2, D-403 | T-1-36 |
| E-21 | Rain, hartal, market closed, DH out of stock, breakdown, sick | `day_exception` event offline (SR for the own route, AMO for the zone); the TSO approves in the app; an approved exception removes the route from the denominators of Login % and Submit % (of logged-in) and labels it "exception" | D-39, F-SR-059 | T-2-97 |
| E-22 | Shop shut, owner absent, refusal, stock fine | A visit outcome code or a `visit_skip`; the honest SR is not a CPR padder | D-38 | T-2-91 |
| E-23 | Cold GPS at the first outlet | One warm-up fix when the list opens; reuse of a fix at most 60 s old; else force sale `no_fix` | s3.7, G-field-13 | T-2-96 |
| E-24 | Paper runs out or a half print on a clone | The confirmation "ছাপা ঠিক আছে?"; "না" marks `failed_user`; the reprint carries no duplicate marker | s9.4 | T-2-32 |
| E-25 | Cash-and-carry buyer (20,000 sticks) | The price type is an outlet attribute from the bundle; the SR cannot choose; a large quantity is a soft-ceiling flag, never a rejection (26,683 lines of 10,000 sticks or more, docs/22 P-16) | D-32, D-42 | T-2-90 |
| E-26 | Wrong user sold on a shared phone | Identity confirmation at the first capture of the date; `acting_for_user_id`; re-attribution is a server event | s3.6 | T-2-98 |
| E-27 | Retailer disputes a due | `due_dispute` event and the staleness marker on the printed previous due | F-SR-071 | T-2-93 |
| E-28 | Retailer cancels after the print | `memo_void` with reason, fix and acknowledgement; cancel slip; stock and due effects on the server | D-86 | T-2-92 |
| E-29 | AMO sells the rest of a dead-phone SR's route | Memos under the AMO's number series with `acting_for_user_id` | F-AMO-041 | T-3-90 |
| E-30 | Same-day cover | `cover_request`; `scope_version` bump; the substitute's rows may be `rejected(scope_stale)` until the refresh and delta, then re-sent | F-AMO-037 | T-3-90 |
| E-31 | Parallel-run pilot: two memos at the shop | `parallel` rows excluded from rollups; test print with the watermark; due collections flagged and excluded from balances | D-152 | T-2-99 |
| E-32 | DR failover or PITR | New `X-Server-Generation`; devices re-send the last 24 h with jitter | s4.12 | T-1-56 |
| E-33 | One poison row | Server isolation; client bisection and skip-ahead | s4.10 | T-1-53 |
| E-34 | WAF returns an HTML 403 | Transport failure, backoff, no banner, no halt | s4.6 | T-2-57 |
| E-35 | Hourly access-token expiry wave | TTL jitter, piggybacked refresh, 60 s grace, expiry on trusted time | s7.2 | T-1-55 |
| E-36 | FCM push fan-out | `pull_after_s` jitter | s6.7 | T-2-55 |
| E-37 | A bad build loops on upload | Edge hold by version; rows stay on the phone | s10.2 | T-2-56 |
| E-38 | Custom domain, certificate or DNS failure | Second hostname after 3 failures | s4.13 | T-2-57 |
| E-39 | App rollback with pending rows | Roll forward with a rescue release; PDA to Support before any reinstall | s10.2 | T-2-23 |
| E-40 | Captive Wi-Fi portal | `HEAD /health` fails: treated as offline; no toast storm; T4 re-arms | s4.1 | T-1-32 |
| E-41 | Flapping network (30 s on, 90 s off) | Validation at most once per 10 s; at most 1 POST per 5 s; no duplicate rows | s4.2 | T-1-31 |
| E-42 | Memo number block exhausted, or unexplained gaps | Overflow range with `memo_no_overflow`; nightly gap report | s9.5 | T-2-20 |
| E-43 | Bengali digits typed into phone, quantity or amount fields | Normalised at input and again at ingest (flag when changed) so dedupe and arithmetic do not break | D-118, F-SYS-070 | T-1-39 |
| E-44 | Ramadan hours, effective-dated check-out time | The key is effective-dated per scope and applied on trusted time | D-225 | T-2-28 |
| E-45 | Forgotten check-out | Never blocks Sales Submit; the server closes it as "no check-out" at the end of the business date | D-329 | T-2-31 |
| E-46 | Retailer refuses the force-sale photo | PARITY: the photo is required; guidance belongs in the tutorial | docs/05 | none |
| E-47 | Phone offline for more than 5 days (repair, rural) | At 2 days before `cfg.sync.max_backdate_days` (7) the oldest pending row's age shows in the device-health line and a banner; at the window rows become `business_date_out_of_window` and go to quarantine for admin; PDA to Support is the recovery | s5.5 (ASSUMPTION: the 5-day warning) | T-2-27 |
| E-48 | Phone reboot mid-day | `boot_id` changes; rows flagged `time_untrusted` until the next anchor; informational only | s5.5 | T-1-38 |
| E-49 | Device time zone changed to another zone | Business date and printed time derive from trusted time in Asia/Dhaka whatever the device zone says | s5.5 | T-1-38 |
| E-50 | A same-date unsynced day on two phones of one user after a replace | Disjoint blocks; the server accepts both; the sequence-gap report sees both blocks | s9.5 | T-2-20 |

Proved by: T-1-24, T-1-30, T-1-31, T-1-32, T-1-38, T-2-20, T-2-23, T-2-25, T-2-28, T-2-29, T-2-30, T-2-32, T-2-56, T-2-57, T-3-21, T-3-22, T-3-36.

## 12 Sync test plan

### 12.1 Method

| Item | Rule |
| --- | --- |
| Gate ids | Doc 20 owns the gate register (doc 20 s3) and places each T-id in a sub-milestone. Ids minted here: T-1-36 to T-1-39 and T-3-36 |
| Data | Synthetic and PII-free from `/packages/testkit` (D-151); the Apsis sample never enters CI. Generator parameters from docs/22: 47 visits per SR-day, memos with a mean of 2.5 lines, p99 4 lines, a maximum of 60, 10 to 16 percent zero-sale visits, a few outlets visited twice a day (P-06), 10,000-stick wholesale lines (P-16) |
| Server property tests | TypeScript with `fast-check`, in CI on every PR |
| Device tests | Dart property tests for the outbox; Flutter `integration_test` on the primary device and an emulator; a debug-only chaos hook `--dart-define=CHAOS_KILL_AT=<step>` plus `adb shell am force-stop`; Maestro or Appium drives the scripted day |
| Connectivity | `adb shell cmd connectivity airplane-mode enable` and `... disable`, Wi-Fi toggles, and a throttling proxy at a 2G profile (50 kbps, 400 ms) |
| No-mock rule | Sync tests run against a real PostgreSQL ingest, not a stub |

### 12.2 Property and fuzz tests

| Gate | Property | Generator | Oracle |
| --- | --- | --- | --- |
| T-1-20 | Outbox invariant: `count(domain rows) = count(outbox rows)` and every payload hash equals the hash of the stored row, after every operation and every random kill point | A random day of 1 to 300 captures of every record type in s2.3 | Equality (Dart) |
| T-1-21 | Idempotent convergence: any partition of a day's records into batches, with duplication 0 to 50 percent, children before parents 0 to 20 percent, truncated batch tails 0 to 20 percent and `batch_uuid` replay, reaches the same server state as one clean batch; a re-sent `client_uuid` with a mutated payload yields `conflict` and no state change; a record with a regenerated uuid is caught by the fingerprint | 10,000 runs per PR, 100,000 nightly | Row-by-row equality of `app.*` and `dw.agg_*` for the touched dates |
| T-1-22 | Replay returns a byte-identical response for the same `batch_uuid` within 24 h; a different row set under the same `batch_uuid` returns 409; another device never receives the replay | | |
| T-1-23 | ACK application is a pure function: never leaves `syncing`, never flips `synced` to `pending` except by the generation rule, partition invariant of s4.5, `device = accepted + rejected + conflict` | Random ACKs including unknown `client_uuid` values | Invariants |
| T-1-25 | Family atomicity: the builder never splits a family; overshoot is at most one family | Random outbox contents including a 175-row family | |
| T-1-26 | Backoff: sequence bounded by the cap and jittered; hand-over after 5 failures; `attempt_count` unaffected by transport failures; Retry-After honoured | Simulated failure streams | |
| T-1-27 | Business date: for random trusted timestamps around midnight Dhaka and offsets up to plus or minus 48 h, device and server dates agree; the 23:55 sale synced at 00:10 re-aggregates only D | | `dw.agg_daily_route` diff |
| T-1-39 | Contract conformance: the record-type enum and reason codes in `/packages/contract` match the outbox CHECK constraint, the server registry and the Dart client; Zod and Dart round-trip golden fixtures; an unknown type or unknown key is rejected; Bengali digits are normalised | Generated | Equality |
| T-2-20 | Memo numbers: random (user, date, slot at most 3, up to 1,500 memos including overflow, random rollbacks) never collide; the regex holds; the server rejects a foreign prefix, a wrong date part and a `seq` outside the slot's ranges; a held slot is never reissued on the same date | | |
| T-2-21 | Edits, voids, dues and stock as events: random supersede chains and due collections across batches never double a memo count, a due balance or a stock balance | | Ledger equality against a reference model |

### 12.3 Kill tests

| Gate | Kill point | Expected after relaunch |
| --- | --- | --- |
| T-1-24 | Between the memo commit and the first printer byte; inside the transaction; during `sale_draft` autosave | Exactly one memo or none; draft resume offered; number not duplicated; outbox equals domain |
| T-1-28 | Mid-batch send, after the request left and before the response | Rows back to `pending` within 120 s; resend reuses `batch_uuid`; the server shows one copy |
| T-1-29 | Mid-ACK application | All rows of the batch flipped or none; the resend is `replayed: true` |
| T-2-22 | Mid-photo compression or mid-upload | No orphan file; consistent `media_queue`; the blob is absent or complete |
| T-2-23 | During the Drift migration on upgrade with 200 pending rows | Migration completes or rolls back; rows intact and uploadable; N-1 can open the DB for one release |
| T-2-24 | During a bundle delta apply | `ref_*` is old or new, never mixed |
| T-2-32 | Mid-print; printer off mid-print | Sale intact; reprint works; `print_count` right; idle disconnect after 120 s |
| T-3-20 | During Sales Submit (event appended, app killed) | `submitted_local` persists; the event uploads; the server `sales_submitted` once |
| T-3-36 | During a paged bundle download | The old bundle intact; the download resumes from the last page; the swap is atomic; total at most 2 MB gz for a 54-route zone |

### 12.4 Airplane-mode, connectivity and protocol tests

| Gate | Scenario | Pass criteria |
| --- | --- | --- |
| T-1-30 | The scripted day (s8.2) in airplane mode from 08:00 with the network back at 17:05; variants: permission revoked at 12:00, reboot at 13:00, MIUI force-stop at 14:00 | All rows synced within 3 min of connectivity (T4 fires); counts match; Login % shows the offline start; Sales Submit accepted; no row lost or doubled |
| T-1-31 | Flapping network, 30 s on and 90 s off for 2 h | No duplicate rows; at most 1 POST per 5 s; no batch above `batch_max_rows`; wake-lock total within budget |
| T-1-32 | Captive Wi-Fi portal | Treated as offline; no error storm; T4 re-arms |
| T-1-38 | Time anchor: reboot between anchor and capture; wall clock set back 24 h in the same boot; clock set forward to pass 17:00; device zone changed | Fallback with `time_untrusted` after a reboot; business date and gate unchanged under set-back and set-forward, banner shown; zone change has no effect |
| T-1-53 | Poison row: a record whose ingest throws; 10,000 rows with 1 percent throwing | 200 with `rejected(server_error)` and a quarantine row; all other rows accepted; a stuck family uploads the rest of the day within 2 cycles |
| T-1-54 | Submit settle: `day_submit` one batch before 100 rows; rows never arrive | `submit_pending_rows` and no mismatch flag; then `sales_submitted`; after 30 min flagged; the sync-health tile right throughout |
| T-1-55 | Token expiry wave and skew: 1,000 devices issued tokens in one second; a device +3 h | Refreshes spread over at least 8 min; no refresh loop; a 30 s-expired token accepted on `/sync/batch` |
| T-1-56 | Server generation re-sync: restore staging to T-1 h and change the generation; 100 devices | State equals the pre-restore state for those rows; zero duplicates; counts equal; re-sync spread over the jitter window |
| T-2-25 | 2G throttle, 200-row catch-up | Completes through halving and resume; at most 10 min |
| T-2-26 | Wi-Fi-only photos: 13 photos on mobile all day, Wi-Fi at 19:00 | Zero photo bytes on mobile except the evidence fallback after 6 h for the 5 force-sale photos |
| T-2-27 | Stale bundle: fetch D, airplane mode, device date D+1 then D+2 | D+1 sells with the banner and `day_open{online:false}`; D+2 is read-only; D+1 rows flagged `stale_price` when a price changed; pending rows older than 5 days raise the warning (E-47) |
| T-2-28 | Config change mid-day (radius 100 to 60 m) | The next response carries the version; the delta applies; the next outlet open uses 60 m; the visit stores `radius_m_used = 60` and `config_version`; `config_ack` arrives; an earlier visit keeps 100 |
| T-2-29 | Clock skew minus 3 h and plus 40 min | Corrected business date and gate; rows flagged; banner; no rejections |
| T-2-55 | Push jitter: FCM data message to 1,000 simulated devices | `GET /config/delta` spread over at least 100 s (ordinary) and at most 20 s (kill switch); no 429 at minimum replicas |
| T-2-56 | Version-scoped hold: a build looping at 1 req/s on 500 devices behind one NAT address | Stopped at the edge within 60 s; other versions unaffected; auto-expiry; rows stay on the phones |
| T-2-57 | Hostname and certificate failover; WAF HTML 403 | Devices switch to the Front Door host within 3 failures; the HTML 403 is transient (no banner, no stop) |
| T-2-58 | Support visibility | A failing device is found by an L1 persona in 2 min from its observed headers; "Send diagnostics" lands while `/sync/batch` returns 500 |
| T-3-21 | Shared phone: A captures 20 rows offline, logs out; B logs in online | A's rows upload under A's token within one cycle; B's bundle and counters correct |
| T-3-22 | Device replaced mid-day | New slot and block; `day_states` show the route in field; the old DB replays idempotently |
| T-3-23 | TSO logout with 5 pending rows | Refused with the count; allowed after PDA to Support or after sync |

### 12.5 Reconciliation

| Gate | Test | Pass criteria |
| --- | --- | --- |
| T-1-34 | After T-1-30 the screen's per-type device counts equal `server_totals`; one injected `unknown_sku` row | Exact equality; the mismatch path shows 1 rejected and Sales Submit stays allowed |
| T-2-31 | Nightly job `reconcile_device_vs_server(business_date)` compares each device's last `device_counts` claim with `ingest_registry` counts | Zero unexplained differences over a 10,000-batch fuzz day |
| T-7-20 | Pilot parallel run: new-app counts against the Apsis daily report per route | Memo count, STD and dues equal for 10 consecutive trading days (D-145) |

### 12.6 Budget and printer tests

| Gate | Test |
| --- | --- |
| T-1-33 | CI: per-ABI APK at most 30 MB; growth against the previous release warns at 5 percent and fails at 15 percent |
| T-1-36 | Battery and data baseline: the standard scripted day on the primary device with the s8.8 protocol, recorded in `/docs/perf/battery-<version>.md`; also measures engine-start cost, SQLCipher cost, tail-energy assumption and the heavy-day slope; re-run per RC |
| T-1-37 | Trigger inventory and manifest lint: zero foreground services and alarms in `dumpsys`; no `Timer.periodic` under 60 s; WorkManager jobs registered only while rows or photos are pending; `allowBackup` false; no background-location permission |
| T-1-35 | Golden print of each kind of s9.2 on an RPP02N and a clone, photographed and diffed against the captured Apsis baseline (needs the physical samples, G-sync-02) |
| T-1-41 | Memo parity against the baseline corpus (doc 20 s4) |
| T-2-30 | Photo CPU at most 1.5 s on the primary device; storage-pressure thresholds of D-404 |
| T-2-33 | Payloads of the previous two releases are accepted by the new server |
| T-2-34 | Android 7 and 8 TLS trust chain to the Front Door certificate |

### 12.7 Gate summary by phase

| Sub-milestone | Gates from this document |
| --- | --- |
| 1a | T-1-20, T-1-24, T-1-35, T-1-41 |
| 1b | T-1-21, T-1-22, T-1-23, T-1-25 to T-1-32, T-1-34, T-1-38, T-1-39, T-1-53 to T-1-56 |
| 1c | T-1-33, T-1-36, T-1-37 |
| 2a to 2e | T-2-20 to T-2-34, T-2-55 to T-2-58, T-2-90 to T-2-99 |
| 3a, 3b | T-3-20 to T-3-23, T-3-36, T-3-41, T-3-42, T-3-90 |
| 7b, 7c | T-7-20, T-7-84 |

Proved by: every gate above; this table is the source for doc 20 s3.

## 13 Gaps owned, decisions and keys minted here

### 13.1 The 35 master gaps owned by this document (gap register, doc 14 s7), each closed in this text

Alias ids merged into a master are retired; the alias is shown in brackets once.

| Master id | Aliases | Gap | Sev | Phase | Closed by | Decision | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G-data-08 | G-scale-18 | No human memo number; offline numbering undefined (Q5) | blocker | 1a | s9.5: format, slot blocks, overflow, validation, cutover options | D-35, D-393 | T-2-20 |
| G-sync-02 | G-man-014, G-feat-60 | The current printed memo layout is not captured | blocker | 1a | s9.2: template contract, kinds, content rules, samples requested; 1a cannot exit on parity without them | D-76, D-158, D-392 | T-1-35, T-1-41 |
| G-fraud-02 | G-sec-12, G-cfg-20 | Clock-correction conflict: offset-at-last-sync is defeated by changing the clock | blocker | 1b | s5.5: monotonic anchor, same-boot drift detection, reboot fallback | D-20, D-389 | T-1-27, T-1-38 |
| G-scale-01 | G-sync-05, G-sre-14 | No batch-level idempotency or response replay | blocker | 1b | s4.4, s4.5, s4.8: `batch_uuid`, stored response, 409 | D-62 | T-1-22 |
| G-sre-01 | none | DR or PITR loses rows the devices hold as synced | blocker | 1b | s4.12: server generation, window, jittered re-send | D-63, D-406 | T-1-56 |
| G-sre-02 | G-sync-09 | `day_submit` can precede the rows it closes | blocker | 1b | s4.11: last in sequence, settle, `submit_pending_rows`, timeout | D-64, D-383 | T-1-54 |
| G-sre-03 | none | One poison row stalls a device forever | blocker | 1b | s4.10: savepoints, bisection, skip-ahead | D-65, D-381 | T-1-53 |
| G-qa-05 | G-sync-12, G-man-026 | Reference devices are assumptions; no OS floor | blocker | 1c | s8.1: three device classes, lab, `minSdk 26`, census as the proof | D-11, D-12 | T-1-36 |
| G-sync-01 | G-sec-02, G-feat-41 | Device binding model for shared phones undefined | blocker | 1c | s7.3: per-user DBs, 3 users and 2 devices, flush order, slots | D-66, D-395 | T-3-21 |
| G-sync-03 | G-scale-05 | Offline new-day start (stale bundle) undefined | blocker | 2e | s6.5: 2-day stale policy, `day_open`, pre-fetch | D-70, D-30 | T-2-27 |
| G-man-006 | none | Sale commit point: Print is the commit; QC and Print independent; draft at Proceed | major | 1a | s3.1, s3.4, s9.4 | D-77 | T-1-24 |
| G-man-020 | none | Permission gating, offline re-login, version string and branding on login | major | 1a | s10.3, s7.1, s7.2, s10.1 | D-74, D-115, D-208, D-244 | T-1-37 |
| G-man-023 | G-feat-43 | Printer pairing, connection and print-button rules | major | 1a | s9.1, s9.4 | D-76, D-391 | T-1-35, T-2-32 |
| G-sre-10 | none | Edge 4xx or 5xx without the API envelope misread as terminal | major | 1b | s4.6: envelope rule, edge 413, hostname fallback | D-81 | T-2-57 |
| G-sync-21 | none | Bundle size and paging for several routes and for AMOs with up to 54 routes | major | 1c | s6.1, s6.4 | D-72, D-257, D-386 | T-3-36 |
| G-scale-20 | none | Photo linkage lifecycle undefined | major | 2c | s2.4, s4.14, s8.6: `photo_client_uuid`, `media_meta`, `linked` | D-75, D-390 | T-2-22, T-2-26 |
| G-sync-10 | none | Evidence photos on a Wi-Fi-only policy may never upload | major | 2c | s4.14: evidence fallback after 6 h, photo-pending after 24 h | D-75 | T-2-26 |
| G-feat-35 | none | No notification mechanism to apps | major | 2d | s10.6: no polling, local reminder, FCM urgent only, task nudge option | D-84, D-09, D-397 | T-2-55 |
| G-sync-11 | none | Permission revoked mid-day has no path; force-sale reasons fixed | major | 2d | s10.3: block with rationale; the planning draft's `permission_denied` reason is retired | D-74 | T-1-30 variant |
| G-scale-16 | none | APK and thumbnail distribution on wave day would saturate the API | major | 2e | s10.2: Blob behind Front Door, pre-bind day on Wi-Fi | D-10, D-126 | T-7-57 |
| G-sre-11 | none | A non-compliant build's upload loop cannot be stopped | major | 2e | s10.2, s4.6: version-scoped hold, rows stay on the phone | D-130 | T-2-56 |
| G-sre-20 | none | App rollback with pending rows is data-loss-prone | major | 2e | s10.2: roll-forward, additive schema, engine switch, PDA first | D-79 | T-2-23 |
| G-sync-06 | G-feat-65 | Sales Submit and check-out specified as online calls | major | 2e | s4.11, s3.1: outbox events | D-64, D-383 | T-1-30, T-3-20 |
| G-sync-15 | G-man-022 | Updater must not install mid-batch; per-ABI; rollback | major | 2e | s10.2 | D-79, D-208, D-223, D-224 | T-2-23 |
| G-sync-16 | G-man-024 | Wipe with pending rows loses data | major | 2e | s7.4 | D-69 | T-3-23 |
| G-man-059 | G-field-18 | Map and geocoding provider undecided | major | 3a | s10.5 | D-08 | T-3-42 |
| G-man-065 | none | AMO bundle scope: any route of the zone, offline | major | 3a | s6.1, s6.4 | D-72, D-386 | T-3-36, T-3-41 |
| G-man-069 | none | TSO has no offline model | major | 3b | s6.6 | D-82, D-387 | T-3-42 |
| G-field-20 | none | Half-printed memo followed by a "duplicate" reprint | minor | 1a | s9.4, s9.6 | D-322, D-394 | T-2-32 |
| G-sync-14 | none | Debounce default 10 s versus 5 s | minor | 1b | s4.1: 5 s with the family hold | D-261, D-59 | T-1-31 |
| G-sync-20 | none | No cap on local photo queue or image cache on 16 GB phones | minor | 2c | s2.7, s8.6: 50 MB queue, 40 MB cache, D-404 thresholds | D-404 | T-2-30 |
| G-field-13 | none | Cold GPS at the first outlet becomes a force sale | minor | 2d | s3.7: warm-up, reuse, `no_fix` | D-74 | T-2-96 |
| G-field-15 | none | Pre-login SR cannot be diagnosed; no device-health line | minor | 2e | s10.7: support code, health line | D-396 | T-2-58 |
| G-man-025 | none | PDA to Support: confirmation, progress, offline queue, naming | minor | 2e | s10.4 | D-402 | T-3-23 |
| G-sync-17 | none | Purge rule conflicts with Sale History and reprints | minor | 2e | s2.7: purge by date, 7-day window, online fallback | D-83 | T-2-31 |

Touching gaps owned elsewhere, resolved here in part: G-fraud-03 [G-sync-13] (doc 21): the on-device DB is SQLCipher per user (s2.1, D-67); G-field-03 [G-sync-08] (doc 15): outcomes, skip and abandonment on the device (s3.2, D-38); G-fraud-01 [G-sync-18] (doc 21): passive radio environment captured with the fix (s3.7, D-110); G-sec-17 [G-sync-19] (doc 21): redemption over balance is arbitrated by the server (s4.9 `insufficient_points`, D-266); G-sync-04 (doc 16): rounding rule printed on the memo (s9.2, D-19); G-sync-07 (doc 16): the offline day start is a `day_open` event (s6.5, D-30).

### 13.2 Decisions minted (D-380 to D-406; the doc 14 author copies them into DECISIONS.md)

| ID | Decision | Status | Why and source | Docs |
| --- | --- | --- | --- | --- |
| D-380 | Outbox contract: the engine reads only the outbox; a capture is one transaction writing the domain row and the outbox row; family state (hold, valve, fail count) lives in `outbox_family`; synced rows are kept `cfg.app.outbox_keep_days` (3) with `keep x 24 >= resync_window_h + 24`; rejected rows 30 days while unseen | DEFAULT | P1, P2; coupling to D-63 | 16, 17, 20 |
| D-381 | Retry: delay `min(cap, 2 s x 2^k) x U(0.5, 1)`, cap 300 s; 5 in-process failures then T4 and T5; `attempt_count` counts only retryable rejections; `row_max_retries` 10; manual and foreground triggers bypass; `Retry-After` honoured with plus or minus 20 percent jitter up to 15 min | DEFAULT | R4, R5 (D-59) | 17, 18 |
| D-382 | Batch builder: at most 200 rows and 256 KB raw; rows of one transaction share a family and are never split; priority classes 1 (check-in, `day_open`, `config_ack`), 2 (families by `seq`), 3 (logs); `day_submit` never ahead of lower `seq`; `batch_uuid` reuse for an identical set; halve on API 413 (floor 25, recover 25 percent per success); the server cap of 500 rows exceeds 200 plus the largest family of about 175 | DEFAULT | D-60, D-116, D-246 | 17, 18, 20 |
| D-383 | Sales Submit on the device: enabled online after every row is acknowledged (PARITY), enabled offline with a "N records not yet sent" confirm when `cfg.day.sales_submit_offline_queue` is true (default); dues only warn; `rejected_count` and `submitted_with_dues` recorded; an unprinted stock slip warns (or blocks with a supervisor override when `cfg.stock.require_printed_slip` is true) | DEFAULT; MUST-CONFIRM (by 2e): Q-UI-08 | D-64 versus D-173 and UI-SR-37 (G-17-02) | 15, 17 |
| D-384 | Reconciliation: every batch carries `device_counts` per type and business date; the response returns `server_totals`; equality `device = accepted + rejected + conflict`; money per category in mtk is checked beside counts; the legacy rows are shown from `cfg.sync.reconcile_types`; the Server column is the last `server_totals`, blank with a timestamp before the first sync | DEFAULT | UI-SR-36; D-222 | 15, 17, 20 |
| D-385 | Delta protocol: 304, 200, 410 (older than 72 h or lineage broken), 409 `new_business_date`; atomic apply; foreground refresh at most every 30 min, version-triggered every 5 min; at most 15 deltas and 100 KB a day; `valid_for_business_date` asserted; the first bundle request of the Dhaka date counts as the login, a 304 included | DEFAULT; the 304 reading is an ASSUMPTION for 1c | D-30, D-126 (G-17-12) | 16, 17, 18 |
| D-386 | Paged bundle: sections above 2,000 rows paged at about 1,000 rows; staged and swapped in one transaction; 2 MB gz hard cap | DEFAULT | G-sync-21, G-man-065 | 17, 18 |
| D-387 | AMO and TSO aggregate screens refresh only on explicit open or pull-to-refresh and show "as of hh:mm"; no timer | DEFAULT | D-84, G-man-069 | 15, 17 |
| D-388 | A new business date starts at 00:00 trusted Dhaka time; the family date is fixed at visit open; yesterday's Sales Submit is allowed until `cfg.day.submit_grace_h` (10 h after midnight), then only by audited admin reopen | DEFAULT; ASSUMPTION; MUST-CONFIRM (by 3b) with Q11 and D-55 | E-18 | 16, 17 |
| D-389 | Trusted time: anchor `{S, E, B, W}` after every 2xx or 304 with a round trip of at most 10 s; trusted now = `S + (E_now - E)` in the same boot, else wall time plus the last offset flagged `time_untrusted`; same-boot drift above 10 min flags `clock_skew` and shows the banner; out-of-window rows accepted with `clock_corrected` only when the offset is present and the corrected date is in the window | DEFAULT | D-20 method; G-fraud-02 | 17, 21 |
| D-390 | Photo lifecycle: the record syncs first and carries `photo_client_uuid` and `blob_path` from capture; `media_meta` links the blob; the local file is deleted after `linked` plus 2 days; 20 failed attempts abandon and surface the photo; SAS requests batched up to 10; 2 concurrent uploads on Wi-Fi and 1 on mobile; a photo never blocks a sale or Sales Submit | DEFAULT | D-75 | 17, 18, 21 |
| D-391 | Printer: `PrinterPort`; Bluetooth Classic SPP with a bonded-device picker (no `BLUETOOTH_SCAN`); connect at most 8 s, two reconnects 2 s apart; idle disconnect 120 s except on Stock; 512-byte chunks at 20 ms pacing; `print_job` states and record | DEFAULT | D-76 | 17, 20 |
| D-392 | Template contract: 7 memo kinds plus day summary, due receipt, cancel slip and parallel-run test print; template JSON in the bundle with an embedded fallback of each kind; a golden print test per kind; Phase 1a cannot exit on memo parity without physical samples | DEFAULT; MUST-CONFIRM (by 1a): samples | D-76, D-158 | 15, 17, 20 |
| D-393 | Memo number slots: the bind ordinal is a stable device slot (lowest slot neither active nor revoked on the same date; a revoked slot is held until the next business date); blocks 001 to 500, 501 to 999, 1001 to 1500, 1501 to 2000; overflow 3000 + 1000 k; at most 4 slots; the server validates prefix, date and range; usernames contain no "-" | DEFAULT; MUST-CONFIRM (by 1a) with D-35 | D-35 states `seq3`; G-17-01 | 16, 17 |
| D-394 | Print mechanics: `print_job` is a sync record; "পুনর্মুদ্রণ #n" marker unless the previous print was `failed_user`; "ছাপা ঠিক আছে?" after each print; applies D-322 | DEFAULT; MUST-CONFIRM (by 1a): marker text | D-322, D-76 | 15, 17 |
| D-395 | Multi-user engine: one batch in flight device-wide; active user first then oldest pending; one batch per user per rotation; each batch under that user's token; after an SR or AMO logout the refresh token stays in upload-only mode and is revoked after the last ACK | DEFAULT; MUST-CONFIRM (by 1c) with D-66 | D-69; G-17-09 | 17, 21 |
| D-396 | Support code `SC-eee-bbb-ccc-ddd-k` (digits only) on every blocking screen and a device-health line on Home | DEFAULT | G-field-15; F-SR-064, F-SR-077 | 17, 19, 20 |
| D-397 | A local reminder at `cfg.sync.pending_reminder_time` (16:30 Dhaka) when rows are pending; an OEM guidance screen; the app never requests a battery-optimisation exemption | DEFAULT | E-09 | 17 |
| D-398 | Heavy-day budgets: gate(n) = D-73 figure x (0.2 + 0.8 n / 50) for n outlets (median 64, p99 112); enforced once T-1-36 has a measured baseline; AMO 2 MB and TSO 1.5 MB mobile data a day without photos | DEFAULT; ASSUMPTION (20 percent fixed cost) | docs/22 P-13; G-17-03 | 17, 20 |
| D-399 | A perf record `/docs/perf/battery-<version>.md` for every release candidate; a regression above 20 percent blocks; APK growth of 5 percent warns and 15 percent fails | DEFAULT | D-73 | 17, 20 |
| D-400 | Connectivity is validated by `HEAD /health` (3 s) at most once per 10 s; the online indicator needs a server contact within 120 s | DEFAULT | UI-SR-38 | 17 |
| D-401 | Record types and reason codes are additive; every payload carries `schema_version`; the server accepts N, N-1, N-2; unknown keys are rejected | DEFAULT | D-150 | 16, 17, 20 |
| D-402 | A support bundle is replayed through the same ingest under the user's identity by an admin tool, idempotently by `client_uuid` | DEFAULT | E-03 | 17, 19, 21 |
| D-403 | Below 15 percent battery (not charging) or in Battery Saver: no background mobile photo upload and no pre-fetch; foreground record sync continues | DEFAULT | E-20 | 17 |
| D-404 | Storage: below 500 MB free the image cache is evicted; below 200 MB photo capture is refused and force sale stays allowed with `photo_pending_storage`; `SQLITE_FULL` rolls back and retries after eviction | DEFAULT; ASSUMPTION; MUST-CONFIRM (by 2c): block or allow force sale without a photo | E-05 | 17 |
| D-405 | A visit is two wire records: `visit` (immutable open facts, rank 0) and `visit_close` (outcome, `call_started_at`, `ended_at`, rank 1); the family valve releases an idle visit alone after 180 s without family activity | DEFAULT | D-38, D-59, D-78; G-17-06 | 16, 17 |
| D-406 | Re-sync herd control: re-send starts after `U(0, cfg.sync.resync_jitter_s)` (900 s); (the second half, raising the window to the PITR distance before minting, is SUPERSEDED by D-517: the restore point travels with the generation) | DEFAULT | D-63 | 17, 18 |

### 13.3 Gaps minted here (block G-17-01 to G-17-60)

| ID | Sev | Phase | Gap | Where closed |
| --- | --- | --- | --- | --- |
| G-17-01 | major | 1a | D-35's 3-digit `seq` allows only two disjoint 500-blocks, and "ordinal = bindings that date" lets two devices of one user share a block across days | s9.5, D-393 |
| G-17-02 | major | 2e | D-64 (submit completes offline) contradicts D-173 and UI-SR-37 (enabled after sync) | s4.11, D-383 |
| G-17-03 | major | 1c | D-73's GPS and drain numbers come from a 50-outlet script; the median planned route has 64 outlets and p99 112; AMO and TSO data gates are missing | s8.2 to s8.4, D-398 |
| G-17-04 | minor | 1c | Plan s3.1 cites T-1-35 for both golden print (1a) and the budget baseline (1c) | T-1-36 (OI-17-02) |
| G-17-05 | minor | 1b | The planning draft assumed a family of at most 40 rows; a 60-line memo with QC lines is about 175 rows | s4.3, D-382 |
| G-17-06 | major | 1a | The planning draft's visit row is immutable yet carries an outcome known only at close, and its 180 s valve contradicts the 120-minute abandonment | s3.3, D-405 |
| G-17-07 | minor | 1c | The planning draft says an empty periodic run costs under 50 ms; the `workmanager` plugin starts a Dart engine for each run | s4.1 decision rule, T-1-36 |
| G-17-08 | minor | 1b | Server-generation re-send of about 3 M rows after a failover is an unthrottled herd | s4.12, D-406 |
| G-17-09 | major | 2e | F-API-031 revokes the refresh token at logout, but SR and AMO logout must keep uploading | s7.4, D-395 (OI-17-09) |
| G-17-10 | minor | 2e | PDA to Support cannot send the raw DB because it is encrypted under a device-bound key | s10.4, D-402 (OI-17-11) |
| G-17-11 | minor | 2e | A phone offline beyond `cfg.sync.max_backdate_days` loses its rows to quarantine without warning | s11 E-47 |
| G-17-12 | minor | 1c | D-30 says the login event is a full or delta bundle download; after a pre-bind day the morning request is a 304 | s5.2, D-385 (OI-17-13) |
| G-17-13 | minor | 1a | A username containing "-" breaks the `memo_no` split | s9.5 (OI-17-10) |
| G-17-14 | minor | 2e | The reconciliation legacy rows (Sale, Stock) add pieces and dozens together; their measure is unknown | s4.15, D-384 |
| G-17-15 | minor | 2e | No stated rule for selling after Sales Submit on the same route-day | s4.11 (ASSUMPTION), OI-17-14 |

### 13.3c Round-3 gaps closed in this document (skeptic review, D-554 to D-601)

| Gap | Decision | Where | Gate |
| --- | --- | --- | --- |
| G-qa-89 | D-557 | s6.5 (pre-fetch), s7.1 (pre-bind readiness tick, day one is a delta) | T-7-161 |
| G-qa-94 | D-562 | s10.2 (distribution channel risk) | T-2-164, T-7-166 |
| G-qa-96, G-qa-106 | D-563 | s4.1 (T2 resume check), s6.7 | T-2-165, T-2-174 |
| G-qa-107 | D-571 | s6.7 (stamping) | T-2-162 |
| G-qa-108 | D-572 | s4.12 (late rows after a restore) | T-1-156 |
| G-qa-115 | D-579 | s4.9 item 4 (`day_closed` removed) | T-3-159 |
| G-qa-116 | D-580 | s3.1 (stock load) | T-2-161 |
| G-qa-123 | D-584 | s6.5 (working-day windows, pre-fetch of the next working day) | T-2-163 |
| G-qa-124 | D-585 | s7.3 (policy unbind) | T-3-157 |
| G-qa-133 | D-594 | s4.4 (directive) | T-2-169 |

### 13.3b Round-2 gaps closed in this document (skeptic review, D-500 to D-553)

| Gap | Decision | Where | Gate |
| --- | --- | --- | --- |
| G-qa-29 | D-505 | s4.1 (T7 jitter) | T-4-155, T-1-54 |
| G-qa-30 | D-506 | s8.2b, s8.4, s8.5, s8.8 | T-3-151, T-3-152, T-3-153 |
| G-qa-31 | D-507 | s4.4 (telemetry.day), s8.10 | T-2-153, T-7-155, T-7-156 |
| G-qa-32 | D-508 | s8.2, s8.3, s8.4, s8.5 | T-1-36, T-2-40 |
| G-qa-33 | D-509 | s4.2, s4.4, s8.10 | T-2-154, T-7-157 |
| G-qa-34 | D-510 | s4.14 item 6 | T-2-40 |
| G-qa-41 | D-517 | s4.12, s4.4 (headers) | T-1-56, T-1-152, T-7-54 |
| G-qa-45 | D-521 | s4.5, s4.10 | T-1-53, T-1-151 |
| G-qa-68, G-qa-72, G-qa-74 | D-538, D-541, D-543 | s10.5, s10.7, s10.4 | T-4-40, T-6-42, T-2-151 |
| G-qa-70 | D-539 | s4.11, s5.2 | T-3-150 |
| G-qa-48 (OEM) | D-511 | s4.1b | T-2-140 |
| G-qa-83 | D-551 | s4.6 | T-3-154 |

### 13.4 Config keys introduced or constrained here (registry mechanics and names are doc 19's)

| Key | Default | Bounds | Note |
| --- | --- | --- | --- |
| `cfg.sync.debounce_s` | 5 | 2 to 60 | D-261 retires the 10 |
| `cfg.sync.family_hold_max_s` | 180 | 60 to 900 | the valve (s3.3) |
| `cfg.sync.batch_max_rows`, `cfg.sync.batch_max_kb_raw` | 200, 256 | 50 to 500, 64 to 1,024 | |
| `cfg.sync.retry_backoff_s`, `cfg.sync.retry_cap_s`, `cfg.sync.retry_max_inprocess` | 2, 300, 5 | cap 60 to 900, attempts 3 to 10 | OI-17-06 Resolved at the editorial merge: `cfg.sync.retry_backoff_s`, `retry_cap_s`, `retry_max_inprocess`, `resync_jitter_s` and `engine_mode` are registered in doc 19 s3.2.4 (no unregistered literal remains). |
| `cfg.sync.row_max_retries`, `cfg.sync.family_skip_after` | 10, 5 | 3 to 50, 2 to 20 | |
| `cfg.sync.periodic_min` | 15 | 15 to 120 | platform minimum |
| `cfg.sync.login_jitter_s` | 120 | 0 to 600 | automatic refresh only |
| `cfg.sync.max_clock_skew_min` | 10 | 2 to 60 | |
| `cfg.sync.checkout_upload_jitter_max_s` | 90 | 0 to 120 | D-505: random upload delay for check-out and Sales Submit when the clock gate is the only reason |
| `cfg.sync.max_savepoints_per_tx` | 60 | 8 to 60 | D-521: ingest bisection cap |
| `cfg.sync.resync_safety_margin_h`, `cfg.sync.digest_days` | 6, 3 | 1 to 24, 1 to 7 | D-517 |
| `cfg.sla.pending_rows_alert_h` | 4 | 1 to 24 | D-511: the held-rows list |
| `cfg.release.auto_freeze_regression_pct` | 20 | 5 to 50 | D-507: automatic wave halt |
| `cfg.telemetry.bat17_floor_pct` | 35 | 20 to 60 | D-507 |
| `cfg.ops.prefetch_enabled`, `cfg.telemetry.enabled`, `cfg.geo.radio_env_enabled` | true, true, true | boolean | D-507 remote brakes (C1 operational switches) |
| `cfg.day.sales_submit_locks_capture`, `cfg.day.submit_undo_window_min` | true, 120 | boolean, 15 to 720 | D-539 |
| `cfg.sync.max_backdate_days`, `cfg.sync.orphan_pending_alert_h` | 7, 24 | 1 to 30, 6 to 72 | server-only |
| `cfg.sync.pending_reminder_time` | 16:30 | 14:00 to 20:00 | |
| `cfg.sync.reason_texts`, `cfg.sync.reconcile_types` | maps | | ship in the bundle |
| `cfg.sync.resync_window_h` | 24 | 1 to 72 | D-63 |
| `cfg.sync.resync_jitter_s` (proposed) | 900 | 0 to 3,600 | D-406 |
| `cfg.sync.engine_mode` (proposed) | current | current, previous | s10.2 |
| `cfg.day.submit_settle_timeout_min` | 30 | 5 to 240 | server |
| `cfg.day.submit_grace_h` | 10 | 0 to 24 | D-388 |
| `cfg.day.sales_submit_offline_queue` | true | | D-383 |
| `cfg.day.checkout_earliest_time` | 17:00 | effective-dated | |
| `cfg.bundle.stale_max_days`, `cfg.bundle.delta_max_age_h`, `cfg.bundle.delta_min_interval_min` | 2, 72, 30 | 1 to 3, 24 to 168 | |
| `cfg.bundle.max_gz_kb`, `cfg.bundle.regen_max_per_s` | 2,048, 20 | | |
| `cfg.media.photo_max_kb`, `long_edge_px`, `jpeg_quality` | 150, 1,024, 70 | 60 to 300, 640 to 1,600, 40 to 90 | |
| `cfg.media.wifi_only_default`, `evidence_mobile_fallback_h`, `local_queue_max_mb`, `local_keep_days` | true, 6, 50, 2 | fallback 1 to 48 (0 never) | |
| `cfg.print.disconnect_idle_s`, `status_query`, `confirm_after_print`, `template_version`, `pairing_pins`, `models` | 120, false, true, 1, [0000, 1234], [RPP02N] | | |
| `cfg.memo.seq_block_size` | 500 | 100 to 999; future-dated, new binds only | |
| `cfg.memo.reprint_max`, `cfg.memo.reprint_watermark` | 5, marker | | D-322 |
| `cfg.visit.abandon_min`, `cfg.kpi.count_abandoned_visits` | 120, false | 15 to 480 | |
| `cfg.app.outbox_keep_days`, `local_history_days`, `rejected_keep_days`, `image_cache_mb` | 3, 7, 30, 40 | | |
| `cfg.app.health_warn_battery_pct`, `hold_to_confirm_ms`, `oem_guidance` | 40, 1,000, list | | |
| `cfg.app.logout_block_when_pending`, `cfg.app.logout_wipes_data` | true, {tso: true} | | D-69 |
| `cfg.auth.max_users_per_device`, `max_devices_per_user`, `offline_unlock_max_days` | 3, 2, 7 | 1 to 14 for the window | |
| `cfg.auth.otp_length`, `otp_ttl_min`, `reverify_on_new_version`, `min_refresh_interval_s` | 4, 120, false, 300 | | |
| `cfg.net.fallback_after_failures` | 3 | 1 to 10 | |
| `cfg.geo.max_accuracy_m`, `fix_timeout_s`, `refresh_max`, `fix_reuse_max_age_s`, `require_precise` | 100, 15, 3, 60, true | accuracy 30 to 300 | D-264 |
| `cfg.release.min_version`, `update_prompt_policy`, `update_wifi_only`, `finish_offline_day_before_force` | semver, prompt, true, true | | |
| `cfg.support.max_upload_mb`, `cfg.support.pda_upload_wifi_only` | set by doc 19, true | | |
| `cfg.map.provider`, `cfg.map.tile_cache_mb` | google, 20 | | D-08 |
| `cfg.stock.require_printed_slip` | false (warns) | | |
| `cfg.telemetry.device_max_bytes_per_day` | 1,024 | | D-136 |
| `cfg.ops.push_enabled`, `cfg.ops.sync_hold_by_version` | false, empty | | D-09, D-130 |

Proved by: T-1-36, T-1-37, T-1-39, T-2-20, T-3-36.

## Open items

| ID | Item | Why open | Owner role | Needed by | Proceeds on the default meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-17-01 | Physical 58 mm samples: cash memo, credit memo with partial payment, zero sale, edited, reprint, stock slip, day summary (Q57, MQ-57; Q-UI-09); duplicate-marker text (D-322); printed digits (D-344, MQ-59); whether the two on-screen layouts are two templates (MQ-58) | No manual shows a printout; "same memo" cannot be tested | Sales ops with the sponsor's delegate | 1a (D-76, D-158, D-392, D-394) | A provisional template (version 0) from the on-screen memo; T-1-41 stays red |
| OI-17-02 | Plan s3.1 cites T-1-35 for 1a (print) and 1c (budget baseline) | Id collision | doc 20 | 0a | This document uses T-1-35 for print and T-1-36 for the baseline Resolved at the editorial merge: doc 20 places T-1-35 (print) and T-1-36 (baseline); doc 14 s2.1 now lists doc 20 placements. |
| OI-17-03 | Memo number: continuity across cutover and printed width for slots 2 and 3 (Q5, MQ-56; D-35, D-393) | Retailer-facing | Sales ops | 1a, before the first golden print | Option A; 3-digit `seq` for slots 0 and 1, 4 digits otherwise |
| OI-17-04 | Doc 16 maps `visit` and `visit_close` and the `outbox`-driven `server_totals` shape (D-405, D-384) | Table design is doc 16's | doc 16 | 1a | The wire types of s2.3 are fixed; storage may be columns or an event table |
| OI-17-05 | Enum doc 16 uses for `submit_pending_rows` (sub-state or column) | doc 16 | doc 16 | 1b | A flag on `route_day` |
| OI-17-06 | Final names of the retry keys (`retry_backoff_s`, `retry_cap_s`, `retry_max_inprocess`) and the new keys `cfg.sync.resync_jitter_s`, `cfg.sync.engine_mode`; doc 19 s3 retires only the unsuffixed retry key | doc 19 owns the registry | doc 19 | 1b | The names of s13.4 |
| OI-17-07 | Runbook step: raise `cfg.sync.resync_window_h` to the PITR distance before minting a generation (D-406) | doc 18 owns runbooks | doc 18 | 7c | The window stays 24 h |
| OI-17-08 | F-API-057 must accept up to 10 items per call | doc 15 owns the endpoint | doc 15 | 2c | One item per call (more round trips) Resolved at the editorial merge: F-API-057 in doc 15 accepts up to 10 items per call. |
| OI-17-09 | Upload-only refresh token after SR and AMO logout, revoked after the last ACK (F-API-031 revokes today) | Token semantics are doc 21's | doc 21 | 2e | The token is kept until the outbox is empty Resolved at the editorial merge: F-API-031 in doc 15 revokes the full grant at once and the upload-only grant after the last ACK (D-471, doc 21 s2.7). |
| OI-17-10 | Usernames must not contain "-" | doc 16 and doc 21 own user creation | doc 16, doc 21 | 1a | The regex assumes it Resolved at the editorial merge: D-488 and the CHECK of doc 16 s3.4. |
| OI-17-11 | Support public key and custody for the PDA to Support bundle | doc 21 owns keys | doc 21 | 2e | Bundle encrypted to a Key Vault public key |
| OI-17-12 | Support-code decoder page | doc 19 or doc 20 | doc 19 | 2e | A manual lookup table |
| OI-17-13 | A 304 on `GET /sync/bundle` counts as the login (after a pre-bind day) | D-30 is worded for full or delta | doc 16 | 1c | 304 counts Resolved at the editorial merge: D-30 counts a full or delta download; a 304 on the first request of the Dhaka day is a delta with no changes and counts. |
| OI-17-14 | Whether Apsis allows selling after Sales Submit on the same route-day | unknown; confirm with the business | Sales ops | 2e | Local lock after Sales Submit |
| OI-17-15 | Needs F-id (doc 15): pre-bind readiness screen (s7.1), local pending reminder and OEM guidance screen (s10.6), support bundle replay tool (s10.4) | Not in the F-id universe | doc 15 | 2e | Built as part of F-SYS-020, F-SYS-021, F-SR-064 Resolved at the editorial merge: the three screens remain parts of F-SYS-020, F-SYS-021 and F-SR-064; the endpoints are F-API-066 to F-API-068. |
| OI-17-16 | Which Sales Submit legacy-row measure the current app shows (G-17-14, V-day G-man-031) | unknown; confirm with the business | Sales ops | 2e | Defaults in s4.15 |
| OI-17-17 | Disagreement noted, DECISIONS.md followed: the planning draft's `permission_denied` force-sale reason is retired for D-74's block; the planning draft's header `X-Device-UUID` becomes `X-Device-Id` (doc 15 s8) | The planning draft predates the plan | doc 14 | none | Plan wins |
| MUST-CONFIRM D-08 | Maps provider, quota, cost | | Sponsor | 3a | Google Maps SDK parity |
| MUST-CONFIRM D-09 | FCM for urgent config; task nudge (Q22, Q-UI-07) | | Sponsor, security | 2d | Off in the pilot |
| MUST-CONFIRM D-10 | Distribution channel: own signed APK or Play (MQ-50) | | Sponsor, IT | 0c | Own signed APK |
| MUST-CONFIRM D-11 and D-12 | Android floor and reference devices (Q31, MQ-53) | | IT, sales ops | 0c | `minSdk 26`; three device classes |
| MUST-CONFIRM D-16 and D-17 | Lighter box size and match entry unit; loose sticks (MQ-02) | | Sales ops | 2a | Sticks for cigarette and bidi; unit label on every quantity |
| MUST-CONFIRM D-18 and D-19 | Negative-net rule, rounding to the paisa | | Sales ops, finance | 2a | Allow negative net; half-up on the unrounded sum |
| MUST-CONFIRM D-20 | 00:00 business-day cutoff (Q29) | | Sales ops | 1a | 00:00 Dhaka |
| MUST-CONFIRM D-35 | Memo numbering across cutover (Q5) | | Sales ops | 1a | Option A |
| MUST-CONFIRM D-38 | Visit outcome codes: parity or addition (Q50) | | Sales ops | 2a | IMPROVEMENT |
| MUST-CONFIRM D-55 and D-388 | Reopen rules after final submit and the grace for yesterday (Q11) | | Sales ops | 3b | 10 h grace |
| MUST-CONFIRM D-63 | Counts rising after a failover (Q54) | | Sponsor | 7c | Accept |
| MUST-CONFIRM D-66 and D-395 | Shared-phone binding model | | Sales ops, security | 1c | 3 users, 2 devices |
| MUST-CONFIRM D-67 | SQLCipher sign-off | | Security owner | 1b | SQLCipher with the size fallback |
| MUST-CONFIRM D-70 | Selling on yesterday's prices (up to 2 days) | | Sales ops, finance | 2e | Allowed with banner |
| MUST-CONFIRM D-76, D-158, D-392 | Printer samples | | Sales ops | 1a | OI-17-01 |
| MUST-CONFIRM D-80 | OTP re-verify after an update (MQ-15) | | Security, TSO ops | 2e | Off |
| MUST-CONFIRM D-83 | Local history window and online fallback | | Sales ops | 2b | 7 days |
| MUST-CONFIRM D-85 and D-86 | Who assigns cover; current void behaviour (Q44, Q43) | | Sales ops | 3a, 2b | AMO may assign; void as an event |
| MUST-CONFIRM D-103 | OTP form, TTL, whether the AMO binds (MQ-15) | | Security | 0c | 4 digits, 120 min |
| MUST-CONFIRM D-110 | Radio-environment privacy sign-off | | HR, legal | 2d | Passive capture on |
| MUST-CONFIRM D-115 | Microphone (Q15, MQ-55) | | Sales ops | 2e | Camera only |
| MUST-CONFIRM D-245 | Whether all sales in the export come through the apps | | Sales ops | 1c | Plan for 4.5 lakh calls |
| MUST-CONFIRM D-262 | Final submit delegation and auto-close (Q45, Q53) | | Sales ops | 3b | Online-only, no auto-close |
| MUST-CONFIRM D-322, D-344, D-346 | Reprint marker, printed digits, money labels | | Sales ops | 1a | OI-17-01 |
| MUST-CONFIRM D-404 | Force sale without a photo when storage is low | | Sales ops | 2c | Allowed with a flag |
| OI-17-18 | MUST-CONFIRM D-510: the sponsor signs the R5(f) photo exception (Wi-Fi first, 6-hour mobile fallback, 24-hour SLO) | trade between R4 and R5 | Sponsor | 2c | the numbers of s4.14 |
| OI-17-19 | Numeric AMO and TSO gates of s8.2b are ASSUMPTION until the 3a and 3b baselines (D-506) | no AMO or TSO device measurement exists | QA lead | 3a, 3b | the s8.2b values |
| OI-17-20 | Coefficients of `app_energy_estimate_mah` are ASSUMPTION until fitted on the lab runs (D-507) | needs Battery Historian runs per device class | QA lead | 2e | the estimate is shown only as a trend |
| OI-17-21 | Whether Apsis lets a rep sell after Sales Submit is still unknown (OI-17-14); the void of D-539 is the safety valve whichever way it falls | unknown; confirm with the business | Sales ops | 2e | local lock plus void |
| OI-17-22 | Google developer verification: current scope, countries and dates, and whether an enterprise or limited-distribution path covers AKTCL's three package ids (D-562) | Platform policy moves; confirm against Google's current documentation | engineering, IT | 0c | the options of s10.2 are costed, none is chosen |
| OI-17-23 | Whether the resume check (`GET /config/check`) should also fetch the bundle delta when the version differs (D-563); today it fetches config only | Battery and data trade-off, measured in T-2-40 | engineering | 2d | config only |

## Traceability

| Requirement | Mechanism in this document | Sections | Gates |
| --- | --- | --- | --- |
| R1 DATA | Every captured fact is an immutable outbox record with provenance (`@LOCAL` block, trusted time, `route_id`, `config_version`); reasons for non-selling are events | s2.2, s2.3, s3.2, s5.5 | T-1-20, T-1-21, T-1-39 |
| R2 FEATURES | Screen, label and printed-memo parity: commit flow, print dialogs, reconciliation table, logout, update flow, PDA to Support, permissions | s3.1, s4.15, s7.4, s9, s10 | T-1-24, T-1-35, T-1-41 |
| R3 SCALE | One POST per visit; token, push and re-sync jitter; hostname fallback; edge hold by version; paged bundles; Blob for APKs; pre-bind day | s3.3, s4.12, s4.13, s6.4, s7.1, s10.2 | T-1-55, T-1-56, T-2-55 to T-2-57, T-3-36 |
| R4 BATTERY | No polling, no foreground service, no alarms; one fix per event; budgets with a measurement protocol and heavy-day gates | s4.1, s3.7, s8 | T-1-33, T-1-36, T-1-37 |
| R5 OFFLINE and IMMEDIATE SYNC | Outbox, T1 to T8, validated connectivity, WorkManager on connectivity, offline Sales Submit | s2, s4.1, s4.11 | T-1-30, T-1-31, T-1-32 |
| R6 ADMIN CONFIG | Config pull, ack, scheduled values and reach on the device; constraints enforced on the device | s6.7 | T-2-28 |
| Process requirement | Every section ends with its gate line; phase placement in s12.7 | s12 | all |

Constraint check (CLAUDE.md): 1 offline-first (s1 P5, s3, s4.11); 2 idempotent sync (s4.4 to s4.10); 3 battery (s8); 4 server-side scope (P12, s4.4); 5 geo on device with anti-spoofing (s3.7, s5.5); 6 no-hiccup cutover (s7.1, s9.5, s10.2); 7 Dhaka business date (s5.5); 8 bilingual (s9.2, s10.7).

Ids this document uses, by the section that handles them (an id cited only in s13 or the open items is in s13.1 to s13.4). Ids minted here: D-380 to D-406, G-17-01 to G-17-15, T-1-36 to T-1-39 and T-3-36. All other ids are in DECISIONS.md, doc 14 or doc 15; none is invented here.

**s1 Principles**

| Kind | Ids |
| --- | --- |
| Features | none |
| Gaps | none |
| Decisions | D-20, D-21, D-27, D-35, D-59, D-60, D-62, D-63, D-64, D-69, D-73, D-77, D-79, D-83, D-106, D-115, D-117, D-130, D-150, D-173, D-224, D-380, D-383, D-389, D-399, D-401 |
| Gates | T-1-20, T-1-21, T-1-37 |
| Config keys | none |

**s2 Local store**

| Kind | Ids |
| --- | --- |
| Features | F-SR-051, F-SR-052, F-SR-071, F-SR-073, F-SYS-028, F-SYS-029, F-SYS-045, F-SYS-068 |
| Gaps | G-man-013, G-sync-17, G-sync-20 |
| Decisions | D-18, D-22, D-34, D-36, D-37, D-38, D-41, D-43, D-58, D-64, D-66, D-67, D-70, D-73, D-75, D-79, D-82, D-83, D-85, D-86, D-87, D-95, D-109, D-110, D-136, D-152, D-245, D-246, D-262, D-380, D-392, D-401, D-404, D-405 |
| Gates | T-1-20, T-1-24, T-1-36, T-1-37, T-1-39, T-1-56, T-2-23, T-2-30, T-2-31, T-2-33, T-3-21 |
| Config keys | `cfg.app.image_cache_mb`, `cfg.app.local_history_days`, `cfg.app.outbox_keep_days`, `cfg.app.rejected_keep_days`, `cfg.flag.parallel_run_mode`, `cfg.media.evidence_mobile_fallback_h`, `cfg.media.local_keep_days`, `cfg.media.local_queue_max_mb`, `cfg.print.template_version`, `cfg.sale.max_lines_per_memo`, `cfg.sync.resync_window_h`, `cfg.telemetry.device_max_bytes_per_day` |

**s3 Capture rules**

| Kind | Ids |
| --- | --- |
| Features | F-SR-011, F-SR-057, F-SR-072 |
| Gaps | G-field-13, G-man-006 |
| Decisions | D-36, D-37, D-38, D-43, D-59, D-64, D-66, D-74, D-77, D-78, D-85, D-86, D-95, D-110, D-200, D-201, D-203, D-209, D-264, D-393, D-394, D-405 |
| Gates | T-1-20, T-1-24, T-1-28, T-1-29, T-2-22, T-2-23, T-2-32, T-2-96, T-3-21 |
| Config keys | `cfg.app.hold_to_confirm_ms`, `cfg.auth.confirm_identity_on_first_capture`, `cfg.geo.fix_reuse_max_age_s`, `cfg.geo.fix_timeout_s`, `cfg.geo.max_accuracy_m`, `cfg.geo.refresh_max`, `cfg.kpi.count_abandoned_visits`, `cfg.memo.edit_reasons`, `cfg.sale.call_start_prompt`, `cfg.sale.require_printer_before_sale`, `cfg.stock.max_issue_qty`, `cfg.stock.require_printed_slip`, `cfg.sync.family_hold_max_s`, `cfg.visit.abandon_min`, `cfg.visit.closed_streak_task`, `cfg.visit.outcome_codes` |

**s4 Sync engine**

| Kind | Ids |
| --- | --- |
| Features | F-ADM-030, F-API-057, F-SYS-009 |
| Gaps | G-man-031, G-scale-01, G-scale-20, G-sre-01, G-sre-02, G-sre-03, G-sre-10, G-sre-14, G-sre-19, G-sync-05, G-sync-06, G-sync-09, G-sync-10, G-sync-14, G-sync-15 |
| Decisions | D-21, D-22, D-59, D-60, D-62, D-63, D-64, D-65, D-73, D-75, D-81, D-116, D-123, D-130, D-138, D-173, D-222, D-246, D-261, D-266, D-381, D-382, D-383, D-384, D-390, D-400, D-403, D-406 |
| Gates | T-1-21, T-1-22, T-1-23, T-1-25, T-1-26, T-1-28, T-1-29, T-1-30, T-1-31, T-1-32, T-1-34, T-1-36, T-1-37, T-1-53, T-1-54, T-1-56, T-2-25, T-2-26, T-2-31, T-2-34, T-2-57 |
| Config keys | `cfg.bundle.delta_min_interval_min`, `cfg.day.sales_submit_dues_warning`, `cfg.day.sales_submit_offline_queue`, `cfg.day.submit_settle_timeout_min`, `cfg.media.evidence_mobile_fallback_h`, `cfg.net.fallback_after_failures`, `cfg.sync.batch_max_kb_raw`, `cfg.sync.batch_max_rows`, `cfg.sync.debounce_s`, `cfg.sync.family_skip_after`, `cfg.sync.periodic_min`, `cfg.sync.reason_texts`, `cfg.sync.reconcile_types`, `cfg.sync.resync_jitter_s`, `cfg.sync.resync_window_h`, `cfg.sync.retry_backoff_s`, `cfg.sync.retry_cap_s`, `cfg.sync.retry_max_inprocess`, `cfg.sync.row_max_retries` |

**s5 State machines**

| Kind | Ids |
| --- | --- |
| Features | F-AMO-039, F-SYS-056 |
| Gaps | G-cfg-20, G-fraud-02, G-sec-12 |
| Decisions | D-20, D-27, D-30, D-55, D-64, D-71, D-85, D-126, D-209, D-225, D-388, D-389 |
| Gates | T-1-20, T-1-27, T-1-28, T-1-29, T-1-30, T-1-38, T-1-54, T-1-56, T-2-27, T-2-29, T-3-20, T-3-21 |
| Config keys | `cfg.app.rejected_keep_days`, `cfg.day.checkout_earliest_time`, `cfg.day.submit_grace_h`, `cfg.sync.max_backdate_days`, `cfg.sync.max_clock_skew_min` |

**s6 Bundle and config propagation**

| Kind | Ids |
| --- | --- |
| Features | F-API-040, F-API-041, F-API-042 |
| Gaps | G-man-065, G-man-069, G-scale-05, G-sync-03, G-sync-21 |
| Decisions | D-09, D-30, D-57, D-70, D-71, D-72, D-82, D-84, D-89, D-129, D-130, D-257, D-262, D-385, D-386, D-387, D-392 |
| Gates | T-1-27, T-1-30, T-1-36, T-1-38, T-2-24, T-2-27, T-2-28, T-2-29, T-2-55, T-3-36, T-3-41, T-3-42 |
| Config keys | `cfg.bundle.delta_max_age_h`, `cfg.bundle.delta_min_interval_min`, `cfg.bundle.regen_max_per_s`, `cfg.bundle.stale_max_days`, `cfg.day.checkout_earliest_time`, `cfg.geo.mock_policy`, `cfg.geo.radius_m`, `cfg.ops.push_enabled`, `cfg.route.allow_unplanned_day`, `cfg.support.contacts`, `cfg.sync.login_jitter_s`, `cfg.sys.schedule_horizon_days` |

**s7 Session, binding, logout**

| Kind | Ids |
| --- | --- |
| Features | F-API-031, F-SR-001, F-TSO-022 |
| Gaps | G-feat-41, G-man-020, G-man-024, G-sec-02, G-sre-06, G-sync-01, G-sync-16 |
| Decisions | D-01, D-66, D-68, D-69, D-80, D-101, D-102, D-103, D-104, D-105, D-120, D-126, D-208, D-244, D-265, D-395 |
| Gates | T-0-70, T-1-37, T-2-28, T-2-29, T-3-21, T-3-22, T-3-23, T-7-84 |
| Config keys | `cfg.app.logout_block_when_pending`, `cfg.app.logout_wipes_data`, `cfg.auth.max_devices_per_user`, `cfg.auth.max_users_per_device`, `cfg.auth.min_refresh_interval_s`, `cfg.auth.offline_unlock_max_days`, `cfg.auth.otp_length`, `cfg.auth.otp_ttl_min`, `cfg.auth.reverify_on_new_version`, `cfg.sync.orphan_pending_alert_h` |

**s8 Budgets**

| Kind | Ids |
| --- | --- |
| Features | F-SYS-030 |
| Gaps | G-17-03, G-man-026, G-qa-05, G-scale-16, G-sync-12 |
| Decisions | D-09, D-11, D-12, D-67, D-73, D-74, D-75, D-224, D-398, D-399, D-404 |
| Gates | T-1-30, T-1-33, T-1-36, T-1-37, T-2-26, T-2-30, T-2-32, T-2-34, T-7-20 |
| Config keys | `cfg.media.photo_max_kb`, `cfg.print.disconnect_idle_s` |

**s9 Printing and memo numbers**

| Kind | Ids |
| --- | --- |
| Features | F-SR-015, F-SR-070, F-SR-073, F-SYS-044, F-SYS-069 |
| Gaps | G-17-01, G-data-08, G-feat-43, G-field-14, G-field-20, G-man-006, G-man-014, G-man-023, G-scale-18, G-sync-02 |
| Decisions | D-16, D-17, D-18, D-19, D-33, D-35, D-36, D-37, D-74, D-76, D-77, D-83, D-86, D-107, D-118, D-152, D-158, D-214, D-246, D-321, D-322, D-343, D-344, D-346, D-391, D-392, D-393, D-394 |
| Gates | T-1-24, T-1-35, T-1-41, T-2-20, T-2-32, T-2-41, T-2-92, T-2-93, T-2-99 |
| Config keys | `cfg.i18n.digit_script`, `cfg.memo.reprint_max`, `cfg.memo.seq_block_size`, `cfg.print.confirm_after_print`, `cfg.print.disconnect_idle_s`, `cfg.print.models`, `cfg.print.pairing_pins`, `cfg.print.status_query`, `cfg.print.template_version`, `cfg.stock.require_printed_slip` |

**s10 App lifecycle**

| Kind | Ids |
| --- | --- |
| Features | F-ADM-024, F-AMO-001, F-AMO-034, F-API-029, F-API-030, F-SR-006, F-SR-008, F-SR-009, F-SR-064, F-SR-077, F-SYS-020, F-SYS-021, F-TSO-024, F-TSO-028 |
| Gaps | G-feat-35, G-field-15, G-field-18, G-man-020, G-man-022, G-man-025, G-man-059, G-scale-16, G-sre-11, G-sre-20, G-sync-11, G-sync-15 |
| Decisions | D-01, D-08, D-09, D-10, D-74, D-75, D-79, D-84, D-115, D-130, D-170, D-181, D-205, D-208, D-223, D-232, D-342, D-344, D-396, D-397, D-402 |
| Gates | T-1-35, T-2-23, T-2-31, T-2-32, T-2-33, T-2-55, T-2-56, T-2-58, T-3-41, T-3-42, T-7-57 |
| Config keys | `cfg.app.default_locale`, `cfg.app.drawer_items`, `cfg.app.health_warn_battery_pct`, `cfg.app.home_tiles`, `cfg.app.location_denied_policy`, `cfg.geo.require_precise`, `cfg.i18n.date_style`, `cfg.i18n.digit_script`, `cfg.i18n.grouping`, `cfg.map.3d_enabled`, `cfg.map.api_key_ref`, `cfg.map.tile_cache_mb`, `cfg.release.finish_offline_day_before_force`, `cfg.release.min_version`, `cfg.release.update_prompt_policy`, `cfg.release.update_wifi_only`, `cfg.support.max_upload_mb`, `cfg.support.pda_upload_wifi_only`, `cfg.sync.engine_mode`, `cfg.sync.pending_reminder_time`, `cfg.tso.team_location_max_age_min` |

**s11 Edge cases**

| Kind | Ids |
| --- | --- |
| Features | F-AMO-037, F-AMO-041, F-SR-059, F-SR-071, F-SYS-070 |
| Gaps | G-field-13 |
| Decisions | D-32, D-38, D-39, D-42, D-66, D-79, D-86, D-95, D-118, D-152, D-225, D-253, D-326, D-329, D-388, D-395, D-397, D-403, D-404 |
| Gates | T-1-20, T-1-21, T-1-24, T-1-27, T-1-30, T-1-31, T-1-32, T-1-36, T-1-38, T-1-39, T-1-53, T-1-55, T-1-56, T-2-10, T-2-20, T-2-23, T-2-24, T-2-25, T-2-27, T-2-28, T-2-29, T-2-30, T-2-31, T-2-32, T-2-33, T-2-55, T-2-56, T-2-57, T-2-90, T-2-91, T-2-92, T-2-93, T-2-96, T-2-97, T-2-98, T-2-99, T-3-21, T-3-22, T-3-36, T-3-90 |
| Config keys | `cfg.app.oem_guidance`, `cfg.sync.max_backdate_days` |

**s12 Test plan**

| Kind | Ids |
| --- | --- |
| Features | none |
| Gaps | G-sync-02 |
| Decisions | D-145, D-151, D-404 |
| Gates | T-1-20, T-1-21, T-1-22, T-1-23, T-1-24, T-1-25, T-1-26, T-1-27, T-1-28, T-1-29, T-1-30, T-1-31, T-1-32, T-1-33, T-1-34, T-1-35, T-1-36, T-1-37, T-1-38, T-1-39, T-1-41, T-1-53, T-1-54, T-1-55, T-1-56, T-2-20, T-2-21, T-2-22, T-2-23, T-2-24, T-2-25, T-2-26, T-2-27, T-2-28, T-2-29, T-2-30, T-2-31, T-2-32, T-2-33, T-2-34, T-2-55, T-2-56, T-2-57, T-2-58, T-2-90, T-2-99, T-3-20, T-3-21, T-3-22, T-3-23, T-3-36, T-3-41, T-3-42, T-3-90, T-7-20, T-7-84 |
| Config keys | none |

### Added at the editorial merge

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-160, D-174, D-204, D-206, D-220, D-235, D-250, D-309. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-29, G-qa-30, G-qa-31, G-qa-32, G-qa-33, G-qa-34, G-qa-41, G-qa-45, G-qa-68, G-qa-48, G-qa-70, G-qa-72, G-qa-74, G-qa-83 | s4.1, s4.1b, s4.4, s4.11, s4.12, s8.2b, s8.10, s10, s13.3b |
| Decisions | D-505, D-506, D-507, D-508, D-509, D-510, D-511, D-517, D-521, D-538, D-539, D-541, D-543, D-551 | as above |
| Features | F-SR-080, F-API-069, F-API-070, F-SYS-079, F-SYS-080, F-SYS-081, F-SYS-084, F-ADM-072, F-ADM-075 | s4.1, s4.11, s4.12, s8.10, s10 |
| Gates | T-1-151, T-1-152, T-2-151, T-2-153, T-2-154, T-3-150, T-3-151, T-3-152, T-3-153, T-3-154, T-4-155, T-7-155, T-7-156, T-7-157 | as above |
| Config keys | cfg.sync.checkout_upload_jitter_max_s, cfg.sync.max_savepoints_per_tx, cfg.sync.resync_safety_margin_h, cfg.sync.digest_days, cfg.sla.pending_rows_alert_h, cfg.release.auto_freeze_regression_pct, cfg.telemetry.bat17_floor_pct, cfg.ops.prefetch_enabled, cfg.telemetry.enabled, cfg.geo.radio_env_enabled, cfg.day.sales_submit_locks_capture, cfg.day.submit_undo_window_min | s4, s8.10, s13.4 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-89, G-qa-94, G-qa-96, G-qa-106, G-qa-107, G-qa-108, G-qa-115, G-qa-116, G-qa-123, G-qa-124, G-qa-133 | s13.3c |
| Decisions | D-557, D-562, D-563, D-571, D-572, D-579, D-580, D-584, D-585, D-594 | s13.3c |
| Features | F-SYS-089, F-SYS-090, F-SYS-091, F-SYS-092, F-SYS-095, F-SR-081, F-AMO-049, F-API-083 | s3.1, s4.1, s4.4, s4.12, s6.5, s6.7 |
| Gates | T-1-156, T-2-161 to T-2-165, T-2-169, T-3-157, T-3-159, T-7-161, T-7-166 | s13.3c |
| Config keys | cfg.sync.config_check_min_gap_min, cfg.sync.config_check_max_per_day, cfg.sync.resync_late_max_days, cfg.stock.save_mode, cfg.stock.correct_total_enabled, cfg.stock.resave_guard_window_min, cfg.calendar.window_unit, cfg.bundle.stale_max_cal_days_ceiling, cfg.calendar.break_overrides, cfg.calendar.prefetch_next_working_day, cfg.support.directive_ttl_h | doc 19 s3.2.11 |
