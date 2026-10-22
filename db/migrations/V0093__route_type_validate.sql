-- V0093 validates the V0092 route_type CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.route VALIDATE CONSTRAINT route_route_type_check;
