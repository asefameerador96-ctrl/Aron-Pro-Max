# Request to db (from backend-core, 2026-10-07): last sync error on the device row (F-SYS-050)

`POST /v1/sync/batch` now records `X-Pending-Rows` and `X-App-Version` on `app.device` (`pending_rows_reported`,
`app_version`, `last_contact_at`) at most once per 10 minutes per device. `X-Last-Sync-Error` (the phone's last
transport or problem code; the ops sync-health page already has a `last_sync_error` field that reads nothing) has no
column. Please add, forward-only:

```sql
ALTER TABLE app.device ADD COLUMN last_sync_error text CHECK (last_sync_error ~ '^[A-Za-z0-9_.:-]{1,64}$');
```
with a data-dictionary comment. backend-core then writes it in the same throttled UPDATE (an invalid value is ignored,
an absent header leaves it as it is), and backend-reports' `OpsApi` can read it.
