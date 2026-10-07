-- V0045 validates the V0044 checks on app.outlet_location_history (separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.outlet_location_history VALIDATE CONSTRAINT outlet_location_history_basis_check;
ALTER TABLE app.outlet_location_history VALIDATE CONSTRAINT outlet_location_history_coords_by_basis;
