# backend-reports request to the db lane: dw tables M-123 to M-125 (F-SYS-096)

F-SYS-096 ("Integrity, activity and consent records land in their fact tables and the daily screen-use aggregate equals the activity log") needs the
tables that docs/16 s8.9.5 specifies and no migration creates yet (D-559, G-qa-91): `dw.fact_device_integrity`, `dw.fact_activity`, `dw.agg_daily_screen_use`, `dw.fact_consent`.
Please add them forward-only exactly as docs/16 s8.9.5 lists them (the DDL there is binding; partition `fact_device_integrity` and `fact_activity` by month on `business_date`
and register them in `app.partition_policy`; `agg_daily_screen_use` and `fact_consent` unpartitioned). Source tables already exist: `app.activity_log` (events jsonb: `at`, `screen`, `action`, `duration_ms`),
`app.user_consent` (policy_key, policy_version, accepted, locale, shown_at), `app.device` / the device status report for integrity.

Needed from the db lane besides the DDL: `dw.fact_activity.device_key` and `user_key` are `app.device.id` and `app.app_user.id` (no surrogate dimension tables exist; say so in the data dictionary).
When the tables land, backend-reports adds three projections to the aggregation worker (activity rows with `seq` = position in the event array, the daily rollup recomputed per date, consent per user and version, integrity per login/bundle) and the test
"daily screen-use rollup equals the activity log": sum(events) of `agg_daily_screen_use` = count of non-duplicate events in `app.activity_log` of that date.

Already delivered from the existing tables (no request): `dw.fact_attendance`, `dw.fact_geo_fix` (route-day rebuild) and `dw.fact_device_day` (device_day_agg dirty keys).

## Answer (db, 2026-10-07): V0031
`dw.fact_device_integrity`, `dw.fact_activity` (monthly partitions, in `app.partition_policy`), `dw.agg_daily_screen_use`
and `dw.fact_consent` exactly as docs/16 s8.9.5. `user_key` = `app.app_user.id`, `device_key` = `app.device.id` (said
in the data dictionary). `rooted_hint` follows R18: NULL = unknown (root_hints never reported), false = empty list.
The worker has full rights on dw; web and BI see dw only through `v_*` views (none added for these yet: ask when a
dashboard needs one).
