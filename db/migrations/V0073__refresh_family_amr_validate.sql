-- V0073 validates the V0072 refresh_family amr CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.refresh_family VALIDATE CONSTRAINT refresh_family_amr_known;
