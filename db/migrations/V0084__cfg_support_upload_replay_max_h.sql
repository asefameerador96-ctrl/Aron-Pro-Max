-- V0084 cfg.support.upload_replay_max_h (lead ruling 2026-10-18, DECISIONS D-PDA-REPLAY-AGE; D-MIG-RANGE-01 frees V0084):
-- hours a stored "PDA to Support" upload may be replayed for a fresh write SAS. An older replay is refused with a visible
-- reason (400 replay_expired; the phone starts a new upload under a new upload_uuid); nothing is stored or applied.
-- 168 (7 days), 1..720, scope global, editor ops, class C1, effect B, server only.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.support.upload_replay_max_h', 'support', 'S', 'int', '168'::jsonb, '{"min": 1, "max": 720}'::jsonb, NULL,
   ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Hours a stored PDA to Support upload may be replayed for a new write SAS; older replays are refused (start a new upload).');
