-- V0089 per-module read/write grants of ADMIN users (owner directive 2026-10-22, docs/34 s1, DECISIONS D-ROLES-02).
-- backend-authz's request, migration B, as written (docs/requests/db-backend-authz-roles.md): the module list is the
-- contract's AdminModule (15 fixed codes); access r = read, w = read and write; carried in the web token as the
-- `modules` claim, changed only through the admin grants API (audited; it bumps app_user.scope_version). granted_by is
-- the SUPERADMIN who granted, null for this migration's seed. Today's ADMIN users keep everything: w on every module,
-- seeded only into an empty table. A grant is revoked by DELETE.

SET lock_timeout = '5s';

CREATE TABLE app.user_module_grant (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  module      text   NOT NULL CHECK (module IN ('masterdata','routes','outlets','products','prices','users','targets',
                                                'credentials','mdm','reports','config','data_entry','indent',
                                                'leaderboard','daily_tracking')),
  access      text   NOT NULL CHECK (access IN ('r','w')),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  granted_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (user_id, module)
);
CREATE INDEX user_module_grant_granted_by ON app.user_module_grant (granted_by);

INSERT INTO app.user_module_grant (user_id, module, access)
SELECT u.id, m.module, 'w'
  FROM app.app_user u
 CROSS JOIN (VALUES ('masterdata'),('routes'),('outlets'),('products'),('prices'),('users'),('targets'),('credentials'),
                    ('mdm'),('reports'),('config'),('data_entry'),('indent'),('leaderboard'),('daily_tracking')) AS m(module)
 WHERE u.role = 'ADMIN' AND NOT EXISTS (SELECT 1 FROM app.user_module_grant)
ON CONFLICT (user_id, module) DO NOTHING;

-- api_rw reads, inserts and updates through its app '*' rows and revokes by DELETE; auth_rw builds the claim.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'user_module_grant', 'DELETE', 'V0089: a revoked module grant is deleted (history in audit_log)'),
  ('auth_rw', 'app', 'user_module_grant', 'SELECT', 'V0089: token claim modules');

COMMENT ON TABLE app.user_module_grant IS 'Per-module access of an ADMIN user (D-ROLES-02, docs/34 s1): r = read, w = read and write. Carried in the web token as the modules claim; changed only through the admin grants API, which audits and bumps app_user.scope_version.
owner: backend:authz | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.user_module_grant.user_id IS 'ADMIN user holding the grant (app.app_user).';
COMMENT ON COLUMN app.user_module_grant.module IS 'Admin-portal module (contract AdminModule): masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, data_entry, indent, leaderboard or daily_tracking.';
COMMENT ON COLUMN app.user_module_grant.access IS 'r = read only, w = read and write.';
COMMENT ON COLUMN app.user_module_grant.granted_at IS 'UTC instant the grant was set or last changed.';
COMMENT ON COLUMN app.user_module_grant.granted_by IS 'SUPERADMIN who set the grant; null for the V0089 seed of existing admins.';

SELECT app.apply_db_role_grants();
