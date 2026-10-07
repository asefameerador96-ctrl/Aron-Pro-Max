# backend-reports request: indexes and outbox events (to db lane and backend-core)

From F-SYS-015's Opus checker (2026-10-07). None blocks the row; all matter at 8,500 users.

## db lane (new forward-only migration)
1. `app.due_collection (route_id, business_date)` and `app.stock_movement (route_id, business_date)`: every route-day rebuild reads both by route and date.
2. `app.day_exception (status, from_date, to_date)` partial on `status = 'approved'`, or a GIN on `route_ids`: the worker asks "is this route excused on this date" on every rebuild.
3. `dw.agg_hourly_zone` is rebuilt from `dw.fact_visit` / `dw.fact_memo` by `zone_id` and `business_date`: add `(zone_id, business_date)` on both facts.
4. `dw.agg_daily_route_segment (business_date, route_id, segment_id, memo_count, sold_qty_base, gross_mtk)`: the contract's `by_segment` needs memos counted once per segment; it cannot be derived from the brand or SKU aggregates. `by_segment` is omitted from `GET /v1/dashboards/summary` until this exists.

## backend-core (ingest)
The aggregation worker reads `app.domain_event` and finds the route-day from `payload.route_id`, else from `source_client_uuid` (a memo, visit, due or stock row, or the void / close / supersede record that points at one), else from `payload.user_id` (every route that user visited that day). Please make sure ingest writes in the same transaction as the record:
`memo.created`, `memo.voided`, `memo.superseded`, `visit.opened`, `visit.closed`, `due.collected`, `stock.moved`, `route_day.state_changed` (aggregate_id = route_day id),
plus events for things that change a route-day's figures without a new record: `day_exception.decided` (payload `route_ids`: one event per route), `risk_signal.changed` (payload `user_id`, on create and on review).
