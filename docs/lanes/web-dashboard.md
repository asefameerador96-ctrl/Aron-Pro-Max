# Lane brief: web-dashboard

Session model: **Sonnet** (docs/29 s3). Owns: `web/` dashboard routes (`web/src/app/(dashboards)`), report pages and charts.

Read `docs/lanes/README.md` first.

- Scope: role-aware dashboards and reports (the report list in `docs/evidence/manuals/`, minus Target, Astha, Campaign Gift, Diamond League and Superstar: docs/27), team map, sync-health ops view, leaderboard, exports, PII gating, web login and session, change password.
- The admin portal is built by lanes `web-admin` and `web-config` under `web/src/app/admin`; do not edit their folders. Shared kit lives in `web/src/` components from N-010 and N-011; append, do not reformat.
- Build against the contract with the existing mock server (`web/mock/server.ts`) and the generated client; the real API arrives from backend-reports and backend-core. Charts per the `dataviz` skill; light and dark; fast: p95 page budget of docs/31.
- Maps: Google Maps JavaScript key from the build secret (`N-047`); never hard-code.

## Completion pass (owner, 2026-10-17 night: "complete the dashboard in the web portal, designing and functionalities")

All 48 dashboard rows have code (docs/status/web-dashboard.md), so this pass is about quality and completeness, not new rows. Work it in this order and push small candidates to `lane/web-dashboard` (the integrator promotes green heads; ask the lead for `checked <sha>` only if a candidate touches the contract):

1. Read `docs/09-web-and-api.md` (dashboard sections), `docs/design/web-glass.md`, `docs/design/screens-web.md`, the F-WEB rows of `docs/15-feature-inventory.md`, and `docs/status/ui-calm-glass.md`. The owner's screenshot of the current portal dashboard arrived at 19:00 UTC: it is described in `docs/evidence/web-dashboard-reference-2026-10-17.md` (layout, cards, and the lead's rulings on deferred programmes). That page is your visual and functional target; read it before the audit.
2. Audit every dashboard route in `web/src/app/(dashboards)` (home, reports, final-submit, daily-tracking, tso-daily-tracking, sync-health, routes, leave, exceptions, products, device-otp, credentials, tutorial) once per role (TSO, DMO, WM, TOP, ANALYST, SUPPORT). For each: does it show everything the spec lists, is every number from the contract (no placeholder or mock value on a production path), do filters cascade by the token scope, does export work, are empty, loading, error and offline states present, Bangla and English, keyboard and focus, tablet width.
3. Design: finish the Calm Glass web look so the dashboard home reads as a finished product: a KPI strip, trend charts per the `dataviz` skill (accessible, light and dark, consistent hues), a team map panel, recent activity or exceptions, per-role tile sets, clear hierarchy and spacing. The web may be calmer than the Android apps but must use the shared tokens (the vibrant status hues are fine in charts and badges). Solid surfaces under every number. No Target, loyalty or discount programme (docs/27).
4. Functionality gaps you find in the data layer: file `docs/requests/backend-reports-<name>.md` with the report key, the columns and a failing contract test, and carry on with the rest; the lead routes it.
5. Tests: unit and Playwright; screenshots of the key pages in `web/e2e` (Linux goldens are recorded by the `web-snapshots` workflow, never locally on Windows). Keep the p95 and bundle budgets of docs/31.
6. Before 05:00 UTC write the top of `docs/status/web-dashboard.md`: what changed tonight, which seeded user to log in as for each page (`tso1001`, `dmo1001`, `admin1001`; passwords are never written down), what is still open.
