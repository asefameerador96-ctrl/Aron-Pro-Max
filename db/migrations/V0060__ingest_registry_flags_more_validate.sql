-- V0060 validates the V0059 flags CHECK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.ingest_registry VALIDATE CONSTRAINT ingest_registry_flags_known;
