# Request to web-dashboard (from backend-core, 2026-10-08): TOTP enrolment screen

`POST /v1/auth/mfa/enrol` and `POST /v1/auth/mfa/verify` are served (BC-89). An ADMIN, SUPERADMIN or SUPPORT user who
has never enrolled gets `mfa_required` at login like an enrolled one, and has no authenticator code yet.

## Ask
- On the MFA step add "Set up authenticator": a BFF route `POST /api/bff/mfa/enrol` that calls
  `POST /v1/auth/mfa/enrol` with `Authorization: Bearer <mfa_token>` from the MFA cookie. 200 gives
  `otpauth_uri` (render as a QR code, plus the secret text for manual entry) and ten `recovery_codes` (show once, with
  copy/print; never store them). 409 means the user is already enrolled: show the normal code form. 403 means the role
  needs no MFA.
- The user then types the 6-digit code into the existing verify form; the first good code confirms the enrolment and
  completes the login.
- Send `Cache-Control: no-store` on the BFF response; never log the URI or the codes.
- `Me.mfa_enabled` is now real (true once confirmed).
