# Critic: A Day in the Field (walk-through against the spec and the seven lenses)

Date 2026-10-04. Role: field-operations critic. Inputs read in full: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13 and 22, db/schema.sql, db/seed, seed-findings.md, lens-data, lens-features, lens-scale, lens-sync, lens-config, lens-security, lens-quality.

Method: seven personas, one real Tuesday in October 2026 (a trading day; Friday is the off-day per docs/22 P-03), walked hour by hour. At every step the question is: what must the system have **captured (C)**, **configured (K)**, **synced (S)** or **displayed (D)** for this step to work, and is that in the spec or a lens? Verdicts: **OK** (covered, with the F/M/cfg/G id), **PARTIAL** (covered but a piece is missing), **MISS** (nothing covers it → a `G-field-NN` gap in §10). Existing gaps are cited, never re-derived. Everything not in the spec is marked ASSUMPTION or "unknown; confirm with the business".

---

## 0. Conclusions

| # | Conclusion | Consequence |
| --- | --- | --- |
| C1 | The seven lenses cover the *system's* day thoroughly (storms, sync, config, fraud, gates). What they under-cover is the **human exceptions around the day**: the day that does not happen (rain, hartal, market closed), the shop that is shut, the memo the retailer tears up, the SR who is sick at 07:30, the phone that is picked up by the wrong SR, the TSO who is on leave at 19:00. Each of these is a legitimate event today that the new system would record as a failure, a fraud signal or nothing at all. | 23 gaps (G-field-01..23), 12 major, 11 minor, 0 blockers. Six must land before pilot-ready (2e): G-field-02, 03, 04, 05, 08, 12. |
| C2 | **The distribution house is not an actor.** Stock issue at 07:00, stock return at 18:00 and the cash hand-over are the three moments where money and goods change hands between AKTCL's SR and a third party, and all three are self-declared by the SR on his own phone. FS-17 (stock leakage) and the dues ledger are built on numbers nobody counter-signs. | G-field-01: a confirmation event by a DH user or an AMO proxy, and a DH settlement view. |
| C3 | **"Zero sale" is doing the work of five different outcomes** (shop closed, owner absent, refused, stock sufficient, competitor). Non-visit vs zero-sale (G-feat-38) cannot be defined without a visit-outcome code, FS-14 cannot separate honest zero sales from CPR padding, and the retailer-list hygiene signal (three consecutive "closed" → closure task) is lost. | G-field-03, one tap on the zero-sale dialog. |
| C4 | **Correction paths exist on paper but have no capture.** `memo.status='void'` exists (M-06) with no void event, reason, slip or stock/dues effect; `acting_for` does not exist, so an SS, an AMO selling for a dead-phone SR, or the wrong SR on a shared phone are attributed to the wrong person forever (P2 immutability); same-day cover cannot even start because the substitute has no bundle. | G-field-04, 05, 06. |
| C5 | **Retailer-facing money needs three small artefacts the spec omits**: a due-collection receipt, a staleness line on the printed "previous due", and a rule for closing an outlet that still owes. In the parallel pilot the retailer would receive two memos with different numbers from two apps; nothing says which one is real. | G-field-09, 12. |
| C6 | **Wholesale buyers are real (docs/22 P-16: 26,683 lines ≥ 10,000 sticks) and the price-type rule is absent.** `sku_price` has five types, `memo_line.price_type` exists, the bundle carries outlet prices, and nothing decides which type an outlet gets. | G-field-08 before 2a; the pilot must include a C&C outlet (lens-quality §5.1 already asks for one). |
| C7 | **Bangla is a UI rule in the spec but not a data rule.** Bengali digits in phone and quantity fields, collation of 735k Bangla outlet names on SQLite vs Postgres, and an A–Z alphabet filter over Bangla names will each produce a different list on the phone and on the web. | G-field-10, in `/packages` and both stores. |
| C8 | **Radius cannot be calibrated before the first wave**: the dump has no GPS fixes, the what-if tool (lens-config P2) needs fixes, and radius has no geo-class dimension although that is docs/05's own rationale. | G-field-11; the pilot's first job is to collect fixes per geo class. |
| C9 | Five cross-lens inconsistencies surfaced on the walk (debounce 5 s vs 10 s, bundle pre-gen 04:00 vs 22:00, `mock_policy` enum values, offline verifier PBKDF2 vs Argon2id, memo-number format in lens-config vs lens-sync). None is a field gap; all must be reconciled in the merged plan (§13). | Listed for the orchestrator; no G-field ids. |

---

## 1. The day's clock and the shared assumptions

| Item | Value used in the walk | Source |
| --- | --- | --- |
| Date | Tuesday 2026-10-06 (trading day); the following Friday is off; Eid-e-Miladunnabi fell in September; next public holiday 16 Dec | docs/22 P-03; cfg.calendar.* (lens-config §1.10) |
| Town | District town, patchy 3G/4G, one market street with ~380 outlets inside 55 m cells (P-10), a rural stretch with 2G | docs/22 P-10 |
| SR | `sr334001`, route "Apsis RouteDaily" (visit_days Daily), 50 outlets planned, 1 Astha outlet, 1 C&C buyer, 2 outlets with placeholder coordinates (P-09), 1 outlet with no coordinates | docs/06, docs/22 P-09/P-16 |
| Phone | Redmi 9A-class, 2 GB RAM, 32 GB with 1.1 GB free, Android 11 MIUI, shared with `sr334002` (alternate-day route next door) | lens-sync §5.1 D-sync-01 |
| Printer | RPP02N clone, 58 mm, paper roll half used | lens-sync §6 |
| AMO | `amo5001`, zone of 22 routes, 8 planned today | docs/07, P-13 |
| TSO | territory of 4 zones, closing at 19:00 from the territory office (good signal) | docs/08 |
| WM | wing of ~1,100 routes, opens the web dashboard at 08:30 on a laptop | docs/09 |
| Retailer | GT outlet, owes 1,840 Tk from last Tuesday's credit memo, pays on the next visit | docs/06 |
| DH | distribution house issues to 48 SRs between 06:45 and 07:45; takes returns 17:30–18:30 | docs/01 geography |
| Support | wave-1 day one: one territory per wing switched (~300 routes); L1 helpdesk 8 agents | lens-quality §5.6/5.7 |

Notation in the step tables: **C** captured on a device, **K** configured (a `cfg.*` key or master data), **S** must have synced (down or up), **D** displayed to someone.

---

## 2. (a) The SR — 50 outlets, patchy signal, shared phone

### 2.1 06:00–08:00 Morning

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 06:10 | SR picks up the shared phone; it is at 38 %, `sr334002` used it yesterday and left 23 rows pending | C: nothing yet. S: 002's rows must still upload under 002's token. D: a quiet line "অন্য ব্যবহারকারীর 23টি রেকর্ড আপলোড বাকি" | D-sync-01 per-user DB, lens-sync §8.1, G-sync-01 | OK (binding model itself is G-sync-01/G-sec-02, still a business decision) |
| 06:15 | Opens the app at home, no signal yet | K: offline unlock window `cfg.auth.offline_unlock_max_days`; stale-bundle `cfg.bundle.stale_max_days`. D: yesterday's or pre-fetched bundle | lens-security §2.6 (Argon2id verifier), lens-sync §4.5 (sell on a ≤ 2-day-old bundle), pre-fetch at 22:00 | OK; **inconsistency**: lens-sync's PBKDF2-100k verifier vs lens-security's Argon2id (G-sec-07 already flags it) |
| 06:40 | Walks to the DH; signal returns; app in foreground → bundle delta for today, 002's rows flush | S: `GET /sync/bundle` 304/delta; T2/T3 triggers; `day_open` event if the bundle was stale | lens-sync §2.1 T2/T3, §4.3, D-08 login event, G-sync-07 | OK |
| 06:50 | Battery 36 %: `requiresBatteryNotLow` will hold background jobs below 15 % | K: `cfg.sync.periodic_requires_battery_not_low`. D: nothing warns the SR that the phone will not last to 17:00 | lens-sync §8.20 | PARTIAL: no battery-level warning at check-in. ASSUMPTION it matters: shared phones arrive half-charged. Minor; folded into the support-code gap G-field-15 as a "device health line" on Home |
| 06:55 | **Attendance check-in** at the DH (indoors, GPS weak) | C: `attendance_event(kind=check_in)` + fix (accuracy 60–150 m, maybe none). K: `cfg.day.checkin_earliest_time`, `cfg.geo.max_accuracy_m`. S: priority class in the next batch | M-12, lens-sync §2.3 priority classes, FS-12 (check-in > 2 km from route centroid) | OK. Check-in with **no fix** indoors: `fix_id` nullable, allowed (G-feat-18 lists attendance edge cases) |
| 07:00 | **Stock issue**: the DH keeper hands over 15 SKUs; the SR types issued quantities in packs; prints the stock memo; the keeper signs the paper | C: `stock_movement(kind=issue)` per SKU with unit/pack_factor; stock memo print. K: units (D-02), `cfg.stock.print_stock_memo`. D: stock memo with "SR + distributor signature lines" | M-11, F-SR-014/015, lens-sync §6.2 | **MISS → G-field-01**: the DH is not a user; the issue is self-declared; nothing records the keeper's confirmation, nothing records "requested 60, issued 40 (DH out of stock)" |
| 07:05 | DH keeper disputes a quantity after the print ("you typed 40, I gave 30") | C: a correction. Today: new `stock_movement(kind=adjustment, qty −10)` | M-11 kind=adjustment | OK (but unsigned by the DH; part of G-field-01) |
| 07:10 | SR notices the phone date is wrong (someone set it back a day yesterday to beat the 17:00 gate) | C: `clock_offset_ms`, trusted time anchor. D: banner "ফোনের সময় ঠিক করুন" | D-sync-13, lens-security §5.4, FS-13, T-1-76 | OK |
| 07:20 | **Printer pairing**: last printer MAC stored device-wide; Android 12+ needs `BLUETOOTH_CONNECT` | K: `cfg.print.disconnect_idle_s`. C: `device.printer_id` | lens-sync §6.1, M-41 | OK |
| 07:30 | Which route today? The SR is on "Daily"; 002 is on Sun/Tue/Thu + Mon/Wed/Sat; the header must show the right route | K: `visit_days_mask`, `route_day.planned`, `cfg.calendar.weekend_days`, `cfg.route.allow_unplanned_day`. D: route picker when an SR has 2 planned routes | M-18, M-32, G-feat-40 (multi-route header), G-feat-10 | OK (G-feat-40 open) |
| 07:40 | **It is pouring; the market street will not open before 11:00; two SRs on the rural route are told "no haat today"** | C: a *reason* that today or part of today is not a selling day for this route, from the field, offline. K: `cfg.calendar.route_visit_day_exceptions` exists but is admin/TSO-only, per route per date, web-side. D: Daily Tracking must not bucket the route <80 % red; Login % denominator must drop it or mark it | lens-config §1.10, G-feat-09/10, D-09 target outlets | **MISS → G-field-02**: no field-side day-exception capture (rain/flood, hartal, market closed, DH out of stock, vehicle breakdown, sick without leave). Today the SR simply does not log in and management sees "not logged in" with no reason |
| 07:50 | SR `sr334002` phones in sick; the AMO wants `sr334001` (or the SS) to cover 002's route tomorrow — and the AMO wants to know **now** who will carry the phone | K: `route_assignment(kind=cover, valid_from=valid_to=D+1)`. S: the cover user's bundle must include the route; `scope_version` bump | M-14, G-feat-07 (SS flow), F-ADM-003 (web, Phase 6), G-cfg-12 scope_version | **MISS → G-field-06**: there is no AMO/TSO-app action to assign cover; until a `route_assignment` exists the substitute has no outlets in the bundle and DQ-06 would reject his rows as out of scope |

### 2.2 08:00–12:00 The beat begins

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 08:05 | Opens the outlet list: 50 outlets, label `name (code-phone-cluster)`, alphabet filter; 38 names are in Bangla script, 12 in Latin | D: a sorted list, an index strip, search. K: `cfg.app.outlet_list_label_format` | F-SR-016 ("Bangla vs Latin alphabet filter → confirm"), lens-sync §1.5 `ref_outlet` | **MISS → G-field-10**: no collation decision (SQLite code-point order ≠ Postgres ICU `bn-x-icu`), A–Z strip useless for Bangla names, Bengali digits typed into search/phone/qty never normalised |
| 08:10 | First outlet of the day: taps it; GPS cold after 70 min indoors; 8 s high-accuracy then 15 s timeout → "no fix" → Force Sale dialog | C: `visit` with `device_geo_verdict=no_fix`, `gps_retry_count`. K: `cfg.geo.fix_timeout_s`, `cfg.geo.refresh_max` | lens-sync §5.3 (one fix per outlet open), F-SR-017/019, M-05 | PARTIAL → **G-field-13** (minor): acquisition starts only on tap; the first outlet after any long gap becomes a force sale and an FS-09 count against an honest SR. Start acquisition on list entry and reuse a fix ≤ 60 s old |
| 08:20 | Outlet #2 **is shut** (owner at the mosque / closed Tuesday / moved). The SR walks on | C: today there is no capture at all, or the SR does a "zero sale" to show he was there. D: KPI strip Non-visit / No-sale | F-SR-010, F-SR-029, G-feat-38, D-05, FS-14 | **MISS → G-field-03**: no visit outcome code. A shut shop, an absent owner, a refusal and "stock still fine" are all either invisible or a zero sale; FS-14 will flag the honest SR who records shut shops as zero sales |
| 08:35 | Outlet #3 is on the list but 400 m away from its stored pin (placeholder pin, P-09) | C: force sale `location_change` + photo; `outlet_location_history(source=force_sale_photo)` → change request → AMO verifies | docs/05, F-SR-018, M-15/M-16, `cfg.geo.first_capture_sets_location`, P-09 "location unconfirmed" flag | OK. Note: the retailer who refuses to be photographed blocks the sale (`cfg.sale.force_requires_photo=true`); parity with today, keep — the guidance "photograph the shop front, not the person" belongs in the SR tutorial, not the code |
| 08:50 | Outlet #4 has **no coordinates** at all (1,093 such outlets, P-09) | C: force sale with reason `no_outlet_location` (a reason code, not the 2-value enum). K: `cfg.geo.no_location_policy` | lens-sync §8.11, lens-config §1.1, G-feat-39, G-sync-11, P14 (reason codes as config) | OK (policy value is Q25) |
| 09:00 | Outlet #5 wants Maxim 20s ×3 packs + 5 loose sticks; offers auto-apply; credit 500 Tk; QC: 1 pack transport fault; print | C: `memo`, `memo_line(qty_entered, unit_entered, pack_factor)`, `memo_offer`, `qc_entry`, `print_job`. K: `cfg.sale.qty_entry_unit`, `cfg.promo.rules`, `cfg.memo.rounding_mode`, `cfg.credit.*`, `cfg.qc.required_before_print` | M-06..M-08, D-01/D-02, G-feat-13 (promo engine, blocker), G-sync-04 (rounding), D-qa-14 (promo to 2a) | OK as designed; both blockers already owned |
| 09:05 | **Paper runs out on line 7 of the memo.** The clone printer reports nothing. The app marks the job done; the SR loads paper and reprints; the reprint carries "পুনর্মুদ্রণ #1" | C: `print_job(state)`, `print_count`. K: `cfg.print.status_query` (false), `cfg.memo.reprint_watermark` | lens-sync §6.3 step 3 "paper out (when detectable)" | PARTIAL → **G-field-20** (minor): the retailer's only legible memo says DUPLICATE. Add a one-tap "ছাপা ঠিক আছে?" confirmation; a reprint after "না" carries no marker and does not count toward `cfg.memo.reprint_max` |
| 09:15 | Outlet #6 is the **C&C buyer**: 20,000 sticks of one SKU, pays by bank transfer later, expects the `cc` price (7.935 → mtk) | C: `memo_line.price_type=cc`, `unit_price_mtk` at the cc list. K: which price type this outlet gets; the bundle must carry that list. D: review screen shows cc prices | M-07 `price_type DEFAULT 'outlet'`, M-16 `outlet_kind`, bundle §4.1 (prices by type), DQ-13 `price_mismatch`, P-16, Q17 | **MISS → G-field-08**: no rule maps outlet → price type, the SR cannot choose, DQ-13 would flag every C&C memo as `price_mismatch`, and the printed total differs from Apsis on day one |
| 09:20 | The C&C quantity 20,000 trips `cfg.sale.max_line_qty_base` = 100,000? No (100k). But a fat-finger 200,000 would be flagged, not blocked | K: `cfg.sale.max_line_qty_base` (soft, per zone). | lens-config §1.3, DQ-12 `qty_outlier` | OK |
| 09:30 | The retailer at #7 **refuses to pay last week's 1,840 Tk** ("I paid the other SR on Thursday") | C: the dispute. Today: nothing; the SR either records a collection he did not receive or leaves the due | F-SR-032, G-feat-45 (allocation/aging), F-ADM-036 (admin adjustment) | **MISS → G-field-09(d)**: no `due_dispute` capture from the field (claimed amount, claimed date, claimed collector) that becomes an AMO task and a finance queue item. The retailer's claim may be true if `sr334002`'s Thursday collection is still pending on the shared phone |
| 09:35 | Same outlet: the memo the SR prints shows "আগের বাকি 1,840" — but if 002's Thursday collection is unsynced (or synced after this morning's bundle) the printed previous due is stale | D: the printed "previous due" line. K: `cfg.memo.show_due_balance_on_print` | lens-sync §6.2 totals block, `ref_open_memo` recomputed with pending local collections only | **MISS → G-field-09(b)**: no staleness marker ("বাকি <date> পর্যন্ত তথ্য অনুযায়ী") on the printed balance; disputes will follow |
| 09:50 | SR captures a **new shop** (name typed in Bangla with Ridmik keyboard; phone typed with Bengali digits ০১৭…) and sells to it immediately | C: `outlet_change_request(type=new)`, photo, fix; provisional outlet id (`outlet_client_uuid`) on the visit/memo. | G-feat-42, lens-sync §8.12, FS-03 (duplicate detection by `phone_hash`) | PARTIAL: `phone_hash` = HMAC(E.164-normalised) will **not** match if the digits are Bengali and never normalised → duplicates slip through FS-03; part of **G-field-10** |
| 10:00 | Phone signal is back for 20 min; trickle sends 6 visit families; a radius change (100 → 80 m for this territory, approved yesterday, effective today 00:00) was already in the bundle's `scheduled` list | S: batches, `X-Config-Version` compare, `config_ack`. K: `cfg.geo.radius_m` at T scope | lens-sync §2.1–2.4, lens-config §3.1–3.2, T-2-62 | OK |
| 10:30 | **MIUI kills the app** while the SR is mid-sale at outlet #12 (he answered a call) | C: `sale_draft` autosave; on relaunch "চলমান কল পুনরায় শুরু করুন?" | lens-sync §8.4, §8.9, T-1-24 | OK |
| 11:00 | **AMO arrives for a joint call** at outlet #14; both open a visit on the same outlet | C: two `visit` rows, kinds `sr_call` and `amo_joint_call`; `call_assessment` by the AMO | F-AMO-011, F-AMO-036, M-05 `kind`, P-06 (two visits one outlet one day) | OK; see **G-field-16** on which KPIs each kind feeds |
| 11:10 | At the joint call the SR captures a new shop and the AMO, standing there, wants to verify it on the spot | S: the request must reach the server and come back in the AMO's delta before the AMO can verify; both phones are offline | F-AMO-022 (verify from bundle `pending requests`), lens-sync §4.3 | PARTIAL → **G-field-23** (minor): no on-the-spot verification; a QR of the request's `client_uuid` scanned by the AMO would produce a verification event parked until the request lands (DQ-03 already parks children) |
| 11:30 | Outlet #16 wants to **return a torn pack bought last week** | C: today only `qc_entry` at sale time | G-feat-05 (returns/damaged after QC) | OK as an owned gap |
| 11:45 | Outlet #17: the SR enters 15 packs, prints, the retailer changes his mind and **cancels the whole purchase** | C: the memo must be voided: reason, who, fix; stock back into current stock; due reversed if credit; the retailer holds a printed memo. K: `cfg.memo.edit_after_print_policy` | M-06 `status in (active, superseded, void)`, F-SR-033 (edit = re-enter), D-sync-06 "no void slip" (ASSUMPTION) | **MISS → G-field-04**: `void` has no event, no reason list, no cancel slip, no stock/dues effect, no DQ rule and no fraud signal. An "edit to zero lines" is not defined as a void and would print a zero memo with a new number |

### 2.3 12:00–17:00 Afternoon

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 12:30 | Lunch + Zohr; the phone sits for 50 min with 9 pending families, no signal | S: nothing; T4 one-off armed; no polling | lens-sync §2.1 T4/T5, §5.3 background ≤ 1 %/8 h | OK |
| 13:00 | **Storage warning**: 1.1 GB free falls under 500 MB after the SR's own WhatsApp videos; the app evicts its image cache; at 200 MB photo capture is refused but force sale still allowed with `photo_pending_storage` | K: thresholds; `cfg.media.local_queue_max_mb` | lens-sync §8.5, G-sync-20 | OK (ASSUMPTION in lens-sync that the business accepts a force sale without photo under storage pressure — confirm) |
| 13:30 | `sr334002` turns up (he feels better) and takes the shared phone to sell on his own route while 001 still has the session open; he sells 6 memos **under 001's login** before noticing | C: visits carry `user_id=001`, `route_id=002's route` → DQ-07 `unassigned_route` flag only. Memo numbers are 001's series. Dues owed to the DH are counted against 001's cash | lens-sync §8.1 (per-user DB), §8.13 (two devices one user), DQ-07, `cfg.auth.app_lock_idle_min` default 0 | **MISS → G-field-05**: no identity confirmation on first capture of the day on a multi-user device, no `acting_for_user_id`, and (P2 immutability) no audited re-attribution path for a route-day's rows. The six memos are wrong for KPIs, incentives and cash settlement for ever |
| 14:00 | SR runs out of a fast-moving SKU; a DH van tops him up on the road | C: second `stock_movement(kind=issue)` same day | M-11 (events, not a per-day sum) | OK (confirmation again G-field-01) |
| 14:30 | Outlet #31 is the **Astha** outlet: STD targets by brand, gift "27 pcs Dinner Set" chosen in the TSO portal, hand-over photo | C: `gift_photo` linked to `gift_assignment`; D: Astha tabs with quarter filter | F-SR-041/044, M-22/M-24, G-feat-21 (gift choice surface) | OK (Phase 5) |
| 14:45 | Outlet #33: Diamond League redemption, 2 Tk/point cash back for 150 points — the balance in the bundle is yesterday's | C: `redemption` + lines; K: `cfg.loyalty.cash_rate_mtk_per_point`. S: server rejects over-balance | F-SR-043, M-23, G-sync-19, FS-06, T-5-70 | OK (earning rules G-feat-20 still blocker for Phase 5) |
| 15:10 | Location permission was revoked by the OS "unused app permissions" auto-reset over the weekend (Android 11+) | C: reason code `permission_denied` force sale; `device_capability_snapshot` | lens-sync §8.6, M-41, G-sync-11 | OK |
| 15:30 | Rural stretch, 2G only; a 200-row catch-up would time out → halving on 413, trickle is tiny anyway | K: `cfg.sync.batch_max_rows`, `batch_max_kb_raw` | lens-sync §2.3, T-2-25 | OK |
| 16:00 | The SR has done 44 of 50; 3 shut, 1 refused, 2 not reached (rain). He wants the day's "non-visit" list to carry reasons so the AMO does not call him | D: KPI strip Non-visit = planned − visited; nothing explains why | F-SR-010, G-feat-38 | **G-field-03** again: outcome codes also cover "not reached" when the SR marks it from the list without opening a visit (no fix, no geo gate — a *skip* record, not a visit) |
| 16:30 | Local notification "আপলোড বাকি 31টি" from the periodic job (pre-18:00 reminder) | K: `cfg.sync.pending_reminder_time` | lens-sync §8.9 | OK |
| 16:55 | SR tries to check out at 16:55 by setting the clock forward | C: corrected-time gate; `checkout_before_allowed` flag | D-sync-13, DQ-20, lens-config §3.2 restrictive fallback | OK |

### 2.4 17:00–22:00 Day close

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 17:00 | **Sync → reconciliation screen → Sales Submit** with 2 outlets still owing (warning) and 1 rejected row (`sku_not_in_plan`) → **Check-out** (indoors at the DH, weak fix) | C: `day_submit`, `attendance_event(check_out)` as outbox events; D: device vs server counts per type, Bangla reason text for the rejected row | D-sync-08, F-SYS-009, lens-sync §2.9, `cfg.day.sales_submit_dues_warning`, G-feat-65 | OK |
| 17:30 | **Stock return at the DH**: 14 unsold packs + 1 transport-fault pack; the keeper counts 13 | C: `stock_movement(kind=return)`; damaged vs unsold kinds; DH count vs SR count | M-11 (`issue/return/adjustment` only), G-feat-02 (return flow absent), G-feat-05 (damaged path), FS-17 | PARTIAL → **G-field-01**: the DH's count has no home; kinds lack `damaged`/`qc_return`; variance belongs to a confirmation event |
| 17:45 | **Cash hand-over**: Σ paid + Σ collections − float; the keeper counts the cash; a 60 Tk shortfall | C: `cash_handover` event (declared, counted, variance, by whom) | G-feat-03 (absent) | **G-field-01** (cash side); the SR summary print (F-SR-036) is his statement, nothing is the DH's |
| 18:00 | Summary print as the end-of-day statement | D: per-SKU memo count/qty/value/discount/return, category totals | F-SR-036, G-feat-60 (layout) | OK |
| 18:30 | At home on Wi-Fi: 10 photos upload; T8 trigger; tomorrow's bundle pre-fetched | S: media queue, SAS; `GET /sync/bundle?for=D+1` | lens-sync §2.11, §4.5 pre-fetch (ASSUMPTION 22:00 pre-gen), lens-scale D-scale-3 (04:00) | OK; **inconsistency**: 04:00 vs 22:00 pre-gen (lens-sync flags it; §13) |
| 20:00 | A late `after_final_submit` row: the TSO closed the zone at 19:00, the SR's last two memos sync at 20:10 | S: accepted and flagged, aggregated into D | lens-sync §3.2, `cfg.day.late_sync_after_final_policy`, G-feat-17 | OK |
| 21:00 | The SR hands the phone to `sr334002` for tomorrow; it is at 11 % | — | — | not a system matter; the 06:50 device-health line (G-field-15) is the only lever |

### 2.5 What the SR day needs that nobody owns yet

| Need | Gap |
| --- | --- |
| Day-exception capture from the field (rain, hartal, market closed, DH out of stock, breakdown, sick without leave) | G-field-02 |
| Visit outcome / no-sale reason, and a "skip" record for outlets never opened | G-field-03 |
| Memo void after print: event, reason, slip, stock/dues effect | G-field-04 |
| Wrong-user capture on a shared phone; `acting_for`; re-attribution | G-field-05 |
| Same-day cover assignment from the AMO app | G-field-06 |
| Price type per outlet (C&C) | G-field-08 |
| Due dispute capture; stale "previous due" marker | G-field-09 |
| Bangla digits, collation, index strip | G-field-10 |
| Warm GPS before the first outlet | G-field-13 |
| Print confirmation before "duplicate" marking | G-field-20 |

---

## 3. (b) The AMO — one zone, 22 routes, 8 planned today

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 07:20 | Bundle for the zone: all 22 routes' outlets (~1,400) because control calls may pick any route; open requests to verify; exceptions | S: paged bundle sections > 2,000 rows; `cfg.bundle.max_gz_kb` | lens-sync §8.19, §4.1, lens-security §7.4 (exceptions ≤ 200 rows in bundle) | OK |
| 07:30 | Attendance (press-and-hold, reverse-geocoded address needs network → coordinates offline) | C: `attendance_event` | F-AMO-003 | OK |
| 07:40 | Decides the day: Team Location is empty until SRs check in; check-in fixes are priority rows | D: last fix + age per SR; `cfg.tso.team_location_max_age_min` | F-AMO-016, G-feat-36 | OK |
| 07:50 | **`sr334002` is sick**: the AMO must get his route covered today | K: cover assignment; S: substitute's bundle | G-feat-07, F-ADM-003 (web, Phase 6) | **G-field-06**: no app action; the AMO's only options are to sell the route himself (attributed to the AMO, see G-field-16) or lose the day |
| 08:00 | **Rain**: the AMO wants to mark three rural routes "market closed" so the TSO's dashboard does not show them red and so their Login % does not count | K: `cfg.calendar.route_visit_day_exceptions` — admin/TSO web only, per route per date | lens-config §1.10 | **G-field-02**: the AMO in the field cannot declare it; needs a zone-level, date-ranged exception from the app with a reason, approved by the TSO |
| 08:30 | SR Stock tile: who lifted what (only synced loads) | D: `agg_daily_user_sku` | F-AMO-021 | OK |
| 09:00 | **Control call** at an outlet 60 m from its pin in a dense market; "AMO not within retailer range" → Manual Override photo (updates location through the change flow) | C: `visit(kind=amo_control_call)`, `force_reason=manual_override`, photo | F-AMO-004/005, M-01 enum value, `cfg.geo.outlet_location_change_approval` | OK |
| 09:10 | Distribution/OOS/POSM check, 15 brands | C: `distribution_check` header + lines | M-26, F-AMO-007..009 | OK |
| 09:20 | **Price compliance** — the AMO's reconciliation screen counts it, no screen exists | C: `price_compliance_check` (fields ASSUMPTION) | G-feat-01, M-28 | OK as an owned gap; the walk adds: observed price should be captured per **pack as sold** (retail price per pack is what the shopkeeper quotes) — note for M-28 `observed_unit` |
| 09:30 | AMO sells from his own stock at the control call | C: memo with `user_id=amo`, `kind=amo_control_call`, route_id of the outlet | F-AMO-006/012/029 | PARTIAL → **G-field-16** (minor): which KPIs an AMO memo feeds (route STD yes, route CPR?, SR efficiency no) is undefined; F-AMO-036 ("must not inflate SR CPR") and D-09 ("any outlet visited counts") disagree |
| 11:00 | **Joint call** with `sr334001` (see §2.2 11:00) — rubric, steps 4–5 unknown | C: `call_assessment`, rubric version | F-AMO-011, G-feat-56, M-27 | OK |
| 11:10 | On-the-spot verification of the new shop the SR just captured | — | F-AMO-022 | **G-field-23** (minor) |
| 12:00 | Verifies 4 pending outlet requests offline; sets sub-channel + geo class; one is a probable duplicate (same phone) | C: verification events, `verifier_payload`; D: "possible duplicate" badge | F-AMO-022, M-15, FS-03/G-sec-18 | OK — **but** FS-03's `phone_hash` match fails on Bengali-digit phone numbers (G-field-10) |
| 13:00 | Assigns a task (OOS) to an SR; resolve is swipe | C: `task` + `task_event` | M-25, F-AMO-019, G-feat-19 | OK |
| 14:00 | Live Dashboard: strike rate, geo %, login/submit — needs network; "as of" stamp | D: `agg_daily_route` zone view | F-AMO-020, D-03 (Submit % vs Upload-of-login %) | OK |
| 15:00 | An SR's phone died; the AMO **sells the rest of that SR's route on his own phone** so the retailers are served | C: memos with `user_id=amo`, `kind=?` — there is no "on behalf of" | — | **G-field-05**: `acting_for_user_id` + the route's assignee list is exactly this case; without it the route's day shows the AMO as seller and the SR as absent |
| 16:00 | Update Base for a mis-pinned outlet: photo + pick the exact point on a map | D: map tiles (offline?), tile budget | F-AMO-028 ("P (map needs tiles)"), lens-sync §5.6 ("lightweight tile widget on demand") | PARTIAL → **G-field-18** (minor): no tile provider, licence, cache size or data budget named; OSM public tiles forbid heavy app use, Google Maps SDK adds ~5 MB and a licence |
| 17:00 | AMO Sales Submit with nine reconciliation types; dues warning | C: `day_submit` | F-AMO-030 | OK |
| 17:30 | Exceptions tile: today's FS-08/09/14 signals for the zone; review/dismiss offline | C: `risk_review` events | lens-security §7.4, T-3-73 | OK |
| 18:00 | The DH keeper asks the AMO to confirm three SRs' returns and cash because "the system has no place for me" | — | — | **G-field-01**: the AMO as DH proxy (`confirmed_by` on `stock_movement`, `cash_handover.counted_by`) is the cheapest parity-safe design if the business will not create DH logins (Q17) |

---

## 4. (c) The TSO — closing a territory of 4 zones at 19:00

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 08:30 | Dashboard: login status per zone; "Not Logged In" list (SR/AMO name, username, route, dep) | D: `agg_daily_zone`, `route_day` states | F-TSO-007, D-08 | OK — **but** the three rain routes show as "Not Logged In" with no reason (G-field-02) |
| 10:00 | A phone died in zone 3: TSO must issue a device OTP for the replacement **from the field** | C: `device_otp`; `bind_ordinal` | F-TSO-019 (web only), G-feat-11, lens-security §2.3 | OK as an owned gap (G-feat-11 says the TSO has no app screen) |
| 11:00 | Set Plan for tomorrow (zone, route, outlets); Visit Query at planned outlets | C: `visit_plan`, `call_assessment(kind=retailer_questionnaire)` | M-35, F-TSO-013..015, G-feat-49 | OK |
| 12:00 | A retailer calls the TSO: "the memo total is wrong by 1 paisa vs the old app" | — | G-sync-04, G-feat-46 | OK as owned blockers |
| 14:00 | Target Status (till-date basis working days) | D: `agg_month_zone_product`, `dim_date.elapsed_fraction_working` | D-07, F-TSO-017, `cfg.target.tilldate_basis` | OK |
| 16:00 | TSO on the periphery map, 300 m radius | D: `GET /outlets/nearby`; single fix | F-TSO-012, F-API-019 | OK |
| 18:30 | Daily Tracking: two routes <80 % — one is the rain route, one is the dead-phone SR | D: buckets; "take action" after 17:00 | F-WEB-038/039, G-feat-29 | PARTIAL: without G-field-02 and G-field-05 both routes look like under-performance |
| 19:00 | **Final submit zone 1**: "Get Sales Data" → 12 routes, 2 "Not Set" (one is the Wi-Fi-only SR who syncs at 21:00, one never logged in) | C: `final_submit`, `final_submit_route` snapshot; K: `cfg.day.final_submit_allow_not_set_routes` (warn), `late_sync_after_final_policy` (accept_and_flag) | M-34, F-TSO-010, G-feat-64, G-feat-17, lens-sync §3.2 | OK; the snapshot memo count (10 routes) and the final aggregate (12 routes, after 21:00) will differ by design — the Final Submit Log must label its number "at submit", the STD reports always use the aggregate (D-scale-2) |
| 19:05 | Zone 2: the TSO's colleague is **on leave**; this zone is in *his* territory and nobody can submit it; the DMO is not allowed by scope | K: `cfg.day.reopen_roles` exists; nothing defines who may *final-submit for* an absent TSO, nor an automatic close | F-TSO-010 (TSO = own territory), `leave_application` (M-33), lens-config §1.2 | **MISS → G-field-07**: no final-submit delegation (acting TSO, DMO) and no auto-close time; a zone can stay open indefinitely, month-end rollups and "final-submit coverage = 100 % by 21:00" (T-7-87) then fail for a reason that is not a defect |
| 19:10 | Zone 3: the TSO is in a dead spot; final submit is online-only | — | D-sync-08 keeps `POST /day/final-submit` online | PARTIAL (part of G-field-07): a `final_submit` outbox event from the TSO app is the same mechanism as `day_submit`; the once-only rule is enforced on the server by the PK anyway |
| 19:30 | Zone 4: second attempt refused "already given FINAL SUBMIT" — the retry with the same `client_uuid` should return the first success | — | M-34 `client_uuid` on final_submit | OK |
| 20:00 | Reviews the day's exceptions (mock flags, teleports); confirms two, dismisses one | C: `risk_signal.status` | lens-security §7.4 | OK |
| 20:30 | Leave application for next week (Casual) → DMO | C: `leave_application` | M-33, F-TSO-009, G-feat-25 | OK — the walk adds: a TSO leave that is approved must **also** name the acting TSO for final submit (ties to G-field-07) |

---

## 5. (d) The Wing Manager — 08:30 at the laptop

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 08:30 | Opens the dashboard. Wants: yesterday's sales vs target (complete), today's login % (in progress). The page has one date filter | D: default date logic; "as of HH:MM"; comparator "same time yesterday" | F-WEB-001, lens-scale §4.14 "as of", `cfg.sla.login_pct_alert_time` | PARTIAL → **G-field-19** (minor): no date-default rule (sales tiles default to the last *final-submitted* business date, login/submit tiles to today), no same-time-yesterday comparator on the tile (only in the alert), no non-working-day banner |
| 08:35 | Login % for the wing is 61 %: 3 % are rain routes, 2 % are stale-bundle offline starts that will appear as `day_open` later | D: `offline_start` flag; day-exception reason | G-sync-07 (`day_open`), G-field-02 | PARTIAL until G-field-02 |
| 08:40 | Drills to a territory → zone → route → SR; sees only the wing | D: scoped reads; `user_route_reach` | lens-security §3, F-WEB-041, T-0-75 | OK |
| 08:50 | Yesterday's MTD moved by 0.4 % since 21:00 (late syncs) | D: late-data report; "as of" | lens-sync §3.2, D-scale-2 | OK |
| 09:00 | Approves a target revision at level 3 | C: `target_revision_event` | M-21, F-WEB-030, G-feat-24 | OK |
| 09:15 | Exports the Astha report (PII? no — outlet code/name only); exports Browse Retailer with phones → `pii` claim + re-auth + export log | C: `report_export_log` | lens-security §2.7, §4.1, T-4-72 | OK |
| 09:30 | Looks at the geo-valid % by territory: territory X dropped 25 points since Monday — the radius change to 80 m | D: anomaly watch; config history with provenance | lens-config §4.9, `cfg.sla.geo_valid_drop_alert_pts` | OK |
| 09:45 | Asks "which radius is right for my hill territory?" — no fixes exist yet from the dump; the what-if tool has nothing to re-evaluate; radius has no geo-class lever | D: what-if over stored fixes; K: `cfg.geo.radius_m` by `geo_class` | lens-config P2 what-if, docs/22 (dump has no fixes), docs/05 rationale | **MISS → G-field-11** |
| 10:00 | On Friday the WM opens the dashboard: Login % 0/0; must read as "non-working day", not an outage | D: `dim_date.is_working_day` banner | cfg.calendar.*, dim_date | PARTIAL (part of G-field-19) |

---

## 6. (e) The retailer — a memo today, dues a week later

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| Tue 09:30 | Receives the printed memo: same layout as the Apsis memo, Bangla raster, memo no `sr334001-261006-007`, paid 1,000, due 840, previous due 1,840, total due 2,680 | D: template parity; K: `cfg.memo.number_format`, `cfg.print.template_version` | G-sync-02 (layout not captured anywhere — blocker), D-sync-07/D-04, lens-sync §6.2 | OK as owned; the "previous due" line needs the staleness marker (**G-field-09(b)**) |
| Tue 09:35 | Asks for a **receipt for the 1,000 Tk** he just paid against the old due | D: a due-collection receipt print | F-SR-032 ("receipt print? ASSUMPTION none") | **MISS → G-field-09(a)**: no receipt template; the memo shows "paid" only for *this* memo; a separate collection against an old memo leaves the retailer with no paper. Retailers "will dispute anything wrong" (docs/11) |
| Tue 10:00 | The retailer notices the memo is at **cc price** (he is a C&C buyer) — or is not | — | G-field-08 | MISS → G-field-08 |
| Tue 11:00 | Retailer tears up a memo after a cancelled purchase; the SR voids | D: cancel slip "বাতিল — মেমো … " so the retailer cannot later present the memo as a credit record | G-field-04 | MISS → G-field-04 |
| Wed | A different SR (`sr334002`, alternate day) comes; the retailer pays 1,680 Tk against two memos | C: `due_collection` by a user who is not the seller; allocation across memos | G-feat-45 (FIFO vs chosen, collection by another SR), DQ-19 | OK as owned gap |
| Thu | The retailer's shop is **sold to his nephew**; name/owner change; dues stay with the outlet | C: `outlet_change_request(type=info)` | F-SR-039, M-15/M-16 | OK — ASSUMPTION dues belong to the outlet, not the person; confirm with the business |
| Fri | Off day | — | cfg.calendar | OK |
| next Tue | The retailer **closes for good** with 2,680 Tk outstanding and 90 Diamond League points | C: `outlet_change_request(type=close)`; the dues and points | F-SR-038, F-AMO-023, M-15, F-ADM-036 (write-off, finance approver) | **MISS → G-field-09(c)**: no rule for closing an outlet with open dues/points (block, warn, route to a write-off queue, keep `closed_with_dues` status); the AMO's verification screen does not show the balance |
| next Tue | During the parallel pilot this retailer receives **two memos** (Apsis app + new app) with different numbers and totals that differ by one promotion | D: which memo is "real"; dues recorded twice | `cfg.flag.parallel_run_mode` (rows `parallel=true`, excluded from aggregates), T-2-48, T-7-82 | **MISS → G-field-12**: print behaviour in parallel mode is undefined (watermark "পরীক্ষামূলক", no print, or print only on the live app), and the retailer's confusion is a parity failure the pilot sheet (#15) will record but the design does not prevent |
| any day | The retailer calls the AMO: "my balance in the new app is 300 Tk more than the old app said" (cutover) | D: provenance on the device: opening balance as of the import date with the Apsis memo numbers, then the new movements | M-42 `opening_balance`, lens-sync §4.1 `open_memos` (memo_no verbatim for migrated memos) | PARTIAL → **G-field-22** (minor): the bundle/app does not show "balance per old system as of <date>" separately from movements since; disputes on cutover week cannot be settled at the shop |
| any day | The retailer refuses the force-sale photo | — | `cfg.sale.force_requires_photo=true` | parity; no change; tutorial guidance only |

---

## 7. (f) The distribution house — issue at 07:00, returns at 18:00

The DH is on the geographic spine (`house` between `territory` and `zone`, docs/01) but is not a user. Roles are `sr, amo, tso, dmo, wm, top, admin` (schema.sql). Q17 asks only whether DHs use "bex" order pages and back-margin letters. Everything below is therefore observed through the SR's phone.

| Time | Step | System must have | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 06:45–07:45 | 48 SRs queue; each types his own issued quantities; each prints a stock memo the keeper signs | C: `stock_movement(kind=issue)` ×15 per SR; D: stock memo. Nothing records the keeper, the DH's own count, or "requested vs issued" | M-11, F-SR-014/015 | **G-field-01**: self-declared issue; `requested_qty`/`issued_qty` both missing; DH stock-out invisible (a cause of the G-field-02 "no stock" day exception) |
| 07:00 | Two SRs on the same route (cover day) both lift stock | C: two users' movements, route_id NULL when ambiguous | M-11 `route_id NULL when SR covers several` | OK |
| 07:30 | The DH keeper wants the day's **issue sheet** per SR to reconcile his own ledger | D: a DH-level view: per SR per SKU issued (today), from `agg_daily_user_sku` | lens-data §4.3 `agg_daily_user_sku`, F-AMO-021 (AMO sees "SR Stock") | PARTIAL → **G-field-01**: the data exists in `dw`; the *reader* does not (no DH role, no DH page, no scope node of type `house` for a user) |
| 17:30–18:30 | Returns: unsold packs, damaged packs (from outlet QC), short packs; the keeper counts | C: `stock_movement(kind=return)`; kinds for `damaged`/`qc_return`; the keeper's count and variance | M-11, G-feat-02 (return flow absent), G-feat-05 (damaged path), DQ-21 `stock_variance`, FS-17 | **G-field-01**: `confirmed_by`, `counted_qty`, `variance_reason` on the return event; AMO-as-proxy if no DH login |
| 18:00 | Cash: each SR hands over Σ paid + Σ collected; the keeper counts; shortfalls are deducted from the SR | C: `cash_handover(declared_mtk, counted_mtk, variance_mtk, counted_by, fix)`; D: DH daily settlement (per SR: issued, sold, returned, cash declared/counted, credit created, collections) | G-feat-03 (absent) | **G-field-01**: the settlement view is a `dw` read (`agg_daily_user_sku` + `agg_daily_route.collected_mtk`) once the event exists |
| 18:30 | The DH closes its day; the TSO's final submit does not wait for it, and nothing links "DH settled" to the zone's day | — | — | note for the business (Q): whether DH settlement should be a visible state on the sync-health screen; ASSUMPTION it is wanted by sales ops, not required for cutover |
| month end | DH stock count vs system stock | — | out of scope (no DH inventory model; parity does not need it) | — |

---

## 8. (g) The support desk — wave-1 day one

~300 routes switched; 3–5 % contact rate ≈ 10–15 calls (lens-quality §5.7); the full-fleet day would be 250–425. The desk has the admin device page, quarantine panel, config console, sync-health, `GET /config/public` banner, and Bangla scripts for 20 issues.

| Time | Call | What the desk needs | Covered by | Verdict |
| --- | --- | --- | --- | --- |
| 06:30 | "Cannot log in" ×6: wrong password (migrated hashes or forced reset), lockout after 10 tries on a shared phone, `min_version` banner | D: lockout state, `must_change`, temp password by TSO; identity check of the caller | G-sec-03, G-feat-34, lens-security §2.2, `cfg.auth.lockout_attempts` | OK |
| 06:40 | "App shows an error and I cannot read it" — the caller is pre-login; no telemetry has arrived; the desk cannot see the device | D: something the SR can **read aloud** | `GET /config/public` (banner + helpdesk number), lens-quality §5.7 scripts | **MISS → G-field-15** (minor): a short support code on every blocking screen (error code · app version · config_version · last 4 of device_uuid · bind state · free storage · battery) and a decoder page for the desk |
| 07:00 | "No route today" ×4: import left `route_assignment.valid_to` in the past, or the SR covers a route the dump never assigned | D: assignments; action: L1 fixes assignment with TSO confirmation → the SR's delta picks it up | lens-scale runbook, lens-quality §5.7 L1, lens-sync §4.3 (assignments delta-able) | OK |
| 07:10 | "Three of my outlets are missing" (P-08 archived stubs, P-07 malformed codes, outlet on the wrong route) | action: reactivate / move outlet → delta; the SR must not create duplicates via "new shop" | G-feat-61 (route-change request), G-feat-62 (reactivation), FS-03 | OK as owned gaps — the walk raises their urgency: both are **Phase 6 web CRUD** today but are day-one cutover calls; recommend pulling G-feat-61/62 into 2c (SR-side "outlet exists, add to my route" request type) |
| 07:30 | "Printer prints boxes instead of Bangla" (a clone with a different dot width) | action: `cfg.print.template_version`, template JSON in bundle; known-printer list | lens-sync §6.2, T-1-35 (two printers) | OK — add the printer model to the device record so the desk can see it (`device.printer_name` exists, M-41) |
| 08:00 | "Old app still works, which one do I use?" | D: wave membership by username; banner in the old app is not under AKTCL's control | G-feat-67, P18 wave page | OK as owned gap |
| 08:30 | "Everything is a force sale today" from one territory (radius imported at 100 m into a market where 80 % of outlets sit within 55 m of another) | D: force-sale % by territory; what-if; radius per geo class | lens-config P2 what-if, `cfg.sla.force_sale_pct_alert`, G-field-11 | **G-field-11**: the what-if has no fixes to run on until the pilot has collected them; the desk can only raise the radius blind |
| 09:00 | "My balance for shop X is wrong" (retailer dispute at cutover) | D: opening balance provenance; dispute capture; finance adjustment with approver | M-42, F-ADM-036, G-field-09(d), G-field-22 | PARTIAL → G-field-09/22 |
| 10:00 | "I sold under my colleague's login for an hour" | action: re-attribute a route-day's rows | — | **G-field-05** (admin re-attribution event) |
| 11:00 | "Sync says 1 rejected: sku_not_in_plan" | D: Bangla reason text on the device; quarantine panel; "fix & accept" four-eyes for data-entry-class reasons | lens-sync §2.9, F-ADM-030, lens-quality §5.7 L1 | OK |
| 12:00 | "Phone is dead, memos on paper" | action: Data Entry keyed by printed `memo_no` through the same ingest | G-feat-30, lens-sync §8.3 | OK as owned gap (Phase 6 — recommend minimal Data Entry in 2e for the pilot, since a dead phone is a pilot-week certainty) |
| 15:00 | "Market closed because of a hartal in two upazilas; will our SRs be marked absent?" | action: a zone/date-range day exception with a reason | G-field-02 | MISS |
| 17:30 | "Zone cannot be final-submitted; TSO is in hospital" | action: delegated or admin final submit | G-field-07 | MISS |
| 19:00 | Day close: contacts by reason into the known-issue board; P1 = 0 | D: ticket tool with `device_id`, `route_id` | lens-quality §5.7 | OK |

---

## 9. The "things engineers forget" checklist

| Item | Verdict | Where it is covered / what is missing |
| --- | --- | --- |
| Holidays, Eid, make-up Saturdays | OK | `cfg.calendar.holidays` with `selling_day`, `dim_date.is_working_day`, D-07/D-09 (lens-config §1.10, lens-data §4.2) |
| Weekly off-day (Friday) | OK | `cfg.calendar.weekend_days`, P-03; per-wing scope allowed |
| Rain day / flood / hartal / market closed / DH out of stock / vehicle breakdown | **MISS** | **G-field-02** — no field-side day exception; the only lever is an admin/TSO web override per route per date |
| Printer out of paper / half print | PARTIAL | lens-sync §6.3 covers failures the printer reports; **G-field-20** for the undetectable half print and the "duplicate" marker |
| Phone swapped mid-day | OK | lens-sync §8.3, `bind_ordinal` memo blocks (D-sync-07), PDA-to-Support replay, Data Entry fallback |
| SR on leave with substitute | PARTIAL | G-feat-07 (SS flow), G-feat-25 (SR/AMO leave); **G-field-06** same-day cover from the AMO app; **G-field-05** `acting_for` |
| Outlet closed when visited | **MISS** | **G-field-03** visit outcome codes + skip records |
| Retailer refuses to pay | PARTIAL | credit/dues flows exist (F-SR-026/032, `cfg.credit.*`); **G-field-09(d)** dispute capture |
| Partial stock return | PARTIAL | M-11 events, DQ-21; **G-field-01** DH confirmation and damaged/short kinds (with G-feat-02/05) |
| Memo cancelled after print | **MISS** | **G-field-04** void event, slip, effects |
| Two SRs logged into one device | PARTIAL | D-sync-01 isolation, G-sync-01 binding; **G-field-05** wrong-user capture and re-attribution |
| Wrong date on phone | OK | D-sync-13 corrected time, lens-security §5.4 trusted time, FS-13, DQ-09/10/11, banner |
| Bangla input | **MISS** | **G-field-10** digits, collation, index strip, search normalisation (security §6.4 has NFC only) |
| Low storage | OK | lens-sync §8.5, `cfg.media.local_queue_max_mb`, `cfg.app.image_cache_mb` |
| App killed by OS battery saver | OK | lens-sync §8.4 `sale_draft`, §8.9 OEM guidance, WorkManager T4/T5, `cfg.sync.pending_reminder_time` |
| Cold GPS at the first outlet | PARTIAL | **G-field-13** |
| Wholesale buyer at outlet price | **MISS** | **G-field-08** |
| TSO absent at final submit | **MISS** | **G-field-07** |
| Month-end: next month's targets not yet set, route_day rows for the 1st, Astha quarter boundary | PARTIAL → **G-field-17** (minor) | M-21/M-22 hold the data; no deadline alert, no "targets pending" behaviour in the app (achievement must render "—", F-SYS-034) |
| Ramadan hours (check-out earlier, iftar) | OK | `cfg.day.checkout_earliest_time` effective-dated per scope (lens-config §1.2, §2.1) |
| Permission auto-reset by Android | OK | lens-sync §8.6 |
| SIM-less Wi-Fi-only phone | OK | lens-sync §8.8, D-sync-08 |
| Two devices same user same day | OK | lens-sync §8.13 |
| Memo number gaps (burned vs lost rows, "clear data" to hide a day) | PARTIAL → **G-field-14** (minor) | D-sync-07 says a failed save burns the number; nobody reports gaps; a per-user-day gap report is a free lost-row detector |
| Map tiles for AMO/TSO maps | PARTIAL → **G-field-18** (minor) | provider, licence, cache, budget unnamed |
| Dashboard date semantics at 08:30 / on a holiday | PARTIAL → **G-field-19** (minor) | |
| Pilot double memo at the retailer | **MISS** → **G-field-12** | |
| Opening balance provenance at the shop | PARTIAL → **G-field-22** (minor) | |
| On-the-spot verification during a joint call | PARTIAL → **G-field-23** (minor) | |
| Visit kinds per KPI (AMO/SS/acting-for sales) | PARTIAL → **G-field-16** (minor) | |

---

## 10. Gap list (G-field-NN)

Severity rule (same as the other lenses): **blocker** = a sale cannot be completed or cutover-day parity breaks; **major** = a daily workflow, a money figure or a management number is wrong or missing; **minor** = confirm-only, cosmetic or a cheap improvement. None of these is a blocker for the vertical slice; the "before" column says the last sub-milestone (lens-quality §2) by which each must land.

| ID | Severity | Title | Where it should land (doc / table / feature / key) | What to add | Before |
| --- | --- | --- | --- | --- | --- |
| G-field-01 | major | **Distribution house is not an actor**: stock issue, stock return and cash hand-over are self-declared by the SR; no counter-confirmation, no DH view, no requested-vs-issued | docs/01 geography (`house`), docs/03 roles, M-11 `stock_movement`, G-feat-02/03/05, Q17; F-SR-014/015/036, F-AMO-021 | New role `dh` (scope node type `house`) **or** AMO-as-proxy (business choice, Q41); `stock_movement` gains `requested_qty`, `counted_qty`, `variance_reason_code`, `confirmed_by`, `confirmation_client_uuid`, kinds `damaged`, `qc_return`, `short`; new event `cash_handover(client_uuid, user_id, business_date, declared_mtk, counted_mtk, variance_mtk, counted_by, fix_id)`; `dw.agg_daily_user_sku` + `agg_daily_route.collected_mtk` feed a "DH daily settlement" page/print; stock memo and summary print carry a confirmation line | 2b (events), 3a (confirmer UI), 4b (settlement page) |
| G-field-02 | major | **No field-side day exception**: rain/flood, hartal, market closed (haat day), DH out of stock, breakdown, sick-without-leave; Login %, Submit %, Daily Tracking, till-date proration and FS-12 all misread a legitimately dead day | docs/04 day state, docs/09 Daily Tracking, docs/10 Login %, M-32 `route_day`, lens-config §1.10 `cfg.calendar.route_visit_day_exceptions`, G-feat-09/10/18, D-09 | Outbox event `day_exception(client_uuid, route_id[], business_date_from, business_date_to, reason_code, note, declared_by, approved_by)` from SR (own route) and AMO (zone, date range); TSO approves in app; `route_day.planned=false, exception_reason` and `route_day.exception_pending`; `cfg.day.exception_reasons` (list, kind T), `cfg.day.exception_requires_approval` (true), `cfg.day.exception_max_days` (7); dashboards show "exception (rain)" bucket distinct from "not logged in"; `dw.agg_daily_route.exception_reason` | 2e |
| G-field-03 | major | **No visit outcome / no-sale reason** and no "skip" record: shut shop, owner absent, refused, stock sufficient, competitor, not reached — all are "zero sale" or invisible; FS-14 flags honest zero sales | docs/06 step 4e, F-SR-029 zero sale, F-SR-010 Non-visit/No-sale, M-05 `visit`, D-05/D-06, G-feat-38, FS-14 | `visit.outcome_code` from `cfg.visit.outcome_codes` (sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned); the zero-sale confirm dialog asks the reason (one tap); a **skip** record (`visit.kind=skip`, no fix, no geo gate) for outlets never opened; `agg_daily_outlet.outcome_code`; nightly rule "3 consecutive `closed` → task to AMO (`outlet_check`)"; KPI: Non-visit = planned − (visited + skipped-with-reason) | 2a |
| G-field-04 | major | **Memo void after print has no capture path**: `memo.status='void'` exists with no event, reason, retailer slip, stock/dues effect or DQ rule; "edit to zero lines" undefined | docs/06 sale edit, F-SR-033, M-06 `memo.status`, `cfg.memo.edit_after_print_policy`, `cfg.memo.edit_reasons`, D-sync-06, FS-02, `fact_due_ledger` | Event `memo_void(client_uuid, memo_client_uuid, reason_code, note, fix_id, retailer_ack bool)`; server sets `status='void'`, `voided_at`, writes `fact_due_ledger(kind=void_reversal)` for credit memos, stock recompute excludes void memos; same guards as edit (geofence, pre-QC, pre-submit; DQ-16 extended); `cfg.memo.void_reasons` (list), `cfg.memo.print_void_slip` (true) → slip "বাতিল — মেমো <memo_no>" with the same raster renderer; FS-02 counts voids and void-after-collection; memo count D-06 already excludes non-active | 2b |
| G-field-05 | major | **Wrong-user capture on a shared phone and no `acting_for`**: SR B sells under A's session; AMO sells for a dead-phone SR; SS covers — all attributed to the wrong person for ever (P2 immutability) | lens-sync §8.1/8.13, M-05 `visit.user_id`, M-06 `memo.user_id`, D-sync-07 memo series, F-ADM-024 Data Entry, F-AMO-036, lens-security §3.2 step 5 (user_id stamped from the token) | `cfg.auth.confirm_identity_on_first_capture` (true on devices with > 1 bound user): "আপনি কি <name> (<code>)?" at the first capture of a business date; `visit.acting_for_user_id` / `memo.acting_for_user_id` chosen from the route's assignees when the capturing user is not the route's primary (SS, AMO); admin action `attribution_event(client_uuid, route_id, business_date, from_user_id, to_user_id, reason, approved_by)` applied by the worker at aggregation (`dw` uses the effective user; `app` rows untouched, audited); memo numbers keep the capturing user's series (the number is identity, not attribution) | 2e (confirm + acting_for), 3a (re-attribution) |
| G-field-06 | major | **Same-day cover cannot start**: no AMO/TSO-app action creates a `route_assignment(kind=cover)`; the substitute's bundle lacks the route and DQ-06 rejects his rows as out of scope | G-feat-07, F-ADM-003 (web, Phase 6), M-14, docs/07 Team, lens-security §3.1 reach, G-cfg-12 scope_version | F-AMO-0xx "Assign cover for today" (route, user in zone, dates ≤ `cfg.route.cover_max_days` 7) → `POST /routes/:id/cover` (online; queued as outbox if offline, applied on sync) → `scope_version` bump → substitute's next delta carries the route; TSO approval optional (`cfg.route.cover_requires_tso_approval` false); audit row; the AMO's "Not Logged In" list offers the action inline | 3a (needed for pilot weeks; a web-only interim in 2e) |
| G-field-07 | major | **Final submit has no delegation and no auto-close**: TSO on leave or offline at 19:00 leaves the zone open indefinitely; T-7-87 "100 % by 21:00" then fails for a non-defect | docs/08 Final Submit, F-TSO-010, M-33 `leave_application`, M-34 `final_submit`, `cfg.day.reopen_roles`, D-sync-08 (final submit kept online-only) | `cfg.day.final_submit_delegate_roles` ([dmo]) + `leave_application.acting_user_id` (an approved TSO leave names the acting TSO whose scope temporarily includes the territory via `user_scope` with `valid_to`); `cfg.day.auto_final_submit_time` (23:30, 0 = off) marks `final_submit.kind='auto'` with the snapshot; `final_submit` as an outbox event from the TSO app (same mechanism as `day_submit`, PK still enforces once-only); Final Submit Log shows `manual | delegated | auto` | 3b |
| G-field-08 | major | **Price-type resolution per outlet is undefined**: C&C/wholesale buyers exist (P-16); `sku_price` has five types; the bundle carries one list; the SR cannot choose; DQ-13 would flag every C&C memo | docs/03 `sku_price`, M-07 `memo_line.price_type DEFAULT 'outlet'`, M-16 `outlet.outlet_kind`, bundle §4.1 prices, F-SR-023, DQ-13, Q17 | `outlet.price_type` (default derived: `outlet_kind='wholesale'` → `cc`; else `outlet`), admin-editable with audit, in `dim_outlet` SCD2; bundle carries `outlet` **and** `cc` lists (both already counted in the 12.6 KB estimate) plus the per-outlet type; sale screen shows the resolved list and allows no manual switch (`cfg.sale.allow_price_type_override` false); DQ-13 checks against the outlet's type; Discount/STD reports split by price type; pilot must include ≥ 1 C&C outlet (lens-quality §5.1 already does) | 2a |
| G-field-09 | major | **Dues lifecycle holes at the retailer**: (a) no due-collection receipt, (b) stale "previous due" on the memo with no marker, (c) closing an outlet with open dues/points has no rule, (d) no field capture of a balance dispute | F-SR-032, F-SR-038, F-AMO-023, lens-sync §6.2 template, M-15, M-12 `due_collection`, F-ADM-036, G-feat-45, `fact_due_ledger` | (a) `cfg.print.due_receipt` template: outlet, memo(s) allocated, amount, remaining balance, collector, time, `due_collection.client_uuid` short code; (b) memo line "আগের বাকি <amount> (<bundle date> পর্যন্ত)" when the balance source is older than today (`cfg.memo.due_balance_staleness_marker` true); (c) `cfg.outlet.close_with_dues_policy` (block | warn | write_off_queue) and `outlet.status='closed_with_dues'` until finance clears; verification screen shows balance and points; (d) event `due_dispute(client_uuid, outlet_id, claimed_paid_mtk, claimed_date, claimed_collector_user_id, note, fix_id)` → task to AMO + finance queue; disputed outlets badge on the memo list | 2b |
| G-field-10 | major | **Bangla as data**: Bengali digits in phone/qty/amount fields never normalised (`phone_hash` dedupe fails), no collation decision (SQLite code-point vs Postgres ICU), A–Z strip over Bangla names, search not nukta/diacritic-insensitive | F-SR-016 outlet list, F-SR-037 new shop, lens-security §6.4 (NFC only), lens-sync §1.5 `ref_outlet`, M-16 `phone_hash`, FS-03 | `/packages/text`: `normaliseDigits()` (০–৯ → 0–9) applied to every numeric input on device and at ingest (DQ-35 `digits_normalised` flag when it changed), `normaliseName()` (NFC, strip ZWJ/ZWNJ except in conjunct contexts, collapse spaces); Postgres `COLLATE "bn-x-icu"` on `outlet.name` indexes; device: precomputed `name_sort_key` (ICU key generated server-side, shipped in the bundle, ~8 B/outlet) so SQLite `ORDER BY name_sort_key` matches the server; bilingual index strip (অ আ ই … / A–Z) driven by the first grapheme cluster, `cfg.app.outlet_list_index_script` (bn_first); goldens in T-1-41/T-2-43 include a mixed-script list | 1a (normaliser + sort key), 2c (strip) |
| G-field-11 | major | **Radius cannot be calibrated before the first wave**: the dump has no GPS fixes, the what-if tool needs stored fixes, and `cfg.geo.radius_m` has no geo-class dimension (docs/05's own urban-vs-rural rationale) | lens-config §1.1 and §2.1 `cfg.scope_level`, docs/05, docs/22 P-09/P-10, docs/11 dump request, T-7-84 "radius per territory reviewed" | Add scope level `geo_class` (precedence between territory and zone) for `cfg.geo.radius_m`, `max_accuracy_m`, `fix_timeout_s`; pilot plan item: ≥ 2 weeks of fixes per geo class before wave radii are set, with a calibration report (distance histogram per class × territory from `fact_visit.device_distance_m`); dump request adds raw fixes if Apsis has them (Q18 already asks); readiness item T-7-84 cites the report | 2d (scope level), 7b (calibration) |
| G-field-12 | major | **Parallel-run pilot prints two memos**: nothing defines print behaviour in `parallel_run_mode`, how the retailer tells them apart, or how dues collected twice are kept apart | lens-config §4.6 `cfg.flag.parallel_run_mode`, lens-sync §6.2 template, lens-quality T-2-48/T-7-82, docs/11 pilot | `cfg.flag.parallel_run_mode` values `off | capture_only | print_test_watermark`; watermark "পরীক্ষামূলক — এটি রসিদ নয়" and no "previous due" line in test mode; new-app `due_collection` rows `parallel=true` excluded from `agg_outlet_balance`; the SR's briefing card says which memo the retailer keeps; pilot sheet #15 records retailer confusion | 2e |
| G-field-13 | minor | Cold GPS at the first outlet becomes a force sale and an FS-09 count | lens-sync §5.3, F-SR-017/019, `cfg.geo.fix_timeout_s` | Start acquisition on entering the outlet list; reuse a fix ≤ `cfg.geo.fix_reuse_max_age_s` (60) and ≤ 30 m displacement (step counter/`speed_mps`); budget unchanged (still ≤ 80 fixes/day) | 2d |
| G-field-14 | minor | Memo sequence gaps are not reported: a free detector of lost rows and "clear data" days | lens-sync §7 (burned numbers), lens-security §7.3, lens-quality §4.6 reconciliation | Nightly `memo_seq_gap(user_id, business_date, missing_seq[])` from `memo.memo_no`; `sale_abort` activity rows explain burned numbers; unexplained gaps → sync-health row + FS-20 `sequence_gap` | 2e |
| G-field-15 | minor | Pre-login SR cannot be diagnosed; no device-health line on Home | lens-quality §5.7, `GET /config/public`, F-SYS-020 | Support code on every blocking screen (base32 of error code · app version · config_version · device_uuid last 4 · bind state · free MB · battery %) read aloud in Bangla; desk decoder page; Home shows battery/storage/pending/last-sync line with thresholds (`cfg.app.health_warn_battery_pct` 40) | 2e |
| G-field-16 | minor | Visit-kind inclusion per KPI undefined (AMO/SS/acting-for sales): F-AMO-036 vs D-09 disagree | lens-data §4.5 D-05/D-09, F-AMO-036, `agg_daily_route`, `agg_daily_user_*` | Rule: route-level KPIs (STD, memo, CPR, geo %) include every `active` memo/visit on the route regardless of seller; user-level KPIs (SR Efficiency, GIGO, risk score) use `coalesce(acting_for_user_id, user_id)`; AMO control-call KPIs use `kind`; written into D-05/D-09 and `fact_visit.effective_user_key` | 3a |
| G-field-17 | minor | Month-end readiness: targets for M+1, Astha quarter, program periods, `route_day` rows for the 1st; no alert, no app behaviour when targets are missing | M-21, M-22, lens-config §1.8, `cfg.sys.schedule_horizon_days`, F-SYS-034 | `cfg.sla.targets_missing_alert_day` (26): alert listing routes/zones without M+1 targets; bundle carries M+1 targets within the horizon; app renders achievement "—" with "লক্ষ্য নির্ধারিত হয়নি"; nightly job creates `route_day` rows for D+1 | 5c |
| G-field-18 | minor | Map tiles for Team Location, Update Base, Periphery: provider, licence, cache, data budget unnamed | docs/07 Team Location/Update Base, docs/08 My Periphery, lens-sync §5.6 | Decide Azure Maps (keyed via API-issued short-lived token) vs self-hosted OSM tiles behind Front Door (Q42); `cfg.map.tile_provider`, `cfg.map.cache_mb` (20), Wi-Fi-only prefetch of the zone's tiles at z14–16; no map SDK in the SR role | 3a |
| G-field-19 | minor | Dashboard date semantics: default date, "same time yesterday" comparator, non-working-day banner | docs/09 Dashboard, F-WEB-001, `dim_date`, lens-scale §4.14 | Sales tiles default to the last business date with final submits (or yesterday), activity tiles to today; every live tile shows the same-time-yesterday value; holiday banner from `dim_date.is_working_day`; `cfg.ops.dashboard_default_date_rule` | 4a |
| G-field-20 | minor | Half-printed memo on a clone printer is followed by a "duplicate"-marked reprint | lens-sync §6.3, `cfg.memo.reprint_watermark`, `cfg.memo.reprint_max` | One-tap "ছাপা ঠিক আছে?" after each print (`cfg.print.confirm_after_print` true); "না" marks the job `failed_user`, the next print carries no marker and does not count toward `reprint_max`; `print_job.user_confirmed` | 2a |
| G-field-21 | minor | Outlet-closure and stock-return events need `damaged`/`short` kinds and the DH count (sub-items of G-field-01 and G-feat-02/05; listed so the RTM can close them separately) | M-11, G-feat-02, G-feat-05 | see G-field-01 | 2b |
| G-field-22 | minor | Opening-balance provenance is not visible at the shop on cutover week | M-42 `opening_balance`, lens-sync §4.1 `open_memos`, docs/11 "exactly right on day one" | Bundle carries per outlet `opening_due_mtk`, `opening_points`, `as_of_date`, Apsis memo numbers for open memos; outlet card shows "পুরনো সিস্টেম অনুযায়ী <date>: X; এরপর +Y −Z"; the AMO's verification and the desk's dispute script cite it | 7a |
| G-field-23 | minor | On-the-spot verification during a joint call is impossible (request not yet on the server) | F-AMO-022, docs/07 Outlet verification, DQ-03 parking | SR screen shows a QR of `outlet_change_request.client_uuid`; AMO scans → `verification_event(request_client_uuid, …)` parked until the request lands (DQ-03 already parks children) | 3a |

---

## 11. Config keys, decisions and questions raised by the walk

### 11.1 New or constrained config keys (all registered per lens-config §2; defaults are ASSUMPTIONS unless marked spec)

| Key | Default | Bounds / type | Scope | Risk | Gap |
| --- | --- | --- | --- | --- | --- |
| `cfg.day.exception_reasons` | rain_flood, hartal, market_closed, dh_no_stock, breakdown, sick_no_leave, other | list (kind T) | G | C1 | G-field-02 |
| `cfg.day.exception_requires_approval` | true | bool | G, W | C2 | G-field-02 |
| `cfg.day.exception_max_days` | 7 | 1–31 | G | C1 | G-field-02 |
| `cfg.visit.outcome_codes` | sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned | list (kind T) | G | C1 | G-field-03 |
| `cfg.visit.closed_streak_task` | 3 | 0–10 (0 = off) | G, W | C1 | G-field-03 |
| `cfg.memo.void_reasons` | retailer_cancelled, wrong_outlet, duplicate_entry, other | list (kind T) | G | C1 | G-field-04 |
| `cfg.memo.print_void_slip` | true | bool | G | C1 | G-field-04 |
| `cfg.auth.confirm_identity_on_first_capture` | true (devices with > 1 bound user) | bool | G, ROLE | C2 | G-field-05 |
| `cfg.route.cover_max_days` | 7 | 1–31 | G | C1 | G-field-06 |
| `cfg.route.cover_requires_tso_approval` | false | bool | G, W | C2 | G-field-06 |
| `cfg.day.final_submit_delegate_roles` | [dmo] | list<role> | G, W | C3 | G-field-07 |
| `cfg.day.auto_final_submit_time` | 23:30 (0 = off) | time | G, W | C3 | G-field-07 |
| `cfg.sale.allow_price_type_override` | false | bool | G | C3 | G-field-08 |
| `cfg.print.due_receipt` | template v1 | template (kind T) | G, W | C1 | G-field-09 |
| `cfg.memo.due_balance_staleness_marker` | true | bool | G | C0 | G-field-09 |
| `cfg.outlet.close_with_dues_policy` | warn | enum(block, warn, write_off_queue) | G, W | C2 | G-field-09 |
| `cfg.app.outlet_list_index_script` | bn_first | enum(bn_first, latin_first, bn_only) | G, ROLE, W | C0 | G-field-10 |
| `cfg.geo.radius_m` scope levels | + `geo_class` | — | — | — | G-field-11 |
| `cfg.flag.parallel_run_mode` | off | enum(off, capture_only, print_test_watermark) | WAVE, T, Z | C3 | G-field-12 |
| `cfg.geo.fix_reuse_max_age_s` | 60 | 0–300 | G | C1 | G-field-13 |
| `cfg.app.health_warn_battery_pct` | 40 | 10–80 | G | C0 | G-field-15 |
| `cfg.sla.targets_missing_alert_day` | 26 | 20–31 | G | C1 | G-field-17 |
| `cfg.map.tile_provider` / `cfg.map.cache_mb` | azure_maps / 20 | enum / 5–100 | G | C1 | G-field-18 |
| `cfg.ops.dashboard_default_date_rule` | last_final_submitted | enum | G, ROLE | C0 | G-field-19 |
| `cfg.print.confirm_after_print` | true | bool | G | C0 | G-field-20 |

### 11.2 Decisions proposed (numbers assigned on merge)

| ID | Decision | Rationale |
| --- | --- | --- |
| D-field-01 | Every non-selling situation in the field is an **event with a reason code** (`day_exception`, `visit.outcome_code`, `memo_void`, `due_dispute`, `cash_handover`), captured offline and approved/reviewed by the supervisor; nothing is ever inferred from silence | R1 (any dashboard needs the reason), R2 (today's silence is not parity, it is a blind spot), fraud signals must not fire on honest exceptions |
| D-field-02 | `acting_for_user_id` on visit and memo; re-attribution is an event applied at aggregation, never an update of the captured row | P2 immutability + the shared-phone reality |
| D-field-03 | The DH is confirmed by a `dh` role or an AMO proxy (business choice Q41); until then the stock/cash events carry `confirmed_by` nullable and the settlement page marks "unconfirmed" | G-field-01 without blocking Phase 2 |
| D-field-04 | Price type is an outlet attribute resolved server-side and delivered in the bundle; the SR never chooses | money correctness, R4 of CLAUDE.md (no client-chosen scope-like data) |
| D-field-05 | Text normalisation (digits, NFC, sort key) lives in `/packages` and runs identically on device and server; ordering is by a server-generated sort key | one list on the phone and on the web |
| D-field-06 | Final submit may be delegated and auto-closed; both are visible kinds in the Final Submit Log | a zone's day must always close |

### 11.3 Questions for docs/13 (continuing after lens-quality's Q40)

| Q | Question | Blocks |
| --- | --- | --- |
| Q41 | Does the distribution house get a login (`dh` role, scope = house) or does the AMO confirm issue/return/cash on its behalf? Does the DH keeper sign anything today beyond the paper stock memo? | G-field-01, 2b events |
| Q42 | Map tile provider and licence for AMO/TSO maps (Azure Maps vs self-hosted OSM); is a map needed in the SR role at all? | G-field-18 |
| Q43 | When a retailer cancels after print today, what does the SR do in Apsis (edit to zero? nothing?) and does the retailer receive any cancellation paper? | G-field-04 (D-sync-06 is an ASSUMPTION) |
| Q44 | Who covers a route the same day when the SR is sick today, and who records it? May the AMO assign cover without the TSO? | G-field-06 |
| Q45 | Who final-submits a zone when the TSO is absent, and is an automatic close at 23:30 acceptable to sales ops and finance? | G-field-07 |
| Q46 | Which outlets are priced at `cc`/`distributor` today and how does the Apsis app choose (per outlet flag, per channel, SR choice)? | G-field-08 |
| Q47 | Does the Apsis app print a receipt for a due collection? What does the retailer receive today when paying an old due? | G-field-09(a) |
| Q48 | Rule for closing an outlet with open dues or points today (collect first, write-off, carry)? | G-field-09(c) |
| Q49 | During the parallel pilot, does the new app print at all, and what does the retailer keep? | G-field-12 |
| Q50 | Does the current Apsis app record a "shop closed / owner absent" outcome, or is a zero sale the only non-productive record? (Decides whether G-field-03 is parity or improvement.) | G-field-03 |

---

## 12. Phase mapping (where the G-field work lands; docs/12 numbering, lens-quality sub-milestones)

| Phase / sub | Deliverables from this walk | Gates to add (90-range, this critic) |
| --- | --- | --- |
| 1a | `/packages/text` normaliser + server sort key in the bundle (G-field-10 core) | T-1-90 mixed-script outlet list sorts identically on device and server; Bengali-digit phone matches `phone_hash` |
| 2a | `outlet.price_type` + dual price lists (G-field-08); `visit.outcome_code` + skip record (G-field-03); print confirmation (G-field-20) | T-2-90 C&C memo prices from the `cc` list and passes DQ-13; T-2-91 zero-sale dialog records an outcome, Non-visit/No-sale strip matches the seed |
| 2b | `memo_void` event + slip + ledger reversal (G-field-04); due receipt, staleness marker, close-with-dues policy, `due_dispute` (G-field-09); stock/cash events with `confirmed_by` nullable (G-field-01 events, G-field-21) | T-2-92 void after print: stock back, due reversed, slip printed, memo count unchanged, FS-02 fires on void-after-collection; T-2-93 due receipt prints and allocation matches ledger; T-2-94 cash hand-over variance appears on the settlement view |
| 2c | Bilingual index strip (G-field-10 UI) | goldens in T-2-43 |
| 2d | `geo_class` scope level (G-field-11); fix reuse (G-field-13) | T-2-95 radius resolves by geo class with correct provenance; T-2-96 first outlet after 60 min idle gets a fix within 10 s on the primary device |
| 2e | `day_exception` event + approval (G-field-02); identity confirmation + `acting_for` (G-field-05 part 1); parallel-run print mode (G-field-12); support code + health line (G-field-15); memo gap report (G-field-14); interim web cover assignment (G-field-06) | T-2-97 rain-day exception removes the route from Login %/Submit % denominators and buckets it "exception" on Daily Tracking; T-2-98 shared-phone wrong-user drill: confirmation shown, `acting_for` stored, KPIs attribute to the acting user; T-2-99 parallel mode prints the watermark and keeps dues out of balances |
| 3a | AMO "assign cover for today" (G-field-06); re-attribution event + admin action (G-field-05 part 2); AMO as DH confirmer (G-field-01 UI); joint-call QR verification (G-field-23); visit-kind KPI rule (G-field-16); map tiles decision (G-field-18) | T-3-90 same-day cover: substitute's delta contains the route within one sync cycle and his rows pass DQ-06/07; T-3-91 re-attribution moves a route-day in `dw` without touching `app` rows, audited |
| 3b | Final-submit delegation, auto-close, outbox final submit (G-field-07) | T-3-92 TSO on approved leave → acting TSO submits; auto-close at 23:30 writes `kind='auto'`; late rows still accepted and flagged |
| 4a / 4b | Dashboard date rule and comparators (G-field-19); DH settlement page (G-field-01 reader) | T-4-90 Friday shows the non-working banner, Tuesday 08:30 shows yesterday's sales and today's login side by side |
| 5c | Month-end readiness alert and "targets pending" behaviour (G-field-17) | T-5-90 missing M+1 targets alert fires on the 26th; app renders "—" |
| 7a / 7b | Opening-balance provenance in the bundle (G-field-22); radius calibration report from pilot fixes (G-field-11) | T-7-90 30 outlets' old-system balance visible on the device equals the import reconciliation; T-7-91 calibration report per geo class exists before wave radii are set (T-7-84 cites it) |

---

## 13. Cross-lens inconsistencies seen on the walk (for the merged plan; not field gaps)

| # | Topic | Lens A | Lens B | Suggested resolution |
| --- | --- | --- | --- | --- |
| I1 | Trickle debounce | lens-scale `cfg.sync.trickle_debounce_s` = 10 | lens-sync = 5 with family hold (G-sync-14) | 5 s + family hold; load unchanged (one batch per visit) |
| I2 | Bundle pre-generation time | lens-scale D-scale-3 04:00 Dhaka for the current day | lens-sync §4.5 22:00 for the next day (pre-fetch) | 22:00 for D+1 plus a 04:00 refresh of snapshots invalidated overnight; the walk shows the Wi-Fi-only SR needs the evening copy |
| I3 | `cfg.geo.mock_policy` values | lens-config: `flag | flag_and_warn | block_sale`, default `flag_and_warn` | lens-security: `flag | block_geo_valid | block_sale`, default `block_geo_valid` | one enum: `flag_only | warn_sr | block_sale`; `server_geo_valid=false` for a mocked fix is unconditional (DQ-23), so "block_geo_valid" is not a policy but the rule |
| I4 | Offline verifier | lens-sync PBKDF2-HMAC-SHA256 100k | lens-security Argon2id m=19 MiB t=2 (G-sec-07) | Argon2id; lens-sync §3.3 to be updated |
| I5 | Memo number format | lens-config `cfg.memo.number_format` = `{device_prefix}-{yyMMdd}-{seq:4}` | lens-sync D-sync-07 `<username>-<yyMMdd>-<seq3>` with bind-ordinal blocks of 500 | D-sync-07 (username is the prefix; 3 digits suffice with blocks); the config key keeps the template but its default must match |
| I6 | Final submit transport | lens-sync D-sync-08 keeps `POST /day/final-submit` online-only | this walk G-field-07 | outbox event; PK enforces once-only either way |
| I7 | `agg.poll_interval_s` | lens-data 20 s (daytime) | lens-config 5 s | 5 s during 06:00–23:00, 60 s otherwise; cheap either way |
