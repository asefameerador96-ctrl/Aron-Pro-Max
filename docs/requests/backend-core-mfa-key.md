# Request to infra (from backend-core, 2026-10-08): Key Vault secret for the MFA key

The TOTP secrets of web admins (`app.mfa_secret.secret_cipher`, F-WEB-043) are sealed with AES-256-GCM. The key must
not be derived from the JWT signing key: a signing-key rotation would then make every sealed secret unreadable and lock
every ADMIN, SUPERADMIN and SUPPORT user out (independent checker, 2026-10-08).

## Ask
- Key Vault secret `aron-mfa-key`: 32 random bytes, base64 (`openssl rand -base64 32`, generated in the pipeline,
  never printed or stored elsewhere), mapped to the api app as `ARON_MFA_KEY` (or `ARON_MFA_KEY_FILE`).
- Rotation: move the old value to `aron-mfa-key-previous` (`ARON_MFA_KEY_PREVIOUS`) and set a new `aron-mfa-key`;
  the API opens secrets with either. Users re-enrol only when the previous key is removed.
- The api refuses to start when the value is not base64 of at least 32 bytes.

Until it is set the API uses a key derived from the signing key (the last entry of the key ring), so dev works today
and enrolments made now stay readable after `aron-mfa-key` is added; they are lost only if the signing key rotates
first. No secret value goes into git or logs.
