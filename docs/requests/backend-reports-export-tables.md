# backend-reports request to the db lane: export log, export jobs and PII read budget

Needed by F-API-017 / F-API-053 / F-SYS-058 / F-SYS-064 (report registry, async exports, PII budgets). No table for them exists in V0001 to V0013.
Contract: `ExportJob`, `ExportLogEntry` (contract/openapi.yaml); docs/24 s12.3 ("every export is listed with its filters, row count and PII flag").

```sql
-- One row per export (print, xlsx, pdf), sync or async. Append-only except the job columns.
CREATE TABLE app.report_export (
  export_id     uuid PRIMARY KEY,
  report_key    text NOT NULL CHECK (report_key ~ '^[a-z][a-z0-9-]{1,40}$'),
  user_id       bigint NOT NULL REFERENCES app.app_user(id),
  format        text NOT NULL CHECK (format IN ('xlsx','pdf','print')),
  status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed')),
  filters       jsonb NOT NULL,                              -- the ReportQuery as run (scalars only; no scope ids from the client are trusted)
  scope_hash    text NOT NULL,                               -- hash of the caller's reach at request time
  row_count     integer CHECK (row_count >= 0),
  pii_included  boolean NOT NULL DEFAULT false,
  blob_path     text,                                        -- Blob container path of the finished file
  error         text CHECK (length(error) <= 300),
  claimed_by    text, claimed_at timestamptz,                -- worker lease, like app.dirty_key
  created_at    timestamptz NOT NULL DEFAULT now(),
  started_at    timestamptz, finished_at timestamptz,
  expires_at    timestamptz                                  -- download link and blob lifetime
);
CREATE INDEX ON app.report_export (user_id, created_at DESC);
CREATE INDEX ON app.report_export (created_at DESC);
CREATE INDEX report_export_queue ON app.report_export (created_at) WHERE status = 'queued';

-- Rows of personal data a user has read this hour (cfg.ops PII budget, F-SYS-058). One row per (user, hour).
CREATE TABLE app.pii_read_budget (
  user_id     bigint NOT NULL REFERENCES app.app_user(id),
  hour_start  timestamptz NOT NULL,
  rows_read   integer NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
  PRIMARY KEY (user_id, hour_start)
);
```
Also the config key `cfg.ops.pii_rows_per_hour` (int, default 2000, 100..100000, role-scoped) if it is not in the registry yet.
Until this lands the backend-reports lane builds the registry, json, print and inline xlsx paths behind an `ExportLog` interface and tests the rest with an in-memory log.
