-- V0033 outbox in commit order and a dead letter for the rebuild queue (audit AUD-DA-01; docs/16 s8.6).
-- (a) app.domain_event.tx_id: the writing transaction (pg_current_xact_id()). Identity ids are taken at INSERT, not at
--     COMMIT, so a consumer reading by id can step over a row whose transaction commits later. A consumer reads only rows
--     with tx_id < app.outbox_horizon() (every older transaction has ended), ordered by (tx_id, id), and stores the pair
--     in app.event_consumer. A long transaction holds the horizon back: alert on app.outbox_horizon_lag().
--     Rows written before V0033 have tx_id NULL and sort first (consumers read them with coalesce(tx_id, '0')).
--     After a logical dump and restore into another server (the move to the final account, docs/28), new transaction
--     ids may be lower than the restored ones: reset each consumer to (NULL, its last id) and read by id once.
-- (b) The aggregate projector recomputes by dirty key (docs/16 s8.6, D-61); last_event_id is informational, never a skip
--     guard. Domain events are the Phase 2 feed.
-- (c) app.dirty_key gets attempts, last_error, not_before and dead_at: the worker backs off a failing key and parks it
--     as dead after cfg-driven attempts (T-1-105: 5); re-marking a key revives it.
-- (d) V0018 checker follow-ups: a deprecated event version is refused on insert; the catalogue cannot be truncated.

SET lock_timeout = '5s';

-- ---------- (a) commit order ----------
ALTER TABLE app.domain_event ADD COLUMN tx_id xid8;
ALTER TABLE app.domain_event ALTER COLUMN tx_id SET DEFAULT pg_current_xact_id();
-- The outbox is range-partitioned, so CONCURRENTLY is not available; it is small at pilot size and empty in a new
-- (final) account, and the index is built once.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX domain_event_commit_order ON app.domain_event (tx_id, id);

ALTER TABLE app.event_consumer ADD COLUMN last_tx_id xid8;

CREATE FUNCTION app.outbox_horizon() RETURNS xid8
LANGUAGE sql STABLE
AS $$ SELECT pg_snapshot_xmin(pg_current_snapshot()) $$;

-- Age of the oldest transaction still open that holds the horizon back (zero when none); the worker alerts on it.
-- SECURITY DEFINER: other sessions' transaction columns of pg_stat_activity are hidden from the runtime roles.
CREATE FUNCTION app.outbox_horizon_lag() RETURNS interval
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog
AS $$
  SELECT coalesce(now() - min(xact_start), interval '0')
    FROM pg_stat_activity
   WHERE backend_xid IS NOT NULL AND pid <> pg_backend_pid()
$$;

-- ---------- (c) dead letter ----------
ALTER TABLE app.dirty_key ADD COLUMN attempts smallint NOT NULL DEFAULT 0;
ALTER TABLE app.dirty_key ADD COLUMN last_error text;
ALTER TABLE app.dirty_key ADD COLUMN not_before timestamptz;
ALTER TABLE app.dirty_key ADD COLUMN dead_at timestamptz;
ALTER TABLE app.dirty_key ADD CONSTRAINT dirty_key_attempts_check CHECK (attempts >= 0) NOT VALID;
ALTER TABLE app.dirty_key ADD CONSTRAINT dirty_key_last_error_check CHECK (length(last_error) <= 2000) NOT VALID;

-- Re-marking a key (new data arrived) revives a dead or backed-off key: the new data may be what it needed.
CREATE OR REPLACE FUNCTION app.mark_dirty(p_kind text, p_subject bigint, p_date date, p_reason text DEFAULT NULL) RETURNS void
LANGUAGE sql
AS $$
  INSERT INTO app.dirty_key (kind, subject_id, business_date, reason) VALUES (p_kind, p_subject, p_date, p_reason)
  ON CONFLICT (kind, subject_id, business_date)
  DO UPDATE SET last_dirtied_at = now(), dirty_count = app.dirty_key.dirty_count + 1, claimed_at = NULL, claimed_by = NULL,
                reason = coalesce(excluded.reason, app.dirty_key.reason),
                attempts = 0, not_before = NULL, dead_at = NULL
$$;

-- Dead keys per kind for the sync-health view (support and ops read it).
CREATE VIEW app.v_dirty_key_dead AS
  SELECT kind, count(*) AS dead_keys, min(dead_at) AS oldest_dead_at
    FROM app.dirty_key WHERE dead_at IS NOT NULL GROUP BY kind;

-- ---------- (d) catalogue follow-ups ----------
CREATE OR REPLACE FUNCTION app.domain_event_check_payload() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  req     jsonb;
  enforce boolean;
  dep     timestamptz;
BEGIN
  SELECT t.payload_schema -> 'required', t.enforce_required, t.deprecated_at INTO req, enforce, dep
    FROM app.domain_event_type t
   WHERE t.event_type = NEW.event_type AND t.payload_version = NEW.payload_version;
  IF req IS NULL THEN
    RAISE EXCEPTION 'app.domain_event: % v% is not in app.domain_event_type', NEW.event_type, NEW.payload_version
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF dep IS NOT NULL AND dep <= now() THEN
    RAISE EXCEPTION 'app.domain_event: % v% is deprecated since %; write the current version', NEW.event_type, NEW.payload_version, dep
      USING ERRCODE = 'check_violation';
  END IF;
  IF jsonb_typeof(NEW.payload) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'app.domain_event: % v% payload is not a JSON object', NEW.event_type, NEW.payload_version
      USING ERRCODE = 'check_violation';
  END IF;
  IF enforce AND NOT (NEW.payload ?& ARRAY(SELECT jsonb_array_elements_text(req))) THEN
    RAISE EXCEPTION 'app.domain_event: % v% payload lacks required keys %', NEW.event_type, NEW.payload_version,
      ARRAY(SELECT k FROM jsonb_array_elements_text(req) k WHERE NOT NEW.payload ? k)
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER domain_event_type_no_truncate BEFORE TRUNCATE ON app.domain_event_type
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();

-- ---------- data dictionary ----------
COMMENT ON COLUMN app.domain_event.tx_id IS 'Transaction that wrote the row (pg_current_xact_id()); consumers read below app.outbox_horizon() in (tx_id, id) order. Null on rows written before V0033.';
COMMENT ON COLUMN app.event_consumer.last_tx_id IS 'tx_id of the last row the consumer processed; with last_event_id it is the consumer''s position in (tx_id, id) order. Null before the first V0033-era row.';
COMMENT ON COLUMN app.event_consumer.last_event_id IS 'id of the last row the consumer processed; informational for the aggregate projector (it recomputes by dirty key), part of the position for feed consumers.';
COMMENT ON COLUMN app.dirty_key.attempts IS 'Failed rebuild attempts since the key was last marked dirty.';
COMMENT ON COLUMN app.dirty_key.last_error IS 'Error text of the last failed rebuild (no personal data).';
COMMENT ON COLUMN app.dirty_key.not_before IS 'UTC time before which the worker does not retry the key (back-off); null means at once.';
COMMENT ON COLUMN app.dirty_key.dead_at IS 'UTC time the key was parked as dead after too many attempts; null while live. Marking the key again revives it.';
COMMENT ON VIEW app.v_dirty_key_dead IS 'Dead rebuild keys per kind, for the sync-health page and alerting.
owner: db | capture: SERVER | retention: ops | pii: none';
COMMENT ON COLUMN app.v_dirty_key_dead.kind IS 'Kind of the rebuild key.';
COMMENT ON COLUMN app.v_dirty_key_dead.dead_keys IS 'Number of dead keys of the kind.';
COMMENT ON COLUMN app.v_dirty_key_dead.oldest_dead_at IS 'UTC time the oldest of them was parked.';
COMMENT ON FUNCTION app.outbox_horizon() IS 'Outbox read horizon: every transaction with a lower tx_id has ended, so rows below it never appear later.';
COMMENT ON FUNCTION app.outbox_horizon_lag() IS 'Age of the oldest open transaction that holds the outbox horizon back; alert above a few minutes.';

SELECT app.apply_db_role_grants();
