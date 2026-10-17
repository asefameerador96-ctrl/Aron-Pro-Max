# qa → backend-reports (and lead for the contract): a visit read that shows the geofence radius used (N-050), 2026-10-17

**Need.** N-050's acceptance is "the next visit on the phone is stamped with 50 m and judged by it". The stamp is
stored: `app.visit.radius_m_used`, plus `server_radius_m`, `verdict`, `server_verdict` and `distance_m`. But no
operation returns it:
- the record payload has it on the way in;
- `dw.fact_visit` has `distance_m` but not the radius;
- no report column shows it.

The Day 4 check script (`tools/checks/day4-check.ps1`) therefore cannot prove the stamp from the laptop.

**Ask (smallest shape):** add `radius_m_used` and `server_radius_m` to the visit-level geo report rows. The report
that already bands `distance_m`, the geo-validation evidence in ReportHandlersD, is the natural place, or a
visit-detail report. Concretely, a visit row with `visit_client_uuid, outlet_id, outlet_code, opened_at, distance_m,
radius_m_used, server_radius_m, verdict, server_verdict`, readable by the TSO in reach. Scope: as every report, from
the token.

**Until then:** check 7 of the script fails with this reason. The laptop session reads the value from the dev
database for the owner's visit.
