# Request to the lead (from backend-core, 2026-10-07): the change-password and bind-device responses (contract 1.2.1 or v1.3, description only)

Found by the Opus checker of F-API-004. `changePassword` declares 200, 204, 400, 401 and 429. The server can also answer:
- `403 ERR_AUTH_ACCOUNT_LOCKED`, with Retry-After: too many wrong `current_password` (the same lockout counts as login);
- `403 ERR_AUTH_USER_DISABLED`;
- `409 ERR_CONFLICT`: two changes at the same time, where the second loses;
- `503 ERR_SERVICE_UNAVAILABLE`: the hash pool is busy (`HashLimiter`), the same as login.

Asked: add the `Forbidden`, `Conflict` and `ServiceUnavailable` responses to `changePassword`. Also document, for the
web BFF, that a call carrying the BFF's own `aron_rt` (in the `Cookie` header) keeps that session's family. Every other
full-grant family of the user is revoked; upload grants survive.

`login` already declares 403. Since 2026-10-07 it is also used when a phone client is used with a role it does not serve:
only SR on app_sr, AMO on app_amo and TSO on app_tso (`ERR_FORBIDDEN`). This closes a way for MFA roles to skip TOTP.
