-- V0182 (lane portal-loyalty-api, push C): the sync record `gift_choice` (contract 1.15.0, SRM-B-66, D-LOY-API-19).
-- 1. app.gift_choice: one immutable row per choice a phone sent (idempotency by client_uuid, like every field record). The handler (backend:sync GiftChoiceRecords)
--    upserts app.gift_assignment from it: last writer by captured_at until the hand-over photo locks the outlet's gift.
-- 2. Two outcome codes: gift_locked (choice after the photo lock) and gift_not_for_tier. The CHECK is re-created NOT VALID like V0112/V0135 (new rows are checked; a later
--    migration validates it once a green run holds it), so no table scan under the lock.
-- 3. cfg.astha.gift_choice_roles default: SR and TSO (owner Q4 default; V0009 had TSO only). Existing explicit cfg_value rows are untouched.

SET lock_timeout = '5s';

CREATE TABLE app.gift_choice (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,
  business_date_device date,
  user_id              bigint NOT NULL REFERENCES app.app_user(id),
  device_id            bigint REFERENCES app.device(id),
  acting_for_user_id   bigint REFERENCES app.app_user(id),
  route_id             bigint REFERENCES app.route(id),
  captured_at          timestamptz NOT NULL,
  captured_elapsed_ms  bigint CHECK (captured_elapsed_ms >= 0),
  boot_count           int CHECK (boot_count >= 0),
  clock_offset_ms      bigint,
  captured_offline     boolean NOT NULL DEFAULT false,
  schema_version       int NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  config_version       bigint NOT NULL CHECK (config_version >= 0),
  bundle_version       text,
  bundle_stale         boolean NOT NULL DEFAULT false,
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,
  external_ref         varchar(64) UNIQUE,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  gift_id              bigint NOT NULL REFERENCES app.gift(id),
  quarter              text NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  data_origin          app.data_origin
);
CREATE INDEX gift_choice_assignment ON app.gift_choice (outlet_id, quarter, captured_at);
CREATE TRIGGER gift_choice_immutable BEFORE UPDATE OR DELETE ON app.gift_choice
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'data_origin');
CREATE TRIGGER gift_choice_stamp_origin BEFORE INSERT ON app.gift_choice
  FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin();
CREATE TRIGGER gift_choice_guard_origin BEFORE UPDATE ON app.gift_choice
  FOR EACH ROW EXECUTE FUNCTION app.guard_data_origin();
INSERT INTO app.data_origin_capture (table_name) VALUES ('gift_choice');

ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
  'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
  'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'memo_no_invalid',
  'edit_not_allowed', 'chain_too_deep', 'voided_by_admin', 'lines_exceed_max', 'qty_invalid', 'price_invalid',
  'attendance_duplicate', 'server_error', 'retry_exhausted', 'unknown_gift', 'insufficient_points',
  'gift_photo_exists', 'day_final_submitted', 'ticket_state_invalid', 'issue_type_unknown',
  'gift_locked', 'gift_not_for_tier')) NOT VALID;

UPDATE app.cfg_key SET default_value = '["SR", "TSO"]'::jsonb WHERE key = 'cfg.astha.gift_choice_roles';

COMMENT ON TABLE app.gift_choice IS 'The Astha gift an outlet chose for a quarter, one immutable row per choice a phone sent; the handler keeps app.gift_assignment in step (later captured_at wins until the hand-over photo locks the outlet).
owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal';
COMMENT ON COLUMN app.gift_choice.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.gift_choice.client_uuid IS 'Client-generated UUID v4 of the record; the server upserts by it (idempotency key).';
COMMENT ON COLUMN app.gift_choice.family_uuid IS 'client_uuid of the family header the record belongs to (equal to client_uuid on a header).';
COMMENT ON COLUMN app.gift_choice.business_date IS 'Asia/Dhaka date of the trusted capture time.';
COMMENT ON COLUMN app.gift_choice.business_date_device IS 'Date on the phone''s own clock, kept when the server re-dated the row to a trusted business date.';
COMMENT ON COLUMN app.gift_choice.user_id IS 'Database id of the user who captured the record, taken from the token and never from the body.';
COMMENT ON COLUMN app.gift_choice.device_id IS 'Database id of the phone that captured the record, from the token.';
COMMENT ON COLUMN app.gift_choice.acting_for_user_id IS 'Assignee of the route when the capturing user worked it as a cover substitute.';
COMMENT ON COLUMN app.gift_choice.route_id IS 'Route (app.route) being worked.';
COMMENT ON COLUMN app.gift_choice.captured_at IS 'UTC instant of capture on the phone, from trusted time when an anchor exists.';
COMMENT ON COLUMN app.gift_choice.captured_elapsed_ms IS 'SystemClock.elapsedRealtime() at capture, in milliseconds since boot.';
COMMENT ON COLUMN app.gift_choice.boot_count IS 'Android boot counter at capture; with captured_elapsed_ms it anchors trusted time.';
COMMENT ON COLUMN app.gift_choice.clock_offset_ms IS 'Server time minus phone wall clock known at capture, in ms; null if the phone never synced.';
COMMENT ON COLUMN app.gift_choice.captured_offline IS 'True when the phone had no connection at capture.';
COMMENT ON COLUMN app.gift_choice.schema_version IS 'Version of the record payload schema.';
COMMENT ON COLUMN app.gift_choice.config_version IS 'Global config version in force for the row (at capture for device records).';
COMMENT ON COLUMN app.gift_choice.bundle_version IS 'Version of the day bundle the phone held at capture (<date>:<seq>).';
COMMENT ON COLUMN app.gift_choice.bundle_stale IS 'True when the record was captured on a cached bundle older than the business date.';
COMMENT ON COLUMN app.gift_choice.first_batch_uuid IS 'batch_uuid of the sync batch that first delivered the record.';
COMMENT ON COLUMN app.gift_choice.received_at IS 'UTC instant the server received the row.';
COMMENT ON COLUMN app.gift_choice.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.gift_choice.voided_at IS 'UTC time an admin data void tombstoned the row; the row is never deleted.';
COMMENT ON COLUMN app.gift_choice.external_ref IS 'Stable external reference for cross-walks with other systems (Apsis, ERP); unique when set.';
COMMENT ON COLUMN app.gift_choice.outlet_id IS 'Outlet (app.outlet) that chose the gift.';
COMMENT ON COLUMN app.gift_choice.gift_id IS 'Gift (app.gift) chosen.';
COMMENT ON COLUMN app.gift_choice.quarter IS 'Astha quarter label the choice is for, for example 2026-Q4 (cfg.astha.quarter_start_month).';
COMMENT ON COLUMN app.gift_choice.fix_status IS 'Outcome of the fix request: ok, timeout, permission_denied, location_off or provider_unavailable.';
COMMENT ON COLUMN app.gift_choice.fix_lat IS '[pii:personal] Latitude of the user''s location fix taken with the record (WGS84 degrees).';
COMMENT ON COLUMN app.gift_choice.fix_lng IS '[pii:personal] Longitude of the user''s location fix taken with the record (WGS84 degrees).';
COMMENT ON COLUMN app.gift_choice.fix_accuracy_m IS '[pii:personal] Accuracy in metres of the location fix taken with the record.';
COMMENT ON COLUMN app.gift_choice.fix_is_mock IS 'True when the phone flagged the fix as coming from a mock location provider.';
COMMENT ON COLUMN app.gift_choice.data_origin IS 'Origin of the row (production, pilot, test, seed), stamped at insert from the user, outlet and route (V0116).';

-- The new table and function get the standard role grants (api_rw, worker_rw, readers; app.apply_db_role_grants).
SELECT app.apply_db_role_grants();
