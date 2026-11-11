-- V0157 first-result row version (lane portal-discount-api, follow-up of D-DISC-API-06; local until the lead schedules a train).
-- A replayed request answers the FIRST result: the status and the ETag the offer had when the call first succeeded. A version row is
-- immutable and the trail append-only, so the row version of app.offer that the call produced is stored on insert (the handler knows it:
-- it holds the offer row locked and the one update that follows adds exactly 1). Null for rows made before this migration.
SET lock_timeout = '5s';

ALTER TABLE app.offer_version ADD COLUMN result_row_version int;
ALTER TABLE app.offer_status_change ADD COLUMN result_row_version int;
COMMENT ON COLUMN app.offer_version.result_row_version IS 'Row version (ETag) that app.offer had right after the call that made this version; a replay of the same request_uuid answers with it. Null for versions made before V0157 and for versions not made by the portal.';
COMMENT ON COLUMN app.offer_status_change.result_row_version IS 'Row version (ETag) that app.offer had right after this status move; a replay of the same request_uuid answers with it. Null for moves made before V0157.';
