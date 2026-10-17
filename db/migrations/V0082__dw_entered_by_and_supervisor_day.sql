-- V0082 (answers docs/requests/backend-reports-db-entered-by.md on lane/backend-reports):
-- 1. dw.fact_memo and dw.fact_visit gain entered_by_user_id (the web user who keyed a manual row, F-API-038), copied by
--    the projector from app.memo / app.visit. No FK (dw facts carry none). The CHECK is one-way on purpose: a device row
--    never names a web user. The reverse (manual implies a user) is not enforced here, because the projector today
--    copies entry_source but not yet entered_by_user_id; a two-way CHECK would refuse every manual projection until
--    backend-reports ships the copy. app.memo/app.visit keep the two-way rule (V0077). Added NOT VALID, validated in V0083.
-- 2. dw.fact_supervisor_day (F-AMO-039, docs/16 s2): one row per AMO or TSO and business date, projected from
--    app.supervisor_day, kept apart from route-day Login % and Submit % (D-27). Not partitioned: one row per
--    supervisor per day (fact_attendance size). worker_rw writes it through its dw '*' row; web_ro and bi_reader read
--    only dw.v_* (V0020), so the table is not exposed until a stable view is asked for.
-- 3. app.dirty_key.kind gains supervisor_day_agg, so the worker can queue a supervisor-day rebuild (the callers of
--    app.mark_dirty, not a trigger, enqueue keys). The CHECK is replaced NOT VALID and validated in V0083.

SET lock_timeout = '5s';

ALTER TABLE dw.fact_memo ADD COLUMN entered_by_user_id bigint;
ALTER TABLE dw.fact_memo ADD CONSTRAINT fact_memo_entered_by_manual_only
  CHECK (entered_by_user_id IS NULL OR entry_source = 'manual') NOT VALID;
ALTER TABLE dw.fact_visit ADD COLUMN entered_by_user_id bigint;
ALTER TABLE dw.fact_visit ADD CONSTRAINT fact_visit_entered_by_manual_only
  CHECK (entered_by_user_id IS NULL OR entry_source = 'manual') NOT VALID;

CREATE TABLE dw.fact_supervisor_day (
  user_id             bigint NOT NULL,
  business_date       date NOT NULL,
  role                text NOT NULL,
  zone_id             bigint,
  checked_in_at       timestamptz,
  checked_out_at      timestamptz,
  sales_submitted_at  timestamptz,
  submit_cycle        int NOT NULL DEFAULT 1 CHECK (submit_cycle BETWEEN 1 AND 50),
  submit_voided       boolean NOT NULL DEFAULT false,
  last_event_id       bigint,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, business_date)
);
CREATE INDEX fact_supervisor_day_date_zone ON dw.fact_supervisor_day (business_date, zone_id);

ALTER TABLE app.dirty_key DROP CONSTRAINT dirty_key_kind_check;
ALTER TABLE app.dirty_key ADD CONSTRAINT dirty_key_kind_check
  CHECK (kind IN ('bundle_user', 'route_day_agg', 'zone_day_agg', 'outlet_day_agg', 'device_day_agg', 'route_snapshot',
                  'risk_user_day', 'supervisor_day_agg')) NOT VALID;

COMMENT ON COLUMN dw.fact_memo.entered_by_user_id IS 'Web user who keyed the memo (entry_source manual), copied from app.memo; null for device rows.';
COMMENT ON COLUMN dw.fact_visit.entered_by_user_id IS 'Web user who keyed the visit (entry_source manual), copied from app.visit; null for device rows.';
COMMENT ON TABLE dw.fact_supervisor_day IS 'One row per AMO or TSO and business date with the day''s check-in, check-out and Sales Submit, filled by the worker from app.supervisor_day (F-AMO-039).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.fact_supervisor_day.user_id IS 'Supervisor (app.app_user).';
COMMENT ON COLUMN dw.fact_supervisor_day.business_date IS 'Asia/Dhaka business date of the supervisor-day.';
COMMENT ON COLUMN dw.fact_supervisor_day.role IS 'Role code of the user at projection time (app.role_def, upper case: AMO or TSO for a supervisor-day); no CHECK, so a rebuild after a re-role still succeeds.';
COMMENT ON COLUMN dw.fact_supervisor_day.zone_id IS 'Home zone of the supervisor that day; null when the user has none (a TSO over several zones).';
COMMENT ON COLUMN dw.fact_supervisor_day.checked_in_at IS 'UTC time of the day''s check-in; null when the supervisor did not check in.';
COMMENT ON COLUMN dw.fact_supervisor_day.checked_out_at IS 'UTC time of the day''s check-out.';
COMMENT ON COLUMN dw.fact_supervisor_day.sales_submitted_at IS 'UTC time the supervisor''s Sales Submit was accepted.';
COMMENT ON COLUMN dw.fact_supervisor_day.submit_cycle IS 'Submit cycle of the day (a voided submit opens the next cycle).';
COMMENT ON COLUMN dw.fact_supervisor_day.submit_voided IS 'True when the day''s submit was voided.';
COMMENT ON COLUMN dw.fact_supervisor_day.last_event_id IS 'Domain event id the projection last applied (watermark).';
COMMENT ON COLUMN dw.fact_supervisor_day.updated_at IS 'UTC time the row was last rebuilt.';
COMMENT ON COLUMN app.dirty_key.kind IS 'Rebuild kind: bundle_user, route_day_agg, zone_day_agg, outlet_day_agg, device_day_agg, route_snapshot, risk_user_day or supervisor_day_agg.';

SELECT app.apply_db_role_grants();
