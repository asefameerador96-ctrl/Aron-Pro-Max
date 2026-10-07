# Request to db (from backend-core, 2026-10-07): register three phone app keys (F-SYS-024/028/029)

Forwarded from `android-core-backend-app-cfg-keys.md` (lane/android-core). The phone reads these keys from the bundle and
config delta and falls back to its own defaults while they are missing; the portal cannot set them until they are in
`app.cfg_key`. The validator (`ConfigValidator`) is generic and reads type and bounds from the registry, and the bundle
and delta deliver every key with delivery `device`/`both`, so a registry row is all the backend needs.

Already registered and matching the request (V0009): `cfg.app.activity_log_sample_pct` (pct, 10, 0..100) and
`cfg.app.location_notice_required` (bool, true).

## Ask: one forward migration with these rows (same columns and style as V0009 line 39-41)
```sql
('cfg.app.image_cache_mb',     'app', 'S', 'int', '50'::jsonb, '{"min": 5, "max": 500}'::jsonb, NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops', 'Field app details (docs/24 s9.5)'),
('cfg.app.local_history_days', 'app', 'S', 'int', '7'::jsonb,  '{"min": 1, "max": 90}'::jsonb,  NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops', 'Field app details (docs/24 s9.5)'),
('cfg.app.outbox_keep_days',   'app', 'S', 'int', '7'::jsonb,  '{"min": 1, "max": 90}'::jsonb,  NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.ops', 'Field app details (docs/24 s9.5)')
```
Plus their data-dictionary comments as your convention requires. No backend code change follows; backend-core will add a
bundle test that the three arrive in `config.values` once the migration is on INT.
