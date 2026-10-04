# Lens: Central Admin Dashboard and Runtime Configuration (requirement R6)

Author: config lens, 2026-10-04. Inputs: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13, db/schema.sql, db/seed/*, seed-findings.md (SF-1..SF-10), and the sibling lenses already written (lens-data.md §3 M-45 and §4 `fact_config_change`; lens-scale.md §2.9 and §7.1; lens-features.md F-ADM-012/013/034 and G-feat-50). Where a sibling lens already named a key, this document keeps that name; where two lenses used different names for the same thing, §1.0 lists the canonical name and the aliases to retire.

This is a planning document. It contains no application code. DDL fragments are the data contract for Phase 0 migrations, not a migration file.

---

## 0. Conclusions (one page)

| # | Conclusion | Consequence |
| --- | --- | --- |
| C1 | The spec hard-codes **about 240 operating parameters** (catalogued in §1: 241 rows, 34 of them critical) in prose. Today every one of them is a release. The sponsor's R6 asks for the opposite: every one adjustable from a GUI, with audit, reaching the phones. | A single `cfg.*` registry (§2) is a Phase 0 deliverable, not a Phase 6 nice-to-have. The radius, the 17:00 check-out and `min_version` must be config from the first vertical slice (§5). |
| C2 | `territory_geo_config.radius_m` (schema.sql) is the only existing config surface and it is wrong on four counts: no global default, no zone/outlet override, no effective dating, no audit (SF-6). | Replace it with the scoped, effective-dated `cfg.config_value` and keep `territory_geo_config` as a compatibility **view** for the importer (lens-data M-45). |
| C3 | Config is not one thing. There are three kinds: **scalars/lists** (radius, TTLs, reasons), **structured reference content** (gift catalog, survey questions, holidays, tutorial videos, support contacts) and **operational switches** (kill switch, read-only, sync hold). They need one version stream and one audit trail but different editors, different approval rules and different delivery. §1 tags each key with its kind. | Structured content lives in its own tables (owned by lens-data) but every change **bumps the same `config_version`** and is listed in the same audit log, so the app learns about it through the same delta pull and the admin sees one history (§3.4). |
| C4 | Propagation must cost zero extra battery (R4) and must still be "immediate" (R5). The design is: `X-Config-Version` on every response → app pulls `GET /config/delta` on its next natural request → acks in its next sync batch. No polling, no socket. Urgent keys (kill switch, radius revert) additionally ride an FCM data message (ASSUMPTION, Q22 in lens-scale). | Admin sees, per change, `devices_targeted / devices_acked / pending`, and can list the pending devices by zone (§3.3). "Immediate" is measured: T-2-61 requires ≥ 95 % of online devices acked within 15 min, 100 % by next bundle. |
| C5 | Mid-day changes are the dangerous case. A radius changed at 11:00 must apply to new captures only; the server re-check must use the value **effective at the visit's capture time**, not the current one; an offline phone must apply a scheduled 17:00 change at 17:00 without a network. | Every value is effective-dated (`effective_from/to`), every bundle carries scheduled changes up to `cfg.sys.schedule_horizon_days` ahead, and every `visit`/`memo` stores `config_version` plus the resolved `radius_m_used` (lens-data P3). |
| C6 | The admin is a user population too: ~10–20 people with very different authority (a TSO issuing an OTP, a wing manager asking for a looser radius, an IT admin setting token TTLs, a release manager pushing `min_version`). The schema has one `admin` role. | Add a permission model (`cfg.admin_permission`, §4.1): per-page and per-key-class rights, optionally bounded to a geography. Four risk classes C0–C3 (§1.0) decide who may edit, whether a second approver is required, whether a canary scope is mandatory and whether the change can be scheduled only. |
| C7 | The blast-radius problem is real: `cfg.geo.radius_m = 10` at global scope or a typo in `min_version` locks 8,500 reps out in one morning (G-scale-15). | Safety rails (§4.9): bounds enforced in the DB (`constraints` jsonb + CHECK), live blast-radius preview ("affects 1,051 zones, 8,412 devices, 460,112 outlets"), **two-person approval** for C3 keys, mandatory canary scope for C3 at ≥ zone level, one-click revert that creates a new audited change, rollback-to-version, and an **anomaly watch** that pages when geo-valid % or login % drops after a change. |
| C8 | 18 config questions cannot be answered from the spec (edit reason texts, no-outlet-location behaviour, who may reopen a day, Astha/Superstar parameters, PII matrix, approval roster …). They are listed as `unknown; confirm with the business` in §1 and as gaps in §7. None blocks Phase 0–1 because every key ships with a safe default that the GUI can later change — which is the whole point of R6. | Defaults in §1 are marked ASSUMPTION where the spec is silent. |

---

## 1. Catalog of tunable parameters

### 1.0 How to read the catalog

**Columns**

| Column | Meaning |
| --- | --- |
| Key | `cfg.<area>.<name>`; stable identifier; never renamed once shipped (add a new key and deprecate the old one). |
| Kind | **S** scalar/list value stored in `cfg.config_value`; **T** structured content in its own table (lens-data owns the DDL) but versioned through `config_version`; **O** operational switch (scalar, but with distinct UI and alerting). |
| Type | `int`, `bool`, `time` (HH:MM Asia/Dhaka), `text`, `money_mtk` (integer milli-taka, per SF-1 / lens-data D-01), `pct`, `list<…>`, `json(schema)`, `enum(…)`. |
| Default | Value on day one. **(spec)** = taken from docs; **(A)** = ASSUMPTION, reason given in the row or in the area notes. |
| Range / validation | Hard bounds enforced in `config_item.constraints` and re-checked by the API; a value outside them is rejected before it can be approved. |
| Scope | Which scope levels may hold an override: **G** global, **ROLE**, **WAVE** (rollout wave, §4.6), **W** wing, **D** division, **T** territory, **H** house, **Z** zone, **R** route, **O** outlet, **U** user, **DEV** device. Resolution precedence is §2.2. |
| Editor | Minimum admin permission (§4.1) needed to *propose* the change: `cfg.ops` (IT/ops admin), `cfg.field` (field operations manager), `cfg.program` (trade-marketing/program manager), `cfg.sec` (security admin), `cfg.release` (release manager), `tso` (within own territory), `dmo`/`wm` (within own scope). |
| Effect | When the new value is in force. **S** server-side at the next request (resolver cache invalidated on commit); **B** on the device after the next delta pull (seconds to minutes while online; next bundle when offline); **L** needs a new login/bundle (used for scope- and auth-shaped keys); **P** also pushed (FCM) because waiting for the next natural request is not acceptable. |
| Risk | **C0** cosmetic/content (single editor, no approval, immediate); **C1** operational (single editor with permission, reason mandatory); **C2** sensitive (reason mandatory, canary recommended, approver may be the same person but a 10-minute delayed apply with cancel); **C3** critical (two-person approval, canary scope mandatory for scope ≥ T, scheduled or immediate, anomaly watch armed automatically). |
| Risk if mis-set | The concrete failure the rails must prevent. |

**Canonical names and aliases.** Sibling lenses used these variants; the left-hand name is canonical and the aliases are to be replaced on merge.

| Canonical | Aliases seen | Note |
| --- | --- | --- |
| `cfg.geo.max_speed_kmh` | `cfg.geo.max_speed_mps` | km/h is what supervisors understand |
| `cfg.geo.max_accuracy_m` | `cfg.geo.min_accuracy_m` | "max acceptable accuracy radius" |
| `cfg.media.photo_max_kb` | `cfg.media.max_bytes` | |
| `cfg.sync.retry_backoff_s` | `cfg.sync.retry_backoff` | |
| `cfg.sync.max_clock_skew_min` | `cfg.sync.max_clock_skew_s` | |
| `cfg.auth.password_history_depth` | `cfg.auth.pw_history` | |
| `cfg.auth.password_min_len` | `cfg.auth.pw_min_len` | |
| `cfg.auth.password_min_age_h` | `cfg.auth.pw_min_age_h` | |
| `cfg.loyalty.cash_rate_mtk_per_point` | `cfg.loyalty.cash_per_point` | money in milli-taka |
| `cfg.tso.periphery_radius_options_m` | `cfg.periphery.radius_options_m`, `cfg.tso.periphery_radius_options` | |
| `cfg.sale.force_reasons` | — | enum values stay `internet_problem`, `location_change`; labels are localized content |

**Counting.** The catalog below holds **241 rows** (a few rows carry a min/max pair, and `cfg.flag.*` and `cfg.retention.*` are families), so roughly 245 distinct keys: 210 of kind S, 26 of kind T, 5 of kind O. By risk class: C0 = 17, C1 = 109, C2 = 81, C3 = 34. The 34 C3 keys are the ones whose mis-setting stops selling or rewrites history; they are the reason §4.9 exists.

### 1.1 Geofence and location (`cfg.geo.*`) — docs/05, docs/13 Q7

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.geo.radius_m` | S | int | 100 (spec: schema default) | 20–2000; must be ≥ `radius_min_m` and ≤ `radius_max_m`; outlet-level override additionally ≤ 3 × the resolved zone value unless approver overrides with reason | G, W, D, T, Z, O | `cfg.field`; `tso` for own T/Z/O within bounds (G-cfg-03) | B (device re-check uses value effective at capture; server re-check uses same) | C3 at G/W/D; C2 at T/Z; C1 at O | Too small → every sale becomes a force sale, reps revolt on day one; too large → geofence meaningless, spoofing invisible |
| `cfg.geo.radius_min_m` / `cfg.geo.radius_max_m` | S | int | 20 / 2000 (A: 20 m is GPS noise floor on cheap phones; 2 km covers a rural haat) | 10–100 / 500–5000 | G | `cfg.ops` | S | C3 | Bounds too loose make the rail above useless |
| `cfg.geo.no_location_policy` | S | enum(`force_sale_required`, `allow_unvalidated`, `block`) | `force_sale_required` (A: safest parity with today's "out of range → force sale" path; Q7 **unknown; confirm with the business**) | enum | G, W, T, Z | `cfg.field` | B | C3 | `block` strands imported outlets that lack coordinates (lens-data expects some) |
| `cfg.geo.first_capture_sets_location` | S | bool | true (A: the force-sale photo path is "the correction path for moved shops", docs/05) | — | G, T | `cfg.field` | S | C2 | false → outlets without coordinates never acquire one |
| `cfg.geo.fix_timeout_s` | S | int | 20 | 5–60 | G, T | `cfg.ops` | B | C1 | Too short → no fix → force sales; too long → battery + waiting reps |
| `cfg.geo.fix_accuracy_mode` | S | enum(`balanced`, `high`) | `balanced` (CLAUDE.md rule 3) | enum | G, T, Z | `cfg.ops` | B | C2 | `high` everywhere breaks the battery budget (R4) |
| `cfg.geo.max_accuracy_m` | S | int | 150 (A) | 30–500 | G, T, Z | `cfg.field` | B+S | C2 | A fix with accuracy radius above this cannot be `geo_validated`; too low → rural reps never validate |
| `cfg.geo.refresh_max` | S | int | 3 (A) | 1–10 | G | `cfg.field` | B | C1 | Number of "Refresh" retries offered before Force Sale is the only option |
| `cfg.geo.mock_policy` | S | enum(`flag`, `flag_and_warn`, `block_sale`) | `flag_and_warn` (A: docs/05 "report, don't just block"; a visible warning tells the rep the flag exists) | enum | G, W, D, T, Z | `cfg.field` | B+S | C3 | `block_sale` on launch day with a false-positive detector stops selling |
| `cfg.geo.mock_block_message_key` | S | text | `geo.mock_blocked` | must exist in i18n bundle | G | `cfg.ops` | B | C0 | — |
| `cfg.geo.max_speed_kmh` | S | int | 60 (A: urban beat on foot/rickshaw; motorbike plausible to 60) | 10–200 | G, W, T, Z | `cfg.field` | S (server plausibility) | C2 | Too low flags honest motorbike reps; too high misses teleports |
| `cfg.geo.teleport_min_distance_m` | S | int | 500 (A) | 100–5000 | G, T | `cfg.field` | S | C1 | Pairs with speed: avoids flagging GPS jumps between two fixes 30 m apart |
| `cfg.geo.min_fixes_for_jitter` | S | int | 8 (A) | 3–50 | G | `cfg.field` | S | C1 | Fewer → zero-jitter rule fires on short routes |
| `cfg.geo.jitter_threshold_m` | S | int | 2 (A: real GPS never repeats to < 2 m across a route) | 0–20 | G | `cfg.field` | S | C1 | 0 disables the rule |
| `cfg.geo.perfect_accuracy_threshold_m` | S | int | 3 (A: "impossibly perfect accuracy") | 1–10 | G | `cfg.field` | S | C1 | — |
| `cfg.geo.same_point_outlets_max` | S | int | 3 (A: ≥ 4 outlets "visited" from one coordinate on a day → route flag) | 2–20 | G, T | `cfg.field` | S | C1 | — |
| `cfg.geo.integrity_weight` | S | json({mock:int, rooted:int, dev_options:int, play_integrity_fail:int, teleport:int, zero_jitter:int, same_point:int}) | {mock:100, rooted:20, dev_options:10, play_integrity_fail:30, teleport:40, zero_jitter:40, same_point:30} (A) | each 0–100 | G | `cfg.field` | S | C2 | Drives the "suspicious score" shown to AMO/TSO; mock=100 means a mocked fix alone is suspicious |
| `cfg.geo.suspicious_score_threshold` | S | int | 50 (A) | 1–300 | G, W, T | `cfg.field` | S | C2 | Too low drowns supervisors in flags |
| `cfg.geo.play_integrity_enabled` | S | bool | false (A: needs Google Play services on every device; unverified on the fleet) | — | G | `cfg.sec` | L | C2 | true on devices without Play → login friction |
| `cfg.geo.rooted_policy` | S | enum(`ignore`, `flag`, `block_login`) | `flag` | enum | G | `cfg.sec` | L | C3 | `block_login` on a fleet with unknown root prevalence = lockout |
| `cfg.geo.store_fix_on_every_screen_open` | S | bool | false (R4) | — | G | `cfg.ops` | B | C2 | true re-introduces continuous location |
| `cfg.geo.update_base_requires_photo` | S | bool | true (docs/07 Update Base) | — | G | `cfg.field` | B | C1 | — |
| `cfg.geo.outlet_location_change_approval` | S | enum(`amo_then_web`, `amo_only`, `auto`) | `amo_then_web` (docs/06) | enum | G, W | `cfg.field` | S | C2 | `auto` lets a force-sale photo silently move an outlet (docs/05 forbids) |

Area notes. (a) Outlet-level radius overrides are expected to be rare (shops inside a market with poor GPS); the admin map (§4.3) shows them as a distinct ring colour and the bundle carries them as a sparse map, so 460 k outlets with 2 k overrides adds ~20 KB to the fleet's bundles, not 460 k × 4 bytes. (b) `visit.radius_m_used`, `visit.config_version` and `visit.device_geo_verdict` (lens-data) make every verdict reproducible: the server re-check (F-SYS-012) resolves `cfg.geo.radius_m` **as of `visit.started_at`** for `visit.outlet_id`, never "now".

### 1.2 Day cycle and submit (`cfg.day.*`) — docs/04 state machine, docs/06 step 7, docs/08 Final Submit, Q11

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.day.checkout_earliest_time` | S | time | 17:00 (spec) | 12:00–22:00 | G, W, D, T, Z | `cfg.field` | B (device enforces offline using scheduled value; server re-checks on sync) | C3 at G/W; C2 at T/Z | 07:00 lets reps check out at breakfast; 23:00 means nobody can close the day |
| `cfg.day.checkout_latest_time` | S | time | 23:59 (A) | > earliest | G, W | `cfg.field` | B+S | C2 | Earlier than the evening sync tail → honest late syncs rejected |
| `cfg.day.checkin_earliest_time` | S | time | 05:00 (A) | 00:00–12:00 | G, W | `cfg.field` | B+S | C1 | — |
| `cfg.day.business_date_cutoff_time` | S | time | 00:00 (A: calendar day in Asia/Dhaka, CLAUDE.md rule 7; **unknown; confirm** whether a 02:00 cutoff is wanted for late syncs) | 00:00–06:00 | G | `cfg.ops` | S (server assigns business_date at **capture time** from device clock + skew check, lens-scale) | C3 | Changing it mid-month splits a day in two in every aggregate; only allowed effective-from a future date |
| `cfg.day.sales_submit_dues_warning` | S | enum(`off`, `warn`, `block`) | `warn` (spec: "warns if any retailer still has dues") | enum | G, W, T | `cfg.field` | B | C2 | `block` prevents closing a day with legitimate credit sales |
| `cfg.day.sales_submit_requires_checkout` | S | bool | false (A: spec order is submit → check-out) | — | G | `cfg.field` | B | C1 | — |
| `cfg.day.sales_submit_offline_queue` | S | bool | true (G-feat-65) | — | G | `cfg.ops` | B | C1 | false → a dead zone at 17:30 blocks the day close |
| `cfg.day.final_submit_once_per_day` | S | bool | true (spec) | — | G | `cfg.field` | S | C3 | — |
| `cfg.day.final_submit_allow_not_set_routes` | S | enum(`allow`, `warn`, `block`) | `warn` (A; G-feat-64 **unknown; confirm**) | enum | G, W | `cfg.field` | S | C2 | `block` → a TSO cannot close a zone where one SR's phone died |
| `cfg.day.final_submit_earliest_time` | S | time | 17:00 (A: same as check-out) | — | G, W | `cfg.field` | S | C2 | — |
| `cfg.day.reopen_roles` | S | list<role> | [`admin`] (A; Q11 **unknown; confirm** whether DMO may reopen) | subset of roles | G, W | `cfg.sec` | S | C3 | Too wide → "final" means nothing |
| `cfg.day.reopen_window_days` | S | int | 3 (A) | 0–31 | G | `cfg.field` | S | C2 | Beyond this a reopen needs a two-person approval regardless |
| `cfg.day.late_sync_after_final_policy` | S | enum(`accept_and_flag`, `quarantine`, `reject`) | `accept_and_flag` (A: never lose a sale, G-feat-17) | enum | G | `cfg.field` | S | C3 | `reject` loses real sales from a late phone |
| `cfg.day.attendance_missing_checkout_autoclose_time` | S | time | 23:30 (A; G-feat-18) | > checkout_latest | G | `cfg.field` | S | C1 | — |
| `cfg.day.take_action_after` | S | time | 17:00 (spec, Daily Tracking) | — | G, W | `cfg.field` | S | C1 | — |
| `cfg.day.multi_visit_same_outlet_policy` | S | enum(`allow`, `allow_after_zero_sale`, `block`) | `allow_after_zero_sale` (A; G-feat-08) | enum | G | `cfg.field` | B+S | C2 | `allow` double-counts CPR unless the aggregate collapses |

### 1.3 Memo, sale and QC (`cfg.memo.*`, `cfg.sale.*`, `cfg.qc.*`) — docs/06 steps 4–6

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.memo.edit_reasons` | T (`app.reason_code`, kind=`memo_edit`) | list<{code, label_bn, label_en, active, sort}> | 3 placeholder codes `qty_error`, `price_error`, `wrong_outlet` (A; G-feat-15 **unknown; confirm the three texts**) | 1–10 active; codes immutable; labels editable | G | `cfg.field` | B | C1 | Removing a code that history references → keep code, set `active=false` |
| `cfg.memo.edit_requires_geofence` | S | bool | true (spec) | — | G, T | `cfg.field` | B+S | C2 | — |
| `cfg.memo.edit_blocked_after_qc` | S | bool | true (spec) | — | G | `cfg.field` | B+S | C2 | — |
| `cfg.memo.edit_window_min` | S | int | 0 = until day close (A) | 0–720 | G, T | `cfg.field` | B+S | C1 | Adds a time cap on top of the two rules above |
| `cfg.memo.edit_after_print_policy` | S | enum(`void_and_reprint`, `amend_reprint`, `block`) | `void_and_reprint` (A; G-feat-16) | enum | G | `cfg.field` | B | C2 | Retailer holds the old paper |
| `cfg.memo.reprint_max` | S | int | 3 (A; G-feat-04) | 0–10 | G, T | `cfg.field` | B | C1 | — |
| `cfg.memo.reprint_watermark` | S | bool | true ("DUPLICATE") | — | G | `cfg.field` | B | C0 | — |
| `cfg.memo.number_format` | S | text template | `{device_prefix}-{yyMMdd}-{seq:4}` (D-scale-10; Q5 **unknown; confirm**) | must contain `{seq}`; change only effective from a future business date | G | `cfg.ops` | L | C3 | Changing mid-day duplicates printed numbers |
| `cfg.memo.header_lines` / `cfg.memo.footer_lines` | T (`app.print_template`) | list<text_bn/en> per role | AKTCL header, "ধন্যবাদ" footer (A: match current memo, docs/11 "same memo") | ≤ 6 lines × 32 cols (58 mm) | G, W, D | `cfg.field` | B | C1 | Wrong header on 1.2 lakh memos/day |
| `cfg.memo.rounding_mode` | S | enum(`half_up_paisa`, `floor_paisa`, `half_even_paisa`) | `half_up_paisa` (A; G-feat-46 **unknown; must match Apsis to the paisa**) | enum; future-dated only | G | `cfg.ops` | B+S | C3 | Totals differ from the old memo by 1 paisa → retailer disputes |
| `cfg.memo.show_due_balance_on_print` | S | bool | true (A) | — | G | `cfg.field` | B | C0 | — |
| `cfg.sale.force_reasons` | T (`app.reason_code`, kind=`force_sale`) | list<{code,label_bn,label_en,active}> | `internet_problem`, `location_change` (spec) | codes fixed by enum in Phase 1; table replaces enum in Phase 2 | G | `cfg.field` | B | C1 | — |
| `cfg.sale.force_requires_photo` | S | bool | true (spec) | — | G, T | `cfg.field` | B | C2 | false → force sale becomes a free bypass |
| `cfg.sale.zero_sale_requires_confirm` | S | bool | true (spec) | — | G | `cfg.field` | B | C0 | — |
| `cfg.sale.qty_entry_unit` | S | enum(`pack`, `base_unit`) per category | {Cigarette:`pack`, Bidi:`pack`, Lighter:`piece`, Match:`dozen`} (A; Q8 / SF-8 **unknown; confirm**) | json by category; future-dated only | G | `cfg.ops` | L | C3 | Every volume figure wrong by pack_size |
| `cfg.sale.max_line_qty_base` | S | int | 100 000 (A: 5 000 packs of 20) | 1–10 000 000 | G, Z | `cfg.field` | B | C1 | Catches fat-finger "20000" |
| `cfg.sale.stock_insufficient_policy` | S | enum(`allow`, `warn`, `block`) | `warn` (A; G-feat-44) | enum | G, W, Z | `cfg.field` | B | C2 | `block` with a wrong stock issue entry stops selling |
| `cfg.sale.offers_auto_apply` | S | bool | true (spec) | — | G | `cfg.program` | B | C1 | — |
| `cfg.sale.suggested_qty_enabled` | S | bool | false until Q6 formula confirmed | — | G, W, T | `cfg.program` | B | C1 | — |
| `cfg.sale.require_printer_before_sale` | S | bool | false (G-feat-43: sale persists without print) | — | G | `cfg.field` | B | C2 | true blocks sales when the printer dies |
| `cfg.credit.enabled` | S | bool | true (spec) | — | G, W, D, T, Z, O | `cfg.field`; `tso` at Z/O | B | C2 | Outlet-level `false` = credit stop for a defaulting retailer |
| `cfg.credit.max_due_mtk` | S | money_mtk | 0 = no limit (A; G-feat-14 **unknown; confirm**) | 0–10 000 000 000 | G, W, T, Z, O | `cfg.field`; `tso` at Z/O | B | C2 | — |
| `cfg.credit.max_days` | S | int | 0 = no limit (A) | 0–180 | G, W, T, Z, O | `cfg.field` | B | C2 | — |
| `cfg.credit.block_on_overdue` | S | bool | false (A) | — | G, T, Z | `cfg.field` | B | C2 | — |
| `cfg.credit.partial_payment_min_pct` | S | pct | 0 (A) | 0–100 | G | `cfg.field` | B | C1 | — |
| `cfg.qc.fault_kinds` | T (`app.reason_code`, kind=`qc_fault`) | list | `production_fault`, `transport_fault` (spec) | — | G | `cfg.field` | B | C1 | — |
| `cfg.qc.required_before_print` | S | bool | true (spec: QC is a step before print) | — | G | `cfg.field` | B | C2 | — |
| `cfg.drp.kinds` | T (`app.reason_code`, kind=`drp`) | list<{code,label,offer_id?}> | `empty_pack`, `slide` (spec) | — | G | `cfg.program` | B | C1 | — |
| `cfg.stock.return_entry_enabled` | S | bool | true (G-feat-02) | — | G, W | `cfg.field` | B | C1 | — |
| `cfg.stock.print_stock_memo` | S | bool | true (spec) | — | G | `cfg.field` | B | C0 | — |

### 1.4 Media and photos (`cfg.media.*`) — docs/04 battery and data-pack budgets

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.media.long_edge_px` | S | int | 1024 (spec) | 480–2048 | G, W | `cfg.ops` | B | C2 | 2048 doubles the data pack; 480 makes outlet photos unreadable for verification |
| `cfg.media.jpeg_quality` | S | int | 70 (A) | 40–95 | G | `cfg.ops` | B | C1 | — |
| `cfg.media.photo_max_kb` | S | int | 150 (spec 100–200) | 50–500; the compressor iterates quality down until ≤ this | G, W | `cfg.ops` | B | C2 | Above 300 breaks the data budget at 2–3 photos × 8,500 reps |
| `cfg.media.upload_network_policy` | S | enum(`wifi_only`, `wifi_preferred`, `any`) | `wifi_preferred` (spec: Wi-Fi preferred; A: mobile allowed after `wifi_wait_h`) | enum | G, W, D, T, Z | `cfg.ops` | B | C2 | `any` on launch day = photo storm on mobile data; `wifi_only` on a fleet with no Wi-Fi = photos never arrive |
| `cfg.media.wifi_wait_h` | S | int | 6 (A) | 0–72 | G, W | `cfg.ops` | B | C1 | Hours to wait for Wi-Fi before falling back to mobile under `wifi_preferred` |
| `cfg.media.user_may_toggle_wifi_only` | S | bool | true (spec: "allow a sync-photos-on-Wi-Fi-only setting") | — | G | `cfg.ops` | B | C0 | — |
| `cfg.media.max_photos_per_visit` | S | int | 4 (A: survey + force + outlet + gift) | 1–10 | G | `cfg.field` | B | C1 | — |
| `cfg.media.upload_batch_max_mb` | S | int | 5 (A) | 1–20 | G | `cfg.ops` | B | C1 | — |
| `cfg.media.retention_days` | S | int | 730 (A; Q21 **unknown; confirm**) | 90–3650 | G | `cfg.ops` | S | C2 | Owned jointly with lens-data `cfg.retention.*` |
| `cfg.media.thumbnail_cache_mb` (= `cfg.app.image_cache_mb`) | S | int | 50 (A) | 10–200 | G | `cfg.ops` | B | C1 | Bounded LRU per docs/04 |
| `cfg.media.pending_photo_grace_days` | S | int | 7 (G-scale-20) | 1–30 | G | `cfg.ops` | S | C1 | After this a visit with `photo_state=pending` is flagged `missing` |

### 1.5 Sync, bundle and app behaviour (`cfg.sync.*`, `cfg.bundle.*`, `cfg.app.*`) — docs/04, lens-scale §1.5–1.7, R5

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.sync.trickle_enabled` | S | bool | true (R5: send immediately when online) | — | G, W, D, T, Z, WAVE | `cfg.ops` | B | C2 | false = "end-of-day only" (the thing R5 forbids); kept as a brake |
| `cfg.sync.trickle_debounce_s` | S | int | 10 (lens-scale) | 2–60 | G, W | `cfg.ops` | B | C1 | Coalesces a visit's children into one batch; too low → one request per row |
| `cfg.sync.batch_max_rows` | S | int | 200 | 50–500 | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.batch_max_kb` | S | int | 256 (A, gzip) | 64–1024 | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.retry_backoff_s` | S | list<int> | [2,4,8,16,32] + full jitter | 1–5 entries, each 1–600, strictly increasing | G | `cfg.ops` | B | C1 | Short list with no jitter = retry storm (G-scale) |
| `cfg.sync.retry_max_attempts` | S | int | 5 | 1–10 | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.periodic_min` | S | int | 15 (WorkManager floor) | 15–120 | G, W | `cfg.ops` | B | C2 | Cannot go below 15 on Android; setting 120 delays catch-up |
| `cfg.sync.periodic_requires_charging` | S | bool | false | — | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.periodic_requires_battery_not_low` | S | bool | true (docs/04) | — | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.login_jitter_s` | S | int | 120 | 0–600 | G, W | `cfg.ops` | B | C1 | Automatic morning refresh only, never a manual login |
| `cfg.sync.max_clock_skew_min` | S | int | 10 | 1–120 | G | `cfg.ops` | S+B | C2 | Server flags rows beyond this; device prompts |
| `cfg.sync.max_backdate_days` | S | int | 7 (A) | 1–60 | G | `cfg.field` | S | C2 | Rows older than this are quarantined, not rejected |
| `cfg.sync.wakelock_max_s` | S | int | 60 | 10–300 | G | `cfg.ops` | B | C1 | — |
| `cfg.sync.upload_on_metered` | S | bool | true (R5; transactional rows are tiny) | — | G, W | `cfg.ops` | B | C2 | false silently breaks "immediate" on mobile data |
| `cfg.bundle.pregen_time` | S | time | 04:00 (lens-scale) | 00:30–06:00 | G | `cfg.ops` | S | C1 | — |
| `cfg.bundle.ttl_h` | S | int | 24 (A) | 12–72 | G | `cfg.ops` | S | C1 | Snapshot age after which the API regenerates instead of serving |
| `cfg.bundle.stale_max_days` | S | int | 2 | 1–3 | G, W | `cfg.ops` | B | C2 | Offline start allowed on yesterday's bundle with a warning |
| `cfg.bundle.delta_enabled` | S | bool | true | — | G | `cfg.ops` | S | C1 | — |
| `cfg.bundle.outlet_fields` | S | list<text> | per role (lens-data PII matrix) | subset of outlet columns | ROLE | `cfg.sec` | L | C3 | Adding `nid` to the SR bundle leaks PII to 8,500 phones |
| `cfg.bundle.history_days` | S | int | 30 (A: "View previous sale data" needs some history) | 0–90 | G, ROLE | `cfg.ops` | L | C1 | 90 blows the bundle size |
| `cfg.app.local_history_days` | S | int | 7 (A) | 1–30 | G | `cfg.ops` | B | C1 | Local DB purge horizon for synced rows |
| `cfg.app.clear_on_final_submit` | S | bool | true (docs/04) | — | G | `cfg.ops` | B | C1 | Only synced rows are cleared, never pending |
| `cfg.app.logout_wipes_data` | S | json({sr:bool, amo:bool, tso:bool}) | {sr:false, amo:false, tso:true} (docs/08; A for SR/AMO; G-feat-41) | — | ROLE | `cfg.sec` | B | C3 | Wiping a phone with pending rows loses sales — the app must refuse a wipe while `pending > 0` regardless |
| `cfg.app.default_locale` | S | enum(`bn`, `en`) | `bn` | — | G, ROLE, U | `cfg.ops` | B | C0 | — |
| `cfg.app.outlet_list_label_format` | S | text template | `{name} ({code}-{phone}-{cluster})` (spec) | tokens validated | G, ROLE | `cfg.field` | B | C0 | — |
| `cfg.app.home_tiles` | S | list<tile_id> per role | spec tile lists (docs/06–08) | ids from a fixed set | ROLE, WAVE | `cfg.ops` | B | C1 | Hiding "Sales Submit" by mistake blocks day close |
| `cfg.app.kpi_strip_items` | S | list | spec KPI strip | — | ROLE | `cfg.ops` | B | C0 | — |
| `cfg.app.activity_log_sample_pct` | S | pct | 10 (G-scale-07) | 0–100 | G, ROLE | `cfg.ops` | B | C1 | 100 → 0.4 M rows/day |
| `cfg.app.telemetry_enabled` | S | bool | true | — | G, WAVE | `cfg.ops` | B | C1 | Telemetry rides inside sync batches only |
| `cfg.support.contacts` | T (`app.support_contact`) | list<{label_bn, label_en, phone, hours, scope}> | AKTCL helpdesk (A placeholder) | 1–10 | G, W, D, T | `cfg.ops` | B | C0 | — |
| `cfg.support.max_upload_mb` | S | int | 20 | 5–100 | G | `cfg.ops` | B | C1 | "PDA to Support" upload cap |
| `cfg.support.pda_upload_wifi_only` | S | bool | true | — | G | `cfg.ops` | B | C1 | — |

### 1.6 Release, rollout and kill switches (`cfg.release.*`, `cfg.ops.*`, `cfg.flag.*`) — docs/06 in-app update, docs/11 waves, D-scale-9

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.release.min_version` | S | semver | current pilot build | must reference a `published` row in `app_release`; may only increase; cannot exceed `latest_version` | G, WAVE, W, D, T | `cfg.release` | B+P (the login response and every `X-Config-Version` header carry it; blocks a **new day's login**, never upload or local capture — D-scale-9) | C3 | Typo `2.0.0` while fleet is on `1.4.x` = 8,500 lockouts at 07:00 |
| `cfg.release.latest_version` | S | semver | — | references `app_release` | G, WAVE | `cfg.release` | B | C1 | Drives the "update available" prompt |
| `cfg.release.update_url` | S | url | Blob/Front Door URL of the APK | https; host allow-list; signed APK checksum present in `app_release` | G, WAVE | `cfg.release` | B | C2 | Wrong URL → updater fails fleet-wide |
| `cfg.release.update_prompt_policy` | S | enum(`silent`, `prompt`, `force_after_date`) | `prompt` | enum | G, WAVE | `cfg.release` | B | C2 | `force` on a 90 MB download over mobile data breaks R4 |
| `cfg.release.update_wifi_only` | S | bool | true (G-scale-16) | — | G, WAVE | `cfg.release` | B | C2 | — |
| `cfg.release.blocked_versions` | S | list<semver> | [] | each must exist in `app_release` | G, WAVE | `cfg.release` | B+P (blocks **new captures** on that build; upload continues) | C3 | Blocking the current build stops selling |
| `cfg.release.wave_pct` | S | pct | 100 | 0–100 | WAVE | `cfg.release` | S | C2 | Staged exposure within a wave by stable device hash |
| `cfg.release.apk_max_mb` | S | int | 45 (docs/04: "materially smaller than 90 MB") | 20–90 | G | `cfg.release` | S (CI gate reads it) | C1 | — |
| `cfg.ops.kill_switch` | O | enum(`off`, `read_only`, `block_login`) | `off` | enum; max duration `kill_switch_max_h` then auto-expires to `off` unless renewed | G, WAVE, W, D, T, Z | `cfg.ops` (break-glass allowed, §4.9) | S+P | C3 | Left on by accident → a day of selling lost; auto-expiry is the rail |
| `cfg.ops.kill_switch_max_h` | S | int | 4 (A) | 1–24 | G | `cfg.sec` | S | C2 | — |
| `cfg.ops.read_only_mode` | O | bool | false | — | G | `cfg.ops` | S (API returns 503 + Retry-After on writes) | C3 | Migration window brake; uploads queue on device |
| `cfg.ops.sync_hold_s` | O | int | 0 | 0–900 | G, W, D, T, Z, WAVE | `cfg.ops` | B+P | C2 | Emergency back-off, randomised per device; forgetting to reset delays the evening sync |
| `cfg.ops.bundle_hold` | O | bool | false | — | G, W | `cfg.ops` | S (API returns 503 + Retry-After on bundle) | C3 | Morning storm brake; devices fall back to `stale_max_days` |
| `cfg.ops.maintenance_banner` | O | json({bn, en, from, to, severity}) | null | ≤ 200 chars each | G, W, D, T, Z, ROLE | `cfg.ops` | B | C0 | — |
| `cfg.ops.dashboard_refresh_min_s` | S | int | 60 | 30–300 | G | `cfg.ops` | S | C1 | — |
| `cfg.ops.report_export_max_rows` | S | int | 200 000 (A) | 10 000–1 000 000 | G, ROLE | `cfg.ops` | S | C1 | — |
| `cfg.ops.report_concurrency_per_user` | S | int | 2 (A) | 1–5 | G | `cfg.ops` | S | C1 | — |
| `cfg.api.rate_limit_per_device_per_min` | S | int | 120 | 30–600 | G, ROLE | `cfg.ops` | S | C2 | Too low throttles the evening sync |
| `cfg.api.rate_limit_per_user_web_per_min` | S | int | 300 (A) | 60–2000 | G, ROLE | `cfg.ops` | S | C1 | — |
| `cfg.agg.poll_interval_s` | S | int | 5 (lens-data/lens-scale outbox worker) | 1–60 | G | `cfg.ops` | S | C1 | — |
| `cfg.agg.claim_batch` | S | int | 200 | 20–2000 | G | `cfg.ops` | S | C1 | — |
| `cfg.agg.late_data_recompute_days` | S | int | 7 | 1–60 | G | `cfg.ops` | S | C2 | Below `sync.max_backdate_days` → accepted late rows never reach the facts |
| `cfg.flag.<feature>` (one key per flag, registered in `config_item` with `area=flag`) | S | bool | per flag | — | G, ROLE, WAVE, W, D, T, Z, U, DEV | `cfg.release` | B | C2 (C3 for flags that gate the day flow) | Flag list in §4.6; a flag off at WAVE lets a wave run the old behaviour during parallel pilot |

### 1.7 Auth, security and privacy (`cfg.auth.*`, `cfg.pii.*`) — docs/02, docs/09 Credentials, Q4

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.auth.access_ttl_min` | S | int | 60 (lens-scale) | 15–1440 | G, ROLE | `cfg.sec` | L (new tokens) | C2 | 15 min + offline day = token expires in the field; the app must never need a live token to sell (local session) |
| `cfg.auth.refresh_ttl_days` | S | int | 30 | 7–90 | G, ROLE | `cfg.sec` | L | C3 | < 7 → Eid weekend logs everyone out |
| `cfg.auth.refresh_rotation` | S | bool | true | — | G | `cfg.sec` | L | C1 | — |
| `cfg.auth.offline_session_max_days` | S | int | 3 (A) | 1–14 | G, ROLE | `cfg.sec` | B | C2 | Days a cached-credential login works with no network (G-scale-05) |
| `cfg.auth.password_min_len` | S | int | 12 (spec) | 8–64 | G, ROLE | `cfg.sec` | S | C2 | SRs type on 4-inch screens; `ROLE=sr` may be lower **(unknown; confirm whether SR passwords follow the web rule)** |
| `cfg.auth.password_complexity` | S | json({upper, lower, digit, symbol}) | {upper:true, lower:true, digit:true, symbol:false} (spec) | — | G, ROLE | `cfg.sec` | S | C1 | — |
| `cfg.auth.password_history_depth` | S | int | 10 (spec) | 0–24 | G | `cfg.sec` | S | C1 | — |
| `cfg.auth.password_min_age_h` | S | int | 24 (spec) | 0–168 | G | `cfg.sec` | S | C1 | — |
| `cfg.auth.password_max_age_days` | S | int | 0 = never (A) | 0–365 | G, ROLE | `cfg.sec` | S | C2 | 90 days on 8,500 SRs = a reset wave the helpdesk cannot absorb |
| `cfg.auth.lockout_attempts` | S | int | 10 (A) | 3–50 | G, ROLE | `cfg.sec` | S | C2 | 3 on shared phones at 07:00 = helpdesk flood |
| `cfg.auth.lockout_min` | S | int | 15 | 1–1440 | G | `cfg.sec` | S | C1 | — |
| `cfg.auth.otp_ttl_min` | S | int | 30 (A; TSO relays by phone call) | 5–1440 | G | `cfg.sec` | S | C2 | 5 min is too short for a phone relay; cutover day may need 24 h (set at WAVE) |
| `cfg.auth.otp_length` | S | int | 6 | 4–8 | G | `cfg.sec` | S | C1 | — |
| `cfg.auth.otp_max_active_per_user` | S | int | 1 | 1–3 | G | `cfg.sec` | S | C1 | — |
| `cfg.auth.max_devices_per_user` | S | int | 2 (A: shared phones, G-feat-41) | 1–5 | G, ROLE | `cfg.sec` | S | C2 | 1 → every phone swap needs a TSO |
| `cfg.auth.max_users_per_device` | S | int | 3 (A) | 1–10 | G | `cfg.sec` | S | C2 | — |
| `cfg.auth.device_rebind_requires_otp` | S | bool | true | — | G | `cfg.sec` | S | C2 | — |
| `cfg.auth.revoked_device_grace_upload_h` | S | int | 72 (A; G-feat-63) | 0–168 | G | `cfg.sec` | S | C2 | 0 → pending sales on a revoked phone are unrecoverable |
| `cfg.auth.web_session_idle_min` | S | int | 30 | 5–480 | G, ROLE | `cfg.sec` | S | C1 | — |
| `cfg.auth.web_mfa_roles` | S | list<role> | [`admin`] (A) | — | G | `cfg.sec` | L | C2 | — |
| `cfg.auth.scope_token_version_check` | S | bool | true | — | G | `cfg.sec` | S | C2 | A `user_scope` change bumps the user's `scope_version`; tokens with an older version are refused → forces refresh (G-cfg-12) |
| `cfg.pii.field_roles` | S | json({field: list<role>}) | {nid:[admin], tin:[admin], trade_license:[admin,tso], contact_number:[sr,amo,tso,admin], owner_name:[all], address:[all]} (A; G-feat-59 **unknown; confirm matrix**) | fields from the outlet PII set; roles valid | G | `cfg.sec` | S (reads) + L (bundle) | C3 | Over-exposure is irreversible once on 8,500 phones |
| `cfg.pii.mask_style` | S | enum(`hide`, `last4`) | `last4` | enum | G | `cfg.sec` | S | C0 | — |
| `cfg.pii.export_allowed_roles` | S | list<role> | [`admin`] | — | G | `cfg.sec` | S | C2 | Excel exports carry PII out of the system |
| `cfg.pii.log_scrub_patterns` | S | list<regex> | NID/phone/TIN patterns | valid regex | G | `cfg.sec` | S | C1 | — |

### 1.8 Programs: loyalty, Astha, Superstar, promotions, targets (`cfg.loyalty.*`, `cfg.astha.*`, `cfg.superstar.*`, `cfg.promo.*`, `cfg.target.*`) — docs/10, Q12, Q13

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.loyalty.program_active` | S | bool | true (A; Q13 **unknown; confirm**) | — | G, W, D, T | `cfg.program` | B | C2 | false hides Loyalty tile; pending redemptions still sync |
| `cfg.loyalty.period` | S | enum(`month`, `quarter`) | `month` (spec: "Diamond League (<month>)") | future-dated only | G | `cfg.program` | B+S | C3 | Changing mid-period re-buckets balances |
| `cfg.loyalty.cash_rate_mtk_per_point` | S | money_mtk | 2000 (= 2 Tk/point, spec) | 0–100 000 | G, W | `cfg.program` | B | C3 | 20 Tk/point = 10× payout on confirm |
| `cfg.loyalty.cash_max_points` | S | int | 199 (spec) | 0–100 000 | G, W | `cfg.program` | B | C2 | — |
| `cfg.loyalty.gift_catalog` | T (`app.loyalty_gift`) | list<{code, name_bn, name_en, points, image, active, valid_from, valid_to, stock?}> | kitchen rack 200, chair 200, tornado fan 400 (spec) | points 1–100 000; code immutable | G, W, D | `cfg.program` | B | C2 | Redemptions reference `gift.code` + `points` at time of redemption, so a price change never rewrites history |
| `cfg.loyalty.earning_rules` | T (`app.loyalty_rule`) | json(rule schema: {trigger: memo_std / memo_count / brand_std, product_scope, points_per_unit, min_qty, period}) | empty (G-feat-20 **unknown; confirm**) | schema-validated | G, W | `cfg.program` | S (points computed at ingest) | C3 | Wrong rule = wrong balances for 4.6 lakh outlets; rule changes are future-dated and never recompute closed periods |
| `cfg.loyalty.redemption_requires_photo` | S | bool | true (spec: "Gift Verify") | — | G | `cfg.program` | B | C1 | — |
| `cfg.loyalty.redemption_roles` | S | list<role> | [`sr`, `amo`] | — | G | `cfg.program` | B | C1 | — |
| `cfg.loyalty.negative_balance_policy` | S | enum(`block`, `allow_flag`) | `block` | — | G | `cfg.program` | B+S | C2 | — |
| `cfg.loyalty.manual_adjust_max_points` | S | int | 1000 (A) | 0–100 000 | G | `cfg.program` | S | C2 | Above this an adjustment (F-ADM-035) needs two-person approval |
| `cfg.astha.program_active` | S | bool | true (A; Q13) | — | G, W | `cfg.program` | B | C2 | — |
| `cfg.astha.quarter_start_month` | S | int | 1 (A: Q4 = Oct–Dec per docs/06 implies calendar quarters) | 1–12 | G | `cfg.program` | S | C3 | Future-dated only |
| `cfg.astha.tiers` | T (`app.sub_channel` rows of channel Astha) | list<{code,name,sort}> | Platinum, Gold, Diamond, Silver (spec) | — | G | `cfg.program` | B | C1 | Master data, versioned |
| `cfg.astha.brands_in_scope` | S | list<brand_id> | spec brand list (Maxim, Black Diamond, Abul Bidi Style, Marise, Avon, Supreme, Special Abul Bidi, Abul Bidi Gold …) | ids exist | G, W | `cfg.program` | B | C1 | — |
| `cfg.astha.gift_catalog` | T (`app.astha_gift`) | list<{code, name, qty_label e.g. "27 pcs Dinner Set", image, active}> | — | — | G, W | `cfg.program` | B | C1 | — |
| `cfg.astha.gift_choice_roles` | S | list<role> | [`tso`] ("TSO portal", G-feat-21 **unknown; confirm**) | — | G | `cfg.program` | S | C1 | — |
| `cfg.astha.one_photo_per_outlet` | S | bool | true (spec) | — | G | `cfg.program` | B | C1 | — |
| `cfg.astha.target_entry_roles` | S | list<role> | [`admin`, `tso`] | — | G | `cfg.program` | S | C2 | — |
| `cfg.superstar.program_active` | S | bool | false (A until Q13) | — | G, W | `cfg.program` | B | C2 | — |
| `cfg.superstar.slabs` | T (`app.superstar_slab`) | list<{code, category, base_target_rule, incentive, criteria}> | empty (G-feat-22 **unknown**) | schema | G, W | `cfg.program` | S | C2 | — |
| `cfg.superstar.criteria_met_rule` | S | json | {std_pct:100, memo_pct:100} (A) | 0–200 each | G, W | `cfg.program` | S | C2 | — |
| `cfg.promo.engine_enabled` | S | bool | true | — | G, W, D, T, Z | `cfg.program` | B | C2 | Brake if a rule misfires on launch day |
| `cfg.promo.rules` | T (`app.offer` / `app.promotion`, lens-data M-2x) | json(rule schema: {group_code, type: qty_discount / free_sku / pct / drp_credit / sample, product_scope, channel_scope, geo_scope, min_qty, value, valid_from, valid_to, stackable, priority}) | ~22 groups (G-feat-13 **unknown; schema must be reconstructed from the Discount Report**) | schema-validated; no overlapping non-stackable rules on the same SKU+scope | G, W, D, T, Z, (O for outlet-specific) | `cfg.program` | B (bundle carries rules effective in the next `schedule_horizon_days`) | C3 | Memo totals ≠ Apsis → retailer disputes; rules apply by `valid_from` using the **business date of the memo**, so a late sync gets the rule that was live on the day |
| `cfg.promo.max_discount_pct_per_memo` | S | pct | 50 (A) | 0–100 | G | `cfg.program` | B+S | C2 | Guard against a rule typo giving away stock |
| `cfg.promo.free_sample_enabled` | S | bool | true (spec report exists) | — | G, W | `cfg.program` | B | C1 | — |
| `cfg.promo.free_sample_max_per_outlet_day` | S | int | 2 (A) | 0–50 | G, W | `cfg.program` | B | C1 | — |
| `cfg.promo.drp_offer_map` | T (`app.offer` type `drp_credit`) | list<{drp_kind, qty_per_credit, credit_mtk or free_sku}> | — (G-feat-13) | — | G, W | `cfg.program` | B | C2 | — |
| `cfg.target.min_value` | S | int | 0 (spec: validate ≥ 0; the −20 bug) | 0 fixed; exposed so the rail is visible, not editable below 0 | G | `cfg.ops` | S | C3 | — |
| `cfg.target.allow_fractional_std` | S | bool | true (spec) | — | G | `cfg.program` | S | C1 | — |
| `cfg.target.split_method` | S | enum(`proportional_history`, `equal`, `manual`) | `proportional_history` (A; Q12 **unknown; confirm**) | enum; future-dated | G, W | `cfg.program` | S | C3 | Every achievement % depends on it |
| `cfg.target.split_history_months` | S | int | 3 (A) | 1–12 | G | `cfg.program` | S | C2 | — |
| `cfg.target.revision_approval_levels` | T (`app.approval_level_config`) | list<{level, role, scope_type, min_change_pct_for_level}> | [{1,tso},{2,dmo},{3,wm}] (A; Q12 **unknown**) | 1–5 levels; roles valid | G, W | `cfg.program` | S | C3 | Zero levels = anyone edits targets |
| `cfg.target.revision_window_days_before_month_end` | S | int | 5 (A) | 0–31 | G | `cfg.program` | S | C1 | — |
| `cfg.target.tilldate_basis` (= `cfg.kpi.tilldate_basis`) | S | enum(`calendar_days`, `working_days`) | `working_days` (lens-data D-0x; uses the holiday calendar) | enum; future-dated | G | `cfg.program` | S | C3 | Changes every till-date % |
| `cfg.target.achievement_pct_cap` | S | int | 1000 (A: display clamp) | 100–100 000 | G | `cfg.ops` | S | C1 | Prevents the −37,500 % class of display; the raw value is still stored |
| `cfg.target.supervisor_targets` | T (`app.supervisor_target`) | per AMO: call target, control-call target, joint-call target per month | — (G-feat-57 **unknown**) | ≥ 0 | Z, T | `cfg.program`, `tso` | B | C1 | — |

### 1.9 Content, questionnaires and master lists (`cfg.survey.*`, `cfg.rubric.*`, `cfg.content.*`, `cfg.task.*`, `cfg.leave.*`, `cfg.tso.*`) — docs/06–08

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.survey.posm_questions` | T (`app.survey_question`, kind=`posm`) | list<{key, text_bn, text_en, type: yes_no / choice / number / photo, required, photo_required, sort, active, valid_from, valid_to}> | spec: POSM survey + photo (G-feat-12 **unknown; questions to capture from the running app**) | ≤ 30 active; key immutable | G, W, D, T, Z | `cfg.program` | B | C1 | Removing a question that has answers → deactivate, never delete |
| `cfg.survey.tso_visit_query_questions` | T (same table, kind=`tso_visit_query`) | list | "Does the SR visit regularly?", "Does the SR print memos regularly?" (spec) | — | G | `cfg.program` | B | C1 | — |
| `cfg.survey.photo_required` | S | bool | true (spec) | — | G, T | `cfg.program` | B | C1 | — |
| `cfg.rubric.joint_call` | T (`app.assessment_rubric`, kind=`joint_call`) | list<{section, item_key, text_bn, text_en, scale_min:1, scale_max:5, weight, sort, active}> | spec sections (5-step sales call, relationship, service quality; steps 4–5 **unknown**, G-feat-56) | 1–50 items; scale 1–5 fixed unless future-dated | G, W | `cfg.program` | B | C1 | — |
| `cfg.rubric.distribution_brands` | S | list<brand_id> | spec 15-brand list (docs/07) | ids exist and `sales_enable` | G, W, D, T, Z | `cfg.program` | B | C1 | Falls back to the zone's sales plan if empty |
| `cfg.content.avkv_items` | T (`app.content_item`) | list<{title, media, scope, valid_from, valid_to}> | — | media ≤ `cfg.content.max_item_mb` | G, W, D, T, Z | `cfg.program` | B (downloaded Wi-Fi-preferred, LRU-cached) | C1 | A 50 MB video pushed to 8,500 phones over mobile data breaks R4 |
| `cfg.content.max_item_mb` | S | int | 8 (A) | 1–50 | G | `cfg.ops` | S | C2 | — |
| `cfg.content.download_network_policy` | S | enum(`wifi_only`, `wifi_preferred`, `any`) | `wifi_only` (A) | — | G, W | `cfg.ops` | B | C2 | — |
| `cfg.content.tutorial_videos` | T (`app.tutorial_asset`) | list<{audience roles, kind, title_bn, title_en, url, sort}> | empty (spec: list may be empty) | url https | G, ROLE | `cfg.ops` | B | C0 | — |
| `cfg.task.types` | T (`app.task_type`) | list<{code, label_bn, label_en, active, default_due_days}> | oos, general, irregular_visit (spec) | code immutable | G | `cfg.field` | B | C1 | — |
| `cfg.task.default_due_days` | S | int | 3 (A) | 1–30 | G | `cfg.field` | B | C0 | — |
| `cfg.task.overdue_escalation_days` | S | int | 2 (A; G-feat-19) | 0–30 | G | `cfg.field` | S | C1 | — |
| `cfg.task.assign_roles` | S | list<role> | [`amo`, `tso`] (spec) | — | G | `cfg.field` | B | C1 | — |
| `cfg.leave.types` | T (`app.leave_type`) | list<{code, label, max_days_per_year}> | Casual, Sick, Earn (spec); caps **unknown; confirm** | — | G | `cfg.field` | B | C1 | — |
| `cfg.leave.approver_role_by_applicant_role` | S | json({tso:dmo, amo:tso, sr:amo}) | tso→dmo (spec); others A (G-feat-25) | roles valid | G | `cfg.field` | S | C2 | — |
| `cfg.leave.max_consecutive_days` | S | int | 30 (A) | 1–90 | G | `cfg.field` | B | C1 | — |
| `cfg.tso.periphery_radius_options_m` | S | list<int> | [50, 100, 300] (spec) | 1–6 values, each 10–5000 | G, W | `cfg.field` | B | C0 | — |
| `cfg.tso.team_location_max_age_min` | S | int | 120 (A; G-feat-36) | 5–1440 | G | `cfg.field` | S | C1 | Fixes older than this are greyed as stale |
| `cfg.tso.visit_plan_max_outlets` | S | int | 30 (A) | 1–200 | G | `cfg.field` | B | C1 | — |
| `cfg.tso.product_scope` | S | list<category_id> | all tobacco categories (A; Q14 Digonto **unknown**) | ids exist | ROLE, W | `cfg.program` | L | C1 | — |
| `cfg.feedback.categories` | T (`app.reason_code`, kind=`feedback`) | list | Suggestion, Complaint, Bug (A) | — | G | `cfg.ops` | B | C0 | — |
| `cfg.i18n.overrides` | T (`app.i18n_override`) | map<string_key, {bn, en}> | empty | key must exist in the bundled catalogue | G, W, ROLE | `cfg.ops` | B | C0 | Lets the business fix a Bangla label without a release; a missing key falls back to the bundled string |

### 1.10 Calendar, KPI, SLA and data retention (`cfg.calendar.*`, `cfg.kpi.*`, `cfg.sla.*`, `cfg.retention.*`) — docs/09–10, lens-data §4–5, lens-scale §5

| Key | Kind | Type | Default | Range / validation | Scope | Editor | Effect | Risk | Risk if mis-set |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.calendar.weekend_days` | S | list<dow> | [`Fri`] (A: Bangladesh; **unknown; confirm whether Sat is a selling day**) | 0–3 days | G, W, D | `cfg.field` | S+B | C3 | Changes Login % denominators and till-date targets for everyone |
| `cfg.calendar.holidays` | T (`app.holiday_calendar`) | list<{date, name_bn, name_en, scope, selling_day: bool}> | national list (A: to be entered) | date-ranged; scope G/W/D/T | G, W, D, T | `cfg.field` | S+B | C2 | A missing Eid day shows 0 % login on a holiday and pages SRE |
| `cfg.calendar.route_visit_day_exceptions` | T (`app.route_day_override`) | list<{route_id, date, planned: bool, reason}> | empty (G-feat-10) | — | R | `cfg.field`, `tso` | B | C1 | — |
| `cfg.kpi.bands` | S | list<{min_pct, label, colour}> | [≥100 green, 90–100 amber, 80–90 orange, <80 red] (spec) | non-overlapping, ascending | G, ROLE | `cfg.program` | S+B | C1 | — |
| `cfg.kpi.submit_pct_denominator` | S | enum(`target_routes`, `logged_in_routes`) | web `target_routes`, app `logged_in_routes` as two **differently named** KPIs (SF-7); this key selects which one is shown under the label "Submit %" | enum | G, ROLE | `cfg.program` | S | C2 | Two numbers with one name in a board meeting |
| `cfg.kpi.bsr_denominator` | S | enum(`total_memos`, `target_outlets`) | `total_memos` (A; Q9 **unknown; confirm**) | enum; future-dated | G | `cfg.program` | S | C3 | — |
| `cfg.kpi.login_event_definition` | S | enum(`first_full_bundle`, `any_bundle`) | `first_full_bundle` (SF-9) | enum | G | `cfg.ops` | S | C2 | `any_bundle` inflates Login % by every delta refresh |
| `cfg.kpi.cpr_counts_zero_sale_as_successful` | S | bool | false (A; G-feat-38 **unknown; confirm**) | — | G | `cfg.program` | S | C3 | Changes CPR nationally |
| `cfg.kpi.retention_definition` | S | text/formula | unknown (Q10) | — | G | `cfg.program` | S | C2 | — |
| `cfg.sla.login_pct_alert_time` | S | time | 10:00 (A) | 07:00–14:00 | G, W | `cfg.ops` | S | C1 | Time at which Login % below `login_pct_alert_threshold` pages |
| `cfg.sla.login_pct_alert_threshold` | S | pct | 70 (A) | 0–100 | G, W, D, T | `cfg.ops` | S | C1 | — |
| `cfg.sla.submit_pct_alert_time` / `threshold` | S | time / pct | 21:00 / 80 (A) | — | G, W, D, T | `cfg.ops` | S | C1 | — |
| `cfg.sla.geo_valid_drop_alert_pts` | S | int | 10 (A: percentage points vs 7-day baseline, same scope) | 1–50 | G | `cfg.ops` | S | C2 | The anomaly watch armed by every C3 geo change |
| `cfg.sla.force_sale_pct_alert` | S | pct | 25 (A) | 1–100 | G, W, D, T, Z | `cfg.field` | S | C1 | — |
| `cfg.sla.sync_p95_ms_alert` | S | int | 2000 | 200–10000 | G | `cfg.ops` | S | C1 | — |
| `cfg.sla.sync_error_rate_alert_pct` | S | pct | 2 | 0.1–20 | G | `cfg.ops` | S | C1 | — |
| `cfg.sla.quarantine_backlog_alert` | S | int | 500 (A) | 1–100 000 | G | `cfg.ops` | S | C1 | — |
| `cfg.sla.config_ack_pct_alert` | S | pct | 90 (A: alert if < 90 % of online devices acked a change after 60 min) | 0–100 | G | `cfg.ops` | S | C1 | Tells the admin that a change did not reach the field |
| `cfg.sla.config_ack_window_min` | S | int | 60 (A) | 5–1440 | G | `cfg.ops` | S | C1 | — |
| `cfg.sla.mock_gps_pct_alert` | S | pct | 5 (A) | 0–100 | G, W, D, T, Z | `cfg.field` | S | C1 | — |
| `cfg.retention.*` (owned by lens-data §5.1: `activity_log_days`, `raw_fix_days`, `media_days`, `sync_batch_response_h` …) | S | int | as lens-data | each ≥ the legal/business minimum stated there; audit tables are `indefinite` and **not editable** | G | `cfg.ops` | S | C2 | Shortening below a report's horizon deletes evidence |
| `cfg.sys.schedule_horizon_days` | S | int | 7 (A) | 1–30 | G | `cfg.ops` | B | C1 | How far ahead scheduled (future-dated) values are shipped in the bundle so an offline phone applies them on time |
| `cfg.sys.config_delta_max_age_versions` | S | int | 500 (A) | 50–10 000 | G | `cfg.ops` | S | C1 | A device further behind than this gets a full snapshot instead of a delta |

### 1.11 What is deliberately **not** config

| Item | Why it stays in code or master data |
| --- | --- |
| The idempotency rule (`client_uuid` upsert), the money unit (milli-taka), the business-date time zone (Asia/Dhaka), the API envelope | Correctness invariants; making them switches creates failure modes with no business upside |
| Haversine formula, memo total arithmetic (given `rounding_mode`) | Formula is law; parameters are config |
| Geography, products, prices, sales plan, routes, assignments, users, outlets, targets | Master data with its own tables, workflows and audit (`@AUDIT`, `@SCD`); edited in the same console (§4.4) but not through `config_value` |
| The set of roles | Schema enum; adding a role is a release because scope resolution and PII rules depend on it |
| Which key is C0–C3 | Set in `config_item.risk_class` by the engineering team at registration; `cfg.sec` may **raise** a class from the GUI, never lower it (G-cfg-09) |

---

## 2. The config data model

Schema `cfg` (Postgres), owned by this lens; lens-data M-45 is the migration slot and the DDL below supersedes the M-45 sketch where they differ (the differences: `scope_level` precedence table, `config_change_request` for approvals, `config_version` as a table not only a sequence, `risk_class`, `delivery` split into `delivery_device`/`delivery_server`, and `config_snapshot` for the bundle).

### 2.1 Tables

```sql
CREATE SCHEMA cfg;

-- Scope levels and their precedence (higher wins). Data, not an enum, so a level can be added without a release.
CREATE TABLE cfg.scope_level (
  scope_type   text PRIMARY KEY,         -- global | role | wave | wing | division | territory | house | zone | route | outlet | user | device
  precedence   int  NOT NULL UNIQUE,     -- global 0, role 10, wave 20, wing 30, division 40, territory 50, house 60, zone 70, route 80, outlet 90, user 100, device 110
  id_table     text,                     -- the table scope_id references (NULL for global); validated by trigger
  description  text);

-- The registry: one row per key. Inserted by migration, never by the GUI (adding a key is code because the consumer is code).
CREATE TABLE cfg.config_item (
  key              text PRIMARY KEY,                         -- cfg.geo.radius_m
  area             text NOT NULL,                            -- geo | day | memo | sale | credit | qc | drp | stock | media | sync | bundle | app | support | release | ops | api | agg | flag | auth | pii | loyalty | astha | superstar | promo | target | survey | rubric | content | task | leave | tso | feedback | i18n | calendar | kpi | sla | retention | sys
  kind             text NOT NULL CHECK (kind IN ('S','T','O')),
  value_type       text NOT NULL,                            -- int | bool | time | text | url | semver | money_mtk | pct | list | json | enum
  json_schema      jsonb,                                    -- for list/json: a JSON Schema the API validates against
  constraints      jsonb NOT NULL DEFAULT '{}',              -- {"min":20,"max":2000} | {"enum":[..]} | {"regex":".."} | {"max_items":10} | {"ref_table":"app_release","ref_col":"version","ref_status":"published"}
  default_value    jsonb NOT NULL,
  scope_levels     text[] NOT NULL,                          -- subset of cfg.scope_level.scope_type that may hold overrides
  editor_permissions text[] NOT NULL,                        -- e.g. {cfg.field, tso}
  risk_class       smallint NOT NULL CHECK (risk_class BETWEEN 0 AND 3),
  delivery_device  text NOT NULL CHECK (delivery_device IN ('none','bundle','bundle+push')),
  delivery_server  text NOT NULL CHECK (delivery_server IN ('none','immediate')),
  future_dated_only boolean NOT NULL DEFAULT false,          -- true: effective_from must be a future business date (rounding_mode, qty_entry_unit, period keys)
  canary_required  boolean NOT NULL DEFAULT false,           -- true for C3 keys at scope >= territory
  anomaly_watch    text[],                                   -- KPIs to watch after a change: {geo_valid_pct, force_sale_pct, login_pct, submit_pct}
  content_table    text,                                     -- for kind T: the table whose rows this key versions (app.loyalty_gift ...)
  description_en   text NOT NULL, description_bn text,
  deprecated_at    timestamptz, replaced_by text REFERENCES cfg.config_item(key),
  introduced_in_phase smallint NOT NULL);

-- The values. One row per (key, scope, effective range). Never updated in place: a change closes the old row (effective_to) and inserts a new one.
CREATE TABLE cfg.config_value (
  id               bigserial PRIMARY KEY,
  key              text NOT NULL REFERENCES cfg.config_item(key),
  scope_type       text NOT NULL REFERENCES cfg.scope_level(scope_type),
  scope_id         bigint NOT NULL DEFAULT 0,                -- 0 for global; role id / wave id / geography id / user id / device id otherwise
  value            jsonb NOT NULL,
  effective_from   timestamptz NOT NULL DEFAULT now(),       -- stored UTC; the GUI edits in Asia/Dhaka
  effective_to     timestamptz,                              -- NULL = open
  config_version   bigint NOT NULL REFERENCES cfg.config_version(version),   -- the version this row was created in
  change_request_id bigint NOT NULL REFERENCES cfg.config_change_request(id),
  set_by           bigint NOT NULL REFERENCES app.app_user(id),
  set_at           timestamptz NOT NULL DEFAULT now(),
  reason           text NOT NULL,
  EXCLUDE USING gist (key WITH =, scope_type WITH =, scope_id WITH =,
                      tstzrange(effective_from, effective_to, '[)') WITH &&));
CREATE INDEX ON cfg.config_value (key, scope_type, scope_id, effective_from DESC);
CREATE INDEX ON cfg.config_value (config_version);

-- Monotonic version. One row per committed change set; the sequence value is what headers, bundles, visits and acks carry.
CREATE TABLE cfg.config_version (
  version          bigint PRIMARY KEY,                       -- nextval('cfg.config_version_seq'), taken inside the applying transaction
  change_request_id bigint NOT NULL,
  committed_at     timestamptz NOT NULL DEFAULT now(),
  committed_by     bigint NOT NULL,
  summary          text NOT NULL,                            -- "cfg.geo.radius_m territory 44: 100 -> 150"
  scope_type       text NOT NULL, scope_id bigint NOT NULL,  -- for ack targeting (which devices must learn about it)
  devices_targeted int,                                      -- computed at commit from device x scope (NULL for server-only keys)
  is_revert_of     bigint REFERENCES cfg.config_version(version));

-- Approval workflow. Every change goes through a request, even single-approver ones (then requester = approver and it auto-applies).
CREATE TABLE cfg.config_change_request (
  id               bigserial PRIMARY KEY,
  status           text NOT NULL CHECK (status IN ('draft','pending_approval','approved','applied','rejected','cancelled','expired','reverted')),
  changes          jsonb NOT NULL,          -- [{key, scope_type, scope_id, old_value, new_value, effective_from, effective_to}] — a change set is atomic
  max_risk_class   smallint NOT NULL,       -- max over the keys in the set; decides the rails
  blast_radius     jsonb NOT NULL,          -- {"zones":1051,"routes":9120,"outlets":460112,"devices":8412,"users":8500} computed at submit
  canary_of        bigint REFERENCES cfg.config_change_request(id),   -- this request is the fleet-wide follow-up of a canary request
  requested_by     bigint NOT NULL, requested_at timestamptz NOT NULL DEFAULT now(), reason text NOT NULL,
  approved_by      bigint, approved_at timestamptz, approval_note text,
  rejected_by      bigint, rejected_at timestamptz, rejection_note text,
  break_glass      boolean NOT NULL DEFAULT false,            -- single-person emergency apply of a C3 key; forces a review row
  review_due_at    timestamptz, reviewed_by bigint, reviewed_at timestamptz,
  apply_at         timestamptz,                                -- NULL = apply on approval; else scheduled (C2 "delayed apply with cancel" uses now()+10 min)
  applied_version  bigint REFERENCES cfg.config_version(version),
  expires_at       timestamptz NOT NULL,                       -- pending requests expire (default 72 h)
  CONSTRAINT two_person CHECK (break_glass OR max_risk_class < 3 OR approved_by IS NULL OR approved_by <> requested_by));

-- Immutable audit. Append-only; REVOKE UPDATE, DELETE from every role; also mirrored to dw.fact_config_change (lens-data).
CREATE TABLE cfg.config_audit (
  id               bigserial PRIMARY KEY,
  at               timestamptz NOT NULL DEFAULT now(),
  actor_id         bigint NOT NULL, actor_role text NOT NULL, actor_ip inet, actor_user_agent text,
  action           text NOT NULL,           -- request_created | request_approved | request_rejected | request_cancelled | applied | reverted | break_glass_applied | break_glass_reviewed | item_risk_raised | read_pii_export
  change_request_id bigint, config_version bigint,
  key text, scope_type text, scope_id bigint, old_value jsonb, new_value jsonb,
  effective_from timestamptz, effective_to timestamptz,
  reason text, details jsonb);
CREATE INDEX ON cfg.config_audit (key, at DESC);
CREATE INDEX ON cfg.config_audit (actor_id, at DESC);

-- Delivery tracking (R6 "pending until next sync").
CREATE TABLE cfg.config_ack (
  device_id        bigint NOT NULL REFERENCES app.device(id),
  config_version   bigint NOT NULL,
  acked_at         timestamptz NOT NULL DEFAULT now(),
  app_version      text,
  PRIMARY KEY (device_id, config_version));
ALTER TABLE app.device ADD COLUMN config_version bigint, ADD COLUMN config_acked_at timestamptz;   -- latest ack, denormalised for the device list

-- Pre-resolved snapshot per (scope chain hash, version) used by the bundle and the resolver cache. Rebuilt lazily; safe to TRUNCATE.
CREATE TABLE cfg.config_snapshot (
  chain_hash       bytea NOT NULL,          -- sha256 of the ordered (scope_type, scope_id) chain
  config_version   bigint NOT NULL,
  as_of_date       date NOT NULL,           -- business date the snapshot was resolved for
  resolved         jsonb NOT NULL,          -- {key: {value, scope_type, scope_id, effective_from, effective_to}}
  scheduled        jsonb NOT NULL,          -- values whose effective_from is within cfg.sys.schedule_horizon_days, same shape, in a list
  built_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (chain_hash, config_version, as_of_date));

-- Compatibility view for the importer and legacy code paths (schema.sql territory_geo_config). Read-only.
CREATE VIEW cfg.territory_geo_config AS
  SELECT t.id AS territory_id, cfg.resolve_int('cfg.geo.radius_m', 'territory', t.id, now()) AS radius_m FROM app.territory t;
```

Admin permissions (used by §4.1):

```sql
CREATE TABLE cfg.admin_permission (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app.app_user(id),
  permission text NOT NULL,          -- cfg.view | cfg.ops | cfg.field | cfg.program | cfg.sec | cfg.release | cfg.approve | master.geo | master.product | master.price | master.route | master.user | master.outlet | master.target | device.manage | device.otp | quarantine.review | audit.view | pii.view | pii.export | day.reopen
  scope_type text NOT NULL DEFAULT 'global', scope_id bigint NOT NULL DEFAULT 0,   -- a permission may be bounded to a wing/division/territory/zone
  granted_by bigint NOT NULL, granted_at timestamptz NOT NULL DEFAULT now(), valid_to timestamptz,
  UNIQUE (user_id, permission, scope_type, scope_id));
```

Field-level notes:

| Rule | Why |
| --- | --- |
| `scope_id = 0` for global; `scope_type='role'` uses the role enum ordinal; `scope_type='wave'` references `app.rollout_wave.id` (lens-features F-ADM-031). | One shape for every scope; no NULLs in the exclusion constraint. |
| `effective_from/to` are `timestamptz`, not `date`: check-out time changes at a wall-clock moment; radius changes "from tomorrow 00:00 Dhaka" are entered as a date in the GUI and stored as 18:00 UTC the day before. | Business-date keys (`future_dated_only`) are additionally constrained by trigger to land on a Dhaka midnight. |
| A change never UPDATEs `value`. It sets `effective_to` on the open row and INSERTs the new row in the same transaction with the same `config_version`. | History is queryable: "what was the radius for outlet X at 11:04 on 2026-10-04" is one range lookup; the server re-check depends on this. |
| `config_version` is taken **inside** the applying transaction and committed with the rows; `pg_notify('cfg_changed', version)` fires on commit. | Readers can never see a version number whose rows are not yet visible. |
| `config_audit` is append-only; the API role has INSERT only; the audit writer is a trigger on `config_value`, `config_change_request`, `config_item.risk_class` and `admin_permission`. | R6 "audit trail" cannot be bypassed by a code path that forgets to log. |
| Kind T keys hold no row in `config_value` except a `{ "content_version": n }` marker; the content table carries `@AUDIT`/`@SCD` columns (lens-data §3.2) and its write path calls `cfg.bump_version(key, scope, summary)`. | One version stream and one history page for scalars and content, without duplicating the content into jsonb. |

### 2.2 Resolution rule

1. **Candidate rows** for a key are the `config_value` rows whose `(scope_type, scope_id)` is in the caller's **scope chain** and whose `[effective_from, effective_to)` contains the as-of instant.
2. **Most specific wins**: highest `scope_level.precedence`. Ties are impossible (the exclusion constraint forbids two open rows for one key+scope at one instant).
3. **Fallback**: no candidate → `config_item.default_value`.
4. **Scope chain** for a request is derived server-side (CLAUDE.md rule 4), never from client-sent IDs:
   - **user context** (login, bundle, home): `global` → `role:<r>` → `wave:<w>` (device's wave) → geography of the user's assignment: `wing` → `division` → `territory` → `house` → `zone` → `route` (each route the user is assigned today) → `user:<id>` → `device:<id>`.
   - **outlet context** (geo re-check, credit limit, promo): the outlet's `route`/`zone`/`house`/`territory`/`division`/`wing` **as of the business date** (lens-data `dim_outlet` SCD2, because outlets move routes) → `outlet:<id>`.
   - When both exist (a visit), the outlet chain is used for outlet-shaped keys (`cfg.geo.*`, `cfg.credit.*`, `cfg.promo.*`) and the user chain for the rest; `config_item.scope_levels` already excludes nonsensical combinations (no `outlet` level on `cfg.auth.*`).
5. **Role vs geography** precedence: geography beats role (a territory's radius applies to an AMO selling there), role beats global. A key that needs "role inside a territory" (PII per role per wing) is modelled as a json value keyed by role at the geography scope, not as a cross-product scope.
6. **Resolved provenance** travels with the value: `{value, scope_type, scope_id, effective_from, config_version}`. The GUI shows "100 m — from Territory DHK-N (set by R. Karim on 2026-09-30: 'dense market')"; the app stores `radius_m_used` and `config_version` on the visit.

### 2.3 One-query resolution for a given SR / outlet / instant

```sql
-- $1 = key[] (NULL = all keys), $2 = chain as jsonb [{"t":"global","i":0},{"t":"role","i":1},{"t":"territory","i":44},{"t":"zone","i":5012},{"t":"outlet","i":9912}], $3 = as-of timestamptz
WITH chain AS (
  SELECT (c->>'t')::text AS scope_type, (c->>'i')::bigint AS scope_id FROM jsonb_array_elements($2) c),
cand AS (
  SELECT v.key, v.value, v.scope_type, v.scope_id, v.effective_from, v.config_version, sl.precedence
  FROM cfg.config_value v
  JOIN chain ch ON ch.scope_type = v.scope_type AND ch.scope_id = v.scope_id
  JOIN cfg.scope_level sl ON sl.scope_type = v.scope_type
  WHERE ($1 IS NULL OR v.key = ANY($1))
    AND v.effective_from <= $3 AND (v.effective_to IS NULL OR v.effective_to > $3))
SELECT i.key,
       COALESCE(c.value, i.default_value) AS value,
       COALESCE(c.scope_type, 'default')  AS scope_type,
       c.scope_id, c.effective_from, c.config_version
FROM cfg.config_item i
LEFT JOIN LATERAL (SELECT * FROM cand WHERE cand.key = i.key ORDER BY precedence DESC LIMIT 1) c ON true
WHERE ($1 IS NULL OR i.key = ANY($1)) AND i.deprecated_at IS NULL;
```

Cost: the chain is ≤ 12 pairs; `config_value` is small (≈ 240 keys × overrides; even 2,000 outlet radius overrides and 1,051 zone rows keep it under 10 k rows). The index `(key, scope_type, scope_id, effective_from DESC)` makes each probe an index-only range scan. Measured target: < 2 ms for all keys, < 0.3 ms for one key (T-1-62).

`cfg.resolve_int(key, scope_type, scope_id, as_of)` / `resolve_json(...)` are SQL functions over the same query for use inside the ingest transaction (the geo re-check in `POST /sync/batch` needs `cfg.geo.radius_m` for the visit's outlet **as of `visit.started_at`**, which is exactly this query with `$3 = visit.started_at`).

### 2.4 Server-side cache strategy

| Layer | What | Invalidation | Why |
| --- | --- | --- | --- |
| L0 in-process (per API replica) | `current_version` (int) + LRU of resolved snapshots keyed by `(chain_hash, version, as_of_date)`, max 10 k entries (~ 20 MB) | `LISTEN cfg_changed` → set `current_version`; entries for old versions become unreachable and age out. Every request compares its snapshot's version to `current_version`; a stale entry is never served. Fallback if LISTEN drops: re-read `max(version)` every 30 s. | 8,500 bundle requests in an hour share ~1,051 zone chains; hit rate > 99 %. |
| L1 Redis (Azure Managed Redis, lens-scale D-scale-6) | `cfg:ver` (current version), `cfg:snap:<chain_hash>:<version>:<date>` (resolved jsonb, TTL 36 h), `cfg:delta:<from_version>:<to_version>:<chain_hash>` (TTL 24 h) | Written by whichever replica built it; version in the key means no explicit invalidation. Changing a value publishes `cfg:ver`. | Lets a freshly scaled replica serve snapshots without hitting Postgres during the morning storm. |
| L2 Postgres `cfg.config_snapshot` | Same as L1, durable | Rows for versions older than `current_version − cfg.sys.config_delta_max_age_versions` are purged nightly. | Redis is a cache, not the truth; Redis loss at 07:00 must not turn into a Postgres stampede (replicas build with a per-chain mutex). |
| Bundle pre-generation (lens-scale D-scale-3) | The 04:00 job resolves each user's snapshot for the business date and embeds `config` + `scheduled` + `config_version` in the pre-built bundle | A change committed after 04:00 marks affected pre-built bundles stale by scope (`devices_targeted` set); the API then serves the pre-built bundle **plus** a delta, not a rebuild. | Keeps the morning storm cheap even if an admin edits at 06:30. |

**Effective-dating and caches.** A future-dated value (radius 150 from tomorrow) does not need an invalidation at midnight: snapshots are keyed by `as_of_date`, and the `scheduled` list already contains it, so devices and servers switch at the instant without any event. The nightly purge removes yesterday's snapshots.

**Transactional read path for ingest.** The ingest transaction resolves `cfg.geo.radius_m`, `cfg.geo.max_accuracy_m`, `cfg.geo.mock_policy`, `cfg.day.checkout_earliest_time`, `cfg.memo.*` rules and `cfg.promo.rules` from the L0 cache when `as_of` is "today" and from `cfg.resolve_*` (Postgres) when the row is back-dated (late sync) — the historical lookup is rare and must be exact.

---

## 3. Propagation to the field

### 3.1 The contract

| Step | Mechanism | Battery/data cost |
| --- | --- | --- |
| 1 | Every API response carries `X-Config-Version: <n>` (and `X-Scope-Version`, `X-Min-App-Version`). The bundle body carries `config: {version, resolved: {...}, scheduled: [...]}`. | 0 — header bytes on requests the app makes anyway |
| 2 | The app stores `config_version` locally with its snapshot. On any response with a higher header version, it queues a `GET /config/delta?since=<local_version>` to run **after the current request completes** (no new wake-up; same connection). While offline nothing happens; the next natural request picks it up. | One small GET (typically < 2 KB gzip) per change the device is affected by |
| 3 | `GET /config/delta` resolves the caller's chain at `since` and at `current`, diffs, and returns only changed keys with provenance + the `scheduled` list, plus `current_version`. If `since` is older than `config_delta_max_age_versions`, it returns the full snapshot. | Server: two cached resolutions; no per-device state needed |
| 4 | The app applies the delta atomically (SQLite transaction), re-evaluates anything open on screen that reads config (e.g. the check-out button), and records `acked_version = current_version`. | 0 |
| 5 | Ack: the next `POST /sync/batch` (or `GET /sync/bundle`, or an explicit `POST /config/ack` **only if** the device has no other request in the next `cfg.sync.periodic_min`) carries `X-Config-Ack: <version>`. The API upserts `cfg.config_ack` and `device.config_version`, throttled to once per version per device. | 0 in the common case (header on an existing request); one tiny POST worst case |
| 6 | Urgent keys (`delivery_device = bundle+push`: `cfg.ops.kill_switch`, `cfg.release.min_version`, `cfg.release.blocked_versions`, `cfg.ops.sync_hold_s`, any **revert** of a C3 geo/day key) also publish an FCM **data** message `{type:"cfg", version:n}` to the targeted devices' tokens; the app's handler runs step 2–5. ASSUMPTION: FCM is acceptable (lens-scale Q22). If not, urgent changes rely on step 1 (the next request — for an SR selling online that is seconds; for an offline SR nothing reaches the device anyway). | FCM high-priority data messages are the one battery-neutral push path on Android; no app-held socket |
| 7 | The 04:00 bundle pre-generation and the WorkManager periodic job (≥ 15 min, constrained) guarantee a bounded worst case: a device that is online at all learns of any change within `cfg.sync.periodic_min`; a device that is offline learns at its next bundle. | Already budgeted in docs/04 |

### 3.2 Apply semantics on the device

| Rule | Detail |
| --- | --- |
| New captures only | A changed value never rewrites a captured row. A visit at 10:00 keeps `radius_m_used = 100`; a visit at 11:00 after the delta uses 150. |
| Scheduled values apply offline | The `scheduled` list (effective_from within `cfg.sys.schedule_horizon_days`) is stored; the resolver on the device picks the row effective at **device now**, cross-checked against `cfg.sync.max_clock_skew_min` on the last successful server contact. If the device clock is implausible (skew flag set), time-shaped keys (`checkout_earliest_time`) fall back to the **more restrictive** of old/new value and the day row is flagged `clock_suspect` (G-feat-18). |
| Kill switches never block upload | `block_login` refuses a **new day start**; `read_only` and `blocked_versions` refuse **new captures**; neither stops `POST /sync/batch` of already-captured rows nor wipes local data (D-scale-9). The app shows the `maintenance_banner`. |
| Config needed before login | `min_version`, `update_url`, `kill_switch`, `maintenance_banner`, `support.contacts`, `default_locale` are served by an unauthenticated `GET /config/public` (cached at Front Door, 60 s) so a locked-out rep still sees the reason and the helpdesk number. |
| Snapshot on every row | `visit`, `memo`, `attendance`, `due_collection`, `redemption` store `config_version` (lens-data P3); `visit` also stores `radius_m_used`, `mock_policy_used`; `memo` stores `rounding_mode_used`, `price_list_version`, `promo_rule_version`. The server re-check and every later dispute replay the exact rule set. |

### 3.3 "Pending until next sync" — admin visibility

For each `config_version` the admin sees:

| Field | Source |
| --- | --- |
| `devices_targeted` | computed at commit: devices whose current user chain intersects the change's scope (global → all active devices; zone 5012 → devices of users assigned there today) |
| `devices_acked`, `acked_pct`, `full_ack_at` | `cfg.config_ack` joined; refreshed every 60 s into `dw.fact_config_change` (lens-data §4.3) |
| `pending_devices` (drill-down) | targeted − acked, grouped by zone, with each device's `last_seen_at`, `app_version`, `config_version`, user, route, and a "likely offline" badge when `last_seen_at < now() − 30 min` |
| `effective_in_field_pct` | acked devices ÷ devices that have been online since the change (excludes genuinely offline phones so the admin sees delivery failures, not dead zones) |
| alert | `cfg.sla.config_ack_pct_alert` after `cfg.sla.config_ack_window_min` |

The same join answers the sponsor's question in reverse: "which radius is phone X using right now?" = the resolved snapshot at `device.config_version` for that user's chain.

### 3.4 Content and master-data changes ride the same stream

| Change | Version bump | Delivery |
| --- | --- | --- |
| Gift catalog, survey questions, rubric, task types, leave types, holidays, tutorial videos, support contacts, i18n overrides, print templates (kind T) | `cfg.bump_version(key, scope, summary)` from the content table's write path | Delta carries `{key, content_version}`; the app fetches the content rows through the existing bundle delta (`?since=`) — one request |
| Master data: outlet coordinates, prices, sales plan, routes, assignments, targets, offers | **Not** a `config_version` bump (they change thousands of times a day via approvals and imports); they flow through the bundle delta (`updated_since`) as today | An admin "publish" of a price list or promo set does bump `cfg.promo.rules` / a `cfg.price.list_version` marker so the admin can see fleet reach of a price change in the same ack view (G-cfg-14) |
| User scope / role change | bumps `app_user.scope_version`; next request with an older token gets 401 `scope_changed` → silent refresh → new token (G-cfg-12) | — |

---

## 4. The admin dashboard (web, `/admin/*`, Next.js)

Feature IDs: this section details lens-features F-ADM-012 (geofence configuration), F-ADM-013 (operating-parameter console), F-ADM-034 (audit viewer), F-ADM-009/022 (devices, OTP), F-ADM-027/031 (release, waves), F-ADM-030 (quarantine) and the master-data CRUD rows F-ADM-001..011/014..021/033/035/036. API: `/admin/config/*` (new, listed in §4.10), `/admin/*` CRUD (F-API-035), audit (F-API-037).

### 4.1 Access model

| Permission (`cfg.admin_permission.permission`) | Typical holder | Grants |
| --- | --- | --- |
| `cfg.view` | every admin, DMO, WM | Read every page in §4; see values, history, reach |
| `cfg.ops` | IT/ops admin | Propose changes to areas sync, bundle, app, media, ops, api, agg, sla, retention, sys, support, i18n |
| `cfg.field` | field-operations manager | geo, day, memo, sale, credit, qc, drp, stock, task, leave, tso, calendar |
| `cfg.program` | trade-marketing / program manager | loyalty, astha, superstar, promo, target, survey, rubric, content, kpi |
| `cfg.sec` | security admin | auth, pii, risk-class raise, admin_permission grants |
| `cfg.release` | release manager | release.*, flag.*, waves |
| `cfg.approve` | named approvers (≥ 2 people per area; roster in `admin_permission` with `scope`) | Approve C3 requests they did not create; approve C2 early-apply |
| `tso` (role, bounded to own territory) | TSO | `cfg.geo.radius_m` at T/Z/O within bounds; `cfg.credit.*` at Z/O; OTP issue; route day exceptions; Astha gift choice |
| `dmo` / `wm` (role, bounded) | DMO / WM | view + propose for own scope (goes to `cfg.approve`) |
| `device.manage`, `device.otp`, `quarantine.review`, `audit.view`, `pii.view`, `pii.export`, `day.reopen`, `master.*` | per page | as named |

Rules: every permission can be bounded to a geography (`scope_type/scope_id`); a bounded editor sees the blast-radius preview only for their scope and cannot propose above it; `cfg.sec` grants are themselves C3 (two-person) and audited; the roster of `cfg.approve` holders is shown on the console so a requester knows whom to ask; **the API role has no permission to change `admin_permission` outside this workflow** (G-cfg-01 covers the gap that schema.sql has only `admin`).

### 4.2 Page map

| # | Page | Route | Permission | Phase |
| --- | --- | --- | --- | --- |
| P1 | Config home: current `config_version`, pending requests, recent changes, reach widget (acked %), anomaly watches running, break-glass items awaiting review | `/admin/config` | `cfg.view` | 1 (minimal), 6 (full) |
| P2 | Geofence radius management (map) | `/admin/config/geofence` | `cfg.field`, `tso` | 2 |
| P3 | Rules & thresholds console (all S/O keys by area) | `/admin/config/keys` | per area | 1 (radius, check-out, min_version only), 2+ |
| P4 | Operational switches (kill switch, read-only, sync hold, bundle hold, banner) with timers and break-glass | `/admin/config/ops` | `cfg.ops` | 2 |
| P5 | Change requests & approvals (inbox, diff, blast radius, approve/reject, schedule) | `/admin/config/requests` | `cfg.approve`, requester | 2 |
| P6 | Config history & rollback (timeline per key/scope, compare versions, revert, rollback-to-version, export) | `/admin/config/history` | `cfg.view`; revert needs editor + approval rules | 2 |
| P7 | Reach / pending devices per version | `/admin/config/reach` | `cfg.view` | 2 |
| P8 | Program setup: Astha (quarter, brands, targets, gift catalog, gift choice), Diamond League (period, earning rules, catalog, cash rate), Superstar (slabs, criteria), Promotions (rule builder, test-a-memo simulator), Free samples | `/admin/programs/*` | `cfg.program` | 5 |
| P9 | Master data CRUD: geography, clusters, products, prices (effective-dated, diff vs current list), sales plan (zone × SKU matrix, bulk by territory), routes & visit days, assignments (SR/SS with valid ranges, overlap check), users & scope, outlets (PII-gated), classifications, task/leave types, holiday calendar | `/admin/master/*` | `master.*` | 6 (outlets/users/routes earlier for pilot: 2–3) |
| P10 | Targets: set target (bulk upload, split preview), revision approval queue, approval-level config | `/admin/targets/*` | `master.target`, approvers | 5 |
| P11 | Device management: list/search devices, bound users, last sync, app version, `config_version`, integrity signals, revoke (with pending-row warning), OTP issue (TSO-scoped), OTP log | `/admin/devices` | `device.manage`, `device.otp` | 0 (OTP), 6 (full) |
| P12 | Release management: upload APK (checksum, size gate vs `apk_max_mb`), publish, set `latest`/`min_version`, blocked versions, staged rollout by wave + `wave_pct`, adoption chart by version | `/admin/release` | `cfg.release` | 2 (basic), 7 (waves) |
| P13 | Sync health: login %, submit %, final-submit by zone, trickle latency p50/p95, batch error rate, quarantine backlog, config ack %, photo pending count; drill to zone → route → device | `/admin/sync-health` | `cfg.view` | 1 (one tile), 4 |
| P14 | Data-quality quarantine review: filter by reason, see payload (PII-masked), re-map outlet/SKU, accept / discard / return-to-device; bulk by reason | `/admin/quarantine` | `quarantine.review` | 2 |
| P15 | Day control: reopen final-submitted zone-day (reason, window), late-sync queue after final, missing check-out list | `/admin/day` | `day.reopen` | 3 |
| P16 | Admin audit viewer: unified `config_audit` + master-data `@AUDIT` + `admin_permission` changes; filter by actor/key/scope/date; export | `/admin/audit` | `audit.view` | 2 |
| P17 | Import & reconciliation console (lens-features F-ADM-032) | `/admin/import` | `master.*` | 7 |
| P18 | Feature flags & waves: flag matrix (flag × scope), wave membership (territories, devices), per-wave overrides view | `/admin/config/flags` | `cfg.release` | 7 |

### 4.3 P2 Geofence radius management — detailed spec

| Element | Spec |
| --- | --- |
| Filter | Wing → Division → Territory → House → Zone → Route (bounded by the admin's permission scope) |
| Map | Outlets of the selected scope as points (cluster-rendered above 2 k points); each with its **resolved** radius circle; circle colour by provenance: grey = global default, blue = territory, teal = zone, orange = outlet override, red dashed = scheduled change not yet effective; hover shows resolved value and source; click opens the outlet panel |
| Side panel (scope) | Resolved radius at the selected level, its provenance, counts: outlets, routes, devices in scope; last 5 changes; KPIs for the scope over 7/30 days: geo-valid %, force-sale %, mock-flag %, median device-computed distance (from `visit.device_distance_m`) and the **distance histogram** — the admin can see that 18 % of visits land between 100 and 150 m before choosing 150 |
| Edit at scope | New value (int, bounded by `radius_min_m`/`radius_max_m` and by the parent's value × 3 rule for O); effective from (now / tomorrow 00:00 Dhaka / a date); reason (mandatory, ≥ 10 chars); blast radius preview recomputed live: "Affects **1 territory, 4 zones, 37 routes, 1,812 outlets, 41 devices**; 9 outlets keep their own overrides"; **what-if**: % of the last 30 days' visits that would have been geo-valid under the new value, computed from stored fixes (`fact_visit.device_distance_m`) — this is the single most useful number for the sponsor's "adjust from GUI" |
| Bulk edit | Multi-select zones/territories from a table (sortable by force-sale %), set one value → one change set, one approval |
| Outlet override | From the outlet panel: value, reason, optional expiry (`effective_to`), photo of the shop from `outlet_photo`; "clear override" = closes the row; list of all overrides in scope with age, exportable |
| Policies | Same page, second tab: `no_location_policy`, `mock_policy`, `max_accuracy_m`, `fix_timeout_s`, `refresh_max`, plausibility thresholds — same edit/approval mechanics |
| Rails | C3 at G/W/D → two-person + mandatory canary (one zone for ≥ 1 business day; the page offers "create canary request for zone …" and later "promote canary to full scope"); anomaly watch armed on `geo_valid_pct`, `force_sale_pct` for the scope; auto-alert, not auto-revert (ASSUMPTION: auto-revert of a radius during a selling day would create two rule sets in one day; the alert pages the approver who can one-click revert) |

### 4.4 P3 Rules & thresholds console

| Element | Spec |
| --- | --- |
| Layout | Left: areas (§1.1–1.10) with change badges; centre: table of keys — current resolved value at the selected scope, provenance, default, bounds, risk badge, effect (S/B/L/P), last changed by/when; right: edit drawer |
| Scope selector | Global or any node; the table shows resolved values for that node and marks which are overrides **here** vs inherited |
| Edit drawer | Typed control per `value_type` (time picker in Dhaka, list editor, json editor with schema validation and inline errors, enum radio); effective-from; reason; preview: old → new, blast radius, keys that depend on it (`cfg.agg.late_data_recompute_days` warns if < `cfg.sync.max_backdate_days`) |
| Change set | Several keys can be staged into one request (e.g. check-out time + take-action time) and applied atomically |
| Search | By key, by description (bn/en), by "where is this used" (screens listed in `config_item.description`) |
| Deprecated keys | Hidden by default; shown read-only with `replaced_by` |

### 4.5 P4 Operational switches

| Switch | Control | Rails |
| --- | --- | --- |
| `kill_switch` (off / read_only / block_login) | Scope selector, mode, **duration** (15 min – `kill_switch_max_h`), banner text bn/en | Break-glass allowed for one named `cfg.ops` holder at a time (on-call); every break-glass creates `review_due_at = now()+24h` and an alert to all `cfg.approve`; auto-expires; the console shows a countdown and "extend" (another request) |
| `read_only_mode` | Toggle + duration + reason | Two-person unless break-glass; UI shows queued device uploads growing (from telemetry) so nobody forgets it |
| `sync_hold_s`, `bundle_hold` | Slider / toggle + duration | Auto-expire; shows current RPS from the sync-health feed |
| `maintenance_banner` | Text, severity, scope, from/to | C0; preview on a phone frame |

### 4.6 P18 Feature flags and waves

Flags registered at Phase 7 planning (each is a `cfg.flag.<name>` key, C2 unless noted): `new_app_login_enabled` (C3: gates whether a wave's users may log in to the new app at all), `parallel_run_mode` (new app captures but marks rows `parallel=true`, excluded from aggregates — pilot), `print_enabled`, `credit_enabled_ui`, `loyalty_ui`, `astha_ui`, `superstar_ui`, `promo_engine_v2`, `suggested_qty_ui`, `amo_control_call`, `tso_final_submit_new` (C3), `anti_spoof_warnings`, `trickle_sync` (mirrors `cfg.sync.trickle_enabled` for wave experiments), `photo_upload_v2`.

Wave = `app.rollout_wave {id, name, territories[], start_date, status}`; devices inherit the wave of their user's territory unless pinned (`device.wave_id`). Flag matrix page: rows = flags, columns = scopes (global, each wave, selected territory), cells = resolved value with provenance; "flip for wave 2 only" = a scoped change request. Rollback of a wave (docs/11 step 5) = flip `new_app_login_enabled` false at WAVE scope (C3, break-glass allowed, push) — captured rows keep uploading.

### 4.7 P5 / P6 Change requests, history, rollback

| Capability | Spec |
| --- | --- |
| Request lifecycle | `draft → pending_approval → approved → applied` (or `rejected`, `cancelled`, `expired` after 72 h); C0/C1: requester's submit = approve = apply in one step (still a request row); C2: applies after a 10-minute delay with a visible "cancel" (or immediately with a second approver); C3: a different `cfg.approve` holder must approve; `break_glass` bypasses with mandatory review |
| Diff view | Per change: key, scope (named, not just id), old → new, effective window, blast radius, what-if KPIs (geo keys), dependency warnings, canary status |
| Scheduling | `apply_at` for "approve now, apply at 00:00 Dhaka"; scheduled requests appear on P1 with a cancel |
| Timeline (P6) | Per key × scope: every version as a bar on a time axis (effective ranges), with who/why; per actor; per day |
| Compare | Pick two `config_version`s → full resolved diff for a chosen scope chain |
| Revert | One click on any applied version → a new request whose changes restore the previous values (never deletes rows; `is_revert_of` set); inherits the risk class but **skips the canary requirement** and is pushed (`bundle+push`) — reverting fast is the safety valve |
| Rollback to version N | Builds a request restoring every key/scope to the value effective at N; shows the diff; same approval rules by max risk |
| Export | CSV/JSON of history and current state for a scope, for audits |

### 4.8 P11 / P12 Devices and releases

| Element | Spec |
| --- | --- |
| Device list | Search by user/code/device; columns: user, role, route, zone, app version, `config_version` (+ "behind by n"), last sync, pending rows reported by device (telemetry), integrity badges (mock seen last 7 d, rooted, dev options), status |
| Revoke | Shows pending-row count; if > 0 offers "revoke after upload" (grace `cfg.auth.revoked_device_grace_upload_h`) — default; "revoke now" needs a reason and is C2 |
| OTP (TSO-scoped) | Pick user in scope → issue → shows code + expiry; list issued/used/expired; launch-day bulk pre-issue per zone with printed sheets (G-feat-11) **unknown; confirm the distribution method** |
| Release | Upload APK → server verifies signature + checksum, size ≤ `apk_max_mb`, extracts versionName; publish to Blob; set `latest_version` (C1), `min_version` (C3: dropdown of published versions only, ≥ current, plus a live count of devices below it = "this will lock out 312 devices tomorrow morning"), `blocked_versions` (C3, same count), per-wave `wave_pct` slider; adoption chart by version over time from `device.app_version` |

### 4.9 Safety rails — consolidated

| Rail | Mechanism | Applies to |
| --- | --- | --- |
| Bounds | `config_item.constraints` validated by API **and** by a CHECK trigger on `config_value` (jsonschema in plpgsql or a `pg_jsonschema`-style extension; ASSUMPTION: available on Flexible Server, else the API is the only validator and T-0-61 covers it) | all keys |
| Referential validation | `constraints.ref_table`: `min_version` must be a published `app_release`; brand/sku lists must exist and be `sales_enable`; roles valid | release, program, rubric |
| Dependency checks | Named cross-key rules in code with a config-side list (`cfg.sys.dependency_rules` read-only): `agg.late_data_recompute_days ≥ sync.max_backdate_days`; `day.checkout_latest_time > checkout_earliest_time`; `auth.refresh_ttl_days ≥ 7`; `media.photo_max_kb ≤ 300 when upload_network_policy = any` | as listed |
| Future-dated only | `future_dated_only` keys reject `effective_from < next Dhaka midnight` | rounding, units, periods, cutoff, numbering |
| Blast radius | Computed at request creation from the scope → counts of zones/routes/outlets/devices/users; shown before submit and on the approval page; a global change shows the full fleet in red | all |
| Two-person approval | `two_person` CHECK on the request table; approver ≠ requester; both must hold `cfg.approve` (approver) and the area permission (requester) | C3 |
| Canary | C3 geo/day keys at scope ≥ territory must reference a `canary_of` request applied ≥ 1 business day earlier at a sub-scope, or carry `break_glass` | C3 geo/day |
| Delayed apply | C2: 10-minute window with cancel | C2 |
| Anomaly watch | On apply, a watcher compares `geo_valid_pct`, `force_sale_pct`, `login_pct`, `submit_pct` for the affected scope against the 7-day same-weekday baseline every 15 min for 2 business days; breach → alert naming the version and a one-click revert link | keys with `anomaly_watch` |
| Auto-expiry | Operational switches require a duration; the scheduler closes the row (`effective_to`) and pushes the reset | O keys |
| Undo | Revert creates a new audited version (§4.7); nothing is ever deleted | all |
| Break-glass review | `review_due_at` + alert; unreviewed break-glass after 24 h escalates to all `cfg.sec` | break-glass |
| Rate limit on changes | Max 5 applied C3 versions per hour fleet-wide (cfg.sys, C3) — a runaway script or a panicked operator cannot flip the fleet 50 times | C3 |
| Staging mirror | A "copy to staging" action exports a change set for the load-test environment; prod values can be imported into staging, never the reverse | all |

### 4.10 Admin config API (additions to docs/09)

| Endpoint | Purpose |
| --- | --- |
| `GET /config/public` | unauthenticated, Front Door-cached 60 s: `min_version`, `latest_version`, `update_url`, `kill_switch`, `maintenance_banner`, `support.contacts`, `default_locale`, `config_version` |
| `GET /config/delta?since=<v>` | resolved changed keys + scheduled list for the caller's chain (§3.1 step 3) |
| `POST /config/ack` | `{version}` fallback ack (normally a header on sync/bundle) |
| `GET /admin/config/items?area=` | registry with bounds, risk, descriptions |
| `GET /admin/config/resolve?scope_type=&scope_id=&as_of=&keys=` | resolved values with provenance for any node (powers P2/P3) |
| `GET /admin/config/whatif?key=cfg.geo.radius_m&scope=…&value=150&days=30` | re-evaluates stored fixes under a candidate value |
| `GET /admin/config/blast-radius?scope_type=&scope_id=` | counts |
| `POST /admin/config/requests` / `GET …/requests?status=` / `POST …/requests/{id}/approve|reject|cancel` / `POST …/requests/{id}/break-glass` | workflow |
| `GET /admin/config/versions?from=&to=` / `GET …/versions/{v}` / `POST …/versions/{v}/revert` / `POST …/rollback-to/{v}` | history |
| `GET /admin/config/reach/{version}` / `GET …/reach/{version}/pending?zone=` | delivery |
| `GET /admin/audit?actor=&key=&scope=&from=&to=` | unified audit (F-API-037) |
| `GET/POST /admin/permissions` | roster (C3) |

---

## 5. Config in the phase plan (docs/12 numbering)

| Phase | What lands | Keys that must exist (registered with defaults) | Admin surface | Why here |
| --- | --- | --- | --- | --- |
| **0 Foundations** | Schema `cfg` (§2.1) in migration M-45; `config_version` sequence; registry seeded with every Phase 0–1 key; `cfg.resolve_*` functions; audit trigger; `admin_permission`; `X-Config-Version` middleware; `GET /config/public`; `territory_geo_config` compatibility view for the importer | `cfg.geo.radius_m`, `radius_min_m`, `radius_max_m`; `cfg.day.checkout_earliest_time`, `checkin_earliest_time`, `business_date_cutoff_time`; `cfg.release.min_version`, `latest_version`, `update_url`, `blocked_versions`; `cfg.ops.kill_switch`, `kill_switch_max_h`, `read_only_mode`, `maintenance_banner`; `cfg.auth.*` (all); `cfg.pii.field_roles`; `cfg.sync.*`, `cfg.bundle.*` defaults (consumed in Phase 1); `cfg.sys.*`; `cfg.support.contacts` | None (SQL seed + a CLI `cfg set --key --scope --value --reason` for engineers, itself audited); OTP panel (F-ADM-022) | Auth and binding keys are consumed by the Phase 0 login; min_version/kill switch must exist before the first APK is on a pilot phone |
| **1 Vertical slice** | Bundle embeds `config{version, resolved, scheduled}`; app config store + resolver + delta pull + ack header; `visit.config_version`, `radius_m_used`; server geo re-check resolves as-of `started_at`; Redis/L0 cache; one sync-health tile (P13) | + `cfg.geo.fix_timeout_s`, `fix_accuracy_mode`, `max_accuracy_m`, `no_location_policy`, `mock_policy` (flag only in P1); `cfg.media.*`; `cfg.app.local_history_days`, `default_locale` | **P3 minimal**: a key table for the 3 sponsor keys (radius at G/T, check-out time, min_version) with bounds, reason, audit, and the reach widget — single approver (engineering) | Sponsor can change the radius from a GUI and watch the pilot phone pick it up — the R6 "believable moment" belongs in the same slice as the R5 one |
| **2 Full SR day** | All SR-day keys consumed; outlet overrides; plausibility checks; scheduled values on device; P2 map, P4 switches, P5 approvals (two-person, canary, delayed apply), P6 history/revert, P7 reach, P14 quarantine, P16 audit; FCM push for urgent keys (if Q22 yes); anomaly watch | + `cfg.geo.*` (rest), `cfg.day.*` (SR part), `cfg.memo.*`, `cfg.sale.*`, `cfg.credit.*`, `cfg.qc.*`, `cfg.drp.*`, `cfg.stock.*`, `cfg.sync.trickle_*`, `cfg.ops.sync_hold_s`, `bundle_hold`, `cfg.api.*`, `cfg.agg.*`, `cfg.sla.geo_valid_drop_alert_pts`, `force_sale_pct_alert`, `config_ack_*`; reason-code tables replace enums (`force_reason`, edit reasons, QC kinds) | P2, P3 (areas geo/day/memo/sale/credit/media/sync), P4, P5, P6, P7, P12 basic, P14, P16 | Phase 2 is when real reps run a beat; every rail must exist before a field manager can edit anything that reaches them |
| **3 Supervisors** | AMO/TSO keys; P15 day control; supervisor-scoped editing (TSO radius within territory, credit stop per outlet) | + `cfg.day.final_submit_*`, `reopen_*`, `late_sync_after_final_policy`; `cfg.rubric.*`, `cfg.survey.tso_visit_query_questions`, `cfg.task.*`, `cfg.leave.*`, `cfg.tso.*`, `cfg.target.supervisor_targets` | P15; bounded permissions live | TSO final submit and reopen rules are consumed here |
| **4 Web dashboards & reports** | KPI/SLA keys; P13 full | + `cfg.kpi.*`, `cfg.sla.*` (rest), `cfg.ops.dashboard_refresh_min_s`, `report_*`, `cfg.pii.export_allowed_roles`, `cfg.calendar.*` | P13, calendar editor (P9 part) | Dashboards need bands, denominators and the holiday calendar |
| **5 Programs** | Program keys and content tables; P8, P10 | + `cfg.loyalty.*`, `cfg.astha.*`, `cfg.superstar.*`, `cfg.promo.*`, `cfg.target.*` (rest), `cfg.survey.posm_questions`, `cfg.content.*` | P8, P10 | — |
| **6 Admin / master data** | P9 complete, P11 full, P1 full, `cfg.i18n.overrides`, `cfg.feedback.categories`, retention keys editable, staging mirror, export | + `cfg.retention.*`, `cfg.i18n.*`, `cfg.app.home_tiles`, `kpi_strip_items`, `outlet_list_label_format` | P1, P9, P11 | "The business can run the system without engineering" (docs/12 DoD) |
| **7 Migration, pilot, cutover** | Waves and flags (P18), `wave_pct`, `parallel_run_mode`, `new_app_login_enabled`, bulk OTP, per-wave `otp_ttl_min` override, P17 | + `cfg.flag.*`, `cfg.release.wave_pct`, `update_prompt_policy`, `update_wifi_only` | P18, P17 | Rollback of a wave is a config flip, not a release |

Phase-gate rule: **no key is consumed by code before it is registered**; CI fails if `cfg.` string literals in `/api` or `/app` are not present in the registry seed (T-0-63). Conversely a registered key nobody reads is reported weekly so the registry does not accumulate dead switches.

---

## 6. Test gates (config-related; 60-range per phase to avoid clashing with other lenses)

| Gate | Test | Pass criterion |
| --- | --- | --- |
| T-0-60 | Registry completeness | every key in §1 with `introduced_in_phase ≤ 1` exists in `config_item` with default, bounds, risk, scope levels |
| T-0-61 | Bounds enforced at the DB | inserting `cfg.geo.radius_m = 10` or `= 5000` into `config_value` fails even bypassing the API |
| T-0-62 | Exclusion constraint | two open rows for the same key+scope cannot coexist; closing + inserting in one transaction succeeds |
| T-0-63 | Code/registry drift | CI grep of `cfg.` literals vs seed → zero unregistered keys |
| T-0-64 | Audit immutability | `UPDATE`/`DELETE` on `config_audit` is denied for the API role; every write path produces exactly one audit row |
| T-1-60 | Bundle carries config | bundle for a pilot SR contains `config.version`, resolved radius with provenance, `scheduled` list |
| T-1-61 | Change reaches the device | admin sets radius T=150 → within one trickle sync the device's next visit stores `radius_m_used = 150`, `config_version = n`; the visit captured before the change keeps 100 |
| T-1-62 | Resolver performance | all keys for a 12-pair chain < 2 ms p95 on the Phase 1 DB; single key < 0.3 ms; L0 hit rate > 99 % in the morning-storm load test |
| T-1-63 | As-of re-check | a visit back-dated to before a radius change is re-checked by the server with the old radius; after it, with the new one |
| T-1-64 | Header everywhere | 100 % of API responses carry `X-Config-Version`; `GET /config/public` works unauthenticated and is cached |
| T-2-60 | Two-person approval | the requester cannot approve their own C3 request (DB constraint and UI); a second approver can; audit shows both |
| T-2-61 | Reach SLO | after a global C1 change, ≥ 95 % of devices online in the last 15 min ack within 15 min; 100 % of devices that pull a bundle the next morning are on the new version |
| T-2-62 | Offline scheduled apply | device in airplane mode holding a `scheduled` check-out change (17:00 → 16:30 from tomorrow) enables check-out at 16:30 tomorrow with no network; a device with a skewed clock falls back to the restrictive value and flags the day |
| T-2-63 | Kill switch semantics | `block_login` at zone scope: new-day login refused with banner; `POST /sync/batch` of pending rows still accepted; local data intact; switch auto-expires |
| T-2-64 | Revert | one-click revert produces a new version, pushes it, and the device resolves the previous value; nothing deleted; both versions in history |
| T-2-65 | Blast radius accuracy | preview counts equal the `devices_targeted` computed at commit (±0) for G, W, T, Z scopes |
| T-2-66 | Anomaly watch | synthetic drop of geo-valid % by 15 pts in the changed scope raises the alert naming the version within 15 min |
| T-2-67 | Outlet override precedence | outlet 25 m inside a zone at 150 m inside a territory at 100 m resolves 25 for that outlet, 150 for its neighbours, 100 elsewhere; provenance correct on all three |
| T-2-68 | Canary enforcement | a C3 global radius request without a prior sub-scope canary is refused unless break-glass; break-glass creates the review item and alert |
| T-2-69 | Content versioning | editing a reason code bumps `config_version`; the device shows the new label after its next request; historical memos keep the old code |
| T-3-60 | Bounded permission | a TSO can set radius for a zone in their territory within bounds; cannot for a neighbouring territory; cannot exceed bounds; audit records scope |
| T-3-61 | Reopen rules | a role not in `cfg.day.reopen_roles` cannot reopen; a reopen beyond `reopen_window_days` requires two-person; late rows after final land per `late_sync_after_final_policy` |
| T-4-60 | KPI keys | changing `cfg.kpi.bands` re-colours the dashboard without a release; `submit_pct_denominator` switches the labelled KPI and both KPIs remain available by their own names |
| T-5-60 | Program rule effective dating | a promo rule effective tomorrow is in today's bundle under `scheduled`, applies to tomorrow's memos offline, and never to today's; a late sync of today's memo uses today's rule |
| T-5-61 | Loyalty rate safety | changing `cash_rate_mtk_per_point` is C3; redemptions store the rate used; a revert does not alter past redemptions |
| T-6-60 | Dead key report | registry key with no code reader is listed; deprecated key hidden by default |
| T-6-61 | Rollback-to-version | rolling back to version N reproduces the resolved snapshot of N for 10 random chains exactly |
| T-7-60 | Wave rollback by flag | flipping `new_app_login_enabled=false` for wave 2 blocks new-day login for wave 2 devices only, pushes within 5 min to online devices, and all pending rows still upload |
| T-7-61 | Launch-day change rate limit | the 6th applied C3 version within an hour is refused with a clear message |

---

## 7. Decisions, questions and gaps

### 7.1 Proposed decisions for DECISIONS.md (numbers assigned on merge)

- **D-cfg-1** All runtime parameters live in `cfg.config_value`, scoped and effective-dated, resolved most-specific-wins (§2.2); `territory_geo_config` becomes a view. (SF-6)
- **D-cfg-2** One monotonic `config_version` for scalars **and** versioned content tables; master data (prices, outlets, routes, targets) does not bump it but publishes markers for admin reach visibility. (§3.4)
- **D-cfg-3** Propagation is header-triggered pull + ack-on-next-request; no polling; FCM data push only for `bundle+push` keys, pending Q22. (§3.1)
- **D-cfg-4** Captured rows store the config version and the resolved values they used; server re-checks resolve **as of capture time**. (§3.2; lens-data P3)
- **D-cfg-5** Four risk classes C0–C3 with the rails in §4.9; C3 = two-person approval + canary + anomaly watch; break-glass exists and is reviewed within 24 h.
- **D-cfg-6** Kill switches never block upload or wipe data (restates D-scale-9 as a config rule); every operational switch has a mandatory duration and auto-expires.
- **D-cfg-7** Adding or removing a key is a migration (code), changing a value is config (GUI); risk class may be raised from the GUI, never lowered.
- **D-cfg-8** The minimal console for radius / check-out / min_version ships in Phase 1, not Phase 6.
- **D-cfg-9** Admin authority is a permission table bounded by geography, not the single `admin` role.
- **D-cfg-10** Defaults marked (A) in §1 are the day-one values; each is reviewed with the business during its phase and changed through the GUI, which is the point of R6 — none blocks the build.

### 7.2 Questions for docs/13 (new numbers continue after lens-scale's Q22)

| Q | Question | Blocks |
| --- | --- | --- |
| Q23 | Who are the two-person approvers per area (`cfg.approve` roster), and who holds break-glass on-call? | P5 before any field-affecting edit (Phase 2) |
| Q24 | May a TSO change the radius for their own territory/zones/outlets within bounds, or only propose? | T-3-60 |
| Q25 | Behaviour when an outlet has no coordinates (Q7 restated as `cfg.geo.no_location_policy`), and does the first force-sale photo set the location? | Phase 2 default |
| Q26 | The three memo edit reasons' exact texts; the Astha/Superstar parameters; the promo rule schema (G-feat-13/15/20/22) | Phase 2 / 5 content |
| Q27 | Is Saturday a selling day; the national holiday list; does Login % use working days? | Phase 4 |
| Q28 | Is FCM acceptable in the field app (lens-scale Q22) — if not, urgent reverts rely on the next request | Phase 2 push path |
| Q29 | Business-date cutoff: calendar midnight or an early-morning cutoff for late syncs? | `cfg.day.business_date_cutoff_time`, Phase 0 (future-dated only afterwards) |
| Q30 | PII role × field matrix (G-feat-59) as the value of `cfg.pii.field_roles` | Phase 0 default before any outlet read |

### 7.3 Gap list (G-cfg-NN)

| ID | Sev | Title | Where | Detail / what to add |
| --- | --- | --- | --- | --- |
| G-cfg-01 | blocker | Single `admin` role; no permission model for ~20 admins with different authority | schema.sql `role` enum; docs/09 "role-gated" | Add `cfg.admin_permission` (§2.1, §4.1) bounded by geography; two-person approval is impossible without distinguishable approvers. Phase 0. |
| G-cfg-02 | blocker | No runtime config store; ~240 parameters hard-coded in prose | SF-6; every doc | §1 catalog + §2 schema; Phase 0 migration M-45. |
| G-cfg-03 | major | Who may edit the geofence radius, and at what scope, is unspecified | docs/05, docs/13 Q7 | Proposed: `cfg.field` at G/W/D/T (C3/C2), TSO at T/Z/O within bounds (Q24). |
| G-cfg-04 | blocker | Blast radius / mis-set protection absent (radius 10 m or `min_version` typo locks the fleet) | G-scale-15 | Bounds in DB, blast-radius preview, two-person approval, canary, anomaly watch, revert, change-rate limit (§4.9). |
| G-cfg-05 | major | Mid-day config changes and late syncs: which value applies to a captured row is undefined | docs/05 server re-check; docs/04 immutability | Effective-dated rows + `config_version`/`radius_m_used` on rows + as-of resolution (§2.2, §3.2). |
| G-cfg-06 | major | Offline devices cannot learn of a scheduled change (e.g. check-out time) | docs/04 battery rules vs R6 | `scheduled` list in bundle/delta with `cfg.sys.schedule_horizon_days`; restrictive fallback on clock skew (T-2-62). |
| G-cfg-07 | major | No delivery visibility: admin cannot see which devices run which config | R6 "reaches the field apps" | `config_ack`, `device.config_version`, reach page P7, `cfg.sla.config_ack_pct_alert`. |
| G-cfg-08 | major | Urgent changes (kill switch, revert) have no path faster than the next device request | D-scale-9; R5 | `bundle+push` via FCM data message (Q28); unauthenticated `GET /config/public` for pre-login state. |
| G-cfg-09 | minor | Risk classification of keys is not in the spec | — | `config_item.risk_class` set in code; GUI may raise only (D-cfg-7). |
| G-cfg-10 | major | Reason codes (force sale, edit, QC fault, DRP kinds, task types, leave types, feedback categories) are Postgres enums → every label/code change is a release and bilingual labels have no home | schema.sql enums; CLAUDE.md rule 8 | `app.reason_code(kind, code, label_bn, label_en, active, sort)` versioned through `config_version` (kind T keys in §1.3/1.9); codes immutable, deactivate instead of delete. Phase 2. |
| G-cfg-11 | major | Content the business must change without a release has no versioning path (survey questions, rubric, gift catalog, holidays, tutorial videos, support contacts, i18n fixes, print header) | docs/06–08; G-feat-12/47 | Kind T keys + `cfg.bump_version` from content write paths (§3.4); `cfg.i18n.overrides` for label fixes. |
| G-cfg-12 | major | A `user_scope`/role change does not reach an already-issued token (token carries scope) | docs/02 auth; CLAUDE.md rule 4 | `app_user.scope_version` + `X-Scope-Version`; 401 `scope_changed` → silent refresh (`cfg.auth.scope_token_version_check`). |
| G-cfg-13 | major | Outlet-level radius override can blow up bundle size if delivered per outlet | docs/04 size budget | Deliver as a sparse override map; cap overrides per zone with a warning; ≈ 20 KB fleet-wide at 2 k overrides. |
| G-cfg-14 | minor | Price-list / promo-set publication has no fleet-reach visibility | docs/03 `sku_price`; G-feat-52 | `cfg.price.list_version` / `cfg.promo.rules` markers bump `config_version` on publish so P7 shows reach; memos store `price_list_version`. |
| G-cfg-15 | major | Admin actions themselves need an audit trail beyond config (master data edits, PII exports, reopen, revoke) | docs/09 admin CRUD; F-ADM-034 | Unified audit viewer P16 over `config_audit` + `@AUDIT` columns + `admin_permission` changes + `read_pii_export` events. |
| G-cfg-16 | major | Operational switches with no expiry are a launch-day hazard (left on overnight) | lens-scale `cfg.ops.*` | Mandatory duration + auto-expiry + countdown UI + break-glass review (§4.5). |
| G-cfg-17 | minor | Staging/prod config parity for load tests | lens-scale §6 | One-way export/import of change sets prod → staging (§4.9 last row). |
| G-cfg-18 | major | Several defaults are business decisions the spec does not give (no-location policy, cutoff time, weekend days, BSR denominator, CPR zero-sale rule, qty unit, rounding mode, memo number format, PII matrix) | docs/13 Q5/Q7/Q8/Q9/Q11/Q12 | Each is registered as `future_dated_only`/C3 with an (A) default and listed in §7.2; the GUI changes them later without a release — but the C3 "future-dated only" rule means they must be confirmed **before the first pilot business day**, not after. |
| G-cfg-19 | minor | Dead/unused keys will accumulate | — | CI drift check both directions (T-0-63, T-6-60); `deprecated_at`/`replaced_by`. |
| G-cfg-20 | major | Device clock trust for time-shaped config (17:00 gate) on shared, misconfigured phones | G-feat-18; docs/04 clock-skew tests | Skew recorded at every server contact; restrictive fallback; `clock_suspect` flag; admin list of skewed devices on P11. |
| G-cfg-21 | minor | JSON Schema validation inside Postgres may be unavailable on Flexible Server | §4.9 bounds | Verify extension availability in Phase 0; if absent, API-side validation + CHECK for scalar bounds only; T-0-61 adapts. |
| G-cfg-22 | major | Wave membership and feature flags for the parallel pilot have no data model | docs/11 waves; F-ADM-031 | `app.rollout_wave`, `device.wave_id`, `scope_type='wave'`, `cfg.flag.*` (§4.6); rollback = flag flip + push. Phase 7 design, Phase 2 scope-level registration. |
