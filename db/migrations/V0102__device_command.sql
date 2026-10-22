-- V0102 MDM device commands (owner directive 2026-10-22, docs/34 s5, DECISIONS D-MDM-01): the actions of the portal's
-- MDM section (lock, unlock, message, locate, reboot, factory reset) as idempotent rows, pushed by FCM and pulled on
-- sync by the device-owner DPC (android/dpc). app.device_directive (V0010) stays the sync-diagnostics channel
-- (send_status, redownload_bundle, ...); MDM commands are a separate table because they have a two-person rule, a
-- longer life and a result.
-- Rules held by the database:
-- - command_uuid is the idempotency key (a replayed create returns the first row).
-- - factory_reset needs a second person: it is created pending_approval with no approval; the approval is written
--   only on the step to queued, before expires_at, by a SUPERADMIN or an ADMIN with write on the mdm module, never the
--   requester. Every other type is created queued. A new row carries no approval, delivery or outcome.
-- - Status moves only forward: pending_approval -> queued | cancelled | expired; queued -> sent | cancelled | expired;
--   sent -> acked | failed | expired; acked -> done | failed. Request, approval and delivery columns are written once.
--   Expiry is the API's (worker_rw cannot update the table).
-- - support_ro reads the activity log without result (a locate answer is personal data).
-- - Rows are never deleted (the MDM activity log reads them); every change is also audited by the API.

SET lock_timeout = '5s';

CREATE TABLE app.device_command (
  command_uuid   uuid PRIMARY KEY,
  device_id      bigint NOT NULL REFERENCES app.device(id),
  type           text NOT NULL CHECK (type IN ('lock', 'unlock', 'message', 'locate', 'reboot', 'factory_reset')),
  params         jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(params) = 'object'),
  status         text NOT NULL CHECK (status IN ('pending_approval', 'queued', 'sent', 'acked', 'done', 'failed', 'cancelled', 'expired')),
  requested_by   bigint NOT NULL REFERENCES app.app_user(id),
  requested_at   timestamptz NOT NULL DEFAULT now(),
  reason         text CHECK (length(reason) <= 500),
  approved_by    bigint REFERENCES app.app_user(id),
  approved_at    timestamptz,
  expires_at     timestamptz NOT NULL,
  sig            text CHECK (sig ~ '^[A-Za-z0-9_-]{86}$'),
  sent_at        timestamptz,
  sent_via       text CHECK (sent_via IN ('fcm', 'sync')),
  acked_at       timestamptz,
  completed_at   timestamptz,
  result         jsonb CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
  error          text CHECK (length(error) <= 500),
  cancelled_by   bigint REFERENCES app.app_user(id),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > requested_at AND expires_at <= requested_at + interval '30 days'),
  CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
  CHECK (approved_by IS NULL OR approved_by <> requested_by),
  CHECK (type <> 'factory_reset' OR status IN ('pending_approval', 'cancelled') OR approved_by IS NOT NULL),
  CHECK (status <> 'pending_approval' OR type = 'factory_reset'),
  CHECK ((status = 'cancelled') = (cancelled_by IS NOT NULL))
);
CREATE INDEX device_command_device ON app.device_command (device_id, requested_at DESC);
CREATE INDEX device_command_open ON app.device_command (device_id) WHERE status IN ('queued', 'sent');
CREATE INDEX device_command_requested_by ON app.device_command (requested_by);
CREATE INDEX device_command_approved_by ON app.device_command (approved_by);
CREATE INDEX device_command_cancelled_by ON app.device_command (cancelled_by);

CREATE FUNCTION app.device_command_flow() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  first_status text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    first_status := CASE WHEN NEW.type = 'factory_reset' THEN 'pending_approval' ELSE 'queued' END;
    IF NEW.status <> first_status THEN
      RAISE EXCEPTION 'device_command %: a % command starts %', NEW.command_uuid, NEW.type, first_status
        USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.cancelled_by IS NOT NULL
       OR NEW.sent_at IS NOT NULL OR NEW.acked_at IS NOT NULL OR NEW.completed_at IS NOT NULL THEN
      RAISE EXCEPTION 'device_command %: a new command carries no approval, delivery or outcome', NEW.command_uuid
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF (NEW.command_uuid, NEW.device_id, NEW.type, NEW.params, NEW.requested_by, NEW.requested_at, NEW.reason, NEW.expires_at)
     IS DISTINCT FROM (OLD.command_uuid, OLD.device_id, OLD.type, OLD.params, OLD.requested_by, OLD.requested_at, OLD.reason, OLD.expires_at)
     OR (OLD.approved_by IS NOT NULL AND (NEW.approved_by, NEW.approved_at) IS DISTINCT FROM (OLD.approved_by, OLD.approved_at)) THEN
    RAISE EXCEPTION 'device_command %: request and approval columns are written once', OLD.command_uuid USING ERRCODE = 'insufficient_privilege';
  END IF;
  -- The approval is given only on the step pending_approval -> queued, before expiry, by a SUPERADMIN or an ADMIN with
  -- write on the mdm module (docs/34 s5); the CHECK keeps the approver apart from the requester.
  IF OLD.approved_by IS NULL AND NEW.approved_by IS NOT NULL THEN
    IF NOT (OLD.status = 'pending_approval' AND NEW.status = 'queued') THEN
      RAISE EXCEPTION 'device_command %: an approval is given only when a pending command is queued', OLD.command_uuid
        USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM app.app_user u WHERE u.id = NEW.approved_by AND u.status = 'active'
                     AND (u.role = 'SUPERADMIN'
                          OR (u.role = 'ADMIN' AND EXISTS (SELECT 1 FROM app.user_module_grant g
                                                            WHERE g.user_id = u.id AND g.module = 'mdm' AND g.access = 'w')))) THEN
      RAISE EXCEPTION 'device_command %: user % may not approve MDM commands', OLD.command_uuid, NEW.approved_by
        USING ERRCODE = 'insufficient_privilege';
    END IF;
  END IF;
  IF NEW.status = 'queued' AND OLD.status = 'pending_approval' AND now() >= OLD.expires_at THEN
    RAISE EXCEPTION 'device_command %: expired before approval', OLD.command_uuid USING ERRCODE = 'check_violation';
  END IF;
  -- Delivery facts are written once.
  IF (OLD.sig IS NOT NULL AND NEW.sig IS DISTINCT FROM OLD.sig)
     OR (OLD.sent_at IS NOT NULL AND (NEW.sent_at, NEW.sent_via) IS DISTINCT FROM (OLD.sent_at, OLD.sent_via))
     OR (OLD.acked_at IS NOT NULL AND NEW.acked_at IS DISTINCT FROM OLD.acked_at)
     OR (OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
    RAISE EXCEPTION 'device_command %: delivery columns are written once', OLD.command_uuid USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.status <> OLD.status AND NOT (
       (OLD.status = 'pending_approval' AND NEW.status IN ('queued', 'cancelled', 'expired'))
    OR (OLD.status = 'queued' AND NEW.status IN ('sent', 'cancelled', 'expired'))
    OR (OLD.status = 'sent' AND NEW.status IN ('acked', 'failed', 'expired'))
    OR (OLD.status = 'acked' AND NEW.status IN ('done', 'failed'))) THEN
    RAISE EXCEPTION 'device_command %: status % cannot become %', OLD.command_uuid, OLD.status, NEW.status USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.status = OLD.status AND OLD.status IN ('done', 'failed', 'cancelled', 'expired') THEN
    RAISE EXCEPTION 'device_command %: a % command is final', OLD.command_uuid, OLD.status USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER device_command_flow BEFORE INSERT OR UPDATE ON app.device_command
  FOR EACH ROW EXECUTE FUNCTION app.device_command_flow();
CREATE TRIGGER device_command_no_delete BEFORE DELETE ON app.device_command
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER device_command_no_truncate BEFORE TRUNCATE ON app.device_command
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('support_ro', 'app', 'device_command', 'SELECT', 'V0102: the support desk sees the MDM activity log (not the result, personal)');
UPDATE app.db_role_grant SET except_columns = '{result}' WHERE role = 'support_ro' AND object = 'device_command';

COMMENT ON TABLE app.device_command IS 'MDM command to one device (lock, unlock, message, locate, reboot, factory reset): idempotent by command_uuid, pushed by FCM and pulled on sync; factory reset needs a second person (D-MDM-01).
owner: backend:mdm | capture: ONLINE | retention: audit | pii: personal';
COMMENT ON COLUMN app.device_command.command_uuid IS 'UUID of the command generated by the portal; the idempotency key.';
COMMENT ON COLUMN app.device_command.device_id IS 'Target device (app.device).';
COMMENT ON COLUMN app.device_command.type IS 'lock, unlock, message, locate, reboot or factory_reset.';
COMMENT ON COLUMN app.device_command.params IS 'Type-specific parameters (e.g. the message text and its language); no secrets.';
COMMENT ON COLUMN app.device_command.status IS 'pending_approval (factory reset only), queued, sent, acked, done, failed, cancelled or expired; moves only forward.';
COMMENT ON COLUMN app.device_command.requested_by IS 'Portal user who requested the command, from the token.';
COMMENT ON COLUMN app.device_command.requested_at IS 'UTC instant the command was requested.';
COMMENT ON COLUMN app.device_command.reason IS 'Reason typed by the requester.';
COMMENT ON COLUMN app.device_command.approved_by IS 'Second user who approved a factory reset; never the requester.';
COMMENT ON COLUMN app.device_command.approved_at IS 'UTC instant of the approval.';
COMMENT ON COLUMN app.device_command.expires_at IS 'UTC instant after which the device must not act on the command (at most 30 days after the request).';
COMMENT ON COLUMN app.device_command.sig IS 'Server signature over the command the DPC verifies before acting (ES256, base64url); set when queued for sending.';
COMMENT ON COLUMN app.device_command.sent_at IS 'UTC instant the command was first handed to FCM or to a sync answer.';
COMMENT ON COLUMN app.device_command.sent_via IS 'fcm or sync: the channel that first delivered it.';
COMMENT ON COLUMN app.device_command.acked_at IS 'UTC instant the device acknowledged receipt.';
COMMENT ON COLUMN app.device_command.completed_at IS 'UTC instant the device reported the command done or failed.';
COMMENT ON COLUMN app.device_command.result IS '[pii:personal] Result the device reported (e.g. the position of a locate).';
COMMENT ON COLUMN app.device_command.error IS 'Short error text of a failed command (no personal data).';
COMMENT ON COLUMN app.device_command.cancelled_by IS 'User who cancelled the command; set exactly when the status is cancelled.';
COMMENT ON COLUMN app.device_command.updated_at IS 'UTC instant of the last status change.';

SELECT app.apply_db_role_grants();
