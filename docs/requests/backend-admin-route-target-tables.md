# Request (backend-admin -> db, via the lead; 2026-10-22): route monthly target tables (D-TARGET-MIN-01)

Status: OPEN. `PUT/GET /v1/admin/route-targets` (contract slice `putRouteTargets`, `listRouteTargets`) is built and tested against the DDL below,
which the masterdata tests apply from `backend/masterdata/src/test/resources/proposed_route_monthly_target.sql` (idempotent: it ends in the same state
if your migration is applied first). The API probes `to_regclass('app.route_monthly_target')` and answers 503 `ERR_SERVICE_UNAVAILABLE` until the
migration is deployed. When your migration lands, delete the proposed file and its loader line in `BackendAdminEnv`.

Take the file as is, or tell me the names you chose and I will follow (the code touches only the columns below):

- `app.route_monthly_target (route_id, sku_id, month date = first day of month, qty_base bigint 0..1e9, batch_uuid, created_at, updated_at, updated_by)`, primary key (route_id, sku_id, month).
- `app.route_target_batch (batch_uuid pk, content_sha256 bytea(32), row_count, committed_by, committed_at)`: the idempotency record.

Also needed from db for the same upload: `route.kind` values `normal` and `manual` (today `sr`/`amo`): the API accepts a target for kind `sr`, `normal`, `manual` and refuses the rest as `not_selling_route`.
