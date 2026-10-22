-- V0094 route x SKU monthly targets: the minimum target slice (owner directive 2026-10-22, docs/34 s2, DECISIONS
-- D-TARGET-MIN-01). The dashboard's sales rings and "i" pop-ups need a daily target = monthly target / working days of
-- the month (Fridays and holidays excluded, dw.v_calendar, V0095), summed over the routes and SKUs in the filter.
-- Only this slice is built; target reports, approvals, SKU target screens and achievement programmes stay deferred
-- (docs/27), and the older target hook tables of V0005 (app.target_set, target_revision, target) stay empty and unread.
-- 1. app.route_monthly_target_upload: one row per upload (admin page or Excel import), idempotent by upload_uuid (a
--    replayed upload returns the first result). An upload covers one month.
-- 2. app.route_monthly_target: one live row per month, route and SKU, quantity in the SKU's base unit (sticks, pieces,
--    dozens; docs/34 s4.2: storage stays in base units). A later upload that names the same month, route and SKU
--    supersedes the live row (superseded_by_upload_id is written once) and inserts the new one, so history is kept.
--    Rows are never updated otherwise and never deleted.
-- The stable read view with category and working days, dw.v_route_monthly_target, comes with the calendar view (V0095).

SET lock_timeout = '5s';

CREATE TABLE app.route_monthly_target_upload (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  upload_uuid  uuid NOT NULL UNIQUE,
  month        date NOT NULL CHECK (extract(day FROM month) = 1),
  source       text NOT NULL CHECK (source IN ('admin', 'excel')),
  file_name    text CHECK (length(file_name) <= 200),
  row_count    int NOT NULL CHECK (row_count >= 0),
  result       jsonb CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
  note         text CHECK (length(note) <= 500),
  created_by   bigint NOT NULL REFERENCES app.app_user(id),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX route_monthly_target_upload_month ON app.route_monthly_target_upload (month);
CREATE INDEX route_monthly_target_upload_created_by ON app.route_monthly_target_upload (created_by);

CREATE TABLE app.route_monthly_target (
  id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  month                   date NOT NULL CHECK (extract(day FROM month) = 1),
  route_id                bigint NOT NULL REFERENCES app.route(id),
  sku_id                  bigint NOT NULL REFERENCES app.sku(id),
  qty_base                bigint NOT NULL CHECK (qty_base BETWEEN 0 AND 1000000000000),
  upload_id               bigint NOT NULL REFERENCES app.route_monthly_target_upload(id),
  superseded_by_upload_id bigint REFERENCES app.route_monthly_target_upload(id),
  created_at              timestamptz NOT NULL DEFAULT now(),
  CHECK (superseded_by_upload_id IS NULL OR superseded_by_upload_id <> upload_id)
);
CREATE UNIQUE INDEX route_monthly_target_live ON app.route_monthly_target (month, route_id, sku_id)
  WHERE superseded_by_upload_id IS NULL;
CREATE INDEX route_monthly_target_route ON app.route_monthly_target (route_id, month);
CREATE INDEX route_monthly_target_sku ON app.route_monthly_target (sku_id);
CREATE INDEX route_monthly_target_upload_id ON app.route_monthly_target (upload_id);
CREATE INDEX route_monthly_target_superseded_by ON app.route_monthly_target (superseded_by_upload_id);

-- The month of a row equals its upload's month; only superseded_by_upload_id may change, once, from null.
CREATE FUNCTION app.route_monthly_target_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NOT EXISTS (SELECT 1 FROM app.route_monthly_target_upload u WHERE u.id = NEW.upload_id AND u.month = NEW.month) THEN
      RAISE EXCEPTION 'route_monthly_target: month % differs from the month of upload %', NEW.month, NEW.upload_id
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.superseded_by_upload_id IS NOT NULL THEN
      RAISE EXCEPTION 'route_monthly_target: a new row is live' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.superseded_by_upload_id IS NOT NULL
     OR (to_jsonb(NEW) - 'superseded_by_upload_id') <> (to_jsonb(OLD) - 'superseded_by_upload_id') THEN
    RAISE EXCEPTION 'route_monthly_target: only superseded_by_upload_id may be set, once' USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER route_monthly_target_guard BEFORE INSERT OR UPDATE ON app.route_monthly_target
  FOR EACH ROW EXECUTE FUNCTION app.route_monthly_target_guard();
CREATE TRIGGER route_monthly_target_no_delete BEFORE DELETE ON app.route_monthly_target
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER route_monthly_target_no_truncate BEFORE TRUNCATE ON app.route_monthly_target
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER route_monthly_target_upload_immutable BEFORE UPDATE OR DELETE ON app.route_monthly_target_upload
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

COMMENT ON TABLE app.route_monthly_target_upload IS 'One upload of route x SKU monthly targets (admin page or Excel import), idempotent by upload_uuid; the minimum target slice of D-TARGET-MIN-01.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_monthly_target_upload.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.route_monthly_target_upload.upload_uuid IS 'Client-generated UUID of the upload; a replay returns the stored result.';
COMMENT ON COLUMN app.route_monthly_target_upload.month IS 'Target month (first day of the month).';
COMMENT ON COLUMN app.route_monthly_target_upload.source IS 'admin (typed on the page) or excel (file import).';
COMMENT ON COLUMN app.route_monthly_target_upload.file_name IS 'Name of the imported file, for the operator; null when typed.';
COMMENT ON COLUMN app.route_monthly_target_upload.row_count IS 'Number of target rows the upload wrote.';
COMMENT ON COLUMN app.route_monthly_target_upload.result IS 'Stored upload result (counts, rejected lines) returned on a replay.';
COMMENT ON COLUMN app.route_monthly_target_upload.note IS 'Free note of the uploader.';
COMMENT ON COLUMN app.route_monthly_target_upload.created_by IS 'User who uploaded, from the token.';
COMMENT ON COLUMN app.route_monthly_target_upload.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON TABLE app.route_monthly_target IS 'Monthly sales target of a route for a SKU in base units; one live row per month, route and SKU, older rows kept as superseded.
owner: backend:admin | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.route_monthly_target.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.route_monthly_target.month IS 'Target month (first day of the month); equals the upload''s month.';
COMMENT ON COLUMN app.route_monthly_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN app.route_monthly_target.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN app.route_monthly_target.qty_base IS 'Target quantity for the month in the SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN app.route_monthly_target.upload_id IS 'Upload that wrote the row.';
COMMENT ON COLUMN app.route_monthly_target.superseded_by_upload_id IS 'Later upload that replaced the row; null while the row is live. Written once.';
COMMENT ON COLUMN app.route_monthly_target.created_at IS 'UTC instant the row was inserted on the server.';

SELECT app.apply_db_role_grants();
