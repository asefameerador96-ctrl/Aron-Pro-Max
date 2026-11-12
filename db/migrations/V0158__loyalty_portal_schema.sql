-- V0158 loyalty-programme portal, schema (lane portal-loyalty-api, train 1; docs/status/portal-loyalty-brief.md s6;
-- D-LOY-API-01). The owner's roadmap order (D-LEAD-ROADMAP-02) item 2 is the PORTAL for loyalty programmes; the FIELD is
-- unchanged and stays deferred (docs/27): no earning, no redemption, no ledger posting, no memo line, bundle `programmes` null.
--
-- 1. app.programme.status gains `draft` (draft, active, inactive; `inactive` is what the portal calls "Ended"). The CHECK is
--    replaced NOT VALID here and validated by V0159 (squawk rule; V0152/V0153 pattern). The column default becomes `draft`.
--    app.programme.current_version_id points at the version in force.
-- 2. app.programme_version: one immutable row per rule change (name, period, dates, rules), with app.programme_version_tier,
--    app.programme_version_product and app.programme_version_scope as children (offer_version pattern). request_uuid and
--    request_fingerprint make a retried create/update a replay (409 when the content differs); result_row_version is the
--    ETag the programme had right after the call, so a replay answers the first result (V0157 pattern).
-- 3. app.programme_status_change: the append-only trail of draft -> active -> inactive moves.
-- 4. app.gift_change: append-only journal of gift create/update calls with a snapshot of the first result (replay).
-- 5. app.programme_enrolment_batch: the batch_uuid ledger of PUT .../enrolments (a replay answers the first result).
-- 6. app.programme_lifecycle_guard: the database refuses any programme status move but draft -> active -> inactive and any
--    change of current_version_id once the programme has left draft (V0156 pattern).
-- 7. The versions, their children and the three trails are immutable/append-only (trigger + the API role loses UPDATE).
-- Writer rule (for the routes of train 2): the portal copies the new version's name, period, dates and points expiry into app.programme in the same
--    transaction that sets current_version_id, only while the programme is a draft, and never edits those columns of an active or inactive row.
--    dw.v_programme reads them from app.programme; the version row is the immutable record. The foreign key above makes the database refuse a
--    current_version_id that belongs to another programme.
-- Thresholds are in STD as stored (units rule 5, brief Q1 default); no float or money column is added.

SET lock_timeout = '5s';

-- ---------- 1. status draft / active / inactive ----------
ALTER TABLE app.programme DROP CONSTRAINT programme_status_check;   -- widened below: every old value stays allowed
ALTER TABLE app.programme ADD CONSTRAINT programme_status_check CHECK (status IN ('draft','active','inactive')) NOT VALID;
ALTER TABLE app.programme ALTER COLUMN status SET DEFAULT 'draft';
COMMENT ON COLUMN app.programme.status IS 'Lifecycle: draft (being prepared, rule may still change), active, inactive (ended; shown as "Ended" in the portal). Moves go draft -> active -> inactive through app.programme_status_change. Active in the portal does not reach the field: the bundle programmes array stays null and no points are earned or redeemed (docs/27).';
ALTER TABLE app.programme ADD COLUMN current_version_id bigint;   -- FK added after programme_version exists

-- ---------- 2. versions ----------
CREATE TABLE app.programme_version (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id        bigint NOT NULL REFERENCES app.programme(id),
  version_no          int NOT NULL CHECK (version_no >= 1),
  name_en             text NOT NULL CHECK (length(name_en) BETWEEN 1 AND 120),
  name_bn             text CHECK (length(name_bn) <= 120),
  period_label        text CHECK (length(period_label) <= 40),
  active_from         date NOT NULL,
  active_to           date NOT NULL,
  points_expire_on    date,
  rules               jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(rules) = 'object'),
  change_reason       text NOT NULL CHECK (length(change_reason) BETWEEN 10 AND 500),
  request_uuid        uuid NOT NULL UNIQUE,
  request_fingerprint text NOT NULL,
  result_row_version  int,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          bigint REFERENCES app.app_user(id),
  UNIQUE (programme_id, version_no),
  UNIQUE (programme_id, id),                        -- target of the same-programme foreign key from app.programme
  CHECK (active_to >= active_from),
  CHECK (points_expire_on IS NULL OR points_expire_on >= active_to)
);
-- Composite: the current version must belong to THIS programme (MATCH SIMPLE: no check while current_version_id is null).
ALTER TABLE app.programme ADD CONSTRAINT programme_current_version_fk FOREIGN KEY (id, current_version_id) REFERENCES app.programme_version(programme_id, id) NOT VALID;

-- Tier rules: thresholds are DATA the owner supplies (nothing invented, docs/27); STD as stored (brief Q1 default).
CREATE TABLE app.programme_version_tier (
  programme_version_id bigint NOT NULL REFERENCES app.programme_version(id),
  tier_code            text NOT NULL CHECK (tier_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  sort_order           int NOT NULL CHECK (sort_order >= 1),
  label_en             text NOT NULL CHECK (length(label_en) BETWEEN 1 AND 80),
  label_bn             text CHECK (length(label_bn) <= 80),
  threshold            numeric(16,3) CHECK (threshold >= 0),
  threshold_basis      text NOT NULL DEFAULT 'std' CHECK (threshold_basis IN ('std')),
  PRIMARY KEY (programme_version_id, tier_code),
  UNIQUE (programme_version_id, sort_order)
);
CREATE TABLE app.programme_version_product (
  programme_version_id bigint NOT NULL REFERENCES app.programme_version(id),
  product_level        text NOT NULL CHECK (product_level IN ('category','brand','variant','sku')),
  product_id           bigint NOT NULL,
  PRIMARY KEY (programme_version_id, product_level, product_id)
);
-- Empty scope = everywhere.
CREATE TABLE app.programme_version_scope (
  programme_version_id bigint NOT NULL REFERENCES app.programme_version(id),
  node_type            text NOT NULL CHECK (node_type IN ('wing','division','territory','zone','route','channel','sub_channel')),
  node_id              bigint NOT NULL,
  PRIMARY KEY (programme_version_id, node_type, node_id)
);

-- ---------- 3. status trail ----------
CREATE TABLE app.programme_status_change (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id        bigint NOT NULL REFERENCES app.programme(id),
  from_status         text NOT NULL CHECK (from_status IN ('draft','active','inactive')),
  to_status           text NOT NULL CHECK (to_status IN ('draft','active','inactive')),
  request_uuid        uuid NOT NULL UNIQUE,
  request_fingerprint text NOT NULL,
  reason              text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  result_row_version  int,
  actor               bigint NOT NULL REFERENCES app.app_user(id),
  at                  timestamptz NOT NULL DEFAULT now(),
  CHECK (from_status <> to_status)
);
CREATE INDEX programme_status_change_programme ON app.programme_status_change (programme_id, at);

-- ---------- 4. gift journal ----------
CREATE TABLE app.gift_change (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gift_id             bigint NOT NULL REFERENCES app.gift(id),
  change_kind         text NOT NULL CHECK (change_kind IN ('create','update')),
  request_uuid        uuid NOT NULL UNIQUE,
  request_fingerprint text NOT NULL,
  reason              text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  snapshot            jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  result_row_version  int NOT NULL CHECK (result_row_version >= 1),
  actor               bigint NOT NULL REFERENCES app.app_user(id),
  at                  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gift_change_gift ON app.gift_change (gift_id, at);

-- ---------- 5. enrolment batch ledger ----------
CREATE TABLE app.programme_enrolment_batch (
  batch_uuid          uuid PRIMARY KEY,
  programme_id        bigint NOT NULL REFERENCES app.programme(id),
  request_fingerprint text NOT NULL,
  row_count           int NOT NULL CHECK (row_count >= 1),
  inserted            int NOT NULL CHECK (inserted >= 0),
  updated             int NOT NULL CHECK (updated >= 0),
  unchanged           int NOT NULL CHECK (unchanged >= 0),
  reason              text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  actor               bigint NOT NULL REFERENCES app.app_user(id),
  at                  timestamptz NOT NULL DEFAULT now()
);

-- ---------- 6. lifecycle guard ----------
CREATE FUNCTION app.programme_lifecycle_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status
     AND NOT ((OLD.status = 'draft' AND NEW.status = 'active') OR (OLD.status = 'active' AND NEW.status = 'inactive')) THEN
    RAISE EXCEPTION 'app.programme: status % -> % is not allowed (draft -> active -> inactive only)', OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.current_version_id IS DISTINCT FROM OLD.current_version_id AND OLD.current_version_id IS NOT NULL AND OLD.status <> 'draft' THEN
    RAISE EXCEPTION 'app.programme: the rule of a % programme is final (create a new programme)', OLD.status USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.programme_lifecycle_guard() IS 'Allows only draft -> active -> inactive status moves on app.programme and a change of current_version_id only while the programme is a draft (or when it is first set).';
CREATE TRIGGER programme_lifecycle_guard BEFORE UPDATE ON app.programme
  FOR EACH ROW EXECUTE FUNCTION app.programme_lifecycle_guard();

-- ---------- 7. immutability and grants ----------
CREATE TRIGGER programme_version_immutable BEFORE UPDATE OR DELETE ON app.programme_version FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_tier_immutable BEFORE UPDATE OR DELETE ON app.programme_version_tier FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_product_immutable BEFORE UPDATE OR DELETE ON app.programme_version_product FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_scope_immutable BEFORE UPDATE OR DELETE ON app.programme_version_scope FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_status_change_append_only BEFORE UPDATE OR DELETE ON app.programme_status_change FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER gift_change_append_only BEFORE UPDATE OR DELETE ON app.gift_change FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_enrolment_batch_append_only BEFORE UPDATE OR DELETE ON app.programme_enrolment_batch FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
-- TRUNCATE skips row triggers: a statement trigger and the revoked privilege close it (V0074 pattern).
CREATE TRIGGER programme_version_no_truncate BEFORE TRUNCATE ON app.programme_version FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_tier_no_truncate BEFORE TRUNCATE ON app.programme_version_tier FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_product_no_truncate BEFORE TRUNCATE ON app.programme_version_product FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_version_scope_no_truncate BEFORE TRUNCATE ON app.programme_version_scope FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_status_change_no_truncate BEFORE TRUNCATE ON app.programme_status_change FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER gift_change_no_truncate BEFORE TRUNCATE ON app.gift_change FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER programme_enrolment_batch_no_truncate BEFORE TRUNCATE ON app.programme_enrolment_batch FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.programme_version, app.programme_version_tier, app.programme_version_product,
  app.programme_version_scope, app.programme_status_change, app.gift_change, app.programme_enrolment_batch FROM PUBLIC;

UPDATE app.db_role_grant SET except_tables = except_tables || '{programme_version,programme_version_tier,programme_version_product,programme_version_scope,programme_status_change,gift_change,programme_enrolment_batch}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update'
   AND NOT ('programme_version' = ANY (except_tables));

-- ---------- 8. data dictionary ----------
COMMENT ON COLUMN app.programme.current_version_id IS 'Programme version (app.programme_version.id) in force; null for programmes made before the portal (V0158). Changes only while the programme is a draft.';

COMMENT ON TABLE app.programme_version IS 'One immutable version of a loyalty programme''s definition (names, period, dates, rules, tiers, scope): a rule change is a new row, never an UPDATE (trigger and grant). The loyalty portal creates them (V0158); the field does not read them (docs/27: loyalty in the field is deferred, bundle programmes null).
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.programme_version.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.programme_version.programme_id IS 'Programme (app.programme) the version belongs to.';
COMMENT ON COLUMN app.programme_version.version_no IS 'Version number within the programme, from 1.';
COMMENT ON COLUMN app.programme_version.name_en IS 'English name of the programme in this version.';
COMMENT ON COLUMN app.programme_version.name_bn IS 'Bangla name in this version; null when none.';
COMMENT ON COLUMN app.programme_version.period_label IS 'Period label such as 2026-10 (Diamond League month) or 2026-Q4 (Astha quarter); null when none.';
COMMENT ON COLUMN app.programme_version.active_from IS 'First business date (Asia/Dhaka) of the programme window in this version.';
COMMENT ON COLUMN app.programme_version.active_to IS 'Last business date (Asia/Dhaka, inclusive) of the programme window in this version.';
COMMENT ON COLUMN app.programme_version.points_expire_on IS 'Date after which points of the period expire (Diamond League); null when they do not expire. Never before active_to.';
COMMENT ON COLUMN app.programme_version.rules IS 'Kind-specific rules as data (Superstar slabs and criteria, campaign gift list). Empty object in v1: no slab is invented (docs/27).';
COMMENT ON COLUMN app.programme_version.change_reason IS 'Why this version was made, 10 to 500 characters, mandatory (the audit log carries the same reason).';
COMMENT ON COLUMN app.programme_version.request_uuid IS 'Client-generated UUID of the portal call that made this version (create or update); a replay of the same call returns this version instead of a new one.';
COMMENT ON COLUMN app.programme_version.request_fingerprint IS 'SHA-256 (hex) of the normalised portal request that made this version; with request_uuid it tells a true replay from a reused UUID (409).';
COMMENT ON COLUMN app.programme_version.result_row_version IS 'Row version (ETag) that app.programme had right after the call that made this version; a replay answers with it. Null for rows made before it was stored.';
COMMENT ON COLUMN app.programme_version.created_at IS 'UTC instant the version was made.';
COMMENT ON COLUMN app.programme_version.created_by IS 'User (app.app_user) who made the version.';

COMMENT ON TABLE app.programme_version_tier IS 'Tier rules of one programme version: code, labels and the threshold in STD as stored (the unit of the existing target model, brief Q1 default). Thresholds are data the owner supplies; none is invented. Immutable with its version.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.programme_version_tier.programme_version_id IS 'Programme version (app.programme_version.id).';
COMMENT ON COLUMN app.programme_version_tier.tier_code IS 'Tier code (lower-case, as app.programme_enrolment.tier_code uses it).';
COMMENT ON COLUMN app.programme_version_tier.sort_order IS 'Rank of the tier within the version, 1 = lowest; thresholds rise with it.';
COMMENT ON COLUMN app.programme_version_tier.label_en IS 'English label of the tier.';
COMMENT ON COLUMN app.programme_version_tier.label_bn IS 'Bangla label of the tier; null when none.';
COMMENT ON COLUMN app.programme_version_tier.threshold IS 'Qualifying threshold in STD (numeric 16,3, never negative); null when the owner has not supplied one.';
COMMENT ON COLUMN app.programme_version_tier.threshold_basis IS 'Unit of threshold: std (the standard unit of the target model). Other bases wait for the owner''s answer on units.';

COMMENT ON TABLE app.programme_version_product IS 'Products (category, brand, variant or SKU) a programme version applies to; none = all products. Immutable with its version.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.programme_version_product.programme_version_id IS 'Programme version (app.programme_version.id).';
COMMENT ON COLUMN app.programme_version_product.product_level IS 'Product-tree level: category, brand, variant or sku.';
COMMENT ON COLUMN app.programme_version_product.product_id IS 'Id of the product node or SKU at that level.';

COMMENT ON TABLE app.programme_version_scope IS 'Places and channels a programme version applies to; none = everywhere. Immutable with its version.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.programme_version_scope.programme_version_id IS 'Programme version (app.programme_version.id).';
COMMENT ON COLUMN app.programme_version_scope.node_type IS 'wing, division, territory, zone, route, channel or sub_channel.';
COMMENT ON COLUMN app.programme_version_scope.node_id IS 'Id of the node of that type.';

COMMENT ON TABLE app.programme_status_change IS 'Append-only trail of programme status moves (draft, active, inactive) with the reason and the person; the request UUID makes a retried move idempotent.
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.programme_status_change.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.programme_status_change.programme_id IS 'Programme (app.programme) whose status moved.';
COMMENT ON COLUMN app.programme_status_change.from_status IS 'Status before the move: draft, active or inactive.';
COMMENT ON COLUMN app.programme_status_change.to_status IS 'Status after the move; always different from from_status.';
COMMENT ON COLUMN app.programme_status_change.request_uuid IS 'Client-generated UUID of the status call; unique, so a retried call returns this row instead of moving the programme twice.';
COMMENT ON COLUMN app.programme_status_change.request_fingerprint IS 'SHA-256 (hex) of the normalised status request; a reused request_uuid with a different move is told apart (409).';
COMMENT ON COLUMN app.programme_status_change.reason IS 'Why the programme moved, 10 to 500 characters, mandatory.';
COMMENT ON COLUMN app.programme_status_change.result_row_version IS 'Row version (ETag) that app.programme had right after this move; a replay answers with it.';
COMMENT ON COLUMN app.programme_status_change.actor IS 'User (app.app_user) who made the move.';
COMMENT ON COLUMN app.programme_status_change.at IS 'UTC instant of the move.';

COMMENT ON TABLE app.gift_change IS 'Append-only journal of gift create and update calls from the portal with a snapshot of the first result, so a retried call (same request UUID) is answered with that result.
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.gift_change.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.gift_change.gift_id IS 'Gift (app.gift) the call created or changed.';
COMMENT ON COLUMN app.gift_change.change_kind IS 'create or update.';
COMMENT ON COLUMN app.gift_change.request_uuid IS 'Client-generated UUID of the call; unique, so a replay returns the first result.';
COMMENT ON COLUMN app.gift_change.request_fingerprint IS 'SHA-256 (hex) of the normalised request; a reused request_uuid with different content is told apart (409).';
COMMENT ON COLUMN app.gift_change.reason IS 'Why the gift changed, 10 to 500 characters, mandatory.';
COMMENT ON COLUMN app.gift_change.snapshot IS 'The gift as the first result of the call returned it (JSON, contract member names).';
COMMENT ON COLUMN app.gift_change.result_row_version IS 'Row version (ETag) of the gift right after the call.';
COMMENT ON COLUMN app.gift_change.actor IS 'User (app.app_user) who made the call.';
COMMENT ON COLUMN app.gift_change.at IS 'UTC instant of the call.';

COMMENT ON TABLE app.programme_enrolment_batch IS 'Append-only ledger of PUT /v1/admin/programmes/{id}/enrolments batches; the batch UUID makes a retried batch idempotent (the first counts are answered again).
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.programme_enrolment_batch.batch_uuid IS 'Client-generated UUID of the batch.';
COMMENT ON COLUMN app.programme_enrolment_batch.programme_id IS 'Programme the batch enrolled outlets into.';
COMMENT ON COLUMN app.programme_enrolment_batch.request_fingerprint IS 'SHA-256 (hex) of the normalised batch; a reused batch_uuid with different rows is told apart (409).';
COMMENT ON COLUMN app.programme_enrolment_batch.row_count IS 'Rows in the batch.';
COMMENT ON COLUMN app.programme_enrolment_batch.inserted IS 'Enrolments the batch created.';
COMMENT ON COLUMN app.programme_enrolment_batch.updated IS 'Enrolments the batch changed.';
COMMENT ON COLUMN app.programme_enrolment_batch.unchanged IS 'Rows that already matched.';
COMMENT ON COLUMN app.programme_enrolment_batch.reason IS 'Why the batch was made, 10 to 500 characters, mandatory.';
COMMENT ON COLUMN app.programme_enrolment_batch.actor IS 'User (app.app_user) who made the batch.';
COMMENT ON COLUMN app.programme_enrolment_batch.at IS 'UTC instant of the batch.';

SELECT app.apply_db_role_grants();
