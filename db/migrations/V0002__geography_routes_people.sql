-- V0002 geography, routes, people and access (row N-005, schema v1a). docs/24 s8, s12.1; contract GeoNode,
-- Cluster, Route, RouteAssignment, User, UserScopeNode.
-- Master rows: created_at, updated_at, version (optimistic concurrency, app.touch_master), status (never deleted),
-- external_ref (Phase 2 cross-walks, unique when set, docs/24 s12.5 item 5).

-- ---------- geography: wing > division > territory > (house) > zone > cluster ----------
CREATE TABLE app.wing (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn        text CHECK (length(name_bn) <= 120),
  email          text,
  address        text,
  pda_contact_no text,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint
);

CREATE TABLE app.division (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn        text CHECK (length(name_bn) <= 120),
  wing_id        bigint NOT NULL REFERENCES app.wing(id),
  email          text,
  address        text,
  pda_contact_no text,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint
);
CREATE INDEX ON app.division (wing_id);

CREATE TABLE app.territory (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn        text CHECK (length(name_bn) <= 120),
  division_id    bigint NOT NULL REFERENCES app.division(id),
  email          text,
  address        text,
  pda_contact_no text,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint
);
CREATE INDEX ON app.territory (division_id);

-- Distribution house: carries no rules in Phase 1 (D24-13); a zone may name its house.
CREATE TABLE app.house (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn        text CHECK (length(name_bn) <= 120),
  territory_id   bigint NOT NULL REFERENCES app.territory(id),
  email          text,
  address        text,
  pda_contact_no text,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint
);
CREATE INDEX ON app.house (territory_id);

CREATE TABLE app.zone (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn        text CHECK (length(name_bn) <= 120),
  territory_id   bigint NOT NULL REFERENCES app.territory(id),
  house_id       bigint REFERENCES app.house(id),
  dep_name       text CHECK (length(dep_name) <= 120),
  email          text,
  address        text,
  pda_contact_no text,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint
);
CREATE INDEX ON app.zone (territory_id);

CREATE TABLE app.cluster (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  zone_id        bigint NOT NULL REFERENCES app.zone(id),
  name           text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  cluster_type   text CHECK (length(cluster_type) <= 60),
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref   varchar(64) UNIQUE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  version        int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by     bigint,
  updated_by     bigint,
  UNIQUE (zone_id, name)
);

-- ---------- routes ----------
-- visit_days_mask: bit0 Sat, bit1 Sun, bit2 Mon, bit3 Tue, bit4 Wed, bit5 Thu, bit6 Fri (daily = 127,
-- Sun/Tue/Thu = 42, Sat/Mon/Wed = 21). visit_kind daily, 3f (three days a week), 2f (two days); display_label is
-- the printed text, never a key. Route codes are text (both DHK-344-011 and 2689479 style codes occur).
CREATE TABLE app.route (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code            text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name            text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  display_label   text CHECK (length(display_label) <= 60),
  zone_id         bigint NOT NULL REFERENCES app.zone(id),
  kind            text NOT NULL CHECK (kind IN ('sr','amo')),
  visit_kind      text CHECK (visit_kind IN ('daily','3f','2f')),
  visit_days_mask smallint NOT NULL CHECK (visit_days_mask BETWEEN 1 AND 127),
  sequence_no     int CHECK (sequence_no >= 1),
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref    varchar(64) UNIQUE,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by      bigint,
  updated_by      bigint
);
CREATE INDEX ON app.route (zone_id);

-- Planned visit days, effective-dated: a visit-days change takes effect from a future Dhaka date and the past is
-- never overwritten (contract updateRoute). route.visit_days_mask mirrors the row valid today.
CREATE TABLE app.route_planned (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id        bigint NOT NULL REFERENCES app.route(id),
  visit_kind      text CHECK (visit_kind IN ('daily','3f','2f')),
  visit_days_mask smallint NOT NULL CHECK (visit_days_mask BETWEEN 1 AND 127),
  valid_from      date NOT NULL,
  valid_to        date,                               -- exclusive; null = open
  created_at      timestamptz NOT NULL DEFAULT now(),
  created_by      bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

-- ---------- people ----------
CREATE TABLE app.app_user (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username              text NOT NULL CHECK (username ~ '^[A-Za-z][A-Za-z0-9._-]{2,39}$'),
  full_name             text NOT NULL CHECK (length(full_name) BETWEEN 1 AND 120),
  role                  text NOT NULL REFERENCES app.role_def(role),
  designation           text CHECK (length(designation) <= 60),
  employee_code         text CHECK (length(employee_code) <= 40),
  phone                 text CHECK (phone ~ '^01[3-9][0-9]{8}$'),          -- PII
  email                 text CHECK (length(email) <= 120),
  locale                text NOT NULL DEFAULT 'bn' CHECK (locale IN ('bn','en')),
  home_zone_id          bigint REFERENCES app.zone(id),
  status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
  password_hash         text,                                              -- Argon2id PHC string; null = no password set
  password_changed_at   timestamptz,
  must_change_password  boolean NOT NULL DEFAULT true,
  mfa_enabled           boolean NOT NULL DEFAULT false,
  pilot                 boolean NOT NULL DEFAULT false,
  scope_version         bigint NOT NULL DEFAULT 1 CHECK (scope_version >= 1), -- bumped on role, status, scope or assignment change (s8.4)
  last_login_at         timestamptz,
  disabled_at           timestamptz,
  external_ref          varchar(64) UNIQUE,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by            bigint,
  updated_by            bigint,
  -- memo numbers embed the username: memo-producing roles use ^[a-z][a-z0-9]{3,31}$ (docs/24 s7.5)
  CONSTRAINT app_user_memo_username CHECK (role NOT IN ('SR','AMO') OR username ~ '^[a-z][a-z0-9]{3,31}$')
);
CREATE UNIQUE INDEX app_user_username_ci ON app.app_user (lower(username));
CREATE UNIQUE INDEX app_user_employee_code ON app.app_user (employee_code) WHERE employee_code IS NOT NULL;
CREATE INDEX ON app.app_user (home_zone_id);

-- Supervisory reach, effective-dated (docs/24 s8.4). node_type national uses node_id 0.
CREATE TABLE app.user_scope (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  node_type   text NOT NULL CHECK (node_type IN ('national','wing','division','territory','zone')),
  node_id     bigint NOT NULL CHECK (node_id >= 0),
  valid_from  date NOT NULL,
  valid_to    date,                                   -- exclusive; null = open
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  bigint,
  reason      text,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CHECK ((node_type = 'national') = (node_id = 0)),
  EXCLUDE USING gist (user_id WITH =, node_type WITH =, node_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX ON app.user_scope (node_type, node_id);

-- Route assignment (primary or cover), effective-dated; one primary per route and date (exclusion constraint).
CREATE TABLE app.route_assignment (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  route_id    bigint NOT NULL REFERENCES app.route(id),
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  kind        text NOT NULL CHECK (kind IN ('primary','cover')),
  valid_from  date NOT NULL,
  valid_to    date,                                   -- exclusive; null = open
  reason      text CHECK (length(reason) <= 300),
  client_uuid uuid UNIQUE,                            -- set when created by an idempotent online command (cover)
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  bigint,
  ended_at    timestamptz,
  ended_by    bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT route_assignment_one_primary EXCLUDE USING gist
    (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&) WHERE (kind = 'primary'),
  CONSTRAINT route_assignment_no_overlap EXCLUDE USING gist
    (route_id WITH =, user_id WITH =, kind WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX ON app.route_assignment (user_id, valid_from);

-- Admin permission bundles per role (web `perm` claim, docs/24 s8.2).
CREATE TABLE app.role_grant_map (
  role        text NOT NULL REFERENCES app.role_def(role),
  permission  text NOT NULL CHECK (permission ~ '^[a-z][a-z0-9_.]{1,80}$'),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (role, permission)
);

-- ---------- credentials and sessions (owned by backend:auth; device FKs are added in V0010) ----------
CREATE TABLE app.refresh_family (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id             bigint NOT NULL REFERENCES app.app_user(id),
  device_id           bigint,                         -- phones; FK to app.device in V0010
  client              text NOT NULL CHECK (client IN ('app_sr','app_amo','app_tso','web')),
  grant_kind          text NOT NULL CHECK (grant_kind IN ('full','upload')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  last_used_at        timestamptz,
  sliding_expires_at  timestamptz NOT NULL,
  absolute_expires_at timestamptz NOT NULL,
  revoked_at          timestamptz,
  revoke_reason       text CHECK (revoke_reason IN ('logout','reuse_detected','password_changed','user_disabled',
                                                    'device_revoked','admin_force_logout','expired','replaced'))
);
CREATE INDEX ON app.refresh_family (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ON app.refresh_family (device_id) WHERE revoked_at IS NULL;

CREATE TABLE app.refresh_token (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  family_id       bigint NOT NULL REFERENCES app.refresh_family(id),
  token_sha256    bytea NOT NULL UNIQUE CHECK (length(token_sha256) = 32),   -- the token itself is never stored
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  used_at         timestamptz,
  replaced_by_id  bigint REFERENCES app.refresh_token(id)
);
CREATE INDEX ON app.refresh_token (family_id);

CREATE TABLE app.mfa_secret (
  user_id               bigint PRIMARY KEY REFERENCES app.app_user(id),
  secret_cipher         bytea NOT NULL,                -- TOTP secret encrypted by the API (key from Key Vault)
  recovery_code_hashes  text[] NOT NULL DEFAULT '{}',
  created_at            timestamptz NOT NULL DEFAULT now(),
  confirmed_at          timestamptz,
  last_used_step        bigint                         -- TOTP replay guard
);

-- Device-binding OTP the TSO reads on the web Device OTP panel (docs/24 s8.1).
CREATE TABLE app.device_otp (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      bigint NOT NULL REFERENCES app.app_user(id),
  otp_cipher   bytea NOT NULL,                          -- encrypted, shown while unexpired
  otp_sha256   bytea NOT NULL CHECK (length(otp_sha256) = 32),
  device_model text CHECK (length(device_model) <= 80),
  issued_by    bigint REFERENCES app.app_user(id),
  reason       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  attempts     int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  consumed_at  timestamptz,
  revoked_at   timestamptz
);
CREATE INDEX ON app.device_otp (user_id, created_at DESC);

-- Login lockout counters shared by every API replica (docs/24 s8.1; key per cfg.auth.lockout_key_mode).
CREATE TABLE app.auth_lockout (
  lock_key          text PRIMARY KEY,
  failures          int NOT NULL DEFAULT 0 CHECK (failures >= 0),
  window_started_at timestamptz NOT NULL DEFAULT now(),
  locked_until      timestamptz,
  lock_count        int NOT NULL DEFAULT 0 CHECK (lock_count >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------- version and updated_at maintenance ----------
CREATE TRIGGER wing_touch      BEFORE UPDATE ON app.wing      FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER division_touch  BEFORE UPDATE ON app.division  FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER territory_touch BEFORE UPDATE ON app.territory FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER house_touch     BEFORE UPDATE ON app.house     FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER zone_touch      BEFORE UPDATE ON app.zone      FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER cluster_touch   BEFORE UPDATE ON app.cluster   FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER route_touch     BEFORE UPDATE ON app.route     FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER app_user_touch  BEFORE UPDATE ON app.app_user  FOR EACH ROW EXECUTE FUNCTION app.touch_master();
