# 01 — Overview, Goals and Success Criteria

## The business

AKTCL (Abul Khair Group) sells tobacco and related FMCG through a door-to-door field force. Sales Representatives visit retail outlets on fixed routes, take orders, hand over stock, and print a memo on the spot. Aron is the system that runs and measures this: the SR's selling app, the supervisors' apps, and the management dashboards.

People hierarchy (each sees only their own patch):

> SR → Area Marketing Officer (AMO) → Territory Sales Officer (TSO) → Divisional Marketing Officer (DMO) → Wing Manager (WM) → Top Management

Geography (the spine of every query):

> Outlet → Route (section) → Cluster → Zone (distribution point) → Distribution House → Territory → Division → Wing

Scale to design for: **~8,500 SRs, 1,051 zones, 291 territories, 50 divisions, 10 wings, ~4.6 lakh outlets on a day's plan, ~1.2 lakh successful calls/day, 42 SKUs.**

## Why we are rebuilding

The current Aron was built by vendor Apsis. AKTCL's Board decided to bring it in-house and end the Apsis relationship. AKTCL owns the IP. We expect only a data dump of the last ~8 months from Apsis; everything else we build.

## Goals

1. **Parity first.** Reproduce every function the field force and management rely on today, so the switch is invisible to users.
2. **Then better**, only where it is safe: fix the known pain points — fake-GPS spoofing, data/battery drain, slow client-side dashboards, browser-supplied scope.
3. **Independence.** Own the stack end to end on Azure, deployable via GitHub Actions, with no vendor lock-in.

## The no-hiccup mandate (the overriding requirement)

When we switch 8,500 reps from the Apsis app to ours, a selling day must not break. That means:

- **Same workflow.** The SR's day — login/route download, attendance, stock, visit, geo-check, sale, QC, print, dues, sync, submit — works the same, screen for screen, so no retraining is needed.
- **Same memo.** The printed memo looks and totals the same to the retailer.
- **History intact.** Targets, dues, loyalty points, outlet locations and the last several months of sales are migrated, so day one in the new app shows the right balances and achievement.
- **Parallel run.** We run the new system alongside the old for a pilot set of routes before any wide switch (see `docs/11`).
- **Offline from day one.** A rep in a dead zone on cutover day must be unaffected.

## Success criteria

- An SR completes a full day offline and syncs with an exact device-vs-server reconciliation.
- A retailer's memo, dues and loyalty balance match what they had on the old app.
- A Wing Manager sees only their wing; the national dashboard loads in well under a second from aggregates.
- Geo-validation works offline and flags mock-GPS reliably.
- The app meets the battery and data budgets in `docs/04` on a low-end device.
- Zero data loss across the cutover.

## What the current system looks like (summary)

- **Web:** one Next.js build of Apsis's multi-tenant product; AKTCL's menu exposes 37 pages across 13 menus. Most reports are Excel downloads; the dashboard aggregates 400KB+ payloads in the browser and sends all 1,051 zone IDs from the client — both to be fixed.
- **Apps:** three separate **Flutter** apps (SR, AMO, TSO), Dart compiled AOT, offline-capable, Bluetooth printing, GPS-gated sales.
- Full detail is in the companion **Aron System Map** Claude Doc; the forward design is in the **Aron Rebuild Blueprint** Claude Doc. This package supersedes and expands both for build purposes.
