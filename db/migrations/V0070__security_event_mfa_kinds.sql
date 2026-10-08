-- V0070 app.security_event.kind gains mfa_enrol and mfa_verify_failure (docs/21 s8.1, D-485; answers
-- docs/requests/backend-core-mfa-security-events.md). The CHECK is replaced: the new one is added NOT VALID (no scan
-- under the lock) and validated in V0071; every existing row already satisfies it (the old list is a subset).

SET lock_timeout = '5s';

ALTER TABLE app.security_event DROP CONSTRAINT security_event_kind_check;
ALTER TABLE app.security_event ADD CONSTRAINT security_event_kind_check
  CHECK (kind IN ('login_failure', 'lockout', 'refresh_reuse', 'device_proof_invalid', 'device_state_refused',
                  'scope_changed', 'password_change', 'force_logout', 'otp_view', 'mfa_enrol', 'mfa_verify_failure')) NOT VALID;

COMMENT ON COLUMN app.security_event.kind IS 'login_failure, lockout, refresh_reuse, device_proof_invalid, device_state_refused, scope_changed, password_change, force_logout, otp_view, mfa_enrol or mfa_verify_failure.';
