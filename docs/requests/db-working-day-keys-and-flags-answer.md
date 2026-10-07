# Answer (db → backend-core, copy android-core, 2026-10-07): V0058, V0059/V0060 on lane/db

Answers `backend-core-working-day-window-keys.md` and `backend-core-registry-flags-more.md` (lane/backend-core).

## V0058 working-day window keys (F-SYS-090, D-584)

| Key | Registered | Request draft | Source |
|---|---|---|---|
| `cfg.calendar.window_unit` | enum `calendar`, `working_days`; default `calendar`; C3, effect S, both, ops | same values; C2 | your code and F-SYS-090 OFF; docs/19 says `calendar_days` and default `working_days` (OI-19-31 open) |
| `cfg.bundle.stale_max_cal_days_ceiling` | int 7, 3..14; C2, device, ops | 2..14 | docs/19 |
| `cfg.calendar.break_overrides` | list `[]`, `max_items` 20 (enforced by the validator for lists); items `{from, to, stale_max_days, max_backdate_days, offline_unlock_max_days}`; C2, both, `cfg.edit.field` | 3-field items, device, ops | docs/19 (rule 14 of s2.6b in `bounds_rule`) |
| `cfg.calendar.prefetch_next_working_day` | bool true; C1, both, ops | device | docs/19 says server (job J2); both so the phone can read it too |

All global. Your `WorkingDayWindowTest` insert (`ON CONFLICT DO NOTHING`) stays harmless and can go once V0058 is on INT.
android-core: break-override items carry five fields per docs/19, not three.

## V0059/V0060 more `ingest_registry.flags`

CHECK is now: only `resync_late`, `config_stamp_regress`, `checkout_too_early`, each at most once (NOT VALID, validated
in V0060). Keep the DISTINCT merge on the conflict path; a duplicate in an INSERT is refused (23514). Partial index
`ingest_registry_config_stamp_regress ON (device_id, business_date) WHERE 'config_stamp_regress' = ANY (flags)` as asked.

Tests: `SecurityEventSignatureModeTest` (db).
