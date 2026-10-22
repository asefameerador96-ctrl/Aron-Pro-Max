-- V0103 validates the V0102 constraints on app.device (policy FK, version and pair CHECKs) (added NOT VALID; separate transaction, squawk
-- rule; the columns are new, so every row passes).

SET lock_timeout = '5s';

ALTER TABLE app.device VALIDATE CONSTRAINT device_mdm_policy_fkey;
ALTER TABLE app.device VALIDATE CONSTRAINT device_mdm_policy_version_check;
ALTER TABLE app.device VALIDATE CONSTRAINT device_mdm_policy_pair;
