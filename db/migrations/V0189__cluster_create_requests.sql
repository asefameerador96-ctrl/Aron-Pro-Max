-- V0189 sr-server train 2, push B2 (contract 1.16.0; D-SV2-04): cluster creation rides on the outlet request pipeline.
-- An SR proposes a NEW cluster for the route it works with an `outlet_change_request` record of request_type `cluster_create` (no outlet, the
-- name in proposed.name, the route on the envelope); the AMO verifies, the portal approves, and the approval makes a live cluster of the route's zone
-- and records it in `created_cluster_id` (write-once). A rejected `cluster_name_exists` is a new record outcome code (sync_rejected).
-- Every widening CHECK / new FK is NOT VALID here and validated by V0190 (squawk rule). Lock: ONE LOCK ... NOWAIT retried for about 60 s (V0144 pattern).
SET lock_timeout = '5s';

DO $$
DECLARE n int := 0; old_check text;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.outlet_change_request, app.sync_rejected IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0189: app.outlet_change_request or app.sync_rejected stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.outlet_change_request ADD COLUMN IF NOT EXISTS created_cluster_id bigint;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'outlet_change_request_created_cluster_fk' AND conrelid = 'app.outlet_change_request'::regclass) THEN
    ALTER TABLE app.outlet_change_request ADD CONSTRAINT outlet_change_request_created_cluster_fk FOREIGN KEY (created_cluster_id) REFERENCES app.cluster(id) NOT VALID;
  END IF;

  ALTER TABLE app.outlet_change_request DROP CONSTRAINT IF EXISTS outlet_change_request_request_type_check;
  ALTER TABLE app.outlet_change_request ADD CONSTRAINT outlet_change_request_request_type_check
    CHECK (request_type IN ('new','close','info','cluster','location','route_add','cluster_create')) NOT VALID;

  -- "a new shop has no outlet yet" becomes "a new shop or a new cluster has no outlet": the unnamed table CHECK is found by its definition.
  FOR old_check IN SELECT conname FROM pg_constraint WHERE conrelid = 'app.outlet_change_request'::regclass AND contype = 'c'
                    AND pg_get_constraintdef(oid) LIKE '%request_type%new%outlet_id IS NULL%' AND conname <> 'outlet_change_request_outlet_rule' LOOP
    EXECUTE format('ALTER TABLE app.outlet_change_request DROP CONSTRAINT %I', old_check);
  END LOOP;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'outlet_change_request_outlet_rule' AND conrelid = 'app.outlet_change_request'::regclass) THEN
    ALTER TABLE app.outlet_change_request ADD CONSTRAINT outlet_change_request_outlet_rule CHECK ((request_type IN ('new', 'cluster_create')) = (outlet_id IS NULL)) NOT VALID;
  END IF;

  ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
  ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
    'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
    'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'memo_no_invalid',
    'edit_not_allowed', 'chain_too_deep', 'voided_by_admin', 'lines_exceed_max', 'qty_invalid', 'price_invalid',
    'attendance_duplicate', 'server_error', 'retry_exhausted', 'unknown_gift', 'insufficient_points',
    'gift_photo_exists', 'day_final_submitted', 'ticket_state_invalid', 'issue_type_unknown', 'cluster_name_exists')) NOT VALID;
END $$;

-- created_cluster_id is written once by the approval (write-once '=' like created_outlet_id). Recreated, never edited (V0007 is history).
CREATE OR REPLACE TRIGGER outlet_change_request_immutable BEFORE UPDATE OR DELETE ON app.outlet_change_request
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', 'status_changed_at', '=verified_by', '=verified_at', '=decided_by', '=decided_at', '=decision_reason', '=created_outlet_id', '=created_cluster_id', 'data_origin');

COMMENT ON COLUMN app.outlet_change_request.created_cluster_id IS 'The live cluster an approved cluster_create request made (set once at the approval); null for every other request.';
COMMENT ON COLUMN app.outlet_change_request.request_type IS 'Kind of request: new, close, info, cluster, location, route_add or cluster_create (a proposed new cluster for the route the SR works; no outlet).';
