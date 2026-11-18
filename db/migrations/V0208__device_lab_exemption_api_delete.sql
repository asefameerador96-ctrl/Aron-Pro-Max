-- V0208 android-integrity slice 3B (provisional number, renumbered at landing): the API role may DELETE a lab exemption.
-- Withdrawing a lab exemption (DELETE /v1/admin/devices/{id}/lab-exemption) deletes the row; api_rw only holds SELECT, INSERT, UPDATE by wildcard, so once the
-- runtime-role switch is on the withdrawal would fail with "permission denied". Plain data insert, no guard that can raise.
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, note) VALUES
  ('api_rw', 'app', 'device_lab_exemption', 'DELETE', '{}', 'the portal withdraws a lab exemption (V0204)');

SELECT app.apply_db_role_grants();
