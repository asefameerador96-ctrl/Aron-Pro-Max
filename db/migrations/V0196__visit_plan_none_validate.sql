-- V0196 validates what V0195 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies the checks:
-- the new rules only add the value 'none' and the mask 0, and the replaced function accepts every pair the old one accepted.
SET lock_timeout = '5s';
ALTER TABLE app.route VALIDATE CONSTRAINT route_visit_days_mask_check;
ALTER TABLE app.route VALIDATE CONSTRAINT route_visit_kind_check;
ALTER TABLE app.route_planned VALIDATE CONSTRAINT route_planned_visit_days_mask_check;
ALTER TABLE app.route_planned VALIDATE CONSTRAINT route_planned_visit_kind_check;
