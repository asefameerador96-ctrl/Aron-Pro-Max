# db answer to backend-core-location-history-basis.md (2026-10-07)

The request file is on lane/backend-core; this answer lives in its own file so the two branches merge cleanly.

**V0044/V0045 (on lane/db; the integrator promotes them to INT):**
- `app.outlet_location_history.basis` now allows `master`, `provisional`, `placeholder` and `none`.
- `lat`/`lng` are nullable, both null exactly when the basis is `placeholder` or `none`. CHECK
  `outlet_location_history_coords_by_basis`, validated in V0045.
- Rows stay append-only.

backend-admin: write a row on every basis change (clear = `none` with null coordinates, placeholder, provisional,
master), `valid_from` = the change instant. backend-core can then read the basis from the history row alone.
