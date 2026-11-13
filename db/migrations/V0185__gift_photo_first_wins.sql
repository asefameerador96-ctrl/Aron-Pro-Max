-- V0185 (lane portal-loyalty-api, push C): gift hand-over photos, first by captured_at wins (contract 1.16.0, SRM-B-77 to 80, D-LOY-API-20).
-- V0007 allowed ONE photo per Astha assignment and per campaign unit and refused the second as gift_photo_exists. The brief says otherwise: the first photo by
-- captured_at is the lock and a later one is STORED, flagged, never deleted and never refused silently. So:
-- 1. app.gift_photo gets is_duplicate / flagged_at. A BEFORE INSERT trigger decides it under an advisory lock per assignment (or unit): the new photo is the winner only if its
--    captured_at is earlier than the current winner's (the current winner is then flagged), else it is the flagged duplicate. Out-of-order arrival gives the same winner.
-- 2. The two one-per-key unique indexes now cover winners only (NOT is_duplicate), so exactly one winner per key is still enforced by the database.
-- 3. The server geofence re-check of the photo fix is stored beside the phone's data (server_verdict, server_distance_m, server_radius_m, server_checked_at), like app.visit.
-- 4. The synced-row guard lets those columns (and is_duplicate, flagged_at, check_flag) move; everything else stays immutable.
-- 5. Ties: the order of a key's photos is (captured_at, client_uuid), the same in the BEFORE INSERT trigger and in app.gift_photo_elect, so the winner never depends on arrival.
-- 6. A campaign-unit photo that fails the handler's checks (unit_no within the redeemed qty, the gift is a line of that redemption, the outlet is the redemption's, the
--    uploader's reach) is STORED and flagged: check_flag names the reason and is_duplicate is true, so it never counts as the unit's photo and the next eligible photo is elected.
-- 7. An admin data void of the winner promotes the earliest remaining eligible photo and the assignment (photo_media_uuid, locked_at) follows: app.gift_photo_elect.

SET lock_timeout = '5s';

ALTER TABLE app.gift_photo
  ADD COLUMN is_duplicate boolean NOT NULL DEFAULT false,
  ADD COLUMN flagged_at timestamptz,
  ADD COLUMN check_flag text CHECK (check_flag IN ('unit_out_of_range','gift_not_in_redemption','outlet_mismatch','out_of_reach')),
  ADD COLUMN server_verdict text CHECK (server_verdict IN ('in_range','out_of_range','accuracy_too_low','no_fix','mocked','no_outlet_location')),
  ADD COLUMN server_distance_m double precision CHECK (server_distance_m >= 0),
  ADD COLUMN server_radius_m int CHECK (server_radius_m > 0),
  ADD COLUMN server_checked_at timestamptz;
COMMENT ON COLUMN app.gift_photo.is_duplicate IS 'True for a photo that is not the first by captured_at for its assignment or unit; stored and kept, never counted as the lock (V0185).';
COMMENT ON COLUMN app.gift_photo.flagged_at IS 'When the photo was flagged as a duplicate or as failing its checks.';
COMMENT ON COLUMN app.gift_photo.check_flag IS 'Why a campaign-unit photo failed the ingest checks (unit_out_of_range, gift_not_in_redemption, outlet_mismatch, out_of_reach); null when it passed. Such a photo is stored, flagged (is_duplicate) and never the unit''s photo.';
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
    SELECT id, captured_at, client_uuid INTO w FROM app.gift_photo
     WHERE gift_assignment_id = NEW.gift_assignment_id AND voided_at IS NULL AND NOT is_duplicate;
  ELSE
    PERFORM pg_advisory_xact_lock(hashtextextended('gift_photo:u:' || NEW.redemption_client_uuid || ':' || NEW.gift_id || ':' || coalesce(NEW.unit_no, 0), 0));
    SELECT id, captured_at, client_uuid INTO w FROM app.gift_photo
     WHERE redemption_client_uuid = NEW.redemption_client_uuid AND gift_id = NEW.gift_id AND unit_no IS NOT DISTINCT FROM NEW.unit_no AND voided_at IS NULL AND NOT is_duplicate;
  END IF;
  IF NEW.voided_at IS NOT NULL OR w.id IS NULL THEN
    NEW.is_duplicate := false;
  ELSIF (NEW.captured_at, NEW.client_uuid) < (w.captured_at, w.client_uuid) THEN   -- the order (captured_at, client_uuid) is also app.gift_photo_elect's
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

-- Elects the winner of one key again from the table: the earliest by (captured_at, client_uuid) among photos that are not voided and passed their checks. The others are flagged
-- duplicates, the winner is not; for an Astha assignment the assignment's photo_media_uuid follows the winner and, with no winner left, the lock opens again (locked_at is
-- kept while a winner exists: the handler sets it). Used after a void of the winner and after a unit photo is flagged as failing its checks. Returns the winner's id or null.
CREATE FUNCTION app.gift_photo_elect(p_assignment bigint, p_redemption uuid, p_gift bigint, p_unit smallint) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  win record;
BEGIN
  IF p_assignment IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('gift_photo:a:' || p_assignment, 0));
  ELSE
    PERFORM pg_advisory_xact_lock(hashtextextended('gift_photo:u:' || p_redemption || ':' || p_gift || ':' || coalesce(p_unit, 0), 0));
  END IF;
  SELECT id, photo_uuid INTO win FROM app.gift_photo
   WHERE voided_at IS NULL AND check_flag IS NULL
     AND CASE WHEN p_assignment IS NOT NULL THEN gift_assignment_id = p_assignment
              ELSE redemption_client_uuid = p_redemption AND gift_id = p_gift AND unit_no IS NOT DISTINCT FROM p_unit END
   ORDER BY captured_at, client_uuid LIMIT 1;
  -- demote first, promote second: the partial unique indexes allow one winner per key at every step
  UPDATE app.gift_photo SET is_duplicate = true, flagged_at = COALESCE(flagged_at, now())
   WHERE voided_at IS NULL AND NOT is_duplicate AND id IS DISTINCT FROM win.id
     AND CASE WHEN p_assignment IS NOT NULL THEN gift_assignment_id = p_assignment
              ELSE redemption_client_uuid = p_redemption AND gift_id = p_gift AND unit_no IS NOT DISTINCT FROM p_unit END;
  IF win.id IS NOT NULL THEN
    UPDATE app.gift_photo SET is_duplicate = false, flagged_at = NULL WHERE id = win.id AND is_duplicate;
  END IF;
  IF p_assignment IS NOT NULL THEN
    UPDATE app.gift_assignment SET photo_media_uuid = win.photo_uuid, locked_at = CASE WHEN win.id IS NULL THEN NULL ELSE locked_at END
     WHERE id = p_assignment AND (photo_media_uuid IS DISTINCT FROM win.photo_uuid OR (win.id IS NULL AND locked_at IS NOT NULL));
  END IF;
  RETURN win.id;
END $$;

CREATE FUNCTION app.gift_photo_after_void() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM app.gift_photo_elect(NEW.gift_assignment_id, NEW.redemption_client_uuid, NEW.gift_id, NEW.unit_no);
  RETURN NULL;
END $$;
-- Only the void of a photo that was the key's winner changes anything.
CREATE TRIGGER gift_photo_after_void AFTER UPDATE OF voided_at ON app.gift_photo
  FOR EACH ROW WHEN (OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL AND NOT NEW.is_duplicate)
  EXECUTE FUNCTION app.gift_photo_after_void();

CREATE OR REPLACE TRIGGER gift_photo_immutable BEFORE UPDATE OR DELETE ON app.gift_photo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'is_duplicate', 'flagged_at', 'check_flag', 'server_verdict', 'server_distance_m', 'server_radius_m', 'server_checked_at', 'data_origin');

-- The new table and function get the standard role grants (api_rw, worker_rw, readers; app.apply_db_role_grants).
SELECT app.apply_db_role_grants();
