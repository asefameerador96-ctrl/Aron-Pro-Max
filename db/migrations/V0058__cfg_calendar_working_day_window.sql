-- V0058 working-day window keys (F-SYS-090, D-584; answers docs/requests/backend-core-working-day-window-keys.md).
-- Values follow docs/19 s9 (lines 1113-1116) except where noted:
--   cfg.calendar.window_unit             enum calendar | working_days, default calendar. docs/19 spells the first value
--                                        calendar_days and defaults to working_days; backend-core's WorkingDays.kt and
--                                        the phones use "calendar", and F-SYS-090 ships OFF (docs/15): the owner turns
--                                        working_days on once OI-19-31 is confirmed. C3, effect S, delivery both.
--   cfg.bundle.stale_max_cal_days_ceiling int 7, 3..14 (docs/19; the request draft had 2..14). C2, device.
--   cfg.calendar.break_overrides         list of {from, to, stale_max_days, max_backdate_days,
--                                        offline_unlock_max_days}, at most 20 (value_type list, like
--                                        cfg.sys.change_freeze_windows, so the validator enforces max_items). Rule 14 of
--                                        docs/19 s2.6b (each value within its ceiling) is NOT enforced yet: it is in
--                                        bounds_rule and asked of backend-admin. C2, both, field editor.
--   cfg.calendar.prefetch_next_working_day bool true. docs/19 delivers it to the server job J2 only; the request asks the
--                                        phone to read it too, so delivery is both. C1.
-- All global. A missing key kept today's calendar rule, so registering the defaults changes no behaviour.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.calendar.window_unit', 'calendar', 'S', 'enum', '"calendar"'::jsonb, '{"enum": ["calendar", "working_days"]}'::jsonb, NULL,
   ARRAY['global']::text[], 3, NULL, 'S', 'both', false, false, 'cfg.edit.ops',
   'Unit of cfg.bundle.stale_max_days, cfg.sync.max_backdate_days and cfg.auth.offline_unlock_max_days: calendar days or working days (docs/19, D-584).'),
  ('cfg.bundle.stale_max_cal_days_ceiling', 'bundle', 'S', 'int', '7'::jsonb, '{"min": 3, "max": 14}'::jsonb, NULL,
   ARRAY['global']::text[], 2, NULL, 'B', 'device', false, false, 'cfg.edit.ops',
   'Calendar-day hard ceiling on bundle age, whatever the working-day count (docs/19, D-584).'),
  ('cfg.calendar.break_overrides', 'calendar', 'S', 'list', '[]'::jsonb, '{"max_items": 20}'::jsonb,
   'list of {from, to, stale_max_days, max_backdate_days, offline_unlock_max_days}; every value within docs/19 s2.6b rule 14',
   ARRAY['global']::text[], 2, NULL, 'B', 'both', false, false, 'cfg.edit.field',
   'Scheduled per-break windows (Eid, a nine-day break) that override the stale, backdate and offline-unlock windows (docs/19, D-584).'),
  ('cfg.calendar.prefetch_next_working_day', 'calendar', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL,
   ARRAY['global']::text[], 1, NULL, 'B', 'both', false, false, 'cfg.edit.ops',
   'Build the next working day''s snapshot on the last working evening and let phones pre-fetch it (docs/19, D-584).');
