-- V0261 (PROVISIONAL, lane M-APSIS1; D-APSIS-MIG-02/-04): the archive schema for the Apsis history. No foreign keys, no
-- grants to the sync roles (api_rw, worker_rw, jobs_rw): the sync engine cannot read history. Indexes are built after load.
CREATE SCHEMA IF NOT EXISTS apsis_hist;
REVOKE ALL ON SCHEMA apsis_hist FROM PUBLIC;

CREATE TABLE apsis_hist.sales_line (
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,                -- Asia/Dhaka
  chunk_key      text NOT NULL,
  outlet_code    text NOT NULL,
  outlet_id      bigint,                       -- NULL when the outlet is not in app.outlet (archived without personal data)
  sku_code       text,
  sku_id         bigint,
  route_code     text,
  route_id       bigint,
  zone_id        bigint,
  territory_id   bigint,
  division_id    bigint,
  wing_id        bigint,
  user_code      text,
  qty_base       bigint NOT NULL,
  gross_mtk      bigint NOT NULL,              -- integer milli-taka
  is_zero_sale   boolean NOT NULL,
  PRIMARY KEY (row_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE apsis_hist.visit_geo (
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,
  chunk_key      text NOT NULL,
  outlet_code    text NOT NULL,
  outlet_id      bigint,
  user_code      text,
  route_code     text,
  route_id       bigint,
  zone_id        bigint,
  territory_id   bigint,
  division_id    bigint,
  wing_id        bigint,
  captured_at    timestamptz NOT NULL,
  lat            double precision,
  lng            double precision,
  accuracy_m     double precision,
  distance_m     double precision,
  geo_validated  boolean,
  PRIMARY KEY (row_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE apsis_hist.attendance (
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,
  chunk_key      text NOT NULL,
  user_code      text NOT NULL,
  check_in_at    timestamptz NOT NULL,
  check_out_at   timestamptz,
  lat            double precision,
  lng            double precision,
  PRIMARY KEY (row_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE apsis_hist.raw_archive (            -- the deferred programmes (docs/27): promotion and target tables, raw
  vendor_table   text NOT NULL,
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,                  -- 1970-01-01 when the row has no date
  chunk_key      text NOT NULL,
  data           jsonb NOT NULL,
  PRIMARY KEY (vendor_table, row_uuid, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE apsis_hist.route_day_vendor (       -- the vendor's own section summary, kept for reconciliation only
  row_uuid         uuid PRIMARY KEY,
  business_date    date NOT NULL,
  chunk_key        text NOT NULL,
  route_code       text NOT NULL,
  route_id         bigint,
  target_outlets   int,
  visited          int,
  successful_calls int
);
CREATE TABLE apsis_hist.outlet_stub (            -- outlets of the dump that are not in app.outlet: codes only, no name, no phone
  row_uuid     uuid PRIMARY KEY,
  outlet_code  text NOT NULL UNIQUE,
  route_code   text,
  zone_code    text,
  vendor_active boolean
);
CREATE TABLE apsis_hist.outlet_route (           -- outlet to route mapping history (effective-dated)
  row_uuid     uuid PRIMARY KEY,
  outlet_code  text NOT NULL,
  route_code   text NOT NULL,
  valid_from   date,
  valid_to     date
);

-- Default partitions (must stay empty: reconciliation checks it) and the month-partition helper.
CREATE TABLE apsis_hist.sales_line_default PARTITION OF apsis_hist.sales_line DEFAULT;
CREATE TABLE apsis_hist.visit_geo_default PARTITION OF apsis_hist.visit_geo DEFAULT;
CREATE TABLE apsis_hist.attendance_default PARTITION OF apsis_hist.attendance DEFAULT;
CREATE TABLE apsis_hist.raw_archive_default PARTITION OF apsis_hist.raw_archive DEFAULT;

CREATE FUNCTION apsis_hist.ensure_partition(p_parent text, p_date date) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE m date := date_trunc('month', p_date)::date; part text := p_parent || '_' || to_char(m, '"y"YYYY"m"MM');
BEGIN
  IF to_regclass('apsis_hist.' || part) IS NULL THEN
    EXECUTE format('CREATE TABLE apsis_hist.%I PARTITION OF apsis_hist.%I FOR VALUES FROM (%L) TO (%L)',
                   part, p_parent, m, (m + interval '1 month')::date);
    EXECUTE format('ALTER TABLE apsis_hist.%I SET (autovacuum_enabled = false)', part);   -- load window; the runbook re-enables
  END IF;
END $$;

COMMENT ON SCHEMA apsis_hist IS 'Apsis history archive (Dec 2025 - Sep 2026). Read-only after load, read through dw.v_hist_* only; no grants to the sync roles.';
