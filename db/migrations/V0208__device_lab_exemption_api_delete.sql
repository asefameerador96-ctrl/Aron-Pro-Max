-- V0208 android-integrity slice 3B the API role may DELETE a lab exemption.
-- Withdrawing a lab exemption (DELETE /v1/admin/devices/{id}/lab-exemption) deletes the row; api_rw only holds SELECT, INSERT, UPDATE by wildcard, so once the
-- runtime-role switch is on the withdrawal would fail with "permission denied". Plain data insert, no guard that can raise.
SET lock_timeout = '5s';

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, note) VALUES
  ('api_rw', 'app', 'device_lab_exemption', 'DELETE', '{}', 'the portal withdraws a lab exemption (V0208)');

SELECT app.apply_db_role_grants();
