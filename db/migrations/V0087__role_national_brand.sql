-- V0087 role codes NATIONAL and BRAND (owner directive 2026-10-22, docs/34 s1, DECISIONS D-ROLES-02). Expand step only:
-- 1. app.role_def gains NATIONAL (national read: dashboard, reports, exports) and BRAND (own brands only, read; the
--    brands come from app.user_brand_scope, V0089). Both are web-only roles (field_role false), ordinals 11 and 12.
--    The role CHECK is replaced NOT VALID and validated in V0088.
-- 2. The web-role defaults of docs/24 s9.5 that V0006 wrote for every non-field role (password length 12,
--    access-token lifetime 15 min) are written for the two new roles in their own config version.
-- 3. cfg.web.menu_by_role default: NATIONAL gets TOP's read-only menu, BRAND the dashboard, the report pages (not the
--    geo capture report, which has no brand dimension) and its own password. The menu only hides entries; the server
--    enforces every action (backend-authz builds the sidebar from the role and the module grants, docs/34 s1).
-- TOP is NOT retired here and no user is re-roled: the contract Role enum, the token and the backend still name TOP.
-- Moving TOP users to NATIONAL and dropping TOP is the contract step, a later migration on backend-authz's request.

SET lock_timeout = '5s';

ALTER TABLE app.role_def DROP CONSTRAINT role_def_role_check;
ALTER TABLE app.role_def ADD CONSTRAINT role_def_role_check
  CHECK (role IN ('SR','AMO','TSO','DMO','WM','TOP','ANALYST','SUPPORT','ADMIN','SUPERADMIN','NATIONAL','BRAND')) NOT VALID;

INSERT INTO app.role_def (role, ordinal, field_role) VALUES ('NATIONAL', 11, false), ('BRAND', 12, false);

INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
SELECT (SELECT coalesce(max(config_version), 0) + 1 FROM app.cfg_version), 'change', id,
       'V0087: web-role defaults of docs/24 s9.5 for the new roles NATIONAL and BRAND', 2
  FROM app.app_user WHERE username = 'aron.system';
INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason)
SELECT k.key, 'role', r.ordinal, k.value, '2026-01-01T00:00:00Z', (SELECT max(config_version) FROM app.cfg_version), u.id,
       'docs/24 s9.5 default for web roles (V0087)'
  FROM (VALUES ('cfg.auth.password_min_len', '12'::jsonb), ('cfg.auth.access_ttl_min', '15'::jsonb)) AS k(key, value)
 CROSS JOIN app.role_def r
 CROSS JOIN app.app_user u
 WHERE r.role IN ('NATIONAL', 'BRAND') AND u.username = 'aron.system';

UPDATE app.cfg_key
   SET default_value = default_value
         || jsonb_build_object('NATIONAL', default_value -> 'TOP')
         || jsonb_build_object('BRAND', (
              SELECT jsonb_agg(e ORDER BY o)
                FROM jsonb_array_elements(default_value -> 'TOP') WITH ORDINALITY AS t(e, o)
               WHERE e ->> 'menu' IN ('dashboard', 'reports', 'credentials') AND e ->> 'page' <> 'by_route_geo_capture')),
       updated_at = now()
 WHERE key = 'cfg.web.menu_by_role';

COMMENT ON COLUMN app.role_def.role IS 'User role code: the contract Role values plus NATIONAL and BRAND (V0087, docs/34 s1; TOP retires once backend-authz moves its users).';
