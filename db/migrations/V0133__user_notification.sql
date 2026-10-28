-- V0133 the notification inbox behind the TSO bell (contract docs/requests/tso-manual-build-2026-10.md s2.3;
-- D-TSO-MAN-26; gap item SH-08; lane F2).
--
-- app.user_notification: one row per user and notice (kinds ticket_updated, leave_decided, final_submit_auto). The
-- producers (backend platform Inbox.add, in the caller's transaction) are idempotent per recipient through (user_id,
-- dedupe_key): INSERT ... ON CONFLICT (user_id, dedupe_key) DO NOTHING (lead ruling R-F-04), so one event (a zone's
-- auto-submit) reaches every user it concerns once each, and a producer's retry adds nothing. The bell's unread count
-- reads the partial index; the inbox pages by id descending.
-- Opening the inbox marks everything up to the newest id read: read_at is written once (BEFORE UPDATE guard; every other
-- column is fixed). Rows older than cfg.notify.inbox_retention_days (90, V0134) are deleted by the InboxPruneJob, so
-- the guard does not cover DELETE. No data_origin: the rows are server notices about other rows, kept for weeks, not
-- capture (retention class ops).
-- Grants: the API reads, inserts and marks read (the '*' map rows); the worker inserts (midnight auto Final Submit)
-- and prunes (the map row below).

SET lock_timeout = '5s';

CREATE TABLE app.user_notification (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  kind          text NOT NULL CHECK (kind IN ('ticket_updated','leave_decided','final_submit_auto')),
  ref_type      text NOT NULL CHECK (ref_type IN ('support_ticket','leave_application','zone_day')),
  ref_id        text NOT NULL CHECK (length(ref_id) <= 64),
  title_en      text NOT NULL CHECK (length(title_en) <= 120),
  title_bn      text CHECK (length(title_bn) <= 120),
  body_en       text NOT NULL CHECK (length(body_en) <= 300),
  body_bn       text CHECK (length(body_bn) <= 300),
  dedupe_key    text NOT NULL CHECK (length(dedupe_key) <= 160),
  created_at    timestamptz NOT NULL DEFAULT now(),
  business_date date NOT NULL DEFAULT app.dhaka_date(now()),
  read_at       timestamptz,
  UNIQUE (user_id, dedupe_key)
);
CREATE INDEX user_notification_inbox ON app.user_notification (user_id, id DESC);
CREATE INDEX user_notification_unread ON app.user_notification (user_id) WHERE read_at IS NULL;
CREATE TRIGGER user_notification_immutable BEFORE UPDATE ON app.user_notification
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=read_at');

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('worker_rw', 'app', 'user_notification', 'INSERT, DELETE',
   'V0133: inbox rows of worker jobs (midnight auto Final Submit) and the InboxPruneJob after cfg.notify.inbox_retention_days');

COMMENT ON TABLE app.user_notification IS 'Notification inbox behind the bell: one notice for one user (ticket updated, leave decided, zone auto final-submitted); read_at is set once when the user opens the inbox; rows are pruned after cfg.notify.inbox_retention_days.
owner: backend:notify | capture: ONLINE | retention: ops | pii: personal';
COMMENT ON COLUMN app.user_notification.id IS 'Server surrogate key; the inbox pages by it (newest first) and marks read up to it.';
COMMENT ON COLUMN app.user_notification.user_id IS 'User the notice is for; only this user reads it.';
COMMENT ON COLUMN app.user_notification.kind IS 'Kind of notice: ticket_updated, leave_decided or final_submit_auto.';
COMMENT ON COLUMN app.user_notification.ref_type IS 'Kind of row the notice is about: support_ticket, leave_application or zone_day.';
COMMENT ON COLUMN app.user_notification.ref_id IS 'Key of the row the notice is about (ticket or leave UUID, or <zone_id>:<business_date>), up to 64 characters.';
COMMENT ON COLUMN app.user_notification.title_en IS 'English title of the notice (up to 120 characters).';
COMMENT ON COLUMN app.user_notification.title_bn IS 'Bangla title of the notice; null shows the English title.';
COMMENT ON COLUMN app.user_notification.body_en IS '[pii:personal] English text of the notice (up to 300 characters); may name a person.';
COMMENT ON COLUMN app.user_notification.body_bn IS '[pii:personal] Bangla text of the notice; null shows the English text.';
COMMENT ON COLUMN app.user_notification.dedupe_key IS 'Idempotency key of the producer (for example ticket uuid + event uuid), unique per recipient: a second insert for the same user with the same key is ignored (ON CONFLICT (user_id, dedupe_key) DO NOTHING); the same key may reach several users.';
COMMENT ON COLUMN app.user_notification.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.user_notification.business_date IS 'Asia/Dhaka date the notice was created.';
COMMENT ON COLUMN app.user_notification.read_at IS 'UTC time the user opened the inbox showing this notice; null while unread; set once.';

SELECT app.apply_db_role_grants();
