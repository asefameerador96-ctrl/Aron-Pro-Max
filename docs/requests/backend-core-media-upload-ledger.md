# Request to db (from backend-core, 2026-10-07): ledger for the multipart media fallback (F-API-007)

`POST /v1/media/upload` (contract uploadMediaMultipart) must be idempotent by (`media_uuid`, `purpose`): a repeat
answers `replayed: true` and stores nothing. The `app.media` row is written later by the phone's `media_meta` sync
record (it needs `ref_client_uuid`, size and capture facts the multipart body does not carry), so the upload itself has
nowhere to record that it happened. The API writes the blob through a one-blob SAS to
`photos/{business_date}/{device_uuid}/{media_uuid}.jpg`; the business date must be the first upload's, so a retry the
next day lands on the same blob.

## Ask (forward-only)
```sql
CREATE TABLE app.media_upload (
  media_uuid    uuid NOT NULL,
  purpose       text NOT NULL CHECK (purpose IN ('feedback','support')),
  sha256        bytea NOT NULL CHECK (length(sha256) = 32),
  bytes         int NOT NULL CHECK (bytes BETWEEN 1 AND 307200),
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  device_id     bigint REFERENCES app.device(id),
  blob_path     text NOT NULL UNIQUE,
  business_date date NOT NULL,
  uploaded_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (media_uuid, purpose)
);
```
Grant INSERT/SELECT to the api login like `app.media`. Until it lands, F-API-007 stays blocked (the SAS path,
`POST /v1/media/sas`, covers every purpose including feedback and support).
