-- V0013 bind a phone refresh family to the device_uuid it was issued to (request backend-refresh-family-device-uuid,
-- rows F-SYS-002 / F-API-002). A phone without an app.device row (dev and test phones before enrolment, while
-- cfg.device.require_enrolled is false) keeps a refresh and upload grant that only its own X-Device-Id can use.

ALTER TABLE app.refresh_family ADD COLUMN device_uuid uuid;
COMMENT ON COLUMN app.refresh_family.device_uuid IS
  'X-Device-Id the grant was issued to (phones); every refresh must present the same device_uuid';

-- Every phone family is bound to a device row or a device_uuid. NOT VALID: families issued before this migration are
-- not re-checked (the API already refused unbound phone families); every new or updated row is.
ALTER TABLE app.refresh_family ADD CONSTRAINT refresh_family_phone_bound
  CHECK (client = 'web' OR device_id IS NOT NULL OR device_uuid IS NOT NULL) NOT VALID;

CREATE INDEX refresh_family_device_uuid ON app.refresh_family (device_uuid) WHERE device_uuid IS NOT NULL AND revoked_at IS NULL;
