# Rotating the MFA key (aron-mfa-key)

**What it is:** Key Vault secret `aron-mfa-key`, 32 random bytes in base64, mapped to the API only as `ARON_MFA_KEY`.
It seals the TOTP secrets of web admins (ADMIN, SUPERADMIN, SUPPORT; F-WEB-043, `app.mfa_secret.secret_cipher`).
It is independent of the JWT signing key, so a signing-key rotation never locks an admin out.
**Created by:** `infra/scripts/seed-secrets.sh` on the first deploy that finds it missing. After that no deploy
replaces it. A Key Vault read error stops the deploy; only a definite `SecretNotFound` creates a new key.
**Trigger for a rotation:** the key may have leaked, or a scheduled rotation in the final account. Not routine on dev.
**Who:** the infra lane, as an infra commit promoted to the integration branch. The owner only approves it.
Never set the secret by hand and never print it.
**Time:** not drilled (design figure: two deploys, about 30 minutes, plus the re-enrolment window).

## The key ring the API reads (backend `mfaKeyRing`)

In order: `ARON_MFA_KEY`, then `ARON_MFA_KEY_PREVIOUS` if it is set, then a key derived from the JWT signing key.
New secrets are sealed with the first key. An existing secret opens with any key in the ring. The API **refuses to
start** if a key that is set is not base64 of at least 32 bytes, and that includes a blank or placeholder value.
That is why `ARON_MFA_KEY_PREVIOUS` is not wired today: it is added only for a rotation.

Admins enrolled before `aron-mfa-key` existed (sealed with the derived key) stay readable as long as the JWT signing
key is not rotated. To move them onto the dedicated key, they re-enrol once (optional on dev).

## Steps (one infra commit, then one more after the window)

1. **Copy the current key to the previous slot.** Add a one-shot step to `seed-secrets.sh` that copies the value of
   `aron-mfa-key` to `aron-mfa-key-previous` through a temp file (never echoed), then writes a new
   `openssl rand -base64 32` value to `aron-mfa-key`. Guard the step with a marker such as a secret tag
   `rotated=<date>` so it runs once.
2. **Wire the previous key.** In `infra/apps.bicep`, add `kvSecret('mfa-key-previous', ...)` to the API's secrets and
   `{ name: 'ARON_MFA_KEY_PREVIOUS', secretRef: 'mfa-key-previous' }` to `apiOnlySecretRefs`. Also add
   `mfaKeyPrevious: 'aron-mfa-key-previous'` to `secretNames`. Extend the `check_infra` tests.
3. **Deploy** (normal promotion). Check: the health gate passes and an admin logs in with their existing TOTP code.
   New enrolments are now sealed with the new key.
4. **Re-enrolment window.** Each admin re-enrols MFA, which re-seals their secret with the new key.
5. **Remove the previous key** (second infra commit): take out the step 2 wiring and the one-shot step 1. Then delete
   `aron-mfa-key-previous` from Key Vault with a scripted step. An admin who has not re-enrolled by then must
   re-enrol.

## Rolling back

The Container Apps secret reference reads the **latest** version of `aron-mfa-key`, so reverting the commit does not
bring the old key back. If the API does not start after step 3, look for a key that failed validation in the
revision's console log, then fix forward. Keep `ARON_MFA_KEY_PREVIOUS` wired until every admin has re-enrolled:
removing it locks out each admin who has not.
