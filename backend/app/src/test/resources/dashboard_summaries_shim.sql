-- TEST-ONLY shim, a copy of backend/analytics/src/test/resources/dashboard_summaries_test.sql: stands in for the db lane's wave-4 summary-table migration (docs/requests/db-backend-reports-dashboard-summaries.md). Delete both the day it lands.
-- Idempotent on purpose: it also runs unchanged on a database that already has the real objects, and is deleted
-- the day those migrations are on INT.
ALTER TABLE app.route ADD COLUMN IF NOT EXISTS route_class text NOT NULL DEFAULT 'normal';
ALTER TABLE dw.dim_geo ADD COLUMN IF NOT EXISTS route_class text NOT NULL DEFAULT 'normal';
ALTER TABLE dw.dim_geo ADD COLUMN IF NOT EXISTS house_id bigint;
ALTER TABLE dw.dim_geo ADD COLUMN IF NOT EXISTS house_name text;
ALTER TABLE dw.dim_product ADD COLUMN IF NOT EXISTS segment_code text;
ALTER TABLE dw.dim_product ADD COLUMN IF NOT EXISTS brand_code text;
ALTER TABLE dw.dim_product ADD COLUMN IF NOT EXISTS variant_code text;
ALTER TABLE dw.agg_daily_route ADD COLUMN IF NOT EXISTS downloaded_at timestamptz;
ALTER TABLE dw.agg_daily_route ADD COLUMN IF NOT EXISTS completed_calls int NOT NULL DEFAULT 0;
ALTER TABLE dw.agg_daily_route ADD COLUMN IF NOT EXISTS completed_geo_valid_calls int NOT NULL DEFAULT 0;
ALTER TABLE dw.agg_daily_route ADD COLUMN IF NOT EXISTS first_sale_at timestamptz;
ALTER TABLE dw.agg_daily_route ADD COLUMN IF NOT EXISTS last_sale_at timestamptz;
ALTER TABLE dw.agg_daily_zone ADD COLUMN IF NOT EXISTS final_submitted_at timestamptz;
ALTER TABLE dw.agg_daily_zone ADD COLUMN IF NOT EXISTS auto_submitted boolean NOT NULL DEFAULT false;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS radius_m smallint;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS outlet_lat double precision;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS outlet_lng double precision;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS fix_lat double precision;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS fix_lng double precision;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS force_photo_uuid uuid;
ALTER TABLE dw.fact_visit ADD COLUMN IF NOT EXISTS completed boolean NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS dw.agg_daily_outlet_sku (
  business_date date NOT NULL, outlet_id bigint NOT NULL, route_id bigint NOT NULL, sku_id bigint NOT NULL,
  sold_qty_base bigint NOT NULL DEFAULT 0, memo_count int NOT NULL DEFAULT 0, last_event_id bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (business_date, route_id, outlet_id, sku_id));
CREATE INDEX IF NOT EXISTS agg_daily_outlet_sku_route ON dw.agg_daily_outlet_sku (business_date, route_id);
CREATE TABLE IF NOT EXISTS dw.agg_daily_route_variant (
  business_date date NOT NULL, route_id bigint NOT NULL, variant_id bigint NOT NULL, memo_count int NOT NULL DEFAULT 0,
  sold_qty_base bigint NOT NULL DEFAULT 0, last_event_id bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, variant_id));
CREATE TABLE IF NOT EXISTS dw.route_last_position (
  business_date date NOT NULL, route_id bigint NOT NULL, user_id bigint NOT NULL, lat double precision NOT NULL, lng double precision NOT NULL,
  captured_at timestamptz NOT NULL, last_event_id bigint NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (business_date, route_id));
CREATE TABLE IF NOT EXISTS dw.summary_freshness (
  table_name text PRIMARY KEY, last_updated_at timestamptz NOT NULL, last_event_id bigint NOT NULL);
