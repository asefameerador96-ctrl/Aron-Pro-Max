# backend-admin: `app.cfg_key.restrictive_dir` is `none` for every seeded key

Break-glass (`cfg.sys.break_glass_mode` = `restore_or_restrict_only`) applies a change at once only when it restores a
version or moves a value in the key's restrictive direction (`up`, `down` or `enum_order`, docs/24 s9.1). The registry seed (V0006, V0009)
leaves `restrictive_dir = 'none'` for all 172 keys and docs/24 s9.5 has no column for it, so today break-glass can only restore.

Request (db lane, lead to rule on the list): a forward migration setting `restrictive_dir` for the keys where a direction is obvious, for example
`cfg.geo.radius_m` down, `cfg.geo.max_accuracy_m` down, `cfg.geo.mock_policy` enum_order, `cfg.auth.lockout_attempts` down, `cfg.auth.lockout_min` up,
`cfg.release.blocked_version_codes` up, `cfg.device.lockdown_level` enum_order. Until then nothing breaks: break-glass restore works.
