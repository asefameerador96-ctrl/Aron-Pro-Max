# Aron Rebuild — Build Package

This repository is the complete, self-contained specification for rebuilding **Aron**, AKTCL's field-sales and distribution platform, in-house. AKTCL owns the IP; this is a clean-room rebuild designed from the running product and its user manuals, to replace the current vendor (Apsis) build with zero disruption to the field sales team.

It is written to be handed to **Claude Code** (or any engineer) and built from, phase by phase.

## What Aron is

A DMS/SFA platform. Sales Representatives (SRs) sell to retail outlets door-to-door using an Android app that works **fully offline**, prints memos over Bluetooth, and validates each sale against the outlet's GPS location. Supervisors (AMO → TSO → DMO → Wing Manager → Top Management) coach, verify and roll the numbers up through a web dashboard. Scale: ~8,500 SRs, 1,051 zones, ~4.6 lakh outlets, ~1.2 lakh sales calls/day.

## How to use this with Claude Code

1. Unzip into your repo root.
2. Read **`CLAUDE.md` first** — it holds the mission, the non-negotiable constraints, the stack, and the definition of done. Keep it loaded for every build session.
3. Read `docs/` in order (01 → 13). They go from context to a working data model, the offline/sync engine, each app, the web/API, and the phased plan.
4. Start the Postgres database from `db/schema.sql`.
5. Build by the phases in `docs/12-phases.md`. **Do Phase 0–1 (the vertical slice) first** and prove offline → sync → dashboard before widening.

## Repo layout

```
CLAUDE.md                      Agent brief: mission, constraints, stack, conventions, DoD
README.md                      This file
db/
  schema.sql                   Postgres DDL to start from
docs/
  01-overview.md               Context, goals, the no-hiccup cutover mandate, success criteria
  02-architecture.md           System architecture, stack, Azure hosting, CI/CD
  03-data-model.md             Every table, keys, relationships, enums
  04-sync-offline-battery.md   Offline store, sync protocol, day state machine, cache, battery & size budget
  05-geo-validation.md         Geofence, per-territory radius, force sale, anti-spoofing
  06-sr-app.md                 SR app, screen by screen (the priority app)
  07-amo-app.md                AMO app (control/joint calls, verification, selling)
  08-tso-app.md                TSO app (final submit, visit plans, periphery, leave)
  09-web-and-api.md            Web dashboard/portal pages + the API contract
  10-kpis-and-programs.md      KPI formulas, aggregation, Astha/Diamond League/Superstar/promotions/targets
  11-migration-cutover.md      Apsis data-dump import + zero-hiccup cutover plan
  12-phases.md                 Phased build roadmap with a definition of done per phase
  13-open-questions.md         Decisions to confirm with Asef before/while building
```

## Decisions to confirm before building (recommended defaults in brackets)

These are the only load-bearing choices not yet locked. The specs are written to survive any of them; only the implementation notes change.

1. **Mobile framework** — [**Flutter**]. The current apps are Flutter and proven in the field; staying on Flutter means the least retraining risk for 8,500 SRs at cutover, which directly serves the no-hiccup mandate. Alternatives: React Native (reuses the team's JS/TS), native Kotlin.
2. **Backend stack** — [**Node.js + TypeScript (NestJS or Fastify) + PostgreSQL**], matching the team's existing stack. Alternative: .NET.
3. **Hosting** — [**Azure**] (Board-mandated): App Service or Container Apps for the API, Azure Database for PostgreSQL, Azure Blob Storage for photos, **GitHub Actions** for CI/CD.
4. **Auth** — [**JWT access + refresh, role and scope baked into the token**].

See `docs/13-open-questions.md` for the rest.

## Provenance

Built only from: the running web dashboard (observed under AKTCL's own login), the four official Aron user manuals (Web, SR, AMO, TSO), and the published product data. No Apsis source code or backend was used. Two companion human-readable documents exist as live Claude Docs — the **Aron System Map** (what the current system does) and the **Aron Rebuild Blueprint** (the forward design this package expands).
