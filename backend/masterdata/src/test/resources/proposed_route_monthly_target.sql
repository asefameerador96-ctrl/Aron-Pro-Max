-- PROPOSED migration for D-TARGET-MIN-01 (docs/34 s2, db lane): route x SKU monthly targets in base units.
-- Request: docs/requests/backend-admin-route-target-tables.md. Every statement is idempotent so the test loader may apply it first.
CREATE TABLE IF NOT EXISTS app.route_monthly_target (
  route_id    bigint NOT NULL REFERENCES app.route(id),
  sku_id      bigint NOT NULL REFERENCES app.sku(id),
  month       date   NOT NULL CHECK (month = date_trunc('month', month)::date),   -- first day of the Dhaka month
  qty_base    bigint NOT NULL CHECK (qty_base BETWEEN 0 AND 1000000000),          -- sticks, pieces or dozens
  batch_uuid  uuid   NOT NULL,                                                    -- the upload batch that last wrote the row
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  bigint REFERENCES app.app_user(id),
  PRIMARY KEY (route_id, sku_id, month)
);
CREATE INDEX IF NOT EXISTS route_monthly_target_month ON app.route_monthly_target (month, route_id);

-- One row per committed upload: the idempotency record (same uuid and same content replays, other content is a conflict).
CREATE TABLE IF NOT EXISTS app.route_target_batch (
  batch_uuid     uuid PRIMARY KEY,
  content_sha256 bytea NOT NULL CHECK (length(content_sha256) = 32),
  row_count      int NOT NULL CHECK (row_count BETWEEN 1 AND 20000),
  committed_by   bigint NOT NULL REFERENCES app.app_user(id),
  committed_at   timestamptz NOT NULL DEFAULT now()
);
