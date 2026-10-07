# Request (backend): temporary password lifetime is 72 h, the spec says 24 h

Found by the F-TSO-023 checker: `backend/masterdata/.../AdminUsers.kt` has `TEMP_PASSWORD_TTL_H = 72L`, used for createUser and for credential resets. The row F-TSO-023, docs/19 (`cfg.auth.temp_password_ttl_h`, default 24) and docs/21 say 24 hours.

Needed: use the config value (default 24) for both. The web page shows the real `temporary_password_expires_at` from the API, so it stays correct either way; it no longer states a fixed duration.
