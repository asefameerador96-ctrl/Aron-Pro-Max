-- V0095 validates the V0094 route_class CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.route VALIDATE CONSTRAINT route_route_class_check;
