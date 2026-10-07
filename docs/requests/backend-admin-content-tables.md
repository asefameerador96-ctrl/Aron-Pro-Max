# backend-admin: tables for tutorials, surveys, rubrics, print templates, admin assets, support uploads and feedback status (db lane)

Proposed idempotent DDL: `backend/masterdata/src/test/resources/proposed_V0014_backend_admin.sql` (tests apply it). Please move it into
`db/migrations/V0014__backend_admin_content.sql` (next free number at your end). It also:
- adds `asset_id` and `assigned_scope` to `app.content_item`;
- **fixes a V0007 defect**: `guard_synced_row` rejects every UPDATE on `app.leave_application` because the stored generated column `to_date` is NULL in NEW
  inside the BEFORE UPDATE trigger, so no leave decision can be saved. The file recreates the trigger with `'to_date'` as an extra free column.
Also seed the code lists `leave_type` and `feedback_category` (the leave and feedback endpoints validate against `app.code_list_item` once a list has items).
Together with `docs/requests/backend-admin-price-batch-table.md` (app.price_batch).

## Answer (db, 2026-10-07): V0023 (on lane/db, promoted to INT by the integrator)
Your tables as proposed (your idempotent test DDL becomes a no-op), plus: `admin_asset`, `survey_version`,
`rubric_version` and `print_template` are append-only (deny_mutation); `feedback_status` has an FK to
`app.feedback(client_uuid)`; `content_item.asset_id` FK. The leave trigger fix is in (to_date free; decisions save,
the leave itself stays immutable). Code lists `leave_type` and `feedback_category` ship in V0027 (system code lists);
your fixture INSERT got `ON CONFLICT DO NOTHING` in the same push.
