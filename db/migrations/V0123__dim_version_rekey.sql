-- V0123 dw dimension versions follow the app history (backend-reports request
-- docs/requests/backend-reports-db-dim-version-rekey.md, review High 1; D-DB-DIM-REKEY-01).
-- Before: dw.dim_geo_version, dim_outlet_version, dim_product_version and dim_user_version (V0043) were dated by the
-- Asia/Dhaka date on which the projector wrote a dimension change (dw.scd2_track), and the first version was "since ever"
-- with the attributes of the day V0043 ran. Now the versions are rebuilt from the V0119 app history:
--   geo     dw.v_dim_geo_history (route kind, visit kind, class, status; zone, house, territory, division, wing),
--   outlet  dw.v_dim_outlet_history crossed with app.outlet_placement_history (route, cluster),
--   product dw.v_dim_product_history (units, pack size, variant, brand, segment, category, category code),
--   user    dw.v_dim_user_history (role, designation, home zone, status).
-- Those columns are versioned AS WAS; names, codes, pilot and location_confirmed are type 1 (current value of the node the
-- version points at, looked up the way the projector does: backend-analytics Aggregator and DashboardSummaries; the
-- trigger refreshes them for the ids whose dimension row changed, a full rekey for all). Neighbour periods with equal
-- versioned columns are one version, and the first version of an id is valid since ever (V0043's convention).
-- 1. dw.rekey_dim_versions(dim, ids): a version keeps its surrogate key when its natural id, valid_from and versioned
--    columns are unchanged (valid_to and the type-1 columns are updated in place, so closing the current version keeps
--    its key); every other version of an id with history is deleted and the history's periods are inserted, and the
--    facts (fact_memo, fact_visit) of every id whose versions changed are re-stamped from the earliest changed version
--    on (dw.restamp_fact_keys), so no fact keeps a deleted key. An id without history, without a dimension row, or whose
--    history's current period disagrees with the dimension row on a versioned column (a write the history did not see:
--    app.outlet_placement_history is written by the admin API, not by a trigger; house_id does not count) is left
--    alone. One rekey of a dimension runs at a time (advisory lock); the periods go through an analyzed temp table. Invoker rights: worker_rw (the projector, and jobs_rw through its membership) and the
--    owner write; api_rw, web_ro and bi_reader fail on the version-table privilege. Idempotent: a second run changes nothing.
-- 2. The dimension triggers: INSERT and UPDATE are statement level (dw.dim_versions_follow, transition tables): the ids
--    whose kept columns moved are rekeyed in one call; an id the rekey leaves alone takes V0043's step (the date of the
--    projector write, dw.scd2_open_version) and its facts from today are re-stamped. An updated_at-only refresh does
--    nothing. DELETE stays row level and unchanged (dw.scd2_track).
-- 3. One rekey of all four dimensions here, then every fact key that still differs (pilot size on dev; the final
--    account migrates from empty).
-- No dw.v_* view changes: the view contract stays 1.2.

SET lock_timeout = '5s';

-- ---------- 0. btree lookups by id and start (the GiST exclusion index keeps the no-overlap rule) ----------
-- The version tables hold one row per master per change (pilot: a few hundred rows; fleet: tens of thousands).
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX dim_geo_version_key_from ON dw.dim_geo_version (route_id, valid_from);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX dim_outlet_version_key_from ON dw.dim_outlet_version (outlet_id, valid_from);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX dim_product_version_key_from ON dw.dim_product_version (sku_id, valid_from);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX dim_user_version_key_from ON dw.dim_user_version (user_id, valid_from);
COMMENT ON INDEX dw.dim_geo_version_key_from IS 'Lookup of a route''s versions by start (V0123).';
COMMENT ON INDEX dw.dim_outlet_version_key_from IS 'Lookup of an outlet''s versions by start (V0123).';
COMMENT ON INDEX dw.dim_product_version_key_from IS 'Lookup of a SKU''s versions by start (V0123).';
COMMENT ON INDEX dw.dim_user_version_key_from IS 'Lookup of a user''s versions by start (V0123).';

-- ---------- 1. history periods per dimension (unmerged; rekey merges equal neighbours) ----------
CREATE FUNCTION dw.dim_geo_history_periods(p_ids bigint[]) RETURNS TABLE (
  route_id bigint, valid_from date, valid_to date,
  route_kind text, visit_kind text, zone_id bigint, territory_id bigint, division_id bigint, wing_id bigint, status text,
  house_id bigint, route_class text,
  route_code text, route_name text, zone_name text, territory_name text, division_name text, wing_name text, house_name text)
LANGUAGE sql STABLE
AS $$
  SELECT h.route_id, h.valid_from, h.valid_to,
         h.route_kind, h.visit_kind, h.zone_id, h.territory_id, h.division_id, h.wing_id, h.status,
         -- the zone's house in the period as the history recorded it: a house merge (V0093, admin HouseDedup) moves the
         -- zones to the surviving house, which zone_history dates, and a house's status change moves nothing (V0093's
         -- DashboardSummaries key, the lowest active house of the same name, is the same house once zones are merged)
         h.house_id,
         h.route_class,
         r.code, r.name, z.name, t.name, d.name, w.name, hs.name
    FROM dw.v_dim_geo_history h
    JOIN dw.dim_geo g ON g.route_id = h.route_id
    JOIN app.route r ON r.id = h.route_id
    LEFT JOIN app.zone z ON z.id = h.zone_id
    LEFT JOIN app.territory t ON t.id = h.territory_id
    LEFT JOIN app.division d ON d.id = h.division_id
    LEFT JOIN app.wing w ON w.id = h.wing_id
    LEFT JOIN app.house hs ON hs.id = h.house_id
   WHERE p_ids IS NULL OR h.route_id = ANY (p_ids)
$$;

CREATE FUNCTION dw.dim_outlet_history_periods(p_ids bigint[]) RETURNS TABLE (
  outlet_id bigint, valid_from date, valid_to date,
  route_id bigint, cluster_id bigint, zone_id bigint, channel text, sub_channel_id bigint, geo_class text, outlet_kind text, status text,
  outlet_code text, outlet_name text, location_confirmed boolean)
LANGUAGE sql STABLE
AS $$
  SELECT c.outlet_id, lower(p.r), upper(p.r),
         pl.route_id, pl.cluster_id, c.zone_id, c.channel, c.sub_channel_id, c.geo_class, c.outlet_kind, c.status,
         o.code, o.name, o.location_confirmed
    FROM dw.v_dim_outlet_history c
    JOIN dw.dim_outlet g ON g.outlet_id = c.outlet_id
    JOIN app.outlet o ON o.id = c.outlet_id
    JOIN app.outlet_placement_history pl ON pl.outlet_id = c.outlet_id
     AND daterange(pl.valid_from, pl.valid_to, '[)') && daterange(c.valid_from, c.valid_to, '[)')
   CROSS JOIN LATERAL (SELECT daterange(pl.valid_from, pl.valid_to, '[)') * daterange(c.valid_from, c.valid_to, '[)') AS r) p
   WHERE p_ids IS NULL OR c.outlet_id = ANY (p_ids)
$$;

CREATE FUNCTION dw.dim_product_history_periods(p_ids bigint[]) RETURNS TABLE (
  sku_id bigint, valid_from date, valid_to date,
  base_unit text, base_per_pack integer, variant_id bigint, brand_id bigint, segment_id bigint, category_id bigint, category_code text,
  sku_code text, short_name text, variant_name text, brand_name text, segment_name text, category_name text,
  segment_code text, brand_code text, variant_code text)
LANGUAGE sql STABLE
AS $$
  SELECT h.sku_id, h.valid_from, h.valid_to,
         h.base_unit, h.base_per_pack, h.variant_id, h.brand_id, h.segment_id, h.category_id, coalesce(h.category_code, c.code, ''),
         s.code, s.short_name, v.name, b.name, sg.name, c.name,
         coalesce(sg.code, 'n' || h.segment_id), coalesce(b.code, 'n' || h.brand_id), coalesce(v.code, 'n' || h.variant_id)
    FROM dw.v_dim_product_history h
    JOIN dw.dim_product g ON g.sku_id = h.sku_id
    JOIN app.sku s ON s.id = h.sku_id
    LEFT JOIN app.product_node v ON v.id = h.variant_id
    LEFT JOIN app.product_node b ON b.id = h.brand_id
    LEFT JOIN app.product_node sg ON sg.id = h.segment_id
    LEFT JOIN app.product_node c ON c.id = h.category_id
   WHERE p_ids IS NULL OR h.sku_id = ANY (p_ids)
$$;

CREATE FUNCTION dw.dim_user_history_periods(p_ids bigint[]) RETURNS TABLE (
  user_id bigint, valid_from date, valid_to date,
  role text, designation text, home_zone_id bigint, status text,
  pilot boolean)
LANGUAGE sql STABLE
AS $$
  SELECT h.user_id, h.valid_from, h.valid_to, h.role, h.designation, h.home_zone_id, h.status, u.pilot
    FROM dw.v_dim_user_history h
    JOIN dw.dim_user g ON g.user_id = h.user_id
    JOIN app.app_user u ON u.id = h.user_id
   WHERE p_ids IS NULL OR h.user_id = ANY (p_ids)
$$;

COMMENT ON FUNCTION dw.dim_geo_history_periods(bigint[]) IS 'History periods of routes (dw.v_dim_geo_history) as dw.dim_geo_version columns, type-1 names looked up by id; unmerged (V0123).';
COMMENT ON FUNCTION dw.dim_outlet_history_periods(bigint[]) IS 'History periods of outlets (dw.v_dim_outlet_history crossed with app.outlet_placement_history) as dw.dim_outlet_version columns; unmerged (V0123).';
COMMENT ON FUNCTION dw.dim_product_history_periods(bigint[]) IS 'History periods of SKUs (dw.v_dim_product_history) as dw.dim_product_version columns, type-1 names and codes looked up by id; unmerged (V0123).';
COMMENT ON FUNCTION dw.dim_user_history_periods(bigint[]) IS 'History periods of users (dw.v_dim_user_history) as dw.dim_user_version columns; unmerged (V0123).';

-- ---------- 2. the rekey ----------
CREATE FUNCTION dw.dim_version_cols(p_dim text, OUT natural_key text, OUT versioned text[], OUT type1 text[],
                                    OUT fact_key text, OUT key_fn text)
LANGUAGE sql IMMUTABLE
AS $$
  SELECT x.k, x.v, x.t, x.fk, x.fn FROM (VALUES
    ('dim_geo', 'route_id',
     ARRAY['route_kind', 'visit_kind', 'zone_id', 'territory_id', 'division_id', 'wing_id', 'status', 'house_id', 'route_class'],
     ARRAY['route_code', 'route_name', 'zone_name', 'territory_name', 'division_name', 'wing_name', 'house_name'],
     'geo_key', 'geo_key_on'),
    ('dim_outlet', 'outlet_id',
     ARRAY['route_id', 'cluster_id', 'zone_id', 'channel', 'sub_channel_id', 'geo_class', 'outlet_kind', 'status'],
     ARRAY['outlet_code', 'outlet_name', 'location_confirmed'],
     'outlet_key', 'outlet_key_on'),
    ('dim_product', 'sku_id',
     ARRAY['base_unit', 'base_per_pack', 'variant_id', 'brand_id', 'segment_id', 'category_id', 'category_code'],
     ARRAY['sku_code', 'short_name', 'variant_name', 'brand_name', 'segment_name', 'category_name', 'segment_code', 'brand_code', 'variant_code'],
     NULL, NULL),                                                  -- no fact stores a product_key
    ('dim_user', 'user_id',
     ARRAY['role', 'designation', 'home_zone_id', 'status'],
     ARRAY['pilot'],
     'user_key', 'user_key_on')) AS x(d, k, v, t, fk, fn)
   WHERE x.d = p_dim
$$;
COMMENT ON FUNCTION dw.dim_version_cols(text) IS 'Natural key, versioned (as-was) columns, type-1 columns, fact key column and key lookup of a dw dimension''s version table (V0123).';

-- Facts of some ids dated on or after a date take the key valid on their business date (fact_memo, fact_visit).
CREATE FUNCTION dw.restamp_fact_keys(p_dim text, p_ids bigint[], p_from date) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  c   record;
  t   text;
  n   bigint;
  tot bigint := 0;
BEGIN
  SELECT * INTO c FROM dw.dim_version_cols(p_dim);
  IF c.fact_key IS NULL OR cardinality(p_ids) IS NULL OR cardinality(p_ids) = 0 THEN
    RETURN 0;
  END IF;
  FOREACH t IN ARRAY ARRAY['fact_memo', 'fact_visit'] LOOP
    EXECUTE format('UPDATE dw.%1$I f SET %2$I = dw.%3$I(f.%4$I, f.business_date), updated_at = now()
                     WHERE f.%4$I = ANY ($1) AND f.business_date >= $2 AND f.%2$I IS DISTINCT FROM dw.%3$I(f.%4$I, f.business_date)',
                   t, c.fact_key, c.key_fn, c.natural_key) USING p_ids, coalesce(p_from, '-infinity'::date);
    GET DIAGNOSTICS n = ROW_COUNT;
    tot := tot + n;
  END LOOP;
  RETURN tot;
END $$;
COMMENT ON FUNCTION dw.restamp_fact_keys(text, bigint[], date) IS 'Re-stamps geo_key, outlet_key or user_key of dw.fact_memo and dw.fact_visit for some ids from a business date on (null = all dates), where the key valid on the fact''s date differs (V0123).';

CREATE FUNCTION dw.rekey_dim_versions(p_dim text DEFAULT NULL, p_ids bigint[] DEFAULT NULL)
RETURNS TABLE (dim text, ids bigint, kept bigint, inserted bigint, deleted bigint, restamped bigint, handled bigint[])
LANGUAGE plpgsql
AS $$
DECLARE
  d       text;
  c       record;
  vlist   text;     -- versioned columns
  x       text;     -- the merged history periods of the ids it handles, as a query
  m_match text;     -- a version row v matches a period x
  ver     text;
  sk      text;     -- surrogate key column
  keys_   bigint[];
  tos_    date[];
  rows_   jsonb;
  ch_ids  bigint[];
  ch_from date;
  a_ids   bigint[];
  a_from  date;
  n       bigint;
BEGIN
  IF p_dim IS NOT NULL AND p_dim NOT IN ('dim_geo', 'dim_outlet', 'dim_product', 'dim_user') THEN
    RAISE EXCEPTION 'rekey_dim_versions: unknown dimension %', p_dim;
  END IF;
  -- The order of Aggregator.refreshDimensions (geo, product, outlet, user): the advisory locks below are taken in it.
  FOREACH d IN ARRAY ARRAY['dim_geo', 'dim_product', 'dim_outlet', 'dim_user'] LOOP
    CONTINUE WHEN p_dim IS NOT NULL AND d <> p_dim;
    SELECT * INTO c FROM dw.dim_version_cols(d);
    vlist := (SELECT string_agg(quote_ident(v), ', ') FROM unnest(c.versioned) v);
    -- Neighbours with equal versioned columns (adjacent periods) are one version, which takes the type-1 values of its
    -- latest period; the first version of an id is valid since ever (as V0043: a fact dated before the history's first
    -- period, such as outlet_placement_history's 2026-01-01 start, still finds a key).
    x := format($f$
      SELECT %1$I, %2$s, %3$s,
             CASE WHEN grp = 1 THEN NULL ELSE (array_agg(valid_from ORDER BY valid_from NULLS FIRST))[1] END AS valid_from,
             (array_agg(valid_to ORDER BY valid_from DESC NULLS LAST))[1] AS valid_to
        FROM (SELECT b.*, sum(brk) OVER (PARTITION BY %1$I ORDER BY valid_from NULLS FIRST) AS grp
                FROM (SELECT p.*, CASE WHEN row_number() OVER w = 1
                                         OR lag(valid_to) OVER w IS DISTINCT FROM valid_from
                                         OR lag(jsonb_build_array(%2$s)) OVER w IS DISTINCT FROM jsonb_build_array(%2$s)
                                       THEN 1 ELSE 0 END AS brk
                        FROM dw.%4$I(%5$s) p
                      WINDOW w AS (PARTITION BY %1$I ORDER BY valid_from NULLS FIRST)) b) g
       GROUP BY %1$I, grp, %2$s$f$,
      c.natural_key, vlist,
      (SELECT string_agg(format('(array_agg(%1$I ORDER BY valid_from DESC NULLS LAST))[1] AS %1$I', t), ', ') FROM unnest(c.type1) t),
      d || '_history_periods', coalesce(quote_literal(p_ids::text) || '::bigint[]', 'NULL::bigint[]'));
    -- The history is used only where its current version agrees with the dimension row (a write the history did not
    -- see, such as a direct SQL move of an outlet's route, leaves the id to the projector date until the history
    -- catches up). house_id does not count: DashboardSummaries sets it on the row later, by its own key.
    x := format($f$
      WITH m AS (%1$s)
      SELECT m.* FROM m
       WHERE m.%2$I IN (SELECT c.%2$I FROM m c JOIN dw.%3$I g ON g.%2$I = c.%2$I WHERE c.valid_to IS NULL AND NOT (%4$s))$f$,
      x, c.natural_key, d,
      (SELECT string_agg(format('(g.%1$I IS DISTINCT FROM c.%1$I)', v), ' OR ') FROM unnest(c.versioned) v WHERE v <> 'house_id'));
    m_match := format('x.%1$I = v.%1$I AND x.valid_from IS NOT DISTINCT FROM v.valid_from AND (%2$s) IS NOT DISTINCT FROM (%3$s)', c.natural_key,
                      (SELECT string_agg('x.' || quote_ident(v), ', ') FROM unnest(c.versioned) v),
                      (SELECT string_agg('v.' || quote_ident(v), ', ') FROM unnest(c.versioned) v));
    -- One rekey of a dimension at a time (a nightly full run, the projector's trigger, V0043's step): two would delete
    -- each other's versions or both insert one (23P01).
    PERFORM pg_advisory_xact_lock(hashtext('dw.rekey_dim_versions'), hashtext(d));
    -- The merged periods are computed once and analyzed, so every statement below joins them by hash or index (a CTE
    -- estimated at one row made the full run's anti-join quadratic: 94 s for 30k outlets).
    -- (A temp table: the calling transaction cannot use PREPARE TRANSACTION; nothing in Aron does.)
    IF to_regclass('pg_temp.dw_v0123_rekey_periods') IS NOT NULL THEN
      DROP TABLE pg_temp.dw_v0123_rekey_periods;
    END IF;
    EXECUTE 'CREATE TEMP TABLE dw_v0123_rekey_periods ON COMMIT DROP AS ' || x;
    EXECUTE format('CREATE INDEX ON pg_temp.dw_v0123_rekey_periods (%I, valid_from)', c.natural_key);
    ANALYZE pg_temp.dw_v0123_rekey_periods;
    x := 'SELECT * FROM pg_temp.dw_v0123_rekey_periods';
    dim := d;
    ver := d || '_version';
    sk := replace(d, 'dim_', '') || '_key';
    EXECUTE format('WITH x AS (%s) SELECT array_agg(DISTINCT %I) FROM x', x, c.natural_key) INTO handled USING p_ids;
    ids := coalesce(cardinality(handled), 0);
    a_ids := '{}'; a_from := NULL;
    -- Every change is computed by a read statement and then applied by surrogate key (primary-key btree), so no
    -- statement searches the version table's GiST exclusion index for the rows it then writes (round 1 of V0123 did, in
    -- one INSERT ... WHERE NOT EXISTS, and on PostgreSQL 16.15 after rolled-back writes the index was then missing
    -- entries until a REINDEX; D-DB-DIM-REKEY-01). The exclusion check itself still probes that index on every write.
    -- Every changed version adds its id and start to the facts to re-stamp (a superset of the dates whose key moved).
    EXECUTE format('WITH x AS (%1$s) SELECT array_agg(v.%2$I), array_agg(DISTINCT v.%3$I), min(coalesce(v.valid_from, ''-infinity''::date))
                      FROM dw.%4$I v WHERE v.%3$I IN (SELECT %3$I FROM x) AND NOT EXISTS (SELECT 1 FROM x WHERE %5$s)',
                   x, sk, c.natural_key, ver, m_match) INTO keys_, ch_ids, ch_from USING p_ids;
    EXECUTE format('DELETE FROM dw.%I WHERE %I = ANY ($1)', ver, sk) USING coalesce(keys_, '{}');
    deleted := coalesce(cardinality(keys_), 0);
    a_ids := a_ids || coalesce(ch_ids, '{}'); a_from := least(a_from, ch_from);
    EXECUTE format('WITH x AS (%1$s) SELECT array_agg(v.%2$I ORDER BY v.%2$I), array_agg(x.valid_to ORDER BY v.%2$I), array_agg(DISTINCT v.%3$I),
                           min(coalesce(v.valid_from, ''-infinity''::date))
                      FROM dw.%4$I v JOIN x ON %5$s WHERE v.valid_to IS DISTINCT FROM x.valid_to',
                   x, sk, c.natural_key, ver, m_match) INTO keys_, tos_, ch_ids, ch_from USING p_ids;
    EXECUTE format('UPDATE dw.%1$I v SET valid_to = u.t, updated_at = now() FROM unnest($1::bigint[], $2::date[]) u(k, t) WHERE v.%2$I = u.k', ver, sk)
      USING coalesce(keys_, '{}'), coalesce(tos_, '{}');
    a_ids := a_ids || coalesce(ch_ids, '{}'); a_from := least(a_from, ch_from);
    EXECUTE format('WITH x AS (%1$s) SELECT jsonb_agg(jsonb_build_object(%2$L, v.%2$I) || app.jsonb_pick(to_jsonb(x), $2))
                      FROM dw.%3$I v JOIN x ON %4$s WHERE (%5$s) IS DISTINCT FROM (%6$s)',
                   x, sk, ver, m_match,
                   (SELECT string_agg('v.' || quote_ident(t), ', ') FROM unnest(c.type1) t),
                   (SELECT string_agg('x.' || quote_ident(t), ', ') FROM unnest(c.type1) t)) INTO rows_ USING p_ids, c.type1;
    EXECUTE format('UPDATE dw.%1$I v SET (%2$s) = ROW(%3$s), updated_at = now() FROM jsonb_populate_recordset(NULL::dw.%1$I, $1) r WHERE v.%4$I = r.%4$I',
                   ver,
                   (SELECT string_agg(quote_ident(t), ', ') FROM unnest(c.type1) t),
                   (SELECT string_agg('r.' || quote_ident(t), ', ') FROM unnest(c.type1) t), sk) USING coalesce(rows_, '[]');
    EXECUTE format('WITH x AS (%1$s) SELECT jsonb_agg(to_jsonb(x)) FROM x WHERE NOT EXISTS (SELECT 1 FROM dw.%2$I v WHERE %3$s)', x, ver, m_match)
      INTO rows_ USING p_ids;
    EXECUTE format('WITH r AS (INSERT INTO dw.%1$I (%2$I, %3$s, valid_from, valid_to, updated_at)
                     SELECT %2$I, %3$s, valid_from, valid_to, now() FROM jsonb_populate_recordset(NULL::dw.%1$I, $1) RETURNING %2$I AS id, valid_from)
                    SELECT count(*), array_agg(DISTINCT id), min(coalesce(valid_from, ''-infinity''::date)) FROM r',
                   ver, c.natural_key, (SELECT string_agg(quote_ident(v), ', ') FROM unnest(c.versioned || c.type1) v))
      INTO inserted, ch_ids, ch_from USING coalesce(rows_, '[]');
    a_ids := a_ids || coalesce(ch_ids, '{}'); a_from := least(a_from, ch_from);
    EXECUTE format('WITH x AS (%1$s) SELECT count(*) FROM dw.%2$I v WHERE EXISTS (SELECT 1 FROM x WHERE %3$s)', x, d || '_version', m_match)
      INTO kept USING p_ids;
    kept := kept - inserted;
    restamped := dw.restamp_fact_keys(d, ARRAY(SELECT DISTINCT unnest(a_ids)), a_from);
    DROP TABLE pg_temp.dw_v0123_rekey_periods;
    RETURN NEXT;
  END LOOP;
END $$;
COMMENT ON FUNCTION dw.rekey_dim_versions(text, bigint[]) IS 'Rebuilds dw.<dim>_version from the app history (V0123) for one dimension (null = all four) and some natural ids (null = all): a version whose id, valid_from and versioned columns are unchanged keeps its key; ids without history, or whose history disagrees with the dimension row, are left alone; facts of changed ids are re-stamped. Idempotent. Returns per dimension the ids it handled (count and list), versions kept, inserted and deleted, and facts re-stamped.';

-- ---------- 3. the dimension triggers follow the history ----------
-- V0043's version step for one id that the history does not date: close the current version on the Asia/Dhaka date of
-- the projector write and open a new one (a second change on that date rewrites it; the first version is since ever).
CREATE FUNCTION dw.scd2_open_version(p_dim text, p_key text, p_new jsonb) RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  v     text := p_dim || '_version';
  d     date := app.dhaka_date(now());
  cols  text;
  id_   bigint := (p_new ->> p_key)::bigint;
  sk    text := replace(p_dim, 'dim_', '') || '_key';
  cur_key  bigint;
  has_cur  boolean;
  cur_from date;
  had_any  boolean;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('dw.rekey_dim_versions'), hashtext(p_dim));
  -- Columns both tables share (as V0116).
  SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) INTO cols
    FROM pg_attribute a
   WHERE a.attrelid = ('dw.' || p_dim)::regclass AND a.attnum > 0 AND NOT a.attisdropped
     AND EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = ('dw.' || v)::regclass AND b.attname = a.attname
                   AND b.attnum > 0 AND NOT b.attisdropped AND b.attgenerated = '');
  -- Read first, then write by surrogate key (as dw.rekey_dim_versions: no statement scans the table it writes).
  EXECUTE format('SELECT %I, valid_from FROM dw.%I WHERE %I = $1 AND valid_to IS NULL', sk, v, p_key) INTO cur_key, cur_from USING id_;
  has_cur := cur_key IS NOT NULL;
  EXECUTE format('SELECT EXISTS (SELECT 1 FROM dw.%I WHERE %I = $1)', v, p_key) INTO had_any USING id_;
  IF has_cur AND cur_from >= d THEN
    EXECUTE format('UPDATE dw.%1$I SET (%2$s) = (SELECT %2$s FROM jsonb_populate_record(NULL::dw.%3$I, $1)) WHERE %4$I = $2',
                   v, cols, p_dim, sk) USING p_new, cur_key;
  ELSE
    IF has_cur THEN
      EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2', v, sk) USING d, cur_key;
    END IF;
    EXECUTE format('INSERT INTO dw.%1$I (%2$s, valid_from) SELECT %2$s, $2 FROM jsonb_populate_record(NULL::dw.%3$I, $1)',
                   v, cols, p_dim) USING p_new, CASE WHEN had_any THEN d END;
  END IF;
END $$;
COMMENT ON FUNCTION dw.scd2_open_version(text, text, jsonb) IS 'V0043 version step for one dimension row the history does not date: closes the current version on the Asia/Dhaka date of the write and opens one from the row (V0123).';

-- Row level, kept for DELETE (unchanged) and as a complete V0043 trigger for INSERT/UPDATE.
CREATE OR REPLACE FUNCTION dw.scd2_track() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  k     text := TG_ARGV[0];
  v     text := TG_TABLE_NAME || '_version';
  sk    text := replace(TG_TABLE_NAME, 'dim_', '') || '_key';
  d     date := app.dhaka_date(now());
  cur_key  bigint;
  cur_from date;
BEGIN
  IF TG_OP = 'DELETE' THEN
    -- As V0043 (close the current version today; one that began today goes), read first and written by key; the
    -- facts of the id from today on take the key that is left (none when the row is gone).
    PERFORM pg_advisory_xact_lock(hashtext('dw.rekey_dim_versions'), hashtext(TG_TABLE_NAME));
    EXECUTE format('SELECT %I, valid_from FROM dw.%I WHERE %I = $1 AND valid_to IS NULL', sk, v, k) INTO cur_key, cur_from
      USING (to_jsonb(OLD) ->> k)::bigint;
    IF cur_key IS NOT NULL AND cur_from IS NOT DISTINCT FROM d THEN
      EXECUTE format('DELETE FROM dw.%I WHERE %I = $1', v, sk) USING cur_key;
    ELSIF cur_key IS NOT NULL AND (cur_from IS NULL OR cur_from < d) THEN
      EXECUTE format('UPDATE dw.%I SET valid_to = $1 WHERE %I = $2', v, sk) USING d, cur_key;
    END IF;
    PERFORM dw.restamp_fact_keys(TG_TABLE_NAME, ARRAY[(to_jsonb(OLD) ->> k)::bigint], d);
    RETURN NULL;
  END IF;
  PERFORM dw.scd2_open_version(TG_TABLE_NAME, k, to_jsonb(NEW));
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.scd2_track() IS 'AFTER DELETE on a type-1 dimension: closes the current version on the Asia/Dhaka date (V0043); INSERT/UPDATE go to dw.dim_versions_follow since V0123.';

-- Statement level (transition tables dim_new, dim_old): the ids whose versioned or type-1 columns moved are rekeyed from
-- the history in one call; the ids it leaves alone take V0043's step one by one and their facts from today are
-- re-stamped. An UPDATE that moved only updated_at (the projector's refresh every few minutes) does nothing.
CREATE FUNCTION dw.dim_versions_follow() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  k        text := TG_ARGV[0];
  kept     text[];
  ids      bigint[];
  handled  bigint[];
  r        record;
  rest     bigint[] := '{}';
BEGIN
  SELECT array_agg(a.attname::text) INTO kept
    FROM pg_attribute a
   WHERE a.attrelid = TG_RELID AND a.attnum > 0 AND NOT a.attisdropped AND a.attname <> 'updated_at'
     AND EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = ('dw.' || TG_TABLE_NAME || '_version')::regclass AND b.attname = a.attname
                   AND b.attnum > 0 AND NOT b.attisdropped AND b.attgenerated = '');
  IF TG_OP = 'INSERT' THEN
    EXECUTE format('SELECT array_agg(n.%I) FROM dim_new n', k) INTO ids;
  ELSE
    EXECUTE format('SELECT array_agg(n.%1$I) FROM dim_new n JOIN dim_old o ON o.%1$I = n.%1$I
                     WHERE app.jsonb_pick(to_jsonb(n), $1) IS DISTINCT FROM app.jsonb_pick(to_jsonb(o), $1)', k) INTO ids USING kept;
  END IF;
  IF ids IS NULL THEN
    RETURN NULL;
  END IF;
  SELECT x.handled INTO handled FROM dw.rekey_dim_versions(TG_TABLE_NAME, ids) x;
  FOR r IN EXECUTE format('SELECT to_jsonb(n) AS j, n.%1$I AS id FROM dim_new n WHERE n.%1$I = ANY ($1) AND n.%1$I <> ALL ($2) ORDER BY n.%1$I', k)
             USING ids, coalesce(handled, '{}') LOOP
    PERFORM dw.scd2_open_version(TG_TABLE_NAME, k, r.j);
    rest := rest || r.id;
  END LOOP;
  PERFORM dw.restamp_fact_keys(TG_TABLE_NAME, rest, app.dhaka_date(now()));
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.dim_versions_follow() IS 'AFTER INSERT/UPDATE statement on a type-1 dimension: rekeys the changed ids from the app history (dw.rekey_dim_versions), else V0043''s step (dw.scd2_open_version), and re-stamps their facts (V0123).';

DO $$
DECLARE d text; k text;
BEGIN
  FOR d, k IN VALUES ('dim_geo', 'route_id'), ('dim_product', 'sku_id'), ('dim_outlet', 'outlet_id'), ('dim_user', 'user_id') LOOP
    EXECUTE format('DROP TRIGGER %I ON dw.%I', d || '_scd2', d);
    EXECUTE format('CREATE TRIGGER %I AFTER DELETE ON dw.%I FOR EACH ROW EXECUTE FUNCTION dw.scd2_track(%L)', d || '_scd2', d, k);
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT ON dw.%I REFERENCING NEW TABLE AS dim_new FOR EACH STATEMENT EXECUTE FUNCTION dw.dim_versions_follow(%L)',
                   d || '_versions_ins', d, k);
    EXECUTE format('CREATE TRIGGER %I AFTER UPDATE ON dw.%I REFERENCING OLD TABLE AS dim_old NEW TABLE AS dim_new FOR EACH STATEMENT EXECUTE FUNCTION dw.dim_versions_follow(%L)',
                   d || '_versions_upd', d, k);
  END LOOP;
END $$;

-- ---------- 4. one rekey (it re-stamps the facts of the ids it changed), then every fact key that still differs ----------
SELECT count(*) FROM dw.rekey_dim_versions();
SELECT dw.restamp_fact_keys(d, ARRAY(SELECT DISTINCT f.id FROM (SELECT route_id FROM dw.fact_memo UNION SELECT route_id FROM dw.fact_visit) f(id)), NULL)
  FROM (VALUES ('dim_geo')) v(d);
SELECT dw.restamp_fact_keys(d, ARRAY(SELECT DISTINCT f.id FROM (SELECT outlet_id FROM dw.fact_memo UNION SELECT outlet_id FROM dw.fact_visit) f(id)), NULL)
  FROM (VALUES ('dim_outlet')) v(d);
SELECT dw.restamp_fact_keys(d, ARRAY(SELECT DISTINCT f.id FROM (SELECT user_id FROM dw.fact_memo UNION SELECT user_id FROM dw.fact_visit) f(id)), NULL)
  FROM (VALUES ('dim_user')) v(d);

-- ---------- data dictionary ----------
DO $$
DECLARE d text;
BEGIN
  FOREACH d IN ARRAY ARRAY['dim_geo', 'dim_product', 'dim_outlet', 'dim_user'] LOOP
    EXECUTE format('COMMENT ON TABLE dw.%I IS %L', d || '_version',
      format('One row is a version of %s valid over [valid_from, valid_to) (SCD2): dated by the app history (dw.rekey_dim_versions, V0123), else by the projector write on dw.%s.', replace(d, 'dim_', ''), d)
      || E'\n' || 'owner: db | capture: SERVER | retention: master | pii: none');
  END LOOP;
END $$;

SELECT app.apply_db_role_grants();
