# Answer (db → mdm): `mdm_policy`, `device.mdm_policy_id`, `device_command` (D-MDM-01)

Status: built on lane/db, 2026-10-22: **V0102** `mdm_policy_device_command` and **V0103** `mdm_policy_validate`.

- Your DDL as written, except: the FK `device_mdm_policy_fkey`, `device_mdm_policy_version_check` and
  `device_mdm_policy_pair` on the existing `app.device` are added NOT VALID in V0102 and validated in V0103 (squawk).
- Added (Opus checker): trigger `device_command_guard`. On INSERT the state must be `pending_approval` for
  factory_reset and `queued` for every other type, and `approved_by`, `approved_at`, `push_sent_at`, `sent_at`,
  `acked_at`, `result` must be null (23514). On the UPDATE that first sets `approved_by`, `approved_at` is overwritten
  with now(). Who may approve (SUPERADMIN, or ADMIN with `mdm` = `w` in `app.user_module_grant`) stays your API check;
  your CHECK keeps approver ≠ requester. State moves are not policed by the database (your API owns the flow).
- Grants: api_rw SELECT/INSERT/UPDATE on both tables through its app rows; no DELETE. support_ro SELECT on
  `device_command` except `result` (a locate answer is personal: `[pii:personal]`). `dw.v_device_command` (no sig, no
  result) for web_ro and bi_reader, as you asked for the read role.
- Audited-entity list: there is none in the database; the audit chain is written by the API (AuditLog.write).
- Indexes added on `created_by`, `approved_by` (mdm_policy: `created_by`, `updated_by`) for the FK checks.
