-- V0108 dw.agg_daily_route.checked_in_at (backend-reports request docs/requests/db-backend-reports-checked-in-at.md,
-- dashboard brief s3.4 "Successful Login = check-in and download"). Additive and nullable: no rewrite, and the
-- Aggregator probes the column, so either side of this migration keeps working.
-- Backfill of the last 7 business days with backend-reports' rule, written here in SQL rather than through dirty keys
-- (the worker could rebuild those keys before the Aggregator that fills the column is deployed, and lose the backfill):
-- the earliest captured_at of a non-voided check_in on the business date whose route_id is the route, or, when the
-- event names no route (the SR app sends none before it has loaded a route), whose user held a primary or cover
-- assignment of the route on that date. Later days are filled by the Aggregator's route-day rebuild.

SET lock_timeout = '5s';

ALTER TABLE dw.agg_daily_route ADD COLUMN checked_in_at timestamptz;

UPDATE dw.agg_daily_route a
   SET checked_in_at = ci.first_at
  FROM (
    SELECT r.business_date, r.route_id,
           (SELECT min(e.captured_at) FROM app.attendance_event e
             WHERE e.kind = 'check_in' AND e.voided_at IS NULL AND e.business_date = r.business_date
               AND (e.route_id = r.route_id
                    OR (e.route_id IS NULL AND e.user_id IN (
                          SELECT ra.user_id FROM app.route_assignment ra
                           WHERE ra.route_id = r.route_id AND ra.valid_from <= r.business_date
                             AND (ra.valid_to IS NULL OR ra.valid_to > r.business_date))))) AS first_at
      FROM dw.agg_daily_route r
     WHERE r.business_date >= app.dhaka_date(now()) - 7
  ) ci
 WHERE a.business_date = ci.business_date AND a.route_id = ci.route_id
   AND ci.first_at IS NOT NULL AND a.checked_in_at IS NULL;

COMMENT ON COLUMN dw.agg_daily_route.checked_in_at IS 'UTC time of the first non-voided attendance check-in of the route''s SR on the business date (the event''s route, or, when the event names none, a user assigned to the route that day); null when none (V0108).';
