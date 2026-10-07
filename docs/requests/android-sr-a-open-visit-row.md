# Request: local OPEN visit row (android-sr-a to android-core, per ruling R8, 2026-10-07)

`feature-outlet` defines `OpenVisitStore` (begin / findOpen / clear) and `ConfigCheck` (F-SYS-092). core-database needs a small `open_visit` table (visit_uuid PK, outlet_id, route_id, started_at, business_date, state OPEN), written with NO outbox record, cleared in the same transaction as `CaptureRepository.recordVisitOpen`, plus a Room migration and test. `ConfigCheck` is implemented by core-sync/core-session.
