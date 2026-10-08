# Request to db (from backend-core, 2026-10-08): table for web error reports (`POST /v1/client-errors`)

`POST /v1/client-errors` (contract reportClientError, F-SYS-032, D24-80) takes a privacy-scrubbed error report from the
web app and answers 202, deduplicated by `error_uuid`. docs/24 s13 lists a back-office table `client_error`, but no
migration creates it (phones use `app.app_error`, which needs phone-only columns: `app_version` in the phone format,
`exception_class`, capture facts). The endpoint has nowhere to store a report until it exists.

## Ask (forward-only)
```sql
CREATE TABLE app.client_error (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  error_uuid    uuid NOT NULL UNIQUE,                                   -- idempotency key from the browser
  user_id       bigint NOT NULL REFERENCES app.app_user(id),            -- from the token, never from the body
  source        text NOT NULL CHECK (source IN ('web')),
  occurred_at   timestamptz NOT NULL,                                   -- browser clock, clamped by the API
  received_at   timestamptz NOT NULL DEFAULT now(),
  business_date date NOT NULL,                                          -- Asia/Dhaka date of received_at
  page          text CHECK (length(page) <= 200),
  message       text NOT NULL CHECK (length(message) <= 500),
  stack         text CHECK (length(stack) <= 16000),
  build         text CHECK (length(build) <= 40)
);
CREATE INDEX ON app.client_error (business_date);
CREATE INDEX ON app.client_error (user_id, received_at);
-- immutable like app.app_error (BEFORE UPDATE OR DELETE trigger); the retention job removes old rows by business_date
```
Please also add it to the data dictionary and to the F-SYS-063 retention list (same period as `app_error`).

Until it lands, `POST /v1/client-errors` is built with its test assumption-guarded on the table (the route answers 503
`ERR_SERVICE_UNAVAILABLE` while the table is missing, never 500).
