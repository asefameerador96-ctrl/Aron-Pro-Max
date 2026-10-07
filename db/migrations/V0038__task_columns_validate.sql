-- V0038 validates the V0037 task constraints (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.task VALIDATE CONSTRAINT task_route_id_fkey;
ALTER TABLE app.task VALIDATE CONSTRAINT task_cancel_reason_check;
