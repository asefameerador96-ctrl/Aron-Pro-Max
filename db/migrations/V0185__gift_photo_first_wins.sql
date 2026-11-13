-- V0185 (lane portal-loyalty-api, push C): gift hand-over photos, first by captured_at wins (contract 1.17.0, SRM-B-77 to 80, D-LOY-API-20).
-- V0007 allowed ONE photo per Astha assignment and per campaign unit and refused the second as gift_photo_exists. The brief says otherwise: the first photo by
-- captured_at is the lock and a later one is STORED, flagged, never deleted and never refused silently. So:
-- 1. app.gift_photo gets is_duplicate / flagged_at. A BEFORE INSERT trigger decides it under an advisory lock per assignment (or unit): the new photo is the winner only if its
--    captured_at is earlier than the current winner's (the current winner is then flagged), else it is the flagged duplicate. Out-of-order arrival gives the same winner.
-- 2. The two one-per-key unique indexes now cover winners only (NOT is_duplicate), so exactly one winner per key is still enforced by the database.
-- 3. The server geofence re-check of the photo fix is stored beside the phone's data (server_verdict, server_distance_m, server_radius_m, server_checked_at), like app.visit.
-- 4. The synced-row guard lets those columns (and is_duplicate, flagged_at) move; everything else stays immutable.

SET lock_timeout = '5s';

ALTER TABLE app.gift_photo
  ADD COLUMN is_duplicate boolean NOT NULL DEFAULT false,
  ADD COLUMN flagged_at timestamptz,
  ADD COLUMN server_verdict text CHECK (server_verdict IN ('in_range','out_of_range','accuracy_too_low','no_fix','mocked','no_outlet_location')),
  ADD COLUMN server_distance_m double precision CHECK (server_distance_m >= 0),
  ADD COLUMN server_radius_m int CHECK (server_radius_m > 0),
  ADD COLUMN server_checked_at timestamptz;
COMMENT ON COLUMN app.gift_photo.is_duplicate IS 'True for a photo that is not the first by captured_at for its assignment or unit; stored and kept, never counted as the lock (V0185).';
COMMENT ON COLUMN app.gift_photo.flagged_at IS 'When the photo was flagged as a duplicate.';
COMMENT ON COLUMN app.gift_photo.server_verdict IS 'The server re-check of the photo fix against the outlet location (same function as the phone, docs/24 s11.3); the photo is never refused for it.';
COMMENT ON COLUMN app.gift_photo.server_distance_m IS 'Metres between the photo fix and the outlet location at capture, from the server re-check; null when there was no usable fix or location.';
COMMENT ON COLUMN app.gift_photo.server_radius_m IS 'The geofence radius in metres the server re-check used (the value in force at capture).';
COMMENT ON COLUMN app.gift_photo.server_checked_at IS 'When the server re-check ran; null until it has.';

-- The table holds no row before the loyalty field screens ship (programmes were deferred, docs/27); lock_timeout above bounds the wait.
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.gift_photo_one_per_assignment;
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.gift_photo_one_per_unit;
-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX gift_photo_one_per_assignment ON app.gift_photo (gift_assignment_id)
  WHERE gift_assignment_id IS NOT NULL AND voided_at IS NULL AND NOT is_duplicate;
-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX gift_photo_one_per_unit ON app.gift_photo (redemption_client_uuid, gift_id, unit_no)
  WHERE redemption_client_uuid IS NOT NULL AND voided_at IS NULL AND NOT is_duplicate;

CREATE FUNCTION app.gift_photo_first_wins() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  w record;
BEGIN
  IF NEW.gift_assignment_id IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('gift_photo:a:' || NEW.gift_assignment_id, 0));
    SELECT id, captured_at INTO w FROM app.gift_photo
     WHERE gift_assignment_id = NEW.gift_assignment_id AND voided_at IS NULL AND NOT is_duplicate;
  ELSE
    PERFORM pg_advisory_xact_lock(hashtextextended('gift_photo:u:' || NEW.redemption_client_uuid || ':' || NEW.gift_id || ':' || coalesce(NEW.unit_no, 0), 0));
    SELECT id, captured_at INTO w FROM app.gift_photo
     WHERE redemption_client_uuid = NEW.redemption_client_uuid AND gift_id = NEW.gift_id AND unit_no IS NOT DISTINCT FROM NEW.unit_no AND voided_at IS NULL AND NOT is_duplicate;
  END IF;
  IF NEW.voided_at IS NOT NULL OR w.id IS NULL THEN
    NEW.is_duplicate := false;
  ELSIF NEW.captured_at < w.captured_at THEN
    UPDATE app.gift_photo SET is_duplicate = true, flagged_at = now() WHERE id = w.id;
    NEW.is_duplicate := false;
  ELSE
    NEW.is_duplicate := true;
    NEW.flagged_at := now();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER gift_photo_first_wins BEFORE INSERT ON app.gift_photo
  FOR EACH ROW EXECUTE FUNCTION app.gift_photo_first_wins();

CREATE OR REPLACE TRIGGER gift_photo_immutable BEFORE UPDATE OR DELETE ON app.gift_photo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'is_duplicate', 'flagged_at', 'server_verdict', 'server_distance_m', 'server_radius_m', 'server_checked_at', 'data_origin');

-- The new table and function get the standard role grants (api_rw, worker_rw, readers; app.apply_db_role_grants).
SELECT app.apply_db_role_grants();
