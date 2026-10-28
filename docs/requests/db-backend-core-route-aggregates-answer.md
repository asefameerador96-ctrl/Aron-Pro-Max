# db answer: route-keyed dashboard aggregates for route-only TSOs (backend-core #20)

db session 13, 2026-10-28. Lead item 2 of 00:28 UTC; D-BC-DASH-ROUTE-ONLY-01; D-DB-ROUTE-DAY-VIEW-01. Migration **V0122**.

## What db shipped

No new table and **no Aggregator change**: `dw.agg_daily_route` already holds every zone-day measure at route grain
(`dw.agg_daily_zone` is its sum, `Aggregator.rebuildZoneDay`). V0122 adds two stable views (view contract 1.2):

| View | Grain | Use |
|---|---|---|
| `dw.v_route_day` | route x business date | every `agg_daily_route` measure, plus `is_target_route` (`planned AND NOT exception_approved`), `successful_login` (login, download and check-in all set: `DashboardSummaries.successfulLoginSql` on today's schema), `sales_submitted` (day state sales/final submitted AND successful login), and the Final Submit of the zone the route-day was stamped with (`zone_final_submitted`, `zone_final_submitted_at`, `zone_auto_submitted`) |
| `dw.v_route_day_channel` | route x business date x outlet channel at capture | active memos, `successful_calls` (distinct outlets sold to: the summary's by_channel `calls`), gross and net per channel, from `dw.fact_memo` and the channel frozen on `app.memo` (V0042); follows the route that sold, never a later outlet move. Memos with no channel on record form a NULL-channel row |

Both carry `data_origin` and end with `is_effective` (true). `RouteDayViewTest` checks the flags and that the zone rollup's
rules, applied to the route rows, give the expected counts and sums; it does not run `Aggregator.rebuildZoneDay`, so
backend-core #20 should add an analytics test that compares the view's sums over a zone's routes with the rollup's row.

## What backend-core #20 must wire (backend/analytics; db does not touch it)

The 403 for a route-only TSO is thrown in `DashboardService.resolveNode` (`DashboardApi.kt:216`, summary and
geo-validation) and `resolveScopedNode` (`NodeScope.kt:32`, sync-health, login-submit, daily-tracking, flagged-memos).
For a caller whose reach is routes only (`reach.routeIds`, no zone), answer from the caller's route ids instead:

1. **summary** (`DashboardApi.compute`, KPIs from `agg_daily_zone` at :274): sum `dw.v_route_day` over
   `route_id = ANY(:routes)` and the date range, with the zone rollup's rules: target_routes = `count(*) FILTER (WHERE
   is_target_route)`, logged_in_routes = `... AND successful_login`, sales_submitted_routes = `... AND sales_submitted`,
   target_outlets = `sum(target_outlets) FILTER (WHERE is_target_route)`, the other counts and money = plain `sum`.
   Final-submit KPIs (`zt`, `zf` at :272 count zone-days today): for a route-only caller count the zones of its target
   routes, `zt = count(DISTINCT zone_id) FILTER (WHERE is_target_route)` and `zf = count(DISTINCT zone_id) FILTER (WHERE
   is_target_route AND zone_final_submitted)`, so the unit stays zones (the card's meaning); `zone_final_submitted`
   follows `agg_daily_zone.final_submitted` (all target routes final-submitted; the midnight auto-submit sets only
   `zone_auto_submitted`). by_channel (:322, today `agg_daily_outlet` by the outlet's current route and current channel):
   read `dw.v_route_day_channel` (`calls` = `successful_calls`); note the difference: the route path uses the channel
   at capture, and a NULL-channel row may appear. The SKU, segment and brand splits already have `route_id`
   (`agg_daily_route_sku/_segment/_brand`): filter them by `route_id = ANY(:routes)` instead of `dim_geo.zone_id`.
2. **geo-validation** (`computeGeo`): totals from `dw.v_route_day` (visits, geo_valid_visits, completed_calls,
   completed_geo_valid_calls, force_sale_visits, mock_visits, suspicious_visits); `dw.fact_visit` and `app.risk_signal`
   filtered by `route_id` (risk signals with no route stay out of a route-only view: say so in the response's note).
3. **login-submit** (`OpsApi.loginSubmit`): the route-day list already reads `app.route_day` + `agg_daily_route`: filter by
   `route_id = ANY(:routes)`; the `zones[]` final-submit entries come from `v_route_day` grouped by `zone_id` with
   `HAVING bool_or(is_target_route)` (today's `target_routes > 0`, :241), only zones of the caller's routes.
4. **sync-health** (`OpsApi.syncHealth`, `healthExtras`): route_day, sync_rejected, sync_quarantine and fact_memo p95
   already have `route_id`; the route counts from `agg_daily_zone` (:180) come from `v_route_day` as in 1. The response's
   `by_zone` stays zone-shaped (one entry per zone of the caller's routes, counting only those routes); a `by_route` shape
   would be a contract change for the lead.
5. **daily-tracking** and **flagged-memos**: no new data needed; filter `dw.agg_daily_route`, `agg_daily_route_sku`,
   `dw.fact_memo` (DailyTrackingApi.kt) and `dw.v_memo_flags` (FlaggedMemos.kt:42) by `route_id = ANY(:routes)`
   (v_memo_flags rows with a null route stay out).

Scope rule (constraint 4): the route ids come from the caller's token reach on the server, never from the client;
the stamped `zone_id` on these rows is not used to decide reach for a route-only caller.

## Not in this slice

- `suspicious_user_days` has no route grain (a user-day spans routes); none of the six endpoints reads it.
- `dw.agg_hourly_zone` has no route twin; none of the six endpoints reads it. If a route hourly view is wanted later,
  db can add it from `dw.fact_visit`/`dw.fact_memo` (both carry `route_id`).
- Labels (route, zone names) are resolved by the API as today.
