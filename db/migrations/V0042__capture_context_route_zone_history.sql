-- V0042 capture context frozen on the row, and route-zone history (audit AUD-DA-02 parts 1 and 2; docs/16 P3, P6, s8.1
-- "a re-parented outlet never rewrites history").
--
-- (1) visit and memo gain zone_id, cluster_id, outlet_channel, outlet_geo_class; due_collection and stock_movement gain
--     zone_id and cluster_id. A BEFORE INSERT trigger always sets them, overwriting whatever the writer sent (the sync
--     writer copies payload keys into columns, so a device must never choose its own context): zone from the route's
--     zone on the row's business_date (app.route_zone_history), cluster from the outlet's placement on that date
--     (app.outlet_placement_history, else the outlet's current cluster), channel and geo_class from the outlet as it is
--     at ingest (the outlet master keeps no history of those). Ingest needs no change.
--     The columns are write-once (guard_synced_row '='): a later route move or outlet edit never rewrites them; a
--     server backfill may fill a NULL by UPDATE (rows captured before this migration keep NULL until then; route moves
--     before this migration are not known, every route starts with its current zone "since ever").
--     No foreign keys: partitioned parents cannot take NOT VALID keys (squawk gate), and the values are history.
-- (2) app.route_zone_history keeps every zone a route belonged to, with the btree_gist exclusion of route_planned;
--     it is written by triggers on app.route (insert, and update of zone_id). A zone move takes effect on the Asia/Dhaka
--     business date of the change; a second move on the same date replaces that date's row.
-- Part 3 (SCD2 dimensions) is V0043.

SET lock_timeout = '5s';

-- ---------- (2) route-zone history ----------
CREATE TABLE app.route_zone_history (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id   bigint NOT NULL REFERENCES app.route(id),
  zone_id    bigint NOT NULL REFERENCES app.zone(id),
  valid_from date,                                   -- null = since the route existed
  valid_to   date,                                   -- null = current
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE UNIQUE INDEX route_zone_history_current ON app.route_zone_history (route_id) WHERE valid_to IS NULL;

INSERT INTO app.route_zone_history (route_id, zone_id) SELECT id, zone_id FROM app.route;

CREATE FUNCTION app.route_zone_track() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE d date := app.dhaka_date(now());
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO app.route_zone_history (route_id, zone_id) VALUES (NEW.id, NEW.zone_id);
  ELSIF NEW.zone_id IS DISTINCT FROM OLD.zone_id THEN
    -- A second move on the same business date replaces that date's row instead of leaving an empty range.
    UPDATE app.route_zone_history SET zone_id = NEW.zone_id WHERE route_id = NEW.id AND valid_to IS NULL AND valid_from = d;
    IF NOT FOUND THEN
      UPDATE app.route_zone_history SET valid_to = d WHERE route_id = NEW.id AND valid_to IS NULL;
      INSERT INTO app.route_zone_history (route_id, zone_id, valid_from) VALUES (NEW.id, NEW.zone_id, d);
    END IF;
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION app.route_zone_track() IS 'Keeps app.route_zone_history in step with app.route.zone_id (effective on the Asia/Dhaka date of the change).';
CREATE TRIGGER route_zone_track AFTER INSERT OR UPDATE OF zone_id ON app.route
  FOR EACH ROW EXECUTE FUNCTION app.route_zone_track();

CREATE FUNCTION app.route_zone_on(p_route_id bigint, p_date date) RETURNS bigint
LANGUAGE sql STABLE
AS $$
  SELECT zone_id FROM app.route_zone_history
   WHERE route_id = p_route_id AND (valid_from IS NULL OR valid_from <= p_date) AND (valid_to IS NULL OR valid_to > p_date)
$$;
COMMENT ON FUNCTION app.route_zone_on(bigint, date) IS 'Zone the route belonged to on the given business date (null if the route did not exist).';

-- ---------- (1) capture context ----------
ALTER TABLE app.visit ADD COLUMN zone_id bigint, ADD COLUMN cluster_id bigint, ADD COLUMN outlet_channel text, ADD COLUMN outlet_geo_class text;
ALTER TABLE app.memo ADD COLUMN zone_id bigint, ADD COLUMN cluster_id bigint, ADD COLUMN outlet_channel text, ADD COLUMN outlet_geo_class text;
ALTER TABLE app.due_collection ADD COLUMN zone_id bigint, ADD COLUMN cluster_id bigint;
ALTER TABLE app.stock_movement ADD COLUMN zone_id bigint, ADD COLUMN cluster_id bigint;

-- Stamps the context columns the writer left NULL. TG_ARGV[0] = 'outlet' when the table has outlet_id and the
-- channel and geo_class columns, 'outlet_cluster' when it has outlet_id but only zone and cluster, 'route' otherwise.
CREATE FUNCTION app.stamp_capture_context() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  o_cluster bigint;
  o_channel text;
  o_geo     text;
  n         jsonb := to_jsonb(NEW);
  oid_      bigint;
BEGIN
  NEW.zone_id := NULL;
  NEW.cluster_id := NULL;
  IF NEW.route_id IS NOT NULL THEN
    NEW.zone_id := coalesce(app.route_zone_on(NEW.route_id, NEW.business_date), (SELECT zone_id FROM app.route WHERE id = NEW.route_id));
  END IF;
  IF TG_ARGV[0] IN ('outlet', 'outlet_cluster') THEN
    oid_ := (n ->> 'outlet_id')::bigint;
    IF oid_ IS NOT NULL THEN
      SELECT o.cluster_id, o.channel, o.geo_class INTO o_cluster, o_channel, o_geo FROM app.outlet o WHERE o.id = oid_;
      NEW.cluster_id := coalesce(
        (SELECT h.cluster_id FROM app.outlet_placement_history h
          WHERE h.outlet_id = oid_ AND h.valid_from <= NEW.business_date AND (h.valid_to IS NULL OR h.valid_to > NEW.business_date)),
        o_cluster);
    END IF;
  END IF;
  IF TG_ARGV[0] = 'outlet' THEN
    NEW := jsonb_populate_record(NEW, jsonb_build_object('outlet_channel', o_channel, 'outlet_geo_class', o_geo));
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.stamp_capture_context() IS 'BEFORE INSERT: sets zone_id (route zone on the business date), cluster_id (outlet placement on that date) and, for visit and memo, the outlet channel and geo class, overwriting any value the writer sent.';

CREATE TRIGGER visit_stamp_context BEFORE INSERT ON app.visit FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet');
CREATE TRIGGER memo_stamp_context BEFORE INSERT ON app.memo FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet');
CREATE TRIGGER due_collection_stamp_context BEFORE INSERT ON app.due_collection FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('outlet_cluster');
CREATE TRIGGER stock_movement_stamp_context BEFORE INSERT ON app.stock_movement FOR EACH ROW EXECUTE FUNCTION app.stamp_capture_context('route');

-- The context columns are write-once: a NULL may be filled later (backfill), a value never changes.
CREATE OR REPLACE TRIGGER visit_immutable BEFORE UPDATE OR DELETE ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'server_verdict', 'server_distance_m', 'server_radius_m', 'server_max_accuracy_m', 'server_checked_at', '=close_client_uuid', '=close_received_at', '=close_captured_at', '=outcome_code', '=call_started_at', '=call_declined', '=ended_at', '=is_zero_sale',
    '=zone_id', '=cluster_id', '=outlet_channel', '=outlet_geo_class');
CREATE OR REPLACE TRIGGER memo_immutable BEFORE UPDATE OR DELETE ON app.memo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', 'status_changed_at', '=voided_by_client_uuid', '=superseded_by_client_uuid', 'server_flags',
    '=zone_id', '=cluster_id', '=outlet_channel', '=outlet_geo_class');
CREATE OR REPLACE TRIGGER due_collection_immutable BEFORE UPDATE OR DELETE ON app.due_collection
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', '=zone_id', '=cluster_id');
CREATE OR REPLACE TRIGGER stock_movement_immutable BEFORE UPDATE OR DELETE ON app.stock_movement
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', '=zone_id', '=cluster_id');

-- ---------- data dictionary ----------
COMMENT ON TABLE app.route_zone_history IS 'One row is a period in which a route belonged to a zone; written by triggers on app.route.
owner: db | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN app.route_zone_history.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.route_zone_history.route_id IS 'Route.';
COMMENT ON COLUMN app.route_zone_history.zone_id IS 'Zone the route belonged to in the period.';
COMMENT ON COLUMN app.route_zone_history.valid_from IS 'First Asia/Dhaka business date of the period (null = since the route existed).';
COMMENT ON COLUMN app.route_zone_history.valid_to IS 'Asia/Dhaka business date the period ended, exclusive (null = current).';
COMMENT ON COLUMN app.route_zone_history.created_at IS 'UTC instant the row was inserted on the server.';
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['visit', 'memo', 'due_collection', 'stock_movement'] LOOP
    EXECUTE format('COMMENT ON COLUMN app.%I.zone_id IS %L', t, 'Zone of the route on the business date, frozen at capture (never rewritten by a later route move).');
    EXECUTE format('COMMENT ON COLUMN app.%I.cluster_id IS %L', t, 'Cluster of the outlet on the business date, frozen at capture (null without an outlet).');
  END LOOP;
  FOREACH t IN ARRAY ARRAY['visit', 'memo'] LOOP
    EXECUTE format('COMMENT ON COLUMN app.%I.outlet_channel IS %L', t, 'Outlet channel code at capture (as the outlet master held it at ingest).');
    EXECUTE format('COMMENT ON COLUMN app.%I.outlet_geo_class IS %L', t, 'Outlet geo class code at capture (as the outlet master held it at ingest).');
  END LOOP;
END $$;

SELECT app.apply_db_role_grants();
