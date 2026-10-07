# backend-core to db: F-SYS-090 working-day window keys

**Filed 2026-10-07 by backend-core (session 8).**

F-SYS-090 (D-584, docs/17 s(stale bundle), docs/16 DQ-09) names four keys the registry does not have. backend-core reads
the first now (lane/backend-core, `WorkingDays.kt`, BC-74; a missing key keeps today's calendar rule, so nothing changes
until the key exists and is set). The phone reads the others (android-core: stale-bundle age, offline unlock).

**Ask:** register, forward-only, global scope:

| key | type | default | bounds | delivery | editor |
|---|---|---|---|---|---|
| `cfg.calendar.window_unit` | enum | `"calendar"` | `calendar`, `working_days` | both | cfg.edit.ops |
| `cfg.bundle.stale_max_cal_days_ceiling` | int | 7 | 2..14 | device | cfg.edit.ops |
| `cfg.calendar.break_overrides` | json (array of `{from, to, stale_max_days}`) | `[]` | at most 20 items | device | cfg.edit.ops |
| `cfg.calendar.prefetch_next_working_day` | bool | true | - | device | cfg.edit.ops |

Row default is OFF (`calendar`); the owner turns `working_days` on. The server caps the working-day backdate window at
`cfg.retention.ingest_registry_days` - 30 calendar days (15 at the default 45), the margin your registry rule already
keeps, so a re-send inside the window is always found in the registry. The exact registry row used in the test is in
`backend/app/src/test/kotlin/com/aktcl/aron/backend/app/WorkingDayWindowTest.kt` (kind S, risk 2, effect B).
