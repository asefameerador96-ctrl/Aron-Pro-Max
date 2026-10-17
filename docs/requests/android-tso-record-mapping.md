# Request: TSO write records (android-tso -> shared, android-core)

Needed by F-TSO-009 (leave apply), F-TSO-018 (feedback), F-TSO-013/014/015/016 (visit plan, visit query, assign task), F-TSO-010 (final submit).

The TSO app queues these as outbox records like the SR app does (docs/24 s4.2, s4.3). Today `shared/contract` has the `RecordType` values (`leave_application`, `feedback`, `visit_plan`, `visit_plan_outlet`, ...) but no payload classes, and `RecordMapping` (core-database) has no builder for them. android-tso does not edit either.

## Wanted
1. `shared/contract`: payload DTOs mirroring the contract schemas `LeaveApplicationPayload` (leave_type_code, from_date, days, reason), the feedback, visit plan, visit plan outlet, visit query and final-submit payloads (names as in `contract/slices/INDEX.md`).
2. `core-database`: `RecordMapping.<name>(...)` returning an `OutboxEntity` (envelope + payload), and a small `TsoRecordRepository` (or a generic `CaptureRepository.recordSimple(entity)`) that writes the outbox row and a local domain row in one transaction, plus a read of own unsent rows by record type (so a screen can show "not sent yet").

## Until then
android-tso builds the screens, validation and ports with a stub marked `// REQUEST: docs/requests/android-tso-record-mapping.md`; the stub keeps the unsent record in memory only, and the row is marked in `docs/status/android-tso.md` as "queue stubbed".

## Update 2026-10-17 (night)
android-tso now queues its records itself with `app-tso/.../TsoOutbox.kt` (RecordMapping.record with a JsonObject payload, outbox row plus a sync_meta mirror in one Room transaction). The shared DTOs and RecordMapping builders are therefore a nice-to-have, not a blocker. Still wanted from the backend side: the exact required payload members of `feedback`, `visit_plan`, `visit_plan_outlet`, `visit_query` and `final_submit` are read from `contract/slices/schemas/`.

## Update 2026-10-18: reads TSO screens need
- Zone names: the contract has no read that returns a zone name for a TSO (routes carry only `zone_id`); the My Call pickers label zones "Zone <id>". Wanted: a `zone_name` on Route (`GET /v1/routes`) or a scope-tree read.
- `GET /v1/visit-plans` is read with `from`, `to` (one date) and `planner_user_id` of the caller; please confirm the server accepts the TSO's own id and returns completion from `call_assessment` with `visit_plan_outlet_client_uuid`.
- The TSO bundle must carry the `rubrics` section (kind retailer_questionnaire, bool criteria) and the `task_type` and `feedback_category` code lists.

## Update 2026-10-18 (Final Submit)
F-TSO-010 shows one Zone list built from `GET /v1/routes` (zone ids) instead of the manual's five dropdowns Wing, Division, Territory, House, Zone: the contract has no read that returns the names of the TSO's scope tree. Wanted: a read of the caller's scope tree with names (or `zone_name`, `house_name`, `territory_name` on Route), then the cascade is a UI change only.
