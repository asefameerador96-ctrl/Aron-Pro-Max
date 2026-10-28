-- V0120 ingest-registry pruning (lead action row 9 item 3, review Medium 10; docs/16 s6.1 "Pruning", s13.1;
-- D-DB-REGISTRY-PRUNE-01): app.ingest_registry keeps a record's uuid for cfg.retention.ingest_registry_days (45 by
-- default) after it was LAST seen, then the row goes. Only settled rows go: `accepted` and `rejected`. `voided` rows are
-- tombstones (an admin void must keep refusing that uuid); `quarantined` and `parked` rows wait on a review or a parent
-- and are released by updating them; an accepted memo uuid with no app.memo row of its own was matched to a paper memo
-- (its server_id names the twin, which memoAlias follows for later collections and voids): all three are kept.
--
-- app.prune_ingest_registry(p_now, p_limit) deletes at most p_limit expired rows, oldest last_seen_at first, and
-- returns the number deleted: the worker's RetentionJob calls it in short transactions until it returns less than the
-- chunk, so a run is idempotent and a second run the same day deletes nothing. The window is the key's global value
-- in force at p_now (app.cfg_value), else its default, never below the key's minimum (30) nor below the largest
-- cfg.sync.max_backdate_days in force + 31 (the key's rule "must exceed max_backdate_days + 30" is not enforced by the
-- config editor; the working-day window reaches at most max(backdate, registry - 30) days, WorkingDays.ceiling), so no
-- row inside the replay window is ever pruned. Keyed on last_seen_at, not received_at: a uuid a phone is still
-- re-sending stays, and the delete re-checks last_seen_at on the row it locks, so a replay that touched the row while
-- the prune waited for it keeps it (READ COMMITTED re-evaluates only the outer row's own conditions). The candidates
-- are taken as an array (an InitPlan), so each chunk reads the partial index and the partitions' primary keys, never
-- the whole registry. Runs with the caller's rights: worker_rw holds DELETE on the registry (V0014) and jobs_rw
-- inherits it.
-- The partial index serves the oldest-first scan; app.ingest_registry is pilot-sized in the test account and a
-- partitioned index cannot be built CONCURRENTLY, so the build is marked for squawk; lock_timeout bounds the wait.

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX ingest_registry_prune ON app.ingest_registry (last_seen_at) WHERE status IN ('accepted', 'rejected');

CREATE FUNCTION app.ingest_registry_days(p_now timestamptz DEFAULT now()) RETURNS int
LANGUAGE sql STABLE
AS $$
  WITH v AS (
    SELECT k.key, k.default_value, k.bounds,
           (SELECT max((c.value #>> '{}')::int) FROM app.cfg_value c
             WHERE c.key = k.key AND c.effective_from <= p_now AND (c.effective_to IS NULL OR c.effective_to > p_now)) AS in_force,
           (SELECT (c.value #>> '{}')::int FROM app.cfg_value c
             WHERE c.key = k.key AND c.scope_type = 'global' AND c.effective_from <= p_now AND (c.effective_to IS NULL OR c.effective_to > p_now)) AS global_value
      FROM app.cfg_key k WHERE k.key IN ('cfg.retention.ingest_registry_days', 'cfg.sync.max_backdate_days'))
  SELECT greatest(
           coalesce((SELECT coalesce(global_value, (default_value #>> '{}')::int) FROM v WHERE key = 'cfg.retention.ingest_registry_days'), 45),
           coalesce((SELECT (bounds ->> 'min')::int FROM v WHERE key = 'cfg.retention.ingest_registry_days'), 30),
           coalesce((SELECT greatest(in_force, (default_value #>> '{}')::int) FROM v WHERE key = 'cfg.sync.max_backdate_days'), 7) + 31)
$$;
COMMENT ON FUNCTION app.ingest_registry_days(timestamptz) IS 'Retention of app.ingest_registry in days at the instant: the global cfg.retention.ingest_registry_days in force, else its default, never below the key''s minimum nor below the largest cfg.sync.max_backdate_days in force (or its default) + 31.';

CREATE FUNCTION app.prune_ingest_registry(p_now timestamptz DEFAULT now(), p_limit int DEFAULT 1000) RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
  cutoff timestamptz;
  n      int;
BEGIN
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
    RAISE EXCEPTION 'app.prune_ingest_registry: p_limit must be 1 to 10000' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  cutoff := p_now - make_interval(days => app.ingest_registry_days(p_now));
  DELETE FROM app.ingest_registry r
   WHERE r.client_uuid = ANY (ARRAY(
           SELECT x.client_uuid FROM app.ingest_registry x
            WHERE x.status IN ('accepted', 'rejected') AND x.last_seen_at < cutoff
              AND NOT (x.record_type = 'memo' AND x.status = 'accepted'
                       AND NOT EXISTS (SELECT 1 FROM app.memo m WHERE m.client_uuid = x.client_uuid AND m.business_date = x.business_date))
            ORDER BY x.last_seen_at LIMIT p_limit))
     AND r.status IN ('accepted', 'rejected')
     AND r.last_seen_at < cutoff;                -- re-checked on the locked row: a replay that touched it meanwhile keeps it
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
COMMENT ON FUNCTION app.prune_ingest_registry(timestamptz, int) IS 'Deletes at most p_limit settled (accepted, rejected) app.ingest_registry rows last seen more than app.ingest_registry_days() before p_now, oldest first; voided tombstones, quarantined and parked rows and matched memo uuids are kept. Returns the number deleted (the worker repeats until it is below the chunk).';

SELECT app.apply_db_role_grants();
