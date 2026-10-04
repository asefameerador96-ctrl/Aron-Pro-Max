# Critic: the data analyst who wants any dashboard, anytime (R1)

Date: 2026-10-04. Role: adversarial reader of `lens-data.md` §4 (the `dw` star schema), with `lens-features`, `lens-sync`, `lens-scale`, `lens-config`, `lens-security`, `lens-quality`, docs/01–13, docs/22, `db/schema.sql` and `seed-findings.md` read in full. Method: 25 questions management will ask in year one; for each, the exact `dw` tables that must answer it, and whether the answer is **correct** under the ten conditions the sponsor's R1 implies: late-arriving data, memo edits/supersedes, outlets moving routes, SR reassignment/cover, target revisions, mid-month price changes, config (radius) changes over time, business date/time zone, deleted/closed outlets, PII.

Verdicts: **CORRECT** = answerable from `dw` only, right under all ten conditions. **CAVEAT** = answerable, one condition needs a stated rule or a small column. **GAP** = cannot be answered correctly from the design as written; a `G-analyst-NN` names the fix. Gate IDs proposed here use the free **90–99** range per phase.

---

## 0. Verdict in one page

| # | Finding | Questions hit | Gap |
| --- | --- | --- | --- |
| A1 | **There is no memo-grain fact.** `dw` has `fact_visit` and `fact_memo_line` but nothing at the memo level: no `printed_at`, `print_count`, `supersedes`, `edit_reason`, `entry_source`, `outstanding_before`. Every memo-shaped question (edits per SR, time to print, reprints, dispute of a printed total) has to be reconstructed from lines and visits, and several cannot be. | Q7, Q8, Q9, Q22 | G-analyst-01 (blocker) |
| A2 | **Aggregates are current-state tables.** `agg_month_*` is overwritten when a target is revised or a late batch lands; `computed_at` records *that* it changed, not *from what*. "What did the board see on the 12th" and "restate after revision" are unanswerable from `dw`; the analyst would have to read `app.target_version` and re-sum `app.memo_line`, which is exactly what R1 forbids. | Q6, Q9, Q24 | G-analyst-02 (blocker) |
| A3 | **SR-level KPIs have no home.** `agg_daily_route.acting_user_id` is single-valued, so a route worked by the regular SR in the morning and a substitute in the afternoon attributes everything to one person; `agg_daily_route.successful_calls` does not say whether AMO control-call sales on the route are included; there is no `agg_daily_user`. | Q2, Q19 | G-analyst-03 (major) |
| A4 | **Supervisors have no history.** `user_scope` is current-only; an AMO moved between zones makes "mock-GPS sales last month by AMO" wrong for every month before the move. | Q5 | G-analyst-04 (major) |
| A5 | **Dues cannot be aged.** `due_collection.against_memo_id` is nullable (allocation rule is open, G-feat-45), `agg_outlet_balance` has one `oldest_open_memo_date`, `agg_outlet_balance_daily` stores only a total, and migrated opening dues have no age anchor. | Q4, Q12 | G-analyst-05 (major) |
| A6 | **Prices, DQ flags, offer scope and migration provenance are in `app` but not in `dw`.** Each is a join the analyst is told not to make. | Q1, Q11, Q13, Q21, Q24 | G-analyst-06..09 (major) |
| A7 | The design is **right on the hard things**: capture-time context (`route_id`, `geo_key`, `radius_m_used`, `config_version`, `price_list_date`), SCD2 dimensions keyed on facts, recompute-by-dirty-key so late data and supersedes converge, balances as-of-date, PII split. 9 of 25 questions are CORRECT or CAVEAT with no schema change; 16 need the additions below, most of them one table or a few columns. | Q3, Q14, Q17, Q18, Q20, Q23 | — |

Totals: 25 questions → 6 CORRECT, 3 CAVEAT, 16 GAP. 16 gaps: 2 blocker, 8 major, 6 minor. All fixes are additive `dw` tables/columns plus three `app` columns and one `app` table; none changes the ingest contract.

### 0.1 Coverage matrix (✓ correct, ~ caveat, ✗ gap, – not applicable)

| Q | Question (short) | Late data | Edits | Outlet moves | SR reassign | Target rev | Price change | Config change | Biz date/TZ | Closed outlets | PII | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Outlets that stopped buying brand X after price change | ✓ | ✓ | ✓ | – | – | ✗ | – | ✓ | ~ | – | GAP |
| 2 | SR strike-rate trend by weekday | ✓ | ✓ | ✓ | ✗ | – | – | – | ✓ | ✓ | – | GAP |
| 3 | Geo-valid % by territory before/after radius change | ✓ | – | ✓ | – | – | – | ✓ | ✓ | – | – | CORRECT |
| 4 | Dues ageing by outlet channel | ✓ | ✓ | ✓ | – | – | – | – | ✓ | ✓ | ✓ | GAP |
| 5 | Mock-GPS sales last month by AMO | ✓ | ✓ | ✓ | ✗ | – | – | ✓ | ✓ | – | – | GAP |
| 6 | Target vs achievement restated after revision | ✗ | ✓ | ✓ | – | ✗ | – | – | ✓ | – | – | GAP |
| 7 | Memo edits per SR (count, reason, reduction) | ✓ | ✗ | – | ✓ | – | – | – | ✓ | – | – | GAP |
| 8 | Time from outlet open to memo print | ✓ | ~ | – | ✓ | – | – | – | ✗ | – | – | GAP |
| 9 | Late-synced sales by days of delay | ✓ | ✓ | – | – | – | – | – | ✓ | – | – | GAP (app_version, restatement) |
| 10 | Dormant outlets (N planned days not visited) | ✓ | – | ✓ | – | – | – | – | ✗ | ✓ | – | GAP |
| 11 | Price-mismatch rate per SR | ✓ | ✓ | – | ✓ | – | ✗ | – | ✓ | – | – | GAP |
| 12 | Credit collected within 30 days, by channel | ✓ | ✓ | ✓ | – | – | – | – | ✓ | ✓ | – | GAP (with Q4) |
| 13 | Promotion effectiveness, zones with vs without offer | ✓ | ✓ | ✓ | – | – | ~ | – | ✓ | – | – | GAP |
| 14 | Diamond League points liability by tier at month end | ✓ | ✓ | ✓ | – | – | – | – | ✓ | ✓ | – | CORRECT |
| 15 | Login % on an outage day, offline starts counted? | ✓ | – | – | ✓ | – | – | – | ✓ | – | – | CAVEAT |
| 16 | New outlets per month; duplicates merged/closed in 90 d | ✓ | – | ✓ | – | – | – | – | ✓ | ✗ | ✓ | GAP |
| 17 | Stock variance; chronic leakers | ✓ | ✓ | – | ✓ | – | – | – | ✓ | – | – | CORRECT |
| 18 | MTD attribution after an outlet moved routes | ✓ | ✓ | ✓ | ~ | ✓ | – | – | ✓ | – | – | CORRECT |
| 19 | Call duration and calls/hour excl. abandoned | ✓ | – | – | ✓ | – | – | – | ✓ | – | – | GAP |
| 20 | Sales across midnight; hourly chart basis | ✓ | – | – | – | – | – | – | ~ | – | – | CAVEAT |
| 21 | SKUs sold outside the zone sales plan | ✓ | ✓ | ✓ | – | – | – | – | ✓ | – | – | GAP |
| 22 | Printed total due vs ledger now (dispute) | ✓ | ✓ | – | – | – | – | – | ✓ | – | ~ | GAP |
| 23 | Visits validated against a later-reverted radius | ✓ | – | – | – | – | – | ~ | ✓ | – | – | CORRECT |
| 24 | 8-month YoY across the cutover (migrated history) | ✓ | – | ✓ | – | – | – | – | ✓ | ✓ | – | GAP |
| 25 | Astha achievement by tier, tier changed mid-quarter | ✓ | ✓ | ✓ | – | ✓ | – | – | ✓ | ✓ | – | GAP |

---
## 1. The 25 questions

Format per question: the question as a manager would phrase it; the `dw` objects that must answer it; the correctness check against the ten conditions (only the conditions that bite are listed); verdict; fix.

### Q1. "Which outlets stopped buying brand X after the price change on <date>?"

**Needs:** `agg_daily_outlet_brand` (date × outlet × brand: `qty_base`, `memo_count`) for the windows before/after; `agg_daily_outlet.visited` to separate "visited, did not buy" from "not visited"; `dim_outlet` (SCD2, `status`, `channel`) to exclude outlets closed in the after-window; **the date of the price change for brand X** — which has no home in `dw`.

| Condition | Check |
| --- | --- |
| Price change | `app.sku_price` is effective-dated, but `dw.dim_product` is SCD2 over hierarchy/units/`sales_enable` only; there is no price dimension and no price-change fact. `fact_memo_line.unit_price_mtk` is the price *paid*, not the list; the analyst would have to detect the change from the data (wrong when a promo masks it) or read `app.sku_price` (forbidden by R1). A "brand" price change is several SKU rows with possibly different `valid_from`; the question needs the SKU→brand roll-up of change dates. |
| Closed outlets | `dim_outlet.status` as-of the after-window date must be used (not `is_current`), otherwise outlets closed for unrelated reasons inflate "stopped buying". Supported by `f_outlet_key(outlet_id, date)`. |
| Edits | superseded memos are excluded by the recompute (D-06); correct. |
| Outlet moves | brand purchase is at outlet grain; moving route does not matter here. |

**Verdict: GAP** → G-analyst-06 (price history in `dw`). With `dw.dim_sku_price` (SCD2) and `dw.fact_price_change`, the query is: outlets with `SUM(qty_base) > 0` in `[D−60, D)` and `= 0` in `[D, D+60)` for SKUs of brand X, with `visited = true` on ≥ 2 planned days in the after-window, and `dim_outlet.status = 'active'` at `D+60`.

### Q2. "SR-level strike-rate trend by weekday."

**Needs:** successful calls ÷ target outlets per **user** per date, joined to `dim_date.day_of_week`. `dw` offers `agg_daily_route` (route grain; `assigned_user_id`, `acting_user_id`, `successful_calls`, `target_outlets`) and `fact_visit` (visit grain; `user_id`, `kind`, `is_successful`, `is_planned`).

| Condition | Check |
| --- | --- |
| SR reassignment / cover | A route-day has one `acting_user_id`. On the days P-13 implies (11,336 routes for 8,500 SRs; alternate-day cover), the regular SR and a substitute can both sell on one route on one day; `agg_daily_route` attributes all successful calls to one of them and the other's day vanishes. The denominator (`target_outlets`) is a route property, not a user property; two users on one route both "own" 64 target outlets. |
| AMO visits on the route | `agg_daily_route.successful_calls` does not state a `kind` filter. `fact_visit.kind` exists (`sr_call`, `amo_control_call`, `amo_joint_call`, …) but the aggregate's definition (D-05) is "a visit with ≥ 1 active memo"; an AMO control-call sale on the route inflates the SR's strike rate. lens-features F-AMO-036 raises this and leaves it as an assumption. |
| Late data | per-date recompute is correct. |
| Weekday | `dim_date.day_of_week` fine; but weekday trend must exclude non-working days (P-03) or Friday shows 0 %. |

**Verdict: GAP** → G-analyst-03. Fix: `dw.agg_daily_user` (business_date × user_id) computed from `fact_visit WHERE kind = 'sr_call'` (and separately for `amo_*`): `visits`, `successful_calls`, `zero_sale_calls`, `abandoned_calls`, `target_outlets_worked` (= planned active outlets of every route the user had an assignment or acting record on that day), `memo_count`, `net_mtk`, `geo_valid_calls`, `mock_calls`, `worked_minutes`; and an explicit `kind IN (...)` clause written into the D-05 definition for `agg_daily_route`, with AMO sales counted in `agg_daily_route.amo_successful_calls` separately.

### Q3. "Geo-validation % by territory before and after the radius change on <date>."

**Needs:** `agg_daily_route.geo_valid_calls`, `visits` rolled up through `dim_geo` (route → territory as-of date); `fact_config_change` (key `cfg.geo.radius_m`, scope, `effective_from`, `config_version`); `fact_visit.radius_m_used`, `config_version`, `server_geo_valid`.

| Condition | Check |
| --- | --- |
| Config change over time | `server_geo_valid` is computed at ingest with the radius resolved **as of `captured_at`** (lens-data DQ-24, lens-config §2.3), and the value used is stored on the visit. A visit captured at 10:00 and synced at 19:00 after an 11:00 change is validated with the 10:00 radius — correct. The before/after split can be made on `fact_visit.radius_m_used` or `config_version`, not only on the date, so a change at 11:00 does not smear the day. |
| Late data | a visit from D synced on D+3 dirties (route, D) and the territory number for D restates; the "as of" stamp shows it. Correct. |
| Outlet moves | `geo_key` on the fact is the route→territory chain valid on the visit date. Correct. |
| Denominator | "visits" must exclude AMO/TSO visits and abandoned visits or the % drifts with supervision intensity; define `geo_valid_pct = geo_valid_calls / (visits − abandoned)` for `kind='sr_call'` (ties to G-analyst-03). |

**Verdict: CORRECT.** Caveat only on the denominator definition. Note for lens-config §4.3: the what-if ("% of last 30 days' visits valid under the new value") should use `fact_visit.server_distance_m`, not `device_distance_m`; the device distance is computed from the same fix but is the untrusted copy.

### Q4. "Dues ageing by outlet channel (0–30 / 31–60 / 61–90 / 90+ days)."

**Needs:** per open credit memo: original due, collections allocated to it, memo business_date → age bucket; outlet channel as-of today; roll-up by channel. `dw` offers `fact_due_ledger` (signed events: `credit_created`, `collection`, `memo_superseded_reversal`, `write_off`, `adjustment`, `opening`; `memo_id`, `due_collection_id`), `agg_outlet_balance` (total `due_balance_mtk`, `oldest_open_memo_date`, `open_memo_count`), `agg_outlet_balance_daily` (total per changed day).

| Condition | Check |
| --- | --- |
| Allocation | `app.due_collection.against_memo_id` is nullable; docs/06 says "Memo menu → outlet → mark paid (full or partial)" and G-feat-45 leaves FIFO-vs-chosen open. If a collection is outlet-level, `fact_due_ledger.memo_id` is NULL on it and no per-memo outstanding exists; ageing collapses to one bucket per outlet. Even when `against_memo_id` is set, a partial payment against the newest memo while an older one stays open is a legitimate but ageing-distorting case; ageing needs a stated allocation (FIFO for reporting regardless of the retailer's choice, or "as recorded"). |
| Opening balances | `app.opening_balance(kind='due', as_of_date, amount_mtk)` has no memo and no original date; the import sample has no memo-level data (docs/22). Opening dues enter `fact_due_ledger(kind='opening')` with `business_date = as_of_date`; aged from cutover, they are all "0–30" on day one and "90+" after a quarter regardless of truth. Needs an `age_basis_date` from the Apsis dues report (per-memo or per-outlet oldest date) or an explicit rule. |
| Edits | a superseding memo reverses the old due (`memo_superseded_reversal`) and creates a new `credit_created` — the new memo's business_date is the edit day (edits are same-day, DQ-16), fine. |
| Closed outlets | closed outlets with open dues must stay in the ageing (P-08: 175k outlets with history are not in the active list); `dim_outlet.status` filter must **not** be applied; `agg_outlet_balance` must keep rows for closed outlets. The design does (no status filter) — state it. |
| PII | ageing by channel is PII-free; the follow-up "give the TSO the phone list of 90+ outlets" is a PII export: `dim_outlet_pii` grant + `report_export_log`. Correct. |
| Historical ageing | "ageing as of 31 Aug" needs per-memo balances as of a date; `agg_outlet_balance_daily` has only the outlet total. |

**Verdict: GAP** → G-analyst-05. Fix: `dw.fact_due_allocation` (collection → memo → amount, produced by the worker under `cfg.credit.collection_allocation = as_recorded | fifo`), `dw.agg_memo_due_open` (open memos with `outstanding_mtk`, `age_days`, `bucket`) refreshed nightly and on each dues event, `dw.agg_outlet_due_ageing_daily` (date × outlet × bucket amount) only for changed outlets; `app.opening_balance.age_basis_date`.

### Q5. "How many sales were made with a mock-GPS flag last month, by AMO?"

**Needs:** `fact_visit.is_mock`, `active_memo_count` (memos, not visits — the question says sales), `zone_id`; **which AMO was responsible for which zone on each day of last month**.

| Condition | Check |
| --- | --- |
| Mock flag | `fact_visit.is_mock` is from `geo_fix.is_mock` of the opening fix, set by the server; `agg_daily_route.mock_calls` and `agg_daily_zone.mock_calls` roll it up. Memos per mocked visit = `SUM(active_memo_count) WHERE is_mock`. Correct. `fact_memo_line` has `is_suspicious` but no `is_mock`; add it for line/value questions (minor, in G-analyst-07). |
| Supervisor assignment | AMO ↔ zone is `app.user_scope` (current only, no `valid_from/to`) and `dim_user.home_zone_id` (current). An AMO transferred on the 15th makes every "by AMO" report for the past wrong. `dim_assignment` covers only `route_assignment` (SR/SS). There is no history of who supervised what. The same hole hits "by TSO", "by DMO" and the whole Supervisory Module (G-feat-31). |
| Config change | `cfg.geo.mock_policy` changes (flag → block) do not alter `is_mock`; they alter whether memos exist. Fine. |

**Verdict: GAP** → G-analyst-04. Fix: `app.user_scope` becomes effective-dated (`valid_from`, `valid_to`, `@SCD` exclusion) with a trigger that closes/opens rows on change; `dw.dim_supervisor_assignment (user_id, role, node_type, node_id, valid_from, valid_to)`; `dw.f_supervisor(node_type, node_id, role, date)` function; `agg_daily_zone` gains `amo_user_id` and `agg_daily_route` keeps `assigned_user_id` as today.

### Q6. "Target vs achievement — restated after the revision approved on the 20th. What did we report on the 12th, and what is true now?"

**Needs:** achievement MTD as of the 12th with the target in force on the 12th; the same as of today with the revised target; the delta. `dw` offers `agg_month_route_product` / `agg_month_zone_product` (`qty_base_mtd`, `std_target`, `target_version`, `through_date`, `computed_at`) and `app.target_version` (history, but in `app`).

| Condition | Check |
| --- | --- |
| Target revision | lens-data §4.4 step 7: a revision "enqueues (month_route, M, route)" and the row is **recomputed in place** with the new target. The row that the board saw on the 12th no longer exists. `target_version` is kept, so the analyst can see that the target changed, not what the achievement % was. |
| Late data | the same mechanism restates `qty_base_mtd` when a D−5 batch lands. The number shown on the 12th for days ≤ 12 is not reproducible from `agg_daily_route_sku` either, because those daily rows were also recomputed. `computed_at` is a single timestamp (last change), so even "did it change since the 12th?" is answerable only for the most recent change. |
| Business date | correct. |

**Verdict: GAP** → G-analyst-02. Fix: (a) `dw.dim_target` SCD2 (target_id, version, std_target, memo_target, valid_from, valid_to, revision_id) mirrored from `app.target_version` so "target in force on date d" is a `dw` lookup; (b) a nightly **as-reported snapshot** `dw.snap_month_zone_product (as_of_date, month, zone_id, product_level, product_id, qty_base_mtd, memo_count_mtd, std_target, target_version)` — 1,051 zones × ~63 products × 30 nights ≈ 2 M rows/month, trivially small; route grain is not snapshotted (11,336 × 63 × 30 ≈ 21 M/month) but is reproducible from (c); (c) `dw.agg_restatement_log (grain, business_date, key1, key2, changed_at, reason, old_row jsonb, new_row jsonb)` written by the worker whenever a recompute changes a row whose `business_date < today − 1` or whose month row's `std_target` changed — this makes "which numbers moved after we reported them, and why" a query.

### Q7. "Memo edits per SR: how many, for which reasons, and by how much did the total go down?"

**Needs:** per superseding memo: SR, reason, original gross/net, new gross/net, delay between original print and edit, whether the edit fix was inside the geofence. `dw` offers `fact_visit.edited` (boolean), `memo_count`/`active_memo_count`; `fact_memo_line.memo_status` (active/superseded); `agg_daily_route.edited_memos`, `agg_daily_outlet.edited_memos`; `dim_reason` (list `memo_edit` codes).

| Condition | Check |
| --- | --- |
| Edits/supersedes | `app.memo` has `supersedes_memo_id`, `edit_reason_code`, `edit_fix_id`, `superseded_at`, `print_count` — none of it reaches `dw`. `fact_memo_line` has no `supersedes_memo_id`, no reason; `dim_reason` exists but no fact carries a `reason_key`. "Net reduction" needs the pair (old memo total, new memo total): the lines of both are in `fact_memo_line` but cannot be paired without the supersedes link. |
| SR reassignment | `user_id` on the memo is the editor; fine once the fact exists. |
| Late data | correct. |

**Verdict: GAP** → G-analyst-01 (memo-grain fact). With `dw.fact_memo` the query is a self-join on `supersedes_memo_id`.

### Q8. "Time between outlet open and memo print, by SR and by weekday."

**Needs:** `visit.started_at` and `memo.printed_at` (first successful print), per SR, per weekday; distribution (p50/p95), excluding unprinted memos (G-feat-43) and reprints.

| Condition | Check |
| --- | --- |
| Memo-grain fact | `fact_visit` has `started_at`, `ended_at`, `duration_s` but no print time; `fact_memo_line` has none. `app.memo.printed_at` exists. No `dw` object answers this. |
| Edits | an edited memo prints again under a new `memo_no`; the measure must take the **first** memo of the visit (`supersedes_memo_id IS NULL`). |
| Business date / time basis | `printed_at` is device wall-clock (G-feat-18 clock games); lens-sync §3.4 stamps corrected time on captures but the spec does not say whether `printed_at` is corrected. Duration between two device timestamps is skew-immune only if both use the same clock; a clock change between open and print (rare) corrupts it. State: `printed_at` stored as device time **and** `print_elapsed_ms` (monotonic since visit open). |

**Verdict: GAP** → G-analyst-01 (`fact_memo.printed_at`, `time_to_print_s`, `print_count`, `unprinted`) + G-analyst-14 (time basis).

### Q9. "Late-synced sales by days of delay, by territory and by app version."

**Needs:** per memo: `business_date`, `received_at` (server) → delay days (Dhaka); territory as-of date; app version at capture.

| Condition | Check |
| --- | --- |
| Late data | `fact_memo_line.received_at` and `business_date` exist; `delay_days = (received_at AT TIME ZONE 'Asia/Dhaka')::date − business_date`. Per memo needs `fact_memo` (G-analyst-01) or `MIN(received_at)` per `memo_id` from lines; usable. `fact_visit.sync_latency_s` and `captured_offline` are there. Correct in substance. |
| App version | `@PROV` stores `app_version` on every `app` row; `fact_visit`/`fact_memo_line` carry `device_key` → `dim_device` (model, OS, ABI, RAM) but **no `app_version`**; a device changes versions weekly so it cannot be a `dim_device` attribute. Missing. |
| Restatement | a batch that arrives on D+3 changes D's territory totals after the Daily Tracking meeting of D; management will ask "which numbers changed since we looked?" — the restatement log of G-analyst-02 answers it. |
| Business date | the memo keeps `business_date` D even when received on D+3 (lens-sync §3.4). Correct. |

**Verdict: GAP (minor)** → G-analyst-11 (`app_version` on `fact_visit`, `fact_memo`, `fact_memo_line`) and G-analyst-02 (restatement log). The delay histogram itself is answerable today.

### Q10. "Outlets on the plan not visited for N consecutive planned days (dormant), by route."

**Needs:** `agg_daily_outlet (business_date, outlet_id, is_planned, visited)` as a window over dates; `dim_outlet.status` as-of; `agg_outlet_balance.last_visit_date`, `visits_90d`.

| Condition | Check |
| --- | --- |
| Planned definition | D-09: `is_planned` = outlet active on that date and route planned that day (`visit_days_mask & dow_mask`). It does not mention `dim_date.is_working_day` or `route_day_override` (lens-config `cfg.calendar.route_visit_day_exceptions`). P-03: Friday is off and Eid closes 5 days; without the calendar every outlet is "planned, not visited" on those days and N consecutive misses are reached by the holiday alone. |
| Closed outlets | an outlet closed on day k must stop being planned from k; `dim_outlet` SCD2 `status` supports it if `is_planned` is evaluated with `f_outlet_key(outlet_id, date)`. Correct if stated. |
| Outlet moves | `agg_daily_outlet.route_id` is as-of date; a moved outlet's history stays on the old route. Correct. |
| Volume | 460 k rows/day × 30 days window = 14 M rows per query; acceptable on the replica with the `(business_date, route_id)` index, but a maintained `agg_outlet_visit_streak (outlet_id, consecutive_planned_missed, last_planned_date, last_visited_date)` is cheaper for the AMO app tile. |

**Verdict: GAP (minor)** → G-analyst-10: `is_planned = visit_days match AND dim_date.is_working_day AND NOT route_day_override.planned=false (OR override.planned=true)`; `target_outlets`, Login % and CPR denominators inherit the same rule (otherwise Daily Tracking shows every route red on Eid). Add `agg_outlet_visit_streak`.

### Q11. "How often does the printed unit price differ from the list price in force, per SR?"

**Needs:** per memo line: `unit_price_mtk` paid vs list price valid on `price_list_date`/`business_date` for the SKU and entered unit; SR.

| Condition | Check |
| --- | --- |
| Price change | DQ-13 computes exactly this at ingest and writes `price_mismatch` into `memo_line.flags[]` with the expected value in `flags_detail`. `fact_memo_line` carries `unit_price_mtk`, `price_valid_from`… no: it carries `unit_price_mtk, gross, discount, net, is_free` and nothing about the expected price or the flag. The analyst must re-derive the list price from `app.sku_price` — forbidden — and cannot reproduce the server's verdict (which used the price list of `memo.price_list_date`, possibly a stale bundle, lens-sync §4.5 `stale_price`). |
| Edits | superseded lines excluded via `memo_status`. Correct. |

**Verdict: GAP** → G-analyst-06/07: `fact_memo_line.list_price_mtk`, `price_mismatch boolean`, `price_list_date`, `bundle_stale boolean`; `agg_daily_route.price_mismatch_lines`. This is also the "Price compliance" count in the AMO reconciliation (G-feat-01) — two different things today (AMO's manual check vs server recompute) that should be two named measures.

### Q12. "Of credit created in month M, how much was collected within 30 days, by channel?"

**Needs:** per credit memo: `due_mtk` at creation, business_date; collections allocated to that memo with their dates; outlet channel as-of M.

| Condition | Check |
| --- | --- |
| Allocation | same hole as Q4: a collection without `against_memo_id` cannot be attributed to a creation cohort. With `fact_due_allocation` (G-analyst-05) the cohort query is `SUM(allocated_mtk WHERE collection_date ≤ memo_date + 30) / SUM(due_mtk)`. |
| Edits | a credit memo superseded on the same day: `memo_superseded_reversal` removes its due from the cohort and the new memo enters with the same business_date. Correct. |
| Outlet moves / channel | channel as-of M via `dim_outlet` (`outlet_class_history`). Correct. |
| Closed outlets | credit to an outlet closed later must remain in the cohort (it is the loss). No status filter. Correct. |

**Verdict: GAP** (shares G-analyst-05).

### Q13. "Did offer X work? STD of qualifying SKUs in zones where X was active vs zones where it was not, before/during/after."

**Needs:** which zones had offer X on which dates (`app.offer_scope`, `offer.valid_from/to`, `offer_version`); line-level take-up (`fact_memo_line.offer_key`, `is_free`, `discount_mtk`); memo-level offers (`app.memo_offer`) for basket/DRP promotions; STD from `agg_daily_route_sku`.

| Condition | Check |
| --- | --- |
| Offer scope | `dim_offer` has code/group/type/level/version/validity but **no geography or channel scope**; `app.offer_scope` is not mirrored. "Zones where X was active" is unanswerable from `dw`. |
| Memo-level offers | `app.memo_offer` (DRP discount, basket promos, `free_sku_id/free_qty_base`) has no `dw` fact; `fact_memo_line.offer_key` covers line offers only. Discount Report totals (docs/09) = line discounts + memo offers; the second half is missing. |
| Price change / version | `dim_offer` is versioned (`UNIQUE(offer_id, version)`) and lines store `offer_id`; the memo stores `price_list_date`/`config_version` but not `offer_version` — lens-config §3.2 says `memo` stores `promo_rule_version`; lens-data M-06 does not have the column. Reconcile: add `memo.offer_version_set jsonb` or rely on `config_version` → `offer_version` lookup. |
| Edits | superseded memos' offers excluded. Correct once the facts exist. |

**Verdict: GAP** → G-analyst-08: `dw.bridge_offer_scope (offer_key, node_type, node_id, valid_from, valid_to)` and `dw.fact_memo_offer (memo_id, business_date, offer_key, discount_mtk, free_product_key, free_qty_base, basis)`.

### Q14. "Diamond League: points earned, redeemed, verified, and the outstanding points liability at month end, by outlet tier."

**Needs:** `fact_loyalty_ledger` (kind, `points_delta`, `period_key`, `outlet_key`), `fact_redemption_line` (`points`, `cash_mtk`, `verified`), `agg_outlet_balance.loyalty_points_balance` (now), `agg_outlet_balance_daily` (balance as of D = last row ≤ D), `dim_outlet.astha_tier` as-of.

| Condition | Check |
| --- | --- |
| Late data | a late memo that earns points lands on its business_date; the ledger row carries `source_type='memo', source_id` with `UNIQUE(source_type, source_id)` so re-aggregation never double-credits. Correct. |
| Edits | a superseded memo's earn must be reversed: the design says points are server-computed from facts (lens-security FS-06) and ledger rows are per source; a `reversal` kind on supersede is implied by `fact_due_ledger`'s pattern but not listed for loyalty (`loyalty_ledger.source_type` has memo/redemption/migration_opening/adjustment/expiry). Add `memo_superseded_reversal` to the loyalty kinds — otherwise an edit that removes qualifying lines leaves phantom points. (Recorded as a caveat inside G-analyst-13's program fixes.) |
| Balance as-of | `agg_outlet_balance_daily` rows only on change days; "as of 31 Aug" = `DISTINCT ON (outlet_id) ORDER BY business_date DESC WHERE business_date ≤ '2026-08-31'` over ≤ 460 k outlets × ~20 change days/month. Fine. |
| Closed outlets | liability includes closed outlets with balances (points may be honoured or written off); keep rows, flag status. Correct. |
| Earn rule | unknown (G-feat-20) — the structure is right; the numbers wait for the rule. |

**Verdict: CORRECT** (with the loyalty reversal kind added).

### Q15. "On the outage morning, which routes' Login % fell below 70 %, and were offline starts counted?"

**Needs:** `agg_daily_route.logged_in`, `logged_in_at`; whether the login came from a bundle download or an offline `day_open` on a stale bundle (lens-sync §4.5, G-sync-07); `fact_bundle_download.is_first_of_day`.

| Condition | Check |
| --- | --- |
| Offline start | lens-sync adds the `day_open{online:false}` event and a `route_day` flag `offline_start`; `agg_daily_route` has `logged_in`/`logged_in_at` but no `offline_start` or `bundle_stale` column. The analyst cannot separate "logged in online" from "started on yesterday's bundle" — the exact question an outage morning raises. |
| Outage correlation | failed requests never reach Postgres; 5xx/429 live in App Insights (lens-quality §4). Acceptable: that is operational telemetry, not business data; the sync-health page joins both visually. |
| Business date | correct. |

**Verdict: CAVEAT** → G-analyst-16: `agg_daily_route.offline_start boolean`, `bundle_version_at_open`, `bundle_stale boolean`; `agg_daily_zone.offline_start_routes`.

### Q16. "New outlets approved per month by territory, and how many were duplicates later merged or closed within 90 days?"

**Needs:** `fact_outlet_request` (type=new, status, `approved_at`, `geo_key`, `requested_by_key`, `verified_by_key`); `dim_outlet` SCD2 `status`; a merge concept.

| Condition | Check |
| --- | --- |
| Closed vs merged | lens-security FS-03 gives the approval panel a "merge / not a duplicate" decision, but no table records a merge; `outlet.status` has `active`/`closed` only. "Closed because duplicate" and "closed because the shop shut" are indistinguishable, and the history of the merged-away outlet (its memos, dues, points) is not re-pointed to the survivor. |
| PII | duplicate detection uses `phone_hash` (HMAC with pepper) — PII-safe; `pepper_version` must be on the column or rotation breaks historical matching (lens-security notes it; `app.outlet` in M-16 has `phone_hash` without the version column). |
| Outlet moves | `fact_outlet_request.geo_key` is the route at request time. Correct. |

**Verdict: GAP (minor)** → G-analyst-12: `app.outlet_merge (from_outlet_id, into_outlet_id, at, by, reason, request_id)`, `outlet.status` gains `merged`, `dim_outlet.merged_into_outlet_id`, and a reporting rule: facts keep the original `outlet_key`; `dw.bridge_outlet_effective (outlet_id, effective_outlet_id, valid_from)` lets "sales of the surviving shop" include the merged one from the merge date on.

### Q17. "Stock variance per SR per day (issued − sold − returned) and the chronic leakers."

**Needs:** `agg_daily_user_sku` (`issued_base`, `returned_base`, `sold_base`, `free_base`, `variance_base`), `fact_stock_movement`, `dim_user`.

| Condition | Check |
| --- | --- |
| Edits | `sold_base` comes from active memos only; an edit recomputes the user-day row. Correct. |
| Free goods | `free_base` separated so promotions do not show as leakage. Correct. |
| SR cover | stock is per user, not per route, so cover days are attributed correctly. Correct. |
| Carry-over | if the business lets an SR carry unsold stock to the next day without a return entry (G-feat-02 open), every carry shows as variance. That is a business rule, not a schema gap; the design already has `stock_movement.kind='adjustment'` for it. |
| Chronic | "leaker" = `variance_base > tolerance` on ≥ k of the last 20 working days — a window over `agg_daily_user_sku`; fine. |

**Verdict: CORRECT** (pending the G-feat-02 rule).

### Q18. "Outlet O was moved from route A to route B on the 15th. Whose MTD did its sales count toward, and did anyone's achievement change retroactively?"

**Needs:** `fact_memo_line.route_id` (route at capture), `geo_key`, `outlet_key`; `agg_daily_route_sku`, `agg_month_route_product`; `dim_outlet` SCD2 (`route_id` by validity); `dw.agg_restatement_log` (for "changed retroactively").

| Condition | Check |
| --- | --- |
| Outlet moves | `visit.route_id` is the route being worked at capture (seed #3 fixed by M-05); `agg_daily_route_sku` is keyed by that `route_id`; `dim_outlet` opens a new SCD row from the move date and past facts keep the old `outlet_key`. Sales before the 15th stay on A, after on B; the move itself changes no history (§4.4 step 6). Correct. |
| Back-dated move | an admin who sets `valid_from` in the past triggers a bounded re-aggregation of the affected days and the UI says "N days will be re-aggregated" — and nothing records what the numbers were before. With G-analyst-02's restatement log the "did anyone's achievement change" half is answerable. |
| SR reassignment | route targets are per route (docs/10); an SR moved between routes mid-month has a "personal MTD" only via `agg_daily_user_sku.sold_base` summed over the days — exists. Whether the SR's incentive follows the person or the route is business (G-feat-06). |
| Target revision | the move does not change targets; the target stays on the route. Correct. |

**Verdict: CORRECT** (the retroactive-change half needs G-analyst-02's log).

### Q19. "Average and p95 call duration and calls per worked hour per SR, excluding abandoned visits."

**Needs:** `fact_visit.duration_s`, `ended_at`, `user_id`; `fact_attendance.worked_minutes`; a way to exclude abandoned visits.

| Condition | Check |
| --- | --- |
| Abandoned visits | lens-sync §8.4 closes an open visit after `cfg.visit.abandon_min` with `outcome='abandoned'` and says they are excluded from "visited"/CPR by default. `fact_visit` has `is_successful`, `is_zero_sale`, `ended_at` — no `outcome`. An abandoned visit with `ended_at` set at the 120-minute mark has `duration_s = 7200` and poisons the p95. |
| SR cover | `user_id` per visit is right; worked minutes per user from `fact_attendance`. Correct. |
| Business date | `worked_minutes` across midnight (a 23:50 check-out) — `fact_attendance` is one row per user per business_date; fine. |

**Verdict: GAP (minor)** → G-analyst-11: `fact_visit.outcome text` (`completed`, `zero_sale`, `abandoned`, `force_completed`) and `agg_daily_route.abandoned_calls`; `agg_daily_user` (G-analyst-03) carries `avg_duration_s`, `p95_duration_s` (HLL/percentile_cont at compute time) and `calls_per_hour`.

### Q20. "Sales between 23:00 and 01:00 — which business date do they belong to, and what does the hourly sales chart use for the hour?"

**Needs:** `fact_visit.business_date`, `hour_of_day`, `started_at`; the clock rule.

| Condition | Check |
| --- | --- |
| Business date | lens-sync §3.4: `business_date = date(t_corr in Asia/Dhaka)` at capture, where `t_corr = t_device + server_time_offset_ms`; lens-security §5.4: `captured_at_trusted = anchor.server_time + elapsed` and `business_date` derived from **that**. Two lenses, two formulas (offset-corrected wall clock vs monotonic anchor); both are better than raw device time, but the merged plan must pick one and `fact_visit` must say which timestamp it stores. lens-data M-05 stores `captured_at` (device) and a generated `business_date_server` from it — the generated column uses the **uncorrected** device time, so on a skewed phone `business_date_server` disagrees with the device's corrected `business_date` and the row is flagged `business_date_mismatch` even though the device was right. The generated column should use the trusted/corrected timestamp. |
| Hour of day | `fact_visit.hour_of_day` — the DDL does not say which timestamp or zone. On a UTC server `EXTRACT(hour FROM started_at)` is UTC; a 10:00 Dhaka sale shows as 04:00. Must be `started_at_trusted AT TIME ZONE 'Asia/Dhaka'`. |
| Midnight | a 23:55 sale synced at 00:10 lands on D and re-aggregates D only (lens-sync T-1-27). Correct. |

**Verdict: CAVEAT** → G-analyst-14: one trusted timestamp per row (`captured_at_trusted`), `business_date_server` generated from it, `hour_of_day` and all `*_local time` columns derived from it in Asia/Dhaka, documented in the `dw` README.

### Q21. "Which SKUs were sold in zones where they were not in the sales plan, by month?"

**Needs:** DQ-08's `sku_not_in_plan` flag per line; zone and SKU dimensions.

| Condition | Check |
| --- | --- |
| DQ flags in `dw` | `app.memo_line.flags text[]` (via `@PROV`) receives the flag; `fact_memo_line` has no `flags` column and there is no generic flag fact — only `fact_geo_flag` for visit geo flags. Re-deriving from `app.sales_plan` history is forbidden and would not reproduce the ingest verdict (sales plan as-of `business_date`). The same hole hits `qty_outlier`, `pack_factor_mismatch`, `stale_price`, `clock_skew`, `outlet_inactive`, `unassigned_route`, `after_final_submit` — every operational question of the form "how many rows had flag F, by scope and time". |
| Outlet moves | zone at capture is on the row. Correct. |

**Verdict: GAP** → G-analyst-07: `dw.fact_dq_flag (record_type, record_id, business_date, flag, detail jsonb, outlet_key, geo_key, user_key, product_key)` partitioned by month, populated from `flags[]` of every `app` row; plus the three most-used flags as boolean columns on `fact_memo_line` (`price_mismatch`, `sku_not_in_plan`, `qty_outlier`) and `fact_visit` (`unassigned_route`, `after_final_submit`, `clock_skew`); `agg_daily_route.flagged_rows jsonb` (flag → count).

### Q22. "Retailer O disputes memo N of <date>: the paper says total due 1,240 Tk; what did our ledger say at that moment, and what does it say now?"

**Needs:** the memo's own due, the outlet's outstanding **as printed** at print time, the ledger balance at that instant, the balance now, every event between.

| Condition | Check |
| --- | --- |
| Printed snapshot | lens-sync §6.2 prints "previous due, total due" computed on the device from local data; `app.memo` stores `due_mtk` (this memo) but **not** `outstanding_before_mtk` or the printed total due. `app.due_collection` has `outstanding_before_mtk`; `memo` does not. The server-side balance at that instant (`fact_due_ledger` ordered by `at`) can differ from what the device printed (late collections, parked rows, another device) — and the retailer holds the paper. Without the printed figure the dispute cannot be adjudicated. |
| Edits | the memo may have been superseded; `fact_memo` (G-analyst-01) chain shows it. |
| Late data | `fact_due_ledger` as-of query is correct for "what the ledger says now" and "at that instant (server view)". |
| PII | the dispute screen needs the retailer's name/phone: served by the API to roles with `pii_access`, logged in `audit_log(action='pii_read')`; the `dw` fact carries only `outlet_key`. Correct. |

**Verdict: GAP (minor)** → G-analyst-15: `app.memo.outstanding_before_mtk`, `printed_total_due_mtk` (device-computed, stored verbatim, DQ-flag `due_snapshot_mismatch` if ≠ server balance at ingest), carried to `fact_memo`.

### Q23. "On <date> in zone Z, which visits were validated against a radius that was changed and reverted within the day?"

**Needs:** `fact_visit.radius_m_used`, `config_version`; `fact_config_change` (`key`, `scope`, `config_version`, `effective_from`, `is_revert_of`).

| Condition | Check |
| --- | --- |
| Config change over time | each visit stores the `config_version` it ran under and the resolved radius; `fact_config_change` rows for that zone's chain on that date give the sequence 100 → 60 → 100. Visits with `config_version` between the two changes are the answer. lens-config's `cfg.config_version.is_revert_of` is not in lens-data's `fact_config_change` DDL — add the column (G-analyst-11). |
| Device vs server | the device applied the delta at its next request; the server re-check used the value effective at `captured_at` (lens-config §2.3). A visit captured at 11:05 on a phone that had not yet pulled the 11:00 change stores `radius_m_used = 100` (device) while the server validated with 60 → `geo_mismatch = true`. That is the designed behaviour and `fact_visit.geo_mismatch` makes it visible. Correct. |

**Verdict: CORRECT** (one column to add).

### Q24. "Wing-level STD this October vs last October — across the cutover, using the migrated Apsis months."

**Needs:** `agg_month_zone_product` for 2025-10 (migrated) and 2026-10 (native) rolled to wing via `dim_geo` as-of each month; knowledge of which rows are migrated and at what fidelity.

| Condition | Check |
| --- | --- |
| Migration provenance | lens-data M-42 says imported memos carry `entry_source='migration'` and flow through the same worker — assuming a **memo-level** dump. docs/22 shows the sample is already aggregated (outlet × SKU × day, no memo, no SR, no route); the full dump *request* asks for memo level, but the fallback when Apsis delivers aggregates again is undefined. If history arrives as daily outlet×SKU volume, the importer must either synthesise one memo per outlet-day (fabricating `memo_no`, `user_id`, `route_id`, prices) or load `agg_daily_outlet`/`agg_daily_route_sku` directly. Neither path exists; `agg_*` rows have no provenance column, so a YoY chart cannot say "left bar is Apsis aggregate, right bar is native memo-level". |
| Outlet moves / route attribution | migrated volume has no route; `route_id` must be inferred from the retailer list as of 1 Oct 2026 (P-13), which is wrong for outlets that moved during the 8 months — route/zone history before cutover does not exist. Wing-level is safe (outlets rarely cross wings); route-level YoY is not, and the tables cannot warn the analyst. |
| Closed outlets | 175 k outlets with history are stubs (P-08); `dim_outlet` rows must exist for them with `status='archived'` and `valid_from` = first sale date. The design says "archived outlet stubs" (docs/22) but `dim_outlet` has no `archived` status value and no rule for `valid_from`. |
| Units | migrated volume is in sticks (P-04); `qty_base` matches. Correct. |
| Zero lines | P-05: 8.7 % zero-volume lines are not sales; the importer must drop them from `memo_count`/`is_successful` (D-05/D-06 handle `line_count = 0`). Correct if the importer maps zero-volume outlet-days to `is_zero_sale` visits. |

**Verdict: GAP** → G-analyst-09: `agg_*` and `fact_*` gain `source text NOT NULL DEFAULT 'native'` (`native | migration_memo | migration_aggregate`) and `fidelity smallint` (3 = memo-level, 2 = outlet-day aggregate, 1 = zone-month totals); importer path B writes `agg_daily_outlet`/`agg_daily_route_sku`/`agg_month_zone_product` directly for aggregate-only months with `source='migration_aggregate'`, excluded from `dw.reconcile()` and from any route-level or SR-level report (reports declare `min_fidelity`); `dim_outlet.status` gains `archived`; `dim_geo` for pre-cutover months is the 1 Oct 2026 structure, labelled as such in `dim_date.structure_basis`.

### Q25. "Astha achievement by tier for Q3 vs Q2 — where an outlet moved from Gold to Platinum mid-quarter."

**Needs:** `agg_month_outlet_program (period_key, outlet_id, product, qty_base, std_target, criteria_met)`; `program_enrolment` (tier, `valid_from/to`); `dim_outlet.astha_tier` SCD2; `dim_program_period`.

| Condition | Check |
| --- | --- |
| Tier change mid-period | `agg_month_outlet_program` has no tier; joining to `program_enrolment` by period yields two rows (Gold until the 20th, Platinum after) → the outlet's achievement is double-counted or the join is ambiguous. Astha targets are per outlet per quarter (docs/06); if the tier change re-sets the target mid-quarter, `program_outlet_target.version` captures it but the aggregate stores one `std_target`. Needs an attribution rule (tier at period end, or at enrolment) and the chosen tier stored on the row. |
| Target revision | `agg_month_outlet_program.std_target` is overwritten on revision — same as Q6; the quarter-end report must snapshot (G-analyst-02 covers programme rows too: add `snap_period_outlet_program`). |
| Edits / late data | recompute. Correct. |
| Closed outlets | an Astha outlet closed mid-quarter stays in the period with its achievement; `criteria_met` must be evaluable. Keep rows. Correct. |
| Quarter alignment | `dim_date.quarter_label` 'Q4 Oct–Dec' assumes calendar quarters (`cfg.astha.quarter_start_month`, confirm). |

**Verdict: GAP (minor)** → G-analyst-13: `agg_month_outlet_program.tier_code`, `enrolment_id`, `tier_attribution` (cfg `cfg.astha.tier_attribution = period_end | enrolment | pro_rata`), and the loyalty reversal kind from Q14.

---
## 2. Gap list (G-analyst-NN) with the exact fix

All fixes are additive. `dw` DDL follows lens-data §4 conventions (SCD keys + natural ids on facts; monthly partitions on event grain; `computed_at` on aggregates). Migration slots proposed: **M-46..M-52** for `app` changes, **M-60..M-66** for `dw` additions (lens-data reserved M-50+ for `dw`; these follow its `dw` series).

### G-analyst-01 — No memo-grain fact (blocker)

**Where:** lens-data §4.3 (`fact_visit`, `fact_memo_line` only). **Hits:** Q7, Q8, Q9, Q22; also reprint counts (G-feat-04), Online/Offline Sales (W20), edit audit (FS-02).

```sql
-- M-60
CREATE TABLE dw.fact_memo (
  memo_id bigint NOT NULL, business_date date NOT NULL, date_key int NOT NULL, client_uuid uuid NOT NULL,
  visit_id bigint NOT NULL, outlet_key bigint NOT NULL, geo_key bigint NOT NULL, user_key bigint NOT NULL, device_key bigint,
  outlet_id bigint, route_id bigint, zone_id bigint, user_id bigint, app_version text, source text NOT NULL DEFAULT 'native',
  memo_no text, memo_serial bigint, entry_source text, captured_offline boolean, bundle_stale boolean,
  status text NOT NULL,                                   -- active | superseded | void
  supersedes_memo_id bigint, superseded_by_memo_id bigint, edit_reason_key bigint, edit_seq smallint NOT NULL DEFAULT 0,
  edit_geo_valid boolean, edit_delay_s int,               -- superseding memo: seconds after the original's printed_at
  is_credit boolean, is_zero_memo boolean, line_count smallint, distinct_sku smallint, distinct_brand smallint,
  gross_mtk bigint, discount_mtk bigint, memo_offer_discount_mtk bigint, net_mtk bigint, round_adj_mtk bigint, paid_mtk bigint, due_mtk bigint,
  outstanding_before_mtk bigint, printed_total_due_mtk bigint, server_balance_at_print_mtk bigint, due_snapshot_mismatch boolean,
  price_list_date date, offer_version_set jsonb, config_version int, price_mismatch_lines smallint, flags text[],
  captured_at_trusted timestamptz, printed_at timestamptz, time_to_print_s int, print_count smallint, unprinted boolean, qc_completed_at timestamptz,
  received_at timestamptz, sync_latency_s int, delay_days smallint,  -- (received_at Dhaka date) − business_date
  PRIMARY KEY (memo_id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_memo (business_date, user_id); CREATE INDEX ON dw.fact_memo (outlet_id, business_date);
CREATE INDEX ON dw.fact_memo (supersedes_memo_id) WHERE supersedes_memo_id IS NOT NULL;
```
Aggregates gain: `agg_daily_route.unprinted_memos`, `reprints`, `edit_net_reduction_mtk`; `agg_daily_user` (G-analyst-03) the same per user. Edits per SR (Q7) = `fact_memo WHERE supersedes_memo_id IS NOT NULL GROUP BY user_id, edit_reason_key`; net reduction = `new.net_mtk − old.net_mtk` via self-join.

### G-analyst-02 — Aggregates are current-state only; no as-reported snapshot, no restatement log, no target dimension (blocker)

**Where:** lens-data §4.4 steps 5–7, `agg_month_*`, `agg_daily_*`. **Hits:** Q6, Q9, Q18, Q24, Q25; R1 literally ("without re-deriving history").

```sql
-- M-61
CREATE TABLE dw.dim_target (target_key bigserial PRIMARY KEY, target_id bigint NOT NULL, version int NOT NULL,
  scope_type text, scope_id bigint, geo_key bigint, product_level text, product_id bigint, month date,
  std_target numeric(16,3), std_unit text, memo_target int, allocation_method text, source text, revision_id bigint,
  valid_from timestamptz NOT NULL, valid_to timestamptz, set_by_user_key bigint, UNIQUE (target_id, version));
-- nightly as-reported snapshot at zone grain (1,051 × ~63 products × 30 ≈ 2 M rows/month); route grain reproducible from agg_daily_route_sku + dim_target
CREATE TABLE dw.snap_month_zone_product (as_of_date date NOT NULL, month date NOT NULL, zone_id bigint NOT NULL, product_level text NOT NULL, product_id bigint NOT NULL,
  qty_base_mtd bigint, memo_count_mtd int, net_mtk_mtd bigint, std_target numeric(16,3), memo_target int, target_version int, achievement_pct numeric(8,3),
  PRIMARY KEY (as_of_date, month, zone_id, product_level, product_id)) PARTITION BY RANGE (as_of_date);
CREATE TABLE dw.snap_period_outlet_program (LIKE dw.agg_month_outlet_program INCLUDING ALL, as_of_date date NOT NULL); -- quarter-end programme freeze
-- restatement log: written by the worker when a recompute changes a row for business_date < today−1, or a month row's target
CREATE TABLE dw.agg_restatement_log (id bigint GENERATED ALWAYS AS IDENTITY, changed_at timestamptz NOT NULL DEFAULT now(),
  grain text NOT NULL, business_date date NOT NULL, key1 bigint NOT NULL, key2 bigint NOT NULL DEFAULT 0,
  reason text NOT NULL,                                   -- late_batch | memo_superseded | target_revision | dim_backdate | rebuild | reconcile_repair
  trigger_ref text,                                       -- sync_batch_id / revision_id / audit_id
  old_row jsonb NOT NULL, new_row jsonb NOT NULL, delta jsonb,
  PRIMARY KEY (id, changed_at)) PARTITION BY RANGE (changed_at);
CREATE INDEX ON dw.agg_restatement_log (grain, business_date, changed_at);
```
Rules: (1) `agg_month_*` keep both `std_target_current` and `std_target_original` (first version in force on the 1st of the month) and `target_key`; achievement % is computed against `current` by default and `original` on request. (2) "As reported on date d" = `snap_month_zone_product WHERE as_of_date = d`. (3) "What changed since d" = `agg_restatement_log WHERE changed_at > d`. (4) The restatement log is sized: late data touches ~2 % of route-days (ASSUMPTION; measure in pilot) → ≈ 200 rows/day at route grain plus month rows; negligible. (5) `dw.reconcile()` repairs also log with `reason='reconcile_repair'` so self-healing is auditable.

### G-analyst-03 — No user-day aggregate; `acting_user_id` single-valued; visit `kind` filter undefined (major)

**Where:** `agg_daily_route`, D-05/D-09. **Hits:** Q2, Q3 denominator, Q19; every "by SR" tile in the apps (F-SR-010, F-AMO-017, SR Efficiency report).

```sql
-- M-62
CREATE TABLE dw.agg_daily_user (business_date date NOT NULL, user_id bigint NOT NULL, user_key bigint NOT NULL, role text NOT NULL, primary_geo_key bigint,
  routes_worked int, target_outlets_worked int, visits int, successful_calls int, zero_sale_calls int, abandoned_calls int, unplanned_visits int,
  geo_valid_calls int, photo_valid_calls int, mock_calls int, suspicious_calls int,
  memo_count int, edited_memos int, unprinted_memos int, reprints int, credit_memos int, price_mismatch_lines int,
  gross_mtk bigint, discount_mtk bigint, net_mtk bigint, credit_created_mtk bigint, collected_mtk bigint,
  worked_minutes int, first_visit_delay_s int, avg_duration_s int, p95_duration_s int, calls_per_hour numeric(6,2),
  computed_at timestamptz NOT NULL, PRIMARY KEY (business_date, user_id));
```
Definitions written into D-05/D-09: `agg_daily_route.*` counts **only** `fact_visit.kind = 'sr_call'` plus `acting_users int[]`; AMO/TSO visits roll into new columns `amo_visits`, `amo_successful_calls` on `agg_daily_route`; `target_outlets_worked` for a user = planned active outlets of each route the user had a `route_assignment` (any kind) or an actual visit on that day, de-duplicated. `agg_daily_user.role` lets the same table serve AMO self-sales.

### G-analyst-04 — Supervisor assignment has no history (major)

**Where:** `app.user_scope` (M-19 unchanged), `dim_user.home_zone_id`. **Hits:** Q5; every "by AMO/TSO/DMO" report; Supervisory Module (G-feat-31).

```sql
-- M-46 (app)
ALTER TABLE app.user_scope ADD COLUMN valid_from date NOT NULL DEFAULT '2000-01-01', ADD COLUMN valid_to date, ADD COLUMN changed_by bigint, ADD COLUMN reason text,
  ADD CONSTRAINT user_scope_no_overlap EXCLUDE USING gist (user_id WITH =, node_type WITH =, node_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&);
-- a scope change closes the open row (valid_to = change date) and inserts a new one; bumps app_user.scope_version (lens-security 2.1)
-- M-62 (dw)
CREATE TABLE dw.dim_supervisor_assignment (assignment_key bigserial PRIMARY KEY, user_id bigint NOT NULL, user_key bigint, role text NOT NULL,
  node_type text NOT NULL, node_id bigint NOT NULL, valid_from date NOT NULL, valid_to date, UNIQUE (user_id, node_type, node_id, valid_from));
CREATE FUNCTION dw.f_supervisor(node_type text, node_id bigint, role text, d date) RETURNS bigint ...; -- the user responsible on date d
ALTER TABLE dw.agg_daily_zone ADD COLUMN amo_user_id bigint, ADD COLUMN tso_user_id bigint;   -- resolved at compute time for the business_date
```

### G-analyst-05 — Dues cannot be aged or cohort-tracked (major)

**Where:** `app.due_collection.against_memo_id` nullable; `fact_due_ledger`; `agg_outlet_balance`; `app.opening_balance`. **Hits:** Q4, Q12; docs/11 "open dues exactly right"; FS-04.

```sql
-- M-47 (app)
ALTER TABLE app.opening_balance ADD COLUMN age_basis_date date, ADD COLUMN source_memo_ref text;   -- from the Apsis dues report; NULL → cfg.credit.opening_due_age_basis
-- M-63 (dw)
CREATE TABLE dw.fact_due_allocation (allocation_id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL,   -- collection's date
  due_collection_id bigint NOT NULL, memo_id bigint, opening_balance_id bigint, outlet_key bigint NOT NULL, outlet_id bigint NOT NULL,
  amount_mtk bigint NOT NULL, method text NOT NULL,       -- as_recorded | fifo
  memo_business_date date, days_outstanding int,          -- collection date − memo date
  PRIMARY KEY (allocation_id, business_date)) PARTITION BY RANGE (business_date);
CREATE TABLE dw.agg_memo_due_open (memo_id bigint PRIMARY KEY, outlet_id bigint NOT NULL, outlet_key bigint, geo_key bigint, memo_business_date date NOT NULL,
  due_original_mtk bigint NOT NULL, collected_mtk bigint NOT NULL, outstanding_mtk bigint NOT NULL, age_days int NOT NULL, bucket text NOT NULL, is_opening boolean NOT NULL, computed_at timestamptz NOT NULL);
CREATE TABLE dw.agg_outlet_due_ageing_daily (business_date date NOT NULL, outlet_id bigint NOT NULL, bucket text NOT NULL, outstanding_mtk bigint NOT NULL, memo_count int NOT NULL,
  PRIMARY KEY (business_date, outlet_id, bucket));     -- rows only for outlets whose ageing changed that day; "as of D" = last row ≤ D per (outlet, bucket)
```
Rules: `cfg.credit.collection_allocation` (`as_recorded` when `against_memo_id` present, else `fifo` over the outlet's open memos by `memo_business_date`); `cfg.credit.ageing_buckets` = `[30, 60, 90]`; opening balances age from `age_basis_date` (confirm the Apsis dues report has a per-memo or per-outlet oldest date — add to the docs/11 dump request). Cohort collection (Q12) = `fact_due_allocation` grouped by `date_trunc('month', memo_business_date)` and `days_outstanding ≤ 30`.

### G-analyst-06 — Price history and list price absent from `dw` (major)

**Where:** `dim_product` (no price), `fact_memo_line` (paid price only). **Hits:** Q1, Q11, Q13; By Segment Value Contribution (F-TSO-005: "which price type for value?").

```sql
-- M-64
CREATE TABLE dw.dim_sku_price (price_key bigserial PRIMARY KEY, sku_id bigint NOT NULL, product_key bigint, price_type text NOT NULL, amount_mtk bigint NOT NULL,
  per_unit text NOT NULL,                                  -- the entered unit the price applies to (pack | stick | …)
  valid_from date NOT NULL, valid_to date, source text, UNIQUE (sku_id, price_type, valid_from));
CREATE TABLE dw.fact_price_change (change_id bigserial PRIMARY KEY, sku_id bigint NOT NULL, product_key bigint, brand_id bigint, price_type text NOT NULL,
  effective_date date NOT NULL, old_amount_mtk bigint, new_amount_mtk bigint, pct_change numeric(8,3), changed_by_user_key bigint, audit_id bigint);
ALTER TABLE dw.fact_memo_line ADD COLUMN list_price_mtk bigint, ADD COLUMN price_list_date date, ADD COLUMN price_mismatch boolean, ADD COLUMN bundle_stale boolean,
  ADD COLUMN reporting_price_mtk bigint, ADD COLUMN value_reporting_mtk bigint;   -- qty_entered × reporting price: the "value" the TSO dashboard wants (Q8 docs/13)
ALTER TABLE dw.agg_daily_route_sku ADD COLUMN price_mismatch_lines int NOT NULL DEFAULT 0, ADD COLUMN value_reporting_mtk bigint NOT NULL DEFAULT 0;
```
Brand-level "price change date" (Q1) = `MIN(effective_date)` of `fact_price_change` for the brand's SKUs, with the per-SKU list shown.

### G-analyst-07 — DQ and plausibility flags do not reach `dw` beyond geo (major)

**Where:** `fact_geo_flag` only; `@PROV.flags[]` on every `app` row. **Hits:** Q11, Q21, Q9 (clock), Q15; every "how many rows had flag F" question; the quarantine/flag reports lens-security §7.4 wants.

```sql
-- M-65
CREATE TABLE dw.fact_dq_flag (record_type text NOT NULL, record_id bigint NOT NULL, business_date date NOT NULL, flag text NOT NULL, detail jsonb,
  outlet_key bigint, geo_key bigint, user_key bigint, product_key bigint, device_key bigint, config_version int, received_at timestamptz,
  PRIMARY KEY (record_type, record_id, business_date, flag)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_dq_flag (flag, business_date); CREATE INDEX ON dw.fact_dq_flag (business_date, geo_key);
ALTER TABLE dw.fact_memo_line ADD COLUMN is_mock boolean, ADD COLUMN sku_not_in_plan boolean, ADD COLUMN qty_outlier boolean, ADD COLUMN flags text[];
ALTER TABLE dw.fact_visit ADD COLUMN unassigned_route boolean, ADD COLUMN after_final_submit boolean, ADD COLUMN clock_skew boolean, ADD COLUMN business_date_mismatch boolean, ADD COLUMN flags text[];
ALTER TABLE dw.agg_daily_route ADD COLUMN flag_counts jsonb NOT NULL DEFAULT '{}';   -- {"price_mismatch": 3, "sku_not_in_plan": 1, ...}
```
`fact_geo_flag` is folded into `fact_dq_flag` (`record_type='visit'`). `dim_reason` gains a list `dq_flag` with bn/en labels so the AMO "needs attention" and the web report share one vocabulary.

### G-analyst-08 — Offer scope and memo-level offers absent from `dw` (major)

**Where:** `dim_offer`; `app.offer_scope`, `app.memo_offer` (M-08, M-20) not mirrored. **Hits:** Q13; Discount Report completeness (W18); FS-19.

```sql
-- M-66
CREATE TABLE dw.bridge_offer_scope (offer_key bigint NOT NULL, offer_id bigint NOT NULL, node_type text NOT NULL, node_id bigint NOT NULL, valid_from date NOT NULL, valid_to date,
  PRIMARY KEY (offer_key, node_type, node_id, valid_from));
CREATE TABLE dw.bridge_offer_product (offer_key bigint NOT NULL, product_level text NOT NULL, product_id bigint NOT NULL, role text NOT NULL, PRIMARY KEY (offer_key, product_level, product_id, role));
CREATE TABLE dw.fact_memo_offer (memo_offer_id bigint NOT NULL, business_date date NOT NULL, memo_id bigint NOT NULL, offer_key bigint NOT NULL, outlet_key bigint, geo_key bigint, user_key bigint,
  discount_mtk bigint NOT NULL DEFAULT 0, free_product_key bigint, free_qty_base int, basis jsonb, memo_status text NOT NULL,
  PRIMARY KEY (memo_offer_id, business_date)) PARTITION BY RANGE (business_date);
ALTER TABLE dw.agg_daily_route_sku ADD COLUMN line_offer_discount_mtk bigint NOT NULL DEFAULT 0;
CREATE TABLE dw.agg_daily_zone_offer (business_date date NOT NULL, zone_id bigint NOT NULL, offer_key bigint NOT NULL, memos_with_offer int, line_discount_mtk bigint, memo_discount_mtk bigint, free_qty_base bigint, qualifying_qty_base bigint,
  PRIMARY KEY (business_date, zone_id, offer_key));
```
`app.memo` gets `offer_version_set jsonb` (M-48) so a memo records which offer versions it applied (lens-config §3.2 `promo_rule_version` reconciled with lens-data M-06). Discount Report = `agg_daily_zone_offer` grouped by `dim_offer.group_code`.

### G-analyst-09 — Migrated history has no provenance in `dw`; aggregate-only dumps have no landing path (major)

**Where:** lens-data M-42, §4; docs/22. **Hits:** Q24; every report spanning the cutover; pilot parallel-run comparisons that include history.

Fix: `source text NOT NULL DEFAULT 'native'` and `fidelity smallint NOT NULL DEFAULT 3` on every `dw.fact_*` and `dw.agg_*` table (`native`/`migration_memo` = 3, `migration_aggregate` = 2, `migration_totals` = 1); importer **path B** writes `agg_daily_outlet`, `agg_daily_route_sku` (route inferred from the retailer list as of import, flagged `route_inferred`), `agg_daily_zone_category`, `agg_month_zone_product` directly for months delivered as outlet×SKU×day volume, with `is_zero_sale` for all-zero outlet-days (P-05) and `visited=true`; those rows are excluded from `dw.reconcile()` (no source rows) and from `agg_restatement_log`; every report declares `min_fidelity` and the UI shows a hatched bar below it; `dim_outlet.status` gains `archived` (P-08 stubs, `valid_from` = first sale date); `dim_date.structure_basis` = `'cutover_snapshot'` for pre-cutover months so route/zone roll-ups before cutover are labelled as using the 1 Oct 2026 structure. Add to the docs/11 dump request: route and SR per outlet-day if memo level is impossible.

### G-analyst-10 — `is_planned`, `target_outlets` and Login % denominators ignore the working-day calendar and route-day overrides (major)

**Where:** D-08, D-09, `agg_daily_outlet.is_planned`, `route_day.planned`. **Hits:** Q2, Q10, Q15; Daily Tracking buckets; Login %/Submit % on Fridays and Eid (P-03).

Fix: `planned(route, d) = (visit_days_mask & dim_date.dow_mask ≠ 0 AND dim_date.is_working_day) XOR override(route, d)` where `override` comes from `cfg.calendar.route_visit_day_exceptions` (lens-config) materialised as `dw.bridge_route_day_override`; `agg_daily_outlet.is_planned` and `route_day.planned` use it; `dim_date.is_working_day` is scoped (national holiday vs wing holiday → `dw.bridge_holiday_scope (date, node_type, node_id)`); `agg_daily_route.target_outlets = 0` and `agg_daily_zone.target_routes = 0` on non-working days so every % is NULL ("—"), never 0 %. Add `dw.agg_outlet_visit_streak (outlet_id, planned_missed_consecutive, last_planned_date, last_visited_date, computed_at)` for the dormant-outlet tile.

### G-analyst-11 — Small missing attributes: `fact_visit.outcome`, `app_version` on facts, `fact_config_change.is_revert_of` (minor)

**Hits:** Q9, Q19, Q23. Fix: `ALTER TABLE dw.fact_visit ADD COLUMN outcome text, ADD COLUMN app_version text;` (`outcome` ∈ `completed | zero_sale | abandoned | force_completed`, from `app.visit.outcome` which lens-sync §8.4 introduces — add the `app` column in M-49); `app_version` on `fact_memo`, `fact_memo_line`, `fact_attendance`, `fact_sync_batch` (already), `fact_dq_flag`; `ALTER TABLE dw.fact_config_change ADD COLUMN is_revert_of bigint, ADD COLUMN risk_class smallint, ADD COLUMN break_glass boolean;` `agg_daily_route.abandoned_calls`, `agg_daily_zone.abandoned_calls`. `dw.dim_app_version (app_version, version_code, released_at, channel)` so version mix charts need no string parsing.

### G-analyst-12 — Outlet merge has no model; "closed" and "merged" are indistinguishable (minor)

**Hits:** Q16; FS-03 "merge" decision; dues/points of a merged-away outlet. Fix (M-50 app, M-66 dw): `app.outlet_merge (id, from_outlet_id, into_outlet_id, at, by, reason, change_request_id)`; `outlet.status` values `active | closed | merged | archived`; `dim_outlet.merged_into_outlet_id`; `dw.bridge_outlet_effective (outlet_id, effective_outlet_id, valid_from, valid_to)` so "sales of the surviving shop since the merge" is a join and history is never re-pointed; `agg_outlet_balance` of the merged outlet is transferred by a `fact_due_ledger(kind='transfer_out'/'transfer_in')` pair and the same for loyalty; `app.outlet.phone_hash_pepper_version smallint` (lens-security §5.2 mentions it; M-16 lacks it).

### G-analyst-13 — Programme attribution rules: tier change mid-period, loyalty reversal on supersede, programme snapshot (minor)

**Hits:** Q14, Q25. Fix: `agg_month_outlet_program` gains `enrolment_id`, `tier_code`, `tier_attribution text`; `cfg.astha.tier_attribution ∈ {period_end, enrolment_start, pro_rata}` (default `period_end`, ASSUMPTION — confirm with trade marketing); `app.loyalty_ledger.source_type` gains `memo_superseded_reversal` and the worker writes it when a memo with earned points is superseded (the superseding memo earns afresh); `dw.snap_period_outlet_program` (G-analyst-02) frozen at `period_end + cfg.astha.freeze_after_days` (7) so late syncs after the freeze land in `agg_restatement_log`, not in the paid-out numbers.

### G-analyst-14 — One trusted timestamp; `hour_of_day` and `business_date_server` basis (minor)

**Hits:** Q8, Q20; every hourly chart; DQ-10 false positives on skewed phones. Fix: `app.*` `@PROV` adds `captured_at_trusted timestamptz` (server-computed at ingest per lens-security §5.4, falling back to `captured_at + clock_offset_ms` per lens-sync §3.4, then to `captured_at`, with `time_basis text ∈ {anchor, offset, device}`); `business_date_server` is GENERATED from `captured_at_trusted`, not `captured_at`; every `dw` fact stores `*_trusted` timestamps and `hour_of_day = EXTRACT(hour FROM captured_at_trusted AT TIME ZONE 'Asia/Dhaka')`; `fact_attendance.check_in_local` likewise; `memo.printed_at` stored with `print_elapsed_ms` so `time_to_print_s` is clock-immune. The merged plan must state which of the two lenses' formulas is primary (recommend lens-security's anchor with lens-sync's offset as fallback) — a one-line decision, D-analyst-01.

### G-analyst-15 — Printed due snapshot not stored on the memo (minor)

**Hits:** Q22; docs/11 "retailers will dispute anything wrong". Fix (M-51 app): `app.memo.outstanding_before_mtk bigint`, `printed_total_due_mtk bigint` (device values, verbatim); ingest computes `server_balance_at_print_mtk` from `fact_due_ledger` as of `captured_at_trusted` and flags `due_snapshot_mismatch` when they differ by > `cfg.credit.snapshot_tolerance_mtk` (0); both reach `fact_memo` (G-analyst-01). The memo template (lens-sync §6.2) already prints these two numbers; storing what was printed costs 16 bytes per memo.

### G-analyst-16 — Login provenance: `offline_start`, stale bundle, and sync-failure telemetry absent from `agg_daily_route` (minor)

**Hits:** Q15; Login % truthfulness on outage days (G-sync-07). Fix: `agg_daily_route.offline_start boolean`, `bundle_version_at_open text`, `bundle_stale boolean`, `login_source text ∈ {bundle, day_open_offline}`; `agg_daily_zone.offline_start_routes int`; `dw.fact_device_day` (lens-quality §4.3 telemetry: `sync_attempts`, `sync_failures{reason}`, `pending_rows`, `battery_drop_pct`) is the `dw` home for device-side failures the server never saw — it is in lens-quality but not in lens-data's §4 table list; add it to the `dw` inventory so outage analysis stays inside `dw`.

### 2.1 Gap summary

| ID | Severity | Title | Questions | Fix slot |
| --- | --- | --- | --- | --- |
| G-analyst-01 | blocker | No memo-grain fact (`dw.fact_memo`) | Q7, Q8, Q9, Q22 | M-60 |
| G-analyst-02 | blocker | Aggregates current-state only: no `dim_target`, no as-reported snapshot, no restatement log | Q6, Q9, Q18, Q24, Q25 | M-61 |
| G-analyst-03 | major | No `agg_daily_user`; `acting_user_id` single-valued; visit `kind` filter undefined | Q2, Q3, Q19 | M-62 |
| G-analyst-04 | major | Supervisor (AMO/TSO/DMO) assignment has no history | Q5 | M-46, M-62 |
| G-analyst-05 | major | Dues: no allocation, no ageing, opening balances unaged | Q4, Q12 | M-47, M-63 |
| G-analyst-06 | major | Price history / list price / reporting value absent from `dw` | Q1, Q11, Q13 | M-64 |
| G-analyst-07 | major | DQ and plausibility flags not in `dw` beyond geo | Q11, Q21, Q9, Q15 | M-65 |
| G-analyst-08 | major | Offer scope and memo-level offers absent from `dw` | Q13 | M-48, M-66 |
| G-analyst-09 | major | Migrated history: no provenance/fidelity; aggregate-only dump has no landing path | Q24 | importer path B; `source`/`fidelity` columns |
| G-analyst-10 | major | `is_planned`/`target_outlets`/Login % ignore working-day calendar and route-day overrides | Q2, Q10, Q15 | D-09 amendment |
| G-analyst-11 | minor | `fact_visit.outcome`, `app_version` on facts, `fact_config_change.is_revert_of` | Q9, Q19, Q23 | M-49 |
| G-analyst-12 | minor | Outlet merge model; `phone_hash_pepper_version` | Q16 | M-50, M-66 |
| G-analyst-13 | minor | Programme tier attribution; loyalty reversal on supersede; programme freeze | Q14, Q25 | cfg + M-61 |
| G-analyst-14 | minor | One trusted timestamp; `hour_of_day`/`business_date_server` basis | Q8, Q20 | `@PROV` amendment, D-analyst-01 |
| G-analyst-15 | minor | Printed due snapshot not stored on memo | Q22 | M-51 |
| G-analyst-16 | minor | Login provenance (`offline_start`, stale bundle); device-day telemetry in `dw` inventory | Q15 | `agg_daily_route` columns |

---

## 3. Cross-lens reconciliation items this critique surfaced

| # | Lenses | Disagreement | Proposed resolution |
| --- | --- | --- | --- |
| X1 | lens-sync §3.4 vs lens-security §5.4 vs lens-data `@PROV` | Three definitions of the capture timestamp used for `business_date`: offset-corrected device time; anchor + monotonic elapsed; raw `captured_at` in the generated `business_date_server`. | D-analyst-01: `captured_at_trusted` = anchor method when `boot_id` matches, else offset method, else device; `business_date` and `business_date_server` both from it; the flag `business_date_mismatch` then means a real disagreement (G-analyst-14). |
| X2 | lens-config §3.2 vs lens-data M-06 | lens-config says `memo` stores `rounding_mode_used`, `price_list_version`, `promo_rule_version`; M-06 has `price_list_date` and `config_version` only. | Add `rounding_mode_used text`, `offer_version_set jsonb` to `app.memo` (M-48); `price_list_date` stays. |
| X3 | lens-features F-AMO-036 vs lens-data D-05/D-09 | AMO visits must not inflate SR CPR; the aggregate definitions never mention `kind`. | Written into D-05/D-09 by G-analyst-03. |
| X4 | lens-sync G-sync-07 / §4.5 vs lens-data `agg_daily_route` | `day_open{online:false}` and `offline_start` exist in `route_day` but not in the aggregate. | G-analyst-16. |
| X5 | lens-quality §4.3 `dw.fact_device_day` vs lens-data §4.1 inventory | The telemetry fact is defined by one lens and absent from the other's `dw` list. | Add to the `dw` inventory (G-analyst-16). |
| X6 | lens-security FS-06 (points server-computed) vs lens-data `loyalty_ledger.source_type` | No reversal kind for superseded memos. | G-analyst-13. |
| X7 | docs/22 (aggregate sample) vs lens-data M-42 (memo-level import) | Importer has one path; the known sample does not fit it. | G-analyst-09 path B. |

---

## 4. Test gates (90–99 range) and phase placement

| Gate | Phase | Test | Pass criterion |
| --- | --- | --- | --- |
| T-0-90 | 0 | `dw` inventory completeness | every table in §2 exists with `source`/`fidelity` columns; `dw` README states the trusted-timestamp rule (D-analyst-01) and the `kind` filters of D-05/D-09 |
| T-1-90 | 1 | Memo fact round trip | a printed memo appears in `fact_memo` with `printed_at`, `time_to_print_s`, `outstanding_before_mtk`; an edit produces a second row with `supersedes_memo_id` and `edit_net_reduction` equal to the fixture |
| T-1-91 | 1 | Restatement log | a D−2 batch changes `agg_daily_route_sku` for D−2 and writes exactly the changed rows to `agg_restatement_log` with `reason='late_batch'`; today's rows untouched |
| T-1-92 | 1 | Business date / hour basis | a sale at 23:55 Dhaka on a phone 40 min fast lands on D with `hour_of_day = 23`; `business_date_mismatch` is **not** raised; the same sale on a phone 40 min slow (device says 23:15) lands on D |
| T-2-90 | 2 | User-day aggregate | regular SR and substitute on one route on one day → two `agg_daily_user` rows whose `successful_calls` sum to `agg_daily_route.successful_calls`; AMO control-call sale on the route is in `amo_successful_calls` only |
| T-2-91 | 2 | Dues ageing | fuzzed collections with and without `against_memo_id` → `agg_memo_due_open` buckets equal a reference FIFO model; opening balances aged from `age_basis_date`; "as of D" reproducible from `agg_outlet_due_ageing_daily` |
| T-2-92 | 2 | DQ flags in `dw` | every DQ-NN rule fired in the ingest fuzz has a matching `fact_dq_flag` row; counts per flag equal `sync_rejected` + flagged rows |
| T-2-93 | 2 | Price/offer provenance | a price change at 11:00 → lines before/after carry the right `list_price_mtk`; a memo on a stale bundle is `bundle_stale=true` with `price_mismatch` where applicable; `fact_memo_offer` sums equal `memo.discount_mtk − Σ line discounts` |
| T-3-90 | 3 | Supervisor history | AMO moved between zones on the 15th → "by AMO" report for the month attributes days 1–14 and 15–31 to different users; `dim_supervisor_assignment` has two rows |
| T-4-90 | 4 | As-reported snapshot | national dashboard value for month M on `as_of_date` d equals `snap_month_zone_product` summed; a target revision on d+8 changes the live row and leaves the snapshot intact; the restatement log names the revision |
| T-4-91 | 4 | Planned-day calendar | on a configured holiday `target_outlets = 0`, Login % renders "—", `is_planned=false`; a route-day override re-plans one route and only that route |
| T-4-92 | 4 | All 25 questions as `dw`-only queries | each Q1–Q25 has a saved SQL under `/db/dw/questions/` that runs under the `bi_reader` role (no `app` grants) against the staged fuzz day and returns the fixture answer |
| T-5-90 | 5 | Programme attribution | an outlet whose tier changes mid-quarter appears once in the quarter report under the configured attribution; superseding a point-earning memo reverses and re-earns; the frozen quarter snapshot is unchanged by a late sync |
| T-7-90 | 7 | Migrated history provenance | months loaded via path B carry `source='migration_aggregate'`, `fidelity=2`; a route-level report over those months is refused (`min_fidelity`) and a wing-level YoY renders with the hatched marker; `dw.reconcile()` skips them |

Phase placement: G-analyst-01, -02, -14 are Phase 0/1 (they shape `fact_memo`, the worker's write path and the timestamp rule — retrofitting a restatement log later loses the history it is meant to keep); G-analyst-03, -05, -06, -07, -08, -10, -15 are Phase 2 (the full SR day is when edits, dues, prices, offers and flags first exist in volume); G-analyst-04, -11, -16 Phase 3; G-analyst-12, -13 Phase 5; G-analyst-09 Phase 7a (importer) but its `source`/`fidelity` columns are Phase 0.

## 5. What this critique does not change

The `dw` design's core choices stand and are the reason 9 of 25 questions already pass: capture-time context on every row (route, assignment, radius, config version, price-list date), SCD2 dimensions with the fact storing the key valid on the business date, recompute-by-dirty-key (so late data, duplicates and supersedes converge without `+=`), balances as-of-date via change-day rows, and the PII split (`dim_outlet_pii`, `phone_hash`, export log). The 16 gaps are additions on that spine — one memo fact, one snapshot/log layer, one user-day aggregate, five "mirror what `app` already knows into `dw`" items, and a handful of columns — not a redesign.
