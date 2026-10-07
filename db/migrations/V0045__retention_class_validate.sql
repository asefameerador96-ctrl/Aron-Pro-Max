-- V0045 validates the V0044 partition_policy retention-class foreign key (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.partition_policy VALIDATE CONSTRAINT partition_policy_retention_class_fkey;
