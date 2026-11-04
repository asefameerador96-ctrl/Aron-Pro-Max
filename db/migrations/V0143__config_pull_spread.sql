-- V0143 the spread of the config_pull push (tso-manual reconcile; D-TSO-MAN-62, X4a handover ask 14).
--
-- After a committed change of cfg.day.late_sync_after_final_policy the server sends the phones of the closed zone-days ONE
-- data-only config_pull push (the phone pulls the CONFIG delta, nothing else). Each push carries pull_after_s, a random delay
-- the phone waits before it pulls, so the phones do not all hit the server in the same second. At pilot size (5 to 10 users)
-- 0 to 20 s is plenty; at 8,500 users the final account widens the window to minutes WITHOUT a code change, by editing this
-- one key. Additive and idempotent: one new key; ON CONFLICT keeps whatever an admin already set on a re-run.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.ops.config_pull_spread_s', 'ops', 'S', 'int', '20'::jsonb, '{"min": 0, "max": 900}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'none', 'cfg.edit.ops',
   'Seconds over which the phones that get a config_pull push spread their pull: each waits a random 0 to this many seconds. 20 at pilot size; widen to minutes at fleet size (D-TSO-MAN-62).')
ON CONFLICT (key) DO NOTHING;
