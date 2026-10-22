-- V0089 per-module read/write grants of Admin users (owner directive 2026-10-22, docs/34 s1, DECISIONS D-ROLES-02).
-- app.portal_module lists the admin-portal modules a grant can name (docs/34 s1 list; a new module is a migration on
-- backend-authz's request). app.user_module_grant holds one row per ADMIN user and module, access 'r' or 'w'; the token
-- claim `modules` ({module: "r"|"w"}) is built from it at login and refresh (backend-authz). Only ADMIN users hold grants
-- (SUPERADMIN has everything, every other role has none): enforced on insert and update by trigger. A grant is revoked by
-- deleting its row; every change is written to the audit chain by the API (AuditLog.write), so the table keeps only the
-- current grants. Re-roling a user away from ADMIN does not delete the rows: the claim is only built for ADMIN, and the
-- API deletes them in the same transaction as the re-role.

SET lock_timeout = '5s';

CREATE TABLE app.portal_module (
  code      text PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9_]{1,30}$'),
  label_en  text NOT NULL CHECK (length(label_en) BETWEEN 2 AND 60),
  label_bn  text CHECK (length(label_bn) <= 60),
  sort      smallint NOT NULL UNIQUE
);
INSERT INTO app.portal_module (code, label_en, label_bn, sort) VALUES
  ('masterdata',  'Master data',  'মাস্টার ডেটা',  1),
  ('routes',      'Routes',       'রুট',          2),
  ('outlets',     'Outlets',      'আউটলেট',       3),
  ('products',    'Products',     'পণ্য',          4),
  ('prices',      'Prices',       'মূল্য',          5),
  ('users',       'Users',        'ব্যবহারকারী',     6),
  ('targets',     'Targets',      'টার্গেট',        7),
  ('credentials', 'Credentials',  'ক্রেডেনশিয়াল',    8),
  ('mdm',         'MDM',          'এমডিএম',        9),
  ('reports',     'Reports',      'রিপোর্ট',        10),
  ('config',      'Configuration','কনফিগারেশন',    11);

CREATE TABLE app.user_module_grant (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  module      text NOT NULL REFERENCES app.portal_module(code),
  access      text NOT NULL CHECK (access IN ('r','w')),
  granted_by  bigint NOT NULL REFERENCES app.app_user(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, module)
);
CREATE INDEX user_module_grant_module ON app.user_module_grant (module);
CREATE INDEX user_module_grant_granted_by ON app.user_module_grant (granted_by);

CREATE FUNCTION app.user_module_grant_admin_only() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.app_user WHERE id = NEW.user_id AND role = 'ADMIN') THEN
    RAISE EXCEPTION 'user %: module grants are for ADMIN users only', NEW.user_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER user_module_grant_admin BEFORE INSERT OR UPDATE OF user_id ON app.user_module_grant
  FOR EACH ROW EXECUTE FUNCTION app.user_module_grant_admin_only();

-- api_rw reads, inserts and updates through its app '*' rows; it revokes by DELETE. auth_rw builds the claim.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'user_module_grant', 'DELETE', 'V0089: a revoked module grant is deleted (history in audit_log)');

COMMENT ON TABLE app.portal_module IS 'Admin-portal modules an ADMIN user can be granted read or write on (docs/34 s1).
owner: backend:authz | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN app.portal_module.code IS 'Module code used in the token claim modules and in the API''s 403 check.';
COMMENT ON COLUMN app.portal_module.label_en IS 'English label of the module in the admin portal.';
COMMENT ON COLUMN app.portal_module.label_bn IS 'Bangla label of the module.';
COMMENT ON COLUMN app.portal_module.sort IS 'Display order of the module.';
COMMENT ON TABLE app.user_module_grant IS 'Current per-module access of an ADMIN user, read (r) or write (w); revoked by delete, history in the audit log (docs/34 s1).
owner: backend:authz | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.user_module_grant.user_id IS 'ADMIN user holding the grant (app.app_user).';
COMMENT ON COLUMN app.user_module_grant.module IS 'Granted module (app.portal_module).';
COMMENT ON COLUMN app.user_module_grant.access IS 'r = read only, w = read and write.';
COMMENT ON COLUMN app.user_module_grant.granted_by IS 'SUPERADMIN who set the grant, from the token.';
COMMENT ON COLUMN app.user_module_grant.granted_at IS 'UTC instant the grant was set or last changed.';

-- auth_rw has no '*' row: name the two tables it reads for the claim.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('auth_rw', 'app', 'user_module_grant', 'SELECT', 'V0089: token claim modules'),
  ('auth_rw', 'app', 'portal_module', 'SELECT', 'V0089: token claim modules');

SELECT app.apply_db_role_grants();
