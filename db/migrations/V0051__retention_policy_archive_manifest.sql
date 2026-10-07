-- V0051 AUD-DA-06, the part that needs no spec ruling (docs/16 s13.1, M-37, D-23, D-371):
--   * app.partition_policy gains retention_class (the six docs/16 classes); the registered parents are classified.
--   * app.retention_policy: hot and archive windows per class as rows (docs/16 values; statutory period still to be
--     confirmed, G-data-32). Nothing reads them to drop data yet: the archive job is scheduled before month 12.
--   * app.archive_manifest: one row per partition export; a partition may be dropped only after its row is verified.
--   * app.archive_candidates(today): partitions past their hot window and not yet dropped.
--   * app.default_partition_rows(): rows parked in <parent>_default (the worker alerts when any parent has some).
-- Re-partitioning the unpartitioned high-volume capture tables (docs/16 s8.11 versus docs/24 s12.1) waits for the
-- lead's ruling D-DB-PART-01 (DECISIONS.md): deferred to the pre-staging migration window.

SET lock_timeout = '5s';

CREATE TABLE app.retention_policy (
  retention_class text PRIMARY KEY CHECK (retention_class IN ('transaction','fix','telemetry','quarantine','audit','event_fact')),
  hot_months      int CHECK (hot_months > 0),                                      -- NULL = never leaves the primary
  keep_months     int CHECK (keep_months IS NULL OR keep_months >= hot_months),   -- NULL = kept for ever in the archive
  note            text NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (hot_months IS NOT NULL OR keep_months IS NULL)
);
INSERT INTO app.retention_policy (retention_class, hot_months, keep_months, note) VALUES
  ('transaction', 13, 84, 'dues disputes and audits reach back a year; statutory period unknown, 7 years assumed (G-data-32)'),
  ('fix',          6, 24, 'raw positions; the verdicts and flags live on in fact_visit'),
  ('telemetry',    3, 12, 'activity_log, content_view, sync_batch'),
  ('quarantine',  12, 24, 'parked rows are never archived while status is parked'),
  ('audit',     NULL, NULL, 'never leaves the primary, never deleted'),
  ('event_fact',  25, 84, 'dw event grain and the 25-month daily aggregates; month aggregates are kept for ever');

-- A constant default is a catalogue-only change; 'transaction' is the longest non-audit window, so a parent registered
-- without a class is never archived early.
ALTER TABLE app.partition_policy ADD COLUMN retention_class text NOT NULL DEFAULT 'transaction';
ALTER TABLE app.partition_policy ADD CONSTRAINT partition_policy_retention_class_fkey
  FOREIGN KEY (retention_class) REFERENCES app.retention_policy(retention_class) NOT VALID;
UPDATE app.partition_policy SET retention_class = CASE
    WHEN parent IN ('app.geo_fix', 'app.geo_breadcrumb', 'dw.fact_geo_fix') THEN 'fix'   -- employee positions: no longer in dw than raw
    WHEN parent = 'app.domain_event' THEN 'audit'          -- the outbox is the event history: never dropped
    WHEN parent = 'dw.fact_activity' THEN 'telemetry'
    WHEN parent LIKE 'dw.%' THEN 'event_fact'
    ELSE 'transaction' END;

CREATE TABLE app.archive_manifest (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent          text NOT NULL,
  partition_name  text NOT NULL UNIQUE,
  month           date NOT NULL CHECK (month = date_trunc('month', month)::date),
  row_count       bigint CHECK (row_count >= 0),
  sha256          text CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  blob_url        text CHECK (length(blob_url) <= 500),
  format          text CHECK (format IN ('parquet','sql_gz')),
  status          text NOT NULL DEFAULT 'planned' CHECK (status IN ('planned','exported','verified','dropped','restored')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  exported_at     timestamptz,
  verified_at     timestamptz,
  dropped_at      timestamptz,
  CHECK (status = 'planned' OR (row_count IS NOT NULL AND sha256 IS NOT NULL AND blob_url IS NOT NULL AND format IS NOT NULL AND exported_at IS NOT NULL)),
  CHECK (status NOT IN ('verified','dropped','restored') OR verified_at IS NOT NULL),
  CHECK (status NOT IN ('dropped','restored') OR dropped_at IS NOT NULL)
);
CREATE TRIGGER archive_manifest_flow BEFORE UPDATE OF status ON app.archive_manifest
  FOR EACH ROW EXECUTE FUNCTION app.guard_transition('status', 'planned>exported', 'exported>verified', 'verified>dropped', 'dropped>restored');
-- What was exported is a fact once written: after 'planned', the export columns never change (a re-export is a new
-- partition_name row only after a restore, which the job plans by hand); the identity columns never change at all.
CREATE FUNCTION app.archive_manifest_freeze() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF NEW.parent IS DISTINCT FROM OLD.parent OR NEW.partition_name IS DISTINCT FROM OLD.partition_name
     OR NEW.month IS DISTINCT FROM OLD.month OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'app.archive_manifest: parent, partition_name, month and created_at never change' USING ERRCODE = '42501';
  END IF;
  IF OLD.status <> 'planned' AND (NEW.row_count IS DISTINCT FROM OLD.row_count OR NEW.sha256 IS DISTINCT FROM OLD.sha256
     OR NEW.blob_url IS DISTINCT FROM OLD.blob_url OR NEW.format IS DISTINCT FROM OLD.format
     OR NEW.exported_at IS DISTINCT FROM OLD.exported_at
     OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM OLD.verified_at)
     OR (OLD.dropped_at IS NOT NULL AND NEW.dropped_at IS DISTINCT FROM OLD.dropped_at)) THEN
    RAISE EXCEPTION 'app.archive_manifest: the export facts are written once' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER archive_manifest_freeze BEFORE UPDATE ON app.archive_manifest
  FOR EACH ROW EXECUTE FUNCTION app.archive_manifest_freeze();
CREATE TRIGGER archive_manifest_no_delete BEFORE DELETE ON app.archive_manifest
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

CREATE FUNCTION app.archive_candidates(p_today date DEFAULT app.dhaka_date(now()))
RETURNS TABLE (parent text, partition_name text, month date, retention_class text)
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
  SELECT pp.parent, n.nspname || '.' || c.relname, to_date(substring(c.relname FROM 'y(\d{4}m\d{2})$'), 'YYYY"m"MM'), pp.retention_class
    FROM app.partition_policy pp
    JOIN app.retention_policy rp ON rp.retention_class = pp.retention_class AND rp.hot_months IS NOT NULL
    JOIN pg_inherits i ON i.inhparent = to_regclass(pp.parent)
    JOIN pg_class c ON c.oid = i.inhrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE c.relname ~ '_y\d{4}m\d{2}$'
     AND to_date(substring(c.relname FROM 'y(\d{4}m\d{2})$'), 'YYYY"m"MM') < (date_trunc('month', p_today) - make_interval(months => rp.hot_months))::date
     AND NOT EXISTS (SELECT 1 FROM app.archive_manifest m WHERE m.partition_name = n.nspname || '.' || c.relname AND m.status IN ('dropped', 'restored'))   -- a restored partition stays until someone plans it by hand
   ORDER BY 3, 1
$$;

-- Rows in a default partition mean a month partition was missing at insert time (ensure_partitions re-routes them on
-- its next run); the worker alerts when this returns any row after its partition run. SECURITY DEFINER: no runtime
-- role has rights on single partitions; the function only counts.
CREATE FUNCTION app.default_partition_rows() RETURNS TABLE (parent text, row_count bigint)
LANGUAGE plpgsql STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  p record;
  n bigint;
  d regclass;
BEGIN
  -- apply_db_role_grants() gives every app function to api_rw and auth_rw; this definer function is for the worker
  -- and the jobs only (and a migrating superuser), so it checks the login itself.
  IF NOT (pg_has_role(session_user, 'worker_rw', 'MEMBER') OR pg_has_role(session_user, 'jobs_rw', 'MEMBER')) THEN
    RAISE EXCEPTION 'app.default_partition_rows is for the worker and the jobs only' USING ERRCODE = '42501';
  END IF;
  FOR p IN SELECT pp.parent FROM app.partition_policy pp ORDER BY pp.parent LOOP
    d := to_regclass(p.parent || '_default');
    IF d IS NOT NULL THEN
      EXECUTE format('SELECT count(*) FROM %s', d::text) INTO n;
      IF n > 0 THEN parent := p.parent; row_count := n; RETURN NEXT; END IF;
    END IF;
  END LOOP;
END $$;

-- The manifest gates partition drops: only the archive job writes it; the internet-facing API has no access.
-- The retention windows decide when partitions are dropped: the API neither reads nor writes them either.
UPDATE app.db_role_grant SET except_tables = except_tables || '{archive_manifest}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object IN ('*', '*/update') AND NOT ('archive_manifest' = ANY (except_tables));
UPDATE app.db_role_grant SET except_tables = except_tables || '{retention_policy}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object IN ('*', '*/update') AND NOT ('retention_policy' = ANY (except_tables));
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('jobs_rw', 'app', 'archive_manifest', 'SELECT, INSERT, UPDATE', 'the monthly archive job records exports, verification and drops');
SELECT app.apply_db_role_grants();
REVOKE ALL ON FUNCTION app.archive_candidates(date), app.default_partition_rows(), app.archive_manifest_freeze() FROM PUBLIC;

COMMENT ON COLUMN app.partition_policy.retention_class IS 'Retention class of the parent (app.retention_policy); decides when its month partitions are archived.';
COMMENT ON TABLE app.retention_policy IS 'One row per retention class: months a partition stays in the primary database and months its export is kept (docs/16 s13.1, D-371).
owner: db | capture: REFERENCE | retention: master | pii: none';
COMMENT ON COLUMN app.retention_policy.retention_class IS 'Class name: transaction, fix, telemetry, quarantine, audit or event_fact.';
COMMENT ON COLUMN app.retention_policy.hot_months IS 'Months a month partition stays in the primary database; null = never leaves it.';
COMMENT ON COLUMN app.retention_policy.keep_months IS 'Months the exported partition is kept in the archive; null = for ever.';
COMMENT ON COLUMN app.retention_policy.note IS 'Why the window is what it is.';
COMMENT ON COLUMN app.retention_policy.updated_at IS 'UTC instant of the last change.';
COMMENT ON TABLE app.archive_manifest IS 'One row per exported month partition: planned, exported, verified, dropped, restored; a partition is dropped only after its row is verified.
owner: db | capture: SERVER | retention: audit | pii: none';
COMMENT ON COLUMN app.archive_manifest.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.archive_manifest.parent IS 'Partitioned parent table (schema-qualified).';
COMMENT ON COLUMN app.archive_manifest.partition_name IS 'Schema-qualified month partition, e.g. app.memo_y2026m10.';
COMMENT ON COLUMN app.archive_manifest.month IS 'First day of the partition''s month.';
COMMENT ON COLUMN app.archive_manifest.row_count IS 'Rows exported.';
COMMENT ON COLUMN app.archive_manifest.sha256 IS 'SHA-256 of the exported file, lower-case hex.';
COMMENT ON COLUMN app.archive_manifest.blob_url IS 'Blob location of the export (no SAS token).';
COMMENT ON COLUMN app.archive_manifest.format IS 'Export format: parquet or sql_gz.';
COMMENT ON COLUMN app.archive_manifest.status IS 'planned > exported > verified > dropped > restored; never backwards.';
COMMENT ON COLUMN app.archive_manifest.created_at IS 'UTC instant the row was planned.';
COMMENT ON COLUMN app.archive_manifest.exported_at IS 'UTC instant the export finished.';
COMMENT ON COLUMN app.archive_manifest.verified_at IS 'UTC instant the export was read back and matched row count and hash.';
COMMENT ON COLUMN app.archive_manifest.dropped_at IS 'UTC instant the partition was dropped from the primary.';
