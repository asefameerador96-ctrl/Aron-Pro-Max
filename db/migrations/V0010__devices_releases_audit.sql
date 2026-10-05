-- V0010 devices, enrolment, policy, app blocking catalogue, releases, push tokens and the append-only audit log
-- (row N-007, schema v1c). docs/24 s8.3, s8.6, s8.7, s10, s12.1; contract Device, EnrolmentToken, AppRelease,
-- DeviceStatusReport, Directive, PushTokenRegistration, AuditEntry.

-- ---------- releases ----------
CREATE TABLE app.app_release (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  flavour             text NOT NULL CHECK (flavour IN ('sr','amo','tso')),
  version_name        text NOT NULL CHECK (version_name ~ '^[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}$'),
  version_code        int NOT NULL CHECK (version_code BETWEEN 1 AND 2100000000),
  abi                 text NOT NULL CHECK (abi IN ('universal','arm64-v8a','armeabi-v7a')),
  sha256              bytea NOT NULL UNIQUE CHECK (length(sha256) = 32),
  size_bytes          int NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
  download_url        text NOT NULL CHECK (length(download_url) <= 1000),
  signing_cert_sha256 bytea NOT NULL CHECK (length(signing_cert_sha256) = 32),
  status              text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','blocked','retired')),
  rollout_pct         smallint NOT NULL DEFAULT 0 CHECK (rollout_pct BETWEEN 0 AND 100),
  notes_en            text CHECK (length(notes_en) <= 2000),
  notes_bn            text CHECK (length(notes_bn) <= 2000),
  created_by          bigint REFERENCES app.app_user(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  published_at        timestamptz,
  published_by        bigint REFERENCES app.app_user(id),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             int NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (flavour, version_code, abi),
  -- release publish is maker-checker (s8.6): a published or blocked release names its author and a different publisher
  CHECK ((published_at IS NULL) = (published_by IS NULL)),
  CHECK (status NOT IN ('published','blocked') OR published_at IS NOT NULL),
  CHECK (published_by IS NULL OR (created_by IS NOT NULL AND published_by <> created_by))
);
CREATE TRIGGER app_release_touch BEFORE UPDATE ON app.app_release FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- ---------- enrolment ----------
-- 256-bit token shown once; only its hash is stored (D24-34).
CREATE TABLE app.enrolment_token (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  token_sha256    bytea NOT NULL UNIQUE CHECK (length(token_sha256) = 32),
  token_prefix    text NOT NULL CHECK (token_prefix ~ '^[A-Za-z0-9_-]{6}$'),
  flavour         text NOT NULL CHECK (flavour IN ('sr','amo','tso')),
  lockdown_level  text NOT NULL CHECK (lockdown_level IN ('dev','prod')),
  max_uses        int NOT NULL CHECK (max_uses BETWEEN 1 AND 500),
  used_count      int NOT NULL DEFAULT 0 CHECK (used_count >= 0),
  zone_id         bigint REFERENCES app.zone(id),
  release_id      bigint REFERENCES app.app_release(id),
  expires_at      timestamptz NOT NULL,
  created_by      bigint NOT NULL REFERENCES app.app_user(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  revoked_at      timestamptz,
  revoked_by      bigint REFERENCES app.app_user(id),
  note            text CHECK (length(note) <= 300),
  CHECK (used_count <= max_uses),
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '168 hours')
);

-- ---------- devices ----------
CREATE TABLE app.device (
  id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_uuid              uuid NOT NULL UNIQUE,
  flavour                  text NOT NULL CHECK (flavour IN ('sr','amo','tso')),
  app_package              text NOT NULL CHECK (app_package IN ('com.aktcl.aron.sr','com.aktcl.aron.amo','com.aktcl.aron.tso')),
  status                   text NOT NULL DEFAULT 'enrolled' CHECK (status IN ('enrolled','active','suspended','revoked','replaced')),
  status_changed_at        timestamptz NOT NULL DEFAULT now(),
  status_reason            text,
  device_owner             boolean NOT NULL,
  lockdown_level           text NOT NULL CHECK (lockdown_level IN ('dev','prod')),
  trust_level              text NOT NULL DEFAULT 'normal' CHECK (trust_level IN ('high','normal','low','blocked')),
  integrity_verdict        text NOT NULL DEFAULT 'unevaluated' CHECK (integrity_verdict IN ('pass','fail','unevaluated','stale')),
  integrity_checked_at     timestamptz,
  hardware_backed_key      boolean NOT NULL DEFAULT false,
  public_key_jwk           jsonb NOT NULL CHECK (jsonb_typeof(public_key_jwk) = 'object'),
  public_key_thumbprint    text NOT NULL UNIQUE,      -- RFC 7638 thumbprint: one device record per Keystore key
  attestation_summary      jsonb NOT NULL DEFAULT '{}'::jsonb,   -- verified boot, security level, package, cert digest
  app_signing_cert_sha256  bytea NOT NULL CHECK (length(app_signing_cert_sha256) = 32),
  enrolment_token_id       bigint REFERENCES app.enrolment_token(id),
  enrolled_at              timestamptz NOT NULL DEFAULT now(),
  device_info              jsonb,                     -- DeviceInfo
  app_version              text CHECK (app_version ~ '^[0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3}[+][0-9]{1,10}$'),
  policy_version_applied   bigint,
  config_version_applied   bigint,
  last_contact_at          timestamptz,
  pending_rows_reported    int CHECK (pending_rows_reported >= 0),
  zone_id                  bigint REFERENCES app.zone(id),
  replaced_by_device_id    bigint REFERENCES app.device(id),
  CHECK (app_package = 'com.aktcl.aron.' || flavour),
  external_ref             varchar(64) UNIQUE,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  version                  int NOT NULL DEFAULT 1 CHECK (version >= 1),
  CHECK ((status = 'replaced') = (replaced_by_device_id IS NOT NULL))
);
CREATE INDEX ON app.device (status, last_contact_at);
CREATE TRIGGER device_touch BEFORE UPDATE ON app.device FOR EACH ROW EXECUTE FUNCTION app.touch_master();

-- User bound to a phone; bind_ordinal 0..3 gives the memo-number block (s7.5, D24-24). At most one active binding of a
-- user per ordinal (so at most four) and per device.
CREATE TABLE app.device_binding (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id     bigint NOT NULL REFERENCES app.device(id),
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  bind_ordinal  smallint NOT NULL CHECK (bind_ordinal BETWEEN 0 AND 3),
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active','unbound_by_policy','revoked')),
  bound_at      timestamptz NOT NULL DEFAULT now(),
  bound_via     text NOT NULL DEFAULT 'otp' CHECK (bound_via IN ('otp','support','migration')),
  unbound_at    timestamptz,
  unbound_by    bigint REFERENCES app.app_user(id),
  CHECK ((status = 'active') = (unbound_at IS NULL))
);
CREATE UNIQUE INDEX device_binding_ordinal ON app.device_binding (user_id, bind_ordinal) WHERE status = 'active';
CREATE UNIQUE INDEX device_binding_pair ON app.device_binding (device_id, user_id) WHERE status = 'active';

-- Single-use server nonces for Play Integrity requests and key re-attestation.
CREATE TABLE app.device_nonce (
  nonce_sha256  bytea PRIMARY KEY CHECK (length(nonce_sha256) = 32),
  device_id     bigint NOT NULL REFERENCES app.device(id),
  purpose       text NOT NULL CHECK (purpose IN ('play_integrity','key_attestation')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  used_at       timestamptz
);
CREATE INDEX ON app.device_nonce (expires_at);

-- Status reports: online (POST /v1/devices/me/status, no client_uuid) or the device_status sync record.
CREATE TABLE app.device_status_report (
  id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid              uuid UNIQUE,
  source                   text NOT NULL CHECK (source IN ('online','record','enrolment')),
  device_id                bigint NOT NULL REFERENCES app.device(id),
  user_id                  bigint REFERENCES app.app_user(id),
  business_date            date NOT NULL,
  reported_at              timestamptz NOT NULL,
  received_at              timestamptz NOT NULL DEFAULT now(),
  config_version           bigint,
  trigger                  text CHECK (trigger IN ('enrolment','policy_applied','check_in','check_out','boot','integrity_change',
                                                   'app_update','periodic','directive')),
  app_version              text NOT NULL,
  device_owner             boolean NOT NULL,
  lockdown_level_applied   text NOT NULL CHECK (lockdown_level_applied IN ('dev','prod')),
  policy_version_applied   bigint,
  blocking_active          boolean NOT NULL,
  location_enabled         boolean NOT NULL,
  dev_options_enabled      boolean NOT NULL,
  adb_enabled              boolean NOT NULL,
  auto_time_enabled        boolean NOT NULL,
  mock_location_apps       text[] NOT NULL DEFAULT '{}',
  pending_rows             int NOT NULL CHECK (pending_rows >= 0),
  battery_pct              smallint NOT NULL CHECK (battery_pct BETWEEN 0 AND 100),
  integrity_verdict        text CHECK (integrity_verdict IN ('pass','fail','unevaluated','stale')),
  report                   jsonb NOT NULL,           -- the DeviceStatusReport as received (Play Integrity token removed)
  created_at               timestamptz NOT NULL DEFAULT now(),
  CHECK (source = 'online' OR source = 'enrolment' OR client_uuid IS NOT NULL)
);
CREATE INDEX ON app.device_status_report (device_id, reported_at DESC);
CREATE INDEX ON app.device_status_report (business_date);
CREATE TRIGGER device_status_report_append_only BEFORE UPDATE OR DELETE ON app.device_status_report
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- The DevicePolicy rendered for a device at a policy_version (= config_version at render, s10.2).
CREATE TABLE app.device_policy (
  device_id       bigint NOT NULL REFERENCES app.device(id),
  policy_version  bigint NOT NULL CHECK (policy_version >= 0),
  lockdown_level  text NOT NULL CHECK (lockdown_level IN ('dev','prod')),
  policy          jsonb NOT NULL CHECK (jsonb_typeof(policy) = 'object'),
  policy_sha256   bytea NOT NULL CHECK (length(policy_sha256) = 32),
  rendered_at     timestamptz NOT NULL DEFAULT now(),
  fetched_at      timestamptz,
  applied_at      timestamptz,                         -- from the next status report naming this version
  PRIMARY KEY (device_id, policy_version)
);

-- App-block list support: the package catalogue the portal offers when editing cfg.device.blocked_packages,
-- cfg.device.allowed_packages and cfg.device.always_allowed_packages (the lists themselves are config, s9.5, s10.6).
CREATE TABLE app.app_package (
  package_name    text PRIMARY KEY CHECK (package_name ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'),
  label           text NOT NULL CHECK (length(label) <= 120),
  category        text NOT NULL CHECK (category IN ('social','video','game','messaging','phone','maps','camera','system','keyboard',
                                                    'launcher','aron','other')),
  suggested_rule  text NOT NULL DEFAULT 'none' CHECK (suggested_rule IN ('block','always_allow','none')),
  first_seen_at   timestamptz,                         -- first status report or install list that named it
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app.app_package (package_name, label, category, suggested_rule) VALUES
  ('com.facebook.katana', 'Facebook', 'social', 'block'),
  ('com.facebook.lite', 'Facebook Lite', 'social', 'block'),
  ('com.instagram.android', 'Instagram', 'social', 'block'),
  ('com.zhiliaoapp.musically', 'TikTok', 'video', 'block'),
  ('com.ss.android.ugc.trill', 'TikTok (trill)', 'video', 'block'),
  ('com.google.android.youtube', 'YouTube', 'video', 'block'),
  ('com.snapchat.android', 'Snapchat', 'social', 'block'),
  ('com.dts.freefireth', 'Free Fire', 'game', 'block'),
  ('com.tencent.ig', 'PUBG Mobile', 'game', 'block'),
  ('com.whatsapp', 'WhatsApp', 'messaging', 'always_allow'),
  ('com.facebook.orca', 'Messenger', 'messaging', 'always_allow'),
  ('com.google.android.dialer', 'Phone (Google)', 'phone', 'always_allow'),
  ('com.samsung.android.dialer', 'Phone (Samsung)', 'phone', 'always_allow'),
  ('com.google.android.apps.messaging', 'Messages (Google)', 'messaging', 'always_allow'),
  ('com.samsung.android.messaging', 'Messages (Samsung)', 'messaging', 'always_allow'),
  ('com.google.android.apps.maps', 'Google Maps', 'maps', 'always_allow'),
  ('com.android.settings', 'Settings', 'system', 'always_allow'),
  ('com.sec.android.app.camera', 'Camera (Samsung)', 'camera', 'always_allow'),
  ('com.google.android.inputmethod.latin', 'Gboard', 'keyboard', 'always_allow'),
  ('com.sec.android.app.launcher', 'One UI Home', 'launcher', 'always_allow'),
  ('com.aktcl.aron.sr', 'Aron SR', 'aron', 'always_allow'),
  ('com.aktcl.aron.amo', 'Aron AMO', 'aron', 'always_allow'),
  ('com.aktcl.aron.tso', 'Aron TSO', 'aron', 'always_allow');

-- Signed remote directives (s10.3, Directive).
CREATE TABLE app.device_directive (
  directive_id  uuid PRIMARY KEY,
  device_id     bigint NOT NULL REFERENCES app.device(id),
  type          text NOT NULL CHECK (type IN ('send_status','redownload_bundle','upload_support_bundle','resend_from')),
  params        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object'),
  sig           text NOT NULL CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),
  created_by    bigint REFERENCES app.app_user(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  delivered_at  timestamptz,
  acked_at      timestamptz,
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '72 hours')
);
CREATE INDEX ON app.device_directive (device_id, created_at DESC);

-- FCM token of a user on a phone (PUT /v1/devices/me/push-token); the token is PII-grade and stored with its hash.
CREATE TABLE app.push_token (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id       bigint NOT NULL REFERENCES app.device(id),
  user_id         bigint NOT NULL REFERENCES app.app_user(id),
  provider        text NOT NULL DEFAULT 'fcm' CHECK (provider = 'fcm'),
  app_flavour     text NOT NULL CHECK (app_flavour IN ('sr','amo','tso')),
  token           text NOT NULL CHECK (length(token) BETWEEN 20 AND 4096),
  token_sha256    bytea NOT NULL CHECK (length(token_sha256) = 32),
  registered_at   timestamptz NOT NULL DEFAULT now(),
  last_seen_at    timestamptz NOT NULL DEFAULT now(),
  revoked_at      timestamptz,
  revoke_reason   text CHECK (revoke_reason IN ('replaced','unregistered','logout','device_revoked','user_disabled'))
);
CREATE UNIQUE INDEX push_token_live ON app.push_token (device_id, user_id) WHERE revoked_at IS NULL;
CREATE INDEX ON app.push_token (token_sha256);

-- ---------- device foreign keys of the tables created before app.device ----------
DO $$
DECLARE t record;
BEGIN
  FOR t IN
    SELECT c.table_name
      FROM information_schema.columns c
      JOIN pg_class k ON k.relname = c.table_name AND k.relnamespace = 'app'::regnamespace
     WHERE c.table_schema = 'app' AND c.column_name = 'device_id' AND k.relkind IN ('r','p') AND NOT k.relispartition
       AND c.table_name NOT IN ('device','device_binding','device_nonce','device_status_report','device_policy',
                                'device_directive','push_token')
     ORDER BY c.table_name
  LOOP
    EXECUTE format('ALTER TABLE app.%I ADD CONSTRAINT %I FOREIGN KEY (device_id) REFERENCES app.device(id)',
                   t.table_name, t.table_name || '_device_fk');
  END LOOP;
END $$;

-- ---------- audit log (s8.6): append-only and hash-chained ----------
-- The chain runs in chain_seq order, a number the insert trigger assigns while holding an advisory lock (the identity
-- id is drawn before the lock, so ids of concurrent writers may interleave). Each row carries the previous row's hash
-- (prev_hash) and its own row_hash over its content, so an edit or a removal inside the chain breaks it
-- (app.audit_verify). at and business_date are set by the server, never by the caller. A writer whose snapshot misses
-- a concurrent row (REPEATABLE READ) gets a unique violation on chain_seq instead of forking the chain. Audit is written
-- by web and admin actions, not by the sync hot path.
CREATE TABLE app.audit_log (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  chain_seq       bigint NOT NULL UNIQUE CHECK (chain_seq >= 1),
  at              timestamptz NOT NULL DEFAULT now(),
  business_date   date NOT NULL DEFAULT app.dhaka_date(now()),
  actor_user_id   bigint REFERENCES app.app_user(id),
  actor_username  text CHECK (length(actor_username) <= 40),
  actor_role      text REFERENCES app.role_def(role),
  via             text NOT NULL CHECK (via IN ('web','api','job','device')),
  entity          text NOT NULL CHECK (length(entity) <= 40),
  entity_id       text NOT NULL CHECK (length(entity_id) <= 64),
  action          text NOT NULL CHECK (length(action) <= 60),
  before          jsonb,
  after           jsonb,
  reason          text CHECK (length(reason) <= 500),
  request_id      uuid,
  ip_class        text,
  prev_hash       bytea CHECK (length(prev_hash) = 32),
  row_hash        bytea NOT NULL CHECK (length(row_hash) = 32)
);
CREATE INDEX ON app.audit_log (entity, entity_id);
CREATE INDEX ON app.audit_log (actor_user_id, at);
CREATE INDEX ON app.audit_log (business_date);

CREATE FUNCTION app.audit_row_hash(r app.audit_log) RETURNS bytea
LANGUAGE sql STABLE
AS $$
  -- A JSON array of the fields: unambiguous boundaries, and no dependence on DateStyle or other session settings.
  SELECT sha256(coalesce(r.prev_hash, '\x'::bytea) || convert_to(jsonb_build_array(
    r.chain_seq, r.id, to_char(r.at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), to_char(r.business_date, 'YYYY-MM-DD'),
    r.actor_user_id, r.actor_username, r.actor_role, r.via, r.entity, r.entity_id, r.action, r.before, r.after, r.reason,
    r.request_id::text, r.ip_class)::text, 'UTF8'))
$$;

CREATE FUNCTION app.audit_log_chain() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(7322841001);           -- one writer at a time keeps the chain linear
  NEW.at := now();
  NEW.business_date := app.dhaka_date(NEW.at);
  SELECT chain_seq + 1, row_hash INTO NEW.chain_seq, NEW.prev_hash FROM app.audit_log ORDER BY chain_seq DESC LIMIT 1;
  NEW.chain_seq := coalesce(NEW.chain_seq, 1);
  NEW.row_hash := app.audit_row_hash(NEW);
  RETURN NEW;
END $$;
CREATE TRIGGER audit_log_chain BEFORE INSERT ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.audit_log_chain();
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON app.audit_log FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.audit_log FROM PUBLIC;

-- First audit row (id) whose hash, link or sequence does not verify (null = the whole chain is intact). Removing the
-- newest rows is not detectable from inside the table; the daily export of the last row_hash anchors it.
CREATE FUNCTION app.audit_verify() RETURNS bigint
LANGUAGE sql STABLE
AS $$
  SELECT id FROM (
    SELECT a.id, a.chain_seq, a.row_hash, a.prev_hash, app.audit_row_hash(a) AS expected,
           lag(a.row_hash) OVER (ORDER BY a.chain_seq) AS previous,
           lag(a.chain_seq) OVER (ORDER BY a.chain_seq) AS previous_seq
      FROM app.audit_log a) x
   WHERE x.row_hash <> x.expected OR x.prev_hash IS DISTINCT FROM x.previous
      OR x.chain_seq <> coalesce(x.previous_seq, 0) + 1
   ORDER BY chain_seq LIMIT 1
$$;
