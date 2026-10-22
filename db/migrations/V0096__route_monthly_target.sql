-- V0096 route x SKU monthly targets: the minimum target slice (owner directive 2026-10-22, docs/34 s2, DECISIONS
-- D-TARGET-MIN-01). The DDL of backend-admin's request docs/requests/backend-admin-route-target-tables.md (lane
-- backend-admin 1be69cc6) as written: its PUT/GET /v1/admin/route-targets and web-admin's upload page build against
-- these names. The dashboard's daily target = monthly target / working days of the month (dw.v_working_day, V0097),
-- summed over the routes and SKUs in the filter. Only this slice is built; the docs/27 target hook tables of V0005
-- (app.target_set, target_revision, target) stay empty and unread.
-- 1. app.route_monthly_target: one row per route, SKU and month, quantity in the SKU's base unit (sticks, pieces,
--    dozens; docs/34 s4.2), rewritten in place by a later upload (batch_uuid, updated_at, updated_by say which and
--    when; the change history is in the audit log the API writes).
-- 2. app.route_target_batch: one row per committed upload, the idempotency record (same uuid and same content replays,
--    other content is a conflict).

SET lock_timeout = '5s';

CREATE TABLE app.route_monthly_target (
  route_id    bigint NOT NULL REFERENCES app.route(id),
  sku_id      bigint NOT NULL REFERENCES app.sku(id),
  month       date   NOT NULL CHECK (month = date_trunc('month', month)::date),
  qty_base    bigint NOT NULL CHECK (qty_base BETWEEN 0 AND 1000000000),
  batch_uuid  uuid   NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (route_id, sku_id, month)
);
CREATE INDEX route_monthly_target_month ON app.route_monthly_target (month, route_id);
CREATE INDEX route_monthly_target_sku ON app.route_monthly_target (sku_id);
CREATE INDEX route_monthly_target_updated_by ON app.route_monthly_target (updated_by);

CREATE TABLE app.route_target_batch (
  batch_uuid     uuid PRIMARY KEY,
  content_sha256 bytea NOT NULL CHECK (length(content_sha256) = 32),
  row_count      int NOT NULL CHECK (row_count BETWEEN 1 AND 20000),
  committed_by   bigint NOT NULL REFERENCES app.app_user(id),
  committed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX route_target_batch_committed_by ON app.route_target_batch (committed_by);

COMMENT ON TABLE app.route_monthly_target IS 'Monthly sales target of a route for a SKU in base units (D-TARGET-MIN-01); one row per route, SKU and month, rewritten by a later upload.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_monthly_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN app.route_monthly_target.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN app.route_monthly_target.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.route_monthly_target.qty_base IS 'Target quantity for the month in the SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN app.route_monthly_target.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.route_monthly_target.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.route_monthly_target.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.route_monthly_target.updated_by IS 'User whose upload last wrote the row.';
COMMENT ON TABLE app.route_target_batch IS 'One committed route-target upload: the idempotency record (same uuid and content replays, other content conflicts).
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_target_batch.batch_uuid IS 'Client-generated UUID of the upload.';
COMMENT ON COLUMN app.route_target_batch.content_sha256 IS 'SHA-256 of the upload content, to tell a replay from a conflicting reuse of the uuid.';
COMMENT ON COLUMN app.route_target_batch.row_count IS 'Number of target rows in the upload.';
COMMENT ON COLUMN app.route_target_batch.committed_by IS 'User who committed the upload, from the token.';
COMMENT ON COLUMN app.route_target_batch.committed_at IS 'UTC instant the upload was committed.';

SELECT app.apply_db_role_grants();
