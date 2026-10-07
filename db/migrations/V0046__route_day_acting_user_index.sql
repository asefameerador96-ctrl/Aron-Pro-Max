-- V0046 index for the acting user on app.route_day (query-plan review, docs/status/db.md). IngestService.dayStates reads
-- "WHERE (assigned_user_id = :u OR acting_user_id = :u) AND business_date = ANY(:d)" on every sync; only the assigned
-- user had an index, so the OR fell back to scanning the date range. With this partial index the planner combines both
-- (BitmapOr). Partial: acting_user_id is set only on covered route-days, so the index stays small.

SET lock_timeout = '5s';

-- route_day holds about one row per route and business date; pilot size now, the same plain build as V0036.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX route_day_acting_user_date ON app.route_day (acting_user_id, business_date) WHERE acting_user_id IS NOT NULL;
