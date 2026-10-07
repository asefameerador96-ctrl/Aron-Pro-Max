-- V0043 AUD-DA-02 (docs/16 P3, P6, s8.1 "a re-parented outlet never rewrites history"), expand-only and scoped:
--   1. Capture rows freeze their structural context at ingest: visit and memo get zone_id, cluster_id, outlet_channel,
--      outlet_geo_class; due_collection gets zone_id and cluster_id; stock_movement (route-level, no outlet) gets
--      zone_id. A BEFORE INSERT trigger stamps every column the writer left NULL, so every writer (sync ingest, web
--      entry, imports) is covered without code changes; a writer may also set them itself. They are write-once.
--      zone_id is the route's zone as of the row's business_date (route_zone_history), else the outlet's zone;
--      cluster, channel and geo class are the outlet's at ingest (no outlet class history exists; docs/16 s4 SCD).
--      No foreign keys: they are frozen copies, and PG16 cannot add a NOT VALID foreign key to the partitioned
--      visit and memo (the copy may name a zone that is later retired, which is the point).
--      A writer's own values win, so the sync path must never copy these columns from a device payload (today the
--      contract has no such members and ingest rejects unknown ones; keep it that way).
--   2. app.route_zone_history records every zone a route has belonged to, maintained by a trigger on app.route
--      (exclusion constraint as route_planned). The first row of a route starts at -infinity.
--   3. Not done (decision, docs/status/db.md): dw.dim_geo, dim_outlet and dim_product stay type 1. backend-reports
--      reads them by natural key in about 40 queries, so an SCD2 redefinition is a cutover risk; facts instead take
--      zone and class from the frozen capture row (docs/requests/db-capture-context-projection.md).
-- Existing pilot rows are backfilled (write-once allows NULL -> value). Cost on ingest: two primary-key lookups per
-- visit or memo row.

SET lock_timeout = '5s';

-- ---------- 2. route zone history ----------
CREATE TABLE app.route_zone_history (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id    bigint NOT NULL REFERENCES app.route(id),
  zone_id     bigint NOT NULL REFERENCES app.zone(id),
  valid_from  date NOT NULL,                          -- Asia/Dhaka business date; -infinity on a route's first row
  valid_to    date,                                   -- exclusive; null = current
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX route_zone_history_asof ON app.route_zone_history (route_id, valid_from);
CREATE UNIQUE INDEX route_zone_history_open ON app.route_zone_history (route_id) WHERE valid_to IS NULL;

-- A zone move takes effect from the next Dhaka business date (like route_planned changes), so the day of the move stays
-- wholly in the old zone whatever the upload order: the open row closes at tomorrow and a new one opens there. A second
-- move before then replaces the pending row. app.route.zone_id itself changes at once. Runs as the table owner so
-- writers need no rights on the history table.
CREATE FUNCTION app.track_route_zone() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  d         date := app.dhaka_date(now()) + 1;           -- effective from the next business date
  cur       app.route_zone_history%ROWTYPE;
  has_cur   boolean;
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO app.route_zone_history (route_id, zone_id, valid_from) VALUES (NEW.id, NEW.zone_id, '-infinity');
    RETURN NULL;
  END IF;
  IF NEW.zone_id IS NOT DISTINCT FROM OLD.zone_id THEN
    RETURN NULL;
  END IF;
  SELECT * INTO cur FROM app.route_zone_history WHERE route_id = NEW.id AND valid_to IS NULL FOR UPDATE;
  has_cur := FOUND;
  IF has_cur AND cur.valid_from >= d THEN
    UPDATE app.route_zone_history SET zone_id = NEW.zone_id WHERE id = cur.id;
  ELSE
    IF has_cur THEN
      UPDATE app.route_zone_history SET valid_to = d WHERE id = cur.id;
    END IF;
    INSERT INTO app.route_zone_history (route_id, zone_id, valid_from)
    VALUES (NEW.id, NEW.zone_id, CASE WHEN has_cur THEN d ELSE '-infinity'::date END);
  END IF;
  RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION app.track_route_zone() FROM PUBLIC;
CREATE TRIGGER route_zone_history AFTER INSERT OR UPDATE OF zone_id ON app.route
  FOR EACH ROW EXECUTE FUNCTION app.track_route_zone();

INSERT INTO app.route_zone_history (route_id, zone_id, valid_from)
SELECT id, zone_id, '-infinity' FROM app.route ORDER BY id;

-- ---------- 1. frozen capture context ----------
ALTER TABLE app.visit ADD COLUMN zone_id bigint;
ALTER TABLE app.visit ADD COLUMN cluster_id bigint;
ALTER TABLE app.visit ADD COLUMN outlet_channel text;
ALTER TABLE app.visit ADD COLUMN outlet_geo_class text;
ALTER TABLE app.memo ADD COLUMN zone_id bigint;
ALTER TABLE app.memo ADD COLUMN cluster_id bigint;
ALTER TABLE app.memo ADD COLUMN outlet_channel text;
ALTER TABLE app.memo ADD COLUMN outlet_geo_class text;
ALTER TABLE app.due_collection ADD COLUMN zone_id bigint;
ALTER TABLE app.due_collection ADD COLUMN cluster_id bigint;
ALTER TABLE app.stock_movement ADD COLUMN zone_id bigint;

-- TG_ARGV[0]: 'outlet_class' (zone, cluster, channel, geo class), 'outlet' (zone, cluster) or 'route' (zone only).
CREATE FUNCTION app.stamp_capture_context() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  rz  bigint;
  o   record;
BEGIN
  IF NEW.zone_id IS NULL AND NEW.route_id IS NOT NULL THEN
    SELECT h.zone_id INTO rz FROM app.route_zone_history h
     WHERE h.route_id = NEW.route_id AND h.valid_from <= NEW.business_date
       AND (h.valid_to IS NULL OR h.valid_to > NEW.business_date);
    IF rz IS NULL THEN
      SELECT r.zone_id INTO rz FROM app.route r WHERE r.id = NEW.route_id;
    END IF;
  END IF;
  IF TG_ARGV[0] IN ('outlet', 'outlet_class') THEN
    SELECT ot.zone_id, ot.cluster_id, ot.channel, ot.geo_class INTO o FROM app.outlet ot WHERE ot.id = NEW.outlet_id;
    NEW.zone_id := coalesce(NEW.zone_id, rz, o.zone_id);
    NEW.cluster_id := coalesce(NEW.cluster_id, o.cluster_id);
    IF TG_ARGV[0] = 'outlet_class' THEN
      NEW.outlet_channel := coalesce(NEW.outlet_channel, o.channel);
      NEW.outlet_geo_class := coalesce(NEW.outlet_geo_class, o.geo_class);
    END IF;
  ELSE
    NEW.zone_id := coalesce(NEW.zone_id, rz);
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER visit_stamp_context BEFORE INSERT ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet_class');
CREATE TRIGGER memo_stamp_context BEFORE INSERT ON app.memo
  FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet_class');
CREATE TRIGGER due_collection_stamp_context BEFORE INSERT ON app.due_collection
  FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet');
CREATE TRIGGER stock_movement_stamp_context BEFORE INSERT ON app.stock_movement
  FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('route');

-- Write-once: the guards gain the new columns with '=' (NULL -> value once, then frozen). Argument lists are V0007's.
CREATE OR REPLACE TRIGGER visit_immutable BEFORE UPDATE OR DELETE ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'server_verdict', 'server_distance_m', 'server_radius_m',
    'server_max_accuracy_m', 'server_checked_at', '=close_client_uuid', '=close_received_at', '=close_captured_at', '=outcome_code',
    '=call_started_at', '=call_declined', '=ended_at', '=is_zero_sale',
    '=zone_id', '=cluster_id', '=outlet_channel', '=outlet_geo_class');
CREATE OR REPLACE TRIGGER memo_immutable BEFORE UPDATE OR DELETE ON app.memo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', 'status_changed_at', '=voided_by_client_uuid',
    '=superseded_by_client_uuid', 'server_flags',
    '=zone_id', '=cluster_id', '=outlet_channel', '=outlet_geo_class');
CREATE OR REPLACE TRIGGER due_collection_immutable BEFORE UPDATE OR DELETE ON app.due_collection
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', '=zone_id', '=cluster_id');
CREATE OR REPLACE TRIGGER stock_movement_immutable BEFORE UPDATE OR DELETE ON app.stock_movement
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', '=zone_id');

-- Backfill the pilot rows (the route history has one -infinity row per route, so this is the current structure).
UPDATE app.visit v SET zone_id = coalesce((SELECT r.zone_id FROM app.route r WHERE r.id = v.route_id), o.zone_id),
       cluster_id = o.cluster_id, outlet_channel = o.channel, outlet_geo_class = o.geo_class
  FROM app.outlet o WHERE o.id = v.outlet_id AND v.zone_id IS NULL;
UPDATE app.memo m SET zone_id = coalesce((SELECT r.zone_id FROM app.route r WHERE r.id = m.route_id), o.zone_id),
       cluster_id = o.cluster_id, outlet_channel = o.channel, outlet_geo_class = o.geo_class
  FROM app.outlet o WHERE o.id = m.outlet_id AND m.zone_id IS NULL;
UPDATE app.due_collection c SET zone_id = coalesce((SELECT r.zone_id FROM app.route r WHERE r.id = c.route_id), o.zone_id),
       cluster_id = o.cluster_id
  FROM app.outlet o WHERE o.id = c.outlet_id AND c.zone_id IS NULL;
UPDATE app.stock_movement s SET zone_id = r.zone_id FROM app.route r WHERE r.id = s.route_id AND s.zone_id IS NULL;

-- Only the trigger writes the history: the API may read it (no INSERT or UPDATE through its V0014 '*' rows).
UPDATE app.db_role_grant SET except_tables = except_tables || '{route_zone_history}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object IN ('*', '*/update') AND NOT ('route_zone_history' = ANY (except_tables));
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'route_zone_history', 'SELECT', 'zone as of a date; written only by the app.route trigger');
SELECT app.apply_db_role_grants();

COMMENT ON TABLE app.route_zone_history IS 'One row is a period (Asia/Dhaka business dates, end exclusive) during which a route belonged to a zone; written by a trigger on app.route.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN app.route_zone_history.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.route_zone_history.route_id IS 'Route (app.route) whose zone is recorded.';
COMMENT ON COLUMN app.route_zone_history.zone_id IS 'Zone (app.zone) the route belonged to in the period.';
COMMENT ON COLUMN app.route_zone_history.valid_from IS 'First Asia/Dhaka business date of the period; -infinity for a route''s first zone.';
COMMENT ON COLUMN app.route_zone_history.valid_to IS 'First business date after the period (exclusive); null while current.';
COMMENT ON COLUMN app.route_zone_history.created_at IS 'UTC instant the row was inserted.';
COMMENT ON COLUMN app.visit.zone_id IS 'Zone of the route as of the business date (else the outlet''s), frozen at ingest; a later zone move never changes it.';
COMMENT ON COLUMN app.visit.cluster_id IS 'Outlet''s cluster at ingest, frozen.';
COMMENT ON COLUMN app.visit.outlet_channel IS 'Outlet''s channel (GT, DCC, ...) at ingest, frozen.';
COMMENT ON COLUMN app.visit.outlet_geo_class IS 'Outlet''s geo class at ingest, frozen.';
COMMENT ON COLUMN app.memo.zone_id IS 'Zone of the route as of the business date (else the outlet''s), frozen at ingest; reports attribute the sale to it.';
COMMENT ON COLUMN app.memo.cluster_id IS 'Outlet''s cluster at ingest, frozen.';
COMMENT ON COLUMN app.memo.outlet_channel IS 'Outlet''s channel (GT, DCC, ...) at ingest, frozen.';
COMMENT ON COLUMN app.memo.outlet_geo_class IS 'Outlet''s geo class at ingest, frozen.';
COMMENT ON COLUMN app.due_collection.zone_id IS 'Zone of the route as of the business date (else the outlet''s), frozen at ingest.';
COMMENT ON COLUMN app.due_collection.cluster_id IS 'Outlet''s cluster at ingest, frozen.';
COMMENT ON COLUMN app.stock_movement.zone_id IS 'Zone of the route as of the business date, frozen at ingest.';
