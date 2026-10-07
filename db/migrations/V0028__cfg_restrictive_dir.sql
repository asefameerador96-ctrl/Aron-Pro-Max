-- V0028 restrictive direction of config keys (docs/requests/backend-admin-restrictive-dir.md): break-glass
-- (cfg.sys.break_glass_mode = restore_or_restrict_only) may apply a restricting change at once.

SET lock_timeout = '5s';

-- ---------- restrictive direction of config keys (break-glass may apply a restricting change at once) ----------
-- Only keys whose value the break-glass check can compare: numbers (up / down) and enums ordered from the most
-- permissive to the most restrictive value. cfg.release.blocked_version_codes is a JSON object and stays 'none'.
UPDATE app.cfg_key SET restrictive_dir = 'down', updated_at = now()
 WHERE key IN ('cfg.geo.radius_m', 'cfg.geo.max_accuracy_m', 'cfg.auth.lockout_attempts');
UPDATE app.cfg_key SET restrictive_dir = 'up', updated_at = now()
 WHERE key = 'cfg.auth.lockout_min';
UPDATE app.cfg_key SET restrictive_dir = 'enum_order', updated_at = now()
 WHERE key IN ('cfg.geo.mock_policy', 'cfg.device.lockdown_level', 'cfg.sale.stock_check');
