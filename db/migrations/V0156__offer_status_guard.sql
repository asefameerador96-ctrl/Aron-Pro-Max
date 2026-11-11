-- V0156 database guard for the offer lifecycle (lane portal-discount-api, follow-up of D-DISC-API-06; local until the lead schedules a train).
-- The handler already enforces draft -> active -> inactive; this trigger makes the database refuse any other status move, and refuses a
-- change of current_version_id once an offer has left draft (a version is only appended while the offer is a draft). Creating an offer
-- sets current_version_id once (null -> the first version), which stays allowed.
SET lock_timeout = '5s';

CREATE FUNCTION app.offer_lifecycle_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NOT ((OLD.status = 'draft' AND NEW.status = 'active') OR (OLD.status = 'active' AND NEW.status = 'inactive')) THEN
    RAISE EXCEPTION 'app.offer: status % -> % is not allowed (draft -> active -> inactive only)', OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.current_version_id IS DISTINCT FROM OLD.current_version_id AND OLD.current_version_id IS NOT NULL AND OLD.status <> 'draft' THEN
    RAISE EXCEPTION 'app.offer: the rule of a % offer is final (create a new offer)', OLD.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.offer_lifecycle_guard() IS 'Allows only draft -> active -> inactive status moves on app.offer and a change of current_version_id only while the offer is a draft (or when it is first set).';

CREATE TRIGGER offer_lifecycle_guard BEFORE UPDATE ON app.offer
  FOR EACH ROW EXECUTE FUNCTION app.offer_lifecycle_guard();
