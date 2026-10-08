-- V0072 app.refresh_family.amr (BC-89; answers docs/requests/backend-core-refresh-family-amr.md). The authentication
-- methods of the login that opened the family (RFC 8176 amr: pwd, mfa); every refresh of the family carries the same,
-- so a web session that passed TOTP stays ["pwd","mfa"] after its first refresh. Existing families (all opened by a
-- password login) get the constant default ['pwd'] as a catalogue-only change; the CHECK is added NOT VALID (no scan
-- under the lock) and validated in V0073. auth_rw's table grant (V0020) covers the new column.
-- Effect on deploy (BC-97, lead accepted): every family created before this column reads {pwd}, so users of MFA roles
-- (admins) are signed out at their next refresh and sign in once more with TOTP. backend-core detects the column
-- (lane/backend-core 6bc9dd85); no code change follows.

SET lock_timeout = '5s';

ALTER TABLE app.refresh_family ADD COLUMN amr text[] NOT NULL DEFAULT '{pwd}'::text[];
ALTER TABLE app.refresh_family ADD CONSTRAINT refresh_family_amr_known
  CHECK (amr <@ '{pwd,mfa}'::text[] AND 'pwd' = ANY (amr)) NOT VALID;

COMMENT ON COLUMN app.refresh_family.amr IS 'Authentication methods of the login that opened the family (RFC 8176 amr: pwd, mfa); every refresh of the family carries the same.';
