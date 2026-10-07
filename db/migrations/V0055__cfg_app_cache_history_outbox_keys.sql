-- V0055 three field-app keys (F-SYS-024/028/029; answers docs/requests/backend-core-app-cfg-keys.md, forwarded from
-- android-core). Values follow docs/19 s9 lines 677-680, which the request's draft differed from:
--   cfg.app.local_history_days  7, 1..30  (D-83: local Sale History window; purge by business date, never unsynced rows)
--   cfg.app.outbox_keep_days    3, 1..14  (docs/17: synced outbox rows kept; keep_days x 24 >= cfg.sync.resync_window_h
--                                          + 24 must hold, so 4 or more when the window is 72 h; the validator checks
--                                          the pair, the rule is in bounds_rule)
--   cfg.app.image_cache_mb      40, 10..70 (D-546: the installed-size budget of docs/04 caps it at 70 MB)
-- Scope global only (docs/19 "G"), editor ops, class C1, effect B, delivered to the device.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.app.local_history_days', 'app', 'S', 'int', '7'::jsonb, '{"min": 1, "max": 30}'::jsonb, NULL,
   ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops',
   'Days of working data (Sale History) kept on the phone; purged by business date, never rows not yet synced (docs/19 s9, D-83).'),
  ('cfg.app.outbox_keep_days', 'app', 'S', 'int', '3'::jsonb, '{"min": 1, "max": 14}'::jsonb,
   'outbox_keep_days x 24 >= cfg.sync.resync_window_h + 24 (docs/17)',
   ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops',
   'Days synced outbox rows stay on the phone so a re-sync can resend them (docs/17, docs/19 s9).'),
  ('cfg.app.image_cache_mb', 'app', 'S', 'int', '40'::jsonb, '{"min": 10, "max": 70}'::jsonb, NULL,
   ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops',
   'Size cap in MB of the phone''s LRU cache of product thumbnails and assets (docs/19 s9, D-546).');
