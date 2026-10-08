# Rotating the MFA key (aron-mfa-key)

**What it is:** Key Vault secret `aron-mfa-key`, 32 random bytes in base64, mapped to the API only as `ARON_MFA_KEY`.
It seals the TOTP secrets of web admins (ADMIN, SUPERADMIN, SUPPORT; F-WEB-043, `app.mfa_secret.secret_cipher`).
It is independent of the JWT signing key, so a signing-key rotation never locks an admin out.
**Created by:** `infra/scripts/seed-secrets.sh` on the first deploy that finds it missing. After that no deploy
replaces it. A Key Vault read error stops the deploy; only a definite `SecretNotFound` creates a new key.
**Trigger for a rotation:** the key may have leaked, or a scheduled rotation in the final account. Not routine on dev.
**Who:** the infra lane, as an infra commit promoted to the integration branch. The owner only approves it.
Never set the secret by hand and never print it.
**Time:** not drilled (design figure: three deploys, about 45 minutes, plus the re-enrolment window).

## The key ring the API reads (backend `mfaKeyRing`)

In order: `ARON_MFA_KEY`, then `ARON_MFA_KEY_PREVIOUS` if it is set, then a key derived from the JWT signing key.
New secrets are sealed with the first key. An existing secret opens with any key in the ring. The API **refuses to
start** if a key that is set is not base64 of at least 32 bytes, and that includes a blank or placeholder value.
That is why `ARON_MFA_KEY_PREVIOUS` is not wired today: it is added only for a rotation.

Admins enrolled before `aron-mfa-key` existed (sealed with the derived key) stay readable as long as the JWT signing
key is not rotated. To move them onto the dedicated key, they re-enrol once (optional on dev).

## Steps (three infra commits, each a normal promotion and deploy)

The key is never swapped in the same deploy that wires the previous key. `seed-secrets.sh` runs before the
migrations and the apps stage, and the Key Vault reference has no version. If the deploy stopped after a swap, a
replica that restarts would load the new key with no previous key wired, and every admin would be locked out.

1. **Deploy A: wire the previous key (no change in behaviour).** Add a one-shot step to `seed-secrets.sh` that
   copies the current `aron-mfa-key` value to `aron-mfa-key-previous` through a temp file (never echoed). In
   `infra/apps.bicep`, add `kvSecret('mfa-key-previous', ...)` to the API's secrets and
   `{ name: 'ARON_MFA_KEY_PREVIOUS', secretRef: 'mfa-key-previous' }` to `apiOnlySecretRefs`, plus
   `mfaKeyPrevious: 'aron-mfa-key-previous'` in `secretNames`. Extend the `check_infra` tests. Both slots now hold
   the same key. Check: the health gate passes and an admin logs in with their existing TOTP code.
2. **Deploy B: swap the key.** Only after deploy A is green, add a one-shot step that writes a new
   `openssl rand -base64 32` value to `aron-mfa-key`. Guard it with a secret tag `rotated=<date>` so it runs
   once. A replica that restarts at any point now has the old key as previous. Check as in step 1. New enrolments
   are sealed with the new key.
3. **Re-enrolment window.** Each admin re-enrols MFA, which re-seals their secret with the new key.
4. **Deploy C: remove the previous key.** Remove the deploy A wiring and both one-shot steps. Then delete
   `aron-mfa-key-previous` with a scripted step. Key Vault soft delete keeps it for 90 days, and prod has purge
   protection. So the next rotation's deploy A must run `az keyvault secret recover` for it before writing; on dev
   it may purge instead. An admin who has not re-enrolled by then must re-enrol.

If `aron-mfa-key` itself is ever soft-deleted, the deploy stops at `secret set` ("deleted but recoverable") and
replaces nothing. Recover it (`az keyvault secret recover --name aron-mfa-key`) with a scripted step rather than
letting a new key be created.

## Rolling back

The Container Apps secret reference reads the **latest** version of `aron-mfa-key`, so reverting a commit does not
bring the old key back. Never revert deploy A's wiring once deploy B has run: that removes `ARON_MFA_KEY_PREVIOUS`
and locks out every admin who has not re-enrolled. If the API does not start, look for a key that failed
validation in the revision's console log, then fix forward.
