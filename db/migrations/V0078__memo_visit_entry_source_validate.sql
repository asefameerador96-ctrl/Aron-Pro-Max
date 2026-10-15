-- V0078 validates the V0077 CHECKs (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.memo VALIDATE CONSTRAINT memo_entry_source_known;
ALTER TABLE app.visit VALIDATE CONSTRAINT visit_entry_source_known;
ALTER TABLE app.ingest_registry VALIDATE CONSTRAINT ingest_registry_flags_known;
