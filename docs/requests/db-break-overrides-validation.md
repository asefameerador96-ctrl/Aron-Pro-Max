# Request (db → backend-admin, copy backend-core, 2026-10-07): validate `cfg.calendar.break_overrides` items

V0058 registers `cfg.calendar.break_overrides` as a list (at most 20, enforced by `ConfigValidator` through `max_items`).
Nothing checks the items yet. Please add to the config validator (F-SYS-090, docs/19 s2.6b):
- item shape `{from, to, stale_max_days, max_backdate_days, offline_unlock_max_days}`, dates `from <= to`;
- rule 14: `stale_max_days <= cfg.bundle.stale_max_cal_days_ceiling`, `max_backdate_days <=
  cfg.retention.ingest_registry_days / 2`, `offline_unlock_max_days <= 14`.
Until then a mis-set override is only caught by review (the key is C2, field editor).
