-- V0260 (PROVISIONAL, lane M-APSIS1; D-APSIS-MIG-04): control and id-map tables of the Apsis history migration.
-- Lives outside db/migrations until the lead lands it. Touches no live table. Numbers only: nothing here holds a name,
-- a phone or a memo text.
CREATE SCHEMA IF NOT EXISTS apsis_ctl;
CREATE SCHEMA IF NOT EXISTS apsis_map;
REVOKE ALL ON SCHEMA apsis_ctl, apsis_map FROM PUBLIC;

CREATE TABLE apsis_ctl.run (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at        timestamptz NOT NULL DEFAULT now(),
  finished_at       timestamptz,
  source_tz         text NOT NULL DEFAULT 'UTC',
  confirm_locations boolean NOT NULL DEFAULT false,
  dry_run           boolean NOT NULL DEFAULT false,
  note              text
);
COMMENT ON TABLE apsis_ctl.run IS 'One row per migration run (tool invocation). Settings that change the result (source time zone, location-confirmed switch) are recorded here.';

CREATE TABLE apsis_ctl.chunk (
  vendor_table  text NOT NULL,
  chunk_key     text NOT NULL,                 -- 'all' for masters, 'YYYY-MM' for facts
  state         text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','staged','transformed','verified','failed')),
  rows_in       bigint NOT NULL DEFAULT 0,     -- rows read from the dump
  rows_out      bigint NOT NULL DEFAULT 0,     -- rows written to the target
  stmt_ordinal  int NOT NULL DEFAULT 0,        -- last INSERT statement staged (resume point)
  checksum      jsonb,                         -- counts and sums only
  run_id        bigint REFERENCES apsis_ctl.run(id),
  started_at    timestamptz,
  finished_at   timestamptz,
  error         text,                          -- table, chunk and statement number only: never a data value
  PRIMARY KEY (vendor_table, chunk_key)
);
COMMENT ON TABLE apsis_ctl.chunk IS 'Per-table, per-chunk checkpoint: pending, staged, transformed, verified. The runner resumes at the first chunk that is not verified.';

CREATE TABLE apsis_map.id_map (
  vendor_table  text NOT NULL,
  vendor_key    text NOT NULL,                 -- the vendor primary key, parts joined with |
  our_table     text NOT NULL,
  our_id        bigint,
  row_uuid      uuid NOT NULL,                 -- UUID v5 of namespace + vendor table + key (tools/apsis-migration keys.py)
  PRIMARY KEY (vendor_table, vendor_key)
);
CREATE INDEX id_map_our ON apsis_map.id_map (our_table, our_id);
COMMENT ON TABLE apsis_map.id_map IS 'Crosswalk vendor key -> our id for the masters, so a re-run never creates a second row.';

CREATE TABLE apsis_ctl.report (
  run_id  bigint NOT NULL REFERENCES apsis_ctl.run(id),
  name    text NOT NULL,
  value   jsonb NOT NULL,
  PRIMARY KEY (run_id, name)
);

-- Lookup tables the fact transformation joins by business code (rebuilt by the masters step; plain tables, no triggers).
CREATE TABLE apsis_map.outlet_geo (outlet_code text PRIMARY KEY, outlet_id bigint NOT NULL, route_id bigint, zone_id bigint,
  territory_id bigint, division_id bigint, wing_id bigint);
CREATE TABLE apsis_map.route_geo (route_code text PRIMARY KEY, route_id bigint NOT NULL, zone_id bigint,
  territory_id bigint, division_id bigint, wing_id bigint);
CREATE TABLE apsis_map.sku_map (sku_code text PRIMARY KEY, sku_id bigint NOT NULL, base_per_pack int NOT NULL);
CREATE TABLE apsis_map.user_map (user_code text PRIMARY KEY, user_id bigint NOT NULL);

-- The only live-master write of the tool (--fill-locations) is logged here so it can be undone exactly.
CREATE TABLE apsis_map.fill_log (outlet_id bigint PRIMARY KEY, prev_location_basis text, prev_location_confirmed boolean, run_id bigint);
