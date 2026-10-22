-- V0092 route type (owner directive 2026-10-22, docs/34 s2 "Route kinds"; dashboard brief s3.1: Live CPR and Login count
-- normal routes only, "Manual (no-internet) routes and the dummy BR / Institutional Sales routes are excluded").
-- The directive calls it route.kind, but app.route.kind (V0002) already means sr or amo, so the new column is
-- app.route.route_type: normal (default, every existing route), manual (no-internet route keyed on the web),
-- dummy_br, dummy_institutional (sales booked to a dummy BR / Institutional Sales route). The CHECK is added NOT VALID
-- and validated in V0093 (every existing row takes the constant default, so the validation only reads).
-- dw.dim_geo and dw.dim_geo_version carry it for the card queries (backend-reports' projector copies it).

SET lock_timeout = '5s';

ALTER TABLE app.route ADD COLUMN route_type text NOT NULL DEFAULT 'normal';
ALTER TABLE app.route ADD CONSTRAINT route_route_type_check
  CHECK (route_type IN ('normal', 'manual', 'dummy_br', 'dummy_institutional')) NOT VALID;

ALTER TABLE dw.dim_geo ADD COLUMN route_type text NOT NULL DEFAULT 'normal';
ALTER TABLE dw.dim_geo_version ADD COLUMN route_type text NOT NULL DEFAULT 'normal';

COMMENT ON COLUMN app.route.route_type IS 'normal, manual (no-internet, keyed on the web), dummy_br or dummy_institutional; Live CPR, Target Route and Login count normal routes only (V0092).';
COMMENT ON COLUMN dw.dim_geo.route_type IS 'Route type copied from app.route.route_type (normal, manual, dummy_br, dummy_institutional).';
COMMENT ON COLUMN dw.dim_geo_version.route_type IS 'Route type in this version.';
