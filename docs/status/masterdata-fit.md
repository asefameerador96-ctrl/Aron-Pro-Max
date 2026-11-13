# HANDOVER (read first; written at push 1)

State: push 1 is complete in the working tree and was pushed with this file. Push 2 (bulk loader) is NOT started. What a fresh context needs:

* Environment: gradle works without --offline (Maven mirror); a local PostgreSQL 16 can be started with `pg_ctlcluster 16 main start`, role `aron_test` (superuser, trust auth on localhost,
  db `aron_test`), `export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron_test'` (re-export in EVERY shell call). Migration files can be applied by hand to a scratch
  database with `psql -1 -v ON_ERROR_STOP=1 -f` in `sort -V` order. `cd web && npm ci --ignore-scripts` then `npm run gen:contract`; Redocly: `npx --yes @redocly/cli@2 lint contract/openapi.yaml --config contract/redocly.yaml`.
* All mapping rules are in `backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/ImportRules.kt` (phone, channel, geo class, pins, timestamps, `visitPlan`). The loader MUST call them (stream the CSV
  in Kotlin, normalise with ImportRules, COPY the normalised rows into `mdload.*` staging tables, then set-based SQL upserts by code). Do not re-implement a rule in SQL.
* Push 2 design decided (see the section below "Push 2 design"). Migration number: V0201 (staging schema `mdload`, UNLOGGED tables keyed by run_id, purged at the end of a run; no personal value in `mdload.issue`).
* `app.norm_cluster_name(text)` (V0191 on `origin/lane/sr-server`) is not on INT yet: when it is, use it for cluster names; until then the loader tests may create it in test setup only if missing.
* Android: no change in this lane; the request file section 3 is the hand-off.

## Push 2 design
* Input: the master CSV (the 32 columns of `docs/reference/master-data-profile-2026-10.md`, header names as there) and an optional supervisor roster CSV (role, username, full name, phone, scope code).
* Order of upserts (all keyed by code, `ON CONFLICT DO UPDATE ... WHERE changed`, so a re-run changes nothing): wing, division, territory, house, zone (+ `house_id`), route (+ visit plan: the most common pattern of
  its outlets that have one, `none` when none), cluster (create if missing, normalised name), outlet (placement and location history rows for new outlets only), SR users (username = lower(rep id), role SR,
  must_change_password, no password hash), route assignments (primary = the rep on more rows of the route, ties by smallest rep id and reported; the others an open-ended `cover`), supervisors + `user_scope`.
* Reported, not guessed: the four hierarchy exceptions (child with two parents: the parent with most outlets is loaded, the case is listed by code), the seven two-rep routes, routes without a rep (no assignment,
  outlets get the status chosen by the `unassigned_outlet_status` parameter, default `archived`, owner default of question 7), routes with more than one pattern or a mix of filled and empty outlets (M04), weekend
  patterns (M19), the 362 tier/channel mismatches (M15), codeless outlet rows (generated `gen.<hash>` code from route + normalised name + ordinal, stable under row order).
* Report: counts per level (read, created, updated, unchanged, skipped) and per error class, NO personal value (codes of wings/zones/routes/outlets are allowed in `mdload.issue`, never names, phones, owners).
* Tests: synthetic CSVs generated in the test (a few hundred rows, all six code shapes, the exceptions above), run twice (second run changes nothing), a bad row is a row error, remove everything the test created.

# Lane masterdata-fit (MD1): status

Branch `lane/masterdata-fit` from INT `4e064555`. Brief: the lead's message of 2026-11-13; rulings D-OWNER-MASTERDATA-01 and D-OWNER-PARITY-02; gaps M01..M22 of `docs/reference/master-data-mapping.md`.
Decisions are `D-MD-nn` in `DECISIONS.md` (nn = gap number). Contract 1.18.0 provisional: `docs/requests/contract-1.18-masterdata-fit.md`.

## Push 1 (rulings: schema, code lists, importer, backend)
| Gap | Where it lands |
|---|---|
| M01 imported pins, `location_confirmed` switch | `OutletImportApi.kt` (`location_confirmed`), `ImportRules.round8/inBangladesh`, D-MD-01 |
| M02 Daily = every selling day | `DayPlan.kindOf` KDoc, `ImportRules.visitPlan`, `ImportRulesTest`, D-MD-02 |
| M03 no visit plan stored | V0195/V0196, `DayPlan.VisitKind.NONE`, `AdminRoutes`, web route editor, D-MD-03 |
| M04 outlet mask | not built (no evidence of mixing); the loader reports mixed routes, D-MD-04 |
| M05 channels | `ImportRules.channel`, importer row error/derive/default, `on_row_error`, D-MD-05 |
| M06 M15 M17 tier = sub-channel | importer writes `sub_channel_id`, mismatch warnings, D-MD-06 |
| M07 Astha tiers | V0199/V0200, docs/19, `CodeListsTest`, ETL, D-MD-07 |
| M08 phones | `ImportRules.phone` on every path, web `phone.ts`, D-MD-08 |
| M12 generated codes | `gen.` prefix, D-MD-12 |
| M13 usernames | V0197/V0198, regexes, D-MD-13 |
| M16 geo class | importer, D-MD-16 |
| M19 weekend patterns | `ImportRules.visitPlan` warning, D-MD-19 |
| M20 created/updated | importer, D-MD-20 |
| M22 cluster types | V0199 code list, seeds, D-MD-22 |

## Push 2 (bulk loader): not started at push 1.
