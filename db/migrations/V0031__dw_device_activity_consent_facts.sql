-- V0031 device-captured facts of docs/16 s8.9.5 (M-123 to M-125; D-559, G-qa-91; docs/requests/backend-reports-device-facts-ddl.md,
-- F-SYS-096): dw.fact_device_integrity and dw.fact_activity (monthly partitions on business_date, maintained by
-- app.ensure_partitions), dw.agg_daily_screen_use (the daily rollup kept for ever) and dw.fact_consent. DDL as docs/16 lists
-- it. No surrogate dimensions exist: user_key is app.app_user.id and device_key is app.device.id. The worker projects
-- app.activity_log, app.user_consent and the device status reports into them.

SET lock_timeout = '5s';

CREATE TABLE dw.fact_device_integrity (
  device_key bigint NOT NULL, user_key bigint NOT NULL, business_date date NOT NULL, observed_at timestamptz NOT NULL,
  app_version text, os_version text, device_model text, battery_capacity_mah int,
  mock_app_present boolean, developer_options boolean, rooted_hint boolean, play_integrity_verdict text, attestation_level text,
  trust_level smallint, time_skew_s int, clock_changed_count smallint,
  ready_bound boolean, ready_bundle_next_day boolean, ready_printer_paired boolean, ready_test_print boolean, ready_permissions boolean,
  free_storage_mb int, battery_pct smallint,
  source_uuid uuid NOT NULL, source text NOT NULL DEFAULT 'native', import_run_id bigint,
  PRIMARY KEY (device_key, business_date, observed_at)
) PARTITION BY RANGE (business_date);

CREATE TABLE dw.fact_activity (
  user_key bigint NOT NULL, device_key bigint NOT NULL, business_date date NOT NULL, occurred_at timestamptz NOT NULL,
  role text NOT NULL, screen text NOT NULL, action text NOT NULL, seq int NOT NULL, source_uuid uuid NOT NULL,
  PRIMARY KEY (user_key, business_date, occurred_at, seq)
) PARTITION BY RANGE (business_date);

CREATE TABLE dw.agg_daily_screen_use (
  business_date date NOT NULL, role text NOT NULL, screen text NOT NULL, action text NOT NULL, users int NOT NULL, events int NOT NULL,
  PRIMARY KEY (business_date, role, screen, action)
);

CREATE TABLE dw.fact_consent (
  user_key bigint NOT NULL, policy_version text NOT NULL, accepted_at timestamptz NOT NULL, device_key bigint, business_date date NOT NULL,
  text_sha256 bytea, source_uuid uuid NOT NULL,
  PRIMARY KEY (user_key, policy_version, accepted_at)
);

INSERT INTO app.partition_policy (parent) VALUES ('dw.fact_device_integrity'), ('dw.fact_activity');
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');

COMMENT ON TABLE dw.fact_device_integrity IS 'One row is a phone''s integrity and readiness state observed at a login or bundle download.
owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.fact_device_integrity.device_key IS 'Phone (app.device.id; no device dimension table).';
COMMENT ON COLUMN dw.fact_device_integrity.user_key IS 'User logged in on the phone (app.app_user.id; no user dimension table).';
COMMENT ON COLUMN dw.fact_device_integrity.business_date IS 'Asia/Dhaka business date of the observation.';
COMMENT ON COLUMN dw.fact_device_integrity.observed_at IS 'UTC time of the login or bundle download the state was taken from.';
COMMENT ON COLUMN dw.fact_device_integrity.app_version IS 'App version on the phone.';
COMMENT ON COLUMN dw.fact_device_integrity.os_version IS 'Android version of the phone.';
COMMENT ON COLUMN dw.fact_device_integrity.device_model IS 'Manufacturer and model of the phone.';
COMMENT ON COLUMN dw.fact_device_integrity.battery_capacity_mah IS 'Battery design capacity in mAh, when the phone reports it.';
COMMENT ON COLUMN dw.fact_device_integrity.mock_app_present IS 'True when a mock-location app was installed; null = not reported.';
COMMENT ON COLUMN dw.fact_device_integrity.developer_options IS 'True when developer options were on; null = not reported.';
COMMENT ON COLUMN dw.fact_device_integrity.rooted_hint IS 'True when any root hint was reported; false when the hint list was empty; null = unknown (older phone).';
COMMENT ON COLUMN dw.fact_device_integrity.play_integrity_verdict IS 'Play Integrity verdict of the observation; null when unavailable.';
COMMENT ON COLUMN dw.fact_device_integrity.attestation_level IS 'Key attestation security level of the phone''s key.';
COMMENT ON COLUMN dw.fact_device_integrity.trust_level IS 'Server trust level of the phone at the observation (ordinal).';
COMMENT ON COLUMN dw.fact_device_integrity.time_skew_s IS 'Phone clock minus server time, in seconds.';
COMMENT ON COLUMN dw.fact_device_integrity.clock_changed_count IS 'Number of manual clock changes reported since the previous observation.';
COMMENT ON COLUMN dw.fact_device_integrity.ready_bound IS 'Readiness: the phone is bound to the user.';
COMMENT ON COLUMN dw.fact_device_integrity.ready_bundle_next_day IS 'Readiness: the next day''s bundle is on the phone.';
COMMENT ON COLUMN dw.fact_device_integrity.ready_printer_paired IS 'Readiness: a printer is paired.';
COMMENT ON COLUMN dw.fact_device_integrity.ready_test_print IS 'Readiness: a test print succeeded.';
COMMENT ON COLUMN dw.fact_device_integrity.ready_permissions IS 'Readiness: every required permission is granted.';
COMMENT ON COLUMN dw.fact_device_integrity.free_storage_mb IS 'Free storage on the phone in MB.';
COMMENT ON COLUMN dw.fact_device_integrity.battery_pct IS 'Battery charge in percent at the observation.';
COMMENT ON COLUMN dw.fact_device_integrity.source_uuid IS 'client_uuid of the source record (status report or bundle download).';
COMMENT ON COLUMN dw.fact_device_integrity.source IS 'Origin of the row: native (this system) or an import.';
COMMENT ON COLUMN dw.fact_device_integrity.import_run_id IS 'Import run that loaded the row; null for native rows.';

COMMENT ON TABLE dw.fact_activity IS 'One row is one screen or action event from a phone''s activity log.
owner: backend:analytics | capture: SERVER | retention: telemetry | pii: none';
COMMENT ON COLUMN dw.fact_activity.user_key IS 'User of the event (app.app_user.id; no user dimension table).';
COMMENT ON COLUMN dw.fact_activity.device_key IS 'Phone of the event (app.device.id; no device dimension table).';
COMMENT ON COLUMN dw.fact_activity.business_date IS 'Asia/Dhaka business date of the event.';
COMMENT ON COLUMN dw.fact_activity.occurred_at IS 'UTC time of the event on the phone.';
COMMENT ON COLUMN dw.fact_activity.role IS 'Role of the user at the event.';
COMMENT ON COLUMN dw.fact_activity.screen IS 'Screen key of the event.';
COMMENT ON COLUMN dw.fact_activity.action IS 'Action key of the event.';
COMMENT ON COLUMN dw.fact_activity.seq IS 'Position of the event in the source activity_log row''s event array.';
COMMENT ON COLUMN dw.fact_activity.source_uuid IS 'client_uuid of the source app.activity_log row.';

COMMENT ON TABLE dw.agg_daily_screen_use IS 'One row is the use of one screen action by one role on one day, rolled up from fact_activity and kept for ever.
owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_screen_use.business_date IS 'Asia/Dhaka business date.';
COMMENT ON COLUMN dw.agg_daily_screen_use.role IS 'Role of the users counted.';
COMMENT ON COLUMN dw.agg_daily_screen_use.screen IS 'Screen key.';
COMMENT ON COLUMN dw.agg_daily_screen_use.action IS 'Action key.';
COMMENT ON COLUMN dw.agg_daily_screen_use.users IS 'Distinct users with at least one such event that day.';
COMMENT ON COLUMN dw.agg_daily_screen_use.events IS 'Number of such events that day (duplicates removed).';

COMMENT ON TABLE dw.fact_consent IS 'One row is a user''s acceptance of a policy version (employee-location notice and other policies).
owner: backend:analytics | capture: SERVER | retention: audit | pii: none';
COMMENT ON COLUMN dw.fact_consent.user_key IS 'User who accepted (app.app_user.id; no user dimension table).';
COMMENT ON COLUMN dw.fact_consent.policy_version IS 'Policy key and version accepted.';
COMMENT ON COLUMN dw.fact_consent.accepted_at IS 'UTC time of the acceptance.';
COMMENT ON COLUMN dw.fact_consent.device_key IS 'Phone the acceptance was made on (app.device.id); null on the web.';
COMMENT ON COLUMN dw.fact_consent.business_date IS 'Asia/Dhaka business date of the acceptance.';
COMMENT ON COLUMN dw.fact_consent.text_sha256 IS 'SHA-256 of the policy text shown.';
COMMENT ON COLUMN dw.fact_consent.source_uuid IS 'client_uuid of the source app.user_consent row.';

SELECT app.apply_db_role_grants();
