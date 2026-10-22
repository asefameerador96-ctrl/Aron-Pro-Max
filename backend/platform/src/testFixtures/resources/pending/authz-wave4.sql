-- PENDING (backend-authz, 2026-10-22): the exact SQL of docs/requests/db-backend-authz-roles.md (migrations A-D),
-- applied by the test fixtures only while lane db's migrations have not landed (FreshDb, PendingAuthzSchema).
-- Delete this file in the push that picks up db's migrations. Idempotent: running it twice ends in the same state.

-- A1. Role codes (contract Role gains NATIONAL and BRAND; TOP stays in the CHECK and in role_def because
--     app.audit_log.actor_role and historical rows reference it; no active user may hold it after A4).
ALTER TABLE app.role_def DROP CONSTRAINT IF EXISTS role_def_role_check;
ALTER TABLE app.role_def ADD CONSTRAINT role_def_role_check
  CHECK (role IN ('SR','AMO','TSO','DMO','WM','TOP','NATIONAL','BRAND','ANALYST','SUPPORT','ADMIN','SUPERADMIN'));
INSERT INTO app.role_def (role, ordinal, field_role) VALUES ('NATIONAL', 11, false), ('BRAND', 12, false)
  ON CONFLICT (role) DO NOTHING;

-- A2. Role-scoped config: NATIONAL inherits every open TOP row (V0006 web-role defaults and any later TOP override,
--     menus included); BRAND gets the two V0006 web-role defaults at TOP's current values (password length, access
--     TTL), not TOP's menus (BRAND's menu is built from its role; docs/34 s1). One config version, committed by
--     aron.system, only when there is something to copy (a second run copies nothing: NOT EXISTS).
DO $$
DECLARE v bigint; sys bigint;
BEGIN
  IF EXISTS (
    SELECT 1 FROM app.cfg_value t
     WHERE t.scope_type = 'role' AND t.scope_id = 6 AND t.superseded_in_version IS NULL AND (t.effective_to IS NULL OR t.effective_to > now())
       AND (NOT EXISTS (SELECT 1 FROM app.cfg_value n WHERE n.key = t.key AND n.scope_type = 'role' AND n.scope_id = 11)
            OR (t.key IN ('cfg.auth.password_min_len','cfg.auth.access_ttl_min')
                AND NOT EXISTS (SELECT 1 FROM app.cfg_value b WHERE b.key = t.key AND b.scope_type = 'role' AND b.scope_id = 12))))
  THEN
    SELECT id INTO sys FROM app.app_user WHERE username = 'aron.system';
    SELECT coalesce(max(config_version), 0) + 1 INTO v FROM app.cfg_version;
    INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
    VALUES (v, 'change', sys, 'D-ROLES-02: NATIONAL inherits TOP role values; BRAND gets the web-role defaults', 2);
    INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, created_by, reason)
    SELECT t.key, 'role', 11, t.value, t.effective_from, t.effective_to, v, sys, 'D-ROLES-02: copied from role TOP'
      FROM app.cfg_value t
     WHERE t.scope_type = 'role' AND t.scope_id = 6 AND t.superseded_in_version IS NULL AND (t.effective_to IS NULL OR t.effective_to > now())
       AND NOT EXISTS (SELECT 1 FROM app.cfg_value n WHERE n.key = t.key AND n.scope_type = 'role' AND n.scope_id = 11);
    INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, created_by, reason)
    SELECT t.key, 'role', 12, t.value, t.effective_from, t.effective_to, v, sys, 'D-ROLES-02: web-role default as role TOP'
      FROM app.cfg_value t
     WHERE t.scope_type = 'role' AND t.scope_id = 6 AND t.superseded_in_version IS NULL AND (t.effective_to IS NULL OR t.effective_to > now())
       AND t.key IN ('cfg.auth.password_min_len','cfg.auth.access_ttl_min')
       AND NOT EXISTS (SELECT 1 FROM app.cfg_value b WHERE b.key = t.key AND b.scope_type = 'role' AND b.scope_id = 12);
  END IF;
END $$;

-- A2b. Role lists in config values (`cfg.auth.mfa_required_roles`, `cfg.day.reopen_roles`, `cfg.web.entry_unlock_roles`,
--      ... every key named `*_roles`): a value in effect now that names TOP and not NATIONAL is closed at now and
--      replaced by the same list plus NATIONAL until the old row's end, so a migrated user keeps every right TOP had
--      (above all `mfa_required_roles`: dropping it would loosen MFA). A row that starts in the future cannot be closed
--      (effective_to must exceed effective_from); none exists in the test account (logged; the API refuses new TOP).
DO $$
DECLARE v bigint; sys bigint; r record;
BEGIN
  IF EXISTS (SELECT 1 FROM app.cfg_value c
              WHERE c.key LIKE '%\_roles' AND jsonb_typeof(c.value) = 'array' AND c.value ? 'TOP' AND NOT c.value ? 'NATIONAL'
                AND c.superseded_in_version IS NULL AND c.effective_from <= now() AND (c.effective_to IS NULL OR c.effective_to > now()))
  THEN
    SELECT id INTO sys FROM app.app_user WHERE username = 'aron.system';
    SELECT coalesce(max(config_version), 0) + 1 INTO v FROM app.cfg_version;
    INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
    VALUES (v, 'change', sys, 'D-ROLES-02: role lists that name TOP also name NATIONAL', 2);
    FOR r IN SELECT * FROM app.cfg_value c
              WHERE c.key LIKE '%\_roles' AND jsonb_typeof(c.value) = 'array' AND c.value ? 'TOP' AND NOT c.value ? 'NATIONAL'
                AND c.superseded_in_version IS NULL AND c.effective_from <= now() AND (c.effective_to IS NULL OR c.effective_to > now())
    LOOP
      UPDATE app.cfg_value SET effective_to = now(), superseded_in_version = v WHERE id = r.id;
      INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, created_by, reason)
      VALUES (r.key, r.scope_type, r.scope_id, r.value || '"NATIONAL"'::jsonb, now(), r.effective_to, v, sys, 'D-ROLES-02: NATIONAL added beside TOP');
    END LOOP;
  END IF;
END $$;

-- A3. Registry defaults keyed by role: cfg.web.menu_by_role gains NATIONAL = TOP's entry (read pages only; the
--     NATIONAL write ban is enforced by the API whatever a menu says) and BRAND = dashboard + reports (read).
UPDATE app.cfg_key
   SET default_value = default_value || jsonb_build_object('NATIONAL', default_value -> 'TOP')
 WHERE key = 'cfg.web.menu_by_role' AND default_value ? 'TOP' AND NOT default_value ? 'NATIONAL';
UPDATE app.cfg_key
   SET default_value = default_value || '{"BRAND": [{"menu": "dashboard", "page": "dashboard", "actions": ["read", "filter"]}, {"menu": "reports", "page": "reports", "actions": ["read", "export"]}]}'::jsonb
 WHERE key = 'cfg.web.menu_by_role' AND NOT default_value ? 'BRAND';

-- A4. Admin permissions of TOP (app.role_grant_map) go to NATIONAL; TOP users become NATIONAL. scope_version is bumped
--     so a TOP token still in flight answers 401 ERR_SCOPE_CHANGED and the refresh mints a NATIONAL token.
INSERT INTO app.role_grant_map (role, permission)
SELECT 'NATIONAL', permission FROM app.role_grant_map WHERE role = 'TOP'
ON CONFLICT DO NOTHING;
UPDATE app.app_user SET role = 'NATIONAL', scope_version = scope_version + 1 WHERE role = 'TOP';

-- A5. No user may hold TOP again (the API refuses it too). NOT VALID then VALIDATE (squawk; db may move the VALIDATE
--     to its own _validate migration as usual).
ALTER TABLE app.app_user DROP CONSTRAINT IF EXISTS app_user_no_top;
ALTER TABLE app.app_user ADD CONSTRAINT app_user_no_top CHECK (role <> 'TOP') NOT VALID;
ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_no_top;

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
COMMENT ON TABLE app.user_module_grant IS 'Per-module access of an ADMIN user (D-ROLES-02, docs/34 s1): r = read, w = read and write. Carried in the web token as the modules claim; changed only through the admin grants API, which audits and bumps app_user.scope_version.';

-- Today's admins keep everything: w on every module. Only into an empty table, so a run after a SUPERADMIN narrowed a
-- grant never puts it back.
INSERT INTO app.user_module_grant (user_id, module, access)
SELECT u.id, m.module, 'w'
  FROM app.app_user u
 CROSS JOIN (VALUES ('masterdata'),('routes'),('outlets'),('products'),('prices'),('users'),('targets'),('credentials'),
                    ('mdm'),('reports'),('config'),('data_entry'),('indent'),('leaderboard'),('daily_tracking')) AS m(module)
 WHERE u.role = 'ADMIN' AND NOT EXISTS (SELECT 1 FROM app.user_module_grant)
ON CONFLICT (user_id, module) DO NOTHING;

CREATE TABLE IF NOT EXISTS app.user_brand_scope (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  brand_id    bigint NOT NULL REFERENCES app.product_node(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  granted_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (user_id, brand_id)
);
CREATE INDEX IF NOT EXISTS user_brand_scope_brand ON app.user_brand_scope (brand_id);
COMMENT ON TABLE app.user_brand_scope IS 'Brands a BRAND user may see (D-ROLES-02, docs/34 s1); carried in the web token as the brands claim; product-based reports and dashboard cards are filtered to them on the server.';

CREATE OR REPLACE FUNCTION app.user_brand_scope_is_brand() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.product_node WHERE id = NEW.brand_id AND level = 'brand') THEN
    RAISE EXCEPTION 'product_node % is not a brand', NEW.brand_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS user_brand_scope_brand_level ON app.user_brand_scope;
CREATE TRIGGER user_brand_scope_brand_level BEFORE INSERT OR UPDATE ON app.user_brand_scope
  FOR EACH ROW EXECUTE FUNCTION app.user_brand_scope_is_brand();

ALTER TABLE app.user_scope DROP CONSTRAINT IF EXISTS user_scope_node_type_check;
ALTER TABLE app.user_scope ADD CONSTRAINT user_scope_node_type_check
  CHECK (node_type IN ('national','wing','division','territory','house','zone'));
