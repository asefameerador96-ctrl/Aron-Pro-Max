# Request to backend-admin and db (from backend-core, 2026-10-07): the pin before an outlet's first edit must be in `app.outlet_location_history`

The server geo re-check (F-SYS-012, `backend/sync/.../GeoRecheck.kt`) judges each visit against the outlet pin that
was **in force at capture**: the newest `app.outlet_location_history` row with `valid_from <= captured_at`. When an
outlet has no history at all, it uses the current pin.

The Opus checker found a gap. Today only `AdminOutlets.kt:123` writes history, and only the **new** pin of an edit. An
outlet that had a pin with no history row (seed, migration, any outlet created outside AdminOutlets) gets its first
history row at the first edit, with `valid_from` = the edit time. Visits captured **before** that edit, but uploaded or
re-checked after it, find no history row at or before their capture. The pin the phone used is no longer recorded
anywhere. Until this is fixed the server judges such visits `no_outlet_location`. That is conservative: the phone's own
outlet coordinates are never trusted, because a hooked client could send its fix as the outlet. But it under-counts
honest geo-valid calls for outlets edited in the last `cfg.sync.max_backdate_days`.

## Ask
1. **backend-admin** (`AdminOutlets`): on a pin edit or clear of an outlet whose history is empty while it has a
   pin, first insert the current pin as a history row (`source 'migration'`, `basis` = the outlet's basis,
   `valid_from` = the outlet's `created_at`), then the new one. Also record a **clear** (basis none or placeholder).
   That needs either a nullable lat/lng with a `cleared` source, or the same rule below; please agree the shape with db.
2. **db**: a forward-only backfill migration: for every outlet with a pin (`lat` or `provisional_lat`) and no history
   row, insert that pin with `valid_from = created_at`, `source 'migration'`.

## Acceptance (backend-core then enables the checker's test `aPinEditDoesNotEraseThePinInForceBeforeIt`)
A visit captured 80 m from the pin an outlet had before its first web edit, uploaded after the edit, is stored
`server_verdict = 'in_range'` at a 100 m radius.
