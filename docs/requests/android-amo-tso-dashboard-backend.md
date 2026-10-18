> **lead-backend, 2026-10-17 (lane/lead-backend):** done except item 5.
> 1. `by_segment` is sent: a new route-day rollup `dw.agg_daily_route_segment` (the table existed, nothing filled it) is
>    rebuilt with the brand one (each memo once per segment, sale lines only), and the summary returns net, qty, memos,
>    calls and the memo ratio per segment, scoped like every other section.
> 2. `by_category` now carries `report_unit` and `qty_report` when every SKU of the group has the same report unit
>    (exact sum of qty_base / report_factor, rounded once to 3 decimals; mixed or missing units send null). `net_mtk`
>    stays the sale-line value, on purpose: memo-level QC settlements and paisa rounding are not attributable to a SKU
>    (free, DRP and offer lines are already offset); the same basis as `by_brand`/`by_segment`. The memo-level net is the
>    KPI `net_mtk`.
> 3. `RouteDayState` gained optional `assigned_username` and `assigned_user_full_name` (additive contract change),
>    filled by `GET /v1/dashboards/login-submit`.
> 4. A cover route outside the zones is never the dashboard node: the default node is the first geo top node; a reach of
>    cover routes only is a clean 403 (summary and geo-validation), with a test.
> 5. Not done here: a TSO outlet section in the bundle is a sizing/scope decision for the lead (about 2,500 outlets per
>    TSO; today only the AMO carries zone outlets). The dashboard aggregates online with kept copies stay the TSO path.

# Request: backend gaps behind the TSO dashboard (from android-amo, doing the F-TSO-027/002..007 block, 2026-10-18)

To: backend analytics / sync owners. Found by the Opus checker reading `backend/`:
1. **`by_segment` is never sent** (`DashboardSummary` DTO in `backend/analytics/.../DashboardApi.kt` has no such field): F-TSO-005 (By Segment Value Contribution) always shows "No sales yet". Please add it to `compute()` (net_mtk and qty_base per segment, e.g. from `dw.agg_daily_route_sku` joined to the product dimension). The app parses it already.
2. **`by_category` carries no `report_unit`/`qty_report`** and its `net_mtk` holds gross. The app shows lighter in boxes and matches in dozens only when `report_unit` and `qty_report` arrive; until then it shows base units.
3. **Route lists name no SR**: `not_logged_in` / `logged_in_not_submitted` rows have `route_id`, `route_code`, `route_name`, `assigned_user_id` but no SR name, so F-TSO-007 shows the route only. Please add optional `assigned_user_full_name` (and username) to `RouteDayState`.
4. **500 for a TSO with a cover route outside its zones**: `resolveNode` (DashboardApi.kt ~205) can return level "route" (reach nodes sort alphabetically, "route" before "territory") and `nodeScope` then throws on `LEVELS.getValue("route")`. Pick the highest geo level and ignore route nodes; add a test.
5. **F-TSO-027 login snapshot**: the TSO bundle carries `supervisor` (routes, team) but no outlets (BundleService.kt ~146 only the AMO gets zone outlets), so the "about 2,500 outlets, under 1 MB gzipped" snapshot and an outlet picker do not exist. The app reads the dashboard aggregates online with kept copies. Lead: re-scope the row or ask backend for a TSO outlet section.
