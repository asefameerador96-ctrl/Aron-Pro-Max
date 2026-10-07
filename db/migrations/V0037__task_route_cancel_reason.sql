-- V0037 app.task columns for the contract's Task (docs/requests/backend-core-task-columns.md, F-API-026):
-- route_id (Task / TaskCreateRequest carry it; a task may name a route without an outlet) and cancel_reason
-- (cancelTask takes a ReasonRequest, 10 to 500 characters). Both are write-once on the synced row: route_id is set at
-- creation, cancel_reason with the cancellation. The FK and the check are added NOT VALID (every existing row is NULL;
-- no scan under lock) and enforced on every new and changed row; V0038 validates them.

SET lock_timeout = '5s';

ALTER TABLE app.task ADD COLUMN route_id bigint;
ALTER TABLE app.task ADD COLUMN cancel_reason text;
ALTER TABLE app.task ADD CONSTRAINT task_route_id_fkey FOREIGN KEY (route_id) REFERENCES app.route(id) NOT VALID;
ALTER TABLE app.task ADD CONSTRAINT task_cancel_reason_check
  CHECK (cancel_reason IS NULL OR length(cancel_reason) BETWEEN 10 AND 500) NOT VALID;

CREATE OR REPLACE TRIGGER task_immutable BEFORE UPDATE OR DELETE ON app.task
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', 'status_changed_at', '=cancelled_by', '=cancel_reason');

COMMENT ON COLUMN app.task.route_id IS 'Route the task belongs to (contract Task.route_id); null when the task names none.';
COMMENT ON COLUMN app.task.cancel_reason IS 'Reason given when the task was cancelled (10 to 500 characters); set once, with the cancellation.';
