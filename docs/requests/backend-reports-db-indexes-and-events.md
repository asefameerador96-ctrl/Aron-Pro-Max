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

## Event catalogue (app.domain_event_type, V0017) and the notify hand-off
- The projector reads `payload.route_id` (memo.created, memo.voided, visit.closed, route_day.state_changed, stock.moved are registered and carry it) and falls back to `source_client_uuid` and `payload.user_id`. Still unregistered and needed: `due.collected` (producer backend:sync), `day_exception.decided` (one event per route, `payload.route_id`), `risk_signal.changed` (`payload.user_id`, `business_date`).
- `tracking_action.created` (producer backend:analytics; payload `route_id`, `business_date`, `note`, `notified_user_ids`) so the notify module can send the FCM nudge of F-API-015. Until it is registered the action is stored and no event is written.

## backend-core / contract questions from the F-SYS-026 / F-API-028 checker
1. `POST /v1/admin/quarantine/{id}/resolve` with `accept` / `accept_with_fix` needs an ingest hook: `fun accept(quarantineId: Long, fixedRecord: JsonObject?, resolver: Long, reason: String)` (re-runs the record through IngestService as the original uploader, same client_uuid, and marks the row accepted / accepted_with_fix). Wire it as `QuarantineAcceptor` in `OpsService` (analytics); until then both answer 503. Maker-checker for data-entry classes (docs/24 s8.6) belongs in that hook.
2. The contract's `listQuarantine` is described as "rejected and quarantined records"; a parked row that turned final (`sync_rejected`) has no `quarantine_id` and no way to resolve it. Decide whether finalised parked rows join the queue (they need an id space and a resolve path) or stay out of it.
3. `return_to_device` should make the row retryable on the phone; today it is stored as `discarded` with a `return_to_device:` note. It needs a phone-visible status.
4. `SyncHealthPage` has no field for the config-ack share (`OpsService.configAckPct` computes it); add `config_ack_pct` and `config_version` to the contract, or serve it from `GET /v1/admin/config/reach/{version}`.

## Answer (db, 2026-10-07): V0036 (db part)
1. Indexes on `app.due_collection (route_id, business_date)` and `app.stock_movement (route_id, business_date)`.
2. Partial index on `app.day_exception (from_date, to_date) WHERE status = 'approved'`. There is no GIN index: the
   code filters `:r = ANY (route_ids)`, which GIN cannot serve. Switch to `route_ids @> ARRAY[:r]::bigint[]` if a
   GIN index is wanted later.
3. `(zone_id, business_date)` indexes on `dw.fact_visit` and `dw.fact_memo`.
4. `dw.agg_daily_route_segment`, shaped like `agg_daily_route_brand`. Note: there is no net or successful-calls
   column; `by_segment` inherits the brand table's gross-as-net approximation.
5. Catalogue rows (required keys enforced only after the producers send them):
   - `due.collected`: `route_id` may be null.
   - `day_exception.decided`: one event per route, `payload.route_id`.
   - `risk_signal.changed`: keys `code`, `subject_type`, `subject_id`; `user_id` may be null. Producers are masterdata
     rules and sync `risk_review`.
