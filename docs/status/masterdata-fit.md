# HANDOVER (read first; written at push 1b)

State: push 1b (the lead's Opus findings B1, B2, B3 and lows 1..17) is complete and pushed. Push 2 (bulk loader) is NOT started; MD1's push-2 design below still holds, with these changes: the loader
calls `ImportRules.generatedOutletCode` for codeless rows (not an ordinal scheme of its own), writes the source columns of V0199, and HOLDS an SR/AMO rep id shorter than 4 characters
(`shortRepIdAllowed`, D-MD-24) instead of creating the user. Migration number for the loader's staging schema: **V0201 must be confirmed with the lead** (the earlier note claimed it; the lead's
push-1b message says V0201/V0202 belong to another lane, so ASK BEFORE WRITING THE FILE).

* Environment: gradle works without --offline. PostgreSQL 16: `pg_ctlcluster 16 main start`; this container needed a `host all aron_test 127.0.0.1/32 trust` line added at the TOP of
  `/etc/postgresql/16/main/pg_hba.conf` plus `CREATE ROLE aron_test SUPERUSER LOGIN` and `CREATE DATABASE aron_test OWNER aron_test` as the `postgres` user, then `SELECT pg_reload_conf()`.
  `export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron_test'` in EVERY shell call. `cd web && npm ci --ignore-scripts`, `npm run gen:contract`, `npx vitest run`.
  Gate binaries: `tools/ci/install-tool.sh squawk|oasdiff <dir>` works here.
* All mapping rules stay in `ImportRules.kt`. The importer (`OutletImportApi.kt`) is now PLAN-based: `checkImport` decides per row create / update / unchanged / error and the column -> value map,
  `applyPlan` writes it. The loader must reuse the same shape (plan, then write) so that the two paths cannot drift.
* Push 1b changed `app.outlet` (four `source_*` columns), `app.outlet_location_history.basis` (new value `candidate`), the Silver code-list item (no `valid_to`, `attrs.retired`), and added
  `cfg.release.short_rep_id_version_code`. All in V0199/V0200, which are now SHIPPED-in-this-branch but not merged; after push 1b treat V0195..V0200 as frozen.

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

## Push 1b (the lead's Opus pass on 0dbe2eee0: 3 blocking findings, 17 lows)

Every fix has a test that fails on 0dbe2eee0 (checked by reverting the two main files and re-running: 8 of the 14 import/user tests failed, including every new one).

| Finding | Fix | Test |
|---|---|---|
| **B1** the web nulled an unrepairable phone before sending | the portal sends the sheet's RAW text; the server repairs, warns `phone_unusable`, counts `phone_source_kept` and stores the text in `app.outlet.source_contact_number` (V0199). D-MD-23 | `web/tests/outlet-import.test.ts` "B1: sends the phone text as the sheet has it"; `OutletImportTest.fieldRowsLoadAsTheyAreAndAnIdenticalReRunChangesNothing` |
| **B2** server/portal accepted 3-character SR/AMO ids the installed app throws on | the server REFUSES create/rename below 4 characters until `cfg.release.short_rep_id_version_code` (V0199, default 0) names a release and `cfg.release.min_version_code` has reached it; error `needs_app_release`. The Android request is `docs/requests/contract-1.18-masterdata-fit.md` s3 item 4. D-MD-24 | `AdminUsersRoutesTest.aThreeCharacterRepIdNeedsTheAppReleaseThatAcceptsIt` |
| **B3** a blank channel on an UPDATE overwrote the channel with GT and kept the old tier | GT default and derived channel on CREATE only; blank = keep stored; a channel change needs a consistent tier (`sub_channel_inconsistent`) and clears a tier that no longer belongs (`tier_cleared`). D-MD-25 | `OutletImportTest.aBlankChannelOnAnUpdateKeepsTheStoredChannelAndTier` |

Lows, in the lead's numbering: (1) the sheet's dates go to `source_created_at`/`source_updated_at`, ours are the write time — `fieldRowsLoadAsTheyAre...`; (2) an identical re-import changes nothing
and is reported `unchanged` (the old "version >= 2" assertion is now an equality check of versions and audit counts) — same test; (3) stable `gen.<20 hex>` codes from the row's identifying fields —
`aCodelessRowImportedTwiceIsNotDuplicated`; (4) a dropped tier keeps its text in `source_sub_channel` and is counted — `fieldRowsLoadAsTheyAre...`; (5) a field-confirmed or captured pin is never
overwritten or un-confirmed, the master pin becomes a `candidate` history row (code and KDoc agree; `GeoRecheck` and the outlet detail skip candidates) — `aFieldConfirmedPinIsKeptAndTheMasterPinBecomesACandidate`;
(6) border and island pins (St Martin's, Banglabandha, the western edge, Teknaf) confirm, an out-of-box or unusable pin never costs the row — `borderAndIslandPinsLoadAndABadPinNeverCostsTheRow`
and the web parser's `notes`; (7) `skip rows` now also lets the browser's own bad rows through (`sheet-import.tsx`, `blocked`/`clientSkipped`); (8) Excel Date cells use the Dhaka wall clock
(`excelWallClock`) — web test "(8) an Excel date cell..."; (9) phone inputs accept `+880 1711-000001` (`PHONE_INPUT_MAX`) — `web/tests/phone-input.test.ts`; (10) Silver keeps NO `valid_to`
(`attrs.retired` instead, else the bundle drops the label) — `CodeListsTest`, `MasterDataFitV0199Test`; (11) a conflicting Titanium row is detected and raises, an inactive one is re-activated —
`MasterDataFitV0199Test.aTitaniumOfAnotherChannelIsDetectedNotSkipped`; (12) V0195/V0197/V0199 take their ACCESS EXCLUSIVE locks with the V0144 NOWAIT retry loop —
`MasterDataFitV0199Test.v0197WaitsHoldingNothingWhileASyncWriterHoldsTheMemoTable`; (13) forward COMMENTs for `none` on the app and dw objects, `docs/data-dictionary.md` regenerated; (14) the web
routes page shows the label of `none` (`visitKindKey`) — `web/tests/routes-users.test.ts`; (15) the web mock mirrors the server's answer (`mock/custom.ts` rewritten: unchanged, skipped, warnings,
by_channel, the create-only GT default); (16) a cover assignment of a `none` route is never planned, on any weekday or make-up Friday —
`RoutePlanDbTest.aCoverOfANoVisitPlanRouteIsNeverPlannedToday`; (17) dev clusters typed `market` are mapped to `Bazar Hub` (V0199 + the portal mock seed), any other value outside the six raises a
NOTICE with the query to list them.

Decisions added: D-MD-23..27. Contract stays 1.18.0 (`unchanged`, `skipped`, `by_channel` on the result, the new warning and error codes, `contact_number` as raw text).
