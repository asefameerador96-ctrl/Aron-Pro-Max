# Lane brief: web-dashboard

Session model: **Sonnet** (docs/29 s3). Owns: `web/` dashboard routes (`web/src/app/(dashboards)`), report pages and charts.

Read `docs/lanes/README.md` first.

- Scope: role-aware dashboards and reports (the report list in `docs/evidence/manuals/`, minus Target, Astha, Campaign Gift, Diamond League and Superstar: docs/27), team map, sync-health ops view, leaderboard, exports, PII gating, web login and session, change password.
- The admin portal is built by lanes `web-admin` and `web-config` under `web/src/app/admin`; do not edit their folders. Shared kit lives in `web/src/` components from N-010 and N-011; append, do not reformat.
- Build against the contract with the existing mock server (`web/mock/server.ts`) and the generated client; the real API arrives from backend-reports and backend-core. Charts per the `dataviz` skill; light and dark; fast: p95 page budget of docs/31.
- Maps: Google Maps JavaScript key from the build secret (`N-047`); never hard-code.
