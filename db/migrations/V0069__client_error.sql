-- V0069 app.client_error: error reports from the web app (POST /v1/client-errors, contract reportClientError,
-- F-SYS-032, D24-80; answers docs/requests/backend-core-client-error-table.md). Phones keep using app.app_error (sync
-- records with phone-only columns). The API inserts once per error_uuid (INSERT ... ON CONFLICT (error_uuid) DO
-- NOTHING) with user_id from the token and business_date = Asia/Dhaka date of received_at.
--
-- Rows never change: UPDATE and TRUNCATE are refused. Retention (F-SYS-063) is the telemetry class like app.app_error
-- (docs/16 s13, app.retention_policy): the table is not partitioned, so the retention job deletes old rows by
-- business_date as worker_rw, the only role with DELETE; the API cannot delete.

SET lock_timeout = '5s';

CREATE TABLE app.client_error (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  error_uuid    uuid NOT NULL UNIQUE,
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  source        text NOT NULL CHECK (source IN ('web')),
  occurred_at   timestamptz NOT NULL,
  received_at   timestamptz NOT NULL DEFAULT now(),
  business_date date NOT NULL,
  page          text CHECK (length(page) <= 200),
  message       text NOT NULL CHECK (length(message) <= 500),
  stack         text CHECK (length(stack) <= 16000),
  build         text CHECK (length(build) <= 40)
);
CREATE INDEX client_error_business_date ON app.client_error (business_date);
CREATE INDEX client_error_user ON app.client_error (user_id, received_at);
CREATE TRIGGER client_error_immutable BEFORE UPDATE ON app.client_error
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER client_error_no_truncate BEFORE TRUNCATE ON app.client_error
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.client_error FROM PUBLIC;

COMMENT ON TABLE app.client_error IS 'One row is a privacy-scrubbed error report from the web app (POST /v1/client-errors, F-SYS-032); phones report errors as app.app_error.
owner: backend:platform | capture: ONLINE | retention: telemetry | pii: none';
COMMENT ON COLUMN app.client_error.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.client_error.error_uuid IS 'UUID v4 the browser generated for the report; idempotency key (a repeat stores nothing).';
COMMENT ON COLUMN app.client_error.user_id IS 'User of the token that sent the report (app.app_user), never from the body.';
COMMENT ON COLUMN app.client_error.source IS 'Client that sent the report: web.';
COMMENT ON COLUMN app.client_error.occurred_at IS 'UTC time of the error by the browser clock, clamped by the API.';
COMMENT ON COLUMN app.client_error.received_at IS 'UTC time the server stored the report.';
COMMENT ON COLUMN app.client_error.business_date IS 'Asia/Dhaka business date of received_at; retention counts from it.';
COMMENT ON COLUMN app.client_error.page IS 'Route of the web page the error happened on, without query string (at most 200 characters).';
COMMENT ON COLUMN app.client_error.message IS 'Scrubbed error message (at most 500 characters).';
COMMENT ON COLUMN app.client_error.stack IS 'Scrubbed stack trace (at most 16000 characters).';
COMMENT ON COLUMN app.client_error.build IS 'Build id of the web app that sent the report.';

-- api_rw inserts and reads (its 'app.*' row) but never updates; the worker reads (its 'app.*' row) and deletes for retention.
UPDATE app.db_role_grant SET except_tables = except_tables || '{client_error}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update' AND NOT ('client_error' = ANY (except_tables));
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('worker_rw', 'app', 'client_error', 'DELETE', 'retention of web error reports (F-SYS-063, telemetry class)');
SELECT app.apply_db_role_grants();
