# 09 — Web Dashboard / Portal + API Contract

## Web front end

Next.js + TypeScript + Tailwind. Every page reads aggregates (`docs/10`), scoped **server-side** from the user's token. Reports render on screen first, with Excel export as a formatting step (not a second query). The current build has 37 pages across 13 menus; most are Excel-only and send all 1,051 zone IDs from the browser — we fix both.

Standard filter on data pages: Wing → Division → Territory → House → Zone, defaulted to the user's scope (and bounded by it).

### Pages to build (grouped; parity with the current menu)

- **Dashboard (home):** national/territory snapshot — sales vs target by category, live CPR, calls by channel, geo-validation %, login/submit %, final-submit status, sales by brand/channel, SR positions on a map. Loads from rollups, no button press.
- **Retailer:** Browse Retailer (outlet list + detail; edit = basic info, address, business, additional-detail sections). PII-gated columns.
- **Products:** Category, Segment, Brand, Variant, SKU, Product Tree (reference views).
- **Route Planning:** Browse Routes (route → SR/SS by zone).
- **Reports (the bulk):** Task Planner, By-Route Geo Capture, STD Memo Report, SR Efficiency, Route-wise STD, Data Entry Log (download/upload per route), Final Submit Log, CPR & BSR, By Outlet Report, Astha Report, GIGO (attendance), Campaign Gift Redemption, Discount Report, By Outlet By Day, Online/Offline Sales, Free Sample, TSO Top Sheet Performance, TSO Daily Tracking Dashboard.
- **Target:** Target Allocation Report, Target Revise List (approval queue).
- **Outlet:** SR Outlets Reports (created/changed), Outlet Approval Panel (pending new-outlet requests + who verified).
- **Credentials:** change password (≥12 chars, mixed case + number, not last 10, not within 24 h of last change).
- **Astha Gift Panel:** Astha Gift Choice Report.
- **Tutorial:** the four manuals (serve as docs/videos).
- **Performance Leaderboard:** achievement % by wing→territory, by product level (loads live).
- **Superstar Program:** Superstar Campaign Report.
- **Daily Tracking Dashboard:** routes bucketed at 100 / 90–100 / 80–90 / <80% of sales & memo targets; a "take action" option after 17:00.

Admin/master-data pages (TSO/admin, beyond the SR-visible menu): QC, Sales Plan, Data Entry, Supervisory Module, Retailer/Wholesale Outlet, SR Device OTP, Diamond League setup, Set Target, and the master-entry CRUD (geography, products, routes, users, outlets).

## API contract

Envelope: `{ "message": string, "success": boolean, "data": T }`. Auth: `Authorization: Bearer <jwt>`. All reads scoped from the token.

### Auth & device
- `POST /auth/login` → `{ token, refreshToken, user, scope }`
- `POST /auth/refresh`
- `POST /auth/bind-device` `{ deviceUuid, otp }` → binds (TSO-issued OTP)
- `POST /auth/change-password`

### Sync (the two that carry the business)
- `GET /sync/bundle?since=<ts>` → the day's reference data for the caller:
  `{ routes, outlets[], products[], prices[], salesPlan[], targets[], offers[], loyaltyBalances[], geoConfig{radius_m}, tasks[], giftAssignments[] }`
- `POST /sync/batch` — body groups records by type, each with `client_uuid`:
  ```json
  { "visits": [...], "memos": [...], "memoLines": [...], "qc": [...],
    "surveys": [...], "drp": [...], "dues": [...], "redemptions": [...],
    "attendance": [...], "stockIssues": [...], "outletRequests": [...],
    "assessments": [...], "distributionChecks": [...] }
  ```
  → `{ accepted: { visits: n, memos: n, ... }, rejected: [{client_uuid, reason}] }` (idempotent upsert by `client_uuid`).
- `POST /media/upload` (multipart, Wi-Fi-preferred) → `{ blobUrl }`; link via record `client_uuid`.
- `POST /day/sales-submit` `{ routeId, businessDate }`; `POST /day/final-submit` `{ zoneId, businessDate }` (once/zone/day).

### Reads (dashboards, apps, reports) — all scoped server-side
- `GET /dashboard/national?date=` (rollups)
- `GET /dashboard/daily-tracking?date=`
- `GET /leaderboard?view=mtd|target|volume&level=wing|division|territory&productLevel=`
- `GET /reports/<name>?filters…&format=json|xlsx`
- `GET /app/home?role=&date=` (the app KPI strip, scoped to the SR/zone/territory)
- `GET /outlets`, `GET /routes`, `GET /targets` (scoped, paginated)

### Admin / master-data (web, role-gated)
- CRUD under `/admin/*`: geography, products, prices, sales-plan, routes, route-assignments, users, user-scope, outlets, outlet-approvals, targets, target-revisions, programs, device-otp.

### Rules
- Scope: the server intersects every query with the caller's `user_scope`; never accept scope IDs from the client.
- Pagination + `updated_since` on all list reads (keeps bundles and lists small).
- Idempotency: all device writes keyed by `client_uuid`.
- PII: outlet NID/TIN/phone returned only to roles that need them.
