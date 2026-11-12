-- Loyalty train 3, push B (lane portal-loyalty-api; docs/requests/contract-1.15-loyalty-field.md): the Astha value target and the upload batch ledger.
-- 1. app.astha_target.value_target_mtk: the money target of a (route, outlet, brand, month) row in integer milli-taka, never negative, 0 until uploaded (owner Q5).
-- 2. app.astha_target_batch: append-only ledger of PUT /v1/programmes/astha/targets batches; the batch UUID makes a retried upload idempotent and a
--    reused UUID with other rows is told apart by the fingerprint.

SET lock_timeout = '5s';

ALTER TABLE app.astha_target ADD COLUMN value_target_mtk bigint NOT NULL DEFAULT 0 CHECK (value_target_mtk >= 0);

CREATE TABLE app.astha_target_batch (
  batch_uuid          uuid PRIMARY KEY,
  quarter             text NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
  request_fingerprint text NOT NULL,
  row_count           int NOT NULL CHECK (row_count >= 1),
  inserted            int NOT NULL CHECK (inserted >= 0),
  updated             int NOT NULL CHECK (updated >= 0),
  unchanged           int NOT NULL CHECK (unchanged >= 0),
  reason              text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  actor               bigint NOT NULL REFERENCES app.app_user(id),
  at                  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER astha_target_batch_append_only BEFORE UPDATE OR DELETE ON app.astha_target_batch FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
CREATE TRIGGER astha_target_batch_no_truncate BEFORE TRUNCATE ON app.astha_target_batch FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.astha_target_batch FROM PUBLIC;
UPDATE app.db_role_grant SET except_tables = except_tables || '{astha_target_batch}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update'
   AND NOT ('astha_target_batch' = ANY (except_tables));

COMMENT ON COLUMN app.astha_target.value_target_mtk IS 'Value target in integer milli-taka (money is never a float), at least 0, 0 until the portal upload sets it.';
COMMENT ON TABLE app.astha_target_batch IS 'Append-only ledger of Astha target upload batches (PUT /v1/programmes/astha/targets); the batch UUID makes a retried batch idempotent (the first counts are answered again).
owner: backend:masterdata | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.astha_target_batch.batch_uuid IS 'Client-generated UUID of the batch.';
COMMENT ON COLUMN app.astha_target_batch.quarter IS 'Quarter the batch targets, YYYY-Qn.';
COMMENT ON COLUMN app.astha_target_batch.request_fingerprint IS 'SHA-256 (hex) of the normalised batch; a reused batch_uuid with different rows is told apart (409).';
COMMENT ON COLUMN app.astha_target_batch.row_count IS 'Rows in the batch.';
COMMENT ON COLUMN app.astha_target_batch.inserted IS 'Target rows the batch created.';
COMMENT ON COLUMN app.astha_target_batch.updated IS 'Target rows the batch changed.';
COMMENT ON COLUMN app.astha_target_batch.unchanged IS 'Rows that already matched.';
COMMENT ON COLUMN app.astha_target_batch.reason IS 'Why the batch was made, 10 to 500 characters, mandatory.';
COMMENT ON COLUMN app.astha_target_batch.actor IS 'User (app.app_user) who made the batch.';
COMMENT ON COLUMN app.astha_target_batch.at IS 'UTC instant of the batch.';

SELECT app.apply_db_role_grants();
