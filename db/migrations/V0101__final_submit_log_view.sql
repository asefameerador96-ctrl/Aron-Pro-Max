-- V0101 final submit log (owner directive 2026-10-22, docs/34 s2; dashboard brief s3.3 and Brief 07: a service zone
-- counts as final-submitted once its TSO has done the Final Submit for the date, and second submits are ignored).
-- No new table: app.final_submit (V0007) already holds one row per submit with zone and business date, at most one
-- live (not reopened) row per zone-day (final_submit_once). A reopen and a second submit add a row, so "first wins" is
-- a read rule: dw.v_final_submit_log gives per zone and business date the FIRST submit (time, submitter, channel), the
-- number of submits and whether the day is currently reopened.

SET lock_timeout = '5s';

CREATE VIEW dw.v_final_submit_log AS
SELECT DISTINCT ON (f.zone_id, f.business_date)
       f.zone_id, f.business_date, f.submitted_at AS first_submitted_at, f.submitted_by AS first_submitted_by,
       f.via AS first_via,
       count(*) OVER w AS submits,
       max(f.submitted_at) OVER w AS last_submitted_at,
       NOT bool_or(f.reopened_at IS NULL) OVER w AS reopened_now
  FROM app.final_submit f
WINDOW w AS (PARTITION BY f.zone_id, f.business_date)
 ORDER BY f.zone_id, f.business_date, f.submitted_at, f.id;

COMMENT ON VIEW dw.v_final_submit_log IS 'Stable view: per zone and business date the first Final Submit (second submits ignored, Brief 07), the number of submits and the reopen state.
owner: worker | capture: SERVER | retention: audit | pii: none';
COMMENT ON COLUMN dw.v_final_submit_log.zone_id IS 'Zone (app.zone).';
COMMENT ON COLUMN dw.v_final_submit_log.business_date IS 'Asia/Dhaka business date that was final-submitted.';
COMMENT ON COLUMN dw.v_final_submit_log.first_submitted_at IS 'UTC time of the first Final Submit of the zone-day.';
COMMENT ON COLUMN dw.v_final_submit_log.first_submitted_by IS 'User who made the first Final Submit (app.app_user id).';
COMMENT ON COLUMN dw.v_final_submit_log.first_via IS 'Channel of the first Final Submit: app or web.';
COMMENT ON COLUMN dw.v_final_submit_log.submits IS 'Number of Final Submits of the zone-day (a reopen and a resubmit add one).';
COMMENT ON COLUMN dw.v_final_submit_log.last_submitted_at IS 'UTC time of the latest Final Submit of the zone-day.';
COMMENT ON COLUMN dw.v_final_submit_log.reopened_now IS 'True when every submit of the zone-day is reopened (no live Final Submit).';

SELECT app.apply_db_role_grants();
