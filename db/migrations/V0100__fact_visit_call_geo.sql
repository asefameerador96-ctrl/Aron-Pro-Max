-- V0100 call record columns on dw.fact_visit (owner directive 2026-10-22, docs/34 s2; dashboard brief s5: Sale Position
-- page and route map markers). backend-reports' request B6 (docs/requests/db-backend-reports-dashboard-summaries.md) as
-- written: radius_m, outlet_lat / outlet_lng (base position used at the call), fix_lat / fix_lng (the SR's position;
-- personal data, never in a dw.v_* view), force_photo_uuid and completed, copied or derived by the rebuild from
-- app.visit. Two more, not asked, that keep one definition for every reader:
-- - server_distance_m, copied by the rebuild when it wants the server's re-check (app.visit.server_distance_m);
-- - within_fence, a stored generated column: the server verdict, else the device verdict, is in_range (the rule the
--   Aggregator already uses for geo_valid_visits). The rebuild never writes it (it is not in its column list).
-- The generated column rewrites the fact's partitions: seconds at pilot size; the final account starts empty.

SET lock_timeout = '5s';

ALTER TABLE dw.fact_visit
  ADD COLUMN radius_m smallint,
  ADD COLUMN outlet_lat double precision, ADD COLUMN outlet_lng double precision,
  ADD COLUMN fix_lat double precision,    ADD COLUMN fix_lng double precision,
  ADD COLUMN force_photo_uuid uuid,
  ADD COLUMN completed boolean NOT NULL DEFAULT false,
  ADD COLUMN server_distance_m double precision;
-- squawk-ignore adding-field-with-default
ALTER TABLE dw.fact_visit ADD COLUMN within_fence boolean GENERATED ALWAYS AS (coalesce(server_verdict, device_verdict) = 'in_range') STORED;

COMMENT ON TABLE dw.fact_visit IS 'One row per visit with device and server verdicts, distances, the outlet base and call positions, the Force Sale photo, the void flag and the completed and within_fence flags.
owner: worker | capture: SERVER | retention: event_fact | pii: personal';
COMMENT ON COLUMN dw.fact_visit.radius_m IS 'Geo-fence radius used at the call, metres (app.visit.radius_m_used).';
COMMENT ON COLUMN dw.fact_visit.outlet_lat IS 'Latitude of the outlet base position used at the call (WGS84; a business location).';
COMMENT ON COLUMN dw.fact_visit.outlet_lng IS 'Longitude of the outlet base position used at the call.';
COMMENT ON COLUMN dw.fact_visit.fix_lat IS '[pii:personal] Latitude of the SR''s fix at the call (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.fact_visit.fix_lng IS '[pii:personal] Longitude of the SR''s fix at the call (WGS84); never in a dw.v_* view.';
COMMENT ON COLUMN dw.fact_visit.force_photo_uuid IS 'media_uuid of the Force Sale photo; null for a call inside the fence.';
COMMENT ON COLUMN dw.fact_visit.completed IS 'True for a call closed with an outcome other than abandoned or not_reached (set by the rebuild, D-DASH-03).';
COMMENT ON COLUMN dw.fact_visit.server_distance_m IS 'Server re-check of the distance between the call fix and the outlet location, metres; null until checked or not copied.';
COMMENT ON COLUMN dw.fact_visit.within_fence IS 'Generated: true when the server verdict, else the device verdict, is in_range (geo-valid call).';
