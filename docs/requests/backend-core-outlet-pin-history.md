# Request to backend-admin and db (from backend-core, 2026-10-07): every outlet pin change in `app.outlet_location_history`

The server geo re-check (F-SYS-012, `backend/sync/.../GeoRecheck.kt`) judges a visit against the outlet location
**in force at capture**: the newest `app.outlet_location_history` row with `valid_from <=` the capture (the D-431
evaluation instant). db answered the schema half (V0044/V0045: bases `placeholder` and `none` with null
coordinates; `db-location-history-basis-answer.md`). Two gaps remain, both found by the Opus checker of the
stood-down duplicate session (`GeoRecheckCheckerTest`).

1. **A cleared pin is not recorded.** `AdminOutlets.kt` writes a history row only when a new pin is set
   (`pinSet`). Clearing a pin (basis `none`) or a placeholder basis writes nothing, so the history still shows the
   old pin. backend-core works around it: an outlet whose basis is **now** none or placeholder counts as having no
   location, which also under-counts visits captured before the clear.
2. **The pin before an outlet's first edit is lost.** An outlet whose pin has no history row (seed outside
   `MIR-%`, outlets created outside AdminOutlets) gets its first row at its first edit, `valid_from` = the edit.
   Visits captured before the edit and uploaded or swept after it find no row: the server judges them
   `no_outlet_location` (conservative; the phone's own outlet coordinates are never trusted).

## Ask
- **backend-admin** (`AdminOutlets`): on every basis change write a history row, `valid_from` = the change
  instant: master/provisional with coordinates, `none` (clear) and `placeholder` with null coordinates. Before the
  first row of an outlet that already has a pin, insert that pin (`source 'migration'`, `basis` = the outlet's
  basis, `valid_from` = the outlet's `created_at`).
- **db**: a forward-only backfill: for every outlet with a pin (`lat` or `provisional_lat`) and no history row,
  insert that pin with `valid_from = created_at`, `source 'migration'`; and the seed writes history for every
  seeded outlet with a pin.

## Then backend-core
- Drops the "basis now" guard in `GeoRecheck.kt` (the history row alone decides).
- Enables `GeoRecheckCheckerTest.aPinEditDoesNotEraseThePinInForceBeforeIt` (80 m from the pre-edit pin, edit
  after capture, 100 m radius: `in_range`).
