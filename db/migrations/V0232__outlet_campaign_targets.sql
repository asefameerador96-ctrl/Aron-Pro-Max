-- V0232 (PROVISIONAL number, lane sr-targets-server; the lead renumbers at landing, never run outside lane CI before that)
-- N-3 outlet campaign target of the SR app (screen 21, "Super Star Low (November)", D-OWNER-SR-PROGRAMMES-01; design docs/status/sr-targets-server.md s5, D-TKPI-08).
-- 1. app.outlet_campaign_target: per campaign programme (kind campaign), month and outlet: the STD target (programme unit, exact decimal) and the memo target.
--    An outlet is in the campaign for a month exactly when this row exists (the upload is the membership; no second list to keep in step).
-- 2. app.outlet_campaign_sku_target: per campaign, month, outlet and SKU: the SKU target in base units (the SKU table of the pop-up page).
-- Written by the admin upload (PUT /v1/admin/route-kpi-targets, campaign_rows and campaign_sku_rows) under the shared idempotency record app.route_target_batch.

SET lock_timeout = '5s';

CREATE TABLE app.outlet_campaign_target (
  programme_id bigint NOT NULL REFERENCES app.programme(id),
  month        date   NOT NULL CHECK (extract(day FROM month) = 1),
  outlet_id    bigint NOT NULL REFERENCES app.outlet(id),
  std_target   numeric(16,3) NOT NULL CHECK (std_target >= 0),
  memo_target  int    NOT NULL CHECK (memo_target BETWEEN 0 AND 1000000),
  batch_uuid   uuid   NOT NULL REFERENCES app.route_target_batch(batch_uuid),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   bigint REFERENCES app.app_user(id),
  PRIMARY KEY (programme_id, month, outlet_id)
);
CREATE INDEX outlet_campaign_target_outlet ON app.outlet_campaign_target (outlet_id, month);
CREATE INDEX outlet_campaign_target_updated_by ON app.outlet_campaign_target (updated_by);
CREATE INDEX outlet_campaign_target_batch ON app.outlet_campaign_target (batch_uuid);

CREATE TABLE app.outlet_campaign_sku_target (
  programme_id bigint NOT NULL REFERENCES app.programme(id),
  month        date   NOT NULL CHECK (extract(day FROM month) = 1),
  outlet_id    bigint NOT NULL REFERENCES app.outlet(id),
  sku_id       bigint NOT NULL REFERENCES app.sku(id),
  qty_base     bigint NOT NULL CHECK (qty_base BETWEEN 0 AND 1000000000),
  batch_uuid   uuid   NOT NULL REFERENCES app.route_target_batch(batch_uuid),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   bigint REFERENCES app.app_user(id),
  PRIMARY KEY (programme_id, month, outlet_id, sku_id)
);
CREATE INDEX outlet_campaign_sku_target_outlet ON app.outlet_campaign_sku_target (outlet_id, month);
CREATE INDEX outlet_campaign_sku_target_sku ON app.outlet_campaign_sku_target (sku_id);
CREATE INDEX outlet_campaign_sku_target_updated_by ON app.outlet_campaign_sku_target (updated_by);
CREATE INDEX outlet_campaign_sku_target_batch ON app.outlet_campaign_sku_target (batch_uuid);

COMMENT ON TABLE app.outlet_campaign_target IS 'Campaign target of an outlet for a month: STD target (programme unit) and memo target; the row is the outlet''s membership of the campaign for that month.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.outlet_campaign_target.programme_id IS 'Campaign programme (app.programme, kind campaign).';
COMMENT ON COLUMN app.outlet_campaign_target.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.outlet_campaign_target.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN app.outlet_campaign_target.std_target IS 'STD target of the month, up to three decimals (base units of the campaign SKUs; owner question Q-H).';
COMMENT ON COLUMN app.outlet_campaign_target.memo_target IS 'Number of memos the outlet should place with the campaign SKUs in the month.';
COMMENT ON COLUMN app.outlet_campaign_target.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.outlet_campaign_target.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.outlet_campaign_target.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.outlet_campaign_target.updated_by IS 'User whose upload last wrote the row.';
COMMENT ON TABLE app.outlet_campaign_sku_target IS 'Campaign target of an outlet for a SKU and month in base units (the SKU table of the outlet target page).
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.outlet_campaign_sku_target.programme_id IS 'Campaign programme (app.programme, kind campaign).';
COMMENT ON COLUMN app.outlet_campaign_sku_target.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.outlet_campaign_sku_target.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN app.outlet_campaign_sku_target.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN app.outlet_campaign_sku_target.qty_base IS 'Target quantity in the SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN app.outlet_campaign_sku_target.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.outlet_campaign_sku_target.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.outlet_campaign_sku_target.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.outlet_campaign_sku_target.updated_by IS 'User whose upload last wrote the row.';

SELECT app.apply_db_role_grants();
