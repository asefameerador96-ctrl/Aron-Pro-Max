# Request: `amr` on `app.refresh_family` (backend-core → db, 2026-10-08)

**Why (BC-89):** a refresh must keep how the login authenticated. Today every refreshed access token says
`amr` ["pwd"], even for a web session that passed TOTP, so a later step-up check that reads `amr` would see an MFA
session as password-only after its first refresh (15 min).

**Ask:** one forward-only migration:

```sql
ALTER TABLE app.refresh_family ADD COLUMN amr text[] NOT NULL DEFAULT '{pwd}'
  CHECK (amr <@ '{pwd,mfa}'::text[] AND 'pwd' = ANY (amr));
COMMENT ON COLUMN app.refresh_family.amr IS 'Authentication methods of the login that opened the family (RFC 8176 amr: pwd, mfa); every refresh of the family carries the same.';
```

(The default makes it safe on a table with rows; the CHECK can be NOT VALID + VALIDATE if your convention wants it.)

**Backend side (ready, lane/backend-core):** `JdbiRefreshStore` writes and reads the column when it exists (checked once
per process) and falls back to ["pwd"] until then; `MfaTest` adds the column in its own test database and proves a
refreshed web MFA session keeps ["pwd","mfa"]. Nothing else changes when the migration lands.

**Rollout note (lead accepted, BC-97):** once the column exists, every family created before it reads `{pwd}`, so
admins (MFA roles) are signed out at their next refresh (15 min) and sign in again once with TOTP. Please mention this
in the migration's note.
