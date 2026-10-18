-- V0085 cfg_key.editor_permission is enforced per request (lead D-AUTHZ-QA-Q4; number D-MIG-ASSIGN-02): a config
-- change (create, adopt, and the second SUPERADMIN's approval) needs the key's editor_permission among the caller's
-- role grants in app.role_grant_map. This seed grants every edit permission the registry names to ADMIN and
-- SUPERADMIN, so no current behaviour changes; deleting a row revokes it within the request.
-- A later migration that introduces a new cfg.edit.* permission seeds its grants too (EditorPermissionTest).

SET lock_timeout = '5s';

INSERT INTO app.role_grant_map (role, permission)
SELECT r.role, k.permission
  FROM (VALUES ('ADMIN'), ('SUPERADMIN')) AS r(role)
 CROSS JOIN (SELECT DISTINCT editor_permission AS permission FROM app.cfg_key) AS k
ON CONFLICT (role, permission) DO NOTHING;
