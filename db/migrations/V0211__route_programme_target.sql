-- V0211 (FINAL number, renumbered from V0231 by the lead on 2026-11-18; lane sr-targets-server)
-- N-8 Match Box Lifting Star of the SR app (screen 62, D-OWNER-SR-PROGRAMMES-01; design docs/status/sr-targets-server.md s4, D-TKPI-07).
-- app.route_programme_target: the monthly target of a route for a programme (kind superstar), in the programme's unit as an exact decimal.
-- The unit is the base quantity of the programme's SKUs divided by programme.rules.unit_divisor (default 1; owner question Q-H). Slabs, status
-- texts, the SKU category and the divisor live in the existing app.programme.rules jsonb, edited in the programme admin; nothing else is stored.
-- Written by the admin upload (PUT /v1/admin/route-kpi-targets, programme_rows) under the shared idempotency record app.route_target_batch.

SET lock_timeout = '5s';

CREATE TABLE app.route_programme_target (
  programme_id bigint NOT NULL REFERENCES app.programme(id),
  route_id     bigint NOT NULL REFERENCES app.route(id),
  month        date   NOT NULL CHECK (extract(day FROM month) = 1),
  target_qty   numeric(16,3) NOT NULL CHECK (target_qty >= 0),
  batch_uuid   uuid   NOT NULL REFERENCES app.route_target_batch(batch_uuid),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   bigint REFERENCES app.app_user(id),
  PRIMARY KEY (programme_id, route_id, month)
);
CREATE INDEX route_programme_target_route ON app.route_programme_target (route_id, month);
CREATE INDEX route_programme_target_updated_by ON app.route_programme_target (updated_by);
CREATE INDEX route_programme_target_batch ON app.route_programme_target (batch_uuid);

COMMENT ON TABLE app.route_programme_target IS 'Monthly target of a route for a programme (Match Box Lifting Star, kind superstar) in the programme unit as an exact decimal.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_programme_target.programme_id IS 'Programme (app.programme).';
COMMENT ON COLUMN app.route_programme_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN app.route_programme_target.month IS 'Target month: the first day of the Asia/Dhaka month.';
COMMENT ON COLUMN app.route_programme_target.target_qty IS 'Target in the programme unit (base units divided by programme.rules.unit_divisor), up to three decimals.';
COMMENT ON COLUMN app.route_programme_target.batch_uuid IS 'Upload batch that last wrote the row (app.route_target_batch).';
COMMENT ON COLUMN app.route_programme_target.created_at IS 'UTC instant the row was first inserted.';
COMMENT ON COLUMN app.route_programme_target.updated_at IS 'UTC instant of the last upload that wrote the row.';
COMMENT ON COLUMN app.route_programme_target.updated_by IS 'User whose upload last wrote the row.';

SELECT app.apply_db_role_grants();
