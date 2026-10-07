-- V0064 validates the V0063 last_sync_error CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.device VALIDATE CONSTRAINT device_last_sync_error_format;
