-- V0138 validates the V0137 app.visit_plan.route_id foreign key (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.visit_plan VALIDATE CONSTRAINT visit_plan_route_id_fkey;
