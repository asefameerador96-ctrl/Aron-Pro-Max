-- V0029 grant gaps of api_rw found before the apps switch to their own logins (docs/requests/db-runtime-roles-gaps.md).
-- Least privilege: each grant matches a statement the API runs today; the guard triggers still limit what changes.
--   DELETE app.route_planned  AdminRoutes: a visit-days change replaces the not-yet-effective planned rows
--                             (valid_from >= the new effective date); rows already in force are closed, never deleted.
--   DELETE app.user_scope     AdminUsers: a scope edit removes scope rows that never took effect (valid_from on or after
--                             the edit date); rows in force are closed with valid_to. The audit log keeps the before state.
--   DELETE app.mfa_secret     AdminUsers: an admin MFA reset destroys the secret (a credential is removed, not
--                             tombstoned; the reset is in the audit log).
--   UPDATE app.geo_fix,       DataVoidApi: a data void tombstones every synced table it covers (voided_at only;
--          app.stock_movement guard_synced_row refuses every other column); these two append-only trails were the ones
--                             left out of api_rw's UPDATE. DbRolesTest runs the void update on every DataVoidApi
--                             table as api_rw (the list is mirrored in the test).

SET lock_timeout = '5s';

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('api_rw', 'app', 'route_planned', 'DELETE', 'visit-days change replaces not-yet-effective planned rows (AdminRoutes)'),
  ('api_rw', 'app', 'user_scope', 'DELETE', 'scope edit removes rows that never took effect (AdminUsers)'),
  ('api_rw', 'app', 'mfa_secret', 'DELETE', 'admin MFA reset destroys the secret (AdminUsers)'),
  ('api_rw', 'app', 'geo_fix', 'UPDATE', 'data void tombstone: voided_at only, guard trigger (DataVoidApi)'),
  ('api_rw', 'app', 'stock_movement', 'UPDATE', 'data void tombstone: voided_at only, guard trigger (DataVoidApi)');

SELECT app.apply_db_role_grants();
