-- V0053 registry row cfg.sec.record_signature_mode (docs/19 s9, F-SYS-072, D-104; answers
-- docs/requests/backend-core-record-signature-mode-key.md). The ingest already honours it with the safe default
-- `record` (accept the sale, raise DEVICE_INTEGRITY_FAIL with the reason); the portal can now set it.
-- off < record < enforce: `off` removes tamper evidence (C3); break-glass may only move it towards `enforce`
-- (restrictive_dir enum_order, V0028). Scope global (docs/19 also names WAVE, which has no scope level yet). The phone
-- reads it to decide whether to sign, so delivery is both. Pilot default `record`; `enforce` before wave 1 is an
-- admin change through the portal, not a migration.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en, restrictive_dir) VALUES
  ('cfg.sec.record_signature_mode', 'sec', 'S', 'enum', '"record"'::jsonb, '{"enum": ["off", "record", "enforce"]}'::jsonb, NULL,
   ARRAY['global']::text[], 3, '`off` removes tamper evidence', 'B', 'both', false, false, 'cfg.edit.security',
   'Signed sale records from enrolled phones: off (no signature), record (accept, flag a bad signature) or enforce (refuse a bad signature) (docs/19 s9, D-104).',
   'enum_order');
