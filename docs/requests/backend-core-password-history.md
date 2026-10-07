# Request to db (from backend-core, 2026-10-07): password history table and the policy keys

Rows F-API-004 and F-SYS-004 (POST /v1/auth/change-password) must refuse "any of the last 10 passwords"
(docs/21 s4, docs/09 Credentials). There is no table for earlier hashes yet. Until there is one, change-password refuses
only the current password. The code and its acceptance test are already in place:
`backend/auth/.../Passwords.kt` (`JdbiPasswordStore`) and `ChangePasswordTest.noneOfTheLastTenPasswords`. The test is
skipped by an assumption until the table exists.

## Ask 1: table (forward-only migration)
```sql
CREATE TABLE app.password_history (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  password_hash text NOT NULL CHECK (length(password_hash) <= 255),   -- Argon2id PHC string of a replaced password
  changed_at    timestamptz NOT NULL                                  -- when it was replaced
);
CREATE INDEX ON app.password_history (user_id, changed_at DESC);
-- append-only like the other trails (app.deny_mutation); old rows may be pruned by a job beyond the depth
```
Ingest writes the old hash in the same transaction that sets `app_user.password_hash`, and reads the newest 9 rows,
plus the current hash, for the "last 10" check.

## Ask 2: cfg keys (registry, s9.5; docs/21 s4 names them)
- `cfg.auth.password_history_depth`: int, default 10, range 1 to 24, server only.
- `cfg.auth.password_min_age_h`: int, default 24, range 0 to 168, server only.
- `cfg.auth.password_denylist_enabled`: bool, default true, ROLE scope, server only.

The code uses these defaults until the keys exist. `password_denylist_enabled` is already read when present.

## Ask 3 (optional, reference data)
The top-10,000 common-password list for the field-role deny-list (docs/21 s4) as reference data. Until it lands, a
short built-in list is used.
