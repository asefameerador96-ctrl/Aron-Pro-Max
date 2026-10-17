# Request (web-dashboard to lead-backend / backend-reports): fields the drill-downs still need

Filed 2026-10-17. The drill-downs are built on what the contract gives today and say so on screen where a split is missing.

1. `DashboardSummary.by_brand` split by category (or `GET /v1/dashboards/summary?category=<code>`): the category card drills to routes and SRs, but its brand/SKU table can only show brands of the whole scope. Need: `by_brand[]` rows per `category_code`, or the `category` filter. Failing contract test: summary with `category=bidi` returns only bidi brands and `sum(net_mtk)` equals the `by_category` bidi row.
2. Calls per route and channel (`DailyTrackingRow.by_channel[]` with `code`, `successful_calls`, `net_mtk`): the channel slice drill-down shows all routes of the scope with a note instead of that channel's routes. Failing test: sum over routes of a channel equals `summary.by_channel[code].successful_calls`.
3. SR names in `RouteDayState` (login-submit lists): today the web joins `assigned_user_id` through the daily-tracking rows; a `assigned_user_name` would drop the join.
4. A cover route outside the caller's zones answers 500 (lead-backend is on it).
5. `daily-tracking` has no node filter: the web narrows by `zone_id` after reading every row; `level`/`node_id` query parameters would cut the payload.
