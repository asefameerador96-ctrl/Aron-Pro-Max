-- V0067 cfg.app.rejected_keep_days (docs/19 s9 line 679, docs/17): days the phone keeps rows the server refused
-- (the "rejected" outbox list the SR can read and support can pull) before they are purged. 30, 7..90, scope global,
-- editor ops, class C1, effect B, delivered to the device; the sibling of V0055's cfg.app.outbox_keep_days. No request
-- named it yet; added so the android outbox purge reads a registered key instead of a constant.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.app.rejected_keep_days', 'app', 'S', 'int', '30'::jsonb, '{"min": 7, "max": 90}'::jsonb, NULL,
   ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops',
   'Days rows the server refused stay in the phone''s rejected list before they are purged (docs/17, docs/19 s9).');
