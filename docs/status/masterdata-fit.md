# HANDOVER (read first; written in MD2's review round, 2026-11-17)

State: the lead's Opus pass found push 1b GOOD (B1, B2, B3 verified) and the bulk loader FAILING on four findings (L1..L4) plus lows and three items from the
push-1b pass. All of them are fixed in this round except item (c), which the lead ruled on (below). One push, every lane ci job.

* **Environment.** Gradle works without --offline. PostgreSQL 16: `pg_ctlcluster 16 main start` (it was DOWN after the container restart). The `aron_test` role needs a
  `host all aron_test 127.0.0.1/32 trust` line at the TOP of `/etc/postgresql/16/main/pg_hba.conf`; the role and database were created as the `postgres` user. Export
  `ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron_test'` in EVERY shell call. `cd web && npm ci --ignore-scripts`, `npm run gen:contract`,
  `npx vitest run`, e2e with `PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test <spec> --trace off`. Gate binaries: `tools/ci/install-tool.sh squawk|oasdiff <dir>`.
  The Gradle daemon keeps the environment it started with, and the Gradle test JVM does not forward `ARON_LOADER_BENCH_ROWS` (edit the row count in the bench file).
* **Item (c), V0199's lock order: ruled by the lead, 2026-11-17.** V0199 stays byte-identical to e18090f1 and V0195..V0200 are frozen. Accepted-risk sentence (D-MD-34): *V0199 holds
  ACCESS EXCLUSIVE on outlet, code_list and outlet_location_history while taking row locks elsewhere under lock_timeout; accepted: dev data is small and the final account migrates an
  empty database; if it is ever applied to a database with production rows, run it in a quiet window.* The reordered file exists (scratchpad `V0199.reordered.sql`: row-lock work first, then ONE
  NOWAIT lock and only the ALTERs, then the inserts that need the widened CHECK, then the COMMENTs) and applies cleanly. The lead's CONDITION for using it: in the FINAL push after the
  integration merge, put it in ONLY IF the migration gate passes with NO allow-list line and NO change to `tools/ci/migrations-check.sh` or its tests (after the merge V0195..V0200 are new
  files relative to the base, so it may); if it needs either, drop (c) for good. No gate change under any option.
* **The loader** is `backend/masterdata/.../loader/`, documented in `docs/reference/master-data-loader.md` (promises with their tests, the switches, the measurements, what is not
  done). Every row is validated once, by `NormRow.of`, in both passes; the shape rules are `MasterRules` (shared with the portal import and the user editor, checked against the live
  CHECKs by `MasterRulesTest`). Its staging schema `mdload` is created by the tool (D-MD-28). It has never run against the real workbook and must not run in the test account: the
  environment guard refuses unless `ServerEnv.PROD` or `syntheticTestData`.
* **Not done**, in the order the lane would pick them up: the supervisor roster (M14) needs an input file from the owner; a CLI entry point for the loader (ServerEnv from the process
  settings, never a flag); matching cluster names with `app.norm_cluster_name` once V0191 lands; the Android change of finding B2 (`docs/requests/contract-1.18-masterdata-fit.md`
  s3 item 4), which the lead assigns to an Android lane; the merge of the integration branch before the final push (the lead's landing order: sr-server, Loyalty C, then this lane).

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

Round correction, found while writing push 2: `importLookups` did not SELECT the two new source-date columns, so the import compared them against null and
reported an identical row as `updated`. Fixed, and the sheet's dates are now compared like any other column: an identical row still changes nothing, and a row
whose Created At or Updated At really changed is stored instead of being dropped (test `aChangedSheetDateIsStoredAndTheSameRowStillChangesNothing`).

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


## Review round after e18090f1 (lead's Opus pass: push 1b passes; the loader failed on L1..L4)

| Finding | Fix | Test (each fails when its fix is removed: I neutered each fix in turn and re-ran) |
|---|---|---|
| **L1** resume under the same run id skipped CHANGED rows (the fingerprint covered row number and code) | the run id is bound to the SHA-256 of the whole file; a different file under it is refused with a message; the batch fingerprint hashes every loaded value | `aRunIdIsBoundToItsFileAndAResumeLosesNothing` |
| **L2** duplicate outlet codes aborted the load or let shop B overwrite shop A | pass 1 finds every duplicated code (generated codes too); every row of such a code is a row error, none wins | `everyRowOfADuplicatedOutletCodeIsHeldAndNoWinnerIsPicked` |
| **L3** file values not checked against the table CHECKs; a database error echoed the row | one shared rule set `MasterRules` (+ `MasterRulesTest` against the live CHECKs) validates every row in pass 1, so the dry run reports them; database errors are replaced at the batch boundary by class, SQLSTATE and constraint, with no cause | `valuesThatATableWouldRefuseAreRowErrorsAndNoFailureCarriesAValueOfTheFile`, `MasterRulesTest` |
| **L4** an update never moved the zone and the route was matched by code only | route, cluster and zone must agree; a zone move is whole; a route of another zone is an error that moves nothing; the loader never moves a route's zone | `aZoneMoveIsAppliedWholeAndARouteOfAnotherZoneIsAnErrorThatMovesNothing` |

Lows: unresolved zone/cluster/route rows are errors counted by reason (never "unchanged"); the dry run is exact (one rolled-back transaction); one advisory lock; hierarchy and reps in
chunks, one transaction each; the memory figure is measured (14 MB at 100,000 rows, 42 MB at 400,000, estimate about 120 MB for the real file) and the throughput re-measured (97 s for
200,000 rows, so about 6 minutes estimated for 737,159, allow 8 to 15); staging dropped at the end and at the next start; short or long CSV records are counted as unreadable; a rep never
overwrites a user of another role; held cover reps are counted; an archived-for-no-rep outlet is reactivated; a placement starting after the business date is corrected in place; the
environment guard is in code; `allowShortRepIds` is internal (only the B2 config gate opens short ids).
Items from the push-1b pass: (a) pickers hide `attrs.retired` items except the held value, lists keep all (web test); (b) sheet timestamps are truncated to microseconds (`OutletImportTest`);
(c) waits for the ruling above.

### What was NOT tested in this round
* The real workbook (never loaded); anything above 200,000 rows; a JVM kill in the middle of a COMMIT; a database holding production rows of another origin.
* Item (c): V0199 is unchanged in the push (the lead's ruling), so its lock order is exactly as the lead reviewed it; the accepted-risk sentence is D-MD-34.
