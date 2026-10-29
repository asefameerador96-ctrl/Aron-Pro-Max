-- V0124 database-side geography version (backend-reports request docs/requests/db-geo-version.md; D-DB-GEO-VERSION-01).
-- app.geo_version is one row whose version goes up by one in the same transaction as any statement that inserts, updates
-- (a real change) or deletes rows of app.wing, division, territory, house, zone or route (zone.house_id included), or
-- truncates one. Being a row (not a sequence) it is transactional: a reader sees the new number exactly when it can see
-- the edit, so a GeoRepository that reloads on a newer number never caches the old geography under it. One bump per
-- statement, not per row. Readers: api_rw and worker_rw (SELECT on app.*), read beside scope_version in
-- SqlReachResolver.reach. Writes: only the SECURITY DEFINER bump trigger and the owner (guard as V0119's history tables,
-- because app.apply_db_role_grants() gives api_rw INSERT and UPDATE on every app table). Geography edits are admin
-- writes: the single row serialises them for the length of their transaction, which is what a version needs.

SET lock_timeout = '5s';

CREATE TABLE app.geo_version (
  id         boolean PRIMARY KEY DEFAULT true CHECK (id),
  version    bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  changed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app.geo_version DEFAULT VALUES;

CREATE FUNCTION app.guard_geo_version() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT app.may_restamp_origin(TG_RELID) THEN
    RAISE EXCEPTION 'app.geo_version is written only by the geography bump trigger or the table owner'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
COMMENT ON FUNCTION app.guard_geo_version() IS 'BEFORE INSERT/UPDATE/DELETE on app.geo_version: only the table owner (the SECURITY DEFINER bump trigger, migrations) writes it.';
CREATE TRIGGER geo_version_guard BEFORE INSERT OR UPDATE OR DELETE ON app.geo_version
  FOR EACH ROW EXECUTE FUNCTION app.guard_geo_version();

-- Statement-level, with transition tables geo_old / geo_new: a statement that touched no row, or an UPDATE that left
-- every row as it was, does not bump.
CREATE FUNCTION app.bump_geo_version() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app, pg_temp
AS $$
DECLARE
  changed boolean;
BEGIN
  -- One branch per event: each names only the transition tables its trigger has.
  IF TG_OP = 'TRUNCATE' THEN
    changed := true;
  ELSIF TG_OP = 'INSERT' THEN
    changed := EXISTS (SELECT 1 FROM geo_new);
  ELSIF TG_OP = 'DELETE' THEN
    changed := EXISTS (SELECT 1 FROM geo_old);
  ELSE
    changed := EXISTS (SELECT to_jsonb(n) FROM geo_new n EXCEPT SELECT to_jsonb(o) FROM geo_old o);
  END IF;
  IF changed THEN
    UPDATE app.geo_version SET version = version + 1, changed_at = now();
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION app.bump_geo_version() IS 'AFTER statement on a geography table (wing, division, territory, house, zone, route): adds one to app.geo_version when the statement changed a row.';

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['wing', 'division', 'territory', 'house', 'zone', 'route'] LOOP
    EXECUTE format('CREATE TRIGGER %I AFTER INSERT ON app.%I REFERENCING NEW TABLE AS geo_new FOR EACH STATEMENT EXECUTE FUNCTION app.bump_geo_version()', t || '_geo_version_ins', t);
    EXECUTE format('CREATE TRIGGER %I AFTER UPDATE ON app.%I REFERENCING OLD TABLE AS geo_old NEW TABLE AS geo_new FOR EACH STATEMENT EXECUTE FUNCTION app.bump_geo_version()', t || '_geo_version_upd', t);
    EXECUTE format('CREATE TRIGGER %I AFTER DELETE ON app.%I REFERENCING OLD TABLE AS geo_old FOR EACH STATEMENT EXECUTE FUNCTION app.bump_geo_version()', t || '_geo_version_del', t);
    EXECUTE format('CREATE TRIGGER %I AFTER TRUNCATE ON app.%I FOR EACH STATEMENT EXECUTE FUNCTION app.bump_geo_version()', t || '_geo_version_trunc', t);
  END LOOP;
END $$;

-- ---------- data dictionary ----------
COMMENT ON TABLE app.geo_version IS 'One row: the geography version, one higher after every statement that changed app.wing, division, territory, house, zone or route (V0124); caches of the geography reload when it is newer than theirs.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN app.geo_version.id IS 'Always true (the table has one row).';
COMMENT ON COLUMN app.geo_version.version IS 'Geography version; goes up by one per changing statement, in the same transaction.';
COMMENT ON COLUMN app.geo_version.changed_at IS 'UTC instant of the last bump.';

SELECT app.apply_db_role_grants();
