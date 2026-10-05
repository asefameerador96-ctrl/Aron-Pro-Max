-- V0007 field transactions (row N-006, schema v1b). docs/24 s4.2 (record types and their tables), s4.9 (day),
-- s7 (money), s11 (geo), s12.1, s12.5; contract *Payload schemas.
--
-- Every device-originated table carries the same envelope: client_uuid (UUID v4 from the phone, UNIQUE), family_uuid,
-- business_date (Asia/Dhaka) and business_date_device, user_id / device_id from the token (never the body),
-- captured_at (UTC timestamptz) and the clock evidence, config and bundle versions, received_at and voided_at (data-void
-- tombstone). Range-partitioned tables (visit, memo, memo_line, geo_fix) must include business_date in every unique
-- key, so their client_uuid key is (client_uuid, business_date) plus the app.client_uuid_once trigger that refuses the
-- same client_uuid under another business_date; app.ingest_registry (V0008, primary key client_uuid) is the global
-- uniqueness point that the ingest path writes first in the same transaction.
-- Synced rows are immutable except their named enrichment and state columns (app.guard_synced_row); nothing is deleted.
-- Parents are referenced by client_uuid (a child may arrive before its parent and is parked, s4.2); master data is
-- referenced by foreign key.

-- Refuses DELETE, and any UPDATE that changes a column not named in the trigger arguments.
CREATE FUNCTION app.guard_synced_row() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'app.%: synced rows are never deleted (tombstone with voided_at)', TG_TABLE_NAME
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (to_jsonb(NEW) - TG_ARGV) IS DISTINCT FROM (to_jsonb(OLD) - TG_ARGV) THEN
    RAISE EXCEPTION 'app.%: only % may change on a synced row', TG_TABLE_NAME, array_to_string(TG_ARGV, ', ')
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;

-- On a business_date-partitioned table: refuses a client_uuid that is already stored under another business_date.
CREATE FUNCTION app.client_uuid_once() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE other date;
BEGIN
  EXECUTE format('SELECT business_date FROM app.%I WHERE client_uuid = $1 AND business_date <> $2 LIMIT 1', TG_ARGV[0])
    INTO other USING NEW.client_uuid, NEW.business_date;
  IF other IS NOT NULL THEN
    RAISE EXCEPTION 'duplicate key value violates unique constraint "%_client_uuid_once"', TG_ARGV[0]
      USING ERRCODE = 'unique_violation',
            DETAIL = format('Key (client_uuid)=(%s) already exists with business_date %s.', NEW.client_uuid, other);
  END IF;
  RETURN NEW;
END $$;

-- ---------- programmes, loyalty and content (contract v1.1, docs/24 s4.14; masters the field records point at) ----------
CREATE TABLE app.programme (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind              text NOT NULL CHECK (kind IN ('diamond_league','astha','campaign','superstar')),
  code              text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name_en           text NOT NULL CHECK (length(name_en) BETWEEN 1 AND 120),
  name_bn           text CHECK (length(name_bn) <= 120),
  period_label      text CHECK (length(period_label) <= 40),          -- 2026-10 (Diamond League month), 2026-Q4 (Astha quarter)
  active_from       date NOT NULL,
  active_to         date NOT NULL,
  points_expire_on  date,
  rules             jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(rules) = 'object'),
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref      varchar(64) UNIQUE,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  version           int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by        bigint,
  updated_by        bigint,
  CHECK (active_to >= active_from)
);
CREATE TRIGGER programme_touch BEFORE UPDATE ON app.programme FOR EACH ROW EXECUTE FUNCTION app.touch_master();

CREATE TABLE app.programme_enrolment (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id      bigint NOT NULL REFERENCES app.programme(id),
  outlet_id         bigint NOT NULL REFERENCES app.outlet(id),
  league_label      text CHECK (length(league_label) <= 40),
  tier_code         text CHECK (tier_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  base_target       numeric(16,3) CHECK (base_target >= 0),
  valid_from        date NOT NULL,
  valid_to          date,                              -- exclusive; null = open
  created_at        timestamptz NOT NULL DEFAULT now(),
  created_by        bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (programme_id WITH =, outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX ON app.programme_enrolment (outlet_id);

CREATE TABLE app.gift (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id      bigint NOT NULL REFERENCES app.programme(id),
  code              text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,40}$'),
  name_en           text NOT NULL CHECK (length(name_en) BETWEEN 1 AND 120),
  name_bn           text CHECK (length(name_bn) <= 120),
  points_cost       int CHECK (points_cost BETWEEN 1 AND 100000),    -- null for Astha gifts (chosen, not redeemed)
  tier_codes        text[] NOT NULL DEFAULT '{}' CHECK (cardinality(tier_codes) <= 10),
  image_url         text CHECK (length(image_url) <= 1000),
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  version           int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (programme_id, code)
);
CREATE TRIGGER gift_touch BEFORE UPDATE ON app.gift FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- Astha gift chosen for an outlet and quarter (TSO on the web); locked by the SR's hand-over photo.
CREATE TABLE app.gift_assignment (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id      bigint NOT NULL REFERENCES app.programme(id),
  quarter           text NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
  outlet_id         bigint NOT NULL REFERENCES app.outlet(id),
  route_id          bigint NOT NULL REFERENCES app.route(id),
  gift_id           bigint NOT NULL REFERENCES app.gift(id),
  tier_code         text CHECK (tier_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  chosen_by_user_id bigint REFERENCES app.app_user(id),
  chosen_at         timestamptz,
  photo_media_uuid  uuid,
  locked_at         timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  version           int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (programme_id, quarter, outlet_id)
);
CREATE TRIGGER gift_assignment_touch BEFORE UPDATE ON app.gift_assignment FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- Astha targets by route (and optionally outlet and brand) and month; never negative.
CREATE TABLE app.astha_target (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  programme_id      bigint REFERENCES app.programme(id),
  month             date NOT NULL CHECK (extract(day FROM month) = 1),
  route_id          bigint NOT NULL REFERENCES app.route(id),
  outlet_id         bigint REFERENCES app.outlet(id),
  brand_id          bigint REFERENCES app.product_node(id),
  std_target        numeric(16,3) NOT NULL CHECK (std_target >= 0),
  memo_target       int NOT NULL CHECK (memo_target >= 0),
  batch_uuid        uuid,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  created_by        bigint
);
CREATE UNIQUE INDEX astha_target_one ON app.astha_target (month, route_id, coalesce(outlet_id, 0), coalesce(brand_id, 0));

-- Points ledger, derived on the server, idempotent on (source_type, source_id) (s4.14 item 1). Append-only.
CREATE TABLE app.loyalty_ledger (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id         bigint NOT NULL REFERENCES app.outlet(id),
  programme_id      bigint NOT NULL REFERENCES app.programme(id),
  source_type       text NOT NULL CHECK (source_type ~ '^[a-z][a-z_]{1,40}$'),   -- survey_response, redemption, expiry, adjustment, migration
  source_id         text NOT NULL CHECK (length(source_id) <= 64),
  points            int NOT NULL CHECK (points <> 0),                           -- + earning, - debit or expiry
  business_date     date NOT NULL,
  expires_on        date,
  flags             text[] NOT NULL DEFAULT '{}',
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_type, source_id)
);
CREATE INDEX ON app.loyalty_ledger (outlet_id, programme_id, business_date);
CREATE TRIGGER loyalty_ledger_append_only BEFORE UPDATE OR DELETE ON app.loyalty_ledger FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- AV and KV items played during calls (ContentItem). A changed asset bumps version.
CREATE TABLE app.content_item (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version           int NOT NULL DEFAULT 1 CHECK (version >= 1),
  kind              text NOT NULL CHECK (kind IN ('av','kv')),
  title_en          text NOT NULL CHECK (length(title_en) BETWEEN 1 AND 120),
  title_bn          text CHECK (length(title_bn) <= 120),
  asset_url         text NOT NULL CHECK (length(asset_url) <= 1000),
  sha256            bytea NOT NULL CHECK (length(sha256) = 32),
  bytes             int NOT NULL CHECK (bytes BETWEEN 1 AND 20971520),
  duration_s        int CHECK (duration_s BETWEEN 1 AND 600),
  valid_from        date NOT NULL,
  valid_to          date NOT NULL,
  sequence          smallint NOT NULL CHECK (sequence BETWEEN 1 AND 20),
  outlet_ids        bigint[] NOT NULL DEFAULT '{}' CHECK (cardinality(outlet_ids) <= 5000),   -- empty = every outlet
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  created_by        bigint,
  CHECK (valid_to >= valid_from)
);

-- Risk signals computed by the worker (s11.4), one per code, subject and business date (idempotent re-evaluation).
CREATE TABLE app.risk_signal (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code              text NOT NULL CHECK (code IN ('GEO_MOCK','GEO_TELEPORT','GEO_ZERO_JITTER','GEO_PERFECT_ACCURACY','GEO_SAME_POINT',
                                                  'GEO_ROUTE_SINGLE_POINT','GEO_STALE_FIX','GEO_GNSS_INCONSISTENT','GEO_GNSS_TIME_SKEW',
                                                  'GEO_DEVICE_SERVER_MISMATCH','GEO_SHORT_VISIT_GAPS','DEVICE_NOT_OWNER','DEVICE_INTEGRITY_FAIL',
                                                  'DEVICE_DEBUG_ENABLED','DEVICE_MOCK_APP_PRESENT','DEVICE_POLICY_DRIFT','CLOCK_SKEW',
                                                  'GEO_OUT_OF_BOUNDS','CONFIG_STAMP_REGRESS')),
  severity          smallint NOT NULL CHECK (severity BETWEEN 1 AND 4),
  business_date     date NOT NULL,
  subject_type      text NOT NULL CHECK (subject_type IN ('user','device','visit','outlet','route','memo')),
  subject_id        text NOT NULL CHECK (length(subject_id) <= 64),
  user_id           bigint REFERENCES app.app_user(id),
  route_id          bigint REFERENCES app.route(id),
  zone_id           bigint REFERENCES app.zone(id),
  score             numeric(6,2) NOT NULL CHECK (score BETWEEN 0 AND 300),
  evidence          jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
  status            text NOT NULL DEFAULT 'open' CHECK (status IN ('open','reviewed','dismissed','confirmed')),
  config_version    bigint NOT NULL CHECK (config_version >= 0),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (code, subject_type, subject_id, business_date)
);
CREATE INDEX ON app.risk_signal (business_date, status);
CREATE INDEX ON app.risk_signal (user_id, business_date);

-- Every location fix embedded in a record (s11.1), keyed by the record that carried it and the slot it filled
-- (fix, or edit_fix on an edited memo). Feeds the server re-check and the risk rules (s11.3, s11.4). Immutable.
CREATE TABLE app.geo_fix (
  id                      bigint GENERATED ALWAYS AS IDENTITY,
  business_date           date NOT NULL,
  source_type             text NOT NULL,                  -- the record type that carried the fix
  source_client_uuid      uuid NOT NULL,
  slot                    text NOT NULL DEFAULT 'fix' CHECK (slot IN ('fix','edit_fix')),
  user_id                 bigint NOT NULL REFERENCES app.app_user(id),
  device_id               bigint,                         -- FK to app.device added in V0010
  route_id                bigint REFERENCES app.route(id),
  captured_at             timestamptz NOT NULL,
  purpose                 text NOT NULL CHECK (purpose IN ('attendance_in','attendance_out','visit_open','force_sale','outlet_capture',
                                                           'outlet_verification','memo_edit','memo_void','due_collection','refresh','breadcrumb',
                                                           'gift_photo','redemption')),
  fix_status              text NOT NULL CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  lat                     double precision CHECK (lat BETWEEN -90 AND 90),
  lng                     double precision CHECK (lng BETWEEN -180 AND 180),
  accuracy_m              double precision CHECK (accuracy_m BETWEEN 0 AND 100000),
  altitude_m              double precision,
  vertical_accuracy_m     double precision CHECK (vertical_accuracy_m >= 0),
  speed_mps               double precision CHECK (speed_mps >= 0),
  bearing_deg             double precision CHECK (bearing_deg BETWEEN 0 AND 360),
  provider                text NOT NULL CHECK (provider IN ('fused','gps','network','passive','unknown')),
  fix_time                timestamptz,
  fix_elapsed_realtime_ms bigint CHECK (fix_elapsed_realtime_ms >= 0),
  fix_age_ms              bigint CHECK (fix_age_ms >= 0),
  time_to_fix_ms          int CHECK (time_to_fix_ms BETWEEN 0 AND 120000),
  request_priority        text CHECK (request_priority IN ('high_accuracy','balanced')),
  is_mock                 boolean NOT NULL,
  reused                  boolean NOT NULL,
  refresh_count           smallint CHECK (refresh_count BETWEEN 0 AND 10),
  gnss                    jsonb,                          -- GnssSummary as received
  satellites_visible      smallint,
  satellites_used         smallint,
  cn0_used_mean_dbhz      real,
  cn0_used_max_dbhz       real,
  cn0_used_stddev_dbhz    real,
  radio                   jsonb,                          -- RadioEnvironment (only when cfg.geo.radio_env_enabled)
  device_owner            boolean NOT NULL,
  dev_options_enabled     boolean NOT NULL,
  adb_enabled             boolean NOT NULL,
  auto_time_enabled       boolean NOT NULL,
  mock_app_present        boolean NOT NULL,
  integrity_ref           uuid,
  received_at             timestamptz NOT NULL DEFAULT now(),
  created_at              timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date),
  UNIQUE (source_client_uuid, slot, business_date),
  CHECK ((lat IS NULL) = (lng IS NULL)),
  CHECK (fix_status <> 'ok' OR (lat IS NOT NULL AND accuracy_m IS NOT NULL))
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.geo_fix (user_id, business_date, captured_at);
CREATE TRIGGER geo_fix_append_only BEFORE UPDATE OR DELETE ON app.geo_fix FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
INSERT INTO app.partition_policy (parent) VALUES ('app.geo_fix');

-- Route-day: one row per route and business date, created by the server from assignments (D24-26). State is derived
-- from the event timestamps, so a late or repeated record never moves it backwards (s4.9).
CREATE TABLE app.route_day (
  id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id                bigint NOT NULL REFERENCES app.route(id),
  business_date           date NOT NULL,
  planned                 boolean NOT NULL,
  planned_source          text NOT NULL DEFAULT 'schedule' CHECK (planned_source IN ('schedule','override','cover')),
  assigned_user_id        bigint REFERENCES app.app_user(id),
  acting_user_id          bigint REFERENCES app.app_user(id),
  state                   text NOT NULL DEFAULT 'not_started' CHECK (state IN ('not_started','logged_in','in_field','synced',
                                                                                'submit_pending_rows','sales_submitted','final_submitted')),
  target_outlets          int CHECK (target_outlets >= 0),          -- frozen at the first bundle of the day
  target_frozen_at        timestamptz,
  route_snapshot_version  int NOT NULL DEFAULT 1 CHECK (route_snapshot_version >= 1),
  logged_in_at            timestamptz,
  in_field_at             timestamptz,
  last_batch_at           timestamptz,
  synced_at               timestamptz,
  submit_received_at      timestamptz,
  sales_submitted_at      timestamptz,
  final_submitted_at      timestamptz,
  submit_cycle            int NOT NULL DEFAULT 1 CHECK (submit_cycle BETWEEN 1 AND 50),
  submit_voided           boolean NOT NULL DEFAULT false,
  submit_count_mismatch   boolean,
  rows_awaited            int CHECK (rows_awaited >= 0),
  settle_deadline_at      timestamptz,
  exception_reason        text,
  late_rows_after_final   int NOT NULL DEFAULT 0 CHECK (late_rows_after_final >= 0),
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  version                 int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (route_id, business_date)
);
CREATE INDEX ON app.route_day (business_date, state);
CREATE INDEX ON app.route_day (assigned_user_id, business_date);
CREATE TRIGGER route_day_touch BEFORE UPDATE ON app.route_day FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- Supervisor-day of an AMO or TSO: attendance and Sales Submit outside a route-day (D24-54).
CREATE TABLE app.supervisor_day (
  id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id                 bigint NOT NULL REFERENCES app.app_user(id),
  business_date           date NOT NULL,
  checked_in_at           timestamptz,
  checked_out_at          timestamptz,
  submit_received_at      timestamptz,
  sales_submitted_at      timestamptz,
  submit_cycle            int NOT NULL DEFAULT 1 CHECK (submit_cycle BETWEEN 1 AND 50),
  submit_voided           boolean NOT NULL DEFAULT false,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  version                 int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (user_id, business_date)
);
CREATE TRIGGER supervisor_day_touch BEFORE UPDATE ON app.supervisor_day FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- QC header: one per visit, created by the server from the visit's qc_line records (s12.1).
CREATE TABLE app.qc_entry (
  id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  visit_client_uuid       uuid NOT NULL UNIQUE,
  business_date           date NOT NULL,
  user_id                 bigint NOT NULL REFERENCES app.app_user(id),
  route_id                bigint REFERENCES app.route(id),
  outlet_id               bigint NOT NULL REFERENCES app.outlet(id),
  created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.qc_entry (business_date);

-- check_in / check_out with its on-demand fix (record attendance_event).
CREATE TABLE app.attendance_event (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  kind                 text NOT NULL CHECK (kind IN ('check_in','check_out')),
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  address_display      text CHECK (length(address_display) <= 300)  -- display only, never used for a decision
);
CREATE INDEX ON app.attendance_event (business_date);
CREATE TRIGGER attendance_event_immutable BEFORE UPDATE OR DELETE ON app.attendance_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
-- The first check-in (or check-out) of a user and date stands; a second one under another client_uuid is rejected
-- attendance_duplicate (s4.5). Rows tombstoned by a data void no longer count.
CREATE UNIQUE INDEX attendance_event_once ON app.attendance_event (user_id, business_date, kind) WHERE voided_at IS NULL;

-- day_open and day_submit records (and the online Sales Submit, source online), s4.9.
CREATE TABLE app.route_day_event (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  kind                 text NOT NULL CHECK (kind IN ('day_open','day_submit')),
  source               text NOT NULL DEFAULT 'record' CHECK (source IN ('record','online')),
  route_ids            bigint[],                     -- day_open: routes opened
  online               boolean,
  bundle_valid_for     date,
  offline_start        boolean,
  scope                text CHECK (scope IN ('route_day','supervisor_day')),   -- day_submit
  submit_cycle         int CHECK (submit_cycle BETWEEN 1 AND 50),
  device_counts        jsonb,                        -- TypeCounts
  device_money         jsonb,                        -- MoneyTotals
  rejected_count       int CHECK (rejected_count >= 0),
  quarantined_count    int CHECK (quarantined_count >= 0),
  pending_count        int CHECK (pending_count >= 0),
  submitted_with_dues  boolean,
  dues_outstanding_mtk bigint CHECK (dues_outstanding_mtk >= 0),
  retailers_with_dues  int CHECK (retailers_with_dues >= 0),
  stock_slip_printed   boolean,
  CHECK (kind <> 'day_open' OR (route_ids IS NOT NULL AND cardinality(route_ids) BETWEEN 1 AND 20 AND online IS NOT NULL
                                AND bundle_valid_for IS NOT NULL AND offline_start IS NOT NULL)),
  CHECK (kind <> 'day_submit' OR (scope IS NOT NULL AND submit_cycle IS NOT NULL AND device_counts IS NOT NULL
                                  AND device_money IS NOT NULL))
);
CREATE INDEX ON app.route_day_event (business_date);
CREATE TRIGGER route_day_event_immutable BEFORE UPDATE OR DELETE ON app.route_day_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.route_day_event (route_id, business_date);
CREATE INDEX ON app.route_day_event (user_id, business_date);

-- Rain, hartal and other day exceptions raised from the field; decided by the zone's TSO.
CREATE TABLE app.day_exception (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  reason_code          text NOT NULL CHECK (reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  route_ids            bigint[] NOT NULL CHECK (cardinality(route_ids) BETWEEN 1 AND 60),
  from_date            date NOT NULL,
  to_date              date NOT NULL,
  note                 text CHECK (length(note) <= 500),
  status               text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  decided_by           bigint REFERENCES app.app_user(id),
  decided_at           timestamptz,
  decision_note        text CHECK (length(decision_note) <= 500),
  CHECK (to_date >= from_date)
);
CREATE INDEX ON app.day_exception (business_date);
CREATE TRIGGER day_exception_immutable BEFORE UPDATE OR DELETE ON app.day_exception
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'decided_by', 'decided_at', 'decision_note');

-- Append-only stock ledger: one event per SKU per Save (s12.5 item 3). qty_base is signed.
CREATE TABLE app.stock_movement (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  kind                 text NOT NULL CHECK (kind IN ('issue','return','adjustment','damaged','short','qc_return')),
  sku_id               bigint NOT NULL REFERENCES app.sku(id),
  qty_entered          int NOT NULL CHECK (qty_entered <> 0 AND qty_entered BETWEEN -10000000 AND 10000000),
  unit_entered         text NOT NULL CHECK (unit_entered IN ('stick','piece','dozen','pack')),
  pack_factor          int NOT NULL CHECK (pack_factor BETWEEN 1 AND 1000),
  qty_base             int NOT NULL CHECK (qty_base BETWEEN -10000000 AND 10000000),
  reason_code          text CHECK (reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  slip_printed         boolean NOT NULL,
  CHECK (qty_base = qty_entered * CASE WHEN unit_entered = 'pack' THEN pack_factor ELSE 1 END),
  CHECK (kind = 'adjustment' OR qty_entered > 0),
  CHECK (kind NOT IN ('adjustment','damaged','short') OR reason_code IS NOT NULL)
);
CREATE INDEX ON app.stock_movement (business_date);
CREATE TRIGGER stock_movement_immutable BEFORE UPDATE OR DELETE ON app.stock_movement
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.stock_movement (user_id, business_date);
CREATE INDEX ON app.stock_movement (sku_id, business_date);

-- Outlet visit (family root) with its close columns (visit_close) and both geo verdicts (s11.2, s11.3).
CREATE TABLE app.visit (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid          uuid NOT NULL,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64),
  visit_kind           text NOT NULL CHECK (visit_kind IN ('sr_call','amo_control_call','amo_joint_call','tso_visit','web_entry')),
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  opened_at            timestamptz NOT NULL,
  sequence_no          int NOT NULL CHECK (sequence_no BETWEEN 1 AND 1000),
  planned              boolean NOT NULL,
  assessed_user_id     bigint REFERENCES app.app_user(id),
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  -- the phone's verdict (DeviceGeoVerdict)
  verdict              text NOT NULL CHECK (verdict IN ('in_range','out_of_range','accuracy_too_low','no_fix','mocked','no_outlet_location')),
  distance_m           double precision CHECK (distance_m >= 0),
  radius_m_used        int NOT NULL CHECK (radius_m_used BETWEEN 10 AND 5000),
  max_accuracy_m_used  int NOT NULL CHECK (max_accuracy_m_used BETWEEN 10 AND 1000),
  location_basis       text NOT NULL CHECK (location_basis IN ('master','provisional','placeholder','none')),
  outlet_lat           double precision CHECK (outlet_lat BETWEEN -90 AND 90),
  outlet_lng           double precision CHECK (outlet_lng BETWEEN -180 AND 180),
  geo_action           text NOT NULL CHECK (geo_action IN ('sale_allowed','force_sale','blocked')),
  force_reason_code    text CHECK (force_reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  force_photo_uuid     uuid,
  -- the server's re-check (enrichment, s11.3)
  server_verdict       text CHECK (server_verdict IN ('in_range','out_of_range','accuracy_too_low','no_fix','mocked','no_outlet_location')),
  server_distance_m    double precision CHECK (server_distance_m >= 0),
  server_radius_m      int,
  server_max_accuracy_m int,
  server_checked_at    timestamptz,
  -- close (record visit_close, rank 1)
  close_client_uuid    uuid,
  close_received_at    timestamptz,
  close_captured_at    timestamptz,
  outcome_code         text CHECK (outcome_code IN ('sold','zero_sale_stock_ok','closed','owner_absent','refused','competitor_exclusive','abandoned','not_reached')),
  call_started_at      timestamptz,
  call_declined        boolean,
  ended_at             timestamptz,
  is_zero_sale         boolean,
  CHECK ((close_client_uuid IS NULL) = (outcome_code IS NULL)),
  CHECK (verdict <> 'mocked' OR geo_action <> 'sale_allowed'),     -- a mocked fix is never geo-valid (s11.2)
  PRIMARY KEY (id, business_date),
  UNIQUE (client_uuid, business_date),
  UNIQUE (external_ref, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.visit (business_date);
CREATE TRIGGER visit_immutable BEFORE UPDATE OR DELETE ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'server_verdict', 'server_distance_m', 'server_radius_m', 'server_max_accuracy_m', 'server_checked_at', 'close_client_uuid', 'close_received_at', 'close_captured_at', 'outcome_code', 'call_started_at', 'call_declined', 'ended_at', 'is_zero_sale');
CREATE TRIGGER visit_client_uuid_once BEFORE INSERT ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.client_uuid_once('visit');
INSERT INTO app.partition_policy (parent) VALUES ('app.visit');
CREATE UNIQUE INDEX visit_close_uuid ON app.visit (close_client_uuid, business_date);
CREATE INDEX ON app.visit (outlet_id, business_date);
CREATE INDEX ON app.visit (user_id, business_date);
CREATE INDEX ON app.visit (route_id, business_date);

-- Outlet of the day's route not visited, with a reason (record visit_skip).
CREATE TABLE app.visit_skip (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  reason_code          text NOT NULL CHECK (reason_code ~ '^[a-z][a-z0-9_]{1,40}$')
);
CREATE INDEX ON app.visit_skip (business_date);
CREATE TRIGGER visit_skip_immutable BEFORE UPDATE OR DELETE ON app.visit_skip
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.visit_skip (route_id, business_date);

-- Memo header (rank 1 of the visit family). The equations of docs/24 s7.4 hold for every stored row; a memo that fails them is quarantined arithmetic_mismatch and never reaches this table.
CREATE TABLE app.memo (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid          uuid NOT NULL,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64),
  visit_client_uuid    uuid NOT NULL,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  memo_no              text NOT NULL CHECK (memo_no ~ '^[a-z][a-z0-9]{3,31}-[0-9]{6}-[0-9]{3,4}$'),
  memo_kind            text NOT NULL CHECK (memo_kind IN ('sale','zero_sale')),
  committed_at         timestamptz NOT NULL,
  price_list_date      date NOT NULL,
  price_type           text NOT NULL CHECK (price_type IN ('outlet','cc','distributor')),
  gross_mtk            bigint NOT NULL CHECK (gross_mtk >= 0),
  offer_discount_mtk   bigint NOT NULL CHECK (offer_discount_mtk >= 0),
  drp_discount_mtk     bigint NOT NULL CHECK (drp_discount_mtk >= 0),
  qc_deduction_mtk     bigint NOT NULL CHECK (qc_deduction_mtk >= 0),
  round_adj_mtk        smallint NOT NULL CHECK (round_adj_mtk BETWEEN -5 AND 5),
  net_mtk              bigint NOT NULL,
  paid_mtk             bigint NOT NULL CHECK (paid_mtk >= 0),
  due_mtk              bigint NOT NULL,
  is_credit            boolean NOT NULL,
  outstanding_before_mtk bigint,
  line_count           smallint NOT NULL CHECK (line_count BETWEEN 0 AND 60),
  discount_line_count  smallint NOT NULL CHECK (discount_line_count BETWEEN 0 AND 120),
  qc_line_count        smallint NOT NULL CHECK (qc_line_count BETWEEN 0 AND 60),
  supersedes_client_uuid uuid,                       -- set on an edited memo
  edit_reason_code     text CHECK (edit_reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  offer_version_ids    bigint[] NOT NULL DEFAULT '{}',
  rounding_mode        text NOT NULL DEFAULT 'half_up_paisa' CHECK (rounding_mode IN ('half_up_paisa')),
  status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active','voided','superseded')),
  status_changed_at    timestamptz,
  voided_by_client_uuid uuid,                        -- the memo_void record
  superseded_by_client_uuid uuid,                    -- the editing memo
  server_flags         text[] NOT NULL DEFAULT '{}', -- enrichment (price mismatch and the like)
  CONSTRAINT memo_net_equation CHECK (net_mtk = gross_mtk - offer_discount_mtk - drp_discount_mtk - qc_deduction_mtk + round_adj_mtk),
  CONSTRAINT memo_net_whole_paisa CHECK (net_mtk % 10 = 0),
  CONSTRAINT memo_paid_due CHECK (paid_mtk + due_mtk = net_mtk),
  CONSTRAINT memo_credit_flag CHECK (is_credit = (due_mtk > 0)),
  CHECK ((status = 'voided') = (voided_by_client_uuid IS NOT NULL)),
  CHECK ((status = 'superseded') = (superseded_by_client_uuid IS NOT NULL)),
  CHECK ((supersedes_client_uuid IS NULL) = (edit_reason_code IS NULL)),
  PRIMARY KEY (id, business_date),
  UNIQUE (client_uuid, business_date),
  UNIQUE (external_ref, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo (business_date);
CREATE TRIGGER memo_immutable BEFORE UPDATE OR DELETE ON app.memo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'status_changed_at', 'voided_by_client_uuid', 'superseded_by_client_uuid', 'server_flags');
CREATE TRIGGER memo_client_uuid_once BEFORE INSERT ON app.memo
  FOR EACH ROW EXECUTE FUNCTION app.client_uuid_once('memo');
INSERT INTO app.partition_policy (parent) VALUES ('app.memo');
-- memo numbers are unique; the number embeds its business date (yyMMdd), so (memo_no, business_date) is the
-- partition-compatible key and the ingest path quarantines a reuse across dates as memo_no_duplicate.
CREATE UNIQUE INDEX memo_no_unique ON app.memo (memo_no, business_date);
CREATE INDEX memo_no_lookup ON app.memo (memo_no);
CREATE INDEX ON app.memo (visit_client_uuid);
CREATE INDEX ON app.memo (outlet_id, business_date);
CREATE INDEX ON app.memo (user_id, business_date);
CREATE INDEX ON app.memo (route_id, business_date);
CREATE INDEX memo_open_dues ON app.memo (outlet_id) WHERE is_credit AND status = 'active' AND voided_at IS NULL;

-- Memo line; gross_mtk = div_half_up(qty_base x base_price_mtk, price_per_qty) (s7.3).
CREATE TABLE app.memo_line (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid          uuid NOT NULL,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64),
  memo_client_uuid     uuid NOT NULL,
  line_no              smallint NOT NULL CHECK (line_no BETWEEN 1 AND 60),
  sku_id               bigint NOT NULL REFERENCES app.sku(id),
  line_kind            text NOT NULL CHECK (line_kind IN ('sale','drp_reward','promo_free','free_sample')),
  qty_entered          int NOT NULL CHECK (qty_entered BETWEEN 1 AND 10000000),
  unit_entered         text NOT NULL CHECK (unit_entered IN ('stick','piece','dozen','pack')),
  pack_factor          int NOT NULL CHECK (pack_factor BETWEEN 1 AND 1000),
  qty_base             int NOT NULL CHECK (qty_base BETWEEN 1 AND 10000000),
  price_type           text NOT NULL CHECK (price_type IN ('outlet','cc','distributor')),
  price_valid_from     date NOT NULL,
  base_price_mtk       bigint NOT NULL CHECK (base_price_mtk >= 0),
  price_per_qty        int NOT NULL CHECK (price_per_qty BETWEEN 1 AND 1000),
  gross_mtk            bigint NOT NULL CHECK (gross_mtk >= 0),
  offer_id             bigint REFERENCES app.offer(id),
  CHECK (qty_base = qty_entered * CASE WHEN unit_entered = 'pack' THEN pack_factor ELSE 1 END),
  CONSTRAINT memo_line_gross CHECK (gross_mtk = app.div_half_up(qty_base::bigint * base_price_mtk, price_per_qty)),
  PRIMARY KEY (id, business_date),
  UNIQUE (client_uuid, business_date),
  UNIQUE (external_ref, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo_line (business_date);
CREATE TRIGGER memo_line_immutable BEFORE UPDATE OR DELETE ON app.memo_line
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE TRIGGER memo_line_client_uuid_once BEFORE INSERT ON app.memo_line
  FOR EACH ROW EXECUTE FUNCTION app.client_uuid_once('memo_line');
INSERT INTO app.partition_policy (parent) VALUES ('app.memo_line');
CREATE UNIQUE INDEX memo_line_no ON app.memo_line (memo_client_uuid, line_no, business_date);
CREATE INDEX ON app.memo_line (sku_id, business_date);

-- One discount component of a memo as (sku, qty, value, kind) (replaces docs/16 memo_offer).
CREATE TABLE app.memo_discount (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  memo_client_uuid     uuid NOT NULL,
  kind                 text NOT NULL CHECK (kind IN ('offer','drp','free_goods')),
  sku_id               bigint REFERENCES app.sku(id),
  qty_base             int CHECK (qty_base BETWEEN 0 AND 10000000),
  value_mtk            bigint NOT NULL CHECK (value_mtk >= 0),
  offer_id             bigint REFERENCES app.offer(id),
  offer_version_id     bigint REFERENCES app.offer_version(id),
  basis_qty_base       int CHECK (basis_qty_base >= 0),   -- drp: empties collected in base units
  line_no              smallint CHECK (line_no BETWEEN 1 AND 60)
);
CREATE INDEX ON app.memo_discount (business_date);
CREATE TRIGGER memo_discount_immutable BEFORE UPDATE OR DELETE ON app.memo_discount
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.memo_discount (memo_client_uuid);
CREATE INDEX ON app.memo_discount (business_date, kind);

-- One QC fault line (record qc_line); applied_to_memo lines are deducted on the memo (s7.4).
CREATE TABLE app.qc_entry_line (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  qc_entry_id          bigint NOT NULL,              -- app.qc_entry (one per visit)
  visit_client_uuid    uuid NOT NULL,
  memo_client_uuid     uuid,
  applied_to_memo      boolean NOT NULL,
  sku_id               bigint NOT NULL REFERENCES app.sku(id),
  fault_type_code      text NOT NULL CHECK (fault_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  fault_group          text NOT NULL CHECK (fault_group IN ('MFC','MKT')),
  qty_base             int NOT NULL CHECK (qty_base BETWEEN 1 AND 100000),
  unit_price_mtk       bigint NOT NULL CHECK (unit_price_mtk >= 0),
  settlement_mtk       bigint NOT NULL CHECK (settlement_mtk >= 0),
  CHECK (NOT applied_to_memo OR memo_client_uuid IS NOT NULL)
);
CREATE INDEX ON app.qc_entry_line (business_date);
CREATE TRIGGER qc_entry_line_immutable BEFORE UPDATE OR DELETE ON app.qc_entry_line
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.qc_entry_line (qc_entry_id);
CREATE INDEX ON app.qc_entry_line (memo_client_uuid);
CREATE INDEX ON app.qc_entry_line (business_date, sku_id);

-- Every print attempt (memo, reprint, stock slip, day summary, void slip, due receipt).
CREATE TABLE app.print_event (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  document_kind        text NOT NULL CHECK (document_kind IN ('memo','memo_reprint','stock_slip','day_summary','void_slip','due_receipt')),
  memo_client_uuid     uuid,
  ref_client_uuid      uuid,
  print_count          int NOT NULL CHECK (print_count BETWEEN 1 AND 100),
  outcome              text NOT NULL CHECK (outcome IN ('printed','failed','failed_user')),
  user_confirmed       boolean,
  template_version     int NOT NULL CHECK (template_version BETWEEN 1 AND 999),
  printer_model        text CHECK (length(printer_model) <= 40)
);
CREATE INDEX ON app.print_event (business_date);
CREATE TRIGGER print_event_immutable BEFORE UPDATE OR DELETE ON app.print_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.print_event (memo_client_uuid);

-- Void of a memo (tombstone; the memo row turns status voided, never deleted).
CREATE TABLE app.memo_void (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  memo_client_uuid     uuid NOT NULL,
  memo_no              text NOT NULL CHECK (memo_no ~ '^[a-z][a-z0-9]{3,31}-[0-9]{6}-[0-9]{3,4}$'),
  reason_code          text NOT NULL CHECK (reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  retailer_ack         boolean NOT NULL,
  note                 text CHECK (length(note) <= 500)
);
CREATE INDEX ON app.memo_void (business_date);
CREATE TRIGGER memo_void_immutable BEFORE UPDATE OR DELETE ON app.memo_void
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE UNIQUE INDEX memo_void_once ON app.memo_void (memo_client_uuid) WHERE voided_at IS NULL;

-- Cash collected against a credit memo (record due_collection).
CREATE TABLE app.due_collection (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  against_memo_client_uuid uuid NOT NULL,
  against_memo_no      text NOT NULL CHECK (length(against_memo_no) <= 40),
  against_memo_business_date date NOT NULL,
  amount_mtk           bigint NOT NULL CHECK (amount_mtk >= 10),
  is_full_settlement   boolean NOT NULL,
  outstanding_before_mtk bigint NOT NULL CHECK (outstanding_before_mtk >= 0),
  payment_mode         text NOT NULL DEFAULT 'cash' CHECK (payment_mode IN ('cash')),
  visit_client_uuid    uuid,
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean
);
CREATE INDEX ON app.due_collection (business_date);
CREATE TRIGGER due_collection_immutable BEFORE UPDATE OR DELETE ON app.due_collection
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.due_collection (outlet_id, business_date);
CREATE INDEX ON app.due_collection (against_memo_client_uuid);

-- One answer of an in-visit survey.
CREATE TABLE app.survey_response (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  visit_client_uuid    uuid NOT NULL,
  survey_id            bigint NOT NULL,
  survey_version       int NOT NULL CHECK (survey_version >= 1),
  question_id          bigint NOT NULL,
  answer_type          text NOT NULL CHECK (answer_type IN ('bool','num','option','text','photo_only')),
  answer_bool          boolean,
  answer_num           numeric,
  answer_option_code   text CHECK (answer_option_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  answer_text          text CHECK (length(answer_text) <= 1000),
  photo_uuid           uuid
);
CREATE INDEX ON app.survey_response (business_date);
CREATE TRIGGER survey_response_immutable BEFORE UPDATE OR DELETE ON app.survey_response
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.survey_response (visit_client_uuid);

-- AMO / TSO distribution check of a visited outlet.
CREATE TABLE app.distribution_check (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  visit_client_uuid    uuid NOT NULL,
  posm_present         boolean,
  note                 text CHECK (length(note) <= 500)
);
CREATE INDEX ON app.distribution_check (business_date);
CREATE TRIGGER distribution_check_immutable BEFORE UPDATE OR DELETE ON app.distribution_check
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.distribution_check (visit_client_uuid);

-- Brand presence and out-of-stock per distribution check (OOS implies present).
CREATE TABLE app.distribution_check_line (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  check_client_uuid    uuid NOT NULL,
  brand_id             bigint NOT NULL REFERENCES app.product_node(id),
  present              boolean NOT NULL,
  oos                  boolean NOT NULL,
  CHECK (NOT oos OR present)
);
CREATE INDEX ON app.distribution_check_line (business_date);
CREATE TRIGGER distribution_check_line_immutable BEFORE UPDATE OR DELETE ON app.distribution_check_line
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.distribution_check_line (check_client_uuid);

-- Joint-call assessment or retailer questionnaire (AMO, TSO).
CREATE TABLE app.call_assessment (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  kind                 text NOT NULL CHECK (kind IN ('joint_call','retailer_questionnaire')),
  visit_client_uuid    uuid,
  visit_plan_outlet_client_uuid uuid,
  rubric_id            bigint NOT NULL,
  rubric_version       int NOT NULL CHECK (rubric_version >= 1),
  assessed_user_id     bigint REFERENCES app.app_user(id),
  total_score          int NOT NULL CHECK (total_score BETWEEN 0 AND 1000),
  max_score            int NOT NULL CHECK (max_score BETWEEN 0 AND 1000),
  delegate_task        boolean NOT NULL,
  CHECK (total_score <= max_score)
);
CREATE INDEX ON app.call_assessment (business_date);
CREATE TRIGGER call_assessment_immutable BEFORE UPDATE OR DELETE ON app.call_assessment
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.call_assessment (visit_client_uuid);

-- One criterion answer of a call assessment.
CREATE TABLE app.call_assessment_answer (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  assessment_client_uuid uuid NOT NULL,
  criterion_id         bigint NOT NULL,
  score                smallint CHECK (score BETWEEN 1 AND 5),
  answer_text          text CHECK (length(answer_text) <= 1000),
  answer_bool          boolean
);
CREATE INDEX ON app.call_assessment_answer (business_date);
CREATE TRIGGER call_assessment_answer_immutable BEFORE UPDATE OR DELETE ON app.call_assessment_answer
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.call_assessment_answer (assessment_client_uuid);

-- New, close, info, cluster and location requests from the field (s12.2); client_uuid is the request_uuid.
CREATE TABLE app.outlet_change_request (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  request_type         text NOT NULL CHECK (request_type IN ('new','close','info','cluster','location','route_add')),
  outlet_id            bigint REFERENCES app.outlet(id),
  proposed             jsonb NOT NULL CHECK (jsonb_typeof(proposed) = 'object'),   -- OutletProposal
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  photo_uuids          uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(photo_uuids) <= 4),
  origin_visit_client_uuid uuid,
  note                 text CHECK (length(note) <= 500),
  status               text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','approved','rejected','lapsed','discarded')),
  status_changed_at    timestamptz,
  verified_by          bigint REFERENCES app.app_user(id),
  verified_at          timestamptz,
  decided_by           bigint REFERENCES app.app_user(id),
  decided_at           timestamptz,
  decision_reason      text CHECK (length(decision_reason) <= 500),
  created_outlet_id    bigint REFERENCES app.outlet(id),
  CHECK ((request_type = 'new') = (outlet_id IS NULL)),
  CHECK (verified_by IS NULL OR verified_by <> user_id),                -- separation of duties (s8.6)
  CHECK (decided_by IS NULL OR (decided_by <> user_id AND decided_by IS DISTINCT FROM verified_by))
);
CREATE INDEX ON app.outlet_change_request (business_date);
CREATE TRIGGER outlet_change_request_immutable BEFORE UPDATE OR DELETE ON app.outlet_change_request
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'status_changed_at', 'verified_by', 'verified_at', 'decided_by', 'decided_at', 'decision_reason', 'created_outlet_id');
CREATE INDEX ON app.outlet_change_request (status, business_date);
CREATE INDEX ON app.outlet_change_request (outlet_id);

-- Task (record task or the online create); client_uuid is the task_uuid. user_id is the creator.
CREATE TABLE app.task (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  task_type_code       text NOT NULL CHECK (task_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  assignee_user_id     bigint NOT NULL REFERENCES app.app_user(id),
  outlet_id            bigint REFERENCES app.outlet(id),
  title                text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description          text CHECK (length(description) <= 1000),
  due_date             date,
  source_visit_client_uuid uuid,
  source               text NOT NULL DEFAULT 'record' CHECK (source IN ('record','online')),
  status               text NOT NULL DEFAULT 'ongoing' CHECK (status IN ('ongoing','completed','cancelled')),
  status_changed_at    timestamptz,
  cancelled_by         bigint REFERENCES app.app_user(id)
);
CREATE INDEX ON app.task (business_date);
CREATE TRIGGER task_immutable BEFORE UPDATE OR DELETE ON app.task
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'status_changed_at', 'cancelled_by');
CREATE INDEX ON app.task (assignee_user_id, status);

-- Resolve / reopen of a task by its assignee (record task_event).
CREATE TABLE app.task_event (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  task_uuid            uuid NOT NULL,
  event                text NOT NULL CHECK (event IN ('resolved','reopened')),
  note                 text CHECK (length(note) <= 500)
);
CREATE INDEX ON app.task_event (business_date);
CREATE TRIGGER task_event_immutable BEFORE UPDATE OR DELETE ON app.task_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.task_event (task_uuid);

-- TSO visit plan for a date.
CREATE TABLE app.visit_plan (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  plan_date            date NOT NULL,
  note                 text CHECK (length(note) <= 500)
);
CREATE INDEX ON app.visit_plan (business_date);
CREATE TRIGGER visit_plan_immutable BEFORE UPDATE OR DELETE ON app.visit_plan
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.visit_plan (user_id, plan_date);

-- Outlet of a TSO visit plan.
CREATE TABLE app.visit_plan_outlet (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  plan_client_uuid     uuid NOT NULL,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id)
);
CREATE INDEX ON app.visit_plan_outlet (business_date);
CREATE TRIGGER visit_plan_outlet_immutable BEFORE UPDATE OR DELETE ON app.visit_plan_outlet
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.visit_plan_outlet (plan_client_uuid);

-- TSO leave, decided by the DMO on the web (D24-55).
CREATE TABLE app.leave_application (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  leave_type_code      text NOT NULL CHECK (leave_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  from_date            date NOT NULL,
  days                 int NOT NULL CHECK (days BETWEEN 1 AND 365),
  to_date              date GENERATED ALWAYS AS (from_date + (days - 1)) STORED,
  reason               text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  status               text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  decided_by           bigint REFERENCES app.app_user(id),
  decided_at           timestamptz,
  decision_note        text CHECK (length(decision_note) <= 500),
  CHECK (decided_by IS NULL OR decided_by <> user_id)
);
CREATE INDEX ON app.leave_application (business_date);
CREATE TRIGGER leave_application_immutable BEFORE UPDATE OR DELETE ON app.leave_application
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'decided_by', 'decided_at', 'decision_note');
CREATE INDEX ON app.leave_application (user_id, from_date);

-- Feedback from the TSO app.
CREATE TABLE app.feedback (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  category_code        text NOT NULL CHECK (category_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  title                text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description          text NOT NULL CHECK (length(description) BETWEEN 1 AND 2000),
  photo_uuid           uuid
);
CREATE INDEX ON app.feedback (business_date);
CREATE TRIGGER feedback_immutable BEFORE UPDATE OR DELETE ON app.feedback
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');

-- Photo metadata (record media_meta; client_uuid is the media uuid). The blob is in Blob Storage (s4.11).
CREATE TABLE app.media (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  purpose              text NOT NULL CHECK (purpose IN ('force_sale','outlet_capture','outlet_verification','survey','feedback','support','gift_photo')),
  ref_type             text NOT NULL,
  ref_client_uuid      uuid NOT NULL,
  sha256               bytea NOT NULL CHECK (length(sha256) = 32),
  phash                text CHECK (phash ~ '^[0-9a-f]{16}$'),
  bytes                int NOT NULL CHECK (bytes BETWEEN 1 AND 307200),
  width                int NOT NULL CHECK (width BETWEEN 1 AND 4096),
  height               int NOT NULL CHECK (height BETWEEN 1 AND 4096),
  mime                 text NOT NULL DEFAULT 'image/jpeg' CHECK (mime = 'image/jpeg'),
  blob_path            text NOT NULL UNIQUE CHECK (blob_path ~ '^photos/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9a-f-]{36}/[0-9a-f-]{36}[.]jpg$'),
  taken_at             timestamptz NOT NULL,
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  status               text NOT NULL DEFAULT 'pending_blob' CHECK (status IN ('pending_blob','stored','mismatch','missing')),
  stored_at            timestamptz,
  blob_bytes           int,
  blob_sha256          bytea
);
CREATE INDEX ON app.media (business_date);
CREATE TRIGGER media_immutable BEFORE UPDATE OR DELETE ON app.media
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'status', 'stored_at', 'blob_bytes', 'blob_sha256');
CREATE INDEX ON app.media (ref_client_uuid);
CREATE INDEX media_pending ON app.media (received_at) WHERE status = 'pending_blob';

-- Batched low-power breadcrumb (only while cfg.geo.breadcrumbs_enabled, D24-46).
CREATE TABLE app.geo_breadcrumb (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean
);
CREATE INDEX ON app.geo_breadcrumb (business_date);
CREATE TRIGGER geo_breadcrumb_immutable BEFORE UPDATE OR DELETE ON app.geo_breadcrumb
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.geo_breadcrumb (user_id, business_date);

-- Config applied on the phone (record config_ack, s9.3 item 5).
CREATE TABLE app.cfg_ack (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  acked_config_version bigint NOT NULL CHECK (acked_config_version >= 0),
  applied_at           timestamptz NOT NULL,
  keys                 text[] NOT NULL DEFAULT '{}' CHECK (cardinality(keys) <= 100)
);
CREATE INDEX ON app.cfg_ack (business_date);
CREATE TRIGGER cfg_ack_immutable BEFORE UPDATE OR DELETE ON app.cfg_ack
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.cfg_ack (acked_config_version);
CREATE INDEX ON app.cfg_ack (device_id, acked_config_version);

-- AV / KV item shown or skipped during a call (telemetry class, s4.14 item 5).
CREATE TABLE app.content_view (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  visit_client_uuid    uuid NOT NULL,
  content_id           bigint NOT NULL REFERENCES app.content_item(id),
  content_version      int NOT NULL CHECK (content_version >= 1),
  kind                 text NOT NULL CHECK (kind IN ('av','kv')),
  outcome              text NOT NULL CHECK (outcome IN ('viewed','skipped_missing','skipped_user')),
  sequence_no          smallint NOT NULL CHECK (sequence_no BETWEEN 1 AND 20),
  started_at           timestamptz,
  duration_ms          int CHECK (duration_ms BETWEEN 0 AND 3600000)
);
CREATE INDEX ON app.content_view (business_date);
CREATE TRIGGER content_view_immutable BEFORE UPDATE OR DELETE ON app.content_view
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.content_view (content_id, business_date);

-- Loyalty redemption basket (Diamond League, campaign); the server debits app.loyalty_ledger.
CREATE TABLE app.redemption (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  programme_id         bigint NOT NULL REFERENCES app.programme(id),
  visit_client_uuid    uuid,
  balance_before_points int NOT NULL CHECK (balance_before_points >= 0),
  points_total         int NOT NULL CHECK (points_total BETWEEN 1 AND 100000),
  cash_points          int NOT NULL CHECK (cash_points BETWEEN 0 AND 100000),
  cash_mtk             bigint NOT NULL CHECK (cash_mtk >= 0),
  cash_rate_mtk_per_point bigint NOT NULL CHECK (cash_rate_mtk_per_point BETWEEN 0 AND 100000),
  line_count           smallint NOT NULL CHECK (line_count BETWEEN 0 AND 20),
  confirmed_at         timestamptz NOT NULL,
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  server_flags         text[] NOT NULL DEFAULT '{}'  -- negative_balance and the like (cfg.loyalty.negative_balance_policy)
);
CREATE INDEX ON app.redemption (business_date);
CREATE TRIGGER redemption_immutable BEFORE UPDATE OR DELETE ON app.redemption
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at', 'server_flags');
CREATE INDEX ON app.redemption (outlet_id, programme_id);

-- One gift of a redemption basket; points_total = qty x points_each.
CREATE TABLE app.redemption_line (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  redemption_client_uuid uuid NOT NULL,
  gift_id              bigint NOT NULL REFERENCES app.gift(id),
  qty                  smallint NOT NULL CHECK (qty BETWEEN 1 AND 50),
  points_each          int NOT NULL CHECK (points_each BETWEEN 1 AND 100000),
  points_total         int NOT NULL CHECK (points_total BETWEEN 1 AND 1000000),
  CHECK (points_total = qty * points_each)
);
CREATE INDEX ON app.redemption_line (business_date);
CREATE TRIGGER redemption_line_immutable BEFORE UPDATE OR DELETE ON app.redemption_line
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.redemption_line (redemption_client_uuid);

-- Gift hand-over photo: one per Astha assignment, one per redeemed campaign unit (gift_photo_exists).
CREATE TABLE app.gift_photo (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  sig                  text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),  -- ES256 over the record (s8.3)
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  programme_kind       text NOT NULL CHECK (programme_kind IN ('diamond_league','astha','campaign','superstar')),
  outlet_id            bigint NOT NULL REFERENCES app.outlet(id),
  gift_id              bigint NOT NULL REFERENCES app.gift(id),
  gift_assignment_id   bigint REFERENCES app.gift_assignment(id),
  redemption_client_uuid uuid,
  unit_no              smallint CHECK (unit_no BETWEEN 1 AND 50),
  photo_uuid           uuid NOT NULL,
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  CHECK (gift_assignment_id IS NOT NULL OR redemption_client_uuid IS NOT NULL)
);
CREATE INDEX ON app.gift_photo (business_date);
CREATE TRIGGER gift_photo_immutable BEFORE UPDATE OR DELETE ON app.gift_photo
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE UNIQUE INDEX gift_photo_one_per_assignment ON app.gift_photo (gift_assignment_id)
  WHERE gift_assignment_id IS NOT NULL AND voided_at IS NULL;
CREATE UNIQUE INDEX gift_photo_one_per_unit ON app.gift_photo (redemption_client_uuid, gift_id, unit_no)
  WHERE redemption_client_uuid IS NOT NULL AND voided_at IS NULL;

-- AMO check of the retail price of a SKU during a visit.
CREATE TABLE app.price_compliance_check (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  visit_client_uuid    uuid NOT NULL,
  sku_id               bigint NOT NULL REFERENCES app.sku(id),
  price_type           text NOT NULL CHECK (price_type IN ('outlet','cc','distributor')),
  reference_price_mtk  bigint NOT NULL CHECK (reference_price_mtk >= 0),
  observed_price_mtk   bigint NOT NULL CHECK (observed_price_mtk >= 0),
  compliant            boolean NOT NULL,
  note                 text CHECK (length(note) <= 300)
);
CREATE INDEX ON app.price_compliance_check (business_date);
CREATE TRIGGER price_compliance_check_immutable BEFORE UPDATE OR DELETE ON app.price_compliance_check
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.price_compliance_check (visit_client_uuid);

-- Review of a risk signal (record risk_review from the AMO Exceptions screen, or the online review_uuid). Append-only.
CREATE TABLE app.risk_signal_review (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  signal_id            bigint NOT NULL REFERENCES app.risk_signal(id),
  action               text NOT NULL CHECK (action IN ('reviewed','dismissed','confirmed')),
  note                 text CHECK (length(note) <= 500),
  source               text NOT NULL DEFAULT 'record' CHECK (source IN ('record','online'))
);
CREATE INDEX ON app.risk_signal_review (business_date);
CREATE TRIGGER risk_signal_review_immutable BEFORE UPDATE OR DELETE ON app.risk_signal_review
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.risk_signal_review (signal_id, captured_at);

-- Sampled screen and action events (cfg.app.activity_log_sample_pct); one row per record.
CREATE TABLE app.activity_log (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  events               jsonb NOT NULL CHECK (jsonb_typeof(events) = 'array' AND jsonb_array_length(events) BETWEEN 1 AND 200)
);
CREATE INDEX ON app.activity_log (business_date);
CREATE TRIGGER activity_log_immutable BEFORE UPDATE OR DELETE ON app.activity_log
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');

-- Scrubbed crash, ANR and handled-error report from a phone.
CREATE TABLE app.app_error (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  occurred_at          timestamptz NOT NULL,
  kind                 text NOT NULL CHECK (kind IN ('crash','anr','handled')),
  exception_class      text NOT NULL CHECK (length(exception_class) <= 200),
  message              text CHECK (length(message) <= 500),
  stack                text CHECK (length(stack) <= 16000),
  screen               text CHECK (length(screen) <= 60),
  app_version          text NOT NULL CHECK (app_version ~ '^[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}[+][0-9]{1,10}$')
);
CREATE INDEX ON app.app_error (business_date);
CREATE TRIGGER app_error_immutable BEFORE UPDATE OR DELETE ON app.app_error
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.app_error (app_version, occurred_at);

-- A memo number consumed without a memo (explains the gap in the memo-number-gaps report).
CREATE TABLE app.sale_abort (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  memo_no              text NOT NULL CHECK (memo_no ~ '^[a-z][a-z0-9]{3,31}-[0-9]{6}-[0-9]{3,4}$'),
  reason               text NOT NULL CHECK (reason IN ('user_cancelled','app_killed','commit_failed')),
  visit_client_uuid    uuid,
  outlet_id            bigint REFERENCES app.outlet(id)
);
CREATE INDEX ON app.sale_abort (business_date);
CREATE TRIGGER sale_abort_immutable BEFORE UPDATE OR DELETE ON app.sale_abort
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.sale_abort (memo_no);

-- Acceptance of a notice (record consent_accept, once per user and policy version on a phone).
CREATE TABLE app.user_consent (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,          -- the record's client_uuid (idempotency key, docs/24 s3.3)
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,                -- Asia/Dhaka date of the trusted capture time
  business_date_device date,                         -- the phone's date when the server re-dated the row (s3.8 item 3)
  user_id              bigint NOT NULL REFERENCES app.app_user(id),   -- from the token, never from the body
  device_id            bigint,                       -- FK to app.device added in V0010
  acting_for_user_id   bigint REFERENCES app.app_user(id),
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
  voided_at            timestamptz,                  -- admin data void tombstone (D24-45); never deleted
  external_ref         varchar(64) UNIQUE,
  policy_key           text NOT NULL CHECK (policy_key IN ('location_notice')),
  policy_version       int NOT NULL CHECK (policy_version >= 1),
  accepted             boolean NOT NULL,
  locale               text NOT NULL CHECK (locale IN ('bn','en')),
  shown_at             timestamptz NOT NULL
);
CREATE INDEX ON app.user_consent (business_date);
CREATE TRIGGER user_consent_immutable BEFORE UPDATE OR DELETE ON app.user_consent
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');
CREATE INDEX ON app.user_consent (user_id, policy_key, policy_version);

ALTER TABLE app.qc_entry_line ADD CONSTRAINT qc_entry_line_entry_fk FOREIGN KEY (qc_entry_id) REFERENCES app.qc_entry(id);

-- ---------- server-side trails written by the ingest handlers and online commands ----------

-- Outstanding dues per outlet as an append-only ledger: + raises the outlet's balance (a credit memo's due, an
-- opening balance), - lowers it (a collection, the void or supersession of a credit memo). One entry per source
-- record and kind, so a replayed projection writes nothing twice.
CREATE TABLE app.due_ledger (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id           bigint NOT NULL REFERENCES app.outlet(id),
  business_date       date NOT NULL,
  entry_kind          text NOT NULL CHECK (entry_kind IN ('memo_due','collection','memo_void','memo_superseded',
                                                          'opening_balance','adjustment')),
  amount_mtk          bigint NOT NULL CHECK (amount_mtk <> 0),
  memo_client_uuid    uuid,
  memo_no             text CHECK (length(memo_no) <= 40),
  source_client_uuid  uuid NOT NULL,                   -- the memo, due_collection, memo_void or adjustment that caused it
  user_id             bigint REFERENCES app.app_user(id),
  note                text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_client_uuid, entry_kind),
  CHECK (entry_kind NOT IN ('memo_due','opening_balance') OR amount_mtk > 0),
  CHECK (entry_kind NOT IN ('collection','memo_void','memo_superseded') OR amount_mtk < 0)
);
CREATE INDEX ON app.due_ledger (outlet_id, business_date);
CREATE INDEX ON app.due_ledger (memo_client_uuid);
CREATE TRIGGER due_ledger_append_only BEFORE UPDATE OR DELETE ON app.due_ledger FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- Phase 2 extension point (s12.5 item 3): the indent portal's ledger, same shape as stock_movement, empty in Phase 1.
CREATE TABLE app.indent_movement (LIKE app.stock_movement INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING IDENTITY INCLUDING INDEXES);
CREATE TRIGGER indent_movement_immutable BEFORE UPDATE OR DELETE ON app.indent_movement
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('voided_at');

-- Verify / approve / reject / lapse trail of an outlet change request. Rows from the AMO app (record
-- outlet_request_verification) carry its client_uuid; web and job events have none.
CREATE TABLE app.outlet_request_event (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid         uuid UNIQUE,
  request_uuid        uuid NOT NULL,                   -- app.outlet_change_request.client_uuid
  event               text NOT NULL CHECK (event IN ('created','verified','discarded','approved','rejected','lapsed')),
  actor_user_id       bigint REFERENCES app.app_user(id),
  via                 text NOT NULL CHECK (via IN ('device','web','job')),
  business_date       date NOT NULL,
  at                  timestamptz NOT NULL,             -- captured_at for device events
  device_id           bigint,
  config_version      bigint,
  sub_channel_id      bigint REFERENCES app.sub_channel(id),
  geo_class           text REFERENCES app.geo_class_def(geo_class),
  fix_status          text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat             double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng             double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m      double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock         boolean,
  photo_uuid          uuid,
  note                text CHECK (length(note) <= 500),
  received_at         timestamptz NOT NULL DEFAULT now(),
  created_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (via <> 'device' OR client_uuid IS NOT NULL)
);
CREATE INDEX ON app.outlet_request_event (request_uuid, at);
CREATE TRIGGER outlet_request_event_append_only BEFORE UPDATE OR DELETE ON app.outlet_request_event
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- Final Submit of a zone-day: online-only, once per zone and date unless reopened (s4.9 rules 3 and 4).
CREATE TABLE app.final_submit (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid         uuid NOT NULL UNIQUE,            -- replay returns the first success
  zone_id             bigint NOT NULL REFERENCES app.zone(id),
  business_date       date NOT NULL,
  submitted_by        bigint NOT NULL REFERENCES app.app_user(id),
  submitted_at        timestamptz NOT NULL DEFAULT now(),
  via                 text NOT NULL CHECK (via IN ('app','web')),
  route_states        jsonb NOT NULL DEFAULT '[]'::jsonb,   -- the preview the submitter saw
  late_rows           int NOT NULL DEFAULT 0 CHECK (late_rows >= 0),
  reopened_at         timestamptz,
  reopened_by         bigint REFERENCES app.app_user(id),
  reopen_reason       text,
  reopen_client_uuid  uuid UNIQUE,
  created_at          timestamptz NOT NULL DEFAULT now(),
  CHECK ((reopened_at IS NULL) = (reopened_by IS NULL))
);
CREATE UNIQUE INDEX final_submit_once ON app.final_submit (zone_id, business_date) WHERE reopened_at IS NULL;

-- Void of an effective Sales Submit (POST /v1/day/submit-void); append-only.
CREATE TABLE app.submit_void_event (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid         uuid NOT NULL UNIQUE,
  scope               text NOT NULL DEFAULT 'route_day' CHECK (scope IN ('route_day','supervisor_day')),
  route_id            bigint REFERENCES app.route(id),
  subject_user_id     bigint REFERENCES app.app_user(id),   -- supervisor-day voids
  business_date       date NOT NULL,
  voided_cycle        int NOT NULL CHECK (voided_cycle >= 1),
  reason_code         text NOT NULL CHECK (reason_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  note                text,
  voided_by           bigint NOT NULL REFERENCES app.app_user(id),
  voided_at           timestamptz NOT NULL DEFAULT now(),
  CHECK ((scope = 'route_day') = (route_id IS NOT NULL)),
  UNIQUE (route_id, business_date, voided_cycle)
);
CREATE TRIGGER submit_void_event_append_only BEFORE UPDATE OR DELETE ON app.submit_void_event
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- Admin data void of a route-day (D24-45): rows captured before barrier_at are tombstoned and late ones rejected
-- voided_by_admin; rows captured after it are accepted.
CREATE TABLE app.route_day_void_barrier (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid         uuid NOT NULL UNIQUE,
  route_id            bigint NOT NULL REFERENCES app.route(id),
  business_date       date NOT NULL,
  barrier_at          timestamptz NOT NULL DEFAULT now(),
  voided_by           bigint NOT NULL REFERENCES app.app_user(id),
  approved_by         bigint REFERENCES app.app_user(id),
  reason              text NOT NULL,
  affected            jsonb NOT NULL DEFAULT '{}'::jsonb,   -- rows tombstoned per record type
  created_at          timestamptz NOT NULL DEFAULT now(),
  CHECK (approved_by IS NULL OR approved_by <> voided_by)
);
CREATE INDEX ON app.route_day_void_barrier (route_id, business_date);
CREATE TRIGGER route_day_void_barrier_append_only BEFORE UPDATE OR DELETE ON app.route_day_void_barrier
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- Programme eligibility shown as dots before an outlet (BundleOutlet.programme_flags, cfg.ui.outlet_badges).
CREATE TABLE app.outlet_programme (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id           bigint NOT NULL REFERENCES app.outlet(id),
  programme_code      text NOT NULL CHECK (programme_code ~ '^[a-z][a-z0-9_]{1,30}$'),
  valid_from          date NOT NULL,
  valid_to            date,
  created_at          timestamptz NOT NULL DEFAULT now(),
  created_by          bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (outlet_id WITH =, programme_code WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
