-- V0005 config model, calendar, business code lists and targets (row N-005, schema v1a).
-- docs/24 s9 (config), s12.1, s12.5 item 2 (targets as data); contract ConfigKey, ConfigValue, ConfigChange,
-- ConfigVersion, Holiday, CodeList, Target. Tables are owned by backend:config / backend:masterdata; DDL by the db lane.
-- cfg_ack (from config_ack records) is a device-originated table and lives in V0007.

-- The key registry (s9.1). Seeded by V0006 from docs/24 s9.5; a key is never deleted (retired_at).
CREATE TABLE app.cfg_key (
  key               text PRIMARY KEY CHECK (key ~ '^cfg\.[a-z0-9_]+(\.[a-z0-9_]+)+$'),
  area              text NOT NULL CHECK (area ~ '^[a-z]+$'),
  kind              text NOT NULL DEFAULT 'S' CHECK (kind IN ('S','T','O')),
  value_type        text NOT NULL CHECK (value_type IN ('int','number','bool','time','text','url','pct','money_mtk','enum','list','json')),
  default_value     jsonb,                            -- JSON null = no value (for example cfg.ops.maintenance_banner)
  bounds            jsonb NOT NULL DEFAULT '{}'::jsonb,   -- ConfigBounds: min, max, enum, max_items, dynamic_min, dynamic_max
  bounds_rule       text,                             -- bound the contract shape cannot express (time ranges, rules), enforced by backend:config
  scope_levels      text[] NOT NULL CHECK (cardinality(scope_levels) >= 1 AND scope_levels <@
                      ARRAY['global','role','wing','division','territory','geo_class','zone','route','outlet','user','device']::text[]),
  risk_class        smallint NOT NULL CHECK (risk_class BETWEEN 0 AND 3),
  risk_rule         text,                             -- escalation rule as written in s9.5 (for example radius classes per level)
  effect            text NOT NULL CHECK (effect IN ('B','S','R')),
  delivery          text NOT NULL CHECK (delivery IN ('server','device','both')),
  requires_ack      boolean NOT NULL DEFAULT false,
  future_dated_only boolean NOT NULL DEFAULT false,
  restrictive_dir   text NOT NULL DEFAULT 'none' CHECK (restrictive_dir IN ('up','down','enum_order','none')),
  editor_permission text NOT NULL CHECK (editor_permission ~ '^cfg\.edit\.[a-z]+$'),
  description_en    text NOT NULL CHECK (length(description_en) <= 500),
  description_bn    text CHECK (length(description_bn) <= 500),
  retired_at        timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- One row per committed change set; global and monotonic (D24-14). No row yet = version 0 (registry defaults only).
CREATE TABLE app.cfg_version (
  config_version  bigint PRIMARY KEY CHECK (config_version >= 1),
  kind            text NOT NULL CHECK (kind IN ('change','revert','rollback','schedule_apply','expiry','content')),
  change_id       bigint,                            -- FK added below
  committed_at    timestamptz NOT NULL DEFAULT now(),
  committed_by    bigint NOT NULL REFERENCES app.app_user(id),
  summary         text NOT NULL CHECK (length(summary) <= 500),
  max_risk_class  smallint NOT NULL DEFAULT 0 CHECK (max_risk_class BETWEEN 0 AND 3),
  is_revert_of    bigint REFERENCES app.cfg_version(config_version)
);

CREATE TABLE app.cfg_change (
  change_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  status         text NOT NULL CHECK (status IN ('pending_approval','scheduled','applied','rejected','cancelled','expired','reverted')),
  items          jsonb NOT NULL CHECK (jsonb_typeof(items) = 'array'),   -- ConfigChangeItem[] with old_value
  reason         text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  risk_class     smallint NOT NULL CHECK (risk_class BETWEEN 0 AND 3),
  requested_by   bigint NOT NULL REFERENCES app.app_user(id),
  requested_at   timestamptz NOT NULL DEFAULT now(),
  approver       bigint REFERENCES app.app_user(id),
  approved_at    timestamptz,
  apply_at       timestamptz,
  decided_at     timestamptz,
  decision_note  text,
  config_version bigint,
  is_revert_of   bigint,
  blast_radius   jsonb NOT NULL DEFAULT '{"zones":0,"routes":0,"outlets":0,"devices":0}'::jsonb,
  client_uuid    uuid UNIQUE,                         -- idempotent create from the portal
  CHECK (approver IS NULL OR approver <> requested_by)          -- maker-checker (ERR_CFG_SELF_APPROVAL, s8.6)
);
CREATE INDEX ON app.cfg_change (status, apply_at);
ALTER TABLE app.cfg_version ADD CONSTRAINT cfg_version_change_fk FOREIGN KEY (change_id) REFERENCES app.cfg_change(change_id);
ALTER TABLE app.cfg_change ADD CONSTRAINT cfg_change_version_fk FOREIGN KEY (config_version) REFERENCES app.cfg_version(config_version);

-- Scoped values. scope_id is 0 for global; for role it is app.role_def.ordinal, for geo_class
-- app.geo_class_def.ordinal, otherwise the node id. Rows are closed (effective_to), never updated in place; the
-- exclusion constraint allows at most one row per key and scope valid at any instant (s9.1).
CREATE TABLE app.cfg_value (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key                    text NOT NULL REFERENCES app.cfg_key(key),
  scope_type             text NOT NULL CHECK (scope_type IN ('global','role','wing','division','territory','geo_class',
                                                             'zone','route','outlet','user','device')),
  scope_id               bigint NOT NULL DEFAULT 0 CHECK (scope_id >= 0),
  value                  jsonb NOT NULL,
  effective_from         timestamptz NOT NULL,
  effective_to           timestamptz,                -- exclusive; null = open
  config_version         bigint NOT NULL REFERENCES app.cfg_version(config_version),
  superseded_in_version  bigint REFERENCES app.cfg_version(config_version),
  change_id              bigint REFERENCES app.cfg_change(change_id),
  created_by             bigint REFERENCES app.app_user(id),
  created_at             timestamptz NOT NULL DEFAULT now(),
  reason                 text NOT NULL CHECK (length(reason) <= 500),
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  CHECK ((scope_type = 'global') = (scope_id = 0)),
  CONSTRAINT cfg_value_one_valid EXCLUDE USING gist
    (key WITH =, scope_type WITH =, scope_id WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&)
);
CREATE INDEX ON app.cfg_value (config_version);
CREATE INDEX ON app.cfg_value (scope_type, scope_id);

-- Only the closing columns of a value row may change; everything else is immutable (rows are closed, never edited).
CREATE FUNCTION app.cfg_value_close_only() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'app.cfg_value rows are closed with effective_to, never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (NEW.key, NEW.scope_type, NEW.scope_id, NEW.value, NEW.effective_from, NEW.config_version, NEW.change_id, NEW.created_by, NEW.created_at, NEW.reason)
     IS DISTINCT FROM
     (OLD.key, OLD.scope_type, OLD.scope_id, OLD.value, OLD.effective_from, OLD.config_version, OLD.change_id, OLD.created_by, OLD.created_at, OLD.reason) THEN
    RAISE EXCEPTION 'app.cfg_value: only effective_to and superseded_in_version may change' USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cfg_value_immutable BEFORE UPDATE OR DELETE ON app.cfg_value FOR EACH ROW EXECUTE FUNCTION app.cfg_value_close_only();

-- A value may only sit on a level the key allows (ERR_CFG_SCOPE_NOT_ALLOWED at the API; this is the backstop).
CREATE FUNCTION app.cfg_value_scope_allowed() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.cfg_key WHERE key = NEW.key AND NEW.scope_type = ANY (scope_levels)) THEN
    RAISE EXCEPTION 'config key % does not allow scope %', NEW.key, NEW.scope_type USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cfg_value_scope BEFORE INSERT ON app.cfg_value FOR EACH ROW EXECUTE FUNCTION app.cfg_value_scope_allowed();

-- ---------- calendar ----------
CREATE TABLE app.calendar_holiday (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  date         date NOT NULL,
  scope_type   text NOT NULL CHECK (scope_type IN ('global','wing','division','territory','zone')),
  scope_id     bigint NOT NULL DEFAULT 0 CHECK (scope_id >= 0),
  kind         text NOT NULL CHECK (kind IN ('holiday','makeup_day','emergency_off')),
  selling_day  boolean NOT NULL,
  name_en      text NOT NULL CHECK (length(name_en) BETWEEN 2 AND 120),
  name_bn      text CHECK (length(name_bn) <= 120),
  reason       text,
  declared_at  timestamptz NOT NULL DEFAULT now(),
  created_by   bigint REFERENCES app.app_user(id),
  revoked_at   timestamptz,
  revoked_by   bigint REFERENCES app.app_user(id),
  CHECK ((scope_type = 'global') = (scope_id = 0)),
  CHECK (selling_day = (kind = 'makeup_day'))
);
CREATE UNIQUE INDEX calendar_holiday_one ON app.calendar_holiday (date, scope_type, scope_id) WHERE revoked_at IS NULL;

-- ---------- business code lists (codes are immutable, retired by valid_to) ----------
CREATE TABLE app.code_list (
  list_key    text PRIMARY KEY CHECK (list_key IN ('force_reason','edit_reason','void_reason','visit_outcome','skip_reason',
                                                    'day_exception_reason','stock_variance_reason','task_type','leave_type',
                                                    'feedback_category','qc_fault_type','payment_mode','outlet_close_reason',
                                                    'submit_void_reason')),
  description text,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app.code_list (list_key) VALUES
  ('force_reason'), ('edit_reason'), ('void_reason'), ('visit_outcome'), ('skip_reason'), ('day_exception_reason'),
  ('stock_variance_reason'), ('task_type'), ('leave_type'), ('feedback_category'), ('qc_fault_type'), ('payment_mode'),
  ('outlet_close_reason'), ('submit_void_reason');

CREATE TABLE app.code_list_item (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  list_key    text NOT NULL REFERENCES app.code_list(list_key),
  code        text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,40}$'),
  label_en    text NOT NULL CHECK (length(label_en) <= 120),
  label_bn    text CHECK (length(label_bn) <= 120),
  sort        int NOT NULL DEFAULT 0,
  attrs       jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attrs) = 'object'),
  valid_from  date NOT NULL DEFAULT '2026-01-01',
  valid_to    date,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (list_key, code),
  CHECK (valid_to IS NULL OR valid_to > valid_from)
);
-- A code never changes or disappears: only labels, sort, attrs and valid_to may be edited.
CREATE FUNCTION app.code_list_item_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'code list items are retired with valid_to, never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.list_key <> OLD.list_key OR NEW.code <> OLD.code OR NEW.valid_from <> OLD.valid_from THEN
    RAISE EXCEPTION 'code list item codes are immutable' USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.updated_at := now();
  IF NEW.version = OLD.version THEN NEW.version := OLD.version + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER code_list_item_guard BEFORE UPDATE OR DELETE ON app.code_list_item FOR EACH ROW EXECUTE FUNCTION app.code_list_item_guard();

-- ---------- targets as data (s12.5 item 2): set > revision > target rows ----------
CREATE TABLE app.target_set (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  month       date NOT NULL UNIQUE CHECK (extract(day FROM month) = 1),   -- first day of the month
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  bigint REFERENCES app.app_user(id)
);

CREATE TABLE app.target_revision (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  target_set_id bigint NOT NULL REFERENCES app.target_set(id),
  revision_no   int NOT NULL CHECK (revision_no >= 1),
  batch_uuid    uuid NOT NULL UNIQUE,                 -- TargetBatchWrite.batch_uuid: a replay returns the first result
  source        text NOT NULL DEFAULT 'admin' CHECK (source IN ('admin','excel','migration','engine')),
  change_reason text,
  result        jsonb,                                -- stored TargetBatchResult for replays
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    bigint REFERENCES app.app_user(id),
  UNIQUE (target_set_id, revision_no)
);

-- Live targets: one row per scope and product for a month; a revision that changes a row closes it
-- (superseded_by_revision_id) and inserts the new one, so history is kept.
CREATE TABLE app.target (
  id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  target_set_id            bigint NOT NULL REFERENCES app.target_set(id),
  revision_id              bigint NOT NULL REFERENCES app.target_revision(id),
  month                    date NOT NULL CHECK (extract(day FROM month) = 1),
  scope_type               text NOT NULL CHECK (scope_type IN ('route','zone')),
  scope_id                 bigint NOT NULL,
  product_level            text NOT NULL CHECK (product_level IN ('category','brand','variant','sku')),
  product_id               bigint NOT NULL,
  std_target               numeric(16,3) NOT NULL CHECK (std_target >= 0),
  std_unit                 text NOT NULL CHECK (std_unit IN ('stick','piece','dozen')),
  memo_target              int NOT NULL CHECK (memo_target >= 0),
  source                   text NOT NULL DEFAULT 'admin' CHECK (source IN ('admin','excel','migration')),
  superseded_by_revision_id bigint REFERENCES app.target_revision(id),
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX target_live ON app.target (month, scope_type, scope_id, product_level, product_id)
  WHERE superseded_by_revision_id IS NULL;
CREATE INDEX ON app.target (scope_type, scope_id, month);
