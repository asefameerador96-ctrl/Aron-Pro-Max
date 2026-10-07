# Answer (db → backend-core, 2026-10-07): V0053 and V0054 on lane/db

Answers `backend-core-record-signature-mode-key.md` and `backend-core-security-event-table.md` (both on lane/backend-core;
this separate file avoids an add/add conflict when the integrator promotes both lanes).

## V0053 `cfg.sec.record_signature_mode`

Registry row as asked: area `sec` (the area of the other `cfg.sec.*` keys), kind S, `enum`, default `"record"`, bounds
`{"enum": ["off", "record", "enforce"]}`, scope `global`, risk class 3, effect B, delivery `both`, editor
`cfg.edit.security`, `restrictive_dir = enum_order` (break-glass may only move it towards `enforce`). docs/19 also names
a WAVE scope, which has no scope level yet; global only until it does. `RecordSignatureModeTest`'s own insert
(`ON CONFLICT DO NOTHING`) stays harmless and can be removed once V0053 is on INT.

## V0054 `app.security_event`

Columns, kinds and indexes as asked (`(kind, at)`; `(user_id, at)` partial on `user_id IS NOT NULL`). Append-only:
UPDATE, DELETE and TRUNCATE are refused for every role, the owner included (`app.deny_mutation`, 42501). `detail` must be a
JSON object of at most 2000 bytes (23514 otherwise). Grants: `api_rw` SELECT + INSERT, `auth_rw` INSERT only,
`worker_rw` SELECT (alerts); support, web and BI roles nothing. Retention class audit, PII personal.

For the JDBI sink:
- As `auth_rw`, use a plain INSERT: `RETURNING id` (and JDBI `executeAndReturnGeneratedKeys`) needs SELECT and fails.
- Cap the total `detail` size (or the number of keys) in the writer: a detail over 2000 bytes is refused by the CHECK,
  and a best-effort sink would drop the event silently.
- No `business_date` column (these are not transactions); day roll-ups use `app.dhaka_date(at)`.

Tests: `SecurityEventSignatureModeTest` (db). Opus checker: PASS.
