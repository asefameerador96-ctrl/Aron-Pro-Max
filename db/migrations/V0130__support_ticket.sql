-- V0130 support tickets (TSO manual build, contract docs/requests/tso-manual-build-2026-10.md s2.1; D-TSO-MAN-17..25;
-- gap items TK-02, TK-05, TK-20; lane F2). The TSO raises a ticket on the phone (offline outbox, record support_ticket,
-- idempotent upsert by the client UUID); the support desk works it on the web; the thread holds comments and state
-- changes from both sides.
--
-- 1. app.support_ticket_no_seq START 100001: the human ticket number is "INC" + at least 6 digits, assigned at the first
--    insert, so the vendor's numbers (INC0107xx), which TSOs will quote, never collide (D-TSO-MAN-18). The default is
--    app.next_support_ticket_no(): one nextval, padded to 6 digits below 1,000,000 and printed in full above it
--    (INC999999 is followed by INC1000000). A bare lpad(.., 6, '0') would cut a 7-digit number to its first 6 digits.
-- 2. app.support_ticket: the capture row, write-once (record type support_ticket). The envelope columns are exactly
--    app.feedback's (V0007), so the sync writer stores it like any other record; payload columns issue_type_code, title,
--    description, affected_username and source. Only voided_at (admin data void) and the origin restamp may change.
-- 3. app.support_ticket_state: one mutable row per ticket that the record handler creates in the ticket's transaction
--    and the desk updates (status, priority, assignee, resolution, cancel, close). Status moves only along the
--    D-TSO-MAN-20 edges (new>in_progress, new|in_progress>resolved, new|in_progress>cancelled, resolved>closed): no
--    reopen. Resolution facts, close and cancel facts are written once. Every resolved or closed ticket has resolved_at,
--    and closed_at is set exactly when the status is closed (lead ruling R-F-08). It carries data_origin as a child of
--    the ticket (like app.feedback_status), because every transaction table is in the origin registry (DataOriginTest).
-- 4. app.support_ticket_event: the append-only thread (device rows via sync: comment, close_review; desk and job rows via
--    the API: created, status_change, resolution, priority_change, assign). Envelope as app.feedback; for a desk row
--    family_uuid = client_uuid, business_date = app.dhaka_date(now()), captured_at = now(), user_id = the actor.
--    Child of the ticket in the origin registry (child_col ticket_uuid, parent_col client_uuid). body holds up to 2000
--    characters, except a resolution event: its D-TSO-MAN-20 message (the TicketID line with the ticket number, then
--    Resolution Code with the code label, then Resolution Notes and the notes) is at most 62 fixed characters + a ticket
--    number of up to 22 (INC + a bigint) + a code label of up to 120 (code_list_item.label_en) + notes of up to 2000 =
--    2204, so it may hold up to 2400, the contract's SupportTicketEvent.body maxLength (lead ruling R-F-02).
--    op_sha256 (R-F-03): a desk PATCH is replayed by its op_uuid. The first event row the op writes has client_uuid =
--    op_uuid and stores the SHA-256 of the request body as canonical JSON; every other row leaves it null. Only a desk
--    row (via = 'web') may carry it (CHECK support_ticket_event_op_hash_desk), so a device record whose payload names an
--    op_sha256 key, or a job row, is refused by the database and not only by the sync writer's column list (V0116).
-- New tables: the indexes need no CONCURRENTLY and the foreign keys no NOT VALID.

SET lock_timeout = '5s';

-- ---------- 1. ticket number ----------
CREATE SEQUENCE app.support_ticket_no_seq START 100001 NO CYCLE;
COMMENT ON SEQUENCE app.support_ticket_no_seq IS 'Source of support ticket numbers; starts at 100001 so INC numbers never collide with the vendor''s INC0107xx (D-TSO-MAN-18).';

CREATE FUNCTION app.next_support_ticket_no() RETURNS text
LANGUAGE sql VOLATILE
AS $$
  SELECT 'INC' || CASE WHEN s.n < 1000000 THEN lpad(s.n::text, 6, '0') ELSE s.n::text END
    FROM (SELECT nextval('app.support_ticket_no_seq') AS n) s
$$;
COMMENT ON FUNCTION app.next_support_ticket_no() IS 'Next support ticket number: INC + the sequence value, padded to 6 digits, in full from 1,000,000 (INC1000000).';

-- ---------- 2. ticket (capture row) ----------
CREATE TABLE app.support_ticket (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,
  business_date_device date,
  user_id              bigint NOT NULL REFERENCES app.app_user(id),
  device_id            bigint REFERENCES app.device(id),
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
  voided_at            timestamptz,
  external_ref         varchar(64) UNIQUE,
  ticket_no            text NOT NULL UNIQUE DEFAULT app.next_support_ticket_no() CHECK (ticket_no ~ '^INC[0-9]{6,}$'),
  issue_type_code      text NOT NULL CHECK (issue_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  title                text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description          text NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
  affected_username    text CHECK (affected_username ~ '^[A-Za-z0-9._-]{3,40}$'),
  source               text NOT NULL CHECK (source IN ('quick','manual')),
  data_origin          app.data_origin
);
CREATE INDEX support_ticket_business_date ON app.support_ticket (business_date);
CREATE INDEX support_ticket_user_created ON app.support_ticket (user_id, created_at DESC);
CREATE TRIGGER support_ticket_immutable BEFORE UPDATE OR DELETE ON app.support_ticket
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'data_origin');
CREATE TRIGGER support_ticket_stamp_origin BEFORE INSERT ON app.support_ticket
  FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin();
CREATE TRIGGER support_ticket_guard_origin BEFORE UPDATE ON app.support_ticket
  FOR EACH ROW EXECUTE FUNCTION app.guard_data_origin();

-- ---------- 3. ticket state (one mutable row per ticket) ----------
CREATE TABLE app.support_ticket_state (
  ticket_uuid        uuid PRIMARY KEY REFERENCES app.support_ticket(client_uuid),
  requester_user_id  bigint NOT NULL REFERENCES app.app_user(id),
  requester_role     text NOT NULL REFERENCES app.role_def(role),
  affected_user_id   bigint REFERENCES app.app_user(id),
  status             text NOT NULL DEFAULT 'new' CHECK (status IN ('new','in_progress','resolved','closed','cancelled')),
  priority           text NOT NULL CHECK (priority IN ('p1','p2','p3','p4')),
  assignee_user_id   bigint REFERENCES app.app_user(id),
  first_response_at  timestamptz,
  resolved_at        timestamptz,
  resolved_by        bigint REFERENCES app.app_user(id),
  resolution_code    text CHECK (resolution_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  resolution_notes   text CHECK (length(resolution_notes) BETWEEN 1 AND 2000),
  closed_at          timestamptz,
  closed_by          bigint REFERENCES app.app_user(id),
  cancelled_at       timestamptz,
  cancelled_by       bigint REFERENCES app.app_user(id),
  cancel_reason      text CHECK (length(cancel_reason) BETWEEN 3 AND 500),
  version            int NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  data_origin        app.data_origin,
  CONSTRAINT support_ticket_state_resolution CHECK (status NOT IN ('resolved','closed') OR closed_at IS NOT NULL
                                                    OR (resolution_code IS NOT NULL AND resolution_notes IS NOT NULL)),
  CONSTRAINT support_ticket_state_cancel CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),
  CONSTRAINT support_ticket_state_closed CHECK ((status = 'closed') = (closed_at IS NOT NULL)),
  CONSTRAINT support_ticket_state_resolved_at CHECK (status NOT IN ('resolved','closed') OR resolved_at IS NOT NULL)
);
CREATE INDEX support_ticket_state_requester ON app.support_ticket_state (requester_user_id, status);
CREATE INDEX support_ticket_state_queue ON app.support_ticket_state (status, updated_at);
CREATE INDEX support_ticket_state_assignee ON app.support_ticket_state (assignee_user_id) WHERE assignee_user_id IS NOT NULL;
CREATE TRIGGER support_ticket_state_immutable BEFORE UPDATE OR DELETE ON app.support_ticket_state
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=affected_user_id', 'status', 'priority', 'assignee_user_id',
    '=first_response_at', '=resolved_at', '=resolved_by', '=resolution_code', '=resolution_notes', '=closed_at', '=closed_by',
    '=cancelled_at', '=cancelled_by', '=cancel_reason', 'version', 'updated_at', 'data_origin');
CREATE TRIGGER support_ticket_state_status_flow BEFORE UPDATE OF status ON app.support_ticket_state
  FOR EACH ROW EXECUTE FUNCTION app.guard_transition('status', 'new>in_progress', 'new>resolved', 'in_progress>resolved',
    'new>cancelled', 'in_progress>cancelled', 'resolved>closed');
CREATE TRIGGER support_ticket_state_stamp_origin BEFORE INSERT ON app.support_ticket_state
  FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin('support_ticket', 'ticket_uuid', 'client_uuid');
CREATE TRIGGER support_ticket_state_guard_origin BEFORE UPDATE ON app.support_ticket_state
  FOR EACH ROW EXECUTE FUNCTION app.guard_data_origin();

-- ---------- 4. ticket thread (append-only) ----------
CREATE TABLE app.support_ticket_event (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid          uuid NOT NULL UNIQUE,
  ticket_uuid          uuid NOT NULL REFERENCES app.support_ticket(client_uuid),
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,
  business_date_device date,
  user_id              bigint NOT NULL REFERENCES app.app_user(id),
  device_id            bigint REFERENCES app.device(id),
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
  voided_at            timestamptz,
  external_ref         varchar(64) UNIQUE,
  via                  text NOT NULL DEFAULT 'device' CHECK (via IN ('device','web','job')),
  kind                 text NOT NULL CHECK (kind IN ('created','comment','close_review','status_change','resolution','priority_change','assign')),
  body                 text CHECK (length(body) <= 2000 OR (kind = 'resolution' AND length(body) <= 2400)),
  from_status          text CHECK (from_status IN ('new','in_progress','resolved','closed','cancelled')),
  to_status            text CHECK (to_status IN ('new','in_progress','resolved','closed','cancelled')),
  resolution_code      text CHECK (resolution_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  op_sha256            bytea CHECK (op_sha256 IS NULL OR octet_length(op_sha256) = 32),
  data_origin          app.data_origin,
  CONSTRAINT support_ticket_event_op_hash_desk CHECK (op_sha256 IS NULL OR via = 'web')
);
CREATE INDEX support_ticket_event_thread ON app.support_ticket_event (ticket_uuid, created_at);
CREATE TRIGGER support_ticket_event_immutable BEFORE UPDATE OR DELETE ON app.support_ticket_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'data_origin');
CREATE TRIGGER support_ticket_event_stamp_origin BEFORE INSERT ON app.support_ticket_event
  FOR EACH ROW EXECUTE FUNCTION app.stamp_data_origin('support_ticket', 'ticket_uuid', 'client_uuid');
CREATE TRIGGER support_ticket_event_guard_origin BEFORE UPDATE ON app.support_ticket_event
  FOR EACH ROW EXECUTE FUNCTION app.guard_data_origin();

-- ---------- origin registry (V0116) ----------
INSERT INTO app.data_origin_capture (table_name) VALUES ('support_ticket');
INSERT INTO app.data_origin_capture (table_name, parent, child_col, parent_col, pass) VALUES
  ('support_ticket_state', 'support_ticket', 'ticket_uuid', 'client_uuid', 2),
  ('support_ticket_event', 'support_ticket', 'ticket_uuid', 'client_uuid', 2);

-- ---------- comments (data dictionary) ----------
COMMENT ON TABLE app.support_ticket IS 'A support ticket a field user raised from the phone (Quick Ticket or Create Ticket), with its INC number; write-once, its state is in app.support_ticket_state and its thread in app.support_ticket_event.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: personal';
COMMENT ON TABLE app.support_ticket_state IS 'Current state of one support ticket (status, priority, assignee, resolution, close, cancel), written by the record handler at creation and by the support desk; status moves only forward (no reopen).
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: personal';
COMMENT ON TABLE app.support_ticket_event IS 'Append-only thread of a support ticket: the reporter''s comments and close review (synced from the phone) and the desk''s replies, status changes, resolution, priority and assignment.
owner: backend:masterdata | capture: OFFLINE | retention: transaction | pii: personal';

-- Envelope columns: the same meaning and wording as app.feedback (V0016).
DO $$
DECLARE
  t text;
  c record;
BEGIN
  FOREACH t IN ARRAY ARRAY['support_ticket', 'support_ticket_event'] LOOP
    FOR c IN SELECT a.attname, col_description(a.attrelid, a.attnum) AS d
               FROM pg_attribute a
              WHERE a.attrelid = 'app.feedback'::regclass AND a.attnum > 0 AND NOT a.attisdropped
                AND a.attname IN ('id', 'client_uuid', 'family_uuid', 'business_date', 'business_date_device', 'user_id', 'device_id',
                                  'acting_for_user_id', 'captured_at', 'captured_elapsed_ms', 'boot_count', 'clock_offset_ms',
                                  'captured_offline', 'schema_version', 'config_version', 'bundle_version', 'bundle_stale',
                                  'first_batch_uuid', 'received_at', 'created_at', 'voided_at', 'external_ref')
    LOOP
      EXECUTE format('COMMENT ON COLUMN app.%I.%I IS %L', t, c.attname, c.d);
    END LOOP;
    EXECUTE format('COMMENT ON COLUMN app.%I.data_origin IS %L', t,
      'Origin of the row (production, pilot, test, seed): the most synthetic of its users, outlet, route'
      || CASE WHEN t = 'support_ticket' THEN '' ELSE ' and parent row (app.support_ticket)' END
      || ', stamped at insert; changes only through app.restamp_data_origin.');
  END LOOP;
END $$;
COMMENT ON COLUMN app.support_ticket.ticket_no IS 'Human ticket number INC + at least 6 digits (app.next_support_ticket_no), assigned at the first insert; quoted by TSOs and the desk.';
COMMENT ON COLUMN app.support_ticket.issue_type_code IS 'Issue type, a code of the code list ticket_issue_type (31 Annex A types); its attrs give the default priority.';
COMMENT ON COLUMN app.support_ticket.title IS 'Short title of the ticket (1 to 120 characters).';
COMMENT ON COLUMN app.support_ticket.description IS '[pii:personal] Description of the problem as written by the reporter (up to 2000 characters; empty for a quick ticket without text).';
COMMENT ON COLUMN app.support_ticket.affected_username IS '[pii:personal] Username of the user the problem is about when the ticket was raised from another user''s phone (D-TSO-MAN-24); the server resolves it to app.support_ticket_state.affected_user_id.';
COMMENT ON COLUMN app.support_ticket.source IS 'Where the ticket was raised: quick (Quick Ticket tile) or manual (Create Ticket form).';

COMMENT ON COLUMN app.support_ticket_state.ticket_uuid IS 'client_uuid of the ticket (app.support_ticket).';
COMMENT ON COLUMN app.support_ticket_state.requester_user_id IS 'Reporter: the user who raised the ticket (the token user of the record); the ticket is visible to this user.';
COMMENT ON COLUMN app.support_ticket_state.requester_role IS 'Role of the reporter when the ticket was raised (cfg.support.ticket_requester_roles).';
COMMENT ON COLUMN app.support_ticket_state.affected_user_id IS 'User resolved from the ticket''s affected_username (desk only); null when none was named or no such user exists.';
COMMENT ON COLUMN app.support_ticket_state.status IS 'Ticket status: new, in_progress, resolved, closed or cancelled; moves only forward (new>in_progress, new|in_progress>resolved or cancelled, resolved>closed).';
COMMENT ON COLUMN app.support_ticket_state.priority IS 'Priority p1 (Highest) to p4 (Low); starts at the issue type''s default, the desk may change it.';
COMMENT ON COLUMN app.support_ticket_state.assignee_user_id IS 'Support user the ticket is assigned to; null when unassigned.';
COMMENT ON COLUMN app.support_ticket_state.first_response_at IS 'UTC time of the first support action (start or first reply); set once.';
COMMENT ON COLUMN app.support_ticket_state.resolved_at IS 'UTC time support resolved the ticket; set once; present on every resolved or closed ticket.';
COMMENT ON COLUMN app.support_ticket_state.resolved_by IS 'Support user who resolved the ticket; set once.';
COMMENT ON COLUMN app.support_ticket_state.resolution_code IS 'Resolution code, a code of the code list ticket_resolution_code; set once, with the resolution.';
COMMENT ON COLUMN app.support_ticket_state.resolution_notes IS '[pii:personal] Resolution notes written by support (1 to 2000 characters); set once, with the resolution.';
COMMENT ON COLUMN app.support_ticket_state.closed_at IS 'UTC time the ticket was closed (the reporter''s Submit Review & Close, or support); set once, exactly when the status is closed.';
COMMENT ON COLUMN app.support_ticket_state.closed_by IS 'User who closed the ticket; set once.';
COMMENT ON COLUMN app.support_ticket_state.cancelled_at IS 'UTC time support cancelled the ticket; set exactly when the status is cancelled.';
COMMENT ON COLUMN app.support_ticket_state.cancelled_by IS 'Support user who cancelled the ticket; set once.';
COMMENT ON COLUMN app.support_ticket_state.cancel_reason IS '[pii:personal] Reason support gave for the cancellation (3 to 500 characters); set once.';
COMMENT ON COLUMN app.support_ticket_state.version IS 'Optimistic-lock version; every desk update checks the expected version and adds one.';
COMMENT ON COLUMN app.support_ticket_state.updated_at IS 'UTC instant of the last update.';
COMMENT ON COLUMN app.support_ticket_state.data_origin IS 'Origin of the row (production, pilot, test, seed): the most synthetic of its users and parent row (app.support_ticket), stamped at insert; changes only through app.restamp_data_origin.';

COMMENT ON COLUMN app.support_ticket_event.ticket_uuid IS 'client_uuid of the ticket the event belongs to (app.support_ticket).';
COMMENT ON COLUMN app.support_ticket_event.user_id IS 'Actor: the reporter for a device row (from the token), the support user for a desk row; the system account for a job row.';
COMMENT ON COLUMN app.support_ticket_event.via IS 'Channel the event came through: device (sync record support_ticket_event), web (support desk) or job.';
COMMENT ON COLUMN app.support_ticket_event.kind IS 'Event kind: created, comment, close_review (the reporter''s Submit Review & Close), status_change, resolution, priority_change or assign.';
COMMENT ON COLUMN app.support_ticket_event.body IS '[pii:personal] Text of a comment (up to 2000 characters) or of the resolution message (up to 2400: the D-TSO-MAN-20 header with the ticket number and the code label of up to 120 characters, then up to 2000 characters of notes); null for events without text.';
COMMENT ON COLUMN app.support_ticket_event.from_status IS 'Ticket status before a status-moving event; null otherwise.';
COMMENT ON COLUMN app.support_ticket_event.to_status IS 'Ticket status after a status-moving event; null otherwise.';
COMMENT ON COLUMN app.support_ticket_event.resolution_code IS 'Resolution code of a resolution event (code list ticket_resolution_code); null otherwise.';
COMMENT ON COLUMN app.support_ticket_event.op_sha256 IS 'SHA-256 (32 bytes) of a support desk PATCH request body as canonical JSON, stored only on the first event row the op writes (its client_uuid is the op_uuid); null on every other row. Replay: the same op_uuid with the same hash answers 200 and writes nothing; the same op_uuid with another hash or for another ticket answers 409 ERR_CONFLICT (R-F-03).';

SELECT app.apply_db_role_grants();
