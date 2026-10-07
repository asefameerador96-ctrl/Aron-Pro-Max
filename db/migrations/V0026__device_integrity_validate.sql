-- V0026 validates the V0025 device integrity constraints (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.device VALIDATE CONSTRAINT device_root_hints_check;
ALTER TABLE app.device VALIDATE CONSTRAINT device_integrity_unavailable_reason_check;
ALTER TABLE app.device VALIDATE CONSTRAINT device_integrity_unavailable_pair;
ALTER TABLE app.device VALIDATE CONSTRAINT device_root_hints_pair;
