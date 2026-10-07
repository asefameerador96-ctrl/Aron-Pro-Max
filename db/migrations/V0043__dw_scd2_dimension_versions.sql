-- V0043 SCD2 dimension history (audit AUD-DA-02 part 3; docs/16 dimension model: surrogate key, valid_from, valid_to,
-- is_current). Expand-only: dw.dim_geo, dim_product and dim_outlet stay the current-state (type 1) tables the projector
-- upserts by natural id (backend-analytics Aggregator); a trigger on each keeps a version table beside it:
--   dw.dim_geo_version (geo_key), dw.dim_product_version (product_key), dw.dim_outlet_version (outlet_key),
--   and the new dw.dim_user + dw.dim_user_version (user_key).
-- A change to any attribute (updated_at ignored) closes the current version on the Asia/Dhaka business date of the change
-- and opens a new one; a second change on the same date rewrites that date's version; a delete closes it. The first
-- version of a natural id has valid_from NULL (since ever). The date is the one on which the projector writes the change
-- (it refreshes the dimensions every few minutes), so near midnight it can be a day after the app-side change date. dw.<dim>_key_on(id, date) returns the key valid on a date;
-- fact_visit and fact_memo gain nullable geo_key, outlet_key and user_key so the projector can store it.
-- dim_user carries no names or contact data (pii: none); the projector fills it like the other dimensions.

SET lock_timeout = '5s';

-- ---------- dim_user (current state) ----------
CREATE TABLE dw.dim_user (
  user_id       bigint PRIMARY KEY,
  role          text NOT NULL,
  designation   text,
  home_zone_id  bigint,
  status        text NOT NULL,
  pilot         boolean NOT NULL DEFAULT false,
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON dw.dim_user (home_zone_id);

-- ---------- version tables ----------
CREATE TABLE dw.dim_geo_version (
  geo_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  LIKE dw.dim_geo,
  valid_from date,
  valid_to   date,
  is_current boolean GENERATED ALWAYS AS (valid_to IS NULL) STORED,
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE TABLE dw.dim_product_version (
  product_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  LIKE dw.dim_product,
  valid_from date,
  valid_to   date,
  is_current boolean GENERATED ALWAYS AS (valid_to IS NULL) STORED,
  EXCLUDE USING gist (sku_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE TABLE dw.dim_outlet_version (
  outlet_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  LIKE dw.dim_outlet,
  valid_from date,
  valid_to   date,
  is_current boolean GENERATED ALWAYS AS (valid_to IS NULL) STORED,
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE TABLE dw.dim_user_version (
  user_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  LIKE dw.dim_user,
  valid_from date,
  valid_to   date,
  is_current boolean GENERATED ALWAYS AS (valid_to IS NULL) STORED,
  EXCLUDE USING gist (user_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE UNIQUE INDEX dim_geo_version_current ON dw.dim_geo_version (route_id) WHERE valid_to IS NULL;
CREATE UNIQUE INDEX dim_product_version_current ON dw.dim_product_version (sku_id) WHERE valid_to IS NULL;
CREATE UNIQUE INDEX dim_outlet_version_current ON dw.dim_outlet_version (outlet_id) WHERE valid_to IS NULL;
CREATE UNIQUE INDEX dim_user_version_current ON dw.dim_user_version (user_id) WHERE valid_to IS NULL;

-- ---------- the generic SCD2 trigger: TG_ARGV[0] = natural key column; version table = <dim>_version ----------
CREATE FUNCTION dw.scd2_track() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  k     text := TG_ARGV[0];
  v     text := TG_TABLE_NAME || '_version';
  d     date := app.dhaka_date(now());
  cols  text;
  id_   bigint;
  has_cur  boolean;
  cur_from date;
  had_any  boolean;
  n        jsonb;
BEGIN
  IF TG_OP = 'DELETE' THEN
    EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2 AND valid_to IS NULL AND (valid_from IS NULL OR valid_from < $1)', v, k)
      USING d, (to_jsonb(OLD) ->> k)::bigint;
    EXECUTE format('DELETE FROM dw.%I WHERE %I = $1 AND valid_to IS NULL AND valid_from = $2', v, k) USING (to_jsonb(OLD) ->> k)::bigint, d;
    RETURN NULL;
  END IF;
  n := to_jsonb(NEW) - 'updated_at';
  id_ := (n ->> k)::bigint;
  IF TG_OP = 'UPDATE' AND n = to_jsonb(OLD) - 'updated_at' THEN
    RETURN NULL;                                                   -- only updated_at moved: no new version
  END IF;
  -- Columns both tables share, so a column added later to the dimension alone never breaks the projector's upsert.
  SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) INTO cols
    FROM pg_attribute a
   WHERE a.attrelid = TG_RELID AND a.attnum > 0 AND NOT a.attisdropped
     AND EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = ('dw.' || v)::regclass AND b.attname = a.attname
                   AND b.attnum > 0 AND NOT b.attisdropped AND b.attgenerated = '');
  EXECUTE format('SELECT true, valid_from FROM dw.%I WHERE %I = $1 AND valid_to IS NULL', v, k) INTO has_cur, cur_from USING id_;
  EXECUTE format('SELECT EXISTS (SELECT 1 FROM dw.%I WHERE %I = $1)', v, k) INTO had_any USING id_;
  IF has_cur AND cur_from >= d THEN
    -- A second change on the same business date rewrites that date's version (also when the clock stepped back).
    EXECUTE format('UPDATE dw.%1$I SET (%2$s) = (SELECT %2$s FROM jsonb_populate_record(NULL::dw.%3$I, $1)) WHERE %4$I = $2 AND valid_to IS NULL',
                   v, cols, TG_TABLE_NAME, k) USING to_jsonb(NEW), id_;
  ELSE
    EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2 AND valid_to IS NULL', v, k) USING d, id_;
    EXECUTE format('INSERT INTO dw.%1$I (%2$s, valid_from) SELECT %2$s, $2 FROM jsonb_populate_record(NULL::dw.%3$I, $1)',
                   v, cols, TG_TABLE_NAME) USING to_jsonb(NEW), CASE WHEN had_any THEN d END;    -- the first version ever is valid since ever
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.scd2_track() IS 'AFTER INSERT/UPDATE/DELETE on a type-1 dimension: keeps <dim>_version as SCD2 (close on the Asia/Dhaka date of the change, open a new version).';

CREATE TRIGGER dim_geo_scd2 AFTER INSERT OR UPDATE OR DELETE ON dw.dim_geo FOR EACH ROW EXECUTE FUNCTION dw.scd2_track('route_id');
CREATE TRIGGER dim_product_scd2 AFTER INSERT OR UPDATE OR DELETE ON dw.dim_product FOR EACH ROW EXECUTE FUNCTION dw.scd2_track('sku_id');
CREATE TRIGGER dim_outlet_scd2 AFTER INSERT OR UPDATE OR DELETE ON dw.dim_outlet FOR EACH ROW EXECUTE FUNCTION dw.scd2_track('outlet_id');
CREATE TRIGGER dim_user_scd2 AFTER INSERT OR UPDATE OR DELETE ON dw.dim_user FOR EACH ROW EXECUTE FUNCTION dw.scd2_track('user_id');

-- Rows already in the current-state tables become their first version.
INSERT INTO dw.dim_geo_version (route_id, route_code, route_name, route_kind, visit_kind, zone_id, zone_name, territory_id, territory_name,
                                division_id, division_name, wing_id, wing_name, status, updated_at)
  SELECT route_id, route_code, route_name, route_kind, visit_kind, zone_id, zone_name, territory_id, territory_name,
         division_id, division_name, wing_id, wing_name, status, updated_at FROM dw.dim_geo;
INSERT INTO dw.dim_product_version (sku_id, sku_code, short_name, base_unit, base_per_pack, variant_id, variant_name, brand_id, brand_name,
                                    segment_id, segment_name, category_id, category_name, category_code, updated_at)
  SELECT sku_id, sku_code, short_name, base_unit, base_per_pack, variant_id, variant_name, brand_id, brand_name,
         segment_id, segment_name, category_id, category_name, category_code, updated_at FROM dw.dim_product;
INSERT INTO dw.dim_outlet_version (outlet_id, outlet_code, outlet_name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class,
                                   outlet_kind, location_confirmed, status, updated_at)
  SELECT outlet_id, outlet_code, outlet_name, route_id, cluster_id, zone_id, channel, sub_channel_id, geo_class,
         outlet_kind, location_confirmed, status, updated_at FROM dw.dim_outlet;

-- ---------- key lookups ----------
CREATE FUNCTION dw.geo_key_on(p_route_id bigint, p_date date) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT geo_key FROM dw.dim_geo_version WHERE route_id = p_route_id
     AND (valid_from IS NULL OR valid_from <= p_date) AND (valid_to IS NULL OR valid_to > p_date) $$;
CREATE FUNCTION dw.product_key_on(p_sku_id bigint, p_date date) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT product_key FROM dw.dim_product_version WHERE sku_id = p_sku_id
     AND (valid_from IS NULL OR valid_from <= p_date) AND (valid_to IS NULL OR valid_to > p_date) $$;
CREATE FUNCTION dw.outlet_key_on(p_outlet_id bigint, p_date date) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT outlet_key FROM dw.dim_outlet_version WHERE outlet_id = p_outlet_id
     AND (valid_from IS NULL OR valid_from <= p_date) AND (valid_to IS NULL OR valid_to > p_date) $$;
CREATE FUNCTION dw.user_key_on(p_user_id bigint, p_date date) RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT user_key FROM dw.dim_user_version WHERE user_id = p_user_id
     AND (valid_from IS NULL OR valid_from <= p_date) AND (valid_to IS NULL OR valid_to > p_date) $$;
COMMENT ON FUNCTION dw.geo_key_on(bigint, date) IS 'geo_key of the route''s dimension version valid on the business date.';
COMMENT ON FUNCTION dw.product_key_on(bigint, date) IS 'product_key of the SKU''s dimension version valid on the business date.';
COMMENT ON FUNCTION dw.outlet_key_on(bigint, date) IS 'outlet_key of the outlet''s dimension version valid on the business date.';
COMMENT ON FUNCTION dw.user_key_on(bigint, date) IS 'user_key of the user''s dimension version valid on the business date.';

-- ---------- facts store the key valid on their business date ----------
ALTER TABLE dw.fact_visit ADD COLUMN geo_key bigint, ADD COLUMN outlet_key bigint, ADD COLUMN user_key bigint;
ALTER TABLE dw.fact_memo ADD COLUMN geo_key bigint, ADD COLUMN outlet_key bigint, ADD COLUMN user_key bigint;

-- ---------- data dictionary ----------
COMMENT ON TABLE dw.dim_user IS 'One row is the current state of a user for reporting (role, designation, home zone, status); no names or contacts.
owner: backend:analytics | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.dim_user.user_id IS 'User (app.app_user.id).';
COMMENT ON COLUMN dw.dim_user.role IS 'Role code of the user.';
COMMENT ON COLUMN dw.dim_user.designation IS 'Designation code of the user (null if none).';
COMMENT ON COLUMN dw.dim_user.home_zone_id IS 'Home zone of the user (null if none).';
COMMENT ON COLUMN dw.dim_user.status IS 'User status (active, disabled).';
COMMENT ON COLUMN dw.dim_user.pilot IS 'True for pilot users.';
COMMENT ON COLUMN dw.dim_user.updated_at IS 'UTC instant of the last update.';
DO $$
DECLARE
  d text; k text; r record;
BEGIN
  FOR d, k IN VALUES ('dim_geo', 'geo_key'), ('dim_product', 'product_key'), ('dim_outlet', 'outlet_key'), ('dim_user', 'user_key') LOOP
    EXECUTE format('COMMENT ON TABLE dw.%I IS %L', d || '_version',
      format('One row is a version of %s valid over [valid_from, valid_to) (SCD2), kept by a trigger on dw.%s.', replace(d, 'dim_', ''), d)
      || E'\n' || 'owner: db | capture: SERVER | retention: master | pii: none');
    FOR r IN SELECT a.attname, col_description(a.attrelid, a.attnum) AS c FROM pg_attribute a
              WHERE a.attrelid = ('dw.' || d)::regclass AND a.attnum > 0 AND NOT a.attisdropped LOOP
      EXECUTE format('COMMENT ON COLUMN dw.%I.%I IS %L', d || '_version', r.attname, r.c);
    END LOOP;
    EXECUTE format('COMMENT ON COLUMN dw.%I.%I IS %L', d || '_version', k, 'Surrogate key of the version; facts store it.');
    EXECUTE format('COMMENT ON COLUMN dw.%I.valid_from IS %L', d || '_version', 'First Asia/Dhaka business date of the version (null = since ever).');
    EXECUTE format('COMMENT ON COLUMN dw.%I.valid_to IS %L', d || '_version', 'Asia/Dhaka business date the version ended, exclusive (null = current).');
    EXECUTE format('COMMENT ON COLUMN dw.%I.is_current IS %L', d || '_version', 'True for the current version (valid_to is null).');
  END LOOP;
  FOR d IN VALUES ('fact_visit'), ('fact_memo') LOOP
    EXECUTE format('COMMENT ON COLUMN dw.%I.geo_key IS %L', d, 'dim_geo_version key valid on the business date (dw.geo_key_on).');
    EXECUTE format('COMMENT ON COLUMN dw.%I.outlet_key IS %L', d, 'dim_outlet_version key valid on the business date (dw.outlet_key_on).');
    EXECUTE format('COMMENT ON COLUMN dw.%I.user_key IS %L', d, 'dim_user_version key valid on the business date (dw.user_key_on).');
  END LOOP;
END $$;

SELECT app.apply_db_role_grants();
