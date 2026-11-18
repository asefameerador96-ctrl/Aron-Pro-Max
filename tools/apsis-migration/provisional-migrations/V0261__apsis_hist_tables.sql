-- V0261 (PROVISIONAL, lane M-APSIS1; D-APSIS-MIG-02/-04): the archive schema for the Apsis history. No foreign keys. No grant to any
-- application role (api_rw, worker_rw, jobs_rw, web_ro, bi_reader): history is read only through the dw views of V0262.
-- Indexes: only chunk_key (so a chunk's delete-and-insert and checksum do not scan everything loaded so far); the read indexes are built after load.
CREATE TABLE apsis_hist.sales_line (
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,                -- Asia/Dhaka
  chunk_key      text NOT NULL,
  outlet_code    text NOT NULL,                -- the vendor's code as the dump has it (never normalised in the archive)
  outlet_id      bigint,                       -- NULL when the outlet is not in app.outlet (counted in the report, gated by threshold)
  sku_code       text,
  sku_id         bigint,
  route_code     text,
  route_id       bigint,
  zone_id        bigint,
  territory_id   bigint,
  division_id    bigint,
  wing_id        bigint,
  user_code      text,
  user_id        bigint,
  qty_base       bigint NOT NULL,
  gross_mtk      bigint NOT NULL,              -- integer milli-taka
  is_zero_sale   boolean NOT NULL,
  vendor_deleted boolean NOT NULL DEFAULT false,
  PRIMARY KEY (row_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE apsis_hist.visit_geo (
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,
  chunk_key      text NOT NULL,
  outlet_code    text NOT NULL,
  outlet_id      bigint,
  user_code      text,
  user_id        bigint,
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
  user_id        bigint,
  zone_id        bigint,                       -- the user's home zone as of the load (scope for reports); NULL when the user is not matched
  territory_id   bigint,
  division_id    bigint,
  wing_id        bigint,
  check_in_at    timestamptz NOT NULL,
  check_out_at   timestamptz,
  lat            double precision,
  lng            double precision,
  PRIMARY KEY (row_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE apsis_hist.raw_archive (            -- the programme tables (promotion, target): the whole vendor row (reviewed columns) as jsonb
  vendor_table   text NOT NULL,
  row_uuid       uuid NOT NULL,
  business_date  date NOT NULL,                  -- 1970-01-01 when the row has no usable date
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
CREATE TABLE apsis_hist.sales_line_default PARTITION OF apsis_hist.sales_line DEFAULT;
CREATE TABLE apsis_hist.visit_geo_default PARTITION OF apsis_hist.visit_geo DEFAULT;
CREATE TABLE apsis_hist.attendance_default PARTITION OF apsis_hist.attendance DEFAULT;
CREATE TABLE apsis_hist.raw_archive_default PARTITION OF apsis_hist.raw_archive DEFAULT;
CREATE INDEX ON apsis_hist.sales_line (chunk_key);
CREATE INDEX ON apsis_hist.visit_geo (chunk_key);
CREATE INDEX ON apsis_hist.attendance (chunk_key);
CREATE INDEX ON apsis_hist.raw_archive (vendor_table, chunk_key);
CREATE INDEX ON apsis_hist.route_day_vendor (chunk_key);

-- Month partitions only inside the plausible window (the vendor history is Nov 2025 - Sep 2026; the window leaves room). A date outside it
-- (year 1900, 2099) is NOT given a partition of its own: it lands in the default partition, which reconciliation requires to be empty.
-- SECURITY DEFINER: the job role cannot create tables in apsis_hist, only call this function.
CREATE FUNCTION apsis_hist.ensure_partition(p_parent text, p_date date) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, apsis_hist AS $$
DECLARE m date := date_trunc('month', p_date)::date; part text := p_parent || '_' || to_char(m, '"y"YYYY"m"MM');
BEGIN
  IF p_parent NOT IN ('sales_line', 'visit_geo', 'attendance', 'raw_archive') THEN RAISE EXCEPTION 'ensure_partition: unknown parent'; END IF;
  IF p_date IS NULL OR (m <> DATE '1970-01-01' AND (m < DATE '2025-10-01' OR m >= DATE '2027-01-01')) THEN RETURN; END IF;
  IF to_regclass('apsis_hist.' || part) IS NULL THEN
    EXECUTE format('CREATE TABLE apsis_hist.%I PARTITION OF apsis_hist.%I FOR VALUES FROM (%L) TO (%L)', part, p_parent, m, (m + interval '1 month')::date);
    EXECUTE format('ALTER TABLE apsis_hist.%I SET (autovacuum_enabled = false)', part);   -- load window; finalize turns it back on
  END IF;
END $$;
REVOKE ALL ON FUNCTION apsis_hist.ensure_partition(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apsis_hist.ensure_partition(text, date) TO apsis_migrator;
GRANT SELECT, INSERT, UPDATE, DELETE ON apsis_hist.sales_line, apsis_hist.visit_geo, apsis_hist.attendance, apsis_hist.raw_archive,
  apsis_hist.route_day_vendor, apsis_hist.outlet_stub, apsis_hist.outlet_route TO apsis_migrator;
COMMENT ON SCHEMA apsis_hist IS 'Apsis history archive (Dec 2025 - Sep 2026). Read-only after load, read through the dw.v_hist_* views only; no grants to any application role. Attributed to the geography as of the load (past moves are not kept).';

-- After the load (finalize): the read indexes, autovacuum back on for the partitions, ANALYZE. SECURITY DEFINER: the job role owns no archive table.
CREATE FUNCTION apsis_hist.finish_load() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, apsis_hist AS $$
DECLARE t record;
BEGIN
  CREATE INDEX IF NOT EXISTS sales_line_route_day ON apsis_hist.sales_line (route_id, business_date);
  CREATE INDEX IF NOT EXISTS sales_line_outlet ON apsis_hist.sales_line (outlet_id, business_date);
  CREATE INDEX IF NOT EXISTS visit_geo_outlet ON apsis_hist.visit_geo (outlet_id, business_date);
  CREATE INDEX IF NOT EXISTS visit_geo_route_day ON apsis_hist.visit_geo (route_id, business_date);
  CREATE INDEX IF NOT EXISTS attendance_user ON apsis_hist.attendance (user_code, business_date);
  FOR t IN SELECT inhrelid::regclass AS r FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'apsis_hist' LOOP
    EXECUTE format('ALTER TABLE %s SET (autovacuum_enabled = true)', t.r);
  END LOOP;
  ANALYZE apsis_hist.sales_line; ANALYZE apsis_hist.visit_geo; ANALYZE apsis_hist.attendance;
END $$;
REVOKE ALL ON FUNCTION apsis_hist.finish_load() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apsis_hist.finish_load() TO apsis_migrator;
GRANT SELECT ON apsis_hist.sales_line_default, apsis_hist.visit_geo_default, apsis_hist.attendance_default, apsis_hist.raw_archive_default TO apsis_migrator;   -- reconciliation reads the default partitions directly
