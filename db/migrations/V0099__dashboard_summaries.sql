-- V0099 dashboard summary tables (owner directive 2026-10-22, docs/34 s2 "Live summaries"; dashboard brief s6). The DDL
-- of backend-reports' request docs/requests/db-backend-reports-dashboard-summaries.md (B1-B5, B7) as written, with
-- comments for the data dictionary. One change: B1's extra index (business_date, route_id) is a prefix of its primary
-- key, which already serves that filter (IndexHygieneTest refuses prefix duplicates), so it is not created.
-- backend-reports' Aggregator.rebuildRouteDay / the zone-day rebuild fill every column (rebuildable and idempotent).
-- B1 dw.agg_daily_outlet_sku: outlet x SKU x day (calling outlets and channel STD under any product filter), range-
--    partitioned by business_date like the facts (app.partition_policy).
-- B2 dw.agg_daily_route_variant: distinct memos per variant (segment and brand exist).
-- B3 dw.route_last_position: the route SR's latest position of the day (FF Geo pins). Personal data: never in a dw.v_* view.
-- B4 dw.agg_daily_route: downloaded_at, completed_calls, completed_geo_valid_calls, first_sale_at, last_sale_at.
-- B5 dw.agg_daily_zone: final_submitted_at, auto_submitted (written by the midnight job in dw only).
-- B7 dw.summary_freshness: per summary table the projector's last pass and outbox position ("last updated hh:mm").

SET lock_timeout = '5s';

CREATE TABLE dw.agg_daily_outlet_sku (
  business_date  date   NOT NULL,
  outlet_id      bigint NOT NULL,
  route_id       bigint NOT NULL,
  sku_id         bigint NOT NULL,
  sold_qty_base  bigint NOT NULL DEFAULT 0,
  memo_count     int    NOT NULL DEFAULT 0,
  last_event_id  bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, outlet_id, sku_id)
) PARTITION BY RANGE (business_date);
INSERT INTO app.partition_policy (parent, retention_class) VALUES ('dw.agg_daily_outlet_sku', 'event_fact');

CREATE TABLE dw.agg_daily_route_variant (
  business_date  date   NOT NULL,
  route_id       bigint NOT NULL,
  variant_id     bigint NOT NULL,
  memo_count     int    NOT NULL DEFAULT 0,
  sold_qty_base  bigint NOT NULL DEFAULT 0,
  last_event_id  bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, variant_id)
);

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

ALTER TABLE dw.agg_daily_route
  ADD COLUMN downloaded_at timestamptz,
  ADD COLUMN completed_calls int NOT NULL DEFAULT 0,
  ADD COLUMN completed_geo_valid_calls int NOT NULL DEFAULT 0,
  ADD COLUMN first_sale_at timestamptz,
  ADD COLUMN last_sale_at  timestamptz;

ALTER TABLE dw.agg_daily_zone
  ADD COLUMN final_submitted_at timestamptz,
  ADD COLUMN auto_submitted boolean NOT NULL DEFAULT false;

CREATE TABLE dw.summary_freshness (
  table_name       text PRIMARY KEY,
  last_updated_at  timestamptz NOT NULL,
  last_event_id    bigint NOT NULL
);

-- Partitions of the new partitioned aggregate for the build period (the worker keeps them ahead afterwards).
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');

COMMENT ON TABLE dw.agg_daily_outlet_sku IS 'Sales per outlet, SKU and business date of the latest memo versions (calling outlets and channel STD under any product filter).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.business_date IS 'Asia/Dhaka business date of the sales.';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.route_id IS 'Route the memos were made on.';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.sold_qty_base IS 'Quantity sold in the SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.memo_count IS 'Memos of the outlet-day with a line of the SKU.';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.agg_daily_outlet_sku.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON TABLE dw.agg_daily_route_variant IS 'Distinct memos and sales per route, business date and product variant (Breakdown by variant; a variant has several SKUs, so SKU memo counts cannot be summed).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_route_variant.business_date IS 'Asia/Dhaka business date of the sales.';
COMMENT ON COLUMN dw.agg_daily_route_variant.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.agg_daily_route_variant.variant_id IS 'Variant node of the product tree (app.product_node, level variant).';
COMMENT ON COLUMN dw.agg_daily_route_variant.memo_count IS 'Distinct memos with at least one line of the variant.';
COMMENT ON COLUMN dw.agg_daily_route_variant.sold_qty_base IS 'Quantity sold in base units.';
COMMENT ON COLUMN dw.agg_daily_route_variant.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.agg_daily_route_variant.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON TABLE dw.route_last_position IS 'Latest position of each route''s SR on a business date (FF Geo pins), upserted from geo fixes in capture-time order.
owner: worker | capture: SERVER | retention: event_fact | pii: personal';
COMMENT ON COLUMN dw.route_last_position.business_date IS 'Asia/Dhaka business date.';
COMMENT ON COLUMN dw.route_last_position.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.route_last_position.user_id IS 'SR whose position it is (app.app_user).';
COMMENT ON COLUMN dw.route_last_position.lat IS '[pii:personal] Latitude of the latest fix (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.route_last_position.lng IS '[pii:personal] Longitude of the latest fix (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.route_last_position.captured_at IS 'UTC capture time of that fix.';
COMMENT ON COLUMN dw.route_last_position.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.route_last_position.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON COLUMN dw.agg_daily_route.downloaded_at IS 'First full bundle download of the route-day (app.route_day.target_frozen_at when bundle_count > 0); Login = check-in and download.';
COMMENT ON COLUMN dw.agg_daily_route.completed_calls IS 'SR calls closed with an outcome other than abandoned or not_reached, with or without a memo (Total Calls).';
COMMENT ON COLUMN dw.agg_daily_route.completed_geo_valid_calls IS 'Completed calls whose server verdict, else device verdict, is in_range (Geo Validation).';
COMMENT ON COLUMN dw.agg_daily_route.first_sale_at IS 'UTC time of the route-day''s first memo (First Sale Time).';
COMMENT ON COLUMN dw.agg_daily_route.last_sale_at IS 'UTC time of the route-day''s last memo (Last Sale Time).';
COMMENT ON COLUMN dw.agg_daily_zone.final_submitted_at IS 'UTC time of the zone-day''s first Final Submit, or of the midnight auto-submit.';
COMMENT ON COLUMN dw.agg_daily_zone.auto_submitted IS 'True when the midnight job auto-submitted the zone-day (dw only; app.final_submit is not written).';
COMMENT ON TABLE dw.summary_freshness IS 'Freshness of each dashboard summary table: the projector''s last pass and its outbox position (the "last updated" time).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.summary_freshness.table_name IS 'Summary table name, e.g. agg_daily_route.';
COMMENT ON COLUMN dw.summary_freshness.last_updated_at IS 'Projector wall-clock time when it last finished a pass over the table.';
COMMENT ON COLUMN dw.summary_freshness.last_event_id IS 'Outbox position (domain event id) the pass reached.';

SELECT app.apply_db_role_grants();
