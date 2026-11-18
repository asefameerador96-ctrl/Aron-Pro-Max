-- V0206 (PROVISIONAL number, lead 2026-11-18): the three keys of the waiting-item sweep (D-LOY-API-29), registered so the admin settings
-- can hold them and the sweep can be stopped or slowed without a deploy. Plain INSERT ... ON CONFLICT DO NOTHING: no data guard, nothing that can raise.
--   cfg.sync.waiting_sweep_enabled    bool, default true  (off = the API process runs no pass)
--   cfg.sync.waiting_sweep_interval_s int, default 60, 10..3600 (seconds between the end of one pass and the start of the next)
--   cfg.sync.waiting_sweep_batch      int, default 200, 1..1000 (waiting items examined per pass)
-- All global, server-only. The code reads them at every pass and falls back to these defaults when a key is absent.
SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.sync.waiting_sweep_enabled', 'sync', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 2, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Release quarantined children that wait for another parent automatically once that parent is stored (D-LOY-API-29); off leaves them to the reviewer.'),
  ('cfg.sync.waiting_sweep_interval_s', 'sync', 'S', 'int', '60'::jsonb, '{"min": 10, "max": 3600}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Seconds between two passes of the waiting-item sweep (D-LOY-API-29).'),
  ('cfg.sync.waiting_sweep_batch', 'sync', 'S', 'int', '200'::jsonb, '{"min": 1, "max": 1000}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Waiting items examined per pass of the waiting-item sweep; a larger backlog is worked off over several passes (D-LOY-API-29).')
ON CONFLICT (key) DO NOTHING;
