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

## Later slices (not built yet; listed so the numbers can be planned)
N-5 `RouteSnapshot.kpi`, N-8 `RouteSnapshot.star`, N-3 `RouteSnapshot.campaigns`; each additive and optional, written here when built.
