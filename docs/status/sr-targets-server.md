# T-KPI1 — SR targets and KPI server lane (`lane/sr-targets-server`)

## HANDOVER (read first; updated each push)

- **State 2026-11-18:** slice N-2 pushed (no migration). `SrTargets` (backend/sync) fills `targets`, `achievement_mtd`, `calendar`; `SkuProgress` in shared/rules; contract 1.22.0 provisional (`docs/requests/contract-sr-targets.md`); D-TKPI-01..04. Local: shared:rules, shared:contract, backend:sync, `SrTargetsBundleTest` green (PG16 via ARON_TEST_PG_URL). Not run locally: full backend:app (CI), oasdiff, redocly.
- **Next:** N-5, then N-8, then N-3 (migrations from V0230 provisional).
- **Provisional numbers:** contract 1.22.0 (lead gives the final), migrations from V0230 (lead renumbers; they must not run outside lane CI).
- **Source branch read for the needs:** `docs/requests/sr-programmes-server-needs.md` on `lane/android-sr-programmes` (items N-2, N-3, N-5, N-8; N-1, N-4, N-6, N-7 are the Loyalty lane's).

## 0. What already exists (read from the integration branch, 39db6c73)

| Piece | Where | Use here |
|---|---|---|
| Route x SKU x month target in base units | `app.route_monthly_target` (V0096), admin page `web/src/app/admin/route-targets`, `/v1/admin/route-targets` | It already **is** per SKU (qty only). N-2 needs no new target table. |
| Working-day calendar, Friday off, holidays and make-up days as data, admin page "Calendar" (`app.calendar_holiday`, revoke = versioning) | `dw.v_calendar`, `dw.v_calendar_month` (V0097); `WorkingCalendarRepo` (node-level calendars) | The "versioned reference table with an admin GUI" asked for already exists: reused, not duplicated. National calendar for the SR's route node. |
| Achievement per route, SKU, day | `dw.agg_daily_route_sku` (sold_qty_base, memo_count), `dw.agg_daily_route_brand`, `dw.agg_daily_outlet_sku`, `dw.fact_memo` | Month-to-date reads. No new aggregate for N-2 and N-5 volume. |
| Per-outlet brand Astha target | `app.astha_target` | A different programme; not reused for N-3. |
| Programme master, enrolment (outlet, tier_code, base_target), `rules` jsonb | `app.programme` kinds `campaign`, `superstar`; `app.programme_enrolment` | N-3 campaign membership and tier label; N-8 slabs and criteria. |
| Ratio rules | `shared/rules` `Targets` (`tillDate` half-up in integers), `Percent.bp` | Formulas below are added here as pure functions so the server tests and the Android phone share one implementation. |
| Bundle sections | `RouteSnapshot.targets` (`TargetRow`) and `achievement_mtd` (`AchievementRow`), kept empty by `BundleService.kt:521` | N-2 fills them (no new contract member for N-2 except the calendar block). |

## 1. Common rules (all four items)

- **Scope:** the bundle is built for the token's user; targets and achievement come only from the routes of that user's assignment (`RouteSnapshot` is per assigned route). No scope id is accepted from the client. Every new read gets a scope test (SR A cannot read SR B's route; AMO/TSO bundles keep their own shape).
- **Units:** storage and every wire quantity in base units (sticks, pieces, dozens) as integers or `Decimal3` strings; money in integer milli-taka. No float anywhere. The phone converts to packets/dozens/boxes for display (rule 5).
- **Dhaka business date:** every day-level figure keys off `business_date`; month = the Dhaka month of the bundle's `valid_for_business_date` D.
- **No double count (the `as_of` rule).** Every server achievement figure is defined as: *memos accepted by the server with `business_date` in [first of month, `through_date`] and projected into the aggregate at or before `as_of`*, with `through_date = D - 1` (the last closed day before the bundle date). **Today's memos are never in a server figure.** The phone adds (a) every local memo with `business_date >= D` and (b) every older local memo that the server has not acknowledged, de-duplicated by memo UUID. The server sends `through_date` and `as_of` with each figure so the phone can apply the rule. A memo acked after `as_of` but dated before D is counted by the phone because it is not acknowledged in the bundle snapshot (rule stated in the request file; the phone lane owns the local half).
  - Limit stated honestly: the aggregate is a worker projection, so a memo accepted a few seconds before `as_of` may not be in the figure yet; `as_of` is the projector watermark time (`dw.summary_freshness`), not the request time, so the figure never claims more than it holds.
- **Compute where:** bundle build reads the aggregates (indexed month reads on `(route_id, business_date)`); no new job and no cadence change. N-8's last-month sales are read from the aggregate of last month (closed, immutable except for late back-dated memos, which the next bundle picks up).
- **Calendar block (shared by N-2, N-5, N-8):**
  `calendar: { month, working_days, elapsed_working_days_before_today, remaining_working_days_after_today, today_is_working, last_month_working_days, through_date, as_of }`.
  `elapsed_..._before_today` = working days from the 1st to D-1; `remaining_..._after_today` = working days from D+1 to month end; `today_is_working` = D is a working day. From these the phone can derive any of the day counts (see owner question Q-A), and the server uses the same numbers.
- **Exact decimal rules:** `ratio(num, den, scale)` = half-up rounding of the exact rational `num/den` computed in `BigInteger`; den = 0 gives null (never 0, never infinity). Percent = `bp` = round-half-up of `num x 10000 / den` (integer basis points), shown with two decimals. Rounding happens once, at the final figure, never on an intermediate (except where a formula says an input is a rounded displayed value; none does).
- **Admin GUI (rule 4):** every new setting has an admin portal page or tab (see each item). Uploads follow the existing route-target pattern (batch uuid, content hash, replay or conflict, audit).
- **Pilot and fleet size:** pilot 5 to 10 SRs: a handful of rows per table and one month read of at most 31 x SKUs rows per route; fleet 8,500 SRs x 1 route each: the bundle reads at most 31 x ~60 SKU-day rows per route from an indexed table, built once nightly at 22:00/03:30 with the existing snapshot jobs. No new Azure resource, no new job.

## 2. N-2 — per-SKU monthly target, calendar numbers, month-to-date achievement (screen 10, Home target card)

**Source of the target.** `app.route_monthly_target` as is (admin: existing "Route targets" upload page; the lane adds no new setting). The SR's targets = rows of the route(s) in the bundle for month(D). If an SR has two routes in the bundle (cover), each `RouteSnapshot` carries its own rows; the phone sums for the Home card.

**Bundle (additive, optional for old phones).**
- `RouteSnapshot.targets[]` = existing `TargetRow` shape: `scope_type = route`, `scope_id = route_id`, `month`, `product_level = sku`, `product_id = sku_id`, `std_target` = `qty_base` as `Decimal3` (`"500.000"`), `std_unit` = the SKU's base unit, `memo_target = 0` (no SKU memo target exists; the memo target is N-5's brand table). Max 500 rows (already the schema limit).
- `RouteSnapshot.achievement_mtd[]` = existing `AchievementRow`: same keys, `achieved_qty_base` = sum of `sold_qty_base` for business dates 1..through_date, `memo_count` = sum of the day rows (a memo with several lines of one SKU counts once for that SKU; the SKU rows are not additive across SKUs, so the Home card memo count comes from `dw.fact_memo`, see N-5 `totals`), `as_of`. Added: optional `through_date` (new member, additive; AchievementRow is closed, so this is a contract addition, 1.22.0).
- New optional `RouteSnapshot.calendar` (above).
- Returns (sale lines only; returns and free goods are excluded exactly as `TargetAchievementService` does: `sold_qty_base`).

**Formulas (K-13, D-58 amended, R-23; implemented in `shared/rules` as `SkuProgress`, used by the server oracle and the phone):**
- Let T = target, A = achieved (server + phone's local), E = elapsed working days, R = remaining working days.
- Remaining = max(T - A, 0) (`Targets.remaining`). Achievement % = bp(A, T), not capped, null when T = 0.
- ADS = A / E; TADS = T / E; RADS = Remaining / R; PADS = A / E (default, Q-B). All round half up to an integer for the screen (screen 10 shows integers). E = `elapsed_working_days_before_today`, R = `remaining_working_days_after_today` (K-13 fixture: April 2026 as of 2026-04-18, E = 14, R = 11).
- **Fixtures (tested):** T = 500 gives TADS 36 and RADS 45; T = 900 gives TADS 64 and RADS 82 (A = 0, E = 14, R = 11). Also E = 0 gives null (day 1), R = 0 gives null (last day).
- Note on the wording "target / working days": the fixtures only hold with the 14 elapsed (not the 26 of the month: 500 / 26 = 19). I implement the D-58 amended / K-13 meaning and state it as Q-A.

**Size:** pilot, a few dozen rows; fleet, up to ~60 SKUs per route x 2 members, about 6 KB uncompressed per route, `Decimal3` strings dominate.

## 3. N-5 — brand-wise KPI and KPI dashboard (screens 60, 61)

**Needs a new memo target (volume target already exists).**

| New table (V0230+) | Columns | Why |
|---|---|---|
| `app.route_brand_kpi_target` | `route_id, brand_id (product_node level brand), month, memo_target int >= 0, kpi_alloc_mtk bigint >= 0 (the brand's allocated KPI in milli-taka, default 0), batch_uuid, created_at, updated_at, updated_by` PK `(route_id, brand_id, month)` | Screen 61 memo target per brand (119, 146, ...) and the allocated KPI. |
| `app.route_kpi_month` | `route_id, month, kpi_alloc_mtk bigint, cpr_target_bp int, cpr_alloc_mtk bigint, batch_uuid, ...` PK `(route_id, month)` | Month card "allocated KPI 5,700" and the CPR block. Optional row. |
| `app.kpi_target_batch` | like `route_target_batch` | Idempotent upload record. |

- **Volume target per brand** = sum of `route_monthly_target.qty_base` of the brand's SKUs (D-TARGET-MIN-01 source; no second volume table, so volume can never disagree with screen 10). Q-C: the screenshot's brand volume targets (10,800 / 16,000 / 47,000) cannot be checked against SKU targets; default stands until the owner confirms they are the same numbers.
- **Admin GUI:** new tab "KPI targets" on the admin Route targets page: Excel/CSV upload and per-route edit of the two tables (same batch idempotency as `/v1/admin/route-targets`; new endpoints `PUT/GET /v1/admin/route-kpi-targets`, permission = the existing route-target grant, scope = the admin's reach). Template download included.
- **Achievement:** volume = `dw.agg_daily_route_brand.sold_qty_base` 1..through_date; memo count = `dw.agg_daily_route_brand.memo_count` (BSR: memos with at least one line of the brand, counted once per memo; already defined in V0011). Totals (screen 60 "Memo Target vs Achievement 397 of 1,102") = distinct memos of the route: `dw.fact_memo` count for the month (not the sum of brand rows).
- **Percentages:** `volume_bp = bp(volume_achieved, volume_target)`, `memo_bp = bp(memo_achieved, memo_target)`, null when the target is 0 (screen 61 shows a dash there; "—" = no target; that answers owner question on the dash with the reading "no target", flagged Q-D).
- **BLM rows** (screen 60 third section: Match, Lighter): per category (`match`, `lighter`) volume target and achievement from `route_monthly_target` joined to `app.sku.category_code`, volume only. Q-E: what BLM stands for.
- **Bundle:** new optional `RouteSnapshot.kpi` object: `{ month, through_date, as_of, allocated_mtk, achieved_mtk (null, see Q-F), achievement_bp (null), days: {elapsed, total}, volume: {target, achieved, bp}, memo: {target, achieved, bp}, brands: [{brand_id, volume_target, volume_achieved, volume_bp, memo_target, memo_achieved, memo_bp, kpi_today_mtk (null)}], categories: [{category_code, volume_target, volume_achieved, volume_bp}], cpr: {target_bp, achieved_bp} | null }`. Brand names are resolved on the phone from the `products` section (N-6 is Loyalty's). Cap 200 brands; truncated flag if exceeded (never a silent cut).
- **Defaults not invented:** `achieved_mtk`, `achievement_bp` (month card 27.00%) and `kpi_today_mtk` are **null** until the owner gives the formula (Q-F); the phone hides them. Allocated KPI is shown (admin-entered). CPR achieved = successful calls / target calls from `dw.agg_daily_route` month-to-date if the columns exist, else null (Q-G, shipped behind a null if the definition is not confirmed in slice 2).
- **Size:** brands per route ~10 to 60 rows, < 5 KB. Fleet identical per route.

## 4. N-8 — Match Box Lifting Star snapshot (screen 62)

**Formulas, proven against the screenshot figures** (target 20, STD 2.833, EWD 10, WD 26, LMS 12.25, LMWD 26, shown 2.83 / 0.28 / 7.37 / 36.83% / 0.47 / -39.87%):
- PADS = STD / EWD; Projection = PADS x WD; Achievement = Projection / Target (a percent; the screenshot's 36.83% = 7.366 / 20; the printed 7.37 is rounded); LMADS = LMS / LMWD; Growth = PADS / LMADS - 1 (-39.87% reproduces from the unrounded values: 0.28333 / 0.47115 - 1 = -39.87%).
- **Exact rules:** computed in exact rationals from the integer base quantities; displayed as `Decimal3` strings (two decimals shown by the phone); percent in bp. Projection is rounded half up to 3 decimals once at the end. Test: the screenshot inputs (STD 2.833 as 2833 milli-units, EWD 10, WD 26, Target 20.000, LMS 12.250, LMWD 26) reproduce 36.83% and -39.87% exactly, and the displayed values at two decimals.

**Sources:**
- Programme: `app.programme` kind `superstar`, active for the month (the owner's "Match Box Lifting Star"); an SR sees it only when the programme is active in their reach (existing `programmes` section rule and flag `cfg.flag.superstar_ui`).
- Target: **new table** `app.route_programme_target (programme_id, route_id, month, target_qty numeric(16,3), batch_uuid, ...)` PK `(programme_id, route_id, month)`; the unit is base units of the programme SKUs divided by `rules.unit_divisor` (default 1). Admin GUI: tab "Programme targets" on the Loyalty/Programmes admin page (extends the existing programme admin), upload and edit, same batch pattern.
- STD (achieved to date) = sum of `sold_qty_base` of the programme's SKUs (`rules.sku_category`, default `match`, or an explicit `rules.sku_ids`) 1..through_date / `unit_divisor`.
- LMS = same over last month; LMWD = working days of last month; WD = working days of the month; EWD = `elapsed_working_days_before_today` (Q-A).
- Status text: rule table in `programme.rules.status_rules` (list of `{max_projection_bp, text_en, text_bn}`), default: projection >= 100% "On Track", else "Keep Pushing" (the screenshot's text for 36.83%).
- Slabs: `programme.rules.slabs` = `[{min_bp, amount_mtk}]` ascending; Achieved Slab = the highest slab with `min_bp <=` **actual** achievement (STD / Target); Slab Amount = its `amount_mtk`; Projected Amount = the amount of the highest slab with `min_bp <=` projection achievement. No slab reached gives null (shown as "-" and "- Tk"). Money is milli-taka. The amounts are **shown, never applied to a memo** (discount/promotion on the memo stays deferred).
- **Bundle:** new optional `RouteSnapshot.star` (or a `programmes.star` member; the final place is the contract request): `{ programme_id, month, through_date, as_of, sr_name, sr_code, route_name, lmwd, wd, ewd, target, std, projection, achievement_bp, lms, lmads, pads, growth_bp, status_en, status_bn, achieved_slab, slab_amount_mtk, projected_amount_mtk }`, all numerics `Decimal3` strings except bp and money integers. The phone may recompute ratios on top of local memos using `shared/rules` `StarProgress` (same functions); the server snapshot is the oracle.
- **Size:** one object per SR, about 600 bytes.

## 5. N-3 — per-outlet per-SKU campaign target and sales (screen 21)

**Sources (new tables, V0230+):**

| Table | Columns | Why |
|---|---|---|
| `app.outlet_campaign_target` | `programme_id (kind campaign), month, outlet_id, std_target numeric(16,3), memo_target int, batch_uuid, ...` PK `(programme_id, month, outlet_id)` | Pop-up rows Target STD and Target Memo. |
| `app.outlet_campaign_sku_target` | `programme_id, month, outlet_id, sku_id, qty_base bigint, batch_uuid` PK `(programme_id, month, outlet_id, sku_id)` | The SKU table "টার্গেট" column. |
| existing `app.programme_enrolment` | `outlet_id, programme_id, tier_code, valid range` | Which campaign and tier ("Super Star Low (November)") the outlet is in; label from `programme.name_*` and `period_label`. |

- **Admin GUI:** tab "Outlet campaign targets" on the programmes admin page: upload (CSV/Excel: outlet code, SKU code, qty; STD and memo target) and per-outlet edit; same batch idempotency; the template is downloadable.
- **Achievement:** per outlet and SKU: `dw.agg_daily_outlet_sku.sold_qty_base` 1..through_date; STD achieved = sum of the programme SKUs' base quantity (Q-H: STD unit, default base units, no `report_factor` conversion); memo achieved = distinct memos of the outlet with a line of a programme SKU, from `dw.fact_memo_line` 1..through_date (not a sum of SKU memo counts); remaining = `Targets.remaining`. Category totals (cigarette, lighter, match) are sums of the SKU rows; their percent = bp(sales, target) from the sums, never a mean of row percents (the screenshot's 657.14% = 920 / 140).
- **Bundle:** new optional `BundleOutlet.campaign` (outlet row) or a sibling `RouteSnapshot.campaigns[]` keyed by outlet (final shape in the request file; the choice favours a sibling list so the outlet row stays small): `{ outlet_id, programme_id, label_en, label_bn, month, through_date, as_of, std: {target, achieved, remaining}, memo: {target, achieved, remaining}, skus: [{sku_id, target_qty_base, sold_qty_base}] }`. Present only for outlets enrolled in an active campaign; cap 2,000 outlets x 40 SKUs, with a `truncated` flag.
- **Size:** pilot, a few outlets; fleet, a route of 70 outlets x ~9 SKUs about 630 SKU cells, about 15 KB uncompressed, 3 KB gzipped. Only outlets with a campaign target appear.
- **Slice order:** last; needs the most data and the most owner confirmation (units, how the campaign is assigned).

## 6. Contract, migrations, tests (summary; details in the slices)

- `docs/requests/contract-sr-targets.md` (written with slice N-2, amended per slice): additive and optional members only: `RouteSnapshot.calendar`, `RouteSnapshot.kpi`, `RouteSnapshot.star`, `RouteSnapshot.campaigns`, `AchievementRow.through_date`. Provisional version 1.22.0. oasdiff must show 0 breaking.
- Migrations (provisional V0230+, forward-only, `SET lock_timeout`, NOWAIT retry pattern of recent migrations, role grants through `app.apply_db_role_grants()`, data dictionary comments, `docs/data-dictionary.md` regenerated): `route_brand_kpi_target`, `route_kpi_month`, `kpi_target_batch`, `route_programme_target`, `outlet_campaign_target`, `outlet_campaign_sku_target` (+ batch tables, or reuse one generic `app.target_upload_batch`; decided in slice N-5 and logged D-TKPI-NN), and `dw` stable views for each (`dw.v_route_brand_kpi_target`, ...).
- Tests: the K-13 fixtures (500 -> 36 / 45, 900 -> 64 / 82), the half-up and zero-divisor edge cases, a property test that server MTD plus local de-duplicated sums never exceed the true total, the screen 62 reproduction, outlet-total percent (920 / 140), scope test per read (SR A vs SR B, AMO, TSO untouched), upload idempotency (same batch replays, other content conflicts), migration tests (lock pattern, grants, dictionary), contract drift tests.

## 7. Owner questions (each with the default I build)

| Id | Question | Default built |
|---|---|---|
| Q-A | Definitions of elapsed (EWD, TADS, ADS) and remaining working days: does the day itself count? K-13's April fixture (14 elapsed, 11 remaining) excludes today; screen 62 (Nov 11, EWD 10) includes it. And is TADS "target / elapsed working days" (K-13) or "target / working days of the month"? | K-13 / D-58 amended: elapsed = working days before the bundle date, remaining = working days after it, TADS = target / elapsed (fixtures reproduce only this way). The calendar block sends today_is_working so the other reading is a one-line switch. |
| Q-B | PADS for screen 10: same as ADS (achieved / elapsed)? | PADS = ADS = achieved / elapsed working days. |
| Q-C | Are the brand volume targets of screen 61 the sum of that brand's SKU targets from the route target upload? | Yes; no second volume target. |
| Q-D | Dash on screen 61: "no target" (target 0 or absent)? | Yes; percent null. |
| Q-E | What does BLM stand for, and which categories are in it? | Category rows for match and lighter, volume only. |
| Q-F | Exact definitions of "achieved KPI" (screen 60 month card, 27.00%), the brand "KPI today" taka and the weights of volume and memo in the KPI. | Not computed: `achieved_mtk`, `achievement_bp`, `kpi_today_mtk` are null; only the admin-entered allocated KPI is shown. |
| Q-G | CPR (target and achieved call productivity): source and definition. | Target from the admin month row; achieved = successful calls / target calls month-to-date from `dw.agg_daily_route` when its columns match, else null. |
| Q-H | Unit of STD (screen 21 and 62: base units, report units or a standard pack) and of the Match target (dozens or boxes)? | STD = base units (sticks, pieces, dozens), divided by `programme.rules.unit_divisor` (default 1) for the star. |
| Q-I | Who sets the outlet campaign target (this is upload vs a rule from last month's sales) and are campaign months aligned to calendar months? | Admin upload per outlet and SKU; calendar months. |
| Q-J | Star status texts and slab ladder (Keep Pushing and the next statuses; slab thresholds and taka). | Two statuses (projection under or at least 100%), empty slab ladder (shows "-") until the admin enters `programme.rules`. |
| Q-K | Is a back-dated memo synced after the bundle allowed to change last month's LMS after the month closed? | Yes (the figure is as of the bundle); the next bundle carries the revised LMS. |
