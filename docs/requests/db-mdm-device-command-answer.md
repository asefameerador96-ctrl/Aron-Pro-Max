# Answer (db → mdm): `mdm_policy`, `device.mdm_policy_id`, `device_command` (D-MDM-01)

Status: built on lane/db, 2026-10-22: **V0102** `mdm_policy_device_command` and **V0103** `mdm_policy_validate`.

- Your DDL as written, except: the FK `device_mdm_policy_fkey`, `device_mdm_policy_version_check` and
  `device_mdm_policy_pair` on the existing `app.device` are added NOT VALID in V0102 and validated in V0103 (squawk).
- Added (Opus checker): trigger `device_command_guard`. On INSERT the state must be `pending_approval` for
  factory_reset and `queued` for every other type, and `approved_by`, `approved_at`, `push_sent_at`, `sent_at`,
  `acked_at`, `result` must be null (23514). On the UPDATE that first sets `approved_by`, `approved_at` is overwritten
  with now(). Who may approve (SUPERADMIN, or ADMIN with `mdm` = `w` in `app.user_module_grant`) stays your API check;
  your CHECK keeps approver ≠ requester.
- **Changed by checker round 3 (db session 8, still before INT):**
  - **Bug fixed in your DDL:** a factory reset that expired while `pending_approval` (no `approved_by`, no `sig`)
    broke both CHECKs, so your pull's `UPDATE ... SET state = 'expired'` (MdmService pull) raised 23514 and every later
    pull of that phone failed. The two CHECKs now allow `expired` unsigned and unapproved. Please add a test in your
    lane: a pending reset past `expires_at`, then a pull.
  - The guard now polices UPDATEs: states move forward only (pending_approval → queued/rejected/expired; queued →
    sent/acked/failed/expired; sent → acked/failed/expired; same state allowed); `command_id`, `device_id`, `type`,
    `params`, `reason`, `request_sha256`, `created_by`, `created_at`, `expires_at` are fixed; `sig`, `approved_by`,
    `approved_at`, `decision_reason`, `push_sent_at`, `sent_at`, `acked_at`, `result` are written once. Your code's
    moves (approve, reject, expire, pull to sent, ack) all pass; a re-pull must not rewrite `sent_at` (yours updates
    only `state = 'queued'` rows, so it does not).
  - A factory reset must name its requester (`created_by` not null), else approver ≠ requester could not hold.
  - `approved_at` is the database's now(): your `:now` binding is ignored (under a fixed test clock it can read
    earlier than `created_at`).
  - Who may approve stays API-only on purpose (test admins made after V0089 carry no module grant).
- Grants: api_rw SELECT/INSERT/UPDATE on both tables through its app rows; no DELETE. support_ro SELECT on
  `device_command` except `result` (a locate answer is personal: `[pii:personal]`). `dw.v_device_command` (no sig, no
  result) for web_ro and bi_reader, as you asked for the read role.
- Audited-entity list: there is none in the database; the audit chain is written by the API (AuditLog.write).
- Indexes added on `created_by`, `approved_by` (mdm_policy: `created_by`, `updated_by`) for the FK checks.
