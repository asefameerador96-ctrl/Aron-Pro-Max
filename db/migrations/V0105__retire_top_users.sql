-- V0105 TOP retired for users (owner directive 2026-10-22, docs/34 s1; backend-authz's migration A4/A5,
-- docs/requests/db-backend-authz-roles.md e449edb7). Held out of V0087 until backend-authz's tests stopped creating TOP
-- users (lane/backend-authz 8ed44597, on INT 658f65d0). TOP stays a role code (role_def and the CHECK) because
-- audit_log.actor_role and history name it; only users may no longer hold it.

SET lock_timeout = '5s';

-- A4 (second half): TOP users become NATIONAL; scope_version + 1 so a TOP token in flight answers 401 ERR_SCOPE_CHANGED.
UPDATE app.app_user SET role = 'NATIONAL', scope_version = scope_version + 1 WHERE role = 'TOP';

-- A5: no user may hold TOP again (validated in V0106).
ALTER TABLE app.app_user ADD CONSTRAINT app_user_no_top CHECK (role <> 'TOP') NOT VALID;
