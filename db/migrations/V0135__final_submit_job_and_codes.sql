-- V0135 midnight auto Final Submit and the day lock codes (contract docs/requests/tso-manual-build-2026-10.md s2;
-- D-TSO-MAN-18..21, D-TSO-MAN-30, D-TSO-MAN-31; gap items FS-11, FS-13, TK-05; lane F2).
--
-- 1. app.final_submit: via gains 'job' (the AutoFinalSubmitJob at 00:00 Asia/Dhaka writes a real row through
--    DayService's locked path, so every final-submit figure reads live app.final_submit rows and the dw-only
--    auto_submitted stamp goes, D-TSO-MAN-30). A job row has no submitting user: submitted_by becomes nullable, and the
--    new CHECK final_submit_job_actor ties the two together ((via = 'job') = (submitted_by IS NULL)), so an app or web
--    submit still always names its user. Dropping NOT NULL is marked for squawk (ban-drop-not-null protects readers that
--    assume the value): the only readers are DayApi (B3 changes it in this build) and the dw.v_final_submit_log view,
--    whose first_submitted_by is now null for a job submit.
-- 2. app.sync_rejected.code gains the three codes the contract 1.10 marks rejected, not retryable:
--    day_final_submitted (a memo, edit, void, return or collection captured at or after the zone-day's Final Submit),
--    ticket_state_invalid (a ticket comment or close review the ticket's state no longer allows) and issue_type_unknown
--    (an issue type that is not active on the business date). app.sync_quarantine.code gains after_final_submit (a row
--    captured before the lock that arrived after it: stored, held out of the figures, counted only when an admin
--    accepts it). Every existing code is kept, so every existing row stays valid.
-- 3. The comments of app.final_submit.late_rows and app.route_day.late_rows_after_final said the wrong thing (V0016:861
--    "still outstanding when the zone-day was submitted"): the count is of rows captured before the submit that arrived
--    after it. The dw columns that carry the same count are reworded too.
-- The three CHECKs are replaced or added NOT VALID here and validated in V0136 (separate transaction, squawk rule).
-- Grants: the worker runs the job; it may now insert final submits and their audit rows.

SET lock_timeout = '5s';

-- ---------- 1. final submit by the midnight job ----------
ALTER TABLE app.final_submit DROP CONSTRAINT final_submit_via_check;
ALTER TABLE app.final_submit ADD CONSTRAINT final_submit_via_check CHECK (via IN ('app','web','job')) NOT VALID;
-- squawk-ignore ban-drop-not-null
ALTER TABLE app.final_submit ALTER COLUMN submitted_by DROP NOT NULL;
ALTER TABLE app.final_submit ADD CONSTRAINT final_submit_job_actor CHECK ((via = 'job') = (submitted_by IS NULL)) NOT VALID;

-- ---------- 2. sync outcome codes ----------
ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
  'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
  'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'memo_no_invalid',
  'edit_not_allowed', 'chain_too_deep', 'voided_by_admin', 'lines_exceed_max', 'qty_invalid', 'price_invalid',
  'attendance_duplicate', 'server_error', 'retry_exhausted', 'unknown_gift', 'insufficient_points',
  'gift_photo_exists', 'day_final_submitted', 'ticket_state_invalid', 'issue_type_unknown')) NOT VALID;

ALTER TABLE app.sync_quarantine DROP CONSTRAINT sync_quarantine_code_check;
ALTER TABLE app.sync_quarantine ADD CONSTRAINT sync_quarantine_code_check CHECK (code IN (
  'arithmetic_mismatch', 'memo_no_duplicate', 'content_duplicate', 'checkout_too_early', 'payload_conflict',
  'business_date_out_of_window', 'scope_out_of_reach', 'no_assignment_on_date', 'device_integrity_failed',
  'device_not_enrolled', 'user_disabled', 'device_revoked', 'app_version_blocked', 'after_month_close',
  'programme_inactive', 'after_final_submit')) NOT VALID;

-- ---------- grants ----------
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note) VALUES
  ('worker_rw', 'app', 'final_submit', 'INSERT', 'V0135: the midnight AutoFinalSubmitJob writes via=job submits (D-TSO-MAN-30)'),
  ('worker_rw', 'app', 'audit_log', 'INSERT', 'V0135: audit rows of worker jobs (the midnight auto Final Submit, actor system)');

-- ---------- 3. comments ----------
COMMENT ON COLUMN app.final_submit.via IS 'Channel the Final Submit came through: app (TSO phone), web (portal) or job (the midnight AutoFinalSubmitJob, V0135).';
COMMENT ON COLUMN app.final_submit.submitted_by IS 'Id of the user who made the Final Submit; null exactly when the midnight job made it (via job, CHECK final_submit_job_actor).';
COMMENT ON COLUMN app.final_submit.late_rows IS 'Rows captured before this Final Submit that arrived after it (late rows of the zone-day).';
COMMENT ON COLUMN app.route_day.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.agg_daily_route.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_daily_route.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_route_day.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_final_submit_log.first_submitted_by IS 'User who made the first Final Submit (app.app_user id); null when the midnight job made it.';
COMMENT ON COLUMN app.sync_rejected.code IS 'Rejection code: a RecordOutcomeCode the contract marks rejected (price_invalid from V0112; day_final_submitted, ticket_state_invalid and issue_type_unknown from V0135) or the server-only retry_exhausted; quarantined codes go to app.sync_quarantine.';
COMMENT ON COLUMN app.sync_quarantine.code IS 'Quarantine code: a RecordOutcomeCode the contract marks quarantined (after_final_submit from V0135: captured before the zone-day''s Final Submit, arrived after it).';

SELECT app.apply_db_role_grants();
