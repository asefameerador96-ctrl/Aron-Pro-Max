-- V0054 app.security_event (AUD-SEC-03, docs/21 s8.1; answers docs/requests/backend-core-security-event-table.md).
-- The durable sink of the backend's SecurityEvents port: append-only like app.audit_log (no hash chain: events are
-- facts about attempts, not a change trail), retention audit (7 years). The writer drains a bounded queue off the
-- request path and already deduplicates login_failure per username hash, device and minute, so no unique key.
-- user_id has no FK: a failure may name an account that does not exist. detail holds short facts only (route, code,
-- username_hash, ip_class, failures, family); never a password, token or OTP.

SET lock_timeout = '5s';

CREATE TABLE app.security_event (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at           timestamptz NOT NULL,
  kind         text NOT NULL CHECK (kind IN ('login_failure', 'lockout', 'refresh_reuse', 'device_proof_invalid',
                                             'device_state_refused', 'scope_changed', 'password_change', 'force_logout', 'otp_view')),
  user_id      bigint,
  device_uuid  uuid,
  request_id   uuid,
  detail       jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail) = 'object' AND octet_length(detail::text) <= 2000)
);
CREATE INDEX security_event_kind_at ON app.security_event (kind, at);
CREATE INDEX security_event_user_at ON app.security_event (user_id, at) WHERE user_id IS NOT NULL;

CREATE TRIGGER security_event_append_only BEFORE UPDATE OR DELETE ON app.security_event
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER security_event_no_truncate BEFORE TRUNCATE ON app.security_event
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.security_event FROM PUBLIC;

-- api_rw inserts and reads (its 'app.*' row) but never updates; auth_rw inserts (login failures, lockouts, reuse);
-- worker_rw reads for the alerts (its 'app.*' row); support reads nothing here.
UPDATE app.db_role_grant SET except_tables = except_tables || '{security_event}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update' AND NOT ('security_event' = ANY (except_tables));
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('auth_rw', 'app', 'security_event', 'INSERT', 'login failures, lockouts and refresh reuse from the auth path');
SELECT app.apply_db_role_grants();

COMMENT ON TABLE app.security_event IS 'Append-only security events (login failures, lockouts, refresh reuse, device proof and state refusals, scope and password changes, force logout, OTP views) for alerts and support (docs/21 s8.1).
owner: backend:platform | capture: SERVER | retention: audit | pii: personal';
COMMENT ON COLUMN app.security_event.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.security_event.at IS 'UTC instant of the event.';
COMMENT ON COLUMN app.security_event.kind IS 'login_failure, lockout, refresh_reuse, device_proof_invalid, device_state_refused, scope_changed, password_change, force_logout or otp_view.';
COMMENT ON COLUMN app.security_event.user_id IS 'User the event names; no foreign key (a failure may name an unknown account); null when none.';
COMMENT ON COLUMN app.security_event.device_uuid IS 'Device the event came from; null when unknown.';
COMMENT ON COLUMN app.security_event.request_id IS 'Request id of the API call, to join the structured log line.';
COMMENT ON COLUMN app.security_event.detail IS '[pii:personal] Short facts (route, code, username_hash, ip_class, failures, family); never a password, token or OTP.';
