-- V0066 validates the V0065 bundle_count CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.route_day VALIDATE CONSTRAINT route_day_bundle_count_nonneg;
