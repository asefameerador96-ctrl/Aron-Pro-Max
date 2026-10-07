-- V0018 domain-event compatibility (forward fix of V0017). The aggregate projector (backend:analytics, F-SYS-015) and
-- its tests write thin events: no payload_version and an empty payload, the source row found by source_client_uuid.
-- V0017 refused both. From here:
-- - payload_version defaults to 1 again (a producer that writes a later version must name it);
-- - every event must still be of a catalogued type and version, with a JSON object payload;
-- - the required keys of a version's schema are enforced only once app.domain_event_type.enforce_required is set for
--   it, by a db migration after its producer sends them (docs/requests). Until then the schema is the documented
--   target shape, not a guarantee, and consumers read the source row.

SET lock_timeout = '5s';

ALTER TABLE app.domain_event ALTER COLUMN payload_version SET DEFAULT 1;
ALTER TABLE app.domain_event_type ADD COLUMN enforce_required boolean NOT NULL DEFAULT false;

-- enforce_required may be switched on by a later migration; the schema itself stays fixed.
DROP TRIGGER domain_event_type_fixed ON app.domain_event_type;
CREATE TRIGGER domain_event_type_fixed BEFORE UPDATE OR DELETE ON app.domain_event_type
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('description', '=deprecated_at', 'enforce_required');

CREATE OR REPLACE FUNCTION app.domain_event_check_payload() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  req     jsonb;
  enforce boolean;
BEGIN
  SELECT t.payload_schema -> 'required', t.enforce_required INTO req, enforce
    FROM app.domain_event_type t
   WHERE t.event_type = NEW.event_type AND t.payload_version = NEW.payload_version;
  IF req IS NULL THEN
    RAISE EXCEPTION 'app.domain_event: % v% is not in app.domain_event_type', NEW.event_type, NEW.payload_version
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF jsonb_typeof(NEW.payload) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'app.domain_event: % v% payload is not a JSON object', NEW.event_type, NEW.payload_version
      USING ERRCODE = 'check_violation';
  END IF;
  IF enforce AND NOT (NEW.payload ?& ARRAY(SELECT jsonb_array_elements_text(req))) THEN
    RAISE EXCEPTION 'app.domain_event: % v% payload lacks required keys %', NEW.event_type, NEW.payload_version,
      ARRAY(SELECT k FROM jsonb_array_elements_text(req) k WHERE NOT NEW.payload ? k)
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

COMMENT ON COLUMN app.domain_event_type.enforce_required IS 'True once the producer sends the required keys; the insert trigger then refuses a payload without them.';
COMMENT ON COLUMN app.domain_event_type.payload_schema IS 'JSON Schema of the payload (the target shape); its required keys are enforced on insert when enforce_required.';
COMMENT ON COLUMN app.domain_event.payload_version IS 'Version of the payload shape, a row of app.domain_event_type with event_type; defaults to 1; null only on rows written before V0017.';
COMMENT ON FUNCTION app.domain_event_check_payload() IS 'Refuses an outbox row of an uncatalogued type or version or with a non-object payload, and, when the version enforces it, one that lacks a required key.';
