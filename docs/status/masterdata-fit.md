# HANDOVER (read first; written at push 2)

State: push 1b and push 2 are complete and pushed. What a fresh context needs:

* **Environment.** Gradle works without --offline. PostgreSQL 16: `pg_ctlcluster 16 main start`; this container needed a `host all aron_test 127.0.0.1/32 trust`
  line at the TOP of `/etc/postgresql/16/main/pg_hba.conf`, then `CREATE ROLE aron_test SUPERUSER LOGIN` and `CREATE DATABASE aron_test OWNER aron_test` as the
  `postgres` user and `SELECT pg_reload_conf()`. Export `ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron_test'` in EVERY shell call.
  `cd web && npm ci --ignore-scripts`, `npm run gen:contract`, `npx vitest run`. Gate binaries: `tools/ci/install-tool.sh squawk|oasdiff <dir>`.
  The Gradle daemon keeps the environment it started with, so a new env var for a test needs `./gradlew --stop` first (or hard-code it).
* **Migrations V0195..V0200 are FROZEN now.** Push 1b edited them under the lead's ruling, which needed four lines in `db/migrations/REPLACED-UNAPPLIED.txt`
  (the CI gate compares against the last green run on the branch, where they already existed). Any further schema change in this lane is a NEW migration, and
  the number must be agreed with the lead (V0201/V0202 belong to another lane).
* **The loader** is `backend/masterdata/.../loader/`, documented in `docs/reference/master-data-loader.md` (what it promises, which test proves each promise,
  the owner's switches, the measured throughput and the honest estimate for 737,159 rows). Its staging schema `mdload` is created by the tool, not by a
  migration (D-MD-28). It has never run against the test account and must not.
* **Not done**, in the order the lane would pick them up: the supervisor roster (M14: AMO/TSO/DMO/WM users and their `user_scope` rows) needs an input file from
  the owner; a CLI entry point for the loader; matching cluster names with `app.norm_cluster_name` once V0191 lands on the integration branch; and the Android
  change of finding B2 (`docs/requests/contract-1.18-masterdata-fit.md` s3 item 4), which the lead assigns to an Android lane.

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


## Push 2 (the bulk loader)

Built as MD1's design planned, with the push-1b rules. Full description: `docs/reference/master-data-loader.md`. Decisions D-MD-28, D-MD-29, D-MD-30.

* Two passes: a scan that writes nothing and decides what the file says (hierarchy conflicts, each route's visit plan and primary rep, each cluster's type,
  duplicate outlet codes), then a batched load (5,000 rows per transaction) of hierarchy, outlets, reps and assignments.
* Idempotent (a second run changes nothing and reports every row `unchanged`), resumable (a batch is skipped only when its fingerprint matches), dry-runnable
  (the full report, no write), and every input row is counted exactly once: the report prints the equality against the rows read.
* The owner's switches: coordinates confirmed or not, outlets with no visit plan STORED, the status of outlets on a route with no rep, and the B2 hold
  (a rep id shorter than 4 characters is held with its id in the report, never created).
* Rules are not re-implemented: phones, channels, tiers, geo classes, coordinates, timestamps, visit plans and generated codes all come from `ImportRules`.
* Reports carry counts and OUR codes only: a test asserts that no outlet name, owner name, rep name or phone number can appear in a report.
* Proved on SYNTHETIC data shaped like the profile (`SyntheticMaster`: the same 32 columns, the same code shapes, the same proportions of blanks and the same
  exceptions). **The real workbook was not loaded, here or in the test account.**
* Throughput measured on this container: 200,000 rows in 55.9 s (3,575 rows/s); a second run 17.7 s. **Estimate** for the real 737,159 rows: about 3.5 minutes,
  allow 5 to 10 in practice (the real file has 4x the routes and 100x the clusters of the sample, and the final account's database has different I/O).

### What was NOT tested

* The real workbook: never loaded (standing rules 1 and 8). Only synthetic data of the profile's shape.
* Full fleet size: the largest run was 200,000 rows, not 737,159. The timing for the real file is an estimate from that measurement.
* The supervisor roster and `user_scope` (M14): not built in this push.
* A crash in the middle of a batch transaction: the resume test interrupts BETWEEN batches (a truncated file). A torn batch is covered by the transaction
  itself (it commits whole or not at all) but no test kills a JVM mid-COMMIT.
* The loader against a database that already holds production rows of another origin: it was proved on a freshly migrated database and on its own re-runs.
* No CLI: the loader is called from Kotlin, so the final-account runbook step is not yet exercised end to end.
