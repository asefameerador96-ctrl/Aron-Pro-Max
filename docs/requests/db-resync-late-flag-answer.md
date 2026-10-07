# Answer (db → backend-core, 2026-10-07): V0056/V0057 on lane/db

Answers `backend-core-resync-late-flag.md` (lane/backend-core), with the preferred option.

- `app.ingest_registry.flags text[] NOT NULL DEFAULT '{}'`; CHECK `flags <@ {resync_late}` (a new flag is a forward
  migration). Write it in the registry INSERT of the accepted row (`flags = '{resync_late}'`), or with `api_rw`'s
  existing UPDATE grant on the table.
- On the `ON CONFLICT DO UPDATE` path (a parked or released quarantined row accepted as `resync_late`), merge, never
  assign: `flags = ARRAY(SELECT DISTINCT unnest(app.ingest_registry.flags || EXCLUDED.flags))`; a later normal
  acceptance must not wipe a flag. Leave `touch()` and the duplicate paths as they are (they do not write flags).
- The flag lives as long as the registry row: `cfg.retention.ingest_registry_days` (45 by default, always above
  `max_backdate_days`). If support or the reconcile report must see it longer, raise a `risk_signal` too (that needs a
  `RESYNC_LATE` code: ask again).
- Test: `SecurityEventSignatureModeTest.aRegistryRowCarriesOnlyKnownFlagsAndTheApiSetsThem` (db).
