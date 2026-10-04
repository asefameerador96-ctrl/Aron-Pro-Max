# 11 — Migration from Apsis and Zero-Hiccup Cutover

The switch must be invisible to 8,500 SRs. That means the history is migrated, the new system is proven in parallel, and the rollout is staged with a rollback at every step.

## The Apsis data dump

We expect ~8 months of data. Ask for it, in writing, in the shape the model needs (per `docs/03`):

- **Reference:** full geography (wing→zone with codes), product hierarchy + the five price types, routes with visit days, route→SR assignments, clusters, classifications, users+roles, per-territory geofence radius.
- **Outlets:** every outlet with code, owner, contact, address, lat/long, cluster, sub-channel, geo classification, status, created/updated.
- **Transactions (8 months):** visits, memos + lines, QC, attendance, stock issue/return, dues (open balances especially), loyalty ledger + redemptions, tasks, surveys.
- **Targets:** monthly by route/zone and product + revision history.
- **Media:** outlet/force-sale/gift photos with the IDs linking them to records.
- **Format:** CSV or a Postgres dump per table, with a data dictionary and the ID relationships.

## Import

- Build the importer against this shape now; seed with sample data so the build isn't blocked waiting on Apsis.
- Import order: geography → products/prices/sales-plan → users/scope/routes/assignments → outlets → targets → open dues & loyalty balances → historical transactions → media.
- **Reconcile after import:** row counts and control totals (per zone: outlets, MTD STD, memo count, total outstanding dues, loyalty balances) must match the old system's reports. Produce a reconciliation report; no cutover until it matches.
- Map Apsis IDs to new IDs in a crosswalk table (so re-imports and late-arriving data are idempotent).

## What must be exactly right on day one

- **Open dues** per outlet (retailers will dispute anything wrong).
- **Loyalty point balances** per outlet.
- **Outlet locations** (geo-validation depends on them).
- **Targets & MTD achievement** (so the SR's bars look right).
- **The printed memo** — same layout and totals the retailer is used to.

## Cutover plan (staged, reversible)

1. **Shadow import:** load the dump into the new system; reconcile. No users yet.
2. **Pilot (parallel run):** a few routes (you have test devices + pilot reps) run the **new app alongside** the old for 1–2 weeks. Compare daily: memos, STD, dues, geo %, submit %. Fix discrepancies. Keep Apsis as the system of record during pilot.
3. **Delta sync:** just before each wider wave, re-import the latest Apsis delta so balances are current at switch.
4. **Wave rollout:** switch by territory/wing in waves, not all at once. Each wave: final delta import → reps download the new app's bundle → old app set read-only for that wave. Watch login %/submit % for the wave on day one.
5. **Rollback:** if a wave breaks, revert that wave to the old app (it's still read-capable); data captured in the new app syncs and is preserved. No wave proceeds until the previous one is clean.
6. **Decommission Apsis** only after all waves are stable and a full final reconciliation passes.

## Device & distribution notes

- Phones are **shared-ownership**, so full MDM factory-reset enrollment isn't viable (known constraint). Distribute via an internal/managed track or signed APK channel; keep the in-app updater.
- The new app must coexist with the old during pilot/parallel (different package id), so a device can run both.
- Keep the data/battery budget (`docs/04`) — cutover must not make phones worse.

## Risks to watch

- Dump arrives incomplete or late → the importer + seed data keep the build moving; reconciliation gates the cutover, not the build.
- Memo-number continuity → decide whether new memo numbers continue the old series or restart with a prefix (`docs/13`).
- Outlet location quality → the force-sale photo-correction path cleans these over time.
