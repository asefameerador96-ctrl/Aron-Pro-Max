-- V0094 route class (owner directive 2026-10-22, docs/34 s2 "Route kinds"; dashboard brief s3.1: Live CPR, Target Route
-- and Login count normal routes only, "Manual (no-internet) routes and the dummy BR / Institutional Sales routes are
-- excluded"). The directive calls it route.kind, but app.route.kind (V0002) already means sr or amo, so the column is
-- app.route.route_class, the name backend-reports reads (docs/requests/db-backend-reports-dashboard-summaries.md A1):
-- normal (default, every existing route), manual (no-internet route keyed on the web), dummy_br, dummy_institutional.
-- The CHECK is added NOT VALID and validated in V0095 (every existing row takes the constant default).
-- dw.dim_geo and dw.dim_geo_version carry it; backend-reports' projector copies it with the dimension.

SET lock_timeout = '5s';

ALTER TABLE app.route ADD COLUMN route_class text NOT NULL DEFAULT 'normal';
ALTER TABLE app.route ADD CONSTRAINT route_route_class_check
  CHECK (route_class IN ('normal', 'manual', 'dummy_br', 'dummy_institutional')) NOT VALID;

ALTER TABLE dw.dim_geo ADD COLUMN route_class text NOT NULL DEFAULT 'normal';
ALTER TABLE dw.dim_geo_version ADD COLUMN route_class text NOT NULL DEFAULT 'normal';

COMMENT ON COLUMN app.route.route_class IS 'normal, manual (no-internet, keyed on the web), dummy_br or dummy_institutional; Live CPR, Target Route and Login count normal routes only (V0094).';
COMMENT ON COLUMN dw.dim_geo.route_class IS 'Route class copied from app.route.route_class (normal, manual, dummy_br, dummy_institutional).';
COMMENT ON COLUMN dw.dim_geo_version.route_class IS 'Route class in this version.';
