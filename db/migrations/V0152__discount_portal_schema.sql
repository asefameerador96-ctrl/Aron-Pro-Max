-- V0152 discount-programme portal, schema (lane portal-discount-api, train 1; docs/status/portal-discount-brief.md s5;
-- D-DISC-API-01). The owner's roadmap order (D-LEAD-ROADMAP-02) starts the PORTAL for offers; the FIELD is unchanged:
-- the memo's offer-discount line stays zero, the bundle `offers` array stays empty, nothing in the apps reads these rows.
--
-- 1. app.offer.status gains `draft` (draft, active, inactive; `inactive` is what the portal calls "Ended", so no contract
--    enum value is removed). The CHECK is replaced NOT VALID here and validated by V0153 (separate transaction, squawk
--    rule; V0131/V0132 pattern). The column default becomes `draft` so a row can never become active by omission.
-- 2. app.offer_version.request_uuid: the client UUID of the create or update call; a replay finds the first result.
-- 3. app.offer_status_change: the append-only trail of draft -> active -> inactive moves (request_uuid unique = replay).
-- 4. app.offer_version is immutable (a rule change is a new version): UPDATE and DELETE are refused by trigger and the
--    API role loses UPDATE on it (grant map); app.offer_status_change is append-only likewise. TRUNCATE is closed on both
--    (statement trigger, REVOKE from PUBLIC; V0074 pattern).
-- 5. dw.v_offer (one row per offer with its current version) and dw.v_offer_version (one row per version, with the product
--    and scope rows as jsonb arrays) for later engines and dashboards (docs/31 s3: read dw.v_*, not app tables). Additive
--    views: dw.view_contract_version() 1.5 -> 1.6. No free text of an admin in dw reuse views (the change reason stays in
--    app.offer_version and the audit log); the jsonb rows use the contract's member names.
-- 6. Data-dictionary text for every new column and view; the V0016 comments on app.offer_version, app.offer_version.offer_id
--    and app.memo.offer_discount_mtk are restated.

SET lock_timeout = '5s';

-- ---------- 1. status draft / active / inactive ----------
ALTER TABLE app.offer DROP CONSTRAINT offer_status_check;   -- widened below: every old value stays allowed (V0131 does the same)
ALTER TABLE app.offer ADD CONSTRAINT offer_status_check CHECK (status IN ('draft','active','inactive')) NOT VALID;
ALTER TABLE app.offer ALTER COLUMN status SET DEFAULT 'draft';
COMMENT ON COLUMN app.offer.status IS 'Lifecycle: draft (being prepared, rule may still change), active, inactive (ended; shown as "Ended" in the portal). Moves go draft -> active -> inactive through app.offer_status_change. Active in the portal does not reach the field: the bundle offers array and the memo offer line stay zero (docs/27).';

-- ---------- 2. request_uuid on versions ----------
ALTER TABLE app.offer_version ADD COLUMN request_uuid uuid;
-- The table is empty (nothing wrote offers before the portal) and the portal is not live yet: a plain unique index.
-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX offer_version_request_uuid_key ON app.offer_version (request_uuid);
COMMENT ON COLUMN app.offer_version.request_uuid IS 'Client-generated UUID of the portal call that created this version (create or update); a replay of the same call returns this version instead of a new one. Null for versions not made by the portal.';

-- ---------- 3. status trail ----------
CREATE TABLE app.offer_status_change (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  offer_id      bigint NOT NULL REFERENCES app.offer(id),
  from_status   text NOT NULL CHECK (from_status IN ('draft','active','inactive')),
  to_status     text NOT NULL CHECK (to_status IN ('draft','active','inactive')),
  request_uuid  uuid NOT NULL UNIQUE,
  reason        text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  actor         bigint NOT NULL REFERENCES app.app_user(id),
  at            timestamptz NOT NULL DEFAULT now(),
  CHECK (from_status <> to_status)
);
CREATE INDEX offer_status_change_offer ON app.offer_status_change (offer_id, at);
CREATE TRIGGER offer_status_change_append_only BEFORE UPDATE OR DELETE ON app.offer_status_change
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER offer_version_immutable BEFORE UPDATE OR DELETE ON app.offer_version
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
-- TRUNCATE skips row triggers: a statement trigger and the revoked privilege close it (V0074 pattern).
CREATE TRIGGER offer_status_change_no_truncate BEFORE TRUNCATE ON app.offer_status_change
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER offer_version_no_truncate BEFORE TRUNCATE ON app.offer_version
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.offer_status_change FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON app.offer_version FROM PUBLIC;

COMMENT ON TABLE app.offer_status_change IS 'Append-only trail of offer status moves (draft, active, inactive) with the reason and the person; the request UUID makes a retried move idempotent.
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.offer_status_change.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.offer_status_change.offer_id IS 'Offer (app.offer) whose status moved.';
COMMENT ON COLUMN app.offer_status_change.from_status IS 'Status before the move: draft, active or inactive.';
COMMENT ON COLUMN app.offer_status_change.to_status IS 'Status after the move: draft, active or inactive; always different from from_status.';
COMMENT ON COLUMN app.offer_status_change.request_uuid IS 'Client-generated UUID of the status call; unique, so a retried call returns this row instead of moving the offer twice.';
COMMENT ON COLUMN app.offer_status_change.reason IS 'Why the offer moved, 10 to 500 characters, mandatory.';
COMMENT ON COLUMN app.offer_status_change.actor IS 'User (app.app_user) who made the move.';
COMMENT ON COLUMN app.offer_status_change.at IS 'UTC instant of the move.';

-- ---------- 4. grants: the API inserts versions and trail rows, never updates them ----------
UPDATE app.db_role_grant SET except_tables = except_tables || '{offer_version,offer_status_change}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update'
   AND NOT ('offer_version' = ANY (except_tables)) AND NOT ('offer_status_change' = ANY (except_tables));

-- ---------- 5. dw views ----------
CREATE VIEW dw.v_offer AS
SELECT o.id AS offer_id, o.code AS offer_code, o.group_code, o.status, o.current_version_id, v.version_no AS current_version_no,
       v.offer_type, v.level, v.title_en, v.title_bn, v.valid_from, v.valid_to, v.threshold_qty_base, v.discount_bp,
       v.discount_mtk_per_unit, v.reward_sku_id, v.reward_qty_base, v.stacking,
       (SELECT count(*)::int FROM app.offer_version x WHERE x.offer_id = o.id) AS version_count,
       o.created_at, o.updated_at, 'production'::app.data_origin AS data_origin,
       o.status = 'active' AS is_effective
  FROM app.offer o
  LEFT JOIN app.offer_version v ON v.id = o.current_version_id;

CREATE VIEW dw.v_offer_version AS
SELECT v.id AS offer_version_id, v.offer_id, o.code AS offer_code, v.version_no, v.id = o.current_version_id AS is_current,
       v.offer_type, v.level, v.title_en, v.title_bn, v.valid_from, v.valid_to, v.threshold_qty_base, v.discount_bp,
       v.discount_mtk_per_unit, v.reward_sku_id, v.reward_qty_base, v.stacking, v.rule,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('product_level', p.product_level, 'product_id', p.product_id) ORDER BY p.product_level, p.product_id)
                   FROM app.offer_version_product p WHERE p.offer_version_id = v.id), '[]'::jsonb) AS products,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('node_type', s.node_type, 'node_id', s.node_id) ORDER BY s.node_type, s.node_id)
                   FROM app.offer_version_scope s WHERE s.offer_version_id = v.id), '[]'::jsonb) AS scope,
       v.created_at, 'production'::app.data_origin AS data_origin,
       (v.id = o.current_version_id AND o.status = 'active') AS is_effective
  FROM app.offer_version v
  JOIN app.offer o ON o.id = v.offer_id;

COMMENT ON VIEW dw.v_offer IS 'Stable view: one row per discount programme (app.offer) with the rule of its current version. The portal exists; the field does not use it yet (memo offer line zero, bundle offers empty). is_effective = status active.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON VIEW dw.v_offer_version IS 'Stable view: one row per immutable offer version with its rule, validity, reward and the product and scope rows as JSON arrays (empty scope = everywhere). is_effective = the current version of an active offer.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';

COMMENT ON COLUMN dw.v_offer.offer_id IS 'Offer (app.offer.id).';
COMMENT ON COLUMN dw.v_offer.offer_code IS 'Stable business code of the offer (app.offer.code).';
COMMENT ON COLUMN dw.v_offer.group_code IS 'Campaign label of the offer (app.offer.group_code); also the group stacking rules would apply to.';
COMMENT ON COLUMN dw.v_offer.status IS 'Lifecycle status: draft, active or inactive (Ended in the portal).';
COMMENT ON COLUMN dw.v_offer.current_version_id IS 'Offer version (app.offer_version.id) currently in force.';
COMMENT ON COLUMN dw.v_offer.current_version_no IS 'Version number of the current version, from 1.';
COMMENT ON COLUMN dw.v_offer.offer_type IS 'Rule type of the current version: pct_discount, amount_per_unit, free_qty or drp_slide.';
COMMENT ON COLUMN dw.v_offer.level IS 'Where the discount applies: line or memo.';
COMMENT ON COLUMN dw.v_offer.title_en IS 'English title of the current version.';
COMMENT ON COLUMN dw.v_offer.title_bn IS 'Bangla title of the current version; null when none.';
COMMENT ON COLUMN dw.v_offer.valid_from IS 'First business date (Asia/Dhaka) the current version is valid.';
COMMENT ON COLUMN dw.v_offer.valid_to IS 'Last business date (Asia/Dhaka, inclusive) the current version is valid.';
COMMENT ON COLUMN dw.v_offer.threshold_qty_base IS 'Quantity in base units (sticks, pieces, dozens) that must be bought for the rule to apply; null when no threshold.';
COMMENT ON COLUMN dw.v_offer.discount_bp IS 'Percentage discount in basis points (1 to 10000); null unless pct_discount.';
COMMENT ON COLUMN dw.v_offer.discount_mtk_per_unit IS 'Money discount per base unit in integer milli-taka; null unless amount_per_unit.';
COMMENT ON COLUMN dw.v_offer.reward_sku_id IS 'SKU given free (app.sku.id); null unless a free-goods rule.';
COMMENT ON COLUMN dw.v_offer.reward_qty_base IS 'Free quantity in base units of the reward SKU; null unless a free-goods rule.';
COMMENT ON COLUMN dw.v_offer.stacking IS 'exclusive or stackable with other offers of the same group.';
COMMENT ON COLUMN dw.v_offer.version_count IS 'Number of versions the offer has had.';
COMMENT ON COLUMN dw.v_offer.created_at IS 'UTC instant the offer was created.';
COMMENT ON COLUMN dw.v_offer.updated_at IS 'UTC instant the offer row last changed (status or current version).';
COMMENT ON COLUMN dw.v_offer.data_origin IS 'Always production: offers carry no origin of their own.';
COMMENT ON COLUMN dw.v_offer.is_effective IS 'True when the offer status is active.';

COMMENT ON COLUMN dw.v_offer_version.offer_version_id IS 'Offer version (app.offer_version.id).';
COMMENT ON COLUMN dw.v_offer_version.offer_id IS 'Offer (app.offer.id) the version belongs to.';
COMMENT ON COLUMN dw.v_offer_version.offer_code IS 'Stable business code of the offer.';
COMMENT ON COLUMN dw.v_offer_version.version_no IS 'Version number within the offer, from 1.';
COMMENT ON COLUMN dw.v_offer_version.is_current IS 'True for the version in force (app.offer.current_version_id).';
COMMENT ON COLUMN dw.v_offer_version.offer_type IS 'Rule type: pct_discount, amount_per_unit, free_qty or drp_slide.';
COMMENT ON COLUMN dw.v_offer_version.level IS 'Where the discount applies: line or memo.';
COMMENT ON COLUMN dw.v_offer_version.title_en IS 'English title of the version.';
COMMENT ON COLUMN dw.v_offer_version.title_bn IS 'Bangla title of the version; null when none.';
COMMENT ON COLUMN dw.v_offer_version.valid_from IS 'First business date (Asia/Dhaka) the version is valid.';
COMMENT ON COLUMN dw.v_offer_version.valid_to IS 'Last business date (Asia/Dhaka, inclusive) the version is valid.';
COMMENT ON COLUMN dw.v_offer_version.threshold_qty_base IS 'Quantity in base units that must be bought for the rule to apply; null when no threshold.';
COMMENT ON COLUMN dw.v_offer_version.discount_bp IS 'Percentage discount in basis points (1 to 10000); null unless pct_discount.';
COMMENT ON COLUMN dw.v_offer_version.discount_mtk_per_unit IS 'Money discount per base unit in integer milli-taka; null unless amount_per_unit.';
COMMENT ON COLUMN dw.v_offer_version.reward_sku_id IS 'SKU given free (app.sku.id); null unless a free-goods rule.';
COMMENT ON COLUMN dw.v_offer_version.reward_qty_base IS 'Free quantity in base units of the reward SKU; null unless a free-goods rule.';
COMMENT ON COLUMN dw.v_offer_version.stacking IS 'exclusive or stackable with other offers of the same group.';
COMMENT ON COLUMN dw.v_offer_version.rule IS 'Extra typed parameters of the rule as JSON (empty object in v1; no slabs are invented, docs/27).';
COMMENT ON COLUMN dw.v_offer_version.products IS 'JSON array of {product_level, product_id} rows the version applies to (product_level category, brand, variant or sku, as in the contract); empty = every product.';
COMMENT ON COLUMN dw.v_offer_version.scope IS 'JSON array of {node_type, node_id} place rows (wing, division, territory, zone, route, channel, sub_channel, as in the contract); empty = everywhere.';
COMMENT ON COLUMN dw.v_offer_version.created_at IS 'UTC instant the version was made.';
COMMENT ON COLUMN dw.v_offer_version.data_origin IS 'Always production: offers carry no origin of their own.';
COMMENT ON COLUMN dw.v_offer_version.is_effective IS 'True for the current version of an active offer.';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.6' $$;

-- ---------- 6. restated V0016 comments ----------
COMMENT ON TABLE app.offer_version IS 'One immutable version of an offer''s rule, validity and reward: a rule change is a new row, never an UPDATE (trigger and grant). The discount portal creates them (V0152); memos will record the versions they used once the discount engine exists, and the field offer line stays zero until then (docs/27).
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.offer_version.offer_id IS 'Offer (app.offer) the version belongs to; the discount portal is in build and the field does not read it (memo offer line zero, docs/27).';
COMMENT ON COLUMN app.memo.offer_discount_mtk IS 'Offer and free-goods discount in milli-taka. Zero in every field app until the owner enables the discount engine (docs/27): the discount portal (V0152) manages programmes, but nothing applies them to a memo yet.';

SELECT app.apply_db_role_grants();
