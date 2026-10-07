-- V0013 bind a phone refresh family to the device_uuid it was issued to (request backend-refresh-family-device-uuid,
-- rows F-SYS-002 / F-API-002). A phone without an app.device row (dev and test phones before enrolment, while
-- cfg.device.require_enrolled is false) keeps a refresh and upload grant that only its own X-Device-Id can use.

ALTER TABLE app.refresh_family ADD COLUMN device_uuid uuid;
COMMENT ON COLUMN app.refresh_family.device_uuid IS
  'X-Device-Id the grant was issued to (phones); every refresh must present the same device_uuid';

-- A phone family issued before the binding rule with neither a device row nor a device_uuid could be replayed from
-- another phone, and PostgreSQL re-checks a CHECK on every later UPDATE of such a row (so it could not even be revoked).
-- Revoke them first (the phone logs in again), then every phone family is bound to a device row or a device_uuid.
UPDATE app.refresh_family SET revoked_at = now(), revoke_reason = 'device_revoked'
 WHERE client <> 'web' AND device_id IS NULL AND revoked_at IS NULL;

ALTER TABLE app.refresh_family ADD CONSTRAINT refresh_family_phone_bound
  CHECK (client = 'web' OR device_id IS NOT NULL OR device_uuid IS NOT NULL OR revoked_at IS NOT NULL) NOT VALID;
ALTER TABLE app.refresh_family VALIDATE CONSTRAINT refresh_family_phone_bound;

CREATE INDEX refresh_family_device_uuid ON app.refresh_family (device_uuid) WHERE device_uuid IS NOT NULL AND revoked_at IS NULL;
