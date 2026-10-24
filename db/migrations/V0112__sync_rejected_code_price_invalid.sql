-- V0112 app.sync_rejected.code accepts price_invalid (lead request 2026-10-24, from the contract 1.8 batch:
-- D-BC-ZERO-PRICE-01 adds RecordOutcomeCode `price_invalid`, status rejected, not retryable, which IngestService
-- answers for a zero-priced sale line instead of schema_invalid).
-- Only price_invalid is added. The V0008 split stays: app.sync_rejected takes the codes the contract marks rejected
-- (plus the server-only retry_exhausted) and app.sync_quarantine takes the 15 it marks quarantined; this CHECK keeps a
-- quarantine code (a money record for the admin queue) from landing in sync_rejected by mistake.
-- Nothing is removed, so every existing row stays valid. Replaced NOT VALID here and validated in V0113 (separate
-- transaction, squawk rule; the V0091/V0092 pattern).

SET lock_timeout = '5s';

ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
  'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
  'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'memo_no_invalid',
  'edit_not_allowed', 'chain_too_deep', 'voided_by_admin', 'lines_exceed_max', 'qty_invalid', 'price_invalid',
  'attendance_duplicate', 'server_error', 'retry_exhausted', 'unknown_gift', 'insufficient_points',
  'gift_photo_exists')) NOT VALID;

COMMENT ON COLUMN app.sync_rejected.code IS 'Rejection code: a RecordOutcomeCode the contract marks rejected (price_invalid from V0112) or the server-only retry_exhausted; quarantined codes go to app.sync_quarantine.';
