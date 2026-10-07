-- V0063 app.device.last_sync_error (F-SYS-050; answers docs/requests/backend-core-device-telemetry-columns.md).
-- POST /v1/sync/batch writes the phone's X-Last-Sync-Error (last transport or problem code) in the same throttled
-- UPDATE as pending_rows_reported and app_version; an invalid value is ignored, an absent header leaves it as it is.
-- The ops sync-health page (backend-reports OpsApi) reads it. api_rw's '*/update' row already covers the column.
-- A nullable column without a default is a catalogue-only change; the CHECK is added NOT VALID (no scan under the
-- lock) and validated in V0064.

SET lock_timeout = '5s';

ALTER TABLE app.device ADD COLUMN last_sync_error text;
ALTER TABLE app.device ADD CONSTRAINT device_last_sync_error_format
  CHECK (last_sync_error ~ '^[A-Za-z0-9_.:-]{1,64}$') NOT VALID;

COMMENT ON COLUMN app.device.last_sync_error IS 'Last sync transport or problem code the phone reported (X-Last-Sync-Error); null when none was reported.';
