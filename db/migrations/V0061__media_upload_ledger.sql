-- V0061 app.media_upload: ledger of the multipart media fallback (F-API-007; answers
-- docs/requests/backend-core-media-upload-ledger.md). POST /v1/media/upload is idempotent by (media_uuid, purpose): the
-- first upload inserts the row (INSERT ... ON CONFLICT DO NOTHING), a repeat finds it and answers replayed = true
-- without storing a second blob. business_date is the first upload's Dhaka date, so a retry on the next day resolves to
-- the same blob path. The app.media row still comes from the phone's media_meta sync record; this table only records
-- that the bytes arrived.
--
-- Rows never change (immutability trigger); the API inserts and reads, the worker reads (SELECT on app '*').

SET lock_timeout = '5s';

CREATE TABLE app.media_upload (
  media_uuid    uuid NOT NULL,
  purpose       text NOT NULL CHECK (purpose IN ('feedback','support')),
  sha256        bytea NOT NULL CHECK (length(sha256) = 32),
  bytes         int NOT NULL CHECK (bytes BETWEEN 1 AND 307200),
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  device_id     bigint REFERENCES app.device(id),
  blob_path     text NOT NULL UNIQUE CHECK (blob_path LIKE 'photos/%/' || media_uuid::text || '.jpg'),
  business_date date NOT NULL,
  uploaded_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (media_uuid, purpose)
);
CREATE INDEX media_upload_user ON app.media_upload (user_id, uploaded_at);
CREATE INDEX media_upload_device ON app.media_upload (device_id) WHERE device_id IS NOT NULL;
CREATE TRIGGER media_upload_immutable BEFORE UPDATE OR DELETE ON app.media_upload
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER media_upload_no_truncate BEFORE TRUNCATE ON app.media_upload
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.media_upload FROM PUBLIC;

COMMENT ON TABLE app.media_upload IS 'One row is a photo whose bytes arrived through the multipart fallback POST /v1/media/upload (feedback or support); the idempotency ledger of that endpoint (F-API-007).
owner: backend:media | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.media_upload.media_uuid IS 'Client-generated UUID v4 of the photo; with purpose the idempotency key of the upload.';
COMMENT ON COLUMN app.media_upload.purpose IS 'Why the photo was sent: feedback or support.';
COMMENT ON COLUMN app.media_upload.sha256 IS 'SHA-256 of the uploaded bytes (32 bytes).';
COMMENT ON COLUMN app.media_upload.bytes IS 'Size of the uploaded file in bytes (at most 300 KiB).';
COMMENT ON COLUMN app.media_upload.user_id IS 'User whose token made the upload (app.app_user).';
COMMENT ON COLUMN app.media_upload.device_id IS 'Device of the token that made the upload (app.device); null for a web upload.';
COMMENT ON COLUMN app.media_upload.blob_path IS 'Blob Storage path photos/{business_date}/{device_uuid}/{media_uuid}.jpg written on the first upload.';
COMMENT ON COLUMN app.media_upload.business_date IS 'Asia/Dhaka business date of the first upload; fixes the blob path for every retry.';
COMMENT ON COLUMN app.media_upload.uploaded_at IS 'UTC time the first upload was stored.';

-- Insert and read only: keep the table out of api_rw's blanket UPDATE grant (the trigger refuses it anyway).
UPDATE app.db_role_grant SET except_tables = except_tables || '{media_upload}'
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update' AND NOT ('media_upload' = ANY (except_tables));

SELECT app.apply_db_role_grants();
