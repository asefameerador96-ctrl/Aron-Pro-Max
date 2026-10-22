-- V0098 call record columns the dashboard needs on dw.fact_visit (owner directive 2026-10-22, docs/34 s2; dashboard
-- brief s5 and s6: "Call record (completed, within_fence)", "Call record (GPS, distance, within_fence, photo)").
-- app.visit (V0007) already holds every source value; the fact had distance_m (the phone's) and the verdicts only.
-- 1. Copied by the projector (backend-reports, Aggregator) from app.visit: server_distance_m (the server's re-check),
--    fix_lat / fix_lng (where the call was made; personal data, never in a dw.v_* view), force_photo_uuid (the
--    Force Sale photo of the Sale Position page).
-- 2. Generated, so every reader gets one definition:
--    within_fence = the call is geo-valid: the server's verdict, else the phone's, is in_range (the rule the
--                   Aggregator already uses for geo_valid_visits);
--    completed    = the call was closed with an outcome other than abandoned or not_reached and is not voided
--                   (Total Calls: with or without a memo, zero calls included).
--    Adding stored generated columns rewrites the fact's partitions; at pilot size that is seconds, and the final
--    account starts with an empty fact.

SET lock_timeout = '5s';

ALTER TABLE dw.fact_visit
  ADD COLUMN server_distance_m double precision,
  ADD COLUMN fix_lat           double precision,
  ADD COLUMN fix_lng           double precision,
  ADD COLUMN force_photo_uuid  uuid;
-- squawk-ignore adding-field-with-default
ALTER TABLE dw.fact_visit ADD COLUMN within_fence boolean GENERATED ALWAYS AS (coalesce(server_verdict, device_verdict) = 'in_range') STORED;
-- squawk-ignore adding-field-with-default
ALTER TABLE dw.fact_visit ADD COLUMN completed boolean GENERATED ALWAYS AS (NOT voided AND outcome_code IS NOT NULL AND outcome_code NOT IN ('abandoned', 'not_reached')) STORED;

COMMENT ON TABLE dw.fact_visit IS 'One row per visit with device and server verdicts, distances, the call fix, the Force Sale photo, the void flag and the generated within_fence and completed flags.
owner: worker | capture: SERVER | retention: event_fact | pii: personal';
COMMENT ON COLUMN dw.fact_visit.server_distance_m IS 'Server re-check of the distance between the call fix and the outlet location, metres (app.visit.server_distance_m); null until checked.';
COMMENT ON COLUMN dw.fact_visit.fix_lat IS '[pii:personal] Latitude of the call fix (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.fact_visit.fix_lng IS '[pii:personal] Longitude of the call fix (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.fact_visit.force_photo_uuid IS 'media_uuid of the Force Sale photo; null for a call inside the fence.';
COMMENT ON COLUMN dw.fact_visit.within_fence IS 'Generated: true when the server verdict, else the device verdict, is in_range (geo-valid call).';
COMMENT ON COLUMN dw.fact_visit.completed IS 'Generated: true when the call is not voided and closed with an outcome other than abandoned or not_reached.';
