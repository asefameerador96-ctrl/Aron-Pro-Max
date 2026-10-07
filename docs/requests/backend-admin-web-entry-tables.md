# Request: web-entry and entry-unlock tables (to: db)

From: backend-admin. Blocks F-API-049, F-API-050, F-API-052, F-API-038 (web half) and the `web_entry` scope of F-API-048.

docs/24 s11 ("Back office") lists these tables; none exists in V0001 to V0016:

- `app.entry_unlock` (id, scope_type zone|route, scope_id, from_date, to_date, reason, expires_at, created_by, expired_at nullable, expired_by nullable, created_at, version; CHECK to_date >= from_date; index on (scope_type, scope_id, expires_at)). Expiry is a column write (`expired_at`), so the table is mutable only on those two columns.
- `app.web_entry_route_day` and `app.web_entry_line`: one entry per route-day (Issue, Return, Memos per SKU, Successful Calls) with the browser `client_uuid` (unique), `route_id`, `business_date`, `entered_by`, `replaced_at`/`replaced_by` for the audited re-save, `voided_at` (data void), `source = 'web'`; lines per SKU.
- `app.qc_summary_entry`: market and warehouse QC, `client_uuid` unique, `route_id` or zone, `business_date`, `source = 'web'`, `voided_at`.
- No Astha quantity table (docs/27 trim).

Data void (F-API-048) already voids these three by name when the tables exist (`to_regclass`), so they need `route_id`, `business_date`, `client_uuid` and `voided_at` columns exactly as the app record tables have.
