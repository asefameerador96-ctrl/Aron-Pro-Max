-- PENDING (db lane, docs/requests/db-backend-authz-roles.md migrations A1, B and C): applied by UserAccessTest only while the tables are absent.
-- Delete this file and the apply call in UserAccessTest in the push that picks db's migrations up.
ALTER TABLE app.role_def DROP CONSTRAINT IF EXISTS role_def_role_check;
ALTER TABLE app.role_def ADD CONSTRAINT role_def_role_check
  CHECK (role IN ('SR','AMO','TSO','DMO','WM','TOP','NATIONAL','BRAND','ANALYST','SUPPORT','ADMIN','SUPERADMIN'));
INSERT INTO app.role_def (role, ordinal, field_role) VALUES ('NATIONAL', 11, false), ('BRAND', 12, false) ON CONFLICT (role) DO NOTHING;
CREATE TABLE IF NOT EXISTS app.user_module_grant (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  module      text   NOT NULL CHECK (module IN ('masterdata','routes','outlets','products','prices','users','targets',
                                                'credentials','mdm','reports','config','data_entry','indent',
                                                'leaderboard','daily_tracking')),
  access      text   NOT NULL CHECK (access IN ('r','w')),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  granted_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (user_id, module)
);
CREATE TABLE IF NOT EXISTS app.user_brand_scope (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  brand_id    bigint NOT NULL REFERENCES app.product_node(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  granted_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (user_id, brand_id)
);
