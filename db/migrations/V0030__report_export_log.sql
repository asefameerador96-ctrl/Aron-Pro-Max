-- V0030 export log, export jobs and PII read budget (docs/requests/backend-reports-export-tables.md; F-API-017, F-API-053,
-- F-SYS-058, F-SYS-064; docs/24 s12.3 "every export is listed with its filters, row count and PII flag"; AUD-DA-05 export
-- log). The request's shape, plus: the export row is append-only except its job columns (guard trigger) and its status
-- moves only forward; the worker that runs async exports may update it.
-- The budgets are the docs/19 keys (lead ruling 2026-10-07): cfg.pii.list_rows_per_hour (2000, the TSO 5000 by a role
-- value, docs/21 s4.5 D-72; 429 beyond) and cfg.pii.export_rows_per_day (5000); both restrictive downwards.

SET lock_timeout = '5s';

CREATE TABLE app.report_export (
  export_id     uuid PRIMARY KEY,
  report_key    text NOT NULL CHECK (report_key ~ '^[a-z][a-z0-9-]{1,40}$'),
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  format        text NOT NULL CHECK (format IN ('xlsx','pdf','print')),
  status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed')),
  filters       jsonb NOT NULL CHECK (jsonb_typeof(filters) = 'object'),
  scope_hash    text NOT NULL CHECK (length(scope_hash) BETWEEN 1 AND 128),
  row_count     integer CHECK (row_count >= 0),
  pii_included  boolean NOT NULL DEFAULT false,
  blob_path     text CHECK (length(blob_path) <= 300),
  error         text CHECK (length(error) <= 300),
  claimed_by    text CHECK (length(claimed_by) <= 100),
  claimed_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  started_at    timestamptz,
  finished_at   timestamptz,
  expires_at    timestamptz,
  CHECK (status <> 'done' OR (row_count IS NOT NULL AND finished_at IS NOT NULL))
);
CREATE INDEX report_export_user ON app.report_export (user_id, created_at DESC);
CREATE INDEX report_export_created ON app.report_export (created_at DESC);
CREATE INDEX report_export_queue ON app.report_export (created_at) WHERE status = 'queued';
-- Who, what, which filters and when never change; only the job columns move.
CREATE TRIGGER report_export_immutable BEFORE UPDATE OR DELETE ON app.report_export
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('status', 'row_count', 'pii_included', 'blob_path', 'error',
    'claimed_by', 'claimed_at', 'started_at', 'finished_at', 'expires_at');
-- A finished export (done or failed) is an audit record: nothing changes again except expires_at (link lifetime).
CREATE FUNCTION app.report_export_frozen() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status IN ('done', 'failed') AND (to_jsonb(NEW) - 'expires_at') IS DISTINCT FROM (to_jsonb(OLD) - 'expires_at') THEN
    RAISE EXCEPTION 'app.report_export: a % export is history and does not change', OLD.status USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.report_export_frozen() IS 'Refuses any change to a finished export except its expiry.';
CREATE TRIGGER report_export_frozen BEFORE UPDATE ON app.report_export FOR EACH ROW EXECUTE FUNCTION app.report_export_frozen();
CREATE TRIGGER report_export_status_flow BEFORE UPDATE OF status ON app.report_export
  FOR EACH ROW EXECUTE FUNCTION app.guard_transition('status',
    'queued>running', 'queued>failed', 'running>done', 'running>failed', 'running>queued');

CREATE TABLE app.pii_read_budget (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  hour_start  timestamptz NOT NULL CHECK (hour_start = date_trunc('hour', hour_start)),
  rows_read   integer NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
  PRIMARY KEY (user_id, hour_start)
);

-- ---------- budget keys (docs/19 s3.2.7, docs/21 s4.5) ----------
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.pii.list_rows_per_hour', 'pii', 'S', 'int', '2000'::jsonb, '{"min": 100, "max": 50000}'::jsonb, NULL, ARRAY['global', 'role']::text[], 2, NULL, 'S', 'server', false, false, 'down', 'cfg.edit.security',
   'Rows with unmasked personal columns a user may read per clock hour across list endpoints; 429 beyond (docs/21 s4.5).'),
  ('cfg.pii.export_rows_per_day', 'pii', 'S', 'int', '5000'::jsonb, '{"min": 100, "max": 100000}'::jsonb, NULL, ARRAY['global', 'role']::text[], 2, NULL, 'S', 'server', false, false, 'down', 'cfg.edit.security',
   'Rows a user may export per day in exports that include personal columns (docs/21 s4.5).');

-- The TSO's hourly budget is 5000 (about twice a 2,500-outlet territory, D-72): a role value in its own config version.
INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
SELECT (SELECT coalesce(max(config_version), 0) + 1 FROM app.cfg_version), 'change', id,
       'V0030: TSO hourly PII list budget 5000 (docs/21 s4.5, D-72)', 2
  FROM app.app_user WHERE username = 'aron.system';
INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason)
SELECT 'cfg.pii.list_rows_per_hour', 'role', r.ordinal, '5000'::jsonb, '2026-01-01T00:00:00Z',
       (SELECT max(config_version) FROM app.cfg_version), u.id, 'docs/21 s4.5 (D-72): TSO budget about twice a territory'
  FROM app.role_def r, app.app_user u WHERE r.role = 'TSO' AND u.username = 'aron.system';

COMMENT ON TABLE app.report_export IS 'One row is a report export (xlsx, pdf or print), synchronous or a queued job: who ran which report with which filters, how many rows and whether personal data was included.
owner: backend:analytics | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.report_export.export_id IS 'Client- or server-generated UUID of the export; the API is idempotent by it.';
COMMENT ON COLUMN app.report_export.report_key IS 'Key of the report in the report registry.';
COMMENT ON COLUMN app.report_export.user_id IS 'User who requested the export, from the token.';
COMMENT ON COLUMN app.report_export.format IS 'Output format; allowed values are listed under constraints.';
COMMENT ON COLUMN app.report_export.status IS 'Job state; queued, running, done or failed; moves only forward (a lapsed lease may re-queue).';
COMMENT ON COLUMN app.report_export.filters IS 'The report query as run (scalar filters only; scope comes from the token, never from the client).';
COMMENT ON COLUMN app.report_export.scope_hash IS 'Hash of the caller''s reach at request time, so a later change of scope is visible.';
COMMENT ON COLUMN app.report_export.row_count IS 'Number of data rows in the export; set when done.';
COMMENT ON COLUMN app.report_export.pii_included IS 'True when the export contains personal columns (unmasked).';
COMMENT ON COLUMN app.report_export.blob_path IS 'Path of the finished file in the export Blob container.';
COMMENT ON COLUMN app.report_export.error IS 'Short error text of a failed export (no personal data).';
COMMENT ON COLUMN app.report_export.claimed_by IS 'Worker instance holding the job lease.';
COMMENT ON COLUMN app.report_export.claimed_at IS 'UTC time the lease was taken.';
COMMENT ON COLUMN app.report_export.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.report_export.started_at IS 'UTC time the export started running.';
COMMENT ON COLUMN app.report_export.finished_at IS 'UTC time the export finished (done or failed).';
COMMENT ON COLUMN app.report_export.expires_at IS 'UTC time the download link and the file expire.';

COMMENT ON TABLE app.pii_read_budget IS 'One row is the number of personal-data rows a user has read in one clock hour (hourly PII read budget).
owner: backend:analytics | capture: SERVER | retention: ops | pii: none';
COMMENT ON COLUMN app.pii_read_budget.user_id IS 'User whose reads are counted.';
COMMENT ON COLUMN app.pii_read_budget.hour_start IS 'Start of the UTC clock hour counted (truncated to the hour).';
COMMENT ON COLUMN app.pii_read_budget.rows_read IS 'Rows with unmasked personal columns read in that hour.';

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('worker_rw', 'app', 'report_export', 'UPDATE', 'async export jobs: lease, run, finish (guard trigger limits the columns)'),
  ('worker_rw', 'app', 'pii_read_budget', 'DELETE', 'retention: hours older than a day');

SELECT app.apply_db_role_grants();
