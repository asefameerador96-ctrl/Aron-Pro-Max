# Request: tasks tables and task_event mapping (android-sr-a to android-core, 2026-10-07)

F-SR-046/047 need, in `core-database`:
1. A `task` reference table filled by `ReferenceRepository.apply` from the bundle's `tasks[]` (contract `Task`: task_uuid, task_type_code, title, description, outlet_id, due_date, status, resolved_at).
2. `CaptureRepository.resolveTask(taskUuid, resolvedAt, TaskEventPayload)`: ONE transaction that sets the local task `completed` and inserts a `task_event` outbox record (payload `TaskEventPayload`: task_uuid, event `resolved`, note; own client_uuid).
3. A Room migration and migration test.

android-sr-a ships `TaskStore` (interface in `feature-tasks`) and the screen logic against it; the Room binding follows when this lands.
