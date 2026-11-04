-- V0137 app.visit_plan.route_id (contract docs/requests/tso-manual-build-2026-10.md s2; D-TSO-MAN-35; gap item SP-03;
-- lane F2). A TSO's Set Plan picks a route first and plans that route's outlets for a date; the phone keys the plan by
-- (planner, date, route), but the server had no route column and derived it as the smallest current route of the plan's
-- outlets, so a later re-routing of an outlet moved or split the plan. The record writer now stores the envelope's
-- route_id; rows before V0137 stay NULL and readers fall back to coalesce(route_id, min outlet route).
-- The column is set at insert only (the write-once guard of V0007/V0116 keeps it). The foreign key is added NOT VALID
-- (every existing row is NULL; no scan under lock) and validated in V0138 (separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.visit_plan ADD COLUMN route_id bigint;
ALTER TABLE app.visit_plan ADD CONSTRAINT visit_plan_route_id_fkey FOREIGN KEY (route_id) REFERENCES app.route(id) NOT VALID;

COMMENT ON COLUMN app.visit_plan.route_id IS 'Route chosen in Set Plan; NULL on rows before V0137 (readers then use the smallest route of the plan''s outlets).';

SELECT app.apply_db_role_grants();
