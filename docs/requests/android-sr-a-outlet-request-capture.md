# Request: outlet_change_request capture (android-sr-a to android-core, 2026-10-07)

F-SR-037/038/039/076/N-040 queue an `outlet_change_request` (contract `OutletChangeRequestPayload`: request_type, outlet_id, proposed, fix, photo_uuids, origin_visit_client_uuid, note). `CaptureRepository` has no method for it yet and `RecordMapping` has no `outlet_change_request` mapping. Needed in core-database: an entity (or the generic outbox path) plus `CaptureRepository.recordOutletRequest(...)` in ONE transaction with its outbox record, header record signing hook (D24-39), a Room migration and its test. The photo `media_meta` record syncs after this record (docs/24 s4.2 rule 5).

android-sr-a ships `OutletRequests` and `OutletRequestCommitter` (feature-outlet) and the forms' validation; the Room binding follows when this lands.
