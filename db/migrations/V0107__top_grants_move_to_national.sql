-- V0107 TOP's admin permissions move to NATIONAL (backend-authz checker N3, relayed by the lead 2026-10-22): V0087
-- copied TOP's app.role_grant_map rows to NATIONAL; since no user may hold TOP (V0105/V0106), its rows are now dead
-- grants and are removed. Copy first (a row added for TOP after V0087 is not lost), then delete. Idempotent: a second
-- run copies nothing and deletes nothing. TOP stays in role_def (audit_log.actor_role and history name it).

SET lock_timeout = '5s';

INSERT INTO app.role_grant_map (role, permission)
SELECT 'NATIONAL', permission FROM app.role_grant_map WHERE role = 'TOP'
ON CONFLICT DO NOTHING;

DELETE FROM app.role_grant_map WHERE role = 'TOP';
