-- V0186 validates the V0182 sync_rejected code CHECK (added NOT VALID; separate transaction, squawk rule; same pattern as V0113 and V0136).

SET lock_timeout = '5s';

ALTER TABLE app.sync_rejected VALIDATE CONSTRAINT sync_rejected_code_check;
