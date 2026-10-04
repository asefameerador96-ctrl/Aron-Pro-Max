# Seed findings from the orchestrator's first read of the spec (2026-10-04)

These were found by reading docs/01-13 and db/schema.sql and loading schema.sql into Postgres 16
(it loads cleanly: 47 tables). Treat them as confirmed; build on them, do not re-derive them.

## Schema / data gaps

1. Money precision. schema.sql stores money as bigint minor units (paisa, 1/100 Tk). 23 of the 42 seed
   prices in db/seed/sku_catalog.csv have three decimals (e.g. distributor price 7.935 Tk) and cannot be
   stored in paisa without rounding. Options: store 1/1000 Tk (milli-taka) integers, or numeric(14,3).
2. Idempotency holes. These device-originated tables have no client_uuid and no natural unique key, so a
   retried upload duplicates rows: memo_line, qc_entry, survey_response, drp_collection, loyalty_ledger,
   gift_photo, outlet_photo. task is also created/resolved offline (AMO/TSO assign, SR resolves) and has
   no client_uuid. Rule #2 in CLAUDE.md is violated by the starting schema.
3. visit has no route_id. fact_daily_route is keyed by route; outlets can move routes and SRs can cover
   other routes (route_assignment valid_from/valid_to), so deriving route from outlet misreports history.
   Store route_id (and the route_assignment / SR context) on visit at capture time.
4. Only one geo_validated column on visit. docs/05 requires the device verdict and the server recheck to
   be stored separately so disagreements can be flagged. No columns for integrity signals (rooted,
   developer options, Play Integrity verdict) or for the plausibility flags (teleport, zero jitter).
5. Tables the specs already require but the schema lacks: password hash + password history (change-password
   rule "not last 10, not within 24h" needs history), device OTP issuance, offer/promotion definitions
   (memo_line.offer_id dangles), human-readable memo number, leave application, TSO visit plan,
   feedback, Superstar / Astha per-outlet program targets, survey question definitions, assessment rubric
   definitions, tutorial videos, in-app update manifest, suggested order quantity per outlet.
6. No runtime configuration table at all. Geofence radius is a bare column on territory_geo_config with no
   zone/outlet override, no effective dating, no audit. Many other tunables are hardcoded in the docs
   (check-out from 17:00, edit reasons, force-sale reasons, photo compression targets, loyalty gift catalog
   and 2 Tk/point rate, password policy, token lifetimes, mock-GPS flag-vs-block policy).

## Spec inconsistencies

7. Submit % has two definitions: web divides by target routes (docs/10), the apps divide by logged-in
   routes (docs/07, docs/08). Pick one canonical KPI (or name them differently).
8. Units are unresolved: memo_line.qty is an integer; STD is in sticks/pieces/dozens; SKUs have pack_size.
   Whether the SR enters packs or sticks decides every volume figure (docs/13 Q8).
9. Login %: docs/04 says downloading the bundle = logged in, but a bundle delta refresh (?since=) should
   not count as a new login; define the event precisely.
10. The day state machine is per route, but attendance is per user, final_submit is per zone, and an SR
    may work more than one route (alternate-day cover). Define the entity each state belongs to.

## Scale observations (for the scale lens to refine)

- 8,500 SRs is not 8,500 continuous connections; it is two daily storms: a morning bundle download
  (~8,500 bundles in roughly an hour) and an evening sync (~1.2 lakh visits + memos across ~8,500 batches
  in a few hours), plus a 17:00 check-out and sales-submit wave. Size for the peaks, not the average.
- Dashboards must never scan transactions; aggregates must be incrementally maintained as batches land,
  including late-arriving batches (a phone syncing days later must re-aggregate the right business_date).
