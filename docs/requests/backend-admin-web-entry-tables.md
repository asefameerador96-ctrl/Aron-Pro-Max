# Request: web-entry and entry-unlock tables (to: db)

From: backend-admin. Blocks F-API-049, F-API-050, F-API-052, F-API-038 (web half) and the `web_entry` scope of F-API-048.

docs/24 s11 ("Back office") lists these tables; none exists in V0001 to V0016:

- `app.entry_unlock` (id, scope_type zone|route, scope_id, from_date, to_date, reason, expires_at, created_by, expired_at nullable, expired_by nullable, created_at, version; CHECK to_date >= from_date; index on (scope_type, scope_id, expires_at)). Expiry is a column write (`expired_at`), so the table is mutable only on those two columns.
- `app.web_entry_route_day` and `app.web_entry_line`: one entry per route-day (Issue, Return, Memos per SKU, Successful Calls) with the browser `client_uuid` (unique), `route_id`, `business_date`, `entered_by`, `replaced_at`/`replaced_by` for the audited re-save, `voided_at` (data void), `source = 'web'`; lines per SKU.
- `app.qc_summary_entry`: market and warehouse QC, `client_uuid` unique, `route_id` or zone, `business_date`, `source = 'web'`, `voided_at`.
- No Astha quantity table (docs/27 trim).

Data void (F-API-048) already voids these three by name when the tables exist (`to_regclass`), so they need `route_id`, `business_date`, `client_uuid` and `voided_at` columns exactly as the app record tables have.

## Answer (db, 2026-10-07): V0039 (on lane/db; the integrator promotes it to INT)
- `app.entry_unlock` as asked (`id` = unlock_id; `reason` 10..500 like ChangeReason; `version` starts at 1). After insert
  only `expired_at` + `expired_by` may be written, together and once; the trigger moves `version`. No delete. Status is
  derived: active while `expired_at IS NULL AND expires_at > now` (your clock). No CHECK ties `expires_at` to the DB clock.
- `app.web_entry_route_day`: browser `client_uuid` unique, `route_id`, `business_date`, `successful_calls`,
  `target_outlets` (snapshot at save), `app_overlap`, `change_reason`, `source` = `'web'`, `entered_by`/`entered_at`,
  `voided_at`. **Re-save:** insert a new entry with `supersedes_client_uuid` = the old one (same route-day, and then
  `change_reason` is required) after setting `replaced_at` + `replaced_by` (user id) on the old one in the same
  transaction. A partial unique index allows one live entry (not replaced, not voided) per route-day, so close first, then insert.
- `app.web_entry_line`: `client_uuid` defaults to `gen_random_uuid()` (the browser sends none), `entry_client_uuid`,
  `route_id` + `business_date` copied from the entry (a composite FK makes them equal), `sku_id` (one line per SKU),
  `issue_qty_base`, `return_qty_base` (<= issue), `memo_count`, `class_qty_base` jsonb object, `voided_at`. Sale is
  issue minus return: compute it in the API (no generated column, because guarded tables and stored generated columns do not mix).
- `app.qc_summary_entry`: `client_uuid`, `qc_source` market|warehouse (the contract's `source`), `zone_id`, `route_id`
  (required for market), `business_date`, `reason` (required for warehouse), `source` = `'web'`, `entered_by`, `voided_at`.
  Rows go into `app.qc_summary_entry_line` (`entry_client_uuid`, `sku_id`, `fault_type_code`, `qty_base`; append-only;
  they follow their header's void).
- All three void targets take your DataVoidApi statement unchanged (tested in `WebEntryPasswordBreadcrumbTest`).
  Everything is append-only apart from the closing columns. `web_entry_outlet_sku` is not added because no row asks for it.
