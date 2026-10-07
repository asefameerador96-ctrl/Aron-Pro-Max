-- V0021 set-based client_uuid uniqueness on the partitioned capture tables (audit AUD-PERF-01). V0007's per-row BEFORE
-- INSERT trigger ran one dynamic query per row, planned each time and probing every monthly partition: with 7 trading
-- days at docs/22 volume (2.0 M visits, 2.8 M lines, 25 partitions) a 200-row ingest batch of visits, memos and lines
-- took 66-79 ms p95, of which the trigger was about 97 % (2.3-2.7 ms with it disabled). The invariant stays: a
-- client_uuid never exists under two business dates. It is now checked once per statement from the transition table
-- (one plan, one join): 8-16 ms p95 for the same batches (db/perf/ingest_batches.sql, docs/status/db.md).
-- Error code and message are unchanged (23505, "<table>_client_uuid_once"). As before, two concurrent transactions
-- with the same uuid under different dates are separated by app.ingest_registry's primary key, which the ingest path
-- writes first in the same transaction.

SET lock_timeout = '5s';

CREATE FUNCTION app.client_uuid_once_rows() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE dup record;
BEGIN
  EXECUTE format('SELECT n.client_uuid, t.business_date AS other FROM new_rows n JOIN app.%I t ON t.client_uuid = n.client_uuid '
                 'AND t.business_date <> n.business_date LIMIT 1', TG_ARGV[0]) INTO dup;
  IF dup.client_uuid IS NOT NULL THEN
    RAISE EXCEPTION 'duplicate key value violates unique constraint "%_client_uuid_once"', TG_ARGV[0]
      USING ERRCODE = 'unique_violation',
            DETAIL = format('Key (client_uuid)=(%s) already exists with business_date %s.', dup.client_uuid, dup.other);
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION app.client_uuid_once_rows() IS 'Statement-level check: refuses inserted rows whose client_uuid already exists in the table (argument) under another business_date.';

DROP TRIGGER visit_client_uuid_once ON app.visit;
CREATE TRIGGER visit_client_uuid_once AFTER INSERT ON app.visit REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION app.client_uuid_once_rows('visit');
DROP TRIGGER memo_client_uuid_once ON app.memo;
CREATE TRIGGER memo_client_uuid_once AFTER INSERT ON app.memo REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION app.client_uuid_once_rows('memo');
DROP TRIGGER memo_line_client_uuid_once ON app.memo_line;
CREATE TRIGGER memo_line_client_uuid_once AFTER INSERT ON app.memo_line REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION app.client_uuid_once_rows('memo_line');
COMMENT ON FUNCTION app.client_uuid_once() IS 'Retired by V0021 (no trigger uses it); kept because a shipped function is never dropped in place.';

-- The writing roles execute trigger functions (V0014 rule); a new function gets the map's grants.
SELECT app.apply_db_role_grants();
