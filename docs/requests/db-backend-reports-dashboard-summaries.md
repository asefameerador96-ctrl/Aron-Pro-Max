# Request to db: dashboard summary tables (backend-reports, wave 4, docs/34 s2, brief s6)

From lane backend-reports, 2026-10-22. Contract: `contract/openapi.yaml` 1.4.0 (`/v1/dashboards/national/*`, route-map, sale-position).

The existing `dw` already covers a lot, so this asks only for what is missing. What I reuse as is:
`dw.agg_daily_route` (`planned`, `logged_in_at`, `sales_submitted_at`, `target_outlets`, `successful_calls`, `visits`,
`geo_valid_visits`), `dw.agg_daily_route_sku` (sold qty and memo count per route × SKU), `dw.agg_daily_route_brand`
(distinct memos per brand), `dw.agg_daily_zone` (`final_submitted`), `dw.fact_visit` (`distance_m`, `opened_at`),
`dw.fact_geo_fix` (lat/lng), `dw.dim_geo`, `dw.dim_product`, `dw.dim_outlet.channel`, `app.target` (route × SKU monthly
target), `app.calendar_holiday`, `app.final_submit` (unique per zone/date while not reopened), `app.event_consumer`.

## A. Masters (db owns; I consume under these names)

| # | Need | Used by |
|---|---|---|
| A1 | `route_class` text NOT NULL on `app.route`, in (`normal`,`manual`,`dummy_br`,`dummy_institutional`), default `normal`, and `dw.dim_geo.route_class` (copied; rebuilds with the dim). Name is yours to choose if `route.kind` is reused; I read only `dw.dim_geo.route_class`. | Target Outlet, Target Route, Login, Final Submit service zones |
| A2 | `app.house.is_active boolean NOT NULL DEFAULT true`, a de-duplication rule (territory, lower(name)) and `house_id`, `house_name` on `dw.dim_geo` (via the zone). | House filter and scope |
| A3 | `app.outlet.channel` stays NOT NULL (exists). Nothing to do; confirm the seed has no outlet without channel. | channel cards |
| A4 | `dw.dim_product`: add `segment_code`, `brand_code`, `variant_code` (the filter and the response use codes). | Segment/Brand/Variant filters |
| A5 | Working-day calendar: a view `dw.v_working_day (day date, working boolean)` over a month: Fridays and `app.calendar_holiday` (kind holiday/emergency_off, scope global or covering the zone) excluded, `makeup_day` included. Global scope only is enough for the card (national count); per-zone scope later. | daily target = monthly / working days |
| A6 | Route monthly target upload (D-TARGET-MIN-01) keeps writing `app.target` with `scope_type='route'`, `product_level='sku'`; if db changes this, tell me the new name. | sales cards, "i", route Target vs Achievement |

## B. New `dw` tables (the exact DDL I need; partitioned like the other facts, retention via `app.partition_policy`)

```sql
-- B1: outlet × SKU × day: calling outlets and channel STD with any product filter (latest memo version only).
CREATE TABLE dw.agg_daily_outlet_sku (
  business_date  date   NOT NULL,
  outlet_id      bigint NOT NULL,
  route_id       bigint NOT NULL,
  sku_id         bigint NOT NULL,
  sold_qty_base  bigint NOT NULL DEFAULT 0,
  memo_count     int    NOT NULL DEFAULT 0,
  last_event_id  bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, outlet_id, sku_id)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.agg_daily_outlet_sku (business_date, route_id);
INSERT INTO app.partition_policy (parent) VALUES ('dw.agg_daily_outlet_sku');

-- B2: distinct memos per segment (brand and variant come from agg_daily_route_brand / agg_daily_route_sku).
CREATE TABLE dw.agg_daily_route_segment (
  business_date  date   NOT NULL,
  route_id       bigint NOT NULL,
  segment_id     bigint NOT NULL,
  memo_count     int    NOT NULL DEFAULT 0,
  sold_qty_base  bigint NOT NULL DEFAULT 0,
  last_event_id  bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, segment_id)
);

-- B3: latest position of each route's SR today (FF Geo pins), upserted from geo fixes in capture-time order.
CREATE TABLE dw.route_last_position (
  business_date  date   NOT NULL,
  route_id       bigint NOT NULL,
  user_id        bigint NOT NULL,
  lat            double precision NOT NULL,
  lng            double precision NOT NULL,
  captured_at    timestamptz NOT NULL,
  last_event_id  bigint NOT NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id)
);

-- B4: add to dw.agg_daily_route (login = check-in AND download; Bikroy Joma stays sales_submitted_at)
ALTER TABLE dw.agg_daily_route
  ADD COLUMN downloaded_at timestamptz,          -- first bundle download of the day (app.route_day_bundle_downloads)
  ADD COLUMN completed_calls int NOT NULL DEFAULT 0,   -- visits closed with a call outcome, with or without memo
  ADD COLUMN first_sale_at timestamptz,
  ADD COLUMN last_sale_at  timestamptz;

-- B5: add to dw.agg_daily_zone
ALTER TABLE dw.agg_daily_zone
  ADD COLUMN final_submitted_at timestamptz,
  ADD COLUMN auto_submitted boolean NOT NULL DEFAULT false;   -- set by the midnight job

-- B6: add to dw.fact_visit (sale position page, geo-fencing %)
ALTER TABLE dw.fact_visit
  ADD COLUMN within_fence boolean,               -- distance_m <= outlet radius_m at the call
  ADD COLUMN radius_m smallint;

-- B7: freshness of each summary table = the outbox position of its consumer (cheaper and truer than max(updated_at))
CREATE TABLE dw.summary_freshness (
  table_name       text PRIMARY KEY,
  last_updated_at  timestamptz NOT NULL,     -- the projector's wall-clock time when it last finished a pass
  last_event_id    bigint NOT NULL
);
```

B1 sizing: about 1.1 lakh calling outlets × about 6 SKUs a day is 0.7 M rows/day at fleet size, a few hundred at pilot
size; if db prefers outlet × brand, say so, but then the Variant filter cannot narrow the channel cards.
B4 `downloaded_at` source: the existing `route_day_bundle_downloads` table (V0065).

## C. Feeding (no new consumer)

`AggregationWorker.project` already turns `memo.created`, `memo.voided`, `visit.closed` and `route_day.*` outbox events into
a dirty `route_day_agg` key, and `Aggregator.rebuildRouteDay` recomputes the whole route-day from the source tables. B1, B2,
B3, the B4 columns and B6 are filled in that rebuild (so they are rebuildable and idempotent like the rest); the zone-day
rebuild fills B5. The dashboard therefore lags the field by one worker pass (seconds), well inside the one-minute budget.
backend-core need only keep emitting the events above for `route_day` state changes (login, bundle download, sales submit,
final submit) and for geo fixes/breadcrumbs (position): if `route_day_bundle_downloads` inserts or `geo_fix`/`geo_breadcrumb`
inserts publish no event today, say so and I mark the route-day dirty from a 30 s poll on their `created_at` instead.

## D. What I do myself

The rebuild code for B1 to B6, B7 (written by the projector at the end of each pass), the midnight auto-submit job (it
writes `final_submitted_at`/`auto_submitted` in `dw` only; it does not insert into `app.final_submit`), and every query.
Until your migrations land, my tests create the objects above from a test-only SQL file `dashboard_summaries_test.sql`,
which I delete the day yours is on INT.
