-- V0102 MDM policies and device commands (owner directive 2026-10-22, docs/34 s5, DECISIONS D-MDM-01). The DDL of mdm's
-- request docs/requests/db-mdm-device-command.md (lane mdm e0257cc5) as written, plus:
-- - a guard (Opus checker, db rounds 1 and 2): a new command carries no approval, delivery or result, and a factory
--   reset starts pending_approval (every other type queued), so the second person can only be added by a later UPDATE,
--   where the CHECK keeps the approver apart from the requester; the approval time is set to now() by the server. Whether the approver may approve (SUPERADMIN, or ADMIN with mdm
--   = w) stays the API's check (mdm's request; test admins made after V0089 carry no module grant).
--   Checker round 3 (session 8): states only move forward (pending_approval -> queued/rejected/expired, queued ->
--   sent/acked/failed/expired, sent -> acked/failed/expired), the request columns are fixed, signature, approval,
--   delivery and result are written once, a factory reset names its requester, and a pending reset may expire
--   unsigned and unapproved (mdm's pull expires it; before this fix the CHECKs refused that and broke the pull).
-- - grants: api_rw reads, inserts and updates both tables through its app rows (no DELETE: commands are the activity
--   log); support_ro reads commands without result (a locate answer is personal data);
-- - dw.v_device_command: the stable read view for the portal and BI, without sig and result.
-- The constraints on the existing app.device are added NOT VALID and validated in V0103 (squawk).
-- app.device_directive (V0010) stays the sync-diagnostics channel.

SET lock_timeout = '5s';

CREATE TABLE app.mdm_policy (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name         text NOT NULL CHECK (length(name) BETWEEN 2 AND 80),
  description  text CHECK (length(description) <= 300),
  doc          jsonb NOT NULL CHECK (jsonb_typeof(doc) = 'object' AND pg_column_size(doc) <= 65536),
  version      int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by   bigint REFERENCES app.app_user(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   bigint REFERENCES app.app_user(id),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX mdm_policy_name_uq ON app.mdm_policy (lower(name));
CREATE INDEX mdm_policy_created_by ON app.mdm_policy (created_by);
CREATE INDEX mdm_policy_updated_by ON app.mdm_policy (updated_by);
CREATE TRIGGER mdm_policy_touch BEFORE UPDATE ON app.mdm_policy FOR EACH ROW EXECUTE FUNCTION app.touch_master();

ALTER TABLE app.device
  ADD COLUMN mdm_policy_id      bigint,
  ADD COLUMN mdm_policy_version int;
ALTER TABLE app.device ADD CONSTRAINT device_mdm_policy_fkey FOREIGN KEY (mdm_policy_id) REFERENCES app.mdm_policy(id) NOT VALID;
ALTER TABLE app.device ADD CONSTRAINT device_mdm_policy_version_check CHECK (mdm_policy_version >= 1) NOT VALID;
ALTER TABLE app.device ADD CONSTRAINT device_mdm_policy_pair CHECK ((mdm_policy_id IS NULL) = (mdm_policy_version IS NULL)) NOT VALID;
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX device_mdm_policy_idx ON app.device (mdm_policy_id) WHERE mdm_policy_id IS NOT NULL;

CREATE TABLE app.device_command (
  seq            bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
  command_id     uuid PRIMARY KEY,
  device_id      bigint NOT NULL REFERENCES app.device(id),
  type           text NOT NULL CHECK (type IN ('lock','unlock','message','locate','reboot','factory_reset','apply_policy')),
  params         jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object' AND pg_column_size(params) <= 8192),
  state          text NOT NULL CHECK (state IN ('pending_approval','queued','sent','acked','failed','expired','rejected')),
  reason         text NOT NULL CHECK (length(reason) BETWEEN 3 AND 300),
  request_sha256 bytea NOT NULL CHECK (length(request_sha256) = 32),
  sig            text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),
  created_by     bigint REFERENCES app.app_user(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  expires_at     timestamptz NOT NULL,
  approved_by    bigint REFERENCES app.app_user(id),
  approved_at    timestamptz,
  decision_reason text CHECK (length(decision_reason) <= 300),
  push_sent_at   timestamptz,
  sent_at        timestamptz,
  acked_at       timestamptz,
  result         jsonb CHECK (jsonb_typeof(result) = 'object' AND pg_column_size(result) <= 4096),
  CHECK (expires_at > created_at AND expires_at <= created_at + interval '72 hours'),
  CHECK (type <> 'factory_reset' OR state IN ('pending_approval','expired') OR approved_by IS NOT NULL),
  CHECK (approved_by IS NULL OR created_by IS NULL OR approved_by <> created_by),
  CHECK ((state IN ('acked','failed')) = (acked_at IS NOT NULL))
);
ALTER TABLE app.device_command ADD CONSTRAINT device_command_state_sig CHECK (state IN ('pending_approval','rejected') OR sig IS NOT NULL
                                                                         OR (state = 'expired' AND approved_by IS NULL));
CREATE INDEX device_command_pull_idx ON app.device_command (device_id, seq) WHERE state IN ('queued','sent');
CREATE INDEX device_command_device_idx ON app.device_command (device_id, seq DESC);
CREATE INDEX device_command_pending_idx ON app.device_command (created_at) WHERE state = 'pending_approval';
CREATE INDEX device_command_created_by ON app.device_command (created_by);
CREATE INDEX device_command_approved_by ON app.device_command (approved_by);

CREATE FUNCTION app.device_command_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  first_state text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    first_state := CASE WHEN NEW.type = 'factory_reset' THEN 'pending_approval' ELSE 'queued' END;
    IF NEW.state <> first_state OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL
       OR NEW.push_sent_at IS NOT NULL OR NEW.sent_at IS NOT NULL OR NEW.acked_at IS NOT NULL OR NEW.result IS NOT NULL THEN
      RAISE EXCEPTION 'device_command %: a new % command starts % with no approval, delivery or result', NEW.command_id, NEW.type, first_state
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.type = 'factory_reset' AND NEW.created_by IS NULL THEN
      RAISE EXCEPTION 'device_command %: a factory reset names its requester (the approver must be someone else)', NEW.command_id
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  -- UPDATE: states only move forward, the request is fixed, approval and delivery are written once.
  IF NEW.state <> OLD.state AND NOT (
       (OLD.state = 'pending_approval' AND NEW.state IN ('queued','rejected','expired'))
    OR (OLD.state = 'queued' AND NEW.state IN ('sent','acked','failed','expired'))
    OR (OLD.state = 'sent' AND NEW.state IN ('acked','failed','expired'))) THEN
    RAISE EXCEPTION 'device_command %: state % cannot become %', OLD.command_id, OLD.state, NEW.state USING ERRCODE = 'check_violation';
  END IF;
  IF (NEW.command_id, NEW.device_id, NEW.type, NEW.params, NEW.reason, NEW.request_sha256, NEW.created_by, NEW.created_at, NEW.expires_at)
     IS DISTINCT FROM (OLD.command_id, OLD.device_id, OLD.type, OLD.params, OLD.reason, OLD.request_sha256, OLD.created_by, OLD.created_at, OLD.expires_at)
  THEN
    RAISE EXCEPTION 'device_command %: the request (device, type, params, reason, requester, times) is fixed', OLD.command_id USING ERRCODE = 'check_violation';
  END IF;
  IF (OLD.sig IS NOT NULL AND NEW.sig IS DISTINCT FROM OLD.sig)
     OR (OLD.approved_by IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.approved_at IS DISTINCT FROM OLD.approved_at))
     OR (OLD.decision_reason IS NOT NULL AND NEW.decision_reason IS DISTINCT FROM OLD.decision_reason)
     OR (OLD.push_sent_at IS NOT NULL AND NEW.push_sent_at IS DISTINCT FROM OLD.push_sent_at)
     OR (OLD.sent_at IS NOT NULL AND NEW.sent_at IS DISTINCT FROM OLD.sent_at)
     OR (OLD.acked_at IS NOT NULL AND NEW.acked_at IS DISTINCT FROM OLD.acked_at)
     OR (OLD.result IS NOT NULL AND NEW.result IS DISTINCT FROM OLD.result) THEN
    RAISE EXCEPTION 'device_command %: signature, approval, delivery and result are written once', OLD.command_id USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.approved_by IS NOT NULL AND OLD.approved_by IS NULL THEN
    NEW.approved_at := now();   -- the decision time is the server's, never the caller's
  ELSIF NEW.approved_by IS NULL THEN
    NEW.approved_at := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER device_command_guard BEFORE INSERT OR UPDATE ON app.device_command
  FOR EACH ROW EXECUTE FUNCTION app.device_command_guard();

CREATE VIEW dw.v_device_command AS
SELECT c.seq, c.command_id, c.device_id, c.type, c.state, c.reason, c.created_by, c.created_at, c.expires_at,
       c.approved_by, c.approved_at, c.decision_reason, c.push_sent_at, c.sent_at, c.acked_at
  FROM app.device_command c;

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_columns, note) VALUES
  ('support_ro', 'app', 'device_command', 'SELECT', '{result}', 'V0102: the support desk sees the MDM activity log, not the locate result (personal)');

COMMENT ON TABLE app.mdm_policy IS 'Editable MDM policy the device-owner DPC enforces on top of app.device_policy: allowed apps, blocking windows, kiosk, location sampling (D-MDM-01).
owner: backend:mdm | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.mdm_policy.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.mdm_policy.name IS 'Policy name, unique case-insensitively.';
COMMENT ON COLUMN app.mdm_policy.description IS 'Free description for the admin.';
COMMENT ON COLUMN app.mdm_policy.doc IS 'Policy document: allowed_packages, block_windows (days, start, end, packages), kiosk, location_sampling.';
COMMENT ON COLUMN app.mdm_policy.version IS 'Row version, moved forward on every update (If-Match).';
COMMENT ON COLUMN app.mdm_policy.created_by IS 'User who created the policy.';
COMMENT ON COLUMN app.mdm_policy.created_at IS 'UTC instant the row was inserted.';
COMMENT ON COLUMN app.mdm_policy.updated_by IS 'User who last changed the policy.';
COMMENT ON COLUMN app.mdm_policy.updated_at IS 'UTC instant of the last change.';
COMMENT ON COLUMN app.device.mdm_policy_id IS 'MDM policy assigned to the device (app.mdm_policy); null when none.';
COMMENT ON COLUMN app.device.mdm_policy_version IS 'Version of that policy last assigned; set exactly when mdm_policy_id is.';
COMMENT ON TABLE app.device_command IS 'MDM command to one device (lock, unlock, message, locate, reboot, factory reset, apply policy): idempotent by command_id, pushed by FCM and pulled by the DPC; a factory reset needs a second person (D-MDM-01).
owner: backend:mdm | capture: ONLINE | retention: audit | pii: personal';
COMMENT ON COLUMN app.device_command.seq IS 'Delivery and listing order (created_at can tie).';
COMMENT ON COLUMN app.device_command.command_id IS 'UUID generated by the admin client; the idempotency key.';
COMMENT ON COLUMN app.device_command.device_id IS 'Target device (app.device).';
COMMENT ON COLUMN app.device_command.type IS 'lock, unlock, message, locate, reboot, factory_reset or apply_policy.';
COMMENT ON COLUMN app.device_command.params IS 'Type-specific parameters (message text, policy id); no secrets.';
COMMENT ON COLUMN app.device_command.state IS 'pending_approval (factory reset), queued, sent, acked, failed, expired or rejected.';
COMMENT ON COLUMN app.device_command.reason IS 'Reason typed by the requester.';
COMMENT ON COLUMN app.device_command.request_sha256 IS 'SHA-256 of the create body: the same id with the same body is the same command.';
COMMENT ON COLUMN app.device_command.sig IS 'ES256 signature (prefix aron-command-v1) the DPC verifies; null while pending_approval or rejected.';
COMMENT ON COLUMN app.device_command.created_by IS 'Portal user who requested the command.';
COMMENT ON COLUMN app.device_command.created_at IS 'UTC instant the command was requested.';
COMMENT ON COLUMN app.device_command.expires_at IS 'UTC instant after which the device must not act on it (at most 72 hours after the request).';
COMMENT ON COLUMN app.device_command.approved_by IS 'Second user who approved or rejected a factory reset; never the requester.';
COMMENT ON COLUMN app.device_command.approved_at IS 'UTC instant of that decision.';
COMMENT ON COLUMN app.device_command.decision_reason IS 'Reason given with the approval or rejection.';
COMMENT ON COLUMN app.device_command.push_sent_at IS 'UTC instant FCM accepted the data message.';
COMMENT ON COLUMN app.device_command.sent_at IS 'UTC instant of the first pull by the device.';
COMMENT ON COLUMN app.device_command.acked_at IS 'UTC instant the device reported done (acked) or failed.';
COMMENT ON COLUMN app.device_command.result IS '[pii:personal] Result the device reported (e.g. the position of a locate).';
COMMENT ON VIEW dw.v_device_command IS 'Stable view: MDM commands for the activity log, without signature and result.
owner: backend:mdm | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN dw.v_device_command.seq IS 'Delivery and listing order.';
COMMENT ON COLUMN dw.v_device_command.command_id IS 'UUID of the command.';
COMMENT ON COLUMN dw.v_device_command.device_id IS 'Target device (app.device).';
COMMENT ON COLUMN dw.v_device_command.type IS 'Command type.';
COMMENT ON COLUMN dw.v_device_command.state IS 'Command state.';
COMMENT ON COLUMN dw.v_device_command.reason IS 'Reason typed by the requester.';
COMMENT ON COLUMN dw.v_device_command.created_by IS 'Requester (app.app_user id).';
COMMENT ON COLUMN dw.v_device_command.created_at IS 'UTC instant of the request.';
COMMENT ON COLUMN dw.v_device_command.expires_at IS 'UTC instant the command expires.';
COMMENT ON COLUMN dw.v_device_command.approved_by IS 'Second person of a factory reset (app.app_user id).';
COMMENT ON COLUMN dw.v_device_command.approved_at IS 'UTC instant of the approval or rejection.';
COMMENT ON COLUMN dw.v_device_command.decision_reason IS 'Reason given with the decision.';
COMMENT ON COLUMN dw.v_device_command.push_sent_at IS 'UTC instant FCM accepted the push.';
COMMENT ON COLUMN dw.v_device_command.sent_at IS 'UTC instant of the first pull by the device.';
COMMENT ON COLUMN dw.v_device_command.acked_at IS 'UTC instant the device reported done or failed.';

SELECT app.apply_db_role_grants();
