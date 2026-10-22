-- V0087 role codes NATIONAL and BRAND; TOP retired and its users migrated (owner directive 2026-10-22, docs/34 s1,
-- DECISIONS D-ROLES-02). The statements are backend-authz's request, migration A, as written
-- (docs/requests/db-backend-authz-roles.md on lane/backend-authz e449edb7), with the two new CHECKs added NOT VALID and
-- validated in V0088 (squawk). backend-authz's API treats TOP exactly as NATIONAL, so this lands with or after its head.
-- A1. NATIONAL (ordinal 11) and BRAND (12), web-only. TOP stays in the CHECK and in role_def because audit_log.actor_role
--     and historical rows name it; after A4 no user holds it.
-- A2. NATIONAL inherits every open TOP role-scoped config value; BRAND gets the two V0006 web-role defaults (password
--     length, access TTL) at TOP's values. One config version by aron.system, only when there is something to copy.
-- A2b. A config value in effect now of any `*_roles` key whose list names TOP and not NATIONAL (cfg.auth.mfa_required_roles
--     above all, so MFA never loosens) is closed at now and replaced by the same list plus NATIONAL until the old end.
-- A3. cfg.web.menu_by_role default: NATIONAL = TOP's entry; BRAND = dashboard (read, filter) and reports (read, export).
-- A4. TOP's admin permissions (role_grant_map) go to NATIONAL; TOP users become NATIONAL with scope_version + 1, so a TOP
--     token in flight answers 401 ERR_SCOPE_CHANGED and the refresh mints a NATIONAL token.
-- A5. CHECK app_user_no_top: no user may hold TOP again.

SET lock_timeout = '5s';

-- A1
ALTER TABLE app.role_def DROP CONSTRAINT role_def_role_check;
ALTER TABLE app.role_def ADD CONSTRAINT role_def_role_check
  CHECK (role IN ('SR','AMO','TSO','DMO','WM','TOP','NATIONAL','BRAND','ANALYST','SUPPORT','ADMIN','SUPERADMIN')) NOT VALID;
INSERT INTO app.role_def (role, ordinal, field_role) VALUES ('NATIONAL', 11, false), ('BRAND', 12, false)
  ON CONFLICT (role) DO NOTHING;

-- A2
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

-- A2b
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

-- A3
UPDATE app.cfg_key
   SET default_value = default_value || jsonb_build_object('NATIONAL', default_value -> 'TOP')
 WHERE key = 'cfg.web.menu_by_role' AND default_value ? 'TOP' AND NOT default_value ? 'NATIONAL';
UPDATE app.cfg_key
   SET default_value = default_value || '{"BRAND": [{"menu": "dashboard", "page": "dashboard", "actions": ["read", "filter"]}, {"menu": "reports", "page": "reports", "actions": ["read", "export"]}]}'::jsonb
 WHERE key = 'cfg.web.menu_by_role' AND NOT default_value ? 'BRAND';

-- A4
INSERT INTO app.role_grant_map (role, permission)
SELECT 'NATIONAL', permission FROM app.role_grant_map WHERE role = 'TOP'
ON CONFLICT DO NOTHING;
UPDATE app.app_user SET role = 'NATIONAL', scope_version = scope_version + 1 WHERE role = 'TOP';

-- A5
ALTER TABLE app.app_user ADD CONSTRAINT app_user_no_top CHECK (role <> 'TOP') NOT VALID;

COMMENT ON COLUMN app.role_def.role IS 'User role code (contract Role); TOP is retired (V0087: no user may hold it, kept for audit_log.actor_role and history), NATIONAL and BRAND are web-only portal levels (docs/34 s1).';
