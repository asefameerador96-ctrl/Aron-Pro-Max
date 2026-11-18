-- V0260 (PROVISIONAL, lane M-APSIS1; D-APSIS-MIG-04): the migration role, schemas, control and id-map tables, code normalisation and
-- MySQL date helpers. Lives outside db/migrations until the lead lands it. Touches no live table. Numbers only: nothing here holds a
-- name, a phone or a memo text.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'apsis_migrator') THEN
    CREATE ROLE apsis_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
END $$;
COMMENT ON ROLE apsis_migrator IS 'The Apsis history migration job. Owns nothing; writes only apsis_ctl, apsis_map, apsis_stage and the apsis_hist archive; reads a column-limited set of live masters. The Entra login of the Container Apps job is made a member by the runbook.';

CREATE SCHEMA IF NOT EXISTS apsis_ctl;
CREATE SCHEMA IF NOT EXISTS apsis_map;
CREATE SCHEMA IF NOT EXISTS apsis_hist;
CREATE SCHEMA IF NOT EXISTS apsis_stage;
REVOKE ALL ON SCHEMA apsis_ctl, apsis_map, apsis_hist, apsis_stage FROM PUBLIC;
GRANT USAGE ON SCHEMA apsis_ctl, apsis_map, apsis_hist TO apsis_migrator;
GRANT USAGE, CREATE ON SCHEMA apsis_stage TO apsis_migrator;       -- the loader creates and drops its own staging tables here
GRANT USAGE ON SCHEMA app TO apsis_migrator;
COMMENT ON SCHEMA apsis_stage IS 'Transient copy of the vendor dump (typed, untransformed, reviewed columns only). Dropped by the cleanup step after sign-off.';

-- ---- code normalisation (the written matching rule, D-APSIS-MIG-07): trim, remove inner spaces, upper case, and strip leading zeros of a
-- purely numeric code ('00123' = '123'). Applied to BOTH sides of every master match. Codes that collide after normalisation are not matched.
CREATE FUNCTION apsis_map.norm_code(p text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE WHEN n ~ '^[0-9]+$' THEN regexp_replace(n, '^0+(?=[0-9])', '') ELSE n END
    FROM (SELECT upper(regexp_replace(btrim(p), '\s+', '', 'g')) AS n) s $$;

-- ---- settings owned by the migrate role (the job role reads them, never changes them)
CREATE TABLE apsis_ctl.setting (key text PRIMARY KEY, value text NOT NULL);
INSERT INTO apsis_ctl.setting VALUES ('history_until', '2026-09-30'), ('window_from', '2025-10-01'), ('window_to', '2026-12-31');
COMMENT ON TABLE apsis_ctl.setting IS 'history_until: last business date for which dw.v_sales_with_history uses the archive instead of live rows (owner question Q16). window_from/window_to: the plausible business-date window of the vendor history; a row outside it is rejected (apsis_hist.reject), never given a partition.';
CREATE FUNCTION apsis_map.in_window(p date) RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT p IS NOT NULL AND p >= (SELECT value::date FROM apsis_ctl.setting WHERE key = 'window_from')
                       AND p <= (SELECT value::date FROM apsis_ctl.setting WHERE key = 'window_to') $$;

-- ---- MySQL oddities -> NULL (zero dates, blank, impossible dates). Callers count the NULLs; nothing aborts on a bad date.
CREATE FUNCTION apsis_map.mysql_date(p text) RETURNS date
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
BEGIN
  IF p IS NULL OR btrim(p) = '' OR left(btrim(p), 4) = '0000' OR btrim(p) !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' THEN RETURN NULL; END IF;
  RETURN left(btrim(p), 10)::date;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;
CREATE FUNCTION apsis_map.mysql_ts(p text, p_tz text) RETURNS timestamptz
LANGUAGE plpgsql STABLE PARALLEL SAFE AS $$
BEGIN
  IF p IS NULL OR btrim(p) = '' OR left(btrim(p), 4) = '0000' OR btrim(p) !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}' THEN RETURN NULL; END IF;
  RETURN btrim(p)::timestamp AT TIME ZONE p_tz;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;
-- business date of a vendor value: a plain date stays as is; a datetime is converted from the source zone to Asia/Dhaka.
CREATE FUNCTION apsis_map.business_date(p text, p_tz text) RETURNS date
LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT CASE WHEN p IS NULL THEN NULL WHEN length(btrim(p)) = 10 THEN apsis_map.mysql_date(p)
              ELSE app.dhaka_date(apsis_map.mysql_ts(p, p_tz)) END $$;
-- numbers from text without ever failing the load on a stray value (the offending row is counted as 'bad_number' by the caller)
CREATE FUNCTION apsis_map.num(p text) RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
BEGIN
  IF p IS NULL OR btrim(p) = '' THEN RETURN NULL; END IF;
  RETURN btrim(p)::numeric;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

CREATE TABLE apsis_ctl.run (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at        timestamptz NOT NULL DEFAULT now(),
  finished_at       timestamptz,
  source_tz         text NOT NULL DEFAULT 'UTC',
  qty_unit          text NOT NULL DEFAULT 'base' CHECK (qty_unit IN ('base', 'pack')),
  confirm_locations boolean NOT NULL DEFAULT false,
  dry_run           boolean NOT NULL DEFAULT false,
  note              text
);
COMMENT ON TABLE apsis_ctl.run IS 'One row per tool invocation. Settings that change the result (source time zone, quantity unit, location-confirmed switch) are recorded here; a later run with different settings is refused while verified chunks exist.';

CREATE TABLE apsis_ctl.chunk (
  vendor_table  text NOT NULL,
  chunk_key     text NOT NULL,                 -- '_stage' (the staging step), 'all' (masters) or 'YYYY-MM' / 'unknown' (facts)
  state         text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','staged','transformed','verified','failed')),
  rows_parsed   bigint NOT NULL DEFAULT 0,     -- '_stage': INSERT rows read from the dump (before de-duplication)
  rows_in       bigint NOT NULL DEFAULT 0,     -- distinct rows staged for this chunk
  rows_out      bigint NOT NULL DEFAULT 0,     -- rows written to the target
  rows_skipped  bigint NOT NULL DEFAULT 0,     -- staged rows with no usable date or key (counted, never silent)
  stmt_ordinal  int NOT NULL DEFAULT 0,        -- last INSERT statement staged (resume point)
  checksum      jsonb,                         -- counts and sums only
  run_id        bigint REFERENCES apsis_ctl.run(id),
  started_at    timestamptz,
  finished_at   timestamptz,
  error         text,                          -- SQLSTATE and our own words: table, chunk, statement number; never a data value
  PRIMARY KEY (vendor_table, chunk_key)
);
COMMENT ON TABLE apsis_ctl.chunk IS 'Per-table, per-chunk checkpoint: pending, staged, transformed, verified. The runner resumes at the first chunk that is not verified and re-stages a table whose staged rows no longer match rows_in.';

CREATE TABLE apsis_ctl.report (
  run_id  bigint NOT NULL REFERENCES apsis_ctl.run(id),
  name    text NOT NULL,
  value   jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, name)
);

CREATE TABLE apsis_map.id_map (
  vendor_table  text NOT NULL,
  vendor_key    text NOT NULL,
  our_table     text NOT NULL,
  our_id        bigint,
  row_uuid      uuid NOT NULL,
  PRIMARY KEY (vendor_table, vendor_key)
);
CREATE INDEX id_map_our ON apsis_map.id_map (our_table, our_id);

-- Lookups the fact steps join, keyed by the NORMALISED code (rebuilt by the masters step). Scope ids may be NULL for a master whose
-- geography is incomplete: the row is still matched (outlet_id set), and the count of such rows is reported.
CREATE TABLE apsis_map.outlet_geo (code_norm text PRIMARY KEY, outlet_code text NOT NULL, outlet_id bigint NOT NULL, route_id bigint, zone_id bigint,
  territory_id bigint, division_id bigint, wing_id bigint);
CREATE TABLE apsis_map.route_geo (code_norm text PRIMARY KEY, route_code text NOT NULL, route_id bigint NOT NULL, zone_id bigint,
  territory_id bigint, division_id bigint, wing_id bigint);
CREATE TABLE apsis_map.sku_map (code_norm text PRIMARY KEY, sku_code text NOT NULL, sku_id bigint NOT NULL, base_per_pack int NOT NULL);
CREATE TABLE apsis_map.user_map (code_norm text PRIMARY KEY, user_id bigint NOT NULL, home_zone_id bigint, territory_id bigint, division_id bigint, wing_id bigint);
CREATE TABLE apsis_map.code_ambiguous (kind text NOT NULL, side text NOT NULL CHECK (side IN ('live', 'vendor')), code_norm text NOT NULL, n int NOT NULL, PRIMARY KEY (kind, side, code_norm));
COMMENT ON TABLE apsis_map.code_ambiguous IS 'Codes that collide after normalisation, on our side (two live masters) or the vendor side (two distinct vendor codes, e.g. 000457 and 457): excluded from matching, never merged onto one master, and counted in the report.';
CREATE TABLE apsis_map.fill_log (outlet_id bigint PRIMARY KEY, prev_lat double precision, prev_lng double precision, prev_location_basis text,
  prev_location_confirmed boolean, new_lat double precision, new_lng double precision, run_id bigint);
COMMENT ON TABLE apsis_map.fill_log IS 'The only live-master write of the tool (coordinate fill), logged with before and after so the undo touches only rows still holding the filled value.';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA apsis_ctl, apsis_map TO apsis_migrator;
GRANT TRUNCATE ON apsis_map.outlet_geo, apsis_map.route_geo, apsis_map.sku_map, apsis_map.user_map, apsis_map.code_ambiguous TO apsis_migrator;
REVOKE INSERT, UPDATE, DELETE ON apsis_ctl.setting FROM apsis_migrator;       -- the migrate role owns the settings; the job only reads them
GRANT EXECUTE ON FUNCTION apsis_map.in_window(date), apsis_map.norm_code(text), apsis_map.mysql_date(text), apsis_map.mysql_ts(text, text), apsis_map.business_date(text, text), apsis_map.num(text) TO apsis_migrator;
