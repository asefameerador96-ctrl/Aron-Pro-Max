# Contract request: SR targets and KPI (lane T-KPI1, provisional version 1.22.0)

Additive and optional for existing phones; the lead gives the final number at landing (integration 1.19.0 after train 117; android-integrity 1.20.0 and Loyalty 1.21.0 are ahead). Design: `docs/status/sr-targets-server.md`. Android reads this file (lane android-sr-programmes).

## Slice N-2 (built): targets, month-to-date achievement, calendar (screen 10, Home target card)

| Where | Change | Notes |
|---|---|---|
| `RouteSnapshot.targets[]` (`TargetRow`) | Now filled (was always `[]`) | One row per SKU of the route for the Dhaka month of `meta.valid_for_business_date`: `scope_type=route`, `scope_id=route_id`, `month="YYYY-MM"`, `product_level=sku`, `product_id=sku_id`, `std_target` = target in the SKU's base unit as `Decimal3` ("500.000"), `std_unit` = the SKU's base unit (stick, piece, dozen), `memo_target=0` (no SKU memo target exists; the memo target is the brand table of N-5). Source `app.route_monthly_target`, uploaded in the existing admin Route targets page. Zone-wide (AMO, TSO) bundles keep `[]`. |
| `RouteSnapshot.achievement_mtd[]` (`AchievementRow`) | Now filled | Same keys, `achieved_qty_base` = sold quantity (sale lines only) of the route and SKU for business dates 1st..`through_date`; `memo_count` = sum of the day rows' memo counts of that SKU (not additive across SKUs); `as_of`. Only SKUs with sales appear; a SKU with a target and no sales is simply absent (achieved 0). |
| `AchievementRow.through_date` | New optional member (`BusinessDate`) | The last day the figure covers = bundle date minus one day. |
| `RouteSnapshot.calendar` | New optional member (`RouteCalendar`, null or absent for zone bundles) | `month`, `working_days`, `elapsed_working_days_before_today`, `remaining_working_days_after_today`, `today_is_working`, `last_month_working_days`, `through_date`, `as_of`. Working days at the route's zone path: Friday (or the division/wing weekend) off, holidays and make-up days as declared in the admin Calendar. |

### The no-double-count rule for the phone

A server figure never contains the bundle date. The phone adds, de-duplicated by memo UUID: every local memo with `business_date > through_date`, and every older local memo the server has not acknowledged at the bundle's snapshot. `as_of` is the time of the aggregate (the projector), not of the request; a memo accepted a moment before `as_of` may not be in the figure yet, so the phone must keep counting a local memo until the server has acknowledged it AND a later bundle's `as_of` covers its acceptance. (Local half is the phone lane's.)

### Formulas (same functions on both sides: `shared/rules` `SkuProgress`)

- Remaining = max(target − achieved, 0). Achievement % = basis points of achieved/target, not capped, null when the target is 0.
- ADS = achieved / E; TADS = target / E; PADS = ADS (owner question Q-B); RADS = remaining / R. Half-up integers, null when the divisor is 0 (never 0).
- E (elapsed) and R (remaining) come from ONE function, `SkuProgress.dayCounts(calendar, convention)`: `BEFORE_TODAY` (default, K-13: 14 and 11 on 2026-04-18) uses `elapsed_working_days_before_today` and `remaining_working_days_after_today`; `THROUGH_TODAY` (screen 62) adds today when `today_is_working`. Lead ruling: both live behind that one named function until the owner answers Q-A; the K-13 and screen 62 fixtures are tests.
- Fixtures: target 500 gives TADS 36 and RADS 45; 900 gives 64 and 82 (E=14, R=11).

## Slice N-5 (built): brand-wise KPI (screens 60 and 61)

| Where | Change | Notes |
|---|---|---|
| `RouteSnapshot.kpi` (`RouteKpi`, optional, null or absent in zone bundles) | New | `month`, `through_date`, `as_of`; `allocated_mtk` (admin-entered month allocation, null when none); `achieved_mtk`, `achievement_bp` (null: the owner has not defined them); `volume` and `memo` totals (`KpiTotal`: `target`, `achieved`, `bp`), which are SUMS of the brand rows (volume: cigarette brands only); `brands[]` (`KpiBrand`: `brand_id`, `volume_target` = sum of the brand's SKU route targets in base units, `volume_achieved`, `volume_bp`, `memo_target`, `memo_achieved` = memos containing the brand once per memo, `memo_bp`, `kpi_alloc_mtk`, `kpi_today_mtk` null) up to 200 with `brands_truncated`; `categories[]` (match, lighter: volume target, achieved, bp: the BLM rows); `cpr` (`target_bp`, `alloc_mtk`, `achieved_bp` null) or null. Percentages are integer basis points, null when the target is 0 (the dash). Brand names come from the bundle `products` section by `brand_id`. |
| `PUT /v1/admin/route-kpi-targets` (`putRouteKpiTargets`) | New admin operation | Validate (dry_run) or commit `brand_rows` (route_code, brand_code, month, memo_target, kpi_alloc_mtk) and `month_rows` (route_code, month, kpi_alloc_mtk, cpr_target_bp, cpr_alloc_mtk); all or none; idempotent by `batch_uuid` (shared record with `putRouteTargets`); audited `route_kpi_target`. |
| `GET /v1/admin/route-kpi-targets` (`listRouteKpiTargets`) | New admin operation | One route's rows of a month (`route_code` required). |

Same no-double-count rule as N-2: figures are through `through_date` (the bundle date minus one day); the phone adds today's memos by UUID.

## Slice N-8 (built): Match Box Lifting Star (screen 62)

| Where | Change | Notes |
|---|---|---|
| `RouteSnapshot.star` (`RouteStar`, optional, absent or null when the route has no target for an active superstar programme) | New | `programme_id`, `programme_code`, `name_en`, `name_bn`, `month`, `through_date`, `as_of`, `sr_name`, `sr_code` (username), `route_name`, `lmwd`, `wd`, `ewd`, `target`, `std`, `projection`, `achievement_bp`, `lms`, `lmads`, `pads` (Decimal3 strings, programme unit), `growth_bp` (signed bp), `status_en`, `status_bn`, `achieved_slab`, `slab_amount_mtk`, `projected_slab`, `projected_amount_mtk`. Nullable where a divisor is 0 or no slab is reached. Money is integer milli-taka and is shown, never applied to a memo. |
| `PUT /v1/admin/route-kpi-targets` | New optional `programme_rows` (route_code, programme_code, month, target_qty Decimal3 string) | Active `superstar` programmes only; the response and the read list carry `programme_rows`. Slabs, status texts, `sku_category`, `sku_ids`, `unit_divisor` live in `app.programme.rules` as flat scalar keys (see D-TKPI-07), edited in the existing programme admin. |

Phone recomputation: the same ratios are `shared/rules` `StarProgress` (PADS = STD/EWD, Projection = PADS x WD, Achievement = Projection/Target, LMADS = LMS/LMWD, Growth = PADS/LMADS - 1); the phone may recompute with its local memos on top of `std` only when it keeps the same exact rules; the server snapshot is the oracle. `std` and `lms` are in the programme unit (base units divided by the programme's unit divisor); the phone must add its local sold base quantity divided by the same divisor, which the snapshot does not send (it is in `programme.rules.unit_divisor`; the phone lane can ask for it as a member if it recomputes).

## Slice N-3 (built): outlet campaign target and sales (screen 21)

| Where | Change | Notes |
|---|---|---|
| `RouteSnapshot.campaigns[]` (`RouteCampaign`, optional, absent when none) | New | One per outlet of the route that has a campaign target for the month in an active `campaign` programme (max 2,000): `outlet_id`, `programme_id`, `label_en`, `label_bn`, `month`, `through_date`, `as_of`, `std` and `memo` (`CampaignFigure`: `target`, `achieved`, `remaining`, integers; STD in base units, the STD target rounded half up) and `skus[]` (`CampaignSku`: `sku_id`, `target_qty_base`, `sold_qty_base`, max 100). The phone builds the category totals (cigarette, lighter, match) and the percent from the SKU rows with the SKU category it already has; percent = basis points of the sums, never a mean of row percents. Memo achieved is the distinct memos of the outlet with a campaign-SKU line. |
| `PUT /v1/admin/route-kpi-targets` | New optional `campaign_rows` (outlet_code, programme_code, month, std_target Decimal3, memo_target) and `campaign_sku_rows` (outlet_code, programme_code, month, sku_code, qty_base) | Active `campaign` programmes only; outlets by code; all or none; same idempotency record. |

Same no-double-count rule: server figures are through `through_date`; the phone adds its own memos of the outlet by UUID.
