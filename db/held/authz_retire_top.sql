-- HELD (not a migration yet): the rest of backend-authz's migration A (docs/requests/db-backend-authz-roles.md e449edb7),
-- held out of V0087 on 2026-10-22 because INT's backend tests still create TOP users (ScopeLeakHarness, ScopeDbTest,
-- FSys005ReviewTest, ...). Ship as the next free V#### pair (this file, then a _validate migration) when backend-authz
-- confirms its tests no longer insert TOP. Until then TOP users keep working: the API treats TOP exactly as NATIONAL.

SET lock_timeout = '5s';

-- A4 (second half): TOP users become NATIONAL; scope_version + 1 so a TOP token in flight answers 401 ERR_SCOPE_CHANGED.
UPDATE app.app_user SET role = 'NATIONAL', scope_version = scope_version + 1 WHERE role = 'TOP';

-- A5: no user may hold TOP again (validated in the following migration).
ALTER TABLE app.app_user ADD CONSTRAINT app_user_no_top CHECK (role <> 'TOP') NOT VALID;
-- next migration: ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_no_top;
