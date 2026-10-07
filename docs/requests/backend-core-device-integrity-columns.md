# Request to db (from backend-core, 2026-10-07): integrity columns on `app.device` (contract v1.2, R12, R13, R18)

Contract v1.2 adds `DeviceStatusReport.root_hints` (up to 16 of `su_binary`, `test_keys`, `ro_debuggable`, `ro_secure_off`,
`root_app`, `hook_framework`, `root_mount`, `clone_app_installed`, `secondary_user`, `foreign_data_dir`).
It also adds `play_integrity_unavailable` (`{reason: no_play_services|not_configured|offline|api_error|timeout, detail <= 80}` or null),
to both `DeviceStatusReport` and `EnrolDeviceRequest`. R13 says root hints are "stored on the device record".
R18(3) says a missing marker or missing hints mean *unknown*: the server stores NULL, never an empty array, and never
treats it as a failure or as a clean phone.

## Ask (forward-only migration)
```sql
ALTER TABLE app.device
  ADD COLUMN root_hints                 text[] CHECK (root_hints IS NULL OR cardinality(root_hints) <= 16),  -- NULL = unknown (older app), '{}' = clean
  ADD COLUMN root_hints_at              timestamptz,
  ADD COLUMN play_integrity_unavailable jsonb CHECK (play_integrity_unavailable IS NULL OR jsonb_typeof(play_integrity_unavailable) = 'object'),
  ADD COLUMN play_integrity_unavailable_at timestamptz;
```
Please add data-dictionary comments as for the other device columns. Optionally add a CHECK on the hint values, using the
contract list above.

## What backend-core does once it lands
- `device_status` ingest updates these columns from the newest report only (by `captured_at`). A report whose member
  is absent leaves the stored value as it is. NULL is written only if the device row has never had a value.
- Enrolment (N-027/N-031, not built yet): the top-level `EnrolDeviceRequest.play_integrity_unavailable` wins over
  the copy in the nested `status` (R18(2)).
- Scoring treats NULL as no evidence. Root hints are weighted `DEVICE_INTEGRITY_FAIL` evidence only and never block a sale on their own.

## Answer (db, 2026-10-07): V0025, V0026 (on lane/db; reaches INT with the next train)
Shipped as typed columns instead of one jsonb, so the reason is checkable and indexable:

| Column | Write | NULL means |
|---|---|---|
| `root_hints text[]` | `root_hints` as sent; `{}` when the phone sent an empty list | unknown (never write `{}` for a missing member, R18(3)) |
| `root_hints_at timestamptz` | report `captured_at`; required whenever `root_hints` is set (CHECK) | never reported |
| `integrity_unavailable_reason text` | `play_integrity_unavailable.reason` (the five contract values, CHECK) | never reported |
| `integrity_unavailable_at timestamptz` | report `captured_at`; both or neither with the reason (CHECK) | never reported |

At most 16 hints, each one of the ten R13 values (CHECK). `play_integrity_unavailable.detail` (<= 80 chars) is not
stored on the device row (free text on a long-lived row; log it with the report if needed). Ask for a column if a
screen needs it. Your "newest report only, absent member leaves the value" rule fits these columns unchanged.
Fuller note: `docs/requests/db-backend-core-config-and-device-v12.md`.
