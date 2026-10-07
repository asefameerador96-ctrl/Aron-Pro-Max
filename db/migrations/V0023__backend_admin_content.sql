-- V0023 back-office content and price batches for the backend-admin rows (F-API-027, 030, 032, 035a, F-API-080,
-- F-ADM-081): uploaded admin assets, tutorials, survey and rubric definitions with immutable published versions,
-- print templates, PDA-to-Support uploads, the inbox status of feedback, and the price batch of the maker-checker
-- price publish (docs/24 s12.1). Shapes are the ones backend-masterdata already codes against
-- (docs/requests/backend-admin-content-tables.md, docs/requests/backend-admin-price-batch-table.md); they stay
-- compatible with that lane's idempotent test DDL (CREATE ... IF NOT EXISTS there is then a no-op).
-- Also repairs the V0007 leave-decision defect.

SET lock_timeout = '5s';

-- ---------- uploaded admin assets (POST /v1/admin/assets), idempotent by asset_id ----------
CREATE TABLE app.admin_asset (
  asset_id    uuid PRIMARY KEY,
  purpose     text NOT NULL CHECK (purpose IN ('content_av','content_kv','tutorial_video','tutorial_manual','sku_image','gift_image')),
  mime        text NOT NULL CHECK (mime IN ('video/mp4','image/jpeg','image/png','application/pdf')),
  bytes       bigint NOT NULL CHECK (bytes BETWEEN 1 AND 104857600),
  sha256      bytea NOT NULL CHECK (length(sha256) = 32),
  blob_path   text NOT NULL CHECK (length(blob_path) <= 300),
  uploaded_by bigint REFERENCES app.app_user(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- An asset is content-addressed by its hash; its row never changes (a new file is a new asset).
CREATE TRIGGER admin_asset_append_only BEFORE UPDATE OR DELETE ON app.admin_asset
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- AV and KV content gets its uploaded asset and a node-level assignment (expanded to outlet_ids on every write).
-- The column is new and NULL on every row; the FK is added NOT VALID (no table scan under lock) and enforced on every
-- new and changed row; V0024 validates it.
ALTER TABLE app.content_item ADD COLUMN asset_id uuid;
ALTER TABLE app.content_item ADD COLUMN assigned_scope jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE app.content_item ADD CONSTRAINT content_item_asset_id_fkey
  FOREIGN KEY (asset_id) REFERENCES app.admin_asset(asset_id) NOT VALID;
ALTER TABLE app.content_item ADD CONSTRAINT content_item_assigned_scope_array
  CHECK (jsonb_typeof(assigned_scope) = 'array') NOT VALID;

-- ---------- tutorials ----------
-- version is the ETag; the API sets it (version + 1) together with the row, so there is no touch trigger here.
CREATE TABLE app.tutorial (
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

-- ---------- surveys and rubrics: a head row plus one immutable row per published version ----------
-- Answers on the phone carry (survey_id, survey_version) / (rubric_id, rubric_version), so a version row never changes.
CREATE TABLE app.survey (
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
CREATE TABLE app.survey_version (
  survey_id  bigint NOT NULL REFERENCES app.survey(id),
  version    int NOT NULL CHECK (version >= 1),
  title_en   text NOT NULL CHECK (length(title_en) BETWEEN 1 AND 120),
  title_bn   text CHECK (length(title_bn) <= 120),
  questions  jsonb NOT NULL CHECK (jsonb_typeof(questions) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  PRIMARY KEY (survey_id, version)
);
CREATE TRIGGER survey_version_append_only BEFORE UPDATE OR DELETE ON app.survey_version
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

CREATE TABLE app.rubric (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind       text NOT NULL CHECK (kind IN ('joint_call','retailer_questionnaire')),
  version    int NOT NULL DEFAULT 1 CHECK (version >= 1),
  status     text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  updated_by bigint
);
CREATE TABLE app.rubric_version (
  rubric_id  bigint NOT NULL REFERENCES app.rubric(id),
  version    int NOT NULL CHECK (version >= 1),
  criteria   jsonb NOT NULL CHECK (jsonb_typeof(criteria) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  created_by bigint,
  PRIMARY KEY (rubric_id, version)
);
CREATE TRIGGER rubric_version_append_only BEFORE UPDATE OR DELETE ON app.rubric_version
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- ---------- print templates: append-only versions per kind (future-dated by effective_from) ----------
CREATE TABLE app.print_template (
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
CREATE TRIGGER print_template_append_only BEFORE UPDATE OR DELETE ON app.print_template
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- ---------- PDA to Support uploads, idempotent by upload_uuid ----------
CREATE TABLE app.support_upload (
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
CREATE INDEX support_upload_user ON app.support_upload (user_id, created_at);

-- ---------- inbox status of a feedback item (app.feedback is immutable); no row means 'new' ----------
CREATE TABLE app.feedback_status (
  feedback_client_uuid uuid PRIMARY KEY REFERENCES app.feedback(client_uuid),
  status               text NOT NULL CHECK (status IN ('new','in_progress','resolved','closed')),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  updated_by           bigint REFERENCES app.app_user(id)
);

-- ---------- price batches of the maker-checker price publish (F-API-080, F-ADM-081) ----------
-- price_rows holds the submitted rows exactly as previewed (amount_mtk integer milli-taka, per_base_qty); the
-- fingerprint (SHA-256 hex of the canonical rows) ties the publish and the decision to what the maker previewed.
CREATE TABLE app.price_batch (
  batch_uuid         uuid PRIMARY KEY,
  status             text NOT NULL CHECK (status IN ('previewed','pending_approval','published','rejected')),
  valid_from         date NOT NULL,
  fingerprint        text NOT NULL CHECK (length(fingerprint) = 64),
  price_rows         jsonb NOT NULL CHECK (jsonb_typeof(price_rows) = 'array'),
  row_count          int NOT NULL CHECK (row_count BETWEEN 1 AND 1000),
  change_reason      text CHECK (length(change_reason) <= 500),
  backdate           boolean NOT NULL DEFAULT false,
  max_change_pct     numeric(12,2),
  previewed_by       bigint REFERENCES app.app_user(id),
  submitted_by       bigint REFERENCES app.app_user(id),
  submitted_at       timestamptz,
  decided_by         bigint REFERENCES app.app_user(id),
  decided_at         timestamptz,
  decision_note      text CHECK (length(decision_note) <= 500),
  price_list_version bigint,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);
CREATE INDEX price_batch_pending ON app.price_batch (status) WHERE status = 'pending_approval';
-- Terminal states never move back (a published or rejected batch is history).
CREATE TRIGGER price_batch_status_flow BEFORE UPDATE OF status ON app.price_batch
  FOR EACH ROW EXECUTE FUNCTION app.guard_transition('status',
    'previewed>pending_approval', 'previewed>published', 'pending_approval>published', 'pending_approval>rejected');

-- ---------- DEFECT FIX (V0007): leave decisions ----------
-- guard_synced_row compares every non-free column of NEW and OLD, but a stored generated column is NULL in NEW inside
-- a BEFORE UPDATE trigger, so leave_application.to_date always differed and every decision (status update) was refused.
-- to_date joins the free columns; its sources from_date and days are still compared, so it cannot drift. It is the
-- only stored generated column on a guarded table.
CREATE OR REPLACE TRIGGER leave_application_immutable BEFORE UPDATE OR DELETE ON app.leave_application
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', '=decided_by', '=decided_at', '=decision_note', 'to_date');

-- ---------- data dictionary ----------
COMMENT ON TABLE app.admin_asset IS 'One row is a file an admin uploaded (content video or image, tutorial, SKU or gift image), stored in Blob.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.admin_asset.asset_id IS 'Client-generated UUID of the upload; the API is idempotent by it.';
COMMENT ON COLUMN app.admin_asset.purpose IS 'What the asset is for; allowed values are listed under constraints.';
COMMENT ON COLUMN app.admin_asset.mime IS 'MIME type of the file; allowed values are listed under constraints.';
COMMENT ON COLUMN app.admin_asset.bytes IS 'Size of the file in bytes (at most 100 MiB).';
COMMENT ON COLUMN app.admin_asset.sha256 IS 'SHA-256 of the file content (32 bytes).';
COMMENT ON COLUMN app.admin_asset.blob_path IS 'Path of the file in the asset Blob container.';
COMMENT ON COLUMN app.admin_asset.uploaded_by IS 'User who uploaded the file.';
COMMENT ON COLUMN app.admin_asset.created_at IS 'UTC instant the row was inserted on the server.';

COMMENT ON COLUMN app.content_item.asset_id IS 'Uploaded admin asset that holds the file (null for items created before V0023).';
COMMENT ON COLUMN app.content_item.assigned_scope IS 'Geography nodes the item is assigned to, as an array of {scope_type, scope_id}; expanded to outlet_ids on every write.';

COMMENT ON TABLE app.tutorial IS 'One row is a tutorial video or manual shown to the listed roles in the apps and on the web.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.tutorial.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.tutorial.version IS 'Optimistic-concurrency version (ETag); the API raises it by one on every update.';
COMMENT ON COLUMN app.tutorial.kind IS 'Kind of the row; allowed values are listed under constraints.';
COMMENT ON COLUMN app.tutorial.title_en IS 'English title.';
COMMENT ON COLUMN app.tutorial.title_bn IS 'Bangla title.';
COMMENT ON COLUMN app.tutorial.asset_id IS 'Uploaded admin asset holding the video or PDF.';
COMMENT ON COLUMN app.tutorial.roles IS 'Roles that see the tutorial.';
COMMENT ON COLUMN app.tutorial.sort IS 'Display order among the tutorials.';
COMMENT ON COLUMN app.tutorial.status IS 'Lifecycle status; allowed values are listed under constraints.';
COMMENT ON COLUMN app.tutorial.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.tutorial.updated_at IS 'UTC instant of the last update.';
COMMENT ON COLUMN app.tutorial.created_by IS 'User who created the row (null for migrations and jobs).';
COMMENT ON COLUMN app.tutorial.updated_by IS 'User who last updated the row.';

COMMENT ON TABLE app.survey IS 'One row is a survey (POSM, AMO survey or TSO visit query); its current published version is in survey_version.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.survey.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.survey.kind IS 'Kind of the row; allowed values are listed under constraints.';
COMMENT ON COLUMN app.survey.version IS 'Current published version (survey_version.version); also the ETag.';
COMMENT ON COLUMN app.survey.valid_from IS 'First day the row is in effect.';
COMMENT ON COLUMN app.survey.valid_to IS 'Last day the survey is in effect (inclusive); null means open-ended.';
COMMENT ON COLUMN app.survey.points_per_photo IS 'Points per accepted photo (deferred programme hook, docs/27; null when not used).';
COMMENT ON COLUMN app.survey.status IS 'Lifecycle status; allowed values are listed under constraints.';
COMMENT ON COLUMN app.survey.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.survey.updated_at IS 'UTC instant of the last update.';
COMMENT ON COLUMN app.survey.created_by IS 'User who created the row (null for migrations and jobs).';
COMMENT ON COLUMN app.survey.updated_by IS 'User who last updated the row.';

COMMENT ON TABLE app.survey_version IS 'One row is an immutable published version of a survey: titles and questions; answers reference it.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.survey_version.survey_id IS 'Survey the version belongs to.';
COMMENT ON COLUMN app.survey_version.version IS 'Version number, from 1 upwards per survey.';
COMMENT ON COLUMN app.survey_version.title_en IS 'English title.';
COMMENT ON COLUMN app.survey_version.title_bn IS 'Bangla title.';
COMMENT ON COLUMN app.survey_version.questions IS 'Array of questions (id, type, English and Bangla text, options).';
COMMENT ON COLUMN app.survey_version.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.survey_version.created_by IS 'User who published the version.';

COMMENT ON TABLE app.rubric IS 'One row is a scoring rubric (joint call or retailer questionnaire); its current version is in rubric_version.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.rubric.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.rubric.kind IS 'Kind of the row; allowed values are listed under constraints.';
COMMENT ON COLUMN app.rubric.version IS 'Current published version (rubric_version.version); also the ETag.';
COMMENT ON COLUMN app.rubric.status IS 'Lifecycle status; allowed values are listed under constraints.';
COMMENT ON COLUMN app.rubric.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.rubric.updated_at IS 'UTC instant of the last update.';
COMMENT ON COLUMN app.rubric.created_by IS 'User who created the row (null for migrations and jobs).';
COMMENT ON COLUMN app.rubric.updated_by IS 'User who last updated the row.';

COMMENT ON TABLE app.rubric_version IS 'One row is an immutable published version of a rubric: its scored criteria; assessments reference it.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.rubric_version.rubric_id IS 'Rubric the version belongs to.';
COMMENT ON COLUMN app.rubric_version.version IS 'Version number, from 1 upwards per rubric.';
COMMENT ON COLUMN app.rubric_version.criteria IS 'Array of criteria (id, English and Bangla text, maximum score).';
COMMENT ON COLUMN app.rubric_version.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.rubric_version.created_by IS 'User who published the version.';

COMMENT ON TABLE app.print_template IS 'One row is an immutable version of a thermal-print template for one slip kind, in force from effective_from.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.print_template.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.print_template.kind IS 'Slip the template prints; allowed values are listed under constraints.';
COMMENT ON COLUMN app.print_template.version IS 'Template version per kind (cfg.print.template_version selects it).';
COMMENT ON COLUMN app.print_template.font_columns IS 'Characters per line of the 58 mm printer font (32 or 42).';
COMMENT ON COLUMN app.print_template.template_json IS 'Template definition as JSON text (at most 20000 characters).';
COMMENT ON COLUMN app.print_template.effective_from IS 'First Asia/Dhaka business date the version is in force.';
COMMENT ON COLUMN app.print_template.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.print_template.created_by IS 'User who created the version.';

COMMENT ON TABLE app.support_upload IS 'One row is a phone database export a field user sent to Support (PDA to Support); the file is in Blob.
owner: backend:masterdata | capture: OFFLINE | retention: ops | pii: none';
COMMENT ON COLUMN app.support_upload.upload_uuid IS 'Client-generated UUID of the upload; the API is idempotent by it.';
COMMENT ON COLUMN app.support_upload.user_id IS 'Database id of the user who sent the export, from the token.';
COMMENT ON COLUMN app.support_upload.device_uuid IS 'device_uuid of the phone that sent the export.';
COMMENT ON COLUMN app.support_upload.bytes IS 'Size of the export in bytes (at most 100 MiB).';
COMMENT ON COLUMN app.support_upload.sha256 IS 'SHA-256 of the export file (32 bytes).';
COMMENT ON COLUMN app.support_upload.app_version IS 'App version name on the phone at export.';
COMMENT ON COLUMN app.support_upload.last_sync_at IS 'UTC time of the phone''s last successful sync, as reported by the phone.';
COMMENT ON COLUMN app.support_upload.pending_rows IS 'Number of rows still in the phone''s outbox at export.';
COMMENT ON COLUMN app.support_upload.blob_path IS 'Path of the export in the support Blob container (access restricted to Support).';
COMMENT ON COLUMN app.support_upload.created_at IS 'UTC instant the row was inserted on the server.';

COMMENT ON TABLE app.feedback_status IS 'One row is the support-inbox status of a feedback item; a feedback item without a row is new.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.feedback_status.feedback_client_uuid IS 'client_uuid of the feedback item.';
COMMENT ON COLUMN app.feedback_status.status IS 'Inbox status; allowed values are listed under constraints.';
COMMENT ON COLUMN app.feedback_status.updated_at IS 'UTC instant of the last update.';
COMMENT ON COLUMN app.feedback_status.updated_by IS 'User who last set the status.';

COMMENT ON TABLE app.price_batch IS 'One row is a batch of price changes through preview, approval and publish (maker-checker above the change threshold).
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.price_batch.batch_uuid IS 'Client-generated UUID of the batch; preview, publish and decision are idempotent by it.';
COMMENT ON COLUMN app.price_batch.status IS 'Batch state; allowed values and moves are listed under constraints and the status-flow trigger.';
COMMENT ON COLUMN app.price_batch.valid_from IS 'First Asia/Dhaka business date the new prices are in force.';
COMMENT ON COLUMN app.price_batch.fingerprint IS 'Lower-case hex SHA-256 of the canonical price rows, so a publish matches what was previewed.';
COMMENT ON COLUMN app.price_batch.price_rows IS 'The price rows of the batch (sku_id, price_type, amount_mtk in integer milli-taka, per_base_qty).';
COMMENT ON COLUMN app.price_batch.row_count IS 'Number of price rows in the batch (1 to 1000).';
COMMENT ON COLUMN app.price_batch.change_reason IS 'Reason the maker gave for the change.';
COMMENT ON COLUMN app.price_batch.backdate IS 'True when valid_from is before the business date of the publish.';
COMMENT ON COLUMN app.price_batch.max_change_pct IS 'Largest per-row price move in percent, two decimals; display only, the threshold decision is exact integer arithmetic.';
COMMENT ON COLUMN app.price_batch.previewed_by IS 'User who previewed the batch.';
COMMENT ON COLUMN app.price_batch.submitted_by IS 'User who submitted the batch for publish (the maker).';
COMMENT ON COLUMN app.price_batch.submitted_at IS 'UTC time of the submission.';
COMMENT ON COLUMN app.price_batch.decided_by IS 'User who approved or rejected the batch (the checker; never the maker).';
COMMENT ON COLUMN app.price_batch.decided_at IS 'UTC time of the decision.';
COMMENT ON COLUMN app.price_batch.decision_note IS 'Note the checker gave with the decision.';
COMMENT ON COLUMN app.price_batch.price_list_version IS 'Price-list version the publish produced (null until published).';
COMMENT ON COLUMN app.price_batch.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.price_batch.updated_at IS 'UTC instant of the last update.';

SELECT app.apply_db_role_grants();
