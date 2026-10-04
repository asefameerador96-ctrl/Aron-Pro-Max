# Lens: DATA PLATFORM (R1) — field inventory, idempotency, schema v2, analytics layer

Date: 2026-10-04. Scope: docs/03, docs/10, db/schema.sql audited against R1 ("any dashboard or report at any time, without touching the transaction log or re-deriving history"). Builds on `seed-findings.md` #1–#10; those are taken as confirmed and not re-argued.

Legend used throughout: **OK** = stored today in schema.sql; **PARTIAL** = column exists but loses information; **NO HOME** = nothing in schema.sql stores it; **ASSUMPTION** = my call, labelled with the reason; **confirm** = unknown; confirm with the business (cross-refs docs/13 Q-numbers where one exists).

---

## 0. Headline findings (read this if nothing else)

| # | Finding | Consequence for R1 | Fix (section) |
| --- | --- | --- | --- |
| H1 | 61 captured values have **no home** in schema.sql (inventory §1); 19 whole feature areas have no table (seed #5 + price compliance, visit plans, free samples, Superstar, gift assignment, data-entry source, bundle/login events). | Reports in docs/09 (Free Sample, Online/Offline Sales, Superstar, Astha Gift Choice, Data Entry Log, Price compliance count) cannot be built at all from the current schema. | §3 M-20..M-45 |
| H2 | 10 device-originated tables are **not idempotent** (seed #2 + attendance/stock semantics). | A retried batch doubles lines, QC, surveys, points. | §2, §3 P1, M-05..M-12 |
| H3 | **Capture-time context is not stored**: visit has no route/assignment, memo has no price-list version, nothing has a config version, outlet→route and SR→route are only "current". | History silently re-writes itself whenever an outlet moves route or an SR is reassigned; MTD numbers change retroactively. | §3 P3, P6; M-13..M-16 |
| H4 | No analytics layer beyond three thin `fact_daily_*` tables; no dimensions, no SCD, no grain for memo-line, attendance, dues, loyalty or config. | "Any dashboard anytime" is impossible; every new report would re-scan `memo`. | §4 |
| H5 | No ingest registry, no quarantine, no conflict table. docs/04 says "quarantine for review, not dropped" — nothing stores it. | Rejected rows vanish; device-vs-server reconciliation cannot explain a mismatch. | §3 M-30, §6 |
| H6 | Money cannot hold the seed prices (seed #1); quantities have no unit (seed #8); Submit % is two KPIs with one name (seed #7). | Every value and volume figure is wrong or ambiguous from day one. | §3 P5, P8; §4.5 D-01..D-03 |

---

## 1. Field-level inventory (everything captured, mapped to storage)

Walked screen by screen through docs/06 (SR), 07 (AMO), 08 (TSO), 09 (web), 10 (programs). Derived/display-only values (KPI strip, charts, lists) are **not** captured and are omitted; they are covered in §4. "Provenance" columns (device_id, app_version, captured_at, received_at, sync_batch_id, captured_offline) apply to every device row and are listed once at the end of each block instead of per row.

### 1.1 SR app (docs/06)

| # | Screen | Captured value | Type | Home in schema.sql | Status |
| --- | --- | --- | --- | --- | --- |
| S01 | Login | username, password | text / secret | `app_user.username`; password hash: none | **NO HOME** (password hash, history) → `user_credential`, `password_history` (M-40) |
| S02 | Bind device | device_uuid, OTP entered, OTP issuer (TSO), issued_at, expires_at, used_at | uuid, text, ts | `device.device_uuid, bound_at`; OTP lifecycle: none | **NO HOME** → `device_otp` (M-41) |
| S03 | Bind device | phone model, OS version, app version, ABI, screen class | text | none | **NO HOME** → `device` columns (M-41); needed for R4 fleet reporting |
| S04 | Permissions | granted/denied per permission (location, camera, mic, BT) | bool×4 | none | **NO HOME** → `device_capability_snapshot` (M-41) — low priority; explains "no GPS fix" days |
| S05 | In-app update | current version, offered version, download/install result | text, enum | none | **NO HOME** → `app_release`, `device.app_version` (M-44) |
| S06 | Settings | language | enum | none | local-only; **ASSUMPTION** not needed server-side (UI preference) |
| S07 | Settings → PDA to Support | uploaded local DB/sync file, reason text | blob, text | none | **NO HOME** → `support_upload` (M-44) |
| S08 | Attendance check-in | time, lat, lng, accuracy, mock flag, provider | ts, double×3, bool, text | `attendance.check_in_at, check_in_lat, check_in_lng` | **PARTIAL**: accuracy, mock, provider missing → `geo_fix` + `attendance.check_in_fix_id` (M-12, M-17) |
| S09 | Attendance check-out | time, lat, lng, accuracy, mock | as above | `attendance.check_out_at` only | **PARTIAL**: no check-out GPS → `attendance.check_out_fix_id` |
| S10 | Attendance | client_uuid of the event | uuid | none | **NO HOME** (natural key exists; see §2) |
| S11 | Printer pairing | printer name/MAC, connect result | text | none | **NO HOME** → `device.printer_id` (M-41); explains print failures |
| S12 | Stock load | sku, issued qty (unit!), time, printed stock memo flag, print count | int, ts, bool | `stock_issue.issued_qty` (date-level sum) | **PARTIAL**: no time, no unit, no event identity → `stock_movement` (M-11) |
| S13 | Stock return (end of day) | sku, returned qty, time | int, ts | `stock_issue.returned_qty` | **PARTIAL** → `stock_movement(kind=return)` |
| S14 | Outlet select → visit open | outlet, started_at, business_date, user | ids, ts, date | `visit.*` | OK |
| S15 | Visit open | **route being worked**, route_assignment in force | ids | none | **NO HOME** (seed #3) → `visit.route_id, route_assignment_id` (M-13) |
| S16 | Visit open | GPS fix: lat, lng, accuracy, mock, provider, fix age, satellites/altitude (if available) | double×3, bool, text, int | `visit.lat, lng, gps_accuracy_m, mock_location` | **PARTIAL**: provider, fix age, retry count missing → `geo_fix` (M-17) |
| S17 | Geo check | device verdict (in range / out of range / no fix), device-computed distance, radius used (value + config version) | enum, double, int, text | `visit.geo_validated` (single flag) | **PARTIAL** (seed #4) → `visit.device_geo_verdict, device_distance_m, radius_m_used, config_version` (M-13) |
| S18 | Geo check → Refresh | number of refresh attempts before start/force | int | none | **NO HOME** → `visit.gps_retry_count` — a fraud signal (reps retry until spoof lands) |
| S19 | Force sale | reason (1 of 2), outlet photo, photo GPS | enum, blob, fix | `visit.force_reason`, `outlet_photo` (unlinked) | **PARTIAL**: photo not linked to visit → `visit.force_photo_id` (M-13) |
| S20 | Visit | ended_at / call duration | ts | none | **NO HOME** → `visit.ended_at` (SR Efficiency report needs it) |
| S21 | Visit | suggested order qty shown (per SKU) and whether accepted | jsonb | none | **NO HOME** (seed #5, Q6) → `outlet_suggestion` + `visit.suggestion_snapshot` (M-13, M-37) |
| S22 | AV/KV content | content item viewed, duration | id, int | none | **NO HOME** → `content_view` (M-44); **ASSUMPTION** low priority, confirm whether marketing wants it |
| S23 | POSM survey | question, answer, photo | key, text, blob | `survey_response.question_key, answer, photo_url` | **PARTIAL**: no question definitions, no client_uuid, no survey version → `survey_question`, `survey_response.client_uuid` (M-09) |
| S24 | DRP collection | kind (empty pack / slide), qty, brand or SKU of empties, offer earned | enum, int, id, id | `drp_collection.kind, qty, offer_id` | **PARTIAL**: brand/sku missing, no client_uuid → M-10 |
| S25 | Sale entry | per SKU: quantity **as entered**, entry unit (pack/stick/piece/dozen) | int, enum | `memo_line.qty` (unit-less) | **PARTIAL** (seed #8) → `qty_entered, unit_entered, pack_factor, qty_base` (M-07) |
| S26 | Sale entry | unit price applied, price type, price-list version/valid_from | money, enum, date | `memo_line.unit_price_minor` | **PARTIAL** → `memo_line.price_type, price_valid_from` (M-07) |
| S27 | Offers auto-apply | offer applied per line and per memo, discount amount, free qty given | ids, money, int | `memo_line.offer_id` (dangling), `memo.discount_minor` | **PARTIAL/NO HOME** → `offer`, `memo_offer`, `memo_line.is_free, free_qty` (M-08, M-20) |
| S28 | Review | gross, discount, net | money | `memo.gross/discount/net_minor` | OK (precision fix P5) |
| S29 | Credit (বাকি) | is_credit, paid amount, due amount | bool, money | `memo.is_credit, paid_minor, due_minor` | OK |
| S30 | Product QC | per SKU production fault qty, transport fault qty; QC completed time (edit lock) | int×2, ts | `qc_entry.*`; QC-done time: none | **PARTIAL**: no client_uuid, no `qc_completed_at` → M-08, M-06 |
| S31 | Print memo | printed_at, memo number on paper, reprint count | ts, text, int | `memo.printed_at` | **PARTIAL**: `memo_no`, `print_count` missing (M-06) |
| S32 | Zero sale | is_zero_sale, confirmation | bool | `visit.is_zero_sale` | OK; whether a zero-sale produces a `memo` row: **confirm** (affects Memo KPI, §4.5) |
| S33 | Due collection | outlet, against memo, amount, full/partial flag, time, GPS, payment mode | ids, money, bool, ts, fix | `due_collection.*` | **PARTIAL**: GPS, full/partial flag, mode missing (M-12) |
| S34 | Sale edit | edit reason (1 of 3), GPS at edit, superseded memo, re-entered lines | enum, fix, id | `memo.edit_reason, supersedes_memo_id` | **PARTIAL**: edit GPS/geo verdict missing; reason is free text → `memo.edit_fix_id`, reason as config list (M-06) |
| S35 | Sync | device counts per type, server accepted/rejected per type, bytes, duration, network type, battery % | jsonb, int | `sync_batch.counts` | **PARTIAL** → `sync_batch` extended (M-31) |
| S36 | Sales Submit | route, business_date, time, "dues outstanding" warning acknowledged, outstanding due amount at submit | ids, ts, bool, money | none (route_log has no state) | **NO HOME** (seed #10) → `route_day` (M-32) |
| S37 | New shop | route, name, owner, mobile, GPS, photo | text×3, fix, blob | `outlet_change_request.proposed`, `outlet_photo` | **PARTIAL**: `outlet_photo.outlet_id NOT NULL` cannot hold a photo for a not-yet-existing outlet → `outlet_photo.change_request_id` (M-15) |
| S38 | Permanently closed | route, outlet, confirm | ids | `outlet_change_request(type=close)` | OK |
| S39 | Info change | name/owner/mobile edits + GPS + photo | text, fix, blob | `outlet_change_request(type=info)` | OK (photo link as S37) |
| S40 | Astha | filters (year/quarter/months) | — | — | display only |
| S41 | Astha | per-outlet per-brand STD target + memo target (shown) | numeric | none | **NO HOME** → `program_outlet_target` (M-22) |
| S42 | Redemption | outlet, gift items with qty each, points per item, total points, cash amount, confirm time | ids, int, money | `redemption.gift (text), points_spent, cash_minor` | **PARTIAL**: multi-gift not representable; catalog missing → `redemption_line`, `gift_catalog` (M-23) |
| S43 | Redemption | loyalty ledger debit | int | `loyalty_ledger` | **PARTIAL**: no client_uuid, no link to redemption, no period/program (M-23) |
| S44 | Photo Capture → Astha gift | gift assigned to outlet (from TSO portal), photo, GPS, time, user | id, blob, fix | `gift_photo` (no assignment, no user, no GPS, no client_uuid) | **PARTIAL** → `gift_assignment`, `gift_photo` v2 (M-24) |
| S45 | Photo Capture → Campaign gift verify | redemption being verified, photo | id, blob | none | **NO HOME** → `gift_photo.redemption_id` |
| S46 | Task Delegation | task resolve: time, note, by | ts, text | `task.status, resolved_at` | **PARTIAL**: no client_uuid, no note → `task_event` (M-25) |
| S47 | Tutorial | video list | — | none | **NO HOME** → `tutorial_asset` (M-44) |
| S48 | Memo number | human memo number safe offline | text | none | **NO HOME** (seed #5, Q5) → `memo.memo_no`, `memo.memo_serial` (M-06) |
| S49 | Every row | provenance: device_id, app_version, captured_at (device clock), received_at (server clock), sync_batch_id, captured_offline | ids, ts, bool | none | **NO HOME** → standard columns on every device table (P11) |

### 1.2 AMO app (docs/07) — only what is additional to §1.1

| # | Screen | Captured value | Home | Status |
| --- | --- | --- | --- | --- |
| A01 | Attendance | press-and-hold check-in/out; reverse-geocoded address | `attendance` | address is display-only; **ASSUMPTION** not stored (recomputable) |
| A02 | Control call | visit kind = control call; manual-override (photo updates location) | `visit.force_reason` enum lacks `manual_override`; no `visit_kind` | **NO HOME** → `visit.kind`, `force_reason` + `manual_override` (M-13) |
| A03 | SR Perf. Assessment | per brand: present / OOS tick (15 brands); POSM sticker/banner yes/no | `distribution_check` (one row per brand with posm repeated) | **PARTIAL**: no visit link, posm is outlet-level → header+line (M-26) |
| A04 | Joint call | SR assessed, visit, rubric version, 1–5 star per criterion (≈14 criteria in 5 groups) | `call_assessment.scores jsonb` | **PARTIAL**: no rubric definitions, no visit link → `assessment_rubric`, `call_assessment.visit_id, rubric_version` (M-27) |
| A05 | Sale / previous sale data | same as SR; date-picker read | `memo` | OK |
| A06 | Task Delegation (+) | route/section, outlet, task type, due date, description, assigner | `task` lacks `route_id`, `client_uuid` | **PARTIAL** (M-25) |
| A07 | Outlet verification | verifier, verified_at, sub-channel, geo-class set, re-captured GPS + photo, approve/reject, reject reason | `outlet_change_request.verified_by` only | **PARTIAL** → `verified_at, verifier_payload jsonb, rejection_reason, approved_at` (M-15) |
| A08 | Update Base | outlet, photo, exact map point (lat/lng chosen), confirm | `request_type` enum lacks it; no location history | **NO HOME** → `request_type += base_update`; `outlet_location_history` (M-15, M-16) |
| A09 | Sales Submit counts | "Price compliance" count | none | **NO HOME** — an entire capture type exists in the current AMO app with no spec detail → `price_compliance_check` (M-28); **confirm** fields |
| A10 | Team Location | SR latest fix | derivable from `geo_fix` (latest per user) | OK once M-17 exists |

### 1.3 TSO app (docs/08)

| # | Screen | Captured value | Home | Status |
| --- | --- | --- | --- | --- |
| T01 | Leave apply | leave type (Casual/Sick/Earn), from, to, days, reason, status, approver (DMO), decided_at, comment | none | **NO HOME** → `leave_application`, `leave_event` (M-33) |
| T02 | Final Submit | zone, sales date, submitter, time, routes shown with FF name or "Not Set" (snapshot), duplicate attempt | `final_submit` (zone, date, by, at) | **PARTIAL**: no route snapshot, no client_uuid, no reopen trail (Q11) → `final_submit_route`, `final_submit.reopened_by/at` (M-34) |
| T03 | Set Plan | date, zone, route, selected outlets, created_at | none | **NO HOME** → `visit_plan`, `visit_plan_outlet` (M-35) |
| T04 | My Visit Plan → Visit | questionnaire answers (SR visits regularly? prints memos?), delegate task Y/N, completed_at, GPS | `call_assessment(kind=retailer_questionnaire)` | **PARTIAL**: no plan link, no fix → `call_assessment.visit_plan_outlet_id, fix_id` (M-27) |
| T05 | Assign Task | type (Irregular Visit), date, comment | `task` | OK (plus M-25) |
| T06 | My Feedback | category, title, description, image | none | **NO HOME** → `feedback` (M-36) |
| T07 | Logout wipes data | logout event | `activity_log` | OK (action='logout_wipe') |
| T08 | Retailer (periphery) | radius chosen 50/100/300 | — | display only; radius list is config `cfg.tso.periphery_radius_options` |

### 1.4 Web (docs/09)

| # | Page | Captured value | Home | Status |
| --- | --- | --- | --- | --- |
| W01 | Browse Retailer → edit | basic info, address, "business" section, "additional detail" section; editor, time, before/after | `outlet` columns; no audit | **PARTIAL**: unknown section fields → `outlet.extra jsonb`; `audit_log` (M-16, M-43). **confirm** field list of "business"/"additional detail" |
| W02 | Products / Geography / Routes / Users CRUD | every master change: who, when, old, new | none | **NO HOME** → `audit_log` (M-43) |
| W03 | Route Planning | route→SR/SS assignment changes with effective dates, reason | `route_assignment` (no created_by/at, reason) | **PARTIAL** (M-14) |
| W04 | Target Allocation | target per scope×product×month, allocator, time, split method | `target` (no unique key, no author, no version) | **PARTIAL** → M-21 |
| W05 | Target Revise List | requested new values per product, old values, requester, levels, each approver + time + decision | `target_revision` has **no values** at all | **PARTIAL** → `target_revision_line`, `target_revision_event` (M-21) |
| W06 | Outlet Approval Panel | approver, approved_at, decision, reason | `approved_by` only | **PARTIAL** (M-15) |
| W07 | Credentials | new password, change time, last-10 history, 24 h rule | none | **NO HOME** (M-40) |
| W08 | Astha Gift Panel / "TSO portal" gift choice | outlet, program period, gift chosen, qty, chosen_by, chosen_at | none | **NO HOME** → `gift_assignment` (M-24) |
| W09 | Superstar Campaign | per outlet: category, incentive slab, base target, STD & memo target, criteria-met flag, period | none | **NO HOME** → `campaign`, `campaign_outlet` (M-22) |
| W10 | Daily Tracking "take action" (after 17:00) | route, date, actor, action type, note | none | **NO HOME** → `tracking_action` (M-32); **confirm** what the action is |
| W11 | Admin → Data Entry | memos entered on the web (source of "Online/Offline Sales" report) | `memo` has no `entry_source` | **NO HOME** → `memo.entry_source`, `captured_offline` (M-06). **ASSUMPTION**: "Data Entry" = web memo entry for SRs without a working phone; confirm |
| W12 | Admin → Retailer/Wholesale Outlet | outlet kind retail vs wholesale | none | **NO HOME** → `outlet.outlet_kind` (M-16) (Q17) |
| W13 | Admin → SR Device OTP | OTP issue: TSO, target user, code hash, expiry, used | none | **NO HOME** → `device_otp` (M-41) |
| W14 | Admin → Diamond League setup | program periods, earn rule (points per what?), gift catalog + costs, cash rate | none | **NO HOME** → `loyalty_program`, `loyalty_earn_rule`, `gift_catalog` (M-23); earn rule **confirm** (Q13) |
| W15 | Admin → Sales Plan | zone×sku enable, who/when | `sales_plan` (no audit, no effective dating) | **PARTIAL** (M-19) |
| W16 | Admin → QC / Supervisory Module | unknown | none | **NO HOME**, **confirm** contents |
| W17 | Reports → Free Sample | free sample qty by SKU by route | none | **NO HOME** → `memo_line.is_free` + offer type `free_sample` (M-07, M-20) |
| W18 | Reports → Discount Report | ~22 promotion groups, discount per group | `offer` missing | **NO HOME** (M-20) |
| W19 | Reports → Data Entry Log | per route download first/last, upload first/last, counts | `route_log` | **PARTIAL**: no login-event identity (seed #9) → `bundle_download` (M-31); `route_log` becomes a view |
| W20 | Reports → Online/Offline Sales | whether each memo was captured offline / entered online | none | **NO HOME** (M-06) |
| W21 | Reports → By-Route Geo Capture | outlet GPS captures per route: who, when, source | `outlet_photo` partly | **PARTIAL** → `outlet_location_history` (M-16) |
| W22 | Report exports | who exported which report with which filters (PII reports) | none | **NO HOME** → `report_export_log` (M-43) — needed for PII governance |
| W23 | Admin → config (R6) | every tunable, scope, effective dates, who, why | `territory_geo_config.radius_m` only | **NO HOME** (seed #6) → `config_item`, `config_value`, `config_change_audit`, `config_ack` (M-45) |

### 1.5 Programs (docs/10) — captured values not already listed

| # | Program | Value | Home | Status |
| --- | --- | --- | --- | --- |
| P01 | Astha | outlet enrolment + tier (sub-channel) history by quarter | `outlet.sub_channel_id` current only | **PARTIAL** → `dim_outlet` SCD2 + `program_enrolment` (M-22) |
| P02 | Diamond League | monthly points earned, source of each earn (memo? target?) | `loyalty_ledger.points_delta, reason` | **PARTIAL**: earn rows have no idempotent source key, no period (M-23) |
| P03 | Promotions | offer definition: type (% / amount / free qty / DRP), scope (sku/brand), date range, zone scope, stacking rule | none | **NO HOME** (M-20) |
| P04 | Targets | split formula inputs (monthly → route → SKU) | none | **NO HOME** → `target.allocation_method, parent_target_id` (M-21), Q12 |
| P05 | Migration | Apsis id → new id crosswalk; opening balances (dues, points) with as-of date | none | **NO HOME** → `id_crosswalk`, `opening_balance` (M-42) |

**Count:** 61 values/groups marked NO HOME, 34 PARTIAL. Every one is resolved by a numbered migration in §3.

---

## 2. Idempotency audit (every table that receives device-originated rows)

Rule under audit (CLAUDE.md #2): a retried, duplicated or partial upload must never create a second row or double a number. Ingest semantics proposed for all device rows: `INSERT … ON CONFLICT (client_uuid) DO NOTHING RETURNING id`, with a global **ingest registry** (M-30) that records `client_uuid → (record_type, server_id, payload_hash)` so (a) the same uuid resubmitted with a *different* payload is detected as a conflict and (b) partitioned tables keep a single global uniqueness point.

| Table | Idempotency key today | Verdict | Fix |
| --- | --- | --- | --- |
| `visit` | `client_uuid UNIQUE` | OK | keep; add `UNIQUE(client_uuid, business_date)` on the partitioned table + registry (M-30) |
| `memo` | `client_uuid UNIQUE` | OK | as above; `supersedes_memo_id` resolved via `supersedes_client_uuid` |
| `memo_line` | none (`memo_id + sku_id` not even unique) | **BROKEN** (seed #2) | `client_uuid UNIQUE NOT NULL`; `UNIQUE(memo_id, line_no)`; carry `memo_client_uuid` (M-07) |
| `qc_entry` | none | **BROKEN** | `client_uuid`; `UNIQUE(visit_id, sku_id)`; carry `visit_client_uuid` (M-08) |
| `survey_response` | none | **BROKEN** | `client_uuid`; `UNIQUE(visit_id, question_id, survey_version)` (M-09) |
| `drp_collection` | none | **BROKEN** | `client_uuid` (M-10) |
| `due_collection` | `client_uuid UNIQUE` | OK | carry `against_memo_client_uuid` for out-of-order |
| `attendance` | `UNIQUE(user_id, business_date)` | **WEAK**: check-in and check-out are two events at different times; a plain upsert on the natural key lets a retried check-in overwrite a stored check-out, or a late check-in from a second device overwrite the first | `attendance_event(client_uuid, kind in/out, fix)` as the stored event; `attendance` becomes a derived row (first in, last out) — M-12 |
| `stock_issue` | `UNIQUE(user_id, business_date, sku_id)` | **WEAK**: stores a sum; a second stock load the same day or a retry cannot be distinguished (replace vs add) | `stock_movement(client_uuid, kind issue/return, qty)`; `stock_issue` becomes derived (M-11) |
| `loyalty_ledger` | none | **BROKEN** | `client_uuid` for device debits; `UNIQUE(source_type, source_id)` for server earns so re-aggregation never double-credits (M-23) |
| `redemption` | `client_uuid UNIQUE` | OK | add `redemption_line(client_uuid)` (M-23) |
| `gift_photo` | none | **BROKEN** | `client_uuid`; `UNIQUE(gift_assignment_id)` (one photo per outlet per assignment) (M-24) |
| `outlet_photo` | none | **BROKEN** | `client_uuid` + `media_object.content_sha256` dedupe (M-15, M-31) |
| `outlet_change_request` | `client_uuid UNIQUE` nullable | **WEAK** (nullable) | `NOT NULL DEFAULT gen_random_uuid()` (web-originated rows get a server uuid) (M-15) |
| `task` | none; created by AMO/TSO app offline, resolved by SR offline | **BROKEN** | `task.client_uuid`; resolve/reassign as `task_event(client_uuid)` (M-25) |
| `call_assessment` | `client_uuid UNIQUE` | OK | — |
| `distribution_check` | `client_uuid UNIQUE` per brand row | OK but shape is wrong | header `client_uuid` + `UNIQUE(check_id, brand_id)` lines (M-26) |
| `final_submit` | `PK(zone_id, business_date)` | OK (first-wins) | add `client_uuid` of the request so a retry returns the same success instead of "already given" (M-34) |
| `sync_batch` | none | **BROKEN for the response**: a retried batch cannot be answered with the same accepted counts | `batch_uuid UNIQUE` (client-generated); replay returns stored response (M-31) |
| sales submit (`route_day`) | n/a (table missing) | — | `PK(route_id, business_date)` + `submit_client_uuid` (M-32) |
| `leave_application`, `visit_plan`, `visit_plan_outlet`, `feedback`, `price_compliance_check`, `support_upload` | tables missing | — | all created with `client_uuid UNIQUE NOT NULL` |
| `activity_log` | none | acceptable (append-only telemetry) but retries duplicate | `client_uuid` (cheap; the device already generates it) |
| `device` | `device_uuid UNIQUE` | OK | — |

Parent/child ordering rule (docs/04 "server tolerates out-of-order"): every child row carries the **parent's client_uuid** (`visit_client_uuid`, `memo_client_uuid`, …) in addition to the server FK. Ingest resolves the FK through the registry; if the parent is not yet present the child is parked in `sync_rejected(reason='parent_missing')` and re-tried automatically when the parent lands (§6 DQ-30). This is the only correct way to survive a batch that was split by a dropped connection between parent and child.

Conflict rule: same `client_uuid`, different `payload_hash` → row kept as first received; the second payload goes to `sync_conflict` (M-30); batch response lists it under `rejected[]` with `reason='conflict'`. Nothing is overwritten silently.

---

## 3. Schema v2 — design principles and the Phase 0 migration list

### 3.1 Principles (each is a review checkbox for every later migration)

| # | Principle | Why / what it fixes |
| --- | --- | --- |
| P1 | **Every device-originated row has `client_uuid uuid NOT NULL`, globally unique via the ingest registry; children also carry the parent's client_uuid.** | CLAUDE.md #2; seed #2; out-of-order batches. |
| P2 | **Transactions are an immutable event log.** No UPDATE on a synced transactional row except server-side enrichment columns (`server_*`, `received_at`, flags). Corrections are new rows (`supersedes_*`, `against_*`, `*_event`). | Audit trail against fake-GPS and edit abuse; re-aggregation is deterministic. |
| P3 | **Capture context is stored at capture time**: route, route_assignment, price list version, config version, radius used, SKU pack factor, outlet's route/zone/channel at that moment (via SCD2 keys in the warehouse). | seed #3; H3. History must not change when masters change. |
| P4 | **Device verdict and server verdict are separate columns, never one flag.** Server verdict is authoritative; disagreement is a stored flag. | docs/05; seed #4. |
| P5 | **Money = `bigint` in milli-taka (1/1000 Tk), suffix `_mtk`.** One constant `MONEY_SCALE = 1000` in `/packages`. Retailer-facing totals are rounded half-up to paisa **only at presentation**, and the rounding adjustment is stored (`memo.round_adj_mtk`) so the printed total is reproducible. | seed #1 (7.935 Tk); keeps CLAUDE.md "integer minor units"; no float anywhere. ASSUMPTION over `numeric(14,3)`: integer arithmetic is exact in Dart, TS and SQL alike; numeric needs care in Dart. |
| P6 | **Anything whose meaning changes over time is effective-dated (SCD2, `valid_from/valid_to`, no overlaps enforced by an exclusion constraint)**: prices, sales plan, outlet→route, outlet location, outlet classification, route→SR, targets, config, program enrolment. | H3; "MTD last month" must be computed with last month's structure. |
| P7 | **`business_date` on every transactional row, computed on device (Asia/Dhaka from `captured_at`), recomputed on server, mismatch flagged.** `dim_date` is the only calendar. | CLAUDE.md #7; docs/04 clock-skew tests. |
| P8 | **Quantities: `qty_entered` + `unit_entered` + `pack_factor` + `qty_base`** on every quantity-bearing row; `qty_base` is in the SKU's base unit; aggregates sum `qty_base` only within one SKU/category. | seed #8; Q8. |
| P9 | **Never drop**: invalid rows go to `sync_rejected`, conflicts to `sync_conflict`, both visible in admin and counted in the batch response. | docs/04 "quarantined for review". |
| P10 | **Provenance on every device row**: `device_id, app_version, captured_at, received_at, sync_batch_id, captured_offline`. | R4/R5 monitoring; "Online/Offline Sales" report; forensic sync debugging. |
| P11 | **Large transactional tables are RANGE-partitioned by month of `business_date`** (`visit, memo, memo_line, geo_fix, attendance_event, due_collection, activity_log, sync_rejected`, and the event-grain facts). Partitions created 3 months ahead by a job. | 44 M visits/yr, ~175 M memo lines/yr, 100 M+ fixes/yr; archival by detaching partitions (§5). |
| P12 | **Masters carry `created_at/by, updated_at/by, version`; every master change writes `audit_log`.** Soft delete with `status`. | W02; R6 audit. |
| P13 | **Analytics schema `dw` is separate from `app` (transactional) and `cfg` (config)**; `dw` is populated only by the aggregation worker; dashboards, apps' home KPIs and BI read `dw` only. | R1; docs/02 write side vs read side. |
| P14 | **Enumerations that the business edits are config lists, not Postgres enums**: edit reasons, force reasons, leave types, task types, DRP kinds, survey questions, rubric criteria, gift catalog. Postgres enums stay only for structural states (`request_status`, `day_state`, `sync_state`). | R6; docs/06 "choose 1 of 3 edit reasons" cannot need a migration to become 4. |

### 3.2 Standard column blocks (referenced as @PROV, @AUDIT, @SCD in the DDL)

```sql
-- @PROV : on every device-originated table
client_uuid      uuid        NOT NULL,              -- idempotency key (P1)
device_id        bigint      REFERENCES app.device(id),
app_version      text,
captured_at      timestamptz NOT NULL,              -- device clock
received_at      timestamptz NOT NULL DEFAULT now(),-- server clock
sync_batch_id    bigint      REFERENCES app.sync_batch(id),
captured_offline boolean,                           -- device had no connectivity at capture
business_date    date        NOT NULL,              -- device-computed Asia/Dhaka date
business_date_server date    GENERATED ALWAYS AS ((captured_at AT TIME ZONE 'Asia/Dhaka')::date) STORED,
flags            text[]      NOT NULL DEFAULT '{}'  -- DQ/plausibility flags (§6)

-- @AUDIT : on every master/reference table
created_at timestamptz NOT NULL DEFAULT now(), created_by bigint REFERENCES app.app_user(id),
updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint REFERENCES app.app_user(id),
version    int NOT NULL DEFAULT 1, status text NOT NULL DEFAULT 'active'

-- @SCD : on every effective-dated table (requires btree_gist)
valid_from date NOT NULL, valid_to date,            -- NULL = open
CONSTRAINT <t>_no_overlap EXCLUDE USING gist (<key cols> WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
```

### 3.3 Phase 0 migration list (forward-only; numbered M-01…M-45; `0001_…` to `0045_…` in `/db/migrations`)

Ordering: M-01..M-04 foundations → M-05..M-12 transactional core → M-13..M-19 context & SCD → M-20..M-29 programs & supervisor captures → M-30..M-36 sync/day/control → M-37..M-45 auxiliary, auth, config, audit. The `dw` schema is §4 (M-50+ series, also Phase 0 so that Phase 1's "one tile" reads from `dw`).

#### M-01 Schemas, extensions, money and unit conventions
```sql
CREATE SCHEMA app; CREATE SCHEMA cfg; CREATE SCHEMA dw; CREATE SCHEMA stg;   -- stg = migration staging
CREATE EXTENSION IF NOT EXISTS btree_gist; CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- Money: every *_minor column in schema.sql is renamed *_mtk and documented as milli-taka.
COMMENT ON SCHEMA app IS 'money columns *_mtk are bigint milli-taka (1/1000 BDT); MONEY_SCALE=1000';
CREATE TYPE app.qty_unit AS ENUM ('stick','piece','dozen','pack','box','carton');
CREATE TYPE app.sync_state AS ENUM ('accepted','rejected','conflict','parked');
CREATE TYPE app.visit_kind AS ENUM ('sr_call','amo_control_call','amo_joint_call','tso_visit','web_entry');
CREATE TYPE app.entry_source AS ENUM ('app_offline','app_online','web_data_entry','migration');
ALTER TYPE request_type ADD VALUE 'base_update';
ALTER TYPE force_reason ADD VALUE 'manual_override';       -- AMO; keep enum, list is also mirrored in cfg
```
Renames (seed #1): `sku_price.amount_minor→amount_mtk`, `memo.gross/discount/net/paid/due_minor→*_mtk`, `memo_line.unit_price_minor/discount_minor→*_mtk`, `due_collection.amount_minor→amount_mtk`, `redemption.cash_minor→cash_mtk`. Seed CSV loads as `round(price*1000)`.

#### M-02 `sku` units (P8, seed #8)
```sql
ALTER TABLE app.sku
  ADD COLUMN base_unit   app.qty_unit NOT NULL DEFAULT 'stick',   -- Cigarette/Bidi: stick; Lighter: piece; Match: dozen (Q8 — confirm)
  ADD COLUMN pack_unit   app.qty_unit NOT NULL DEFAULT 'pack',    -- what the SR counts: pack/box/dozen
  ADD COLUMN base_per_pack int NOT NULL DEFAULT 1,                -- = pack_size for cig/bidi; 1 for lighter box, match dozen (confirm)
  ADD COLUMN report_unit app.qty_unit,                             -- unit used on dashboards if different from base (TSO shows lighter in box)
  ADD COLUMN report_factor numeric(12,6) NOT NULL DEFAULT 1,       -- base → report unit
  ADD COLUMN entry_unit_default app.qty_unit NOT NULL DEFAULT 'pack',
  ADD COLUMN category_id bigint, ADD COLUMN brand_id bigint, ADD COLUMN segment_id bigint; -- denormalised, maintained by trigger from variant
```
ASSUMPTION: SRs enter **packs** (the memo and the stock issue are in packs in the field) and STD is reported in **sticks** (docs/10 "sticks (cigarette)"). `qty_base = qty_entered × base_per_pack` when `unit_entered = pack_unit`, `× 1` when `unit_entered = base_unit`. Mixed entry is allowed per line (loose sticks exist in the trade). Confirm with the business before Phase 2 (Q8).

#### M-03 `sku_price` effective dating + uniqueness
```sql
ALTER TABLE app.sku_price ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN created_by bigint,
  ADD COLUMN source text NOT NULL DEFAULT 'admin',          -- admin | migration
  ADD CONSTRAINT sku_price_uniq UNIQUE (sku_id, price_type, valid_from),
  ADD CONSTRAINT sku_price_no_overlap EXCLUDE USING gist (sku_id WITH =, price_type WITH =, daterange(valid_from, valid_to, '[)') WITH &&),
  ADD CONSTRAINT sku_price_nonneg CHECK (amount_mtk >= 0);
CREATE INDEX ON app.sku_price (sku_id, price_type, valid_from DESC);
```

#### M-04 `sales_plan` effective dating + audit
```sql
ALTER TABLE app.sales_plan DROP CONSTRAINT sales_plan_pkey;
ALTER TABLE app.sales_plan ADD COLUMN id bigserial PRIMARY KEY, ADD COLUMN valid_from date NOT NULL DEFAULT current_date, ADD COLUMN valid_to date,
  ADD COLUMN created_by bigint, ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT sales_plan_no_overlap EXCLUDE USING gist (zone_id WITH =, sku_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&);
```

#### M-05 `visit` v2 (partitioned; context; split verdicts; integrity)
```sql
CREATE TABLE app.visit (
  id bigint GENERATED ALWAYS AS IDENTITY,
  -- @PROV block
  client_uuid uuid NOT NULL, device_id bigint, app_version text, captured_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, captured_offline boolean,
  business_date date NOT NULL, business_date_server date GENERATED ALWAYS AS ((captured_at AT TIME ZONE 'Asia/Dhaka')::date) STORED,
  flags text[] NOT NULL DEFAULT '{}',
  -- who / where / what
  kind app.visit_kind NOT NULL DEFAULT 'sr_call',
  user_id bigint NOT NULL REFERENCES app.app_user(id),
  outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  route_id bigint NOT NULL REFERENCES app.route(id),                 -- route being worked (seed #3)
  route_assignment_id bigint REFERENCES app.route_assignment(id),     -- NULL => flag 'unassigned_route'
  zone_id bigint NOT NULL,                                            -- denormalised at capture (P3)
  started_at timestamptz NOT NULL, ended_at timestamptz,
  -- geo: device side
  open_fix_id bigint,                                                 -- -> geo_fix (M-17), the fix used for the check
  device_geo_verdict text NOT NULL,                                   -- in_range | out_of_range | no_fix | mocked
  device_distance_m double precision, radius_m_used int, config_version int,
  gps_retry_count smallint NOT NULL DEFAULT 0,
  device_geo_valid boolean NOT NULL DEFAULT false,
  -- geo: server side (authoritative; written once by ingest, P2 exception)
  server_distance_m double precision, server_geo_valid boolean, server_checked_at timestamptz,
  geo_mismatch boolean GENERATED ALWAYS AS (server_geo_valid IS NOT NULL AND server_geo_valid <> device_geo_valid) STORED,
  plausibility_flags text[] NOT NULL DEFAULT '{}',                   -- teleport | zero_jitter | perfect_accuracy | single_point_route | mock
  suspicion_score smallint,                                           -- 0..100, server computed (fraud lens defines weights)
  -- force sale
  photo_validated boolean NOT NULL DEFAULT false, force_reason force_reason, force_photo_id bigint,
  -- outcome
  is_zero_sale boolean NOT NULL DEFAULT false,
  qc_completed_at timestamptz,                                        -- edit lock
  suggestion_snapshot jsonb,                                          -- {sku_id: suggested_qty_base} shown (Q6)
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date),
  UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.visit (route_id, business_date); CREATE INDEX ON app.visit (outlet_id, business_date);
CREATE INDEX ON app.visit (user_id, business_date); CREATE INDEX ON app.visit (received_at);
CREATE INDEX ON app.visit (business_date) WHERE cardinality(plausibility_flags) > 0;
-- monthly partitions app.visit_y2026m10 … created by job app.ensure_partitions()
```
Because of partitioning, cross-partition FKs to `visit` are replaced by `(visit_id, business_date)` composite FKs or by `visit_client_uuid` + registry lookup. Children below use `visit_id bigint, visit_business_date date` + `visit_client_uuid`.

#### M-06 `memo` v2
```sql
CREATE TABLE app.memo (
  id bigint GENERATED ALWAYS AS IDENTITY,
  -- @PROV …
  client_uuid uuid NOT NULL, device_id bigint, app_version text, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
  sync_batch_id bigint, captured_offline boolean, business_date date NOT NULL,
  business_date_server date GENERATED ALWAYS AS ((captured_at AT TIME ZONE 'Asia/Dhaka')::date) STORED, flags text[] NOT NULL DEFAULT '{}',
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL,
  outlet_id bigint NOT NULL, user_id bigint NOT NULL, route_id bigint NOT NULL, zone_id bigint NOT NULL,
  entry_source app.entry_source NOT NULL DEFAULT 'app_offline',
  memo_no text NOT NULL,                      -- device-composed, printed: '<username>-<yyMMdd>-<seq>'  (D-04)
  memo_serial bigint,                         -- server sequence (continuity with Apsis series if Q5 says so)
  printed_at timestamptz, print_count smallint NOT NULL DEFAULT 0,
  price_list_date date NOT NULL,              -- the valid_from of the outlet price list applied (P3)
  gross_mtk bigint NOT NULL DEFAULT 0, discount_mtk bigint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0,
  round_adj_mtk bigint NOT NULL DEFAULT 0,    -- printed_total_paisa*10 - net_mtk (P5)
  is_credit boolean NOT NULL DEFAULT false, paid_mtk bigint NOT NULL DEFAULT 0, due_mtk bigint NOT NULL DEFAULT 0,
  line_count smallint NOT NULL DEFAULT 0, is_zero_memo boolean GENERATED ALWAYS AS (line_count = 0) STORED,
  status text NOT NULL DEFAULT 'active',      -- active | superseded | void
  supersedes_memo_id bigint, supersedes_client_uuid uuid, edit_reason_code text, edit_fix_id bigint,
  superseded_at timestamptz,                  -- server enrichment when a newer memo lands
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (memo_no, business_date),
  CHECK (net_mtk = gross_mtk - discount_mtk), CHECK (paid_mtk + due_mtk = net_mtk), CHECK (due_mtk >= 0 AND paid_mtk >= 0),
  CHECK (is_credit = (due_mtk > 0))
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo (outlet_id, business_date); CREATE INDEX ON app.memo (route_id, business_date); CREATE INDEX ON app.memo (visit_id, business_date);
CREATE INDEX ON app.memo (outlet_id) WHERE due_mtk > 0 AND status = 'active';     -- open dues lookup
CREATE SEQUENCE app.memo_serial_seq;   -- START set at import to (max Apsis memo no + 1) if Q5 = continue
```

#### M-07 `memo_line` v2 (P1, P8)
```sql
CREATE TABLE app.memo_line (
  id bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid uuid NOT NULL, business_date date NOT NULL,
  memo_id bigint NOT NULL, memo_client_uuid uuid NOT NULL, line_no smallint NOT NULL,
  sku_id bigint NOT NULL REFERENCES app.sku(id),
  qty_entered int NOT NULL CHECK (qty_entered > 0), unit_entered app.qty_unit NOT NULL,
  pack_factor int NOT NULL CHECK (pack_factor > 0),          -- base units per entered unit, at capture
  qty_base int GENERATED ALWAYS AS (qty_entered * pack_factor) STORED,
  price_type price_type NOT NULL DEFAULT 'outlet', price_valid_from date NOT NULL,
  unit_price_mtk bigint NOT NULL CHECK (unit_price_mtk >= 0),  -- per entered unit
  gross_mtk bigint GENERATED ALWAYS AS (qty_entered * unit_price_mtk) STORED,
  discount_mtk bigint NOT NULL DEFAULT 0 CHECK (discount_mtk >= 0),
  net_mtk bigint GENERATED ALWAYS AS (qty_entered * unit_price_mtk - discount_mtk) STORED,
  is_free boolean NOT NULL DEFAULT false,                    -- free sample / promo free goods (W17)
  offer_id bigint REFERENCES app.offer(id),
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (memo_id, line_no, business_date),
  CHECK (NOT is_free OR unit_price_mtk = 0)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo_line (memo_id, business_date); CREATE INDEX ON app.memo_line (sku_id, business_date);
```

#### M-08 `qc_entry` v2 and memo-level offers
```sql
CREATE TABLE app.qc_entry (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL, memo_id bigint, memo_client_uuid uuid,
  sku_id bigint NOT NULL REFERENCES app.sku(id),
  production_fault_qty int NOT NULL DEFAULT 0 CHECK (production_fault_qty >= 0),
  transport_fault_qty int NOT NULL DEFAULT 0 CHECK (transport_fault_qty >= 0),
  fault_unit app.qty_unit NOT NULL, pack_factor int NOT NULL DEFAULT 1,
  replaced boolean, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (visit_id, sku_id, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE app.memo_offer (                      -- offers applied at memo level (DRP discount, basket promos)
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  memo_id bigint NOT NULL, memo_client_uuid uuid NOT NULL, offer_id bigint NOT NULL REFERENCES app.offer(id),
  discount_mtk bigint NOT NULL DEFAULT 0, free_sku_id bigint, free_qty_base int, basis jsonb,   -- basis = what triggered it
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
```

#### M-09 Surveys: definitions + idempotent responses
```sql
CREATE TABLE app.survey (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name_bn text, name_en text, version int NOT NULL DEFAULT 1,
  audience role[] NOT NULL, valid_from date NOT NULL, valid_to date, /*@AUDIT*/ created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint, version_no int NOT NULL DEFAULT 1, status text NOT NULL DEFAULT 'active');
CREATE TABLE app.survey_question (id bigserial PRIMARY KEY, survey_id bigint NOT NULL REFERENCES app.survey(id), question_key text NOT NULL,
  prompt_bn text, prompt_en text, answer_type text NOT NULL,  -- yes_no | single | multi | number | text | photo
  options jsonb, requires_photo boolean NOT NULL DEFAULT false, sort int, UNIQUE (survey_id, question_key));
CREATE TABLE app.survey_response (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL, survey_id bigint NOT NULL, survey_version int NOT NULL,
  question_id bigint NOT NULL REFERENCES app.survey_question(id), answer jsonb, photo_media_id bigint,
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (visit_id, question_id, survey_version, business_date)
) PARTITION BY RANGE (business_date);
```

#### M-10 `drp_collection` v2
```sql
CREATE TABLE app.drp_collection (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL, outlet_id bigint NOT NULL,
  kind_code text NOT NULL,                  -- cfg list cfg.drp.kinds: empty_pack | slide | …
  brand_id bigint REFERENCES app.product_brand(id), sku_id bigint REFERENCES app.sku(id),
  qty int NOT NULL CHECK (qty > 0), offer_id bigint REFERENCES app.offer(id), discount_mtk bigint NOT NULL DEFAULT 0,
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
```

#### M-11 Stock as events
```sql
CREATE TABLE app.stock_movement (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  user_id bigint NOT NULL, zone_id bigint NOT NULL, route_id bigint,                 -- route NULL when SR covers several
  kind text NOT NULL CHECK (kind IN ('issue','return','adjustment')),
  sku_id bigint NOT NULL REFERENCES app.sku(id),
  qty_entered int NOT NULL CHECK (qty_entered <> 0), unit_entered app.qty_unit NOT NULL, pack_factor int NOT NULL,
  qty_base int GENERATED ALWAYS AS (qty_entered * pack_factor) STORED,
  printed_at timestamptz, note text, device_id bigint, app_version text,
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, captured_offline boolean, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
-- app.stock_issue (user, date, sku, issued, returned) is DROPPED; dw.fact_daily_user_sku (§4) replaces it.
```

#### M-12 Attendance as events; due collection v2
```sql
CREATE TABLE app.attendance_event (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  user_id bigint NOT NULL, kind text NOT NULL CHECK (kind IN ('check_in','check_out')),
  at timestamptz NOT NULL, fix_id bigint,                      -- -> geo_fix
  device_id bigint, app_version text, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, captured_offline boolean, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
-- app.attendance stays as the derived "one row per user per day" (first check_in, last check_out), maintained by ingest trigger.
ALTER TABLE app.attendance ADD COLUMN check_in_fix_id bigint, ADD COLUMN check_out_fix_id bigint, ADD COLUMN check_out_lat double precision, ADD COLUMN check_out_lng double precision,
  ADD COLUMN check_in_event_id bigint, ADD COLUMN check_out_event_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}';

CREATE TABLE app.due_collection (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  outlet_id bigint NOT NULL, user_id bigint NOT NULL, route_id bigint,
  against_memo_id bigint, against_memo_client_uuid uuid, against_memo_business_date date,
  amount_mtk bigint NOT NULL CHECK (amount_mtk > 0), is_full_settlement boolean NOT NULL, payment_mode text NOT NULL DEFAULT 'cash',
  outstanding_before_mtk bigint,                               -- device's view; server recomputes into flags
  collected_at timestamptz NOT NULL, fix_id bigint,
  device_id bigint, app_version text, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, captured_offline boolean, flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.due_collection (outlet_id, business_date); CREATE INDEX ON app.due_collection (against_memo_id);
```

#### M-13 `visit` context columns (already in M-05) + `outlet_suggestion` link; `route_assignment` resolution rule
Ingest resolves `route_assignment_id` = the assignment row where `route_id` matches, `user_id` matches, `business_date ∈ [valid_from, valid_to)`. None found → keep `route_id` as sent, add flag `unassigned_route` (accept, never reject: alternate-day cover is legitimate and the SR cannot be blocked offline).

#### M-14 `route_assignment` v2 (SCD, audit)
```sql
ALTER TABLE app.route_assignment
  ADD COLUMN assignment_kind text NOT NULL DEFAULT 'primary',   -- primary | cover | ss (sales supervisor)
  ADD COLUMN reason text, ADD COLUMN created_by bigint, ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN ended_by bigint, ADD COLUMN ended_at timestamptz,
  ADD CONSTRAINT route_assignment_no_overlap EXCLUDE USING gist (route_id WITH =, user_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&);
CREATE INDEX ON app.route_assignment (user_id, valid_from, valid_to); CREATE INDEX ON app.route_assignment (route_id, valid_from, valid_to);
-- Business rule (DQ-21): at most one 'primary' per route per day -> second EXCLUDE on (route_id, assignment_kind='primary').
```

#### M-15 Outlet change requests & photos v2
```sql
ALTER TABLE app.outlet_change_request
  ALTER COLUMN client_uuid SET NOT NULL, ALTER COLUMN client_uuid SET DEFAULT gen_random_uuid(),
  ADD COLUMN route_id bigint REFERENCES app.route(id), ADD COLUMN business_date date NOT NULL DEFAULT (now() AT TIME ZONE 'Asia/Dhaka')::date,
  ADD COLUMN request_fix_id bigint, ADD COLUMN requester_photo_media_id bigint,
  ADD COLUMN verified_at timestamptz, ADD COLUMN verifier_payload jsonb, ADD COLUMN verifier_fix_id bigint, ADD COLUMN verifier_photo_media_id bigint,
  ADD COLUMN approved_at timestamptz, ADD COLUMN rejected_by bigint, ADD COLUMN rejected_at timestamptz, ADD COLUMN rejection_reason text,
  ADD COLUMN resulting_outlet_id bigint REFERENCES app.outlet(id),   -- for type=new, set on approval
  ADD COLUMN device_id bigint, ADD COLUMN app_version text, ADD COLUMN captured_at timestamptz, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}';
CREATE INDEX ON app.outlet_change_request (status, type); CREATE INDEX ON app.outlet_change_request (outlet_id);
CREATE TABLE app.outlet_change_event (id bigserial PRIMARY KEY, request_id bigint NOT NULL REFERENCES app.outlet_change_request(id), client_uuid uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  event text NOT NULL, actor_id bigint NOT NULL, at timestamptz NOT NULL DEFAULT now(), payload jsonb);   -- requested | verified | rejected | approved | reopened

ALTER TABLE app.outlet_photo
  ALTER COLUMN outlet_id DROP NOT NULL,
  ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN change_request_id bigint REFERENCES app.outlet_change_request(id),
  ADD COLUMN visit_id bigint, ADD COLUMN visit_business_date date, ADD COLUMN media_id bigint, ADD COLUMN fix_id bigint, ADD COLUMN taken_by bigint,
  ADD COLUMN business_date date, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint,
  ADD CONSTRAINT outlet_photo_owner CHECK (outlet_id IS NOT NULL OR change_request_id IS NOT NULL);
-- purpose values (cfg list): capture | base_update | info | force_sale | manual_override | verification
```

#### M-16 Outlet v2: kind, extra, SCD histories for route, location, classification
```sql
ALTER TABLE app.outlet
  ADD COLUMN outlet_kind text NOT NULL DEFAULT 'retail',       -- retail | wholesale (W12, Q17)
  ADD COLUMN extra jsonb NOT NULL DEFAULT '{}',                 -- "business"/"additional detail" web sections (W01) until field list confirmed
  ADD COLUMN location_source text, ADD COLUMN location_updated_at timestamptz, ADD COLUMN location_accuracy_m double precision,
  ADD COLUMN phone_hash text,                                   -- sha256(normalised phone) for de-dupe/matching without exposing PII
  ADD COLUMN created_by bigint, ADD COLUMN updated_by bigint, ADD COLUMN version int NOT NULL DEFAULT 1,
  ADD COLUMN apsis_id text;                                     -- crosswalk convenience (M-42)
CREATE INDEX ON app.outlet (cluster_id); CREATE INDEX ON app.outlet (channel, sub_channel_id); CREATE INDEX ON app.outlet (status);
CREATE INDEX ON app.outlet USING gist (ll_to_earth(latitude, longitude)) WHERE latitude IS NOT NULL;   -- periphery/radius queries (needs earthdistance) — or PostGIS if adopted

CREATE TABLE app.outlet_route_history (     -- SCD2: which route (and therefore zone) an outlet belonged to, by day
  id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id), route_id bigint NOT NULL REFERENCES app.route(id),
  valid_from date NOT NULL, valid_to date, changed_by bigint, change_request_id bigint, reason text, created_at timestamptz NOT NULL DEFAULT now(),
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
CREATE TABLE app.outlet_location_history (
  id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id), latitude double precision NOT NULL, longitude double precision NOT NULL,
  accuracy_m double precision, source text NOT NULL,            -- migration | capture | force_sale_photo | base_update | manual_override | web_edit
  source_ref_uuid uuid, changed_by bigint, fix_id bigint, valid_from timestamptz NOT NULL, valid_to timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ON app.outlet_location_history (outlet_id, valid_from DESC);
CREATE TABLE app.outlet_class_history (     -- channel / sub-channel (Astha tier) / geo-class by period
  id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id), channel channel_type, sub_channel_id bigint, geo_classification geo_class,
  valid_from date NOT NULL, valid_to date, changed_by bigint, reason text, EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
-- Triggers: any UPDATE of outlet.route_id / lat,lng / channel,sub_channel,geo_classification closes the open history row and opens a new one.
```

#### M-17 `geo_fix` — every GPS sample, one table (P4, docs/05 plausibility)
```sql
CREATE TABLE app.geo_fix (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  user_id bigint NOT NULL, device_id bigint,
  purpose text NOT NULL,                     -- attendance_in | attendance_out | visit_open | force_sale | memo_edit | due_collection | outlet_capture | base_update | verification | refresh
  ref_type text, ref_client_uuid uuid,       -- the record this fix belongs to
  fixed_at timestamptz NOT NULL,             -- fix timestamp from the provider
  captured_at timestamptz NOT NULL,          -- when the app read it
  lat double precision NOT NULL, lng double precision NOT NULL, accuracy_m double precision, altitude_m double precision, speed_mps double precision, bearing double precision,
  provider text, is_mock boolean NOT NULL DEFAULT false, fix_age_ms int, satellites smallint,
  -- server enrichment
  prev_fix_id bigint, dist_from_prev_m double precision, secs_from_prev int, implied_speed_mps double precision,
  plausibility_flags text[] NOT NULL DEFAULT '{}',
  received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint,
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date),
  CHECK (lat BETWEEN -90 AND 90 AND lng BETWEEN -180 AND 180)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.geo_fix (user_id, fixed_at); CREATE INDEX ON app.geo_fix (ref_client_uuid);
CREATE INDEX ON app.geo_fix (business_date) WHERE is_mock;
CREATE TABLE app.device_integrity (          -- one row per login / bundle download
  id bigserial PRIMARY KEY, device_id bigint NOT NULL REFERENCES app.device(id), user_id bigint NOT NULL, at timestamptz NOT NULL DEFAULT now(),
  business_date date NOT NULL, mock_location_app_present boolean, developer_options boolean, rooted_hint boolean, play_integrity_verdict text,
  play_integrity_raw jsonb, os_version text, app_version text, time_skew_s int);  -- time_skew = device clock − server clock at request
```

#### M-18 Geography: codes, SCD on route, house/zone fix
```sql
ALTER TABLE app.route ADD COLUMN sequence_no int, ADD COLUMN visit_days_mask smallint,   -- bit per DOW (Sat=1..Fri=64); 127 = Daily; derived from visit_days text
  ADD COLUMN valid_from date NOT NULL DEFAULT '2000-01-01', ADD COLUMN valid_to date, ADD COLUMN apsis_id text, /*@AUDIT*/ ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN created_by bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_by bigint, ADD COLUMN version int NOT NULL DEFAULT 1, ADD COLUMN status text NOT NULL DEFAULT 'active';
CREATE TABLE app.route_history (id bigserial PRIMARY KEY, route_id bigint NOT NULL, zone_id bigint NOT NULL, name text, visit_days_mask smallint, valid_from date NOT NULL, valid_to date, changed_by bigint,
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
ALTER TABLE app.zone ADD COLUMN dep_id text, ADD COLUMN apsis_id text; -- + @AUDIT on wing/division/territory/house/zone/cluster
-- zone.house_id nullable today; DQ-22 flags zones without a house (docs/01 says house is on the spine).
```

#### M-19 Reference audit columns
`@AUDIT` block added to: `wing, division, territory, house, zone, cluster, product_category, product_segment, product_brand, product_variant, sku, sub_channel, app_user, device`. `app_user` additionally: `employee_code text UNIQUE, email text, home_zone_id bigint, locale text DEFAULT 'bn', pii_access boolean DEFAULT false, apsis_id text`.

#### M-20 Offers / promotions (seed #5; memo_line.offer_id dangles)
```sql
CREATE TABLE app.offer (
  id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name_bn text, name_en text,
  group_code text NOT NULL,                        -- the ~22 promotion groups behind the Discount Report
  offer_type text NOT NULL,                        -- pct_discount | amount_discount | free_qty | drp_discount | free_sample | bundle
  level text NOT NULL,                             -- line | memo
  rule jsonb NOT NULL,                             -- {"buy_sku":..,"buy_qty_base":..,"free_sku":..,"free_qty_base":..,"pct":..,"amount_mtk":..,"per":"pack"}
  stacking text NOT NULL DEFAULT 'exclusive',      -- exclusive | stackable
  valid_from date NOT NULL, valid_to date, /*@AUDIT*/ created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint, version int NOT NULL DEFAULT 1, status text NOT NULL DEFAULT 'active');
CREATE TABLE app.offer_product (offer_id bigint NOT NULL REFERENCES app.offer(id), product_level text NOT NULL, product_id bigint NOT NULL, role text NOT NULL DEFAULT 'qualifier', PRIMARY KEY (offer_id, product_level, product_id, role)); -- qualifier | reward
CREATE TABLE app.offer_scope   (offer_id bigint NOT NULL REFERENCES app.offer(id), node_type text NOT NULL, node_id bigint NOT NULL, PRIMARY KEY (offer_id, node_type, node_id)); -- geography or channel scope
CREATE TABLE app.offer_version (id bigserial PRIMARY KEY, offer_id bigint NOT NULL, version int NOT NULL, snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, UNIQUE (offer_id, version));
```
Memo lines store `offer_id` + the memo stores `offer_version` implicitly via `price_list_date`/`config_version`; the Discount Report groups by `offer.group_code`.

#### M-21 Targets v2: uniqueness, non-negativity, versions, revision lines
```sql
ALTER TABLE app.target
  ADD CONSTRAINT target_uniq UNIQUE (scope_type, scope_id, product_level, product_id, month),
  ADD CONSTRAINT target_nonneg CHECK (std_target >= 0 AND memo_target >= 0),          -- the −20 bug (PROJECT-CONTEXT)
  ADD COLUMN std_unit app.qty_unit NOT NULL DEFAULT 'stick',
  ADD COLUMN allocation_method text, ADD COLUMN parent_target_id bigint REFERENCES app.target(id),  -- how a route/SKU target was split (Q12)
  ADD COLUMN version int NOT NULL DEFAULT 1, ADD COLUMN source text NOT NULL DEFAULT 'admin',      -- admin | revision | migration | formula
  ADD COLUMN set_by bigint, ADD COLUMN set_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN revision_id bigint;
CREATE TABLE app.target_version (        -- every past value, so "what was the target on the 12th" is answerable
  id bigserial PRIMARY KEY, target_id bigint NOT NULL REFERENCES app.target(id), version int NOT NULL, std_target numeric(14,3) NOT NULL, memo_target int NOT NULL,
  valid_from timestamptz NOT NULL, valid_to timestamptz, changed_by bigint, revision_id bigint, UNIQUE (target_id, version));
ALTER TABLE app.target_revision ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN month date, ADD COLUMN requested_by bigint, ADD COLUMN reason text,
  ADD COLUMN current_level int NOT NULL DEFAULT 1, ADD COLUMN required_levels int NOT NULL DEFAULT 1, ADD COLUMN applied_at timestamptz;
CREATE TABLE app.target_revision_line (id bigserial PRIMARY KEY, revision_id bigint NOT NULL REFERENCES app.target_revision(id), target_id bigint REFERENCES app.target(id),
  product_level text NOT NULL, product_id bigint NOT NULL, old_std numeric(14,3), new_std numeric(14,3) CHECK (new_std >= 0), old_memo int, new_memo int CHECK (new_memo >= 0));
CREATE TABLE app.target_revision_event (id bigserial PRIMARY KEY, revision_id bigint NOT NULL REFERENCES app.target_revision(id), level int NOT NULL, actor_id bigint NOT NULL,
  decision text NOT NULL, comment text, at timestamptz NOT NULL DEFAULT now());   -- submitted | approved | rejected | returned
```

#### M-22 Programs: Astha / Superstar / enrolments
```sql
CREATE TABLE app.program (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, kind text NOT NULL, -- astha | diamond_league | superstar | campaign
  name_bn text, name_en text, period_kind text NOT NULL, -- month | quarter | custom
  rules jsonb NOT NULL DEFAULT '{}', valid_from date NOT NULL, valid_to date, /*@AUDIT*/ created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint, version int NOT NULL DEFAULT 1, status text NOT NULL DEFAULT 'active');
CREATE TABLE app.program_period (id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES app.program(id), label text NOT NULL, period_start date NOT NULL, period_end date NOT NULL, UNIQUE (program_id, period_start));
CREATE TABLE app.program_enrolment (id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES app.program(id), outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  period_id bigint REFERENCES app.program_period(id), tier text, category text, incentive_slab text, base_target numeric(14,3), criteria jsonb, criteria_met boolean,
  valid_from date NOT NULL, valid_to date, enrolled_by bigint, created_at timestamptz NOT NULL DEFAULT now(),
  EXCLUDE USING gist (program_id WITH =, outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
CREATE TABLE app.program_outlet_target (    -- Astha per-brand STD + memo target; Superstar STD & memo target
  id bigserial PRIMARY KEY, enrolment_id bigint NOT NULL REFERENCES app.program_enrolment(id), product_level text NOT NULL, product_id bigint NOT NULL,
  std_target numeric(14,3) NOT NULL CHECK (std_target >= 0), std_unit app.qty_unit NOT NULL DEFAULT 'stick', memo_target int NOT NULL DEFAULT 0 CHECK (memo_target >= 0),
  version int NOT NULL DEFAULT 1, set_by bigint, set_at timestamptz NOT NULL DEFAULT now(), UNIQUE (enrolment_id, product_level, product_id));
```

#### M-23 Diamond League: program config, earn rules, catalog, ledger v2, redemption lines
```sql
CREATE TABLE app.loyalty_earn_rule (id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES app.program(id), rule jsonb NOT NULL,  -- confirm (Q13): points per memo? per Tk? per target hit?
  valid_from date NOT NULL, valid_to date, created_by bigint, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app.gift_catalog (id bigserial PRIMARY KEY, program_id bigint NOT NULL REFERENCES app.program(id), code text NOT NULL, name_bn text, name_en text,
  kind text NOT NULL,                       -- gift | cash_back
  points_cost int CHECK (points_cost >= 0), cash_rate_mtk_per_point bigint, max_points int, image_media_id bigint,
  valid_from date NOT NULL, valid_to date, status text NOT NULL DEFAULT 'active', UNIQUE (program_id, code, valid_from));
ALTER TABLE app.loyalty_ledger
  ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN program_id bigint REFERENCES app.program(id), ADD COLUMN period_id bigint REFERENCES app.program_period(id),
  ADD COLUMN business_date date NOT NULL DEFAULT (now() AT TIME ZONE 'Asia/Dhaka')::date, ADD COLUMN user_id bigint,
  ADD COLUMN source_type text NOT NULL DEFAULT 'manual',  -- memo | redemption | migration_opening | adjustment | expiry
  ADD COLUMN source_id text, ADD COLUMN balance_after int,
  ADD CONSTRAINT loyalty_ledger_source_uniq UNIQUE (source_type, source_id);     -- server earns are idempotent per source
CREATE INDEX ON app.loyalty_ledger (outlet_id, program_id, at);
ALTER TABLE app.redemption ADD COLUMN program_id bigint, ADD COLUMN period_id bigint, ADD COLUMN user_id bigint, ADD COLUMN points_balance_before int, ADD COLUMN fix_id bigint,
  ADD COLUMN status text NOT NULL DEFAULT 'confirmed',    -- confirmed | verified (photo) | reversed
  ADD COLUMN device_id bigint, ADD COLUMN app_version text, ADD COLUMN captured_at timestamptz, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}';
ALTER TABLE app.redemption DROP COLUMN gift;              -- replaced by lines
CREATE TABLE app.redemption_line (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, redemption_id bigint NOT NULL REFERENCES app.redemption(id), redemption_client_uuid uuid NOT NULL,
  gift_id bigint NOT NULL REFERENCES app.gift_catalog(id), qty int NOT NULL CHECK (qty > 0), points_each int NOT NULL, cash_mtk bigint NOT NULL DEFAULT 0, UNIQUE (redemption_id, gift_id));
```

#### M-24 Gift assignment (Astha "TSO portal" choice) and gift photos v2
```sql
CREATE TABLE app.gift_assignment (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, program_id bigint NOT NULL REFERENCES app.program(id), period_id bigint REFERENCES app.program_period(id),
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), gift_id bigint REFERENCES app.gift_catalog(id), gift_text text, qty int NOT NULL DEFAULT 1,
  chosen_by bigint, chosen_at timestamptz NOT NULL DEFAULT now(), status text NOT NULL DEFAULT 'chosen',   -- chosen | handed_over | verified | cancelled
  UNIQUE (program_id, period_id, outlet_id));
ALTER TABLE app.gift_photo ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN gift_assignment_id bigint REFERENCES app.gift_assignment(id), ADD COLUMN redemption_id bigint REFERENCES app.redemption(id),
  ADD COLUMN media_id bigint, ADD COLUMN fix_id bigint, ADD COLUMN user_id bigint, ADD COLUMN business_date date, ADD COLUMN captured_at timestamptz, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}',
  ADD CONSTRAINT gift_photo_one_per_assignment UNIQUE (gift_assignment_id), ADD CONSTRAINT gift_photo_target CHECK (gift_assignment_id IS NOT NULL OR redemption_id IS NOT NULL);
```

#### M-25 Tasks v2
```sql
ALTER TABLE app.task ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN route_id bigint REFERENCES app.route(id), ADD COLUMN type_code text,  -- cfg list supersedes enum
  ADD COLUMN resolved_by bigint, ADD COLUMN resolution_note text, ADD COLUMN business_date date, ADD COLUMN source text NOT NULL DEFAULT 'app', ADD COLUMN visit_plan_outlet_id bigint,
  ADD COLUMN device_id bigint, ADD COLUMN captured_at timestamptz, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}';
CREATE TABLE app.task_event (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, task_id bigint REFERENCES app.task(id), task_client_uuid uuid NOT NULL,
  event text NOT NULL, actor_id bigint NOT NULL, at timestamptz NOT NULL, note text, fix_id bigint, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint); -- assigned | resolved | reopened | cancelled
CREATE INDEX ON app.task (assignee_id, status); CREATE INDEX ON app.task (outlet_id);
```

#### M-26 Distribution check as header + lines; M-27 assessments; M-28 price compliance
```sql
CREATE TABLE app.distribution_check (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, visit_id bigint, visit_business_date date, visit_client_uuid uuid NOT NULL,
  assessor_id bigint NOT NULL, outlet_id bigint NOT NULL, route_id bigint, posm_present boolean, posm_detail jsonb, business_date date NOT NULL,
  device_id bigint, app_version text, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, flags text[] NOT NULL DEFAULT '{}');
CREATE TABLE app.distribution_check_line (id bigserial PRIMARY KEY, check_id bigint NOT NULL REFERENCES app.distribution_check(id), brand_id bigint NOT NULL REFERENCES app.product_brand(id),
  present boolean NOT NULL, oos boolean NOT NULL, UNIQUE (check_id, brand_id));

CREATE TABLE app.assessment_rubric (id bigserial PRIMARY KEY, code text NOT NULL, version int NOT NULL, kind text NOT NULL, -- joint_call | retailer_questionnaire
  name_bn text, name_en text, valid_from date NOT NULL, valid_to date, UNIQUE (code, version));
CREATE TABLE app.assessment_criterion (id bigserial PRIMARY KEY, rubric_id bigint NOT NULL REFERENCES app.assessment_rubric(id), group_code text NOT NULL, criterion_key text NOT NULL,
  prompt_bn text, prompt_en text, answer_type text NOT NULL, -- stars_1_5 | yes_no | text
  weight numeric(6,3) NOT NULL DEFAULT 1, sort int, UNIQUE (rubric_id, criterion_key));
ALTER TABLE app.call_assessment ADD COLUMN visit_id bigint, ADD COLUMN visit_business_date date, ADD COLUMN visit_client_uuid uuid, ADD COLUMN rubric_id bigint REFERENCES app.assessment_rubric(id),
  ADD COLUMN visit_plan_outlet_id bigint, ADD COLUMN route_id bigint, ADD COLUMN fix_id bigint, ADD COLUMN total_score numeric(8,3), ADD COLUMN max_score numeric(8,3), ADD COLUMN delegate_task boolean,
  ADD COLUMN device_id bigint, ADD COLUMN app_version text, ADD COLUMN captured_at timestamptz, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN sync_batch_id bigint, ADD COLUMN flags text[] NOT NULL DEFAULT '{}';
CREATE TABLE app.call_assessment_answer (id bigserial PRIMARY KEY, assessment_id bigint NOT NULL REFERENCES app.call_assessment(id), criterion_id bigint NOT NULL REFERENCES app.assessment_criterion(id),
  score smallint, answer jsonb, UNIQUE (assessment_id, criterion_id));   -- flattened from scores/answers jsonb at ingest so BI never parses JSON

CREATE TABLE app.price_compliance_check (   -- exists in current AMO sync counts; fields are ASSUMPTION, confirm
  id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, visit_id bigint, visit_business_date date, visit_client_uuid uuid, assessor_id bigint NOT NULL, outlet_id bigint NOT NULL,
  sku_id bigint NOT NULL REFERENCES app.sku(id), observed_price_mtk bigint, listed_price_mtk bigint, is_compliant boolean, note text, business_date date NOT NULL,
  device_id bigint, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, flags text[] NOT NULL DEFAULT '{}');
```

#### M-29 Outlet suggestion (Q6 hook)
```sql
CREATE TABLE app.outlet_suggestion (outlet_id bigint NOT NULL REFERENCES app.outlet(id), sku_id bigint NOT NULL REFERENCES app.sku(id), business_date date NOT NULL,
  suggested_qty_base int NOT NULL, method text NOT NULL, inputs jsonb, computed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (outlet_id, sku_id, business_date));
```

#### M-30 Ingest registry, quarantine, conflicts
```sql
CREATE TABLE app.ingest_registry (             -- global idempotency point across all partitioned tables
  client_uuid uuid PRIMARY KEY, record_type text NOT NULL, server_id bigint, business_date date, payload_sha256 bytea NOT NULL,
  user_id bigint NOT NULL, device_id bigint, first_batch_id bigint NOT NULL, first_seen_at timestamptz NOT NULL DEFAULT now(), seen_count int NOT NULL DEFAULT 1, state app.sync_state NOT NULL);
CREATE INDEX ON app.ingest_registry (first_batch_id); CREATE INDEX ON app.ingest_registry (record_type, business_date);
CREATE TABLE app.sync_rejected (                -- quarantine (P9, §6)
  id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL, sync_batch_id bigint NOT NULL, user_id bigint NOT NULL, device_id bigint,
  record_type text NOT NULL, client_uuid uuid NOT NULL, payload jsonb NOT NULL, reason_code text NOT NULL, reason_detail text, dq_rule text,
  received_at timestamptz NOT NULL DEFAULT now(), status text NOT NULL DEFAULT 'parked',  -- parked | retried | accepted | discarded | fixed_manually
  retry_count smallint NOT NULL DEFAULT 0, next_retry_at timestamptz, resolved_at timestamptz, resolved_by bigint, resolution_note text,
  PRIMARY KEY (id, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.sync_rejected (status, next_retry_at); CREATE INDEX ON app.sync_rejected (client_uuid); CREATE INDEX ON app.sync_rejected (user_id, business_date);
CREATE TABLE app.sync_conflict (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL, record_type text NOT NULL, first_payload_sha256 bytea NOT NULL, second_payload jsonb NOT NULL,
  second_batch_id bigint NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), reviewed_by bigint, reviewed_at timestamptz, decision text);
```

#### M-31 Sync batch v2, bundle downloads, media
```sql
ALTER TABLE app.sync_batch ADD COLUMN batch_uuid uuid NOT NULL UNIQUE, ADD COLUMN business_date date NOT NULL DEFAULT (now() AT TIME ZONE 'Asia/Dhaka')::date,
  ADD COLUMN app_version text, ADD COLUMN network_type text, ADD COLUMN battery_pct smallint, ADD COLUMN payload_bytes int, ADD COLUMN compressed_bytes int,
  ADD COLUMN device_counts jsonb, ADD COLUMN accepted_counts jsonb, ADD COLUMN rejected_count int NOT NULL DEFAULT 0, ADD COLUMN conflict_count int NOT NULL DEFAULT 0,
  ADD COLUMN processing_ms int, ADD COLUMN response jsonb, ADD COLUMN replayed_count int NOT NULL DEFAULT 0,
  ADD COLUMN oldest_captured_at timestamptz, ADD COLUMN newest_captured_at timestamptz,   -- sync latency = uploaded_at − captured_at
  ADD COLUMN trigger text;                                                               -- sale_saved | manual | periodic | end_of_day | wifi_connected
CREATE INDEX ON app.sync_batch (user_id, uploaded_at); CREATE INDEX ON app.sync_batch (business_date);
CREATE TABLE app.bundle_download (id bigserial PRIMARY KEY, user_id bigint NOT NULL, device_id bigint, at timestamptz NOT NULL DEFAULT now(), business_date date NOT NULL,
  is_full boolean NOT NULL, since timestamptz, route_ids bigint[] NOT NULL, bytes int, compressed_bytes int, duration_ms int, config_version int, app_version text, network_type text);
CREATE INDEX ON app.bundle_download (business_date, user_id);
CREATE TABLE app.media_object (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL, device_id bigint, purpose text NOT NULL, ref_type text, ref_client_uuid uuid,
  blob_url text NOT NULL, content_sha256 bytea NOT NULL, bytes int NOT NULL, width int, height int, mime text, captured_at timestamptz, uploaded_at timestamptz NOT NULL DEFAULT now(),
  network_type text, fix_id bigint, business_date date NOT NULL, state text NOT NULL DEFAULT 'stored', retention_class text NOT NULL DEFAULT 'operational'); -- stored | linked | orphan | archived | deleted
CREATE INDEX ON app.media_object (ref_client_uuid); CREATE INDEX ON app.media_object (content_sha256);
-- app.route_log becomes VIEW over bundle_download + sync_batch + route_day (Data Entry Log report, W19).
```

#### M-32 Day state: `route_day` (seed #10) and tracking actions
```sql
CREATE TABLE app.route_day (
  route_id bigint NOT NULL REFERENCES app.route(id), business_date date NOT NULL,
  zone_id bigint NOT NULL, planned boolean NOT NULL,                       -- route due today per visit_days_mask
  assigned_user_id bigint, acting_user_id bigint,                          -- primary assignee; who actually worked it
  state day_state NOT NULL DEFAULT 'not_started',
  logged_in_at timestamptz, first_check_in_at timestamptz, first_visit_at timestamptz, last_visit_at timestamptz, first_sync_at timestamptz, last_sync_at timestamptz,
  sales_submitted_at timestamptz, sales_submit_client_uuid uuid, submitted_with_dues boolean, dues_at_submit_mtk bigint,
  final_submitted_at timestamptz, final_submit_id bigint, reopened_at timestamptz, reopened_by bigint, reopen_reason text,
  target_outlets int, updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (route_id, business_date));
CREATE INDEX ON app.route_day (business_date, zone_id, state);
-- Entity of each state (resolves seed #10): not_started/logged_in/in_field/synced/sales_submitted belong to route_day; attendance to the user (attendance);
-- final_submitted is set on every route_day of the zone when final_submit lands. An SR on two routes produces two route_day rows; logged_in is set on both
-- when the bundle that contains both routes is downloaded.
CREATE TABLE app.tracking_action (id bigserial PRIMARY KEY, route_id bigint NOT NULL, business_date date NOT NULL, actor_id bigint NOT NULL, action_code text NOT NULL, note text, at timestamptz NOT NULL DEFAULT now()); -- W10, confirm actions
```

#### M-33 Leave; M-34 Final submit v2; M-35 Visit plans; M-36 Feedback
```sql
CREATE TABLE app.leave_application (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL, leave_type_code text NOT NULL, from_date date NOT NULL, to_date date NOT NULL,
  days numeric(4,1) NOT NULL CHECK (days > 0), reason text, status text NOT NULL DEFAULT 'pending', approver_role role, decided_by bigint, decided_at timestamptz, decision_comment text,
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, CHECK (to_date >= from_date));
CREATE TABLE app.leave_event (id bigserial PRIMARY KEY, leave_id bigint NOT NULL REFERENCES app.leave_application(id), event text NOT NULL, actor_id bigint NOT NULL, at timestamptz NOT NULL DEFAULT now(), comment text);

ALTER TABLE app.final_submit ADD COLUMN client_uuid uuid UNIQUE, ADD COLUMN route_count int, ADD COLUMN routes_set int, ADD COLUMN memo_count_at_submit int, ADD COLUMN net_mtk_at_submit bigint,
  ADD COLUMN reopened_by bigint, ADD COLUMN reopened_at timestamptz, ADD COLUMN reopen_reason text, ADD COLUMN resubmitted_at timestamptz, ADD COLUMN device_id bigint, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE app.final_submit_route (zone_id bigint NOT NULL, business_date date NOT NULL, route_id bigint NOT NULL, sr_user_id bigint, sr_name_snapshot text, memo_count int, net_mtk bigint, state_at_submit day_state,
  PRIMARY KEY (zone_id, business_date, route_id), FOREIGN KEY (zone_id, business_date) REFERENCES app.final_submit(zone_id, business_date));
CREATE TABLE app.final_submit_attempt (id bigserial PRIMARY KEY, zone_id bigint NOT NULL, business_date date NOT NULL, user_id bigint NOT NULL, at timestamptz NOT NULL DEFAULT now(), outcome text NOT NULL); -- accepted | duplicate | not_allowed

CREATE TABLE app.visit_plan (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, planner_id bigint NOT NULL, plan_date date NOT NULL, zone_id bigint NOT NULL, route_id bigint NOT NULL,
  created_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), status text NOT NULL DEFAULT 'active', UNIQUE (planner_id, plan_date, route_id));
CREATE TABLE app.visit_plan_outlet (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, plan_id bigint NOT NULL REFERENCES app.visit_plan(id), plan_client_uuid uuid NOT NULL, outlet_id bigint NOT NULL,
  status text NOT NULL DEFAULT 'pending', completed_at timestamptz, assessment_id bigint, UNIQUE (plan_id, outlet_id));   -- pending | completed | skipped

CREATE TABLE app.feedback (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL, category_code text NOT NULL, title text, description text, media_id bigint,
  status text NOT NULL DEFAULT 'new', handled_by bigint, handled_at timestamptz, captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint);
```

#### M-37..M-39 Reserved for the scale/sync lens (partition management function `app.ensure_partitions()`, `agg_dirty` queue — defined in §4.4, replication slots).

#### M-40 Credentials
```sql
CREATE TABLE app.user_credential (user_id bigint PRIMARY KEY REFERENCES app.app_user(id), password_hash text NOT NULL, algo text NOT NULL DEFAULT 'argon2id', changed_at timestamptz NOT NULL DEFAULT now(),
  must_change boolean NOT NULL DEFAULT false, failed_attempts int NOT NULL DEFAULT 0, locked_until timestamptz);
CREATE TABLE app.password_history (id bigserial PRIMARY KEY, user_id bigint NOT NULL, password_hash text NOT NULL, changed_at timestamptz NOT NULL DEFAULT now(), changed_by bigint);  -- keep last cfg.auth.password_history_depth (10)
CREATE TABLE app.refresh_token (id bigserial PRIMARY KEY, user_id bigint NOT NULL, device_id bigint, token_hash bytea NOT NULL UNIQUE, issued_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL, revoked_at timestamptz, scope_version int);
```

#### M-41 Device v2 and OTP
```sql
ALTER TABLE app.device ADD COLUMN model text, ADD COLUMN manufacturer text, ADD COLUMN os_version text, ADD COLUMN app_version text, ADD COLUMN abi text, ADD COLUMN ram_mb int,
  ADD COLUMN printer_id text, ADD COLUMN printer_name text, ADD COLUMN last_integrity_id bigint, ADD COLUMN bound_by_otp_id bigint, ADD COLUMN unbound_at timestamptz, ADD COLUMN unbound_by bigint, ADD COLUMN unbind_reason text;
CREATE TABLE app.device_otp (id bigserial PRIMARY KEY, code_hash bytea NOT NULL, issued_by bigint NOT NULL, for_user_id bigint NOT NULL, issued_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
  used_at timestamptz, used_device_id bigint, attempts int NOT NULL DEFAULT 0, status text NOT NULL DEFAULT 'issued');   -- issued | used | expired | revoked
CREATE TABLE app.device_capability_snapshot (id bigserial PRIMARY KEY, device_id bigint NOT NULL, at timestamptz NOT NULL DEFAULT now(), permissions jsonb NOT NULL, location_services_on boolean, battery_saver_on boolean, free_storage_mb int);
```

#### M-42 Migration support
```sql
CREATE TABLE stg.id_crosswalk (entity text NOT NULL, apsis_id text NOT NULL, new_id bigint NOT NULL, imported_at timestamptz NOT NULL DEFAULT now(), import_run_id bigint, PRIMARY KEY (entity, apsis_id));
CREATE TABLE stg.import_run (id bigserial PRIMARY KEY, started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, source_label text, delta_since timestamptz, counts jsonb, control_totals jsonb, status text);
CREATE TABLE app.opening_balance (id bigserial PRIMARY KEY, kind text NOT NULL, outlet_id bigint NOT NULL, program_id bigint, as_of_date date NOT NULL, amount_mtk bigint, points int, source_run_id bigint, UNIQUE (kind, outlet_id, program_id, as_of_date)); -- kind: due | loyalty_points
-- Imported memos carry entry_source='migration', memo_no = Apsis memo number, memo_serial = Apsis serial.
```

#### M-43 Audit log and report exports
```sql
CREATE TABLE app.audit_log (id bigint GENERATED ALWAYS AS IDENTITY, at timestamptz NOT NULL DEFAULT now(), actor_id bigint, actor_role role, via text NOT NULL, -- web | api | job | migration
  entity text NOT NULL, entity_id bigint, action text NOT NULL, before jsonb, after jsonb, diff jsonb, request_id text, ip inet, PRIMARY KEY (id, at)) PARTITION BY RANGE (at);
CREATE INDEX ON app.audit_log (entity, entity_id, at);
CREATE TABLE app.report_export_log (id bigserial PRIMARY KEY, user_id bigint NOT NULL, report_code text NOT NULL, filters jsonb, format text, row_count int, includes_pii boolean, at timestamptz NOT NULL DEFAULT now());
ALTER TABLE app.activity_log ADD COLUMN client_uuid uuid, ADD COLUMN device_id bigint, ADD COLUMN screen text, ADD COLUMN entity_type text, ADD COLUMN entity_client_uuid uuid, ADD COLUMN business_date date, ADD COLUMN meta jsonb;
```

#### M-44 Content and support
```sql
CREATE TABLE app.app_release (id bigserial PRIMARY KEY, app_role role NOT NULL, version_code int NOT NULL, version_name text NOT NULL, abi text, apk_url text NOT NULL, sha256 bytea NOT NULL, bytes int,
  min_supported boolean NOT NULL DEFAULT false, rollout_pct smallint NOT NULL DEFAULT 100, rollout_scope jsonb, released_at timestamptz NOT NULL DEFAULT now(), released_by bigint, notes text, UNIQUE (app_role, version_code, abi));
CREATE TABLE app.tutorial_asset (id bigserial PRIMARY KEY, audience role[] NOT NULL, kind text NOT NULL, title_bn text, title_en text, url text NOT NULL, sort int, status text NOT NULL DEFAULT 'active');
CREATE TABLE app.content_item (id bigserial PRIMARY KEY, kind text NOT NULL, title text, media_id bigint, scope jsonb, valid_from date, valid_to date, status text NOT NULL DEFAULT 'active');  -- AV/KV
CREATE TABLE app.content_view (id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL, user_id bigint NOT NULL, visit_client_uuid uuid, content_id bigint NOT NULL, seconds int, at timestamptz NOT NULL, PRIMARY KEY (id, business_date)) PARTITION BY RANGE (business_date);
CREATE TABLE app.support_upload (id bigserial PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, user_id bigint NOT NULL, device_id bigint, media_id bigint NOT NULL, reason text, at timestamptz NOT NULL DEFAULT now(), status text NOT NULL DEFAULT 'new');
```

#### M-45 Runtime configuration (seed #6; detailed by the cfg lens — this is the data contract it must satisfy)
```sql
CREATE TABLE cfg.config_item (
  key text PRIMARY KEY,                       -- cfg.geo.radius_m, cfg.day.checkout_earliest_time, cfg.memo.edit_reasons, cfg.loyalty.cash_rate_mtk_per_point …
  value_type text NOT NULL,                   -- int | bool | time | text | list | json | money_mtk
  default_value jsonb NOT NULL, constraints jsonb,   -- {"min":30,"max":2000} / {"enum":[…]}
  scope_levels text[] NOT NULL,               -- which of global|wing|division|territory|zone|route|outlet|role may override
  delivery text NOT NULL,                     -- bundle | token | server_only
  description_en text, owner_role role NOT NULL DEFAULT 'admin', requires_ack boolean NOT NULL DEFAULT false);
CREATE TABLE cfg.config_value (
  id bigserial PRIMARY KEY, key text NOT NULL REFERENCES cfg.config_item(key),
  scope_type text NOT NULL DEFAULT 'global', scope_id bigint NOT NULL DEFAULT 0,
  value jsonb NOT NULL, effective_from timestamptz NOT NULL DEFAULT now(), effective_to timestamptz,
  created_by bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), change_reason text NOT NULL, change_request_id bigint,
  EXCLUDE USING gist (key WITH =, scope_type WITH =, scope_id WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&));
CREATE INDEX ON cfg.config_value (key, scope_type, scope_id, effective_from DESC);
CREATE TABLE cfg.config_change_audit (id bigserial PRIMARY KEY, key text NOT NULL, scope_type text NOT NULL, scope_id bigint NOT NULL, old_value jsonb, new_value jsonb,
  effective_from timestamptz NOT NULL, changed_by bigint NOT NULL, changed_at timestamptz NOT NULL DEFAULT now(), reason text NOT NULL, approved_by bigint, config_version int NOT NULL);
CREATE SEQUENCE cfg.config_version_seq;      -- bumped on every change; bundle and token carry the version; visit/memo store config_version (P3)
CREATE TABLE cfg.config_ack (device_id bigint NOT NULL, config_version int NOT NULL, acked_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (device_id, config_version));
CREATE VIEW cfg.territory_geo_config AS /* compatibility: resolves cfg.geo.radius_m at territory level */ SELECT …;
-- Resolution order for a key at capture: outlet > route > zone > territory > division > wing > role > global; the bundle delivers RESOLVED values per outlet/route plus config_version.
```
Config-change facts flow into `dw.fact_config_change` (§4) so "what radius applied to this visit" is a join on `(key, scope, effective range)` or simply `visit.radius_m_used`.

#### Dropped from schema.sql
`stock_issue` (→ `stock_movement` + `dw.fact_daily_user_sku`), `route_log` (→ view), `territory_geo_config` (→ cfg view), `fact_daily_*` in `public` (→ `dw.*`, §4). `attendance` is kept as a derived table.
---

## 4. The analytics layer (`dw`) — what makes "any dashboard anytime" true

### 4.1 Shape

Star schema in schema `dw`, populated **only** by the aggregation worker from `app.*`. Three layers:

1. **Event-grain facts** (one row per transaction; partitioned by month) — the forensic/BI layer: `fact_visit`, `fact_memo_line`, `fact_due_ledger`, `fact_loyalty_ledger`, `fact_attendance`, `fact_geo_fix_flag`, `fact_sync_batch`, `fact_config_change`, `fact_outlet_request`, `fact_task`, `fact_assessment_answer`, `fact_distribution_check_line`, `fact_price_compliance`, `fact_redemption_line`, `fact_gift_handover`, `fact_stock_movement`.
2. **Daily aggregates** (one row per business_date × grain; small, indexed, what the dashboards hit): `agg_daily_route_sku`, `agg_daily_user_sku`, `agg_daily_outlet`, `agg_daily_outlet_brand`, `agg_daily_route`, `agg_daily_zone_category`, `agg_daily_zone`, `agg_month_route_product`, `agg_month_zone_product`, `agg_month_outlet_program`, `agg_outlet_balance`.
3. **Dimensions** with SCD2 where history matters: `dim_date`, `dim_geo` (route grain, flattened to wing), `dim_product` (SKU grain, flattened to category), `dim_outlet` (SCD2), `dim_user`, `dim_assignment`, `dim_device`, `dim_offer`, `dim_gift`, `dim_program_period`, `dim_config_item`, `dim_reason` (all cfg code lists).

Rollups above zone (territory, division, wing, national) are **SQL views over `agg_daily_zone*`** (≤1,051 rows per date) — fast enough for sub-second national dashboards without a further table; MTD above zone sums ≤ 31 × 1,051 rows. If measured p95 > 300 ms the scale lens may add `agg_daily_territory`; the worker design below already supports it.

Every `agg_*` row carries `computed_at` and `source_max_received_at` so a dashboard can display "data as of".

### 4.2 Dimensions (DDL)

```sql
CREATE TABLE dw.dim_date (
  date_key int PRIMARY KEY,                       -- yyyymmdd of business_date
  business_date date UNIQUE NOT NULL,
  year smallint, quarter smallint, quarter_label text,        -- 'Q4 Oct–Dec' (Astha quarters are calendar quarters per docs/06)
  month smallint, month_label text, month_start date, month_end date, days_in_month smallint,
  day_of_month smallint, day_of_week smallint,                 -- ISO 1=Mon..7=Sun
  dow_mask smallint,                                           -- bit matching route.visit_days_mask
  day_name_en text, day_name_bn text,
  is_weekend boolean,                                          -- Fri (and Sat?) — confirm; cfg.calendar.weekend_days
  is_holiday boolean, holiday_name text,                       -- from cfg.calendar.holidays
  is_working_day boolean,
  working_day_of_month smallint, working_days_in_month smallint,   -- for till-date target proration
  elapsed_fraction_calendar numeric(6,5), elapsed_fraction_working numeric(6,5),
  fiscal_year text, fiscal_quarter smallint,                   -- Jul–Jun in BD; confirm whether AKTCL reports fiscal or calendar
  week_of_year smallint, bn_date_label text);                  -- Bangla date string for app headers
-- Populated 2020-01-01 .. 2035-12-31; holidays maintained from cfg (admin edits calendar → recompute working-day columns from that date on).

CREATE TABLE dw.dim_geo (                 -- grain: route; SCD2 over route→zone and the chain above
  geo_key bigserial PRIMARY KEY, route_id bigint NOT NULL, route_code text, route_name text, visit_days_mask smallint,
  zone_id bigint, zone_code text, zone_name text, dep_id text, house_id bigint, house_code text, house_name text,
  territory_id bigint, territory_code text, territory_name text, division_id bigint, division_code text, division_name text, wing_id bigint, wing_code text, wing_name text,
  valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL, UNIQUE (route_id, valid_from));
CREATE INDEX ON dw.dim_geo (route_id, valid_from, valid_to); CREATE INDEX ON dw.dim_geo (zone_id) WHERE is_current;

CREATE TABLE dw.dim_product (             -- grain: SKU; SCD2 over hierarchy/units/sales_enable
  product_key bigserial PRIMARY KEY, sku_id bigint NOT NULL, sku_code text, sku_name text, short_name text, pack_size int, pack_type text,
  base_unit app.qty_unit, base_per_pack int, report_unit app.qty_unit, report_factor numeric(12,6),
  variant_id bigint, variant_name text, brand_id bigint, brand_name text, segment_id bigint, segment_name text, category_id bigint, category_name text,
  sales_enable boolean, sort int, valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL, UNIQUE (sku_id, valid_from));

CREATE TABLE dw.dim_outlet (              -- grain: outlet; SCD2 over route, class, status, location, kind. NO PII.
  outlet_key bigserial PRIMARY KEY, outlet_id bigint NOT NULL, outlet_code text, outlet_name text,
  outlet_kind text, channel text, sub_channel_id bigint, sub_channel_name text, geo_classification text, is_astha boolean, astha_tier text,
  route_id bigint, zone_id bigint, cluster_id bigint, cluster_name text, cluster_type text,
  latitude double precision, longitude double precision, location_source text, status text,
  created_date date, apsis_id text,
  valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL, UNIQUE (outlet_id, valid_from));
CREATE INDEX ON dw.dim_outlet (outlet_id, valid_from, valid_to); CREATE INDEX ON dw.dim_outlet (route_id) WHERE is_current;
CREATE TABLE dw.dim_outlet_pii (outlet_id bigint PRIMARY KEY, owner_name text, contact_number text, address text, nid text, tin text, trade_license text, updated_at timestamptz); -- separate grants (§5)

CREATE TABLE dw.dim_user (user_key bigserial PRIMARY KEY, user_id bigint NOT NULL, username text, full_name text, role text, employee_code text, status text, home_zone_id bigint,
  valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL, UNIQUE (user_id, valid_from));
CREATE TABLE dw.dim_assignment (assignment_key bigserial PRIMARY KEY, route_assignment_id bigint, user_id bigint NOT NULL, route_id bigint NOT NULL, assignment_kind text, valid_from date NOT NULL, valid_to date);
CREATE TABLE dw.dim_device (device_key bigserial PRIMARY KEY, device_id bigint NOT NULL, model text, manufacturer text, os_version text, abi text, ram_mb int, valid_from date NOT NULL, valid_to date, is_current boolean);
CREATE TABLE dw.dim_offer (offer_key bigserial PRIMARY KEY, offer_id bigint NOT NULL, code text, name_en text, group_code text, offer_type text, level text, valid_from date, valid_to date, version int, UNIQUE (offer_id, version));
CREATE TABLE dw.dim_gift (gift_key bigserial PRIMARY KEY, gift_id bigint NOT NULL, program_code text, code text, name_en text, kind text, points_cost int, valid_from date, valid_to date);
CREATE TABLE dw.dim_program_period (period_key bigserial PRIMARY KEY, program_id bigint, program_code text, program_kind text, period_id bigint, label text, period_start date, period_end date);
CREATE TABLE dw.dim_reason (reason_key bigserial PRIMARY KEY, list_key text NOT NULL, code text NOT NULL, label_bn text, label_en text, valid_from date, valid_to date, UNIQUE (list_key, code, valid_from)); -- edit reasons, force reasons, leave types, task types, DRP kinds
```

`dw.f_geo_key(route_id, business_date)`, `dw.f_outlet_key(outlet_id, business_date)`, `dw.f_product_key(sku_id, business_date)`, `dw.f_user_key(user_id, business_date)` are STABLE SQL functions used by the worker to pick the SCD2 row valid on the transaction's business_date. **Facts store the SCD key, never only the natural id**, so last month's report always uses last month's structure (P3/P6). Natural ids are also stored for convenience joins.

### 4.3 Facts (DDL for every grain)

```sql
-- EVENT GRAIN ---------------------------------------------------------------------------------------------
CREATE TABLE dw.fact_visit (
  visit_id bigint NOT NULL, business_date date NOT NULL, date_key int NOT NULL, client_uuid uuid NOT NULL,
  outlet_key bigint NOT NULL, geo_key bigint NOT NULL, user_key bigint NOT NULL, assignment_key bigint, device_key bigint,
  outlet_id bigint, route_id bigint, zone_id bigint, user_id bigint,
  kind text, started_at timestamptz, ended_at timestamptz, duration_s int, hour_of_day smallint,
  is_planned boolean,                       -- outlet was on today's planned route
  is_successful boolean,                    -- ≥1 active memo with net_mtk > 0 (D-05)
  is_zero_sale boolean, memo_count smallint, active_memo_count smallint, edited boolean,
  gross_mtk bigint, discount_mtk bigint, net_mtk bigint, paid_mtk bigint, due_mtk bigint, line_count smallint, distinct_sku int, distinct_brand int,
  device_geo_valid boolean, server_geo_valid boolean, geo_mismatch boolean, photo_validated boolean, force_reason text, is_mock boolean,
  server_distance_m double precision, radius_m_used int, gps_accuracy_m double precision, gps_retry_count smallint,
  plausibility_flags text[], suspicion_score smallint, is_suspicious boolean,
  qc_done boolean, qc_fault_qty_base int, survey_done boolean, drp_qty int,
  captured_offline boolean, sync_latency_s int, received_at timestamptz,
  PRIMARY KEY (visit_id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_visit (business_date, route_id); CREATE INDEX ON dw.fact_visit (business_date, outlet_id); CREATE INDEX ON dw.fact_visit (business_date, user_id);

CREATE TABLE dw.fact_memo_line (
  memo_line_id bigint NOT NULL, business_date date NOT NULL, date_key int NOT NULL, memo_id bigint NOT NULL, visit_id bigint NOT NULL,
  outlet_key bigint NOT NULL, geo_key bigint NOT NULL, product_key bigint NOT NULL, user_key bigint NOT NULL, offer_key bigint,
  outlet_id bigint, route_id bigint, zone_id bigint, user_id bigint, sku_id bigint, brand_id bigint, category_id bigint,
  memo_status text NOT NULL,                -- active | superseded | void  (aggregates use active only)
  entry_source text, is_credit boolean, memo_no text,
  qty_entered int, unit_entered text, pack_factor int, qty_base int, qty_report numeric(14,3),
  unit_price_mtk bigint, gross_mtk bigint, discount_mtk bigint, net_mtk bigint, is_free boolean,
  server_geo_valid boolean, photo_validated boolean, is_suspicious boolean, captured_offline boolean, received_at timestamptz,
  PRIMARY KEY (memo_line_id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_memo_line (business_date, route_id, sku_id); CREATE INDEX ON dw.fact_memo_line (business_date, outlet_id);

CREATE TABLE dw.fact_due_ledger (             -- signed money events; running balance in agg_outlet_balance
  ledger_id bigserial, business_date date NOT NULL, date_key int NOT NULL, outlet_key bigint NOT NULL, outlet_id bigint NOT NULL, geo_key bigint, user_key bigint,
  kind text NOT NULL,                        -- opening | credit_created | collection | memo_superseded_reversal | write_off | adjustment
  memo_id bigint, due_collection_id bigint, source_client_uuid uuid UNIQUE, amount_mtk bigint NOT NULL,   -- + increases outstanding, − reduces
  at timestamptz NOT NULL, PRIMARY KEY (ledger_id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_due_ledger (outlet_id, business_date);

CREATE TABLE dw.fact_loyalty_ledger (ledger_id bigint NOT NULL, business_date date NOT NULL, date_key int, outlet_key bigint, outlet_id bigint NOT NULL, period_key bigint, program_id bigint,
  kind text NOT NULL, source_type text, source_id text, points_delta int NOT NULL, user_id bigint, at timestamptz, PRIMARY KEY (ledger_id, business_date)) PARTITION BY RANGE (business_date);

CREATE TABLE dw.fact_attendance (user_id bigint NOT NULL, business_date date NOT NULL, date_key int, user_key bigint, geo_key bigint,  -- geo of primary route that day
  check_in_at timestamptz, check_out_at timestamptz, worked_minutes int, check_in_local time, check_out_local time,
  late_check_in boolean, early_check_out boolean, checkout_before_allowed boolean,   -- vs cfg.day.* thresholds in force that day
  in_is_mock boolean, out_is_mock boolean, in_lat double precision, in_lng double precision, out_lat double precision, out_lng double precision, in_out_distance_m double precision,
  PRIMARY KEY (user_id, business_date));

CREATE TABLE dw.fact_stock_movement (movement_id bigint NOT NULL, business_date date NOT NULL, date_key int, user_key bigint, product_key bigint, geo_key bigint, user_id bigint, sku_id bigint, zone_id bigint, route_id bigint,
  kind text, qty_base int, at timestamptz, PRIMARY KEY (movement_id, business_date)) PARTITION BY RANGE (business_date);

CREATE TABLE dw.fact_sync_batch (sync_batch_id bigint PRIMARY KEY, business_date date NOT NULL, date_key int, user_key bigint, device_key bigint, user_id bigint, uploaded_at timestamptz, trigger text, network_type text, battery_pct smallint,
  payload_bytes int, compressed_bytes int, record_count int, accepted int, rejected int, conflicts int, processing_ms int, min_latency_s int, max_latency_s int, p50_latency_s int, replayed boolean);
CREATE TABLE dw.fact_bundle_download (bundle_download_id bigint PRIMARY KEY, business_date date NOT NULL, user_id bigint, device_key bigint, at timestamptz, is_full boolean, bytes int, compressed_bytes int, duration_ms int, route_count smallint, is_first_of_day boolean);

CREATE TABLE dw.fact_config_change (audit_id bigint PRIMARY KEY, at timestamptz NOT NULL, business_date date NOT NULL, key text NOT NULL, scope_type text, scope_id bigint, geo_key bigint,
  old_value jsonb, new_value jsonb, changed_by_user_key bigint, approved_by_user_key bigint, reason text, config_version int, effective_from timestamptz,
  devices_targeted int, devices_acked int, acked_pct numeric(5,2), full_ack_at timestamptz);   -- ack columns updated by worker from cfg.config_ack

CREATE TABLE dw.fact_outlet_request (request_id bigint PRIMARY KEY, business_date date, type text, status text, outlet_key bigint, geo_key bigint, requested_by_key bigint, verified_by_key bigint, approved_by_key bigint,
  requested_at timestamptz, verified_at timestamptz, approved_at timestamptz, rejected_at timestamptz, hours_to_verify numeric(8,2), hours_to_approve numeric(8,2), has_photo boolean, has_fix boolean, moved_m double precision);
CREATE TABLE dw.fact_task (task_id bigint PRIMARY KEY, created_date date, due_date date, resolved_date date, type_code text, status text, assigner_key bigint, assignee_key bigint, outlet_key bigint, geo_key bigint, hours_to_resolve numeric(8,2), overdue boolean);
CREATE TABLE dw.fact_assessment_answer (assessment_id bigint NOT NULL, criterion_id bigint NOT NULL, business_date date, kind text, rubric_code text, group_code text, criterion_key text, score smallint, answer jsonb,
  assessor_key bigint, sr_user_key bigint, outlet_key bigint, geo_key bigint, PRIMARY KEY (assessment_id, criterion_id));
CREATE TABLE dw.fact_distribution_check_line (check_id bigint NOT NULL, brand_id bigint NOT NULL, business_date date, outlet_key bigint, geo_key bigint, assessor_key bigint, present boolean, oos boolean, posm_present boolean, PRIMARY KEY (check_id, brand_id));
CREATE TABLE dw.fact_price_compliance (check_id bigint PRIMARY KEY, business_date date, outlet_key bigint, geo_key bigint, product_key bigint, observed_price_mtk bigint, listed_price_mtk bigint, is_compliant boolean, deviation_mtk bigint);
CREATE TABLE dw.fact_redemption_line (redemption_line_id bigint PRIMARY KEY, business_date date, outlet_key bigint, geo_key bigint, period_key bigint, gift_key bigint, user_key bigint, qty int, points int, cash_mtk bigint, verified boolean, verified_at timestamptz);
CREATE TABLE dw.fact_gift_handover (gift_photo_id bigint PRIMARY KEY, business_date date, outlet_key bigint, geo_key bigint, period_key bigint, gift_key bigint, user_key bigint, assignment_id bigint, redemption_id bigint, at timestamptz, is_mock boolean);
CREATE TABLE dw.fact_geo_flag (visit_id bigint NOT NULL, business_date date NOT NULL, flag text NOT NULL, user_key bigint, geo_key bigint, outlet_key bigint, detail jsonb, PRIMARY KEY (visit_id, business_date, flag)) PARTITION BY RANGE (business_date);

-- DAILY AGGREGATES ------------------------------------------------------------------------------------------
CREATE TABLE dw.agg_daily_route_sku (business_date date NOT NULL, route_id bigint NOT NULL, sku_id bigint NOT NULL, geo_key bigint NOT NULL, product_key bigint NOT NULL,
  qty_base bigint NOT NULL DEFAULT 0, qty_report numeric(16,3) NOT NULL DEFAULT 0, free_qty_base bigint NOT NULL DEFAULT 0,
  memo_count int NOT NULL DEFAULT 0, outlets_bought int NOT NULL DEFAULT 0, gross_mtk bigint NOT NULL DEFAULT 0, discount_mtk bigint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0,
  qc_prod_fault_base int NOT NULL DEFAULT 0, qc_trans_fault_base int NOT NULL DEFAULT 0,
  geo_valid_qty_base bigint NOT NULL DEFAULT 0, suspicious_qty_base bigint NOT NULL DEFAULT 0,
  computed_at timestamptz NOT NULL, source_max_received_at timestamptz, PRIMARY KEY (business_date, route_id, sku_id));
CREATE INDEX ON dw.agg_daily_route_sku (route_id, business_date);

CREATE TABLE dw.agg_daily_user_sku (business_date date NOT NULL, user_id bigint NOT NULL, sku_id bigint NOT NULL, user_key bigint, product_key bigint,
  issued_base int NOT NULL DEFAULT 0, returned_base int NOT NULL DEFAULT 0, sold_base int NOT NULL DEFAULT 0, free_base int NOT NULL DEFAULT 0,
  variance_base int GENERATED ALWAYS AS (issued_base - returned_base - sold_base - free_base) STORED,  -- "return qty (issue − sold)" in AMO Summary; ≠0 is a stock-leak flag
  computed_at timestamptz NOT NULL, PRIMARY KEY (business_date, user_id, sku_id));

CREATE TABLE dw.agg_daily_outlet (business_date date NOT NULL, outlet_id bigint NOT NULL, outlet_key bigint NOT NULL, geo_key bigint NOT NULL, route_id bigint, zone_id bigint,
  is_planned boolean NOT NULL, visited boolean NOT NULL, visit_count smallint NOT NULL DEFAULT 0, successful boolean NOT NULL, zero_sale boolean NOT NULL,
  server_geo_valid boolean, photo_valid boolean, is_mock boolean, is_suspicious boolean, geo_mismatch boolean,
  memo_count smallint NOT NULL DEFAULT 0, edited_memos smallint NOT NULL DEFAULT 0, gross_mtk bigint, discount_mtk bigint, net_mtk bigint, credit_mtk bigint, collected_mtk bigint, due_balance_eod_mtk bigint,
  distinct_sku smallint, distinct_brand smallint, qc_done boolean, survey_done boolean, drp_qty int, first_visit_at timestamptz, last_user_id bigint,
  computed_at timestamptz NOT NULL, PRIMARY KEY (business_date, outlet_id));
CREATE INDEX ON dw.agg_daily_outlet (business_date, route_id);

CREATE TABLE dw.agg_daily_outlet_brand (business_date date NOT NULL, outlet_id bigint NOT NULL, brand_id bigint NOT NULL, outlet_key bigint, geo_key bigint, category_id bigint,
  qty_base bigint NOT NULL DEFAULT 0, memo_count smallint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0, PRIMARY KEY (business_date, outlet_id, brand_id));   -- Astha, BSR, Superstar

CREATE TABLE dw.agg_daily_route (business_date date NOT NULL, route_id bigint NOT NULL, geo_key bigint NOT NULL, zone_id bigint NOT NULL,
  is_planned boolean NOT NULL, assigned_user_id bigint, acting_user_id bigint, day_state day_state NOT NULL,
  logged_in boolean, logged_in_at timestamptz, checked_in_at timestamptz, first_visit_at timestamptz, last_visit_at timestamptz, first_sync_at timestamptz, last_sync_at timestamptz,
  sales_submitted boolean, sales_submitted_at timestamptz, submitted_with_dues boolean, final_submitted boolean, final_submitted_at timestamptz,
  target_outlets int NOT NULL DEFAULT 0, outlets_visited int NOT NULL DEFAULT 0, visits int NOT NULL DEFAULT 0, successful_calls int NOT NULL DEFAULT 0, zero_sale_calls int NOT NULL DEFAULT 0, unplanned_visits int NOT NULL DEFAULT 0,
  geo_valid_calls int NOT NULL DEFAULT 0, photo_valid_calls int NOT NULL DEFAULT 0, mock_calls int NOT NULL DEFAULT 0, suspicious_calls int NOT NULL DEFAULT 0, geo_mismatch_calls int NOT NULL DEFAULT 0,
  memo_count int NOT NULL DEFAULT 0, edited_memos int NOT NULL DEFAULT 0, credit_memos int NOT NULL DEFAULT 0,
  gross_mtk bigint NOT NULL DEFAULT 0, discount_mtk bigint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0, credit_created_mtk bigint NOT NULL DEFAULT 0, collected_mtk bigint NOT NULL DEFAULT 0,
  offline_memos int NOT NULL DEFAULT 0, online_memos int NOT NULL DEFAULT 0, web_entry_memos int NOT NULL DEFAULT 0,
  sync_batches int NOT NULL DEFAULT 0, sync_bytes bigint NOT NULL DEFAULT 0, p50_sync_latency_s int, max_sync_latency_s int, rejected_rows int NOT NULL DEFAULT 0,
  qc_fault_base int NOT NULL DEFAULT 0, surveys int NOT NULL DEFAULT 0, drp_qty int NOT NULL DEFAULT 0, tasks_resolved int NOT NULL DEFAULT 0,
  computed_at timestamptz NOT NULL, source_max_received_at timestamptz, PRIMARY KEY (business_date, route_id));
CREATE INDEX ON dw.agg_daily_route (business_date, zone_id);

CREATE TABLE dw.agg_daily_zone_category (business_date date NOT NULL, zone_id bigint NOT NULL, category_id bigint NOT NULL,
  qty_base bigint NOT NULL DEFAULT 0, qty_report numeric(16,3) NOT NULL DEFAULT 0, memo_count int NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0, discount_mtk bigint NOT NULL DEFAULT 0,
  target_month_base numeric(16,3), target_tilldate_base numeric(16,3),                                  -- resolved from app.target for that month/zone (D-07)
  computed_at timestamptz NOT NULL, PRIMARY KEY (business_date, zone_id, category_id));
CREATE TABLE dw.agg_daily_zone (business_date date NOT NULL, zone_id bigint NOT NULL, territory_id bigint, division_id bigint, wing_id bigint,
  target_routes int, logged_in_routes int, in_field_routes int, synced_routes int, submitted_routes int, final_submitted boolean, final_submitted_at timestamptz,
  target_outlets int, outlets_visited int, successful_calls int, geo_valid_calls int, photo_valid_calls int, mock_calls int, suspicious_calls int,
  memo_count int, net_mtk bigint, discount_mtk bigint, credit_created_mtk bigint, collected_mtk bigint, due_balance_eod_mtk bigint,
  calls_gt int, calls_dcc int, calls_astha int, calls_rcc int, calls_mt int, calls_horeca int,
  users_checked_in int, users_checked_out int, late_check_ins int,
  computed_at timestamptz NOT NULL, PRIMARY KEY (business_date, zone_id));

-- MONTHLY / BALANCE -----------------------------------------------------------------------------------------
CREATE TABLE dw.agg_month_route_product (month date NOT NULL, route_id bigint NOT NULL, product_level text NOT NULL, product_id bigint NOT NULL,
  qty_base_mtd bigint NOT NULL DEFAULT 0, qty_report_mtd numeric(16,3), memo_count_mtd int NOT NULL DEFAULT 0, net_mtk_mtd bigint NOT NULL DEFAULT 0,
  std_target numeric(16,3), memo_target int, target_version int, through_date date NOT NULL, computed_at timestamptz NOT NULL,
  PRIMARY KEY (month, route_id, product_level, product_id));
CREATE TABLE dw.agg_month_zone_product (LIKE dw.agg_month_route_product INCLUDING ALL);  -- route_id column renamed zone_id in the real migration
CREATE TABLE dw.agg_month_outlet_program (period_key bigint NOT NULL, outlet_id bigint NOT NULL, product_level text NOT NULL, product_id bigint NOT NULL,
  qty_base bigint NOT NULL DEFAULT 0, memo_count int NOT NULL DEFAULT 0, std_target numeric(16,3), memo_target int, criteria_met boolean, through_date date, computed_at timestamptz NOT NULL,
  PRIMARY KEY (period_key, outlet_id, product_level, product_id));   -- Astha / Superstar per-outlet achievement
CREATE TABLE dw.agg_outlet_balance (outlet_id bigint PRIMARY KEY, due_balance_mtk bigint NOT NULL DEFAULT 0, oldest_open_memo_date date, open_memo_count int,
  loyalty_points_balance int NOT NULL DEFAULT 0, loyalty_program_id bigint, last_visit_date date, last_sale_date date, visits_90d int, sales_90d int, computed_at timestamptz NOT NULL);
CREATE TABLE dw.agg_outlet_balance_daily (business_date date NOT NULL, outlet_id bigint NOT NULL, due_balance_mtk bigint NOT NULL, loyalty_points_balance int NOT NULL, PRIMARY KEY (business_date, outlet_id)); -- only rows for outlets whose balance changed that day; "balance as of D" = last row ≤ D
```

Rollup views: `dw.v_daily_territory`, `dw.v_daily_division`, `dw.v_daily_wing`, `dw.v_daily_national` = `SUM()` over `agg_daily_zone*` grouped via the zone's current parents **as of that date** (join `dim_geo` on validity, not `is_current`).

### 4.4 Rollup strategy — incremental, keyed by business_date, late-data safe

```sql
CREATE TABLE dw.agg_dirty (grain text NOT NULL, business_date date NOT NULL, key1 bigint NOT NULL, key2 bigint NOT NULL DEFAULT 0,
  reason text, enqueued_at timestamptz NOT NULL DEFAULT now(), claimed_at timestamptz, claimed_by text, PRIMARY KEY (grain, business_date, key1, key2));
CREATE TABLE dw.agg_run (id bigserial PRIMARY KEY, started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, worker text, items int, rows_written int, errors int, max_lag_s int);
CREATE TABLE dw.agg_reconcile (business_date date NOT NULL, grain text NOT NULL, checked_at timestamptz NOT NULL, source_value numeric, agg_value numeric, diff numeric, repaired boolean, PRIMARY KEY (business_date, grain, checked_at));
```

Mechanics:

1. **Ingest marks dirty, never aggregates inline.** The `/sync/batch` transaction ends by inserting `(grain, business_date, key)` rows for every distinct `(business_date, route_id)`, `(business_date, outlet_id)`, `(business_date, user_id)`, `(business_date, zone_id)` it touched (`ON CONFLICT DO NOTHING`). Cost: a handful of rows per batch; the sync response is not delayed by aggregation (R3).
2. **Worker drains the queue** every `cfg.agg.poll_interval_s` (default 20 s during 06:00–23:00 Dhaka, 120 s otherwise), claiming up to `cfg.agg.claim_batch` (500) items with `FOR UPDATE SKIP LOCKED`, grouped by `business_date`. Multiple worker replicas are safe.
3. **Recompute-from-source per key, idempotent.** For `(route_id, D)` the worker runs one `INSERT … SELECT … FROM app.memo_line/visit WHERE route_id = $1 AND business_date = $2 … ON CONFLICT (…) DO UPDATE SET …` for `agg_daily_route_sku`, `agg_daily_route`, and the fact rows of those visits. It never does `+= delta`; a retried item converges to the same values. Rows that vanished (memo superseded) are handled because the recompute is a full replace of that key, followed by `DELETE … WHERE (business_date, route_id, sku_id) NOT IN (recomputed set)`.
4. **Cascade.** Finishing a route item enqueues `(zone, D)`; the zone item recomputes `agg_daily_zone`, `agg_daily_zone_category` from `agg_daily_route*` (not from transactions), then `(month_route, month-of-D, route)` and `(month_zone …)` recompute the MTD rows from `agg_daily_route_sku` for that month. Depth is fixed (route → zone → month); no fan-out explosion: a batch of 40 visits creates ≈ 1 route + 1 zone + 2 month items.
5. **Late-arriving data is the same path.** A batch that lands on D+3 for business_date D dirties `(route, D)`; only D is recomputed; the month row for D's month is recomputed from the (now corrected) daily rows. Nothing about "today" is special. Memo edits dirty both the superseded memo's date and the new memo's date.
6. **Dimension changes dirty history only when they should.** Editing an outlet's *current* route opens a new `dim_outlet` row from today; past facts keep their old `outlet_key` → untouched. A **back-dated** correction (admin sets `valid_from` in the past) enqueues `(outlet, d)` for every d in the affected range — bounded and explicit, surfaced in the admin UI as "N days will be re-aggregated".
7. **Targets.** `agg_month_*` store the target resolved at compute time plus `target_version`; a target revision applying to month M enqueues `(month_route, M, route)` for the scope — again bounded.
8. **Never a full recompute at read time.** Dashboards read `agg_*`/views only. A nightly `dw.reconcile()` job compares, for the last 7 business dates, `SUM(net_mtk)`/`COUNT(memo)` per zone between `app.memo` and `agg_daily_zone`; any diff is logged in `agg_reconcile` and the key is re-enqueued (self-healing), and an alert fires if a diff survives two runs.
9. **Freshness SLO**: `max(received_at) − max(agg.computed_at)` for today ≤ 60 s p95 during the evening storm (scale lens sizes the worker; the design is embarrassingly parallel per business_date×route).
10. **Replay**: `dw.rebuild(business_date_from, to, scope)` enqueues every route×date in range; used after a bug fix in a KPI formula. It runs through the same queue at lower priority (`agg_dirty.reason='rebuild'` processed only when the live queue is empty), so a rebuild never starves live dashboards.

### 4.5 KPI definitions (each computed from `dw`, never from `app`)

Decisions proposed here (go to DECISIONS.md):

| ID | Decision | Rationale |
| --- | --- | --- |
| D-01 | Money = bigint milli-taka (`_mtk`); presentation rounding half-up to paisa; `round_adj_mtk` stored on memo. | seed #1 |
| D-02 | Quantities stored as `qty_entered + unit_entered + pack_factor + qty_base`; STD KPIs sum `qty_base`; `report_unit/report_factor` per SKU handle "lighter pieces vs boxes". | seed #8, Q8 |
| D-03 | **Submit % canonical = `sales_submitted routes ÷ target (planned) routes`** (docs/10). The app's "÷ logged-in" figure is kept under a different name, **Upload-of-login %**, shown as a secondary line. Both come from `agg_daily_zone`. | seed #7: two formulas cannot share a name; the web definition measures day completeness, which is what management acts on at 17:00. |
| D-04 | `memo_no` printed = `<username>-<yyMMdd>-<seq3>` composed on device (unique offline by construction: one user, one day, one counter persisted in the local DB); `memo_serial` = server sequence, started at Apsis max+1 if Q5 says "continue", else from 1 with the memo_no as the retailer-facing identity. | Q5; offline numbering must not depend on the server. |
| D-05 | **Successful call** = a visit with ≥ 1 `active` memo whose `net_mtk > 0` **or** `qty_base > 0` (free-sample-only memos count). Zero-sale visits are *visits* but not successful calls. | docs/10 CPR "successful calls"; ASSUMPTION — confirm whether a free-sample-only memo counts. |
| D-06 | **Memo count** = count of `active` memos with `line_count > 0`. Zero-sale memos (if the app creates one) are excluded; superseded memos are excluded; the superseding memo counts once. | Otherwise an edit doubles the memo count. |
| D-07 | **Till-date target** = `month_target × elapsed_fraction_working` (working days per `dim_date`), falling back to calendar fraction when `cfg.kpi.tilldate_basis = 'calendar'`. | Q12 unknown; configurable so the business can switch without code. |
| D-08 | **Login event** = the first `bundle_download` of a business_date for the route's user (full or delta), i.e. `route_day.logged_in_at = min(at)`. Later deltas never change the count. | seed #9 |
| D-09 | **Target outlets (day)** = outlets with `dim_outlet.status='active'` on that date whose route is planned that day (`visit_days_mask & dow_mask ≠ 0`) **plus** any outlet actually visited that day (so CPR can never exceed 100% because of an unplanned visit; unplanned visits are reported separately as `unplanned_visits`). | docs/10 "target outlets on the day's routes". |

KPI table (G = guard: `safe_div(n, d) = CASE WHEN d IS NULL OR d <= 0 THEN NULL ELSE n::numeric / d END`; UIs render NULL as "—", never 0 or ∞; negative targets are impossible by CHECK, and `target_flag='invalid'` is still emitted if a migrated target < 0 slips through staging):

| KPI (docs/10 name) | Grain(s) | Formula from `dw` | Notes / guards |
| --- | --- | --- | --- |
| STD (STT) | route×sku×day → any rollup | `SUM(agg_daily_route_sku.qty_base)`; shown as `qty_report = qty_base × dim_product.report_factor` | Sum only within one SKU or within one category (same base unit). A "total STD" across categories is **not** a number; show value (`net_mtk`) instead. |
| STD by category | zone×category×day | `agg_daily_zone_category.qty_base` | Cigarette sticks, Bidi sticks, Lighter pieces (or boxes via report_factor), Match dozen. |
| Memo | any | `SUM(memo_count)` per D-06 | |
| CPR / strike rate | route, zone, …, day | `safe_div(successful_calls, target_outlets)` | D-05, D-09. App "Live strike rate" = same formula on today's `agg_daily_route` (worker lag ≤ 60 s) — identical to web. |
| BSR (brand) | zone×brand×day/MTD | **primary (D)**: `safe_div(memos containing brand, memo_count)` from `agg_daily_outlet_brand` (`SUM(memo_count)` per brand ÷ zone memo_count); **secondary**: *brand reach* = `safe_div(outlets_bought brand, target_outlets)` | Q9: ship both, named differently; the leaderboard shows the primary. |
| Geo-validation % | route…national, day | `safe_div(geo_valid_calls, visits)` where `geo_valid_calls` counts **server_geo_valid = true** only | Force sales (`photo_valid`) excluded from numerator; mock visits never geo-valid. Also expose `photo_valid %`, `mock %`, `suspicious %`, `geo_mismatch %`. |
| Login % | zone…national, day | `safe_div(logged_in_routes, target_routes)` | D-08; `target_routes` = planned `route_day` rows. |
| Submit % | zone…national, day | `safe_div(submitted_routes, target_routes)` | D-03 |
| Upload-of-login % | zone…, day | `safe_div(submitted_routes, logged_in_routes)` | the apps' current figure, renamed |
| Final-submit status | territory…national, day | `COUNT(final_submitted) / COUNT(zones with target_routes>0)` + list of remaining zones | one/zone/day |
| Achievement % (MTD) | route/zone × product level × month | `safe_div(agg_month_*.qty_base_mtd, std_target_in_base)`; memo achievement `safe_div(memo_count_mtd, memo_target)` | `std_target_in_base` = target converted via `target.std_unit → base`. Bands: ≥100, 90–100, 80–90, <80 computed in SQL `CASE` so every page agrees. |
| Achievement % (till date) | same | `safe_div(qty_base_mtd, std_target_in_base × elapsed_fraction)` | D-07 |
| Target remaining | same | `GREATEST(target − achieved, 0)`; also `overshoot = GREATEST(achieved − target, 0)` | never negative remaining |
| Channel mix | zone…, day | `calls_<channel> / visits`, `memos_<channel> / memo_count` from `agg_daily_zone` (channel from `dim_outlet` as of date) | |
| Retention (Q10) | zone×category×month | **Candidate**: `safe_div(outlets that bought category in both M-1 and M, outlets that bought in M-1)` from `agg_daily_outlet_brand` | **confirm**; stored as `retention_candidate_pct` until Q10 is answered so nothing is mislabelled. |
| Outlets visited x/y, Non-visit, No-sale (app KPI strip) | route, day | `outlets_visited / target_outlets`; non-visit = `target_outlets − outlets_visited`; no-sale = `zero_sale_calls` | |
| Issue / current stock | user×sku, day | `issued_base`, `issued_base − returned_base − sold_base − free_base` | `agg_daily_user_sku` |
| SR Efficiency | user, day/month | calls per worked hour `safe_div(visits, worked_minutes/60)`, memo per call, value per memo, avg call duration, first-call delay `first_visit_at − check_in_at` | `fact_attendance` + `agg_daily_route` |
| GIGO (attendance) | user, day | check-in/out times, late/early flags vs cfg | `fact_attendance` |
| Discount report | offer group × scope × period | `SUM(discount_mtk)` grouped by `dim_offer.group_code` from `fact_memo_line` + `memo_offer` | |
| Free sample | route×sku×period | `SUM(free_qty_base)` | `agg_daily_route_sku.free_qty_base` |
| Online/Offline sales | scope×day | `offline_memos`, `online_memos`, `web_entry_memos` | from `memo.entry_source` + `captured_offline` |
| Data Entry Log | route×day | `logged_in_at, first_sync_at, last_sync_at, sync_batches, bundle count` | `agg_daily_route` + `fact_bundle_download` |
| By-Route Geo Capture | route×day | count of `outlet_location_history` rows by source | `fact_outlet_request`, location history |
| Astha report | outlet×brand×quarter | `agg_month_outlet_program` (period = quarter) achievement/remaining/% | `program_outlet_target` ≥ 0 by CHECK |
| Diamond League | outlet×month | `SUM(points_delta)` per period; balance = `agg_outlet_balance.loyalty_points_balance`; redemptions from `fact_redemption_line` | |
| Superstar | outlet×month | `agg_month_outlet_program` + `criteria_met` from program rules | rules jsonb interpreted by worker |
| Suspicious-location count (docs/05) | user/route/zone, day | `mock_calls + suspicious_calls` (distinct visits) | `agg_daily_route`; drill-down `fact_geo_flag` |
| Due outstanding | outlet, zone…, as of D | `agg_outlet_balance.due_balance_mtk`; historical: last `agg_outlet_balance_daily` ≤ D | from `fact_due_ledger` |
| Daily Tracking buckets | route, day | `CASE achievement_pct_tilldate` into 100 / 90–100 / 80–90 / <80 for STD and for memo target | after 17:00 join `tracking_action` |
| Sync health (R5) | zone…, day | p50/max `sync_latency_s` (captured_at → uploaded_at), % memos synced within 15 min, rejected rows | `fact_sync_batch`, `agg_daily_route` |
| Config reach (R6) | key×change | `acked_pct`, `full_ack_at − effective_from` | `fact_config_change` |

### 4.6 Unit handling worked example (seed #8)

| SKU | base_unit | pack_unit | base_per_pack | SR enters | stored | STD reported |
| --- | --- | --- | --- | --- | --- | --- |
| MaxR-20S | stick | pack | 20 | 3 packs | qty_entered=3, unit_entered=pack, pack_factor=20, qty_base=60 | 60 sticks |
| MaxR-20S | stick | pack | 20 | 5 sticks (loose) | 5, stick, 1, 5 | 5 sticks |
| AB-25s (bidi) | stick | pack | 25 | 2 packs | 2, pack, 25, 50 | 50 sticks (bidi unit — Q8) |
| Aster (lighter) | piece | box | **confirm** (1 or N) | 1 box | 1, box, N, N | pieces (web) / boxes via `report_factor = 1/N` (TSO) |
| FB (match) | dozen | dozen | 1 | 2 | 2, dozen, 1, 2 | 2 dozen |

Prices: `unit_price_mtk` is per **entered** unit; the bundle sends price per pack and per base unit (derived), so a loose-stick line prices correctly. Targets carry `std_unit`; the worker converts to base before dividing.
---

## 5. Retention, archival, PII and BI access

### 5.1 Retention classes (values are `cfg.retention.*` keys so the business can change them; defaults below)

| Data | Where | Hot (primary) | Then | Delete | Rationale |
| --- | --- | --- | --- | --- | --- |
| Transactions (`visit, memo, memo_line, due_collection, qc, survey, drp, stock_movement, attendance_event`) | `app.*` monthly partitions | 13 months | detach partition → `pg_dump` to Blob (Cool tier) as compressed SQL + Parquet export; partition dropped | never (archive kept 7 y; **confirm** statutory retention for sales records in BD) | Dues disputes and audits reach back a year; facts keep history for analytics so the raw rows need not stay online |
| `geo_fix` | partitions | 6 months | aggregated into `fact_visit`/`fact_geo_flag`; raw to Blob Archive tier | 24 months | 100 M+ rows/yr; forensic value decays; flags are already in facts |
| `activity_log`, `content_view` | partitions | 3 months | Blob Cool | 12 months | telemetry |
| `sync_rejected` | partitions | 12 months (parked rows never auto-deleted while `status='parked'`) | Blob | 24 months | reconciliation evidence |
| `sync_batch`, `bundle_download`, `ingest_registry` | tables | 13 months | registry rows older than 13 months pruned (a 13-month-late replay is impossible: the app purges local data after final submit) | — | registry size ≈ 1 row/record; prune keeps it bounded |
| `audit_log`, `cfg.config_change_audit`, `target_version`, `*_event` tables | tables | indefinite | — | never | R6 audit trail |
| Photos (`media_object` + Blob) | Blob | Hot 90 days | Cool 90 d–2 y; Archive 2–7 y via Blob lifecycle policy | 7 y (**confirm**) | outlet/base photos are kept while the outlet is active regardless (lifecycle rule excludes `retention_class='reference'`) |
| `dw.fact_*` event grain | partitions | 25 months | Parquet to Blob (ADLS-ready) | 7 y | BI history |
| `dw.agg_*` | tables | indefinite (tiny) | — | never | "any dashboard anytime" |
| Opening balances, crosswalk | `stg`, `app.opening_balance` | indefinite | — | never | migration provenance |

Mechanics: `app.ensure_partitions()` (creates +3 months) and `app.archive_partitions()` (monthly job) run from the API's scheduler; archive job writes a manifest row (`app.archive_manifest`: partition, row_count, sha256, blob_url) and only drops after the Blob upload is verified. Restoring = `pg_restore` of the partition into `app.*_archive` schema.

### 5.2 PII

| Field | Table(s) | Class | Rule |
| --- | --- | --- | --- |
| `outlet.nid, tin, trade_license` | app.outlet, dw.dim_outlet_pii | Sensitive | Column-level `GRANT` only to role `pii_reader`; API returns only to `admin`/`tso` with `app_user.pii_access=true`; every read logged to `report_export_log`/`audit_log`; never in `dw.dim_outlet`, never in bundles unless the role needs it (SR needs **none** of these). |
| `outlet.contact_number, owner_name, address` | same | Personal | SR/AMO need them for the route list → in bundle; in `dw` only in `dim_outlet_pii`; `phone_hash` used for de-dupe. |
| `app_user.phone, full_name, email`, credentials | app | Personal / secret | hashes only for passwords; `refresh_token.token_hash`. `dim_user` carries name (needed on dashboards) but no phone. |
| GPS fixes of users | geo_fix, attendance | Personal (location) | Supervisors see last fix of their own team only (scope); fixes aged out per §5.1; no export of raw fixes except to `fraud` role. |
| Photos | Blob | Personal (may show people) | Private container; SAS URLs minted per request, 15-min expiry; never public URLs stored. |
| Logs | App Insights | — | no PII in structured logs (ids only); `memo_no` is allowed, phone is not. |

Masking for BI: `dw.v_outlet_masked` exposes `contact_number` as `'01*****' || right(phone,3)`. Power BI connects as `bi_reader`, which has no grant on `dim_outlet_pii`.

### 5.3 BI / Power BI without touching the primary

- **Azure Database for PostgreSQL Flexible Server read replica** (same region; second replica cross-region later if DR demands). `bi_reader` role: `USAGE` on `dw` only, `SELECT` on `dw.agg_*`, `dw.fact_*`, `dw.dim_*` (not `dim_outlet_pii`), nothing on `app`/`cfg`. Connection through private endpoint; the replica's hostname is the only one shared with analysts.
- Power BI: **Import mode** nightly for history + **incremental refresh** hourly on `agg_daily_*` (partition by `business_date`); DirectQuery allowed only against `agg_*` tables. Refresh windows exclude 17:30–21:00 Dhaka (evening sync storm, replica lag).
- Row-level security for BI: `dw.bi_user_scope (bi_login, node_type, node_id)` maintained from `app.user_scope`; Power BI RLS filters `dim_geo` by the viewer's nodes.
- Replica lag is monitored (`pg_stat_wal_receiver`), alert at > 120 s; dashboards in the web app read the **primary's `dw`** (not the replica) so web numbers are never behind.
- Future lake: the Parquet archive in Blob (Hive-partitioned `business_date=…`) is readable by Fabric/Synapse serverless without any change to the DB.

---

## 6. Data-quality rules enforced at ingest

Policy per rule: **REJECT** → row to `sync_rejected` with `reason_code`, counted in the batch response `rejected[]`, device keeps it `failed` and shows it in the device-vs-server screen; **PARK** → `sync_rejected(status='parked')`, auto-retried, not shown as the device's fault; **FLAG** → row accepted, flag added to `flags[]`, visible in dashboards; **CLAMP** → value corrected deterministically and flagged.

| Rule | Check | Policy | reason_code / flag |
| --- | --- | --- | --- |
| DQ-01 | `client_uuid` is a valid v4 UUID and not already in `ingest_registry` with a different `payload_sha256` | REJECT (conflict) | `conflict` |
| DQ-02 | `client_uuid` already registered with same hash | ACCEPT silently, `seen_count++`, counted in `accepted` | — (idempotent replay) |
| DQ-03 | Parent (`visit_client_uuid`, `memo_client_uuid`, `plan_client_uuid`, `against_memo_client_uuid`) exists | PARK, retry when parent lands (trigger on registry insert) or every 10 min for 7 days, then REJECT | `parent_missing` |
| DQ-04 | `user_id` in payload == token user; `device_id` is bound to that user | REJECT | `user_mismatch` |
| DQ-05 | `outlet_id` exists and was `active` on `business_date` (`outlet_class_history`/status) | REJECT if not exists; FLAG if inactive/closed | `outlet_unknown` / `outlet_inactive` |
| DQ-06 | Outlet is within the caller's scope (route in the token's reach) | REJECT | `out_of_scope` |
| DQ-07 | `route_id` has an assignment for the user on `business_date` | FLAG (accept) | `unassigned_route` |
| DQ-08 | `sku_id` exists, `sales_enable`, enabled in `sales_plan` for the zone on `business_date` | REJECT unknown; FLAG not-in-plan | `sku_unknown` / `sku_not_in_plan` |
| DQ-09 | `business_date` ∈ [`today − cfg.sync.max_backdate_days` (7), `today + 1`] | REJECT outside | `business_date_out_of_window` |
| DQ-10 | `business_date == business_date_server` (recomputed from `captured_at`) | FLAG if differs | `business_date_mismatch` |
| DQ-11 | `captured_at ≤ received_at + cfg.sync.max_clock_skew_s` (300) | FLAG; store `time_skew_s` on device_integrity | `clock_skew` |
| DQ-12 | Quantities: `qty_entered > 0`; `pack_factor` == SKU's factor for `unit_entered` on that date; `qty_base ≤ cfg.sale.max_line_qty_base` | REJECT on ≤0 or bad unit; FLAG on factor mismatch (store device's factor, compute server factor into flags); FLAG on huge qty | `qty_invalid` / `pack_factor_mismatch` / `qty_outlier` |
| DQ-13 | `unit_price_mtk` equals the outlet price valid on `price_list_date` for that SKU/unit (tolerance 0) | FLAG `price_mismatch` with expected value in `flags_detail`; never reject (the retailer already paid the printed price) | `price_mismatch` |
| DQ-14 | Memo arithmetic: `gross = Σ line gross`, `discount = Σ line discount + Σ memo_offer`, `net = gross − discount`, `paid + due = net`, `due ≥ 0`, `is_credit = due>0`, `line_count = count(lines)` | REJECT the memo (and PARK its lines) | `memo_arith` |
| DQ-15 | Memo totals in minor units but `round_adj_mtk` within ±5 mtk | FLAG otherwise | `rounding_anomaly` |
| DQ-16 | `supersedes_client_uuid` exists, belongs to same outlet, old memo not QC-done, old memo `business_date` == new (edits are same-day) | REJECT | `edit_not_allowed` |
| DQ-17 | Edit `edit_fix_id` within radius of outlet (server recompute) | FLAG | `edit_out_of_range` |
| DQ-18 | `qc_entry` faults ≤ qty sold of that SKU in the visit's active memos | FLAG | `qc_exceeds_sold` |
| DQ-19 | Due collection: `against_memo` belongs to the outlet; `amount ≤ outstanding(memo)` at ingest; outlet outstanding after ≥ 0 | FLAG overpayment (accept; money was taken); REJECT if memo not of outlet | `overpayment` / `due_memo_mismatch` |
| DQ-20 | Attendance: `check_out.at > check_in.at`; check-out not before `cfg.day.checkout_earliest_time` in force that day; at most one of each kind per user/day (later duplicates become FLAG `duplicate_event`, first wins) | REJECT out-of-order; FLAG early | `attendance_order` / `early_checkout` |
| DQ-21 | Stock: `return ≤ Σ issue` for user/day/sku; sold + returned ≤ issued | FLAG | `stock_variance` |
| DQ-22 | Geo: `lat ∈ [20.3, 26.9]`, `lng ∈ [87.9, 92.8]` (Bangladesh bbox); `accuracy_m ≤ cfg.geo.max_accuracy_m` (100) | FLAG out-of-bbox (`geo_out_of_country`, also sets `server_geo_valid=false`); FLAG poor accuracy | `geo_out_of_country` / `poor_accuracy` |
| DQ-23 | Mock: `is_mock=true` → `server_geo_valid=false`, flag; if `cfg.geo.mock_policy='block'` the visit is still **accepted** but its memos are flagged `blocked_mock` (money changed hands; the business decides the consequence) | FLAG | `mock_location` |
| DQ-24 | Server distance recompute: Haversine(fix, outlet location valid at `captured_at`) ≤ `radius_m` resolved from cfg for that outlet/route/zone/territory at `captured_at` | sets `server_geo_valid`; FLAG `geo_mismatch` when ≠ device verdict | `geo_mismatch` |
| DQ-25 | Plausibility (per user per day, after batch): implied speed between consecutive fixes > `cfg.geo.max_speed_mps` (25 ≈ 90 km/h) → `teleport`; ≥ `cfg.geo.min_fixes_for_jitter` (5) fixes with identical lat/lng to 6 dp → `zero_jitter`; accuracy exactly equal across ≥5 fixes or < 1 m → `perfect_accuracy`; ≥ 80 % of a route's outlets visited from fixes within 20 m of one point → `single_point_route` | FLAG on visits; `suspicion_score` = weighted sum (fraud lens sets weights) | as named |
| DQ-26 | Redemption: Σ `points_each × qty` == `points_spent`; `points_spent ≤ balance` at ingest; gift ids valid for period | REJECT arithmetic; FLAG `points_overdrawn` (accept, balance goes negative and the outlet is blocked from further redemption until reviewed) | `redemption_arith` / `points_overdrawn` |
| DQ-27 | Survey/assessment answers match `answer_type`/options of the question/criterion version | REJECT | `answer_invalid` |
| DQ-28 | Enum/code values exist in the cfg list valid on `business_date` (edit reason, force reason, leave type, task type, DRP kind) | REJECT unknown code | `code_unknown` |
| DQ-29 | Photos: `bytes ≤ cfg.media.max_bytes` (300 KB), mime image/jpeg|webp, dimensions ≤ 1600 px; `content_sha256` matches upload | REJECT upload (device recompresses) | `media_invalid` |
| DQ-30 | Visit with no memo and `is_zero_sale=false` after 24 h | FLAG `incomplete_visit` (nightly) | `incomplete_visit` |
| DQ-31 | Targets (admin/import): `std_target ≥ 0`, `memo_target ≥ 0`, scope exists, product exists, month valid; revision `new_* ≥ 0` | REJECT at API + CHECK constraint; import rows → `stg.import_reject` | `target_negative` |
| DQ-32 | Master data: `zone.house_id` null; outlet without location; route without assignment on a planned day; SKU without price for a type | nightly `dw.master_quality` report (counts per zone), surfaced in admin | informational |
| DQ-33 | Divide-by-zero: all % via `safe_div`; UI renders NULL as "—" | CLAMP to NULL | — |
| DQ-34 | Counts: batch `device_counts[type]` vs server `accepted+rejected+conflict+replayed` per type must be equal; else response `reconcile_mismatch=true` | FLAG batch | `count_mismatch` |

Quarantine handling: admin page "Sync quarantine" lists `sync_rejected` by zone/date/reason with actions *retry*, *fix & accept* (edits payload, records `resolution_note`, re-runs rules), *discard* (needs reason). The device-vs-server screen shows `rejected` per type with the reason code translated (bn/en) so an SR knows which record to look at.

---

## 7. Gap list (G-data-NN)

| ID | Severity | Where | Gap | Fix / owner |
| --- | --- | --- | --- | --- |
| G-data-01 | blocker | schema.sql `memo_line, qc_entry, survey_response, drp_collection, loyalty_ledger, gift_photo, outlet_photo, task` | No idempotency key; retried batch doubles rows (seed #2) | M-07..M-10, M-23..M-25, M-30 registry; sync lens property tests |
| G-data-02 | blocker | `visit`, `memo` | No capture-time route/assignment/price-list/config context; history re-writes itself when masters change (seed #3) | M-05, M-06, M-14, M-16 histories; dw SCD keys |
| G-data-03 | blocker | all money columns, seed CSV | paisa cannot hold 3-decimal prices (seed #1) | D-01, M-01 |
| G-data-04 | blocker | `memo_line.qty`, `target.std_target` | No unit; every volume KPI undefined (seed #8, Q8) | D-02, M-02, M-07, M-21; business confirms bidi/lighter/match units before Phase 2 |
| G-data-05 | blocker | docs/10 vs 07/08 | Submit % two definitions (seed #7) | D-03 |
| G-data-06 | blocker | docs/04 "quarantine" | No quarantine, conflict or registry table; rejected rows vanish | M-30, §6 |
| G-data-07 | blocker | docs/10 aggregates | No dimensions, no event facts, no monthly/balance aggregates, no dirty-queue; "any dashboard anytime" not achievable | §4 (M-50 series), Phase 0/1 |
| G-data-08 | blocker | docs/06/09 memo | No human memo number; offline numbering undefined (Q5) | D-04, M-06; business answers Q5 before pilot print tests |
| G-data-09 | major | docs/05 | Single `geo_validated`; no integrity/plausibility storage (seed #4) | M-05, M-17, DQ-23..25 |
| G-data-10 | major | docs/04 day state (seed #10) | No entity for the route-day state machine; `route_log` cannot hold states | M-32 |
| G-data-11 | major | docs/06 stock, attendance | Sum-style natural keys make retries ambiguous | M-11, M-12 |
| G-data-12 | major | docs/07 AMO sync counts | "Price compliance" capture type has no spec and no table | M-28 (fields ASSUMPTION); confirm with the business from the AMO manual |
| G-data-13 | major | docs/09 Admin "Data Entry", Online/Offline report | No `entry_source`/offline flag on memo; report impossible | M-06 |
| G-data-14 | major | docs/10 programs | Astha per-outlet targets, Superstar, gift assignment, gift catalog, earn rules, offers all missing | M-20, M-22..M-24; Q13 answers needed for earn rule and promotion catalogue |
| G-data-15 | major | docs/09 targets | `target_revision` stores no values; no target uniqueness; no version history; negative targets allowed | M-21 |
| G-data-16 | major | docs/09 credentials, device OTP | No password hash/history, OTP, refresh tokens | M-40, M-41 |
| G-data-17 | major | docs/08 TSO | Leave, visit plan, feedback, final-submit route snapshot/reopen trail missing (Q11) | M-33..M-36 |
| G-data-18 | major | docs/06 outlet new-shop photo | `outlet_photo.outlet_id NOT NULL` cannot store a photo for a not-yet-approved outlet | M-15 |
| G-data-19 | major | docs/11 | No crosswalk/opening-balance/import-run tables; re-import not idempotent | M-42 |
| G-data-20 | major | R6 | No config tables; radius only per territory, no zone/outlet override, no effective dating, no audit, no ack | M-45 (contract for cfg lens) |
| G-data-21 | major | docs/09 reports | Free Sample, Discount (promotion groups), By-Route Geo Capture, Data Entry Log, Campaign Gift Redemption have no source columns | M-07 `is_free`, M-20, M-16 history, M-31 bundle_download, M-24 |
| G-data-22 | major | docs/10 KPIs | Successful call, memo count under edits/zero-sale, target outlets, till-date target, login event undefined | D-05..D-09; confirm D-05/D-07 |
| G-data-23 | major | PII | NID/TIN/phone unprotected in schema; no export log; no masked BI view | §5.2, M-43 |
| G-data-24 | major | partitioning | Unpartitioned transaction tables at 44 M visits/yr; `UNIQUE(client_uuid)` cannot be global once partitioned | P11, M-30 registry; scale lens to size |
| G-data-25 | minor | docs/06 visit | `ended_at`, `gps_retry_count`, suggestion snapshot, force photo link missing | M-05 |
| G-data-26 | minor | docs/07 | `distribution_check` shape repeats POSM per brand; no visit link; assessments lack rubric definitions | M-26, M-27 |
| G-data-27 | minor | docs/06 settings | PDA-to-Support upload, in-app update manifest, tutorial list have no tables | M-44 |
| G-data-28 | minor | docs/08 | Retention KPI (Q10), BSR denominator (Q9), TSO product scope (Q14) unresolved; candidate definitions shipped under non-committal names | §4.5; business |
| G-data-29 | minor | docs/09 web | "business"/"additional detail" outlet sections, QC page, Supervisory Module, Daily-Tracking "take action" contents unknown | `outlet.extra`, M-32 `tracking_action`; confirm from Web manual |
| G-data-30 | minor | dim_date | Weekend days, holiday calendar, fiscal vs calendar year, Astha quarter alignment unconfirmed | cfg.calendar.*; confirm |
| G-data-31 | minor | docs/06 zero sale | Whether a zero-sale creates a memo row (affects Memo KPI and print count) | D-06 handles either; confirm |
| G-data-32 | minor | retention | Statutory retention for sales records/photos in Bangladesh unknown | §5.1 defaults 7 y; confirm with legal |

---

## 8. Phase mapping (what of this lands when)

| Phase | Data-platform deliverables | Test gates |
| --- | --- | --- |
| 0 | M-01..M-45 migrations (all tables exist even if unused), `dw` dims + `agg_daily_route*`/`agg_daily_zone*`/`agg_dirty`, `dim_date` populated, seed loaded in mtk, registry + quarantine, cfg tables with defaults | T-0-01 schema loads clean on PG16 Flexible Server; T-0-02 seed prices round-trip exactly (7.935 → 7935 → 7.935); T-0-03 partition job creates +3 months; T-0-04 `safe_div` and CHECKs reject −20 target |
| 1 | Ingest path for visit/memo/memo_line/geo_fix with registry; worker for route→zone→month; one tile from `agg_daily_zone` | T-1-01 duplicate batch → identical `agg_*`; T-1-02 reordered children parked then accepted; T-1-03 late batch (D−2) changes only D−2 rows; T-1-04 device counts == accepted counts |
| 2 | All SR tables, dues/loyalty ledgers, edit supersession, plausibility flags, DQ-01..DQ-30 | T-2-01 edit does not double memo count; T-2-02 overpayment flagged not lost; T-2-03 mock fix never geo-valid; T-2-04 reconcile job finds zero diff after 10k fuzzed batches |
| 3 | AMO/TSO tables and facts; route_day/final_submit; login/submit % | T-3-01 Login % unchanged by delta refresh; T-3-02 final submit twice → one row, two attempts |
| 4 | Remaining facts, rollup views, report queries, PII grants, read replica, Power BI model | T-4-01 national dashboard p95 < 300 ms from agg; T-4-02 `bi_reader` cannot select PII; T-4-03 every docs/09 report has a `dw`-only query |
| 5–6 | Program aggregates, offer versions, target versions, config facts | T-5-01 target revision re-aggregates only its month/scope; T-6-01 config change → `fact_config_change.acked_pct` reaches 100 % in test fleet |
| 7 | Import into `stg`, crosswalk, opening balances, control totals vs Apsis reports | T-7-01 per-zone control totals match; T-7-02 re-import of same dump is a no-op |
