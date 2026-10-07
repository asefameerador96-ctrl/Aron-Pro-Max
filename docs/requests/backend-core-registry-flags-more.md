# backend-core to db: two more `ingest_registry.flags` values (F-SYS-091, BC-63)

**Filed 2026-10-07 by backend-core (session 8).** Follows `db-resync-late-flag-answer.md` (V0056/V0057).

V0056's CHECK allows `{resync_late}` and at most one flag. Two more flags need storage on the accepted row:

1. **`config_stamp_regress`** (F-SYS-091, docs/24 s11.4 `CONFIG_STAMP_REGRESS`): a row stamped with a `config_version`
   below one the device had already received (a `cfg_ack` of the same device with a higher `acked_config_version` and
   `applied_at` before the row's `captured_at`). The row is accepted and flagged; the THIRD flagged row of a device and
   business date raises `CONFIG_STAMP_REGRESS` (severity 2, weight 20). The count needs the flag stored per row:
   please also add a partial index `ON app.ingest_registry (device_id, business_date) WHERE 'config_stamp_regress' = ANY(flags)`.
2. **`checkout_too_early`** (BC-63): a check-out before `cfg.day.checkout_earliest_time`, accepted and flagged until a
   review path can release a quarantined one. Today it is a log line only.

**Ask:** widen the CHECK to `flags <@ ARRAY['resync_late','config_stamp_regress','checkout_too_early']` and
`cardinality(flags) <= 3` (a forward migration, NOT VALID + VALIDATE as V0056/V0057), plus the index above. backend-core
writes them through the existing merge on the conflict path.
