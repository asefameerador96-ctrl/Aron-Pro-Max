-- PROPOSED migration for the backend-admin rows (F-API-027, 030, 032, 035a). The lead moves this file to
-- db/migrations/V0014__backend_admin_content.sql (every statement is idempotent so the test loader may apply it first).
-- Master-style rows: created_at, updated_at, created_by, updated_by; `version` is the ETag.

-- Uploaded admin assets (POST /v1/admin/assets); idempotent by asset_id.
CREATE TABLE IF NOT EXISTS app.admin_asset (
  asset_id    uuid PRIMARY KEY,
  purpose     text NOT NULL CHECK (purpose IN ('content_av','content_kv','tutorial_video','tutorial_manual','sku_image','gift_image')),
  mime        text NOT NULL CHECK (mime IN ('video/mp4','image/jpeg','image/png','application/pdf')),
  bytes       bigint NOT NULL CHECK (bytes BETWEEN 1 AND 104857600),
  sha256      bytea NOT NULL CHECK (length(sha256) = 32),
  blob_path   text NOT NULL CHECK (length(blob_path) <= 300),
  uploaded_by bigint REFERENCES app.app_user(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- AV and KV content gets the uploaded asset and a node-level assignment (expanded to outlet_ids on every write).
ALTER TABLE app.content_item ADD COLUMN IF NOT EXISTS asset_id uuid REFERENCES app.admin_asset(asset_id);
ALTER TABLE app.content_item ADD COLUMN IF NOT EXISTS assigned_scope jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS app.tutorial (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version    int NOT NULL DEFAULT 1 CHECK (version >= 1),
  kind       text NOT NULL CHECK (kind IN ('video','manual')),
  title_en   text NOT NULL CHECK (length(title_en) BETWEEN 1 AND 120),
  title_bn   text CHECK (length(title_bn) <= 120),
  asset_id   uuid NOT NULL REFERENCES app.admin_asset(asset_id),
  roles      text[] NOT NULL CHECK (cardinality(roles) >= 1),
  sort       int NOT NULL DEFAULT 0 CHECK (sort >= 0),
  status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  updated_by bigint
);

-- Survey and rubric definitions: one head row, one immutable row per published version (answers keep their version).
CREATE TABLE IF NOT EXISTS app.survey (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind             text NOT NULL CHECK (kind IN ('posm','amo_survey','tso_visit_query')),
  version          int NOT NULL DEFAULT 1 CHECK (version >= 1),
  valid_from       date NOT NULL,
  valid_to         date,
  points_per_photo int CHECK (points_per_photo BETWEEN 0 AND 100000),
  status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  created_by       bigint,
  updated_by       bigint,
  CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE TABLE IF NOT EXISTS app.survey_version (
  survey_id  bigint NOT NULL REFERENCES app.survey(id),
  version    int NOT NULL CHECK (version >= 1),
  title_en   text NOT NULL CHECK (length(title_en) BETWEEN 1 AND 120),
  title_bn   text CHECK (length(title_bn) <= 120),
  questions  jsonb NOT NULL CHECK (jsonb_typeof(questions) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  PRIMARY KEY (survey_id, version)
);
CREATE TABLE IF NOT EXISTS app.rubric (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind       text NOT NULL CHECK (kind IN ('joint_call','retailer_questionnaire')),
  version    int NOT NULL DEFAULT 1 CHECK (version >= 1),
  status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  updated_by bigint
);
CREATE TABLE IF NOT EXISTS app.rubric_version (
  rubric_id  bigint NOT NULL REFERENCES app.rubric(id),
  version    int NOT NULL CHECK (version >= 1),
  criteria   jsonb NOT NULL CHECK (jsonb_typeof(criteria) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  PRIMARY KEY (rubric_id, version)
);

-- Print templates are append-only versions per kind (future-dated by effective_from).
CREATE TABLE IF NOT EXISTS app.print_template (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind           text NOT NULL CHECK (kind IN ('cash_memo','credit_memo','offer_memo','drp_memo','zero_memo','edited_memo','stock_slip','day_summary','due_receipt','void_slip')),
  version        int NOT NULL CHECK (version BETWEEN 1 AND 999),
  font_columns   int NOT NULL CHECK (font_columns IN (32, 42)),
  template_json  text NOT NULL CHECK (length(template_json) <= 20000),
  effective_from date NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  created_by     bigint,
  UNIQUE (kind, version)
);

-- PDA to Support uploads; idempotent by upload_uuid.
CREATE TABLE IF NOT EXISTS app.support_upload (
  upload_uuid  uuid PRIMARY KEY,
  user_id      bigint NOT NULL REFERENCES app.app_user(id),
  device_uuid  uuid NOT NULL,
  bytes        bigint NOT NULL CHECK (bytes BETWEEN 1 AND 104857600),
  sha256       bytea NOT NULL CHECK (length(sha256) = 32),
  app_version  text NOT NULL CHECK (length(app_version) <= 40),
  last_sync_at timestamptz,
  pending_rows int CHECK (pending_rows >= 0),
  blob_path    text NOT NULL CHECK (length(blob_path) <= 300),
  created_at   timestamptz NOT NULL DEFAULT now()
);

-- Inbox status of a feedback item (app.feedback is immutable); no row means 'new'.
CREATE TABLE IF NOT EXISTS app.feedback_status (
  feedback_client_uuid uuid PRIMARY KEY,
  status               text NOT NULL CHECK (status IN ('new','in_progress','resolved','closed')),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           bigint REFERENCES app.app_user(id)
);

-- DEFECT FIX (V0007): guard_synced_row compares every non-free column of NEW and OLD, but the stored generated column
-- leave_application.to_date is NULL in NEW inside a BEFORE UPDATE trigger, so every leave decision (status update) was
-- refused with "only voided_at, status, ... may change". Listing to_date among the free columns repairs it; the two
-- source columns it derives from (from_date, days) are still compared, so to_date cannot drift.
DROP TRIGGER IF EXISTS leave_application_immutable ON app.leave_application;
CREATE TRIGGER leave_application_immutable BEFORE UPDATE OR DELETE ON app.leave_application
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', '=decided_by', '=decided_at', '=decision_note', 'to_date');
