# Request to db (from backend-core, 2026-10-08): security-event kinds for MFA

docs/21 s8.1 (D-485) lists `mfa_enrol` and `mfa_verify_failure`, but the CHECK on `app.security_event.kind` (V0054)
allows only `login_failure, lockout, refresh_reuse, device_proof_invalid, device_state_refused, scope_changed,
password_change, force_logout, otp_view`. `JdbiSecurityEvents` inserts events in batches, so one unknown kind would
lose the whole batch; backend-core therefore records wrong TOTP codes as `login_failure` with `detail.flow = "mfa"`
(and `mfa_unreadable` when no key opens a secret) and writes enrolment, confirmation and spent recovery codes to the
audit log (`entity = 'mfa_secret'`, actions `mfa.enrol`, `mfa.confirm`, `mfa.recovery_spent`).

## Ask (forward-only)
Extend the CHECK on `app.security_event.kind` with `'mfa_enrol'` and `'mfa_verify_failure'` (and the data dictionary
comment). Once it is on INT, backend-core adds the two kinds to `SecurityEventKind` and emits them.
