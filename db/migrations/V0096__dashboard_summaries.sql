-- V0096 summary tables for the national dashboard (owner directive 2026-10-22, docs/34 s2 "Live summaries"; dashboard
-- brief s6: the cards read summary tables kept current as each memo, call, login, Bikroy Joma and final submit syncs,
-- never the raw memo tables, and show "last updated"). Built from the brief because backend-reports had filed no table
-- shapes when this was written; the projector (backend-reports, Aggregator) fills every column. What the existing
-- aggregates already carry is reused, not copied: sales per route and SKU (agg_daily_route_sku), memo counts per brand
-- and segment (agg_daily_route_brand, agg_daily_route_segment), CPR, calls, geo validation, login, submit and final
-- submit per route and zone (agg_daily_route, agg_daily_zone). Added:
-- 1. dw.agg_daily_route: completed_calls (Total Calls of the Geo Fencing card: completed calls with or without a memo),
--    first_sale_at / last_sale_at (route map time card), distance_covered_m (the SR's path through the day's positions,
--    D-DIST-01), and the SR's latest position (FF Geo pin and SR Last Position). The position columns are personal
--    data: the dw.v_* views never expose them (V0015 rule), the API reads them under its own grant.
-- 2. dw.agg_daily_outlet_category: sales per outlet, day and category, for By Channel STD (cigarette by outlet channel)
--    and the category-wise sales of the route map's outlet pop-up.
-- 3. dw.agg_daily_route_variant: memo count (each memo once per variant) and quantity per variant, for the Breakdown
--    gear's "By STD / By Memo - Variant" (brand and segment exist).
-- 4. dw.summary_watermark: per summary the last applied domain event and the refresh time ("last updated hh:mm").
-- New aggregate tables are not partitioned (one row per route- or outlet-day and key, like agg_daily_route_sku).

SET lock_timeout = '5s';

ALTER TABLE dw.agg_daily_route
  ADD COLUMN completed_calls     int NOT NULL DEFAULT 0,
  ADD COLUMN first_sale_at       timestamptz,
  ADD COLUMN last_sale_at        timestamptz,
  ADD COLUMN distance_covered_m  double precision,
  ADD COLUMN last_position_at    timestamptz,
  ADD COLUMN last_position_lat   double precision,
  ADD COLUMN last_position_lng   double precision;
ALTER TABLE dw.agg_daily_route ADD CONSTRAINT agg_daily_route_v0096_ranges
  CHECK (completed_calls >= 0 AND (distance_covered_m IS NULL OR distance_covered_m >= 0)
         AND (last_position_lat IS NULL OR last_position_lat BETWEEN -90 AND 90)
         AND (last_position_lng IS NULL OR last_position_lng BETWEEN -180 AND 180)
         AND ((last_position_lat IS NULL) = (last_position_lng IS NULL))
         AND (first_sale_at IS NULL OR last_sale_at >= first_sale_at)) NOT VALID;

CREATE TABLE dw.agg_daily_outlet_category (
  business_date   date NOT NULL,
  outlet_id       bigint NOT NULL,
  category_code   text NOT NULL CHECK (category_code IN ('cigarette', 'bidi', 'lighter', 'match')),
  route_id        bigint,
  sold_qty_base   bigint NOT NULL DEFAULT 0,
  memo_count      int NOT NULL DEFAULT 0 CHECK (memo_count >= 0),
  gross_mtk       bigint NOT NULL DEFAULT 0,
  last_event_id   bigint NOT NULL DEFAULT 0,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, outlet_id, category_code)
);
CREATE INDEX agg_daily_outlet_category_route ON dw.agg_daily_outlet_category (route_id, business_date);

CREATE TABLE dw.agg_daily_route_variant (
  business_date   date NOT NULL,
  route_id        bigint NOT NULL,
  variant_id      bigint NOT NULL,
  memo_count      int NOT NULL DEFAULT 0 CHECK (memo_count >= 0),
  sold_qty_base   bigint NOT NULL DEFAULT 0,
  gross_mtk       bigint NOT NULL DEFAULT 0,
  last_event_id   bigint NOT NULL DEFAULT 0,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, variant_id)
);
CREATE INDEX agg_daily_route_variant_variant ON dw.agg_daily_route_variant (variant_id, business_date);

CREATE TABLE dw.summary_watermark (
  summary         text PRIMARY KEY CHECK (summary ~ '^[a-z][a-z0-9_]{1,60}$'),
  last_event_id   bigint NOT NULL DEFAULT 0 CHECK (last_event_id >= 0),
  refreshed_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN dw.agg_daily_route.completed_calls IS 'Completed calls of the route-day with or without a memo (zero calls included): Total Calls of the Geo Fencing card.';
COMMENT ON COLUMN dw.agg_daily_route.first_sale_at IS 'UTC time of the route-day''s first active memo (route map First Sale Time).';
COMMENT ON COLUMN dw.agg_daily_route.last_sale_at IS 'UTC time of the route-day''s last active memo (Last Sale Time).';
COMMENT ON COLUMN dw.agg_daily_route.distance_covered_m IS 'Metres along the SR''s recorded positions of the day in time order (D-DIST-01); null before the first position.';
COMMENT ON COLUMN dw.agg_daily_route.last_position_at IS 'UTC capture time of the SR''s latest position of the day (FF Geo pin).';
COMMENT ON COLUMN dw.agg_daily_route.last_position_lat IS '[pii:personal] Latitude of that position (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.agg_daily_route.last_position_lng IS '[pii:personal] Longitude of that position (WGS84); never in a dw.v_* view.';
COMMENT ON TABLE dw.agg_daily_route IS 'Per route and business date: day state, visit counts, sales, discounts, paid and due money, and (V0096) completed calls, sale times, distance covered and the SR''s latest position.
owner: worker | capture: SERVER | retention: event_fact | pii: personal';
COMMENT ON TABLE dw.agg_daily_outlet_category IS 'Sales per outlet, business date and product category of active memos (By Channel STD, route map outlet pop-up).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_outlet_category.business_date IS 'Asia/Dhaka business date of the sales.';
COMMENT ON COLUMN dw.agg_daily_outlet_category.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.agg_daily_outlet_category.category_code IS 'Product category: cigarette, bidi, lighter or match.';
COMMENT ON COLUMN dw.agg_daily_outlet_category.route_id IS 'Route of the memos (the outlet''s route that day); null when unknown.';
COMMENT ON COLUMN dw.agg_daily_outlet_category.sold_qty_base IS 'Quantity sold in the category''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN dw.agg_daily_outlet_category.memo_count IS 'Active memos of the outlet-day with at least one line of the category.';
COMMENT ON COLUMN dw.agg_daily_outlet_category.gross_mtk IS 'Gross line value of the category in milli-taka.';
COMMENT ON COLUMN dw.agg_daily_outlet_category.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.agg_daily_outlet_category.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON TABLE dw.agg_daily_route_variant IS 'Memo count (each memo once per variant) and sales per route, business date and product variant (Breakdown by variant).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_route_variant.business_date IS 'Asia/Dhaka business date of the sales.';
COMMENT ON COLUMN dw.agg_daily_route_variant.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.agg_daily_route_variant.variant_id IS 'Variant node of the product tree (app.product_node, level variant).';
COMMENT ON COLUMN dw.agg_daily_route_variant.memo_count IS 'Active memos with at least one line of the variant, counted once per memo.';
COMMENT ON COLUMN dw.agg_daily_route_variant.sold_qty_base IS 'Quantity sold in base units.';
COMMENT ON COLUMN dw.agg_daily_route_variant.gross_mtk IS 'Gross line value in milli-taka.';
COMMENT ON COLUMN dw.agg_daily_route_variant.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.agg_daily_route_variant.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON TABLE dw.summary_watermark IS 'Per dashboard summary the last domain event applied and when (the dashboard''s "last updated").
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.summary_watermark.summary IS 'Summary name, e.g. the aggregate table name.';
COMMENT ON COLUMN dw.summary_watermark.last_event_id IS 'Highest domain event id applied to the summary.';
COMMENT ON COLUMN dw.summary_watermark.refreshed_at IS 'UTC time of the last refresh.';

SELECT app.apply_db_role_grants();
