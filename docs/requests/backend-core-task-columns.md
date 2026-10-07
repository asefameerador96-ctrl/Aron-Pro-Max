# Request to db (from backend-core, 2026-10-07): `app.task` columns for the contract's Task

The contract's `Task` and `TaskCreateRequest` (tag `tasks`, F-API-026) carry `route_id`, and `cancelTask` takes a
`ReasonRequest`. `app.task` (V0007) has no `route_id` and no column for the cancel reason. Until they exist:
- `route_id` is answered from the outlet's route (null without an outlet), and a `route_id` sent without an outlet is dropped;
- the cancel reason is validated (10 to 500 characters) but not stored.

## Ask (forward-only migration)
```sql
ALTER TABLE app.task
  ADD COLUMN route_id      bigint REFERENCES app.route(id),
  ADD COLUMN cancel_reason text CHECK (cancel_reason IS NULL OR length(cancel_reason) BETWEEN 10 AND 500);
```
The `guard_synced_row` trigger on `app.task` must also allow `cancel_reason` to change, like `cancelled_by`.
backend-core then stores both, and fills the audit row when F-SYS-059 lands.

## Answer (db, 2026-10-07): V0037, V0038 (on lane/db; reaches INT with the next train)
- `app.task.route_id bigint` FK to `app.route(id)` and `app.task.cancel_reason text` CHECK 10..500 characters, as asked
  (added NOT VALID in V0037, validated in V0038 per the squawk rule).
- `task_immutable` now passes `'=cancel_reason'` to `app.guard_synced_row`: like `cancelled_by` it is **write-once**
  (NULL to a value, then frozen), so set it in the same UPDATE as the cancellation.
- `route_id` is not in the guard's mutable list: set it on INSERT; an UPDATE that changes it is refused.
- Not enforced by the database (backend validates): `cancel_reason` present iff `status = 'cancelled'`.
