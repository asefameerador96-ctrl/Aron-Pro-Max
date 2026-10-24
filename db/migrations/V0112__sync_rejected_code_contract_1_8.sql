-- V0112 app.sync_rejected.code accepts every RecordOutcomeCode of contract 1.8 (lead request 2026-10-24, from the
-- contract 1.8 batch: D-BC-ZERO-PRICE-01 adds `price_invalid`, rejected and not retryable, which IngestService answers
-- for a zero-priced sale line instead of schema_invalid). The V0008 CHECK listed the codes of that time only; the
-- contract has since added 15 more besides price_invalid (arithmetic_mismatch, memo_no_duplicate, content_duplicate,
-- checkout_too_early, payload_conflict, business_date_out_of_window, scope_out_of_reach, no_assignment_on_date,
-- device_integrity_failed, device_not_enrolled, user_disabled, device_revoked, app_version_blocked, after_month_close,
-- programme_inactive),
-- so any of them reaching app.sync_rejected would fail its insert (23514). The CHECK is widened to the whole contract
-- 1.8 enum plus the server-only retry_exhausted; nothing is removed, so every existing row stays valid. It is
-- replaced NOT VALID here and validated in V0113 (separate transaction, squawk rule; the V0091/V0092 pattern).

SET lock_timeout = '5s';

ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
  'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
  'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'arithmetic_mismatch',
  'memo_no_invalid', 'memo_no_duplicate', 'edit_not_allowed', 'chain_too_deep', 'voided_by_admin',
  'content_duplicate', 'lines_exceed_max', 'qty_invalid', 'price_invalid', 'attendance_duplicate',
  'checkout_too_early', 'server_error', 'payload_conflict', 'business_date_out_of_window', 'scope_out_of_reach',
  'no_assignment_on_date', 'device_integrity_failed', 'device_not_enrolled', 'user_disabled', 'device_revoked',
  'app_version_blocked', 'after_month_close', 'unknown_gift', 'insufficient_points', 'gift_photo_exists',
  'programme_inactive', 'retry_exhausted')) NOT VALID;

COMMENT ON COLUMN app.sync_rejected.code IS 'Rejection code: a contract RecordOutcomeCode (1.8, incl. price_invalid, V0112) or the server-only retry_exhausted.';
