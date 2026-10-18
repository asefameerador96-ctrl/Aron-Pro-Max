-- V0086 PII reveal (docs/21 s4.5, lead D-AUTHZ-QA-Q3, DECISIONS D-AUTHZ-PII; number D-MIG-ASSIGN-02).
-- cfg.pii.reauth_min: minutes a POST /v1/auth/reauth step-up keeps personal columns unmasked for a web session.
-- 15 (docs/21 s4.5 "a login within 15 minutes"), 1..15 (the web access token lives 15 minutes and a refresh mints a
-- token without the reveal), scope global, editor security, class C2, restrictive downwards, server only.
-- pii.reveal: the role grant the reveal needs, read per request from app.role_grant_map like the Q5 grants; seeded to
-- ADMIN and SUPERADMIN only (ANALYST "no PII unless granted", docs/24 s8.4). Deleting a row revokes it within the gate
-- cache (10 s), the open window included.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.pii.reauth_min', 'pii', 'S', 'int', '15'::jsonb, '{"min": 1, "max": 15}'::jsonb, NULL, ARRAY['global']::text[], 2, NULL, 'S', 'server', false, false, 'down', 'cfg.edit.security',
   'Minutes a password (and TOTP) step-up keeps personal columns unmasked for a web session; every unmasked request is audited (docs/21 s4.5).');

INSERT INTO app.role_grant_map (role, permission) VALUES
  ('ADMIN', 'pii.reveal'),
  ('SUPERADMIN', 'pii.reveal')
ON CONFLICT (role, permission) DO NOTHING;
