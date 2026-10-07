# Request to db (from backend-core, 2026-10-07): `app.security_event` (AUD-SEC-03)

The backend now records security events through a port (`platform/SecurityEvents.kt`); today its only sink is one structured `aron.security` log line per event. To make the trail durable (docs/21 s8.1, retention with audit, 7 years), the backend needs a table.

## Ask
A forward migration adding `app.security_event`, append-only with the same grants and deny-mutation pattern as `app.audit_log`:
- `id bigint identity`, `at timestamptz not null`, `kind text not null` CHECK in (`login_failure`, `lockout`, `refresh_reuse`, `device_proof_invalid`, `device_state_refused`, `scope_changed`, `password_change`, `force_logout`, `otp_view`), `user_id bigint null` (no FK: failures name accounts that may not exist), `device_uuid uuid null`, `request_id uuid null`, `detail jsonb not null default '{}'` (short facts: route, code, `username_hash`, `ip_class`, `failures`, `family`; never a password, token or OTP).
- Index `(kind, at)` for the alerts; `(user_id, at)` for support.
- `login_failure` is already deduplicated per username hash, device and minute by the writer; no unique constraint needed.

When it lands the backend adds a JDBI sink (best effort, off the request path: a bounded queue drained by one thread) beside the log sink. Infra's N-062 alerts (refresh_reuse > 0, login_failure spikes) can match the log line now.
