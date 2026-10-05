# Request: what a web `password_change_required` login returns (lane web-dashboard, 2026-10-05)

**Need.** `POST /v1/auth/login` can answer `status: password_change_required` (temporary password, docs/24 s8.1). The web
needs to let the user set a new password with `POST /v1/auth/change-password`, which is an authenticated call, but the
contract does not say which token the login response carries in that state (`access_token` is `null` for the other
non-ok statuses).

**Ask.** State in the `LoginResponse` description, for `client: web`, either (a) `access_token` is set with a restricted
audience that is accepted only by `change-password`, or (b) a dedicated `password_change_token` (like `mfa_token`).
Also: should the TOTP step come before the password change for MFA roles?

**Local stub.** The web login shows the localised message "your password must be changed; ask support to reset it"
(`auth.password_change_required`) and creates no session. No password-change form is built until this is answered.
