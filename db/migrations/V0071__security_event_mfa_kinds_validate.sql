-- V0071 validates the V0070 security_event kind CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.security_event VALIDATE CONSTRAINT security_event_kind_check;
