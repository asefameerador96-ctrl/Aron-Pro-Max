-- PENDING (db lane, docs/requests/db-backend-authz-roles.md migrations B and C (no role codes here: the permissions listing must keep matching the contract Role enum until backend-authz lands it)): applied by UserAccessTest only while the tables are absent.
-- Delete this file and the apply call in UserAccessTest in the push that picks db's migrations up.
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
