# 12 — Phased Build Roadmap

Prove the hard part first (offline capture + exact-reconcile sync), then widen by module. Each phase has a definition of done; don't start the next until it's met.

## Phase 0 — Foundations
- Monorepo (`/api /web /app /db /packages /infra`), CI/CD to Azure, environments.
- Postgres from `db/schema.sql`; the importer skeleton + seed data.
- Auth: login, JWT with role + resolved scope, device binding (OTP), change-password rules.
- **DoD:** a seeded DB, green CI, a login that returns a correctly scoped token.

## Phase 1 — The vertical slice (the believable moment)
One SR, one day, offline → synced → on a dashboard tile.
- `GET /sync/bundle` for one SR (routes, outlets, products, prices, targets, geo config).
- Flutter app: check-in, open one outlet, geo-check, enter one sale, print to the Bluetooth printer — all in airplane mode.
- `POST /sync/batch` idempotent; device-vs-server counts match.
- Aggregate that sale into `fact_daily_*` and light up one real dashboard tile.
- **DoD:** kill-and-relaunch mid-sale loses nothing; duplicate upload doesn't double; the tile shows the sale; meets battery/size budget.

## Phase 2 — The full SR day
- Stock load + stock memo; full sale (offers, review, credit + partial payment, QC, print); zero sale; due collection; memo edit (geofenced, pre-QC, reasons); sales submit; check-out (5 pm).
- Outlet requests (new/close/info) with photos.
- Geo-validation complete with anti-spoofing flags (`docs/05`).
- **DoD:** an SR can run a real beat end to end offline; reconciles exactly; force-sale and spoof flags work.

## Phase 3 — Supervisors
- AMO app: control call (distribution/OOS/POSM), joint call assessment, team location/performance, SR stock, outlet verification, Update Base, AMO sale, sales submit.
- TSO app: dashboard, final submit (per zone/day, once), my periphery, visit plans, retailer questionnaire, leave, target status, feedback.
- **DoD:** verification flow SR→AMO→web works; final submit closes a zone and drives login/submit %.

## Phase 4 — Web dashboards & reports
- National dashboard + daily tracking + leaderboard from aggregates, scoped server-side.
- The report set (render on screen; Excel export). Outlet approval panel, target revise list.
- **DoD:** a Wing Manager sees only their wing; national dashboard loads fast from rollups; reports match control totals.

## Phase 5 — Programs
- Astha (targets, gift choice/handover), Diamond League (points, redemption, verify photos), Superstar, promotions/discounts, free samples, targets + multi-level revision approvals, task delegation, leave approval.
- **DoD:** points and gift flows reconcile; promotions apply at sale; target revisions route through approvals.

## Phase 6 — Admin / master data
- CRUD for geography, products, prices, sales plan, routes, assignments, users, scope, outlets; SR Device OTP panel; Set Target; Sales Plan; QC; supervisory module.
- **DoD:** the business can run the system without engineering.

## Phase 7 — Migration, pilot, cutover
- Full importer + reconciliation; parallel pilot on test routes; wave rollout with rollback; decommission Apsis (`docs/11`).
- **DoD:** reconciliation passes; a pilot route runs a clean parallel day; a wave cuts over with no data loss.

## Sprint note
AKTCL is running a tight sprint with daily involvement from Asef and pilot devices available. Phases 0–1 are the spine to land first; treat 2–6 as a backlog burned down module by module, each shippable on its own; 7 overlaps once Phase 2 is stable.
