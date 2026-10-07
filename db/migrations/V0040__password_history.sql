-- V0040 password history and the password policy keys (F-API-004, F-SYS-004; docs/21 s4, docs/09 Credentials):
-- change-password refuses any of the last cfg.auth.password_history_depth passwords. Answers
-- docs/requests/backend-core-password-history.md (asks 1 and 2; the common-password list of ask 3 is not added here).
--
-- The auth path writes the replaced hash in the same transaction that sets app_user.password_hash and may delete rows
-- beyond the depth there. Rows never change. Like the other credential tables, the worker cannot read it.

SET lock_timeout = '5s';

CREATE TABLE app.password_history (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  password_hash text NOT NULL CHECK (length(password_hash) BETWEEN 1 AND 255),
  changed_at    timestamptz NOT NULL
);
CREATE INDEX password_history_user ON app.password_history (user_id, changed_at DESC);
CREATE TRIGGER password_history_immutable BEFORE UPDATE ON app.password_history
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

COMMENT ON TABLE app.password_history IS 'One row is a password hash a user replaced, kept to refuse re-use of the last cfg.auth.password_history_depth passwords.
owner: backend:auth | capture: ONLINE | retention: master | pii: secret';
COMMENT ON COLUMN app.password_history.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.password_history.user_id IS 'User whose password was replaced.';
COMMENT ON COLUMN app.password_history.password_hash IS '[pii:secret] Argon2id PHC string of the replaced password (never the password itself).';
COMMENT ON COLUMN app.password_history.changed_at IS 'UTC time the password was replaced.';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.auth.password_history_depth', 'auth', 'S', 'int', '10'::jsonb, '{"min": 1, "max": 24}'::jsonb, NULL,
   ARRAY['global']::text[], 2, NULL, 'S', 'server', false, false, 'cfg.edit.security',
   'Number of earlier passwords a new password may not repeat (docs/21 s4).'),
  ('cfg.auth.password_min_age_h', 'auth', 'S', 'int', '24'::jsonb, '{"min": 0, "max": 168}'::jsonb, NULL,
   ARRAY['global']::text[], 2, NULL, 'S', 'server', false, false, 'cfg.edit.security',
   'Hours after a password change before the user may change it again (docs/21 s4).'),
  ('cfg.auth.password_denylist_enabled', 'auth', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL,
   ARRAY['global', 'role']::text[], 2, NULL, 'S', 'server', false, false, 'cfg.edit.security',
   'Refuse common passwords, the username and the company names as a password (docs/21 s4).');

-- Credentials stay away from the worker; the auth path inserts, reads and prunes beyond the depth.
UPDATE app.db_role_grant SET except_tables = except_tables || '{password_history}'
 WHERE role = 'worker_rw' AND schema_name = 'app' AND object = '*';
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('auth_rw', 'app', 'password_history', 'SELECT, INSERT, DELETE', 'password change: last-N check, prune beyond the depth'),
  ('api_rw', 'app', 'password_history', 'DELETE', 'password change on the API login: prune beyond the depth');

SELECT app.apply_db_role_grants();
