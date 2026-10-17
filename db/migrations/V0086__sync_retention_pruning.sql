-- V0086 (DB-01 / HD-08, audit docs/audit/production-readiness-2026-10-15.md): the sync tables the archive job never
-- takes are pruned by the worker's RetentionJob in the night window, in 1,000-row chunks: ingest_registry rows past
-- cfg.retention.ingest_registry_days (voided rows kept, D-22), sync_batch responses past their expiry and batches past
-- the registry window, expired device nonces, and bundle_snapshot bodies of past dates and rows of dates older than
-- 7 days (V0032). This migration gives the steps their grants (the worker login, and jobs_rw for the CI proof) and the
-- partial index the response expiry walks (only rows that still hold a response, so a chunk never re-reads the ones
-- already dropped).

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX sync_batch_response_expiry ON app.sync_batch (expires_at) WHERE response_gz IS NOT NULL;

UPDATE app.db_role_grant SET privileges = 'UPDATE, DELETE', note = 'retention: responses dropped past expiry, batches past the registry window'
  WHERE role = 'worker_rw' AND schema_name = 'app' AND object = 'sync_batch';
UPDATE app.db_role_grant SET privileges = 'UPDATE, DELETE', note = 'retention: bodies of past dates dropped, dates older than 7 days deleted'
  WHERE role = 'worker_rw' AND schema_name = 'app' AND object = 'bundle_snapshot';
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('jobs_rw', 'app', 'ingest_registry', 'SELECT, DELETE', 'retention after cfg.retention.ingest_registry_days (voided rows kept)'),
  ('jobs_rw', 'app', 'sync_batch',      'SELECT, UPDATE, DELETE', 'retention: responses dropped past expiry, batches past the registry window'),
  ('jobs_rw', 'app', 'device_nonce',    'SELECT, DELETE', 'retention: expired nonces'),
  ('jobs_rw', 'app', 'bundle_snapshot', 'SELECT, UPDATE, DELETE', 'retention: bodies of past dates dropped, dates older than 7 days deleted'),
  ('jobs_rw', 'app', 'cfg_value',       'SELECT', 'retention reads cfg.retention.* (global rows)'),
  ('jobs_rw', 'app', 'cfg_key',         'SELECT', 'retention reads the registry defaults of cfg.retention.*');
SELECT app.apply_db_role_grants();
