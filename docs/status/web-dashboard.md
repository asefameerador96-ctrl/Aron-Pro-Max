# Web-dashboard lane status

## Done
- N-010 web skeleton (`web/`): generated client + drift test, HttpOnly-cookie BFF, proxy role gate (real 403), role-driven menu, scope badge, bn/en with Bengali digits, hardcoded-text lint in the build, contract-typed mock, Playwright smoke. Contract v1.1 merged, client regenerated, drift test green.

## Decisions
- Sealed (AES-GCM) session cookie `aron_sess` holds the access token server-side-readable only; `aron_rt` holds the refresh token; stateless, so logout cannot revoke a copied cookie (it dies at the next refresh).
- Roles with web access: TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN. SR/AMO get "no web access".
- TypeScript pinned to 5.9 (typescript-eslint and openapi-typescript do not support 7).

## Blocked / open
- Azure deploy of the web app is infra's (image: `node scripts/start-standalone.mjs`).
- Seeded TSO login awaits db seed; mock user `tso334` stands in.

## Requests filed
- web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job (docs/requests/).

## Scope note (lead, 2026-10-06)
- Read the deferred-programmes scope change (docs/27): target, loyalty/Astha and discount/promotion programmes are deferred. Nothing of them exists in `web/` (menu, entities, report keys); the admin CRUD generator stays generic so a programme module is one entity file later.
- Blocking nothing. Pending requests (routed by the lead): web-admin-create-reason, web-admin-get-by-id, web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job.

## Dashboard rows (session 2, 2026-10-07)

### Built (all 48 rows have code and tests; checker results below once recorded)
- **Report engine** (`web/src/lib/reports/*`, `web/src/components/reports/*`, `/reports/[slug]`, `/api/bff/reports/[slug]/export`): one `POST /v1/reports/{key}/query` page per catalogue entry
  (`catalog.ts`, 27 reports). One query builder serves screen, Excel, print and PDF (so "Get Excel = the screen's query" by construction);
  PII columns masked client-side as well as by the server (fail closed when `/v1/me` fails); Excel and print are streamed from the server (the
  server logs, watermarks and formula-sanitises: docs/24 s12.3); PDF is an export job (303 back with `?job=`).
- **Scope filter F-WEB-041**: `bindGeo`/`boundGeo`. Levels fixed by the token are shown read-only and dropped from every query; others cascade from the level above.
- **Dashboard home, final-submit panel/picker, tile meta (F-WEB-001/047/068)**, **Maps (N-047)**: `MapPanel` asks `/api/bff/maps/load` which counts loads per Dhaka day against `MAPS_DAILY_CAP` (default 2000, per instance) and hands out the key (`MAPS_WEB_KEY` or `NEXT_PUBLIC_MAPS_WEB_KEY`); no key => the same pins as a list.
- **Daily Tracking (038, 039, 028)**, **sync health (045)**, **exceptions (057)**, **leave (046)**, **tutorial (035)**, **credentials (033)**, **login (043)**, **routes (010)**, **products (004-007, 009)**.
- **Mock** (`web/mock/seed.ts`, `dash.ts`): one seeded day (2 territories, 6 routes); every report is a projection of it, so tests assert control totals. Test users: tso334, tso335, tso999 (outside any seeded scope), dmo1, analyst1, wm1.

### Decisions
- Money display: integer milli-taka by BigInt maths, three decimals, locale grouping (`formatTaka`); percentages one decimal (contract gives two).
- `Remember me` off (default) = session cookies; on = persistent. Admin/MFA roles never persistent. User ID is lower-cased and trimmed in the BFF (contract: case-insensitive).
- Leaderboard (F-WEB-036): `target` view omitted (target achievement is deferred, docs/27); the page offers location level and product level filters over the report. Column sets of reports are the server's (`columns_known` is false for several); the mock invents plausible ones.
- Product pages read `/v1/admin/product-nodes/*`; "outside the scope sees no rows" is vacuous there (the product tree is not scoped).
- DS-RRS (F-WEB-053) layout is unknown (ASSUMED in the backlog): built as the generic report with Get Data, Get Excel and the server's print view.

### Requests filed
- `docs/requests/web-dashboard-contract-gaps.md` (same-time comparator, sync-health ops figures, route assignee names, leaderboard target view, per-zone final-submit flag, password policy setting).
- Earlier: web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job, web-admin-create-reason, web-admin-get-by-id.

### Traps for the next session
- `.next` and the Playwright ports are shared: only one `npm run build`/e2e at a time. Use `/tmp/.../e2e.sh`-style: `PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test`.
- `lint` rejects string props named `label`/`title` etc. on JSX (hard-coded text rule): name the prop `msg`.
- The web-admin lane owns `web/src/app/admin` and `messages-config.ts`; dashboards add their messages in `messages-dash-*.ts` only.
- Mock handler order: stubs first, then `handleDash`, then the admin gate; keep it that way (the admin tests stub paths I also serve).
