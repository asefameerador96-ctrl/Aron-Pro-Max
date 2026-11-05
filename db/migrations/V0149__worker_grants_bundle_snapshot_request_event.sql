-- V0149 the worker may INSERT into app.bundle_snapshot and app.outlet_request_event (D-DB-WORKER-GRANTS-01).
-- Found on dev after train 78 (lead, server log): the worker (app_jobs -> jobs_rw -> worker_rw) logged "permission denied for
-- table bundle_snapshot" (x8, 16:41-16:45 UTC) and "permission denied for table outlet_request_event" (x1, 16:51).
-- Not caused by migrations: app.apply_db_role_grants() re-grants exactly the rows of app.db_role_grant, and the map never
-- held these two rights for the worker (identical privileges at V0143 and V0148, checked on fresh databases). The code
-- that needs them has run in the worker since 2026-10-07 / 2026-10-08, so these writes failed silently whenever the jobs ran:
--   * app.bundle_snapshot INSERT: backend/sync BundleService.kt:325 (snapshot sequence and ETag), called by the worker's
--     BundlePreGenerator (D+1 bundles 22:00, dirtied users 03:30). Before: worker_rw = SELECT, DELETE (retention, V0032).
--   * app.outlet_request_event INSERT: backend/masterdata OutletRequestLapseJob.kt:33-36 ('lapsed' event after the
--     UPDATE of app.outlet_change_request, which is granted). Before: worker_rw = SELECT.
-- Audit of every other write statement of the worker's jobs (AggregationWorker, ExportJobService, RiskSignalJob,
-- RouteDayPlanningJob, BundlePreGenerator/BundleService, GeoRecheckSweepJob, RetentionJob, OutletRequestLapseJob, InboxPruneJob,
-- AutoFinalSubmitJob/DayApi.closeLocked): every other INSERT/UPDATE/DELETE target already has its map row (client_error
-- DELETE, dirty_key, archive_manifest [jobs_rw], event_consumer, route_day, supervisor_day, final_submit INSERT, domain_event
-- INSERT, risk_signal, visit UPDATE, report_export UPDATE, outlet_change_request UPDATE, user_notification INSERT/DELETE).
-- Not granted on purpose: app.audit_log and app.security_event (the auto Final Submit job writes no audit row, its
-- trace is the final_submit via='job' row and the domain event). Sequences are covered by the existing USAGE grant.
-- app.bundle_snapshot already has a worker_rw map row (UNIQUE role/schema/object), so it is updated, not inserted.
SET lock_timeout = '5s';

UPDATE app.db_role_grant
   SET privileges = 'INSERT, DELETE',
       note = 'retention deletes dates older than 7 days (V0032); the bundle pre-generation job inserts snapshot rows (V0149)'
 WHERE role = 'worker_rw' AND schema_name = 'app' AND object = 'bundle_snapshot' AND privileges = 'DELETE';

INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, note) VALUES
  ('worker_rw', 'app', 'outlet_request_event', 'INSERT', '{}', 'the outlet-request lapse job appends the lapsed event (V0149)');

SELECT app.apply_db_role_grants();
