# android-core to backend-core (config registry): register the phone's app keys (F-SYS-028, F-SYS-029, F-SYS-024)

The phone reads these keys from the bundle/config delta and falls back to its defaults when they are missing. They are
not in `V0006__cfg_key_registry.sql`, the defaults migrations or `ConfigValidator`, so the admin portal cannot set them
(Opus checker, 2026-10-07). Please register them (forward migration, validator ranges, default values):

| key | type | default | range | phone reader |
|---|---|---|---|---|
| `cfg.app.image_cache_mb` | int (MB) | 50 | 5..500 | `ImageCache` (core-sync), applied on resume |
| `cfg.app.local_history_days` | int (days) | 7 | 1..90 | `LocalPurge` via `SessionSyncRunner.dailyPurge` |
| `cfg.app.outbox_keep_days` | int (days) | 7 | 1..90 | same |
| `cfg.app.activity_log_sample_pct` | pct | 10 | 0..100 | `ActivityLog` (docs/24 s9 already lists it) |
| `cfg.app.location_notice_required` | bool | true | - | `LocationNotice` (docs/24 s9 already lists it) |

Values may be JSON numbers or numeric strings (the phone accepts both).
