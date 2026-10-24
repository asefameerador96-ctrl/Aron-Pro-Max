-- V0113 validates the V0112 sync_rejected code CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.sync_rejected VALIDATE CONSTRAINT sync_rejected_code_check;
