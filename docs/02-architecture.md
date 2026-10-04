# 02 — Architecture and Tech Stack

## The shape of the system

One backend and database serve three Android apps and a web front end. The system is a **round trip run once per SR per day**: the reference bundle goes down to the phone, the day's sales come back up, the server rolls them into aggregates, and both the apps and the web read those aggregates.

```
                        ┌─────────────────────────── Azure ───────────────────────────┐
  Field apps            │   API (Node/TS)        PostgreSQL            Blob Storage     │
  SR / AMO / TSO        │                                                               │
  Flutter, offline  ──POST /sync/batch──▶  ingest → transactional tables               │
      │  ▲               │                        │                                      │
      │  │  GET /sync/bundle (ref data)           ▼                                      │
      │  └───────────────────────────────  aggregation job → fact/rollup tables         │
      │                  │                        │                                      │
  photos ───────────────────────────────────────────────────▶ blob                     │
                         │   Web (Next.js) ◀── scoped reads ── fact/rollup tables        │
                         └───────────────────────────────────────────────────────────────┘
```

Two endpoints carry the business: `GET /sync/bundle` (everything the SR needs for the day, in one payload) and `POST /sync/batch` (the day's captured records, idempotent by client UUID). Everything else is ordinary CRUD and reads.

## Components

| Component | Tech (recommended) | Responsibility |
| --- | --- | --- |
| Mobile app | Flutter (Dart), one codebase, role-aware SR/AMO/TSO | Offline capture, geo-validation, Bluetooth printing, sync |
| API | Node.js + TypeScript (NestJS or Fastify) | Auth, bundle, sync ingest, reads, admin, aggregation triggers |
| Database | PostgreSQL (Azure Database for PostgreSQL) | Reference, transactional, aggregate data |
| Object store | Azure Blob Storage | Outlet/force-sale/gift photos |
| Web | Next.js + TypeScript + Tailwind | Dashboards, reports, admin/master-data, approval panels |
| Aggregation | SQL jobs / a worker (or Postgres materialized views to start) | Roll transactions into fact/rollup tables |
| CI/CD | GitHub Actions | Build, test, migrate, deploy to Azure |

## Why these choices

- **Flutter** for the apps: it is what the field already runs, so cutover needs no retraining, and one Dart codebase covers all three roles. (Confirm in `docs/13`; the functional specs are framework-agnostic.)
- **Postgres** over anything exotic: relational data, heavy aggregation, well understood at this scale.
- **Server-resolved scope**: the API computes reach from the token; the client cannot ask for data outside its assignment.
- **Aggregates for reads**: dashboards never scan the transaction log. This is the fix for the current 400KB-client-aggregation problem.

## Write side vs read side

- **Write side (transactions):** append-heavy, keyed by client UUID, treated as an event log — a synced record is inserted, an edit or due payment is a new row referencing the original. Safe to retry; full audit trail (useful against fake-GPS and edit abuse).
- **Read side (aggregates):** a job rolls new synced data into small per-date, per-scope summary tables that dashboards and app-home screens read directly. See `docs/10`.

## Environments and deploy

- `dev`, `staging`, `prod` on Azure. Separate Postgres and Blob per environment.
- GitHub Actions: on PR → build + test; on merge to main → migrate + deploy to staging; manual promote to prod.
- App distribution: internal APK channel for pilot, then Play Store managed/internal track or MDM push for the fleet (devices are shared-ownership — see migration notes).

## Cross-cutting concerns

- **Auth:** JWT access + refresh. The access token carries user id, role, and resolved scope (wing/division/territory/zone/route set). Device binding via a TSO-issued one-time code (as today).
- **Localization:** Bangla-first, English labels where the current app uses them; all strings in a localization layer; Bengali fonts bundled.
- **Observability:** structured logs, a sync-health dashboard (login %, submit %, final-submit per zone), and error reporting (e.g. Sentry) — but privacy-aware (no PII in logs).
- **Time:** UTC everywhere + an explicit Asia/Dhaka `business_date` on transactions.
