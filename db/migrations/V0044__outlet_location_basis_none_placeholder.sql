-- V0044 every outlet location basis change can be recorded in app.outlet_location_history (F-SYS-012; answers
-- docs/requests/backend-core-location-history-basis.md, db half): a cleared pin (basis 'none') and a 'placeholder'
-- basis become history rows without coordinates, so the visit re-check reads the basis in force at capture from the
-- history alone. lat and lng are null exactly for those two bases. The new checks are validated in V0045 (squawk:
-- NOT VALID, then VALIDATE in another transaction).

SET lock_timeout = '5s';

ALTER TABLE app.outlet_location_history ALTER COLUMN lat DROP NOT NULL, ALTER COLUMN lng DROP NOT NULL;
ALTER TABLE app.outlet_location_history DROP CONSTRAINT outlet_location_history_basis_check;
ALTER TABLE app.outlet_location_history ADD CONSTRAINT outlet_location_history_basis_check
  CHECK (basis IN ('master', 'provisional', 'placeholder', 'none')) NOT VALID;
ALTER TABLE app.outlet_location_history ADD CONSTRAINT outlet_location_history_coords_by_basis
  CHECK ((lat IS NULL) = (lng IS NULL) AND (lat IS NULL) = (basis IN ('placeholder', 'none'))) NOT VALID;

COMMENT ON COLUMN app.outlet_location_history.lat IS 'Latitude in WGS84 degrees; null when the basis is placeholder or none.';
COMMENT ON COLUMN app.outlet_location_history.lng IS 'Longitude in WGS84 degrees; null when the basis is placeholder or none.';
COMMENT ON COLUMN app.outlet_location_history.basis IS 'Location basis from valid_from on: master, provisional, placeholder (no usable pin) or none (pin cleared).';
