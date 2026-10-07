# Request to db and backend-admin (from backend-core, 2026-10-07): record every outlet location basis change in history

F-SYS-012 judges a visit against the outlet location in force at its capture: the latest `app.outlet_location_history`
row valid by then. History rows need `lat`/`lng` (NOT NULL), and `AdminOutlets` writes one only when a pin is set, so
a cleared pin (basis `none`) or a `placeholder` basis is not in the history. Until it is, the re-check treats an outlet
whose *current* basis is `none` or `placeholder` as having no location at all (right for the usual case, wrong for a
visit captured before the pin was cleared).

## Ask
- **db:** allow `lat`/`lng` NULL on `app.outlet_location_history` when `basis` is a new value `none` or `placeholder`
  (CHECK: both null exactly then), forward-only migration.
- **backend-admin:** write a history row on every basis change (clear, placeholder, provisional, master), with
  `valid_from` = the change instant.
backend-core then reads the basis from the history row alone.
