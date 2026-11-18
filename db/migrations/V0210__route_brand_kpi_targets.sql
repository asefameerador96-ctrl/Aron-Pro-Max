-- V0210 (FINAL number, renumbered from V0230 by the lead on 2026-11-18; lane sr-targets-server)
-- N-5 brand-wise KPI of the SR app (screens 60 and 61, D-OWNER-SR-PROGRAMMES-01; design docs/status/sr-targets-server.md s3, D-TKPI-05).
-- The volume target per brand is NOT stored here: it is the sum of app.route_monthly_target over the brand's SKUs (one source, so screen 10
-- and screen 61 cannot disagree). What did not exist is the memo target and the allocated KPI money:
-- 1. app.route_brand_kpi_target: per route, brand (a product_node of level brand) and month: the memo target (number of memos) and the
--    brand's allocated KPI in milli-taka.
-- 2. app.route_kpi_month: per route and month: the allocated KPI of the month card, and the CPR block (target in basis points, its allocation).
-- The dw.v_* reuse views of both tables are NOT in this migration: a new dw view raises dw.view_contract_version() and the contract history of
-- DwViewContractTest, which several lanes contend for; they follow in one migration when the lead orders the contract bumps (D-TKPI-05).
-- Both are written by the admin upload (PUT /v1/admin/route-kpi-targets) under the same idempotency record as the route target upload
-- (app.route_target_batch: one uuid, one content hash). Nothing here computes "achieved KPI" or "KPI today": the owner has not defined them
-- (docs/status/sr-targets-server.md Q-F), so they are null on the wire.

SET lock_timeout = '5s';

CREATE TABLE app.route_brand_kpi_target (
  route_id      bigint NOT NULL REFERENCES app.route(id),
  brand_id      bigint NOT NULL REFERENCES app.product_node(id),
  month         date   NOT NULL CHECK (extract(day FROM month) = 1),
  memo_target   int    NOT NULL CHECK (memo_target BETWEEN 0 AND 1000000),
  kpi_alloc_mtk bigint NOT NULL DEFAULT 0 CHECK (kpi_alloc_mtk BETWEEN 0 AND 100000000000),
  batch_uuid    uuid   NOT NULL REFERENCES app.route_target_batch(batch_uuid),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    bigint REFERENCES app.app_user(id),
  PRIMARY KEY (route_id, brand_id, month)
);
CREATE INDEX route_brand_kpi_target_month ON app.route_brand_kpi_target (month, route_id);
CREATE INDEX route_brand_kpi_target_brand ON app.route_brand_kpi_target (brand_id);
CREATE INDEX route_brand_kpi_target_updated_by ON app.route_brand_kpi_target (updated_by);
CREATE INDEX route_brand_kpi_target_batch ON app.route_brand_kpi_target (batch_uuid);

CREATE TABLE app.route_kpi_month (
  route_id      bigint NOT NULL REFERENCES app.route(id),
  month         date   NOT NULL CHECK (extract(day FROM month) = 1),
  kpi_alloc_mtk bigint NOT NULL DEFAULT 0 CHECK (kpi_alloc_mtk BETWEEN 0 AND 100000000000),
  cpr_target_bp int    CHECK (cpr_target_bp BETWEEN 0 AND 10000),
  cpr_alloc_mtk bigint CHECK (cpr_alloc_mtk BETWEEN 0 AND 100000000000),
  batch_uuid    uuid   NOT NULL REFERENCES app.route_target_batch(batch_uuid),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  updated_by    bigint REFERENCES app.app_user(id),
  PRIMARY KEY (route_id, month)
);
CREATE INDEX route_kpi_month_month ON app.route_kpi_month (month);
CREATE INDEX route_kpi_month_updated_by ON app.route_kpi_month (updated_by);
CREATE INDEX route_kpi_month_batch ON app.route_kpi_month (batch_uuid);

COMMENT ON TABLE app.route_brand_kpi_target IS 'Memo target and allocated KPI of a route for a brand (product_node level brand) and month; the volume target is the sum of app.route_monthly_target over the brand''s SKUs (D-TKPI-05).
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_brand_kpi_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN app.route_brand_kpi_target.brand_id IS 'Brand: an app.product_node of level brand (checked by the upload API).';
COMMENT ON COLUMN app.route_brand_kpi_target.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.route_brand_kpi_target.memo_target IS 'Number of memos containing the brand the route should reach in the month.';
COMMENT ON COLUMN app.route_brand_kpi_target.kpi_alloc_mtk IS 'KPI money allocated to the brand for the month, integer milli-taka.';
COMMENT ON COLUMN app.route_brand_kpi_target.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.route_brand_kpi_target.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.route_brand_kpi_target.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.route_brand_kpi_target.updated_by IS 'User whose upload last wrote the row.';
COMMENT ON TABLE app.route_kpi_month IS 'Month card of the SR KPI dashboard per route: allocated KPI and the CPR (call productivity) target; optional row.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_kpi_month.route_id IS 'Route (app.route).';
COMMENT ON COLUMN app.route_kpi_month.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.route_kpi_month.kpi_alloc_mtk IS 'Total KPI money allocated to the route for the month, integer milli-taka.';
COMMENT ON COLUMN app.route_kpi_month.cpr_target_bp IS 'CPR target in basis points (5000 = 50.00%); null when none is set.';
COMMENT ON COLUMN app.route_kpi_month.cpr_alloc_mtk IS 'KPI money allocated to CPR, integer milli-taka; null when none is set.';
COMMENT ON COLUMN app.route_kpi_month.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.route_kpi_month.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.route_kpi_month.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.route_kpi_month.updated_by IS 'User whose upload last wrote the row.';
SELECT app.apply_db_role_grants();
