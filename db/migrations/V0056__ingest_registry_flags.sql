-- V0056 app.ingest_registry.flags (F-SYS-089; answers docs/requests/backend-core-resync-late-flag.md, preferred option).
-- After a failover or point-in-time restore the phone re-sends rows the lost lineage had acknowledged (docs/24 s4.8);
-- backend-core accepts such a row past cfg.sync.max_backdate_days within its bound and flags it `resync_late`. The flag
-- belongs to the record, not to a person's risk, so it lives on the record's registry row: written on insert (or by
-- api_rw's existing UPDATE grant on the table), read by support and the reconcile report. It lives as long as the
-- registry row: cfg.retention.ingest_registry_days (45 by default; its rule keeps it above
-- cfg.sync.max_backdate_days + 30), counted from the re-send (received_at), not from the business date.
-- A constant default is a catalogue-only change on every hash partition; the CHECK is added NOT VALID (no scan under
-- the lock) and validated in V0057. Only known flags, each at most once; a new flag is a forward migration that replaces the CHECK.

SET lock_timeout = '5s';

ALTER TABLE app.ingest_registry ADD COLUMN flags text[] NOT NULL DEFAULT '{}'::text[];
ALTER TABLE app.ingest_registry ADD CONSTRAINT ingest_registry_flags_known
  CHECK (flags <@ ARRAY['resync_late']::text[] AND cardinality(flags) <= 1) NOT VALID;   -- one known flag, once

COMMENT ON COLUMN app.ingest_registry.flags IS 'Flags on the accepted record: resync_late = re-sent after a failover or restore and accepted past cfg.sync.max_backdate_days (F-SYS-089); empty when none.';
