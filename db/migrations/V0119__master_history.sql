-- V0119 master history (data-architecture review 2026-10-25 Medium 9; lead action row 9 item 2, D-DB-MASTER-HISTORY-01):
-- master columns that are overwritten in place (geography parents, route class and status, outlet class, the product
-- tree, SKU attributes, a user's role and home zone) keep their past, so an old business date can be answered with the
-- master as it was on that date, and dw history no longer depends on when the projector happened to run.
--
-- 1. Ten app-side history tables in the pattern of app.route_zone_history (V0042): one row per period, valid_from (null =
--    since the row existed) and valid_to (exclusive, null = current) on the Asia/Dhaka business date, btree_gist exclusion
--    against overlaps, one open row per master row, data_origin. app.route_zone_history itself is unchanged (it keeps the
--    route's zone; app.route_history keeps the route's other columns).
--      app.division_history       wing_id, status                       (audit entity geo_division)
--      app.territory_history      division_id, status                   (geo_territory)
--      app.house_history          territory_id, status                  (geo_house)
--      app.zone_history           territory_id, house_id, status        (geo_zone)
--      app.cluster_history        zone_id, cluster_type, status         (cluster)
--      app.route_history          kind, visit_kind, route_class, status (route)
--      app.outlet_class_history   zone_id, channel, sub_channel_id, geo_class, outlet_kind, price_type, status (outlet;
--                                 route and cluster stay in app.outlet_placement_history)
--      app.product_node_history   parent_id, sales_enable, status       (product_node)
--      app.sku_history            variant_id, category_code, base_unit, base_per_pack, pack_type, entry_unit_default,
--                                 report_unit, report_factor, sales_enable, status (sku)
--      app.user_history           role, designation, home_zone_id, status (user)
--    The registry app.master_history_map names the master, the key column, the tracked columns (pinned, so a later
--    rename of a master column fails loudly instead of silently stopping its history) and the audit entities; it is
--    guarded like the history tables (the SECURITY DEFINER trigger reads it).
-- 2. app.master_history_track(), AFTER INSERT and AFTER UPDATE (the latter only WHEN a tracked column or data_origin
--    differs, so other writes pay nothing) on each master: an insert opens the first period (since ever);
--    an update opens a new period on the business date of the change only when a tracked column changed. A no-op write,
--    a change of an untracked column (name, coordinates, version) and a data_origin re-tag open none; a re-tag copies the
--    master's origin onto all its history rows. A second change on the same date rewrites that date's period; a change
--    back to the previous period's values on the same date removes the date's period and reopens the previous one.
--    SECURITY DEFINER (search_path pg_temp last) so the API and worker roles need no write grant on history; the history
--    tables refuse every direct write that is not the table owner's (app.guard_master_history, the owner check of
--    V0116's app.may_restamp_origin), because app.apply_db_role_grants() gives api_rw INSERT and UPDATE on every app table.
-- 3. Seeded from app.audit_log by app.rebuild_master_history(): starting from the master's current row it walks the
--    audit rows of the master's entities newest first and applies each row's before-image, so every audited change of a
--    tracked column (admin API updates, outlet import updates, outlet-kind batches, house merges) becomes a period
--    starting on the audit row's business date. What the audit log never saw (migrations such as V0104's lighter pack
--    size, seed SQL, direct SQL, ingest-side writes) cannot be dated: such a change is attributed to the date of the
--    nearest newer audited change of the row (to "since ever" when there is none), an audited value it replaced can be
--    lost, and for the full-snapshot entities (sku, product_node) any edit before an unaudited change dates that change
--    too. One unreadable before-image stops the walk: the row's older past stays unknown. The function is the owner's
--    (refuses other roles), locks the master while it runs, and REPLACES the whole history table: a re-run also drops
--    the periods the trigger recorded since this migration from writes the audit log never saw, so it is for this
--    migration and an empty account, not for routine use.
-- 4. dw: four stable views per the view contract, contract version 1.1 (columns added, none removed):
--    v_dim_geo_history (route -> zone -> territory -> division -> wing as of each period: the intersection of the route,
--    zone, territory and division histories; a change of a column the view does not show, such as a zone's status,
--    splits a period into neighbours with equal values), v_dim_outlet_history, v_dim_product_history (SKU -> variant -> brand ->
--    segment -> category) and v_dim_user_history; each with valid_from, valid_to, is_current, data_origin and
--    is_effective (status active in that period), no names, no coordinates.

SET lock_timeout = '5s';

-- ---------- 1. registry and history tables ----------
CREATE TABLE app.master_history_map (
  history_table  text PRIMARY KEY,
  master_table   text NOT NULL UNIQUE,
  key_col        text NOT NULL,
  tracked_cols   text[] NOT NULL CHECK (cardinality(tracked_cols) >= 1),
  audit_entities text[] NOT NULL
);
INSERT INTO app.master_history_map (history_table, master_table, key_col, tracked_cols, audit_entities) VALUES
  ('division_history', 'division', 'division_id', ARRAY['wing_id', 'status'], ARRAY['geo_division']),
  ('territory_history', 'territory', 'territory_id', ARRAY['division_id', 'status'], ARRAY['geo_territory']),
  ('house_history', 'house', 'house_id', ARRAY['territory_id', 'status'], ARRAY['geo_house']),
  ('zone_history', 'zone', 'zone_id', ARRAY['territory_id', 'house_id', 'status'], ARRAY['geo_zone']),
  ('cluster_history', 'cluster', 'cluster_id', ARRAY['zone_id', 'cluster_type', 'status'], ARRAY['cluster']),
  ('route_history', 'route', 'route_id', ARRAY['kind', 'visit_kind', 'route_class', 'status'], ARRAY['route']),
  ('outlet_class_history', 'outlet', 'outlet_id',
   ARRAY['zone_id', 'channel', 'sub_channel_id', 'geo_class', 'outlet_kind', 'price_type', 'status'], ARRAY['outlet']),
  ('product_node_history', 'product_node', 'node_id', ARRAY['parent_id', 'sales_enable', 'status'], ARRAY['product_node']),
  ('sku_history', 'sku', 'sku_id',
   ARRAY['variant_id', 'category_code', 'base_unit', 'base_per_pack', 'pack_type', 'entry_unit_default', 'report_unit',
         'report_factor', 'sales_enable', 'status'], ARRAY['sku']),
  ('user_history', 'app_user', 'user_id', ARRAY['role', 'designation', 'home_zone_id', 'status'], ARRAY['user']);

CREATE TABLE app.division_history (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  division_id bigint NOT NULL REFERENCES app.division(id) ON DELETE CASCADE,
  wing_id     bigint NOT NULL,
  status      text NOT NULL,
  valid_from  date,
  valid_to    date,
  data_origin app.data_origin NOT NULL DEFAULT 'production',
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (division_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.territory_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  territory_id bigint NOT NULL REFERENCES app.territory(id) ON DELETE CASCADE,
  division_id  bigint NOT NULL,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (territory_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.house_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  house_id     bigint NOT NULL REFERENCES app.house(id) ON DELETE CASCADE,
  territory_id bigint NOT NULL,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (house_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.zone_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  zone_id      bigint NOT NULL REFERENCES app.zone(id) ON DELETE CASCADE,
  territory_id bigint NOT NULL,
  house_id     bigint,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (zone_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.cluster_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cluster_id   bigint NOT NULL REFERENCES app.cluster(id) ON DELETE CASCADE,
  zone_id      bigint NOT NULL,
  cluster_type text,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (cluster_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.route_history (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id    bigint NOT NULL REFERENCES app.route(id) ON DELETE CASCADE,
  kind        text NOT NULL,
  visit_kind  text,
  route_class text NOT NULL,
  status      text NOT NULL,
  valid_from  date,
  valid_to    date,
  data_origin app.data_origin NOT NULL DEFAULT 'production',
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.outlet_class_history (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id      bigint NOT NULL REFERENCES app.outlet(id) ON DELETE CASCADE,
  zone_id        bigint NOT NULL,
  channel        text NOT NULL,
  sub_channel_id bigint,
  geo_class      text,
  outlet_kind    text NOT NULL,
  price_type     text NOT NULL,
  status         text NOT NULL,
  valid_from     date,
  valid_to       date,
  data_origin    app.data_origin NOT NULL DEFAULT 'production',
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.product_node_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  node_id      bigint NOT NULL REFERENCES app.product_node(id) ON DELETE CASCADE,
  parent_id    bigint,
  sales_enable boolean NOT NULL,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (node_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.sku_history (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sku_id             bigint NOT NULL REFERENCES app.sku(id) ON DELETE CASCADE,
  variant_id         bigint NOT NULL,
  category_code      text NOT NULL,
  base_unit          text NOT NULL,
  base_per_pack      int NOT NULL,
  pack_type          text,
  entry_unit_default text NOT NULL,
  report_unit        text,
  report_factor      numeric(16,3) NOT NULL,
  sales_enable       boolean NOT NULL,
  status             text NOT NULL,
  valid_from         date,
  valid_to           date,
  data_origin        app.data_origin NOT NULL DEFAULT 'production',
  created_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (sku_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

CREATE TABLE app.user_history (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      bigint NOT NULL REFERENCES app.app_user(id) ON DELETE CASCADE,
  role         text NOT NULL,
  designation  text,
  home_zone_id bigint,
  status       text NOT NULL,
  valid_from   date,
  valid_to     date,
  data_origin  app.data_origin NOT NULL DEFAULT 'production',
  created_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (user_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

-- One open period per master row.
DO $$
DECLARE m record;
BEGIN
  FOR m IN SELECT * FROM app.master_history_map LOOP
    EXECUTE format('CREATE UNIQUE INDEX %I ON app.%I (%I) WHERE valid_to IS NULL', m.history_table || '_current', m.history_table, m.key_col);
  END LOOP;
END $$;

-- ---------- 2. maintenance ----------
CREATE FUNCTION app.master_history_cols(p_history text) RETURNS text[]
LANGUAGE sql STABLE
AS $$ SELECT tracked_cols FROM app.master_history_map WHERE history_table = p_history $$;
COMMENT ON FUNCTION app.master_history_cols(text) IS 'Tracked columns of a master history table (app.master_history_map.tracked_cols).';

-- Every tracked column exists on both tables with the same type and nullability.
DO $$
DECLARE bad text;
BEGIN
  SELECT string_agg(m.history_table || '.' || c, ', ') INTO bad
    FROM app.master_history_map m CROSS JOIN LATERAL unnest(m.tracked_cols) c
   WHERE NOT EXISTS (SELECT 1 FROM pg_attribute h JOIN pg_attribute a ON a.attrelid = format('app.%I', m.master_table)::regclass
                       AND a.attname = h.attname AND a.atttypid = h.atttypid AND a.atttypmod = h.atttypmod AND a.attnotnull = h.attnotnull
                      WHERE h.attrelid = format('app.%I', m.history_table)::regclass AND h.attname = c AND NOT h.attisdropped AND NOT a.attisdropped);
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'V0119: tracked columns that do not match their master: %', bad;
  END IF;
END $$;

CREATE FUNCTION app.jsonb_pick(p jsonb, p_keys text[]) RETURNS jsonb
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT coalesce(jsonb_object_agg(e.key, e.value), '{}'::jsonb) FROM jsonb_each(p) e WHERE e.key = ANY (p_keys) $$;
COMMENT ON FUNCTION app.jsonb_pick(jsonb, text[]) IS 'The members of a JSON object whose keys are in the list.';

-- TG_ARGV[0] = history table, TG_ARGV[1] = its key column (the master's id).
CREATE FUNCTION app.master_history_track() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app, pg_temp
AS $$
DECLARE
  h        text := TG_ARGV[0];
  k        text := TG_ARGV[1];
  d        date := app.dhaka_date(now());
  n        jsonb := to_jsonb(NEW);
  cols     text[] := app.master_history_cols(TG_ARGV[0]);
  collist  text;
  id_      bigint := (to_jsonb(NEW) ->> 'id')::bigint;
  origin   app.data_origin := coalesce((to_jsonb(NEW) ->> 'data_origin')::app.data_origin, 'production');
  cur_id   bigint;
  cur_from date;
  prev_id  bigint;
  prev     jsonb;
BEGIN
  collist := (SELECT string_agg(quote_ident(c), ', ') FROM unnest(cols) c);
  IF TG_OP = 'INSERT' THEN
    EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, data_origin) SELECT $1, %3$s, $2 FROM jsonb_populate_record(NULL::app.%1$I, $3)', h, k, collist)
      USING id_, origin, n;
    RETURN NULL;
  END IF;
  IF n -> 'data_origin' IS DISTINCT FROM to_jsonb(OLD) -> 'data_origin' THEN
    -- A re-tag is classification, not a change: every period of the row takes the master's origin.
    EXECUTE format('UPDATE app.%I SET data_origin = $1 WHERE %I = $2 AND data_origin IS DISTINCT FROM $1', h, k) USING origin, id_;
  END IF;
  IF app.jsonb_pick(n, cols) = app.jsonb_pick(to_jsonb(OLD), cols) THEN
    RETURN NULL;                                                     -- no tracked column moved: no new period
  END IF;
  EXECUTE format('SELECT id, valid_from FROM app.%I WHERE %I = $1 AND valid_to IS NULL', h, k) INTO cur_id, cur_from USING id_;
  IF cur_id IS NULL THEN
    -- No open period (a row older than its history, or an open period removed by hand): the old values from the end
    -- of the last closed period (since ever when there is none) to today, the new ones from today.
    EXECUTE format('SELECT max(valid_to) FROM app.%I WHERE %I = $1', h, k) INTO cur_from USING id_;
    IF cur_from IS NULL OR cur_from < d THEN
      EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, valid_from, valid_to, data_origin) SELECT $1, %3$s, $2, $3, $4 FROM jsonb_populate_record(NULL::app.%1$I, $5)', h, k, collist)
        USING id_, cur_from, d, origin, to_jsonb(OLD);
    END IF;
    EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, valid_from, data_origin) SELECT $1, %3$s, $2, $3 FROM jsonb_populate_record(NULL::app.%1$I, $4)', h, k, collist)
      USING id_, greatest(d, cur_from), origin, n;
  ELSIF cur_from IS NOT NULL AND cur_from >= d THEN
    -- A second change on the same business date (also when the clock stepped back) rewrites that date's period, or,
    -- when it restores the values of the period that ended on that date, removes it and reopens that period.
    EXECUTE format('SELECT id, to_jsonb(x) FROM app.%I x WHERE %I = $1 AND valid_to = $2', h, k) INTO prev_id, prev USING id_, cur_from;
    IF prev_id IS NOT NULL AND app.jsonb_pick(prev, cols) = app.jsonb_pick(n, cols) THEN
      EXECUTE format('DELETE FROM app.%I WHERE id = $1', h) USING cur_id;
      EXECUTE format('UPDATE app.%I SET valid_to = NULL WHERE id = $1', h) USING prev_id;
    ELSE
      EXECUTE format('UPDATE app.%1$I SET (%2$s) = (SELECT %2$s FROM jsonb_populate_record(NULL::app.%1$I, $1)) WHERE id = $2', h, collist)
        USING n, cur_id;
    END IF;
  ELSE
    EXECUTE format('UPDATE app.%I SET valid_to = $1 WHERE id = $2', h) USING d, cur_id;
    EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, valid_from, data_origin) SELECT $1, %3$s, $2, $3 FROM jsonb_populate_record(NULL::app.%1$I, $4)', h, k, collist)
      USING id_, d, origin, n;
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION app.master_history_track() IS 'AFTER INSERT/UPDATE on a master: keeps its app.*_history table (app.master_history_map); a period opens on the Asia/Dhaka date of a change of a tracked column, never on a no-op write or a data_origin re-tag.';

CREATE FUNCTION app.guard_master_history() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT app.may_restamp_origin(TG_RELID) THEN
    RAISE EXCEPTION 'app.% is written only by the master''s history trigger or the table owner', TG_TABLE_NAME
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
COMMENT ON FUNCTION app.guard_master_history() IS 'BEFORE INSERT/UPDATE/DELETE on a master history table and on app.master_history_map: only the table owner (the SECURITY DEFINER history trigger, migrations, the seed) writes it.';

-- ---------- 3. recovery from the audit log ----------
-- Rebuilds one history table from the current masters and app.audit_log (owner only; the guard refuses other roles).
-- Returns the number of periods written.
CREATE FUNCTION app.rebuild_master_history(p_history text) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  m        app.master_history_map;
  cols     text[];
  req      text[];
  collist  text;
  r        record;
  a        record;
  state    jsonb;
  nxt      jsonb;
  froms    date[];
  states   jsonb[];
  last_d   date;
  d        date;
  i        int;
  vfrom    date;
  pstate   jsonb;
  has_orig boolean;
  total    bigint := 0;
BEGIN
  SELECT * INTO STRICT m FROM app.master_history_map WHERE history_table = p_history;
  IF NOT app.may_restamp_origin(format('app.%I', p_history)::regclass) THEN
    RAISE EXCEPTION 'app.rebuild_master_history: only the owner of app.% may rebuild it', p_history USING ERRCODE = 'insufficient_privilege';
  END IF;
  cols := app.master_history_cols(p_history);
  collist := (SELECT string_agg(quote_ident(c), ', ') FROM unnest(cols) c);
  req := (SELECT array_agg(attname::text) FROM pg_attribute WHERE attrelid = format('app.%I', p_history)::regclass
            AND attname = ANY (cols) AND attnotnull);
  has_orig := EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = format('app.%I', m.master_table)::regclass AND attname = 'data_origin' AND NOT attisdropped);
  EXECUTE format('LOCK TABLE app.%I IN SHARE ROW EXCLUSIVE MODE', m.master_table);
  EXECUTE format('DELETE FROM app.%I', p_history);
  FOR r IN EXECUTE format('SELECT x.id, to_jsonb(x) AS j FROM app.%I x ORDER BY x.id', m.master_table) LOOP
    state := app.jsonb_pick(r.j, cols);
    froms := '{}';
    states := '{}';
    last_d := NULL;
    -- Newest first: the state after an audited change holds from that change's business date; its before-image gives
    -- the state before it. The newest change of a date wins that date.
    FOR a IN SELECT x.business_date, x.before FROM app.audit_log x
              WHERE x.entity = ANY (m.audit_entities) AND x.entity_id = r.id::text
                AND jsonb_typeof(x.before) = 'object' AND x.before ?| cols
              ORDER BY x.chain_seq DESC
    LOOP
      d := CASE WHEN last_d IS NULL THEN a.business_date ELSE least(a.business_date, last_d) END;
      IF last_d IS NULL OR d < last_d THEN
        froms := froms || d;
        states := states || state;
      END IF;
      last_d := d;
      BEGIN
        EXECUTE format('SELECT to_jsonb(jsonb_populate_record(NULL::app.%I, $1))', p_history) INTO nxt
          USING state || app.jsonb_pick(a.before, cols);
        nxt := app.jsonb_pick(nxt, cols);
        IF EXISTS (SELECT 1 FROM unnest(req) c WHERE nxt ->> c IS NULL) THEN
          EXIT;                                                    -- a required value missing: earlier values stay unknown
        END IF;
        state := nxt;
      EXCEPTION WHEN data_exception OR invalid_text_representation THEN
        EXIT;                                                      -- an unreadable before-image: earlier values stay unknown
      END;
    END LOOP;
    -- Oldest first, merging neighbours with equal values.
    vfrom := NULL;
    pstate := state;
    FOR i IN REVERSE coalesce(array_length(froms, 1), 0) .. 1 LOOP
      IF states[i] IS DISTINCT FROM pstate THEN
        EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, valid_from, valid_to) SELECT $1, %3$s, $2, $3 FROM jsonb_populate_record(NULL::app.%1$I, $4)',
                       p_history, m.key_col, collist) USING r.id, vfrom, froms[i], pstate;
        total := total + 1;
        vfrom := froms[i];
        pstate := states[i];
      END IF;
    END LOOP;
    EXECUTE format('INSERT INTO app.%1$I (%2$I, %3$s, valid_from) SELECT $1, %3$s, $2 FROM jsonb_populate_record(NULL::app.%1$I, $3)',
                   p_history, m.key_col, collist) USING r.id, vfrom, pstate;
    total := total + 1;
  END LOOP;
  IF has_orig THEN
    EXECUTE format('UPDATE app.%1$I h SET data_origin = x.data_origin FROM app.%2$I x WHERE x.id = h.%3$I AND h.data_origin IS DISTINCT FROM x.data_origin',
                   p_history, m.master_table, m.key_col);
  END IF;
  RETURN total;
END $$;
COMMENT ON FUNCTION app.rebuild_master_history(text) IS 'Replaces one master history table with a rebuild from the current masters and the before-images of app.audit_log (a period per audited change of a tracked column, dated by the audit row''s business date); drops the periods the trigger recorded from unaudited writes, so it is for V0119 and an empty account. Owner only; locks the master. Returns the periods written.';

DO $$
DECLARE
  m    record;
  n    bigint;
  diff text;
BEGIN
  FOR m IN SELECT * FROM app.master_history_map ORDER BY history_table LOOP
    n := app.rebuild_master_history(m.history_table);
    RAISE NOTICE 'V0119: app.% has % periods', m.history_table, n;
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.guard_master_history()',
                   m.history_table || '_guard', m.history_table);
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT ON app.%I FOR EACH ROW EXECUTE FUNCTION app.master_history_track(%L, %L)',
                   m.master_table || '_history_open', m.master_table, m.history_table, m.key_col);
    diff := (SELECT string_agg(format('OLD.%1$I IS DISTINCT FROM NEW.%1$I', c), ' OR ')
               FROM unnest(m.tracked_cols || CASE WHEN m.master_table IN ('outlet', 'route', 'app_user') THEN ARRAY['data_origin'] ELSE '{}'::text[] END) c);
    EXECUTE format('CREATE TRIGGER %I AFTER UPDATE ON app.%I FOR EACH ROW WHEN (%s) EXECUTE FUNCTION app.master_history_track(%L, %L)',
                   m.master_table || '_history_track', m.master_table, diff, m.history_table, m.key_col);
  END LOOP;
END $$;
-- The registry steers the SECURITY DEFINER trigger: only the owner changes it.
CREATE TRIGGER master_history_map_guard BEFORE INSERT OR UPDATE OR DELETE ON app.master_history_map
  FOR EACH ROW EXECUTE FUNCTION app.guard_master_history();

-- ---------- 4. dw views (contract 1.1) ----------
CREATE VIEW dw.v_dim_geo_history AS
SELECT rz.route_id, rz.zone_id, z.house_id, z.territory_id, t.division_id, dv.wing_id,
       rh.kind AS route_kind, rh.visit_kind, rh.route_class, rh.status,
       lower(p.r) AS valid_from, upper(p.r) AS valid_to, upper_inf(p.r) AS is_current,
       rh.data_origin, rh.status = 'active' AS is_effective
  FROM app.route_zone_history rz
  JOIN app.route_history rh ON rh.route_id = rz.route_id
   AND daterange(rh.valid_from, rh.valid_to, '[)') && daterange(rz.valid_from, rz.valid_to, '[)')
  JOIN app.zone_history z ON z.zone_id = rz.zone_id
   AND daterange(z.valid_from, z.valid_to, '[)') && daterange(rz.valid_from, rz.valid_to, '[)') * daterange(rh.valid_from, rh.valid_to, '[)')
  JOIN app.territory_history t ON t.territory_id = z.territory_id
   AND daterange(t.valid_from, t.valid_to, '[)') && daterange(rz.valid_from, rz.valid_to, '[)') * daterange(rh.valid_from, rh.valid_to, '[)') * daterange(z.valid_from, z.valid_to, '[)')
  JOIN app.division_history dv ON dv.division_id = t.division_id
   AND daterange(dv.valid_from, dv.valid_to, '[)') && daterange(rz.valid_from, rz.valid_to, '[)') * daterange(rh.valid_from, rh.valid_to, '[)') * daterange(z.valid_from, z.valid_to, '[)') * daterange(t.valid_from, t.valid_to, '[)')
 CROSS JOIN LATERAL (SELECT daterange(rz.valid_from, rz.valid_to, '[)') * daterange(rh.valid_from, rh.valid_to, '[)') * daterange(z.valid_from, z.valid_to, '[)')
                            * daterange(t.valid_from, t.valid_to, '[)') * daterange(dv.valid_from, dv.valid_to, '[)') AS r) p;

CREATE VIEW dw.v_dim_outlet_history AS
SELECT h.outlet_id, h.zone_id, h.channel, h.sub_channel_id, h.geo_class, h.outlet_kind, h.price_type, h.status,
       h.valid_from, h.valid_to, h.valid_to IS NULL AS is_current, h.data_origin, h.status = 'active' AS is_effective
  FROM app.outlet_class_history h;

CREATE VIEW dw.v_dim_product_history AS
SELECT s.sku_id, s.variant_id, b.node_id AS brand_id, sg.node_id AS segment_id, c.node_id AS category_id, s.category_code,
       s.base_unit, s.base_per_pack, s.pack_type, s.entry_unit_default, s.report_unit, s.report_factor, s.sales_enable, s.status,
       lower(p.r) AS valid_from, upper(p.r) AS valid_to, upper_inf(p.r) AS is_current,
       'production'::app.data_origin AS data_origin, s.status = 'active' AS is_effective
  FROM app.sku_history s
  JOIN app.product_node_history v ON v.node_id = s.variant_id
   AND daterange(v.valid_from, v.valid_to, '[)') && daterange(s.valid_from, s.valid_to, '[)')
  JOIN app.product_node_history b ON b.node_id = v.parent_id
   AND daterange(b.valid_from, b.valid_to, '[)') && daterange(s.valid_from, s.valid_to, '[)') * daterange(v.valid_from, v.valid_to, '[)')
  JOIN app.product_node_history sg ON sg.node_id = b.parent_id
   AND daterange(sg.valid_from, sg.valid_to, '[)') && daterange(s.valid_from, s.valid_to, '[)') * daterange(v.valid_from, v.valid_to, '[)') * daterange(b.valid_from, b.valid_to, '[)')
  JOIN app.product_node_history c ON c.node_id = sg.parent_id
   AND daterange(c.valid_from, c.valid_to, '[)') && daterange(s.valid_from, s.valid_to, '[)') * daterange(v.valid_from, v.valid_to, '[)') * daterange(b.valid_from, b.valid_to, '[)') * daterange(sg.valid_from, sg.valid_to, '[)')
 CROSS JOIN LATERAL (SELECT daterange(s.valid_from, s.valid_to, '[)') * daterange(v.valid_from, v.valid_to, '[)') * daterange(b.valid_from, b.valid_to, '[)')
                            * daterange(sg.valid_from, sg.valid_to, '[)') * daterange(c.valid_from, c.valid_to, '[)') AS r) p;

CREATE VIEW dw.v_dim_user_history AS
SELECT h.user_id, h.role, h.designation, h.home_zone_id, h.status,
       h.valid_from, h.valid_to, h.valid_to IS NULL AS is_current, h.data_origin, h.status = 'active' AS is_effective
  FROM app.user_history h;

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.1' $$;

-- ---------- data dictionary ----------
COMMENT ON TABLE app.master_history_map IS 'Registry of the master history tables (V0119): the master each one follows, its key column and the audit entities its past was recovered from.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN app.master_history_map.history_table IS 'History table in schema app.';
COMMENT ON COLUMN app.master_history_map.master_table IS 'Master table in schema app whose tracked columns the history keeps.';
COMMENT ON COLUMN app.master_history_map.key_col IS 'Column of the history table that holds the master''s id.';
COMMENT ON COLUMN app.master_history_map.tracked_cols IS 'Master columns the history keeps; a period opens when one of them changes.';
COMMENT ON COLUMN app.master_history_map.audit_entities IS 'app.audit_log entity names whose before-images date the master''s past changes.';

DO $$
DECLARE
  m record;
  c text;
  descr text;
BEGIN
  FOR m IN SELECT x.*, CASE x.history_table
             WHEN 'division_history' THEN 'the wing a division belonged to and its status'
             WHEN 'territory_history' THEN 'the division a territory belonged to and its status'
             WHEN 'house_history' THEN 'the territory a distribution house belonged to and its status'
             WHEN 'zone_history' THEN 'the territory and house a zone belonged to and its status'
             WHEN 'cluster_history' THEN 'the zone a cluster belonged to, its type and status'
             WHEN 'route_history' THEN 'a route''s kind, visit kind, class and status (its zone is in app.route_zone_history)'
             WHEN 'outlet_class_history' THEN 'an outlet''s zone, channel, sub-channel, geo class, kind, price type and status (route and cluster are in app.outlet_placement_history)'
             WHEN 'product_node_history' THEN 'the parent of a product tree node, its sales switch and status'
             WHEN 'sku_history' THEN 'a SKU''s variant, category, units, pack size, report unit, sales switch and status'
             WHEN 'user_history' THEN 'a user''s role, designation, home zone and status'
           END AS what
             FROM app.master_history_map x LOOP
    EXECUTE format('COMMENT ON TABLE app.%I IS %L', m.history_table,
      'One row is a period of ' || m.what || ' (V0119); written by the history trigger on app.' || m.master_table
      || ', seeded from app.audit_log.' || E'\n' || 'owner: db | capture: SERVER | retention: master | pii: none');
    EXECUTE format('COMMENT ON COLUMN app.%I.id IS %L', m.history_table, 'Server surrogate key.');
    EXECUTE format('COMMENT ON COLUMN app.%I.%I IS %L', m.history_table, m.key_col, 'The app.' || m.master_table || ' row.');
    EXECUTE format('COMMENT ON COLUMN app.%I.valid_from IS %L', m.history_table, 'First Asia/Dhaka business date of the period (null = since the row existed).');
    EXECUTE format('COMMENT ON COLUMN app.%I.valid_to IS %L', m.history_table, 'Asia/Dhaka business date the period ended, exclusive (null = current).');
    EXECUTE format('COMMENT ON COLUMN app.%I.data_origin IS %L', m.history_table,
      CASE WHEN m.master_table IN ('outlet', 'route', 'app_user') THEN 'Origin of the master row (production, pilot, test, seed), kept equal to the master''s on every period.'
           ELSE 'Always production: this master carries no origin of its own.' END);
    EXECUTE format('COMMENT ON COLUMN app.%I.created_at IS %L', m.history_table, 'UTC instant the row was inserted on the server.');
    FOREACH c IN ARRAY app.master_history_cols(m.history_table) LOOP
      descr := col_description(format('app.%I', m.master_table)::regclass,
                               (SELECT attnum FROM pg_attribute WHERE attrelid = format('app.%I', m.master_table)::regclass AND attname = c));
      EXECUTE format('COMMENT ON COLUMN app.%I.%I IS %L', m.history_table, c,
        coalesce(regexp_replace(descr, ';? *allowed values are listed under constraints\.?', ''), 'Value of app.' || m.master_table || '.' || c)
        || ' (as it was in the period).');
    END LOOP;
  END LOOP;
END $$;

COMMENT ON VIEW dw.v_dim_geo_history IS 'Stable view: a route''s place in the geography (zone, house, territory, division, wing) and its kind, class and status per period, from the app history tables (a new period whenever one of the histories behind it changes; neighbours can carry equal values). is_effective = route active in the period.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_outlet_history IS 'Stable view: an outlet''s zone and class (channel, sub-channel, geo class, kind, price type, status) per period (app.outlet_class_history); no names, owner, phone or coordinates. is_effective = outlet active in the period.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_product_history IS 'Stable view: a SKU''s attributes and its variant, brand, segment and category per period (app.sku_history, app.product_node_history; a new period whenever one of them changes, neighbours can carry equal values). is_effective = SKU active in the period.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_user_history IS 'Stable view: a user''s role, designation, home zone and status per period (app.user_history); no names or logins. is_effective = account active in the period.
owner: db | capture: SERVER | retention: master | pii: none';

DO $$
DECLARE
  r record;
  c record;
BEGIN
  FOR r IN SELECT * FROM (VALUES ('v_dim_geo_history', 'app.route_history'), ('v_dim_outlet_history', 'app.outlet_class_history'),
                                 ('v_dim_product_history', 'app.sku_history'), ('v_dim_user_history', 'app.user_history')) AS v(view, src)
  LOOP
    FOR c IN SELECT a.attname, col_description(s.attrelid, s.attnum) AS d
               FROM pg_attribute a JOIN pg_attribute s ON s.attrelid = r.src::regclass AND s.attname = a.attname AND s.attnum > 0
              WHERE a.attrelid = ('dw.' || r.view)::regclass AND a.attnum > 0 AND col_description(s.attrelid, s.attnum) IS NOT NULL
    LOOP
      EXECUTE format('COMMENT ON COLUMN dw.%I.%I IS %L', r.view, c.attname, c.d);
    END LOOP;
    EXECUTE format('COMMENT ON COLUMN dw.%I.is_current IS %L', r.view, 'True for the period that is still open (valid_to null).');
    EXECUTE format('COMMENT ON COLUMN dw.%I.is_effective IS %L', r.view, 'True when the row counts in an ordinary total (the rule of this view is in its description).');
  END LOOP;
END $$;
COMMENT ON COLUMN dw.v_dim_geo_history.route_id IS 'Route.';
COMMENT ON COLUMN dw.v_dim_geo_history.zone_id IS 'Zone of the route in the period (app.route_zone_history).';
COMMENT ON COLUMN dw.v_dim_geo_history.house_id IS 'Distribution house of the zone in the period.';
COMMENT ON COLUMN dw.v_dim_geo_history.territory_id IS 'Territory of the zone in the period.';
COMMENT ON COLUMN dw.v_dim_geo_history.division_id IS 'Division of the territory in the period.';
COMMENT ON COLUMN dw.v_dim_geo_history.wing_id IS 'Wing of the division in the period.';
COMMENT ON COLUMN dw.v_dim_geo_history.route_kind IS 'Route kind (sr, amo) in the period.';
COMMENT ON COLUMN dw.v_dim_geo_history.valid_from IS 'First Asia/Dhaka business date of the period (null = since the route existed).';
COMMENT ON COLUMN dw.v_dim_geo_history.valid_to IS 'Asia/Dhaka business date the period ended, exclusive (null = current).';
COMMENT ON COLUMN dw.v_dim_product_history.brand_id IS 'Brand node of the variant in the period.';
COMMENT ON COLUMN dw.v_dim_product_history.segment_id IS 'Segment node of the brand in the period.';
COMMENT ON COLUMN dw.v_dim_product_history.category_id IS 'Category node of the segment in the period.';
COMMENT ON COLUMN dw.v_dim_product_history.valid_from IS 'First Asia/Dhaka business date of the period (null = since the SKU existed).';
COMMENT ON COLUMN dw.v_dim_product_history.valid_to IS 'Asia/Dhaka business date the period ended, exclusive (null = current).';
COMMENT ON COLUMN dw.v_dim_product_history.data_origin IS 'Always production: products carry no origin of their own (every SKU is catalogue data).';

SELECT app.apply_db_role_grants();
