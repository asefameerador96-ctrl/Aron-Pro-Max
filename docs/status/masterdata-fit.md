# HANDOVER (read first; written by MD3, round 3, 2026-11-17)

Round 3 (lead review of e18090f..1cd7892) is built: R1 (resume reports stored counts and issue codes; the report of a resumed run equals an uninterrupted one, hierarchy/rep/assignment
steps included via `mdload.step`), R2 (run id bound to options, message names option names only), and every low (one immutable copy for pass 2; rep home zone from accepted rows only; route of
another zone not renamed/re-planned; differing primary held with a code; unresolved-route pairs are errors; one REPEATABLE READ snapshot per batch with retry on 40001; lock re-check before every
transaction; sub-channel join cannot fan out; held/other-role reps do not give a route a rep; environment from Settings and `syntheticTestData` internal; case-twin and generated-code report lines;
report text says unreadable rows are part of the errors; 'Cluster ' token restored; Silver kept as the portal keeps it). D-MD-35..37. Each fix fails its test when removed (two neuter groups plus single re-runs).
NOT changed, with reasons (D-MD-36/37): dry run keeps one transaction (locks until rollback); malformed record's code is not a duplicate key; PostgreSQL server-log DETAIL is outside the loader.
Environment gotchas: after a container reset recreate role `aron_test` (SUPERUSER CREATEDB) and database, add the pg_hba trust line; in tests JDBI reuses the thread's handle for a nested useHandle, so a "concurrent"
edit must use `fresh.dataSource.connection`. Not done: CLI entry point, supervisor roster (M14), V0199 reorder (final push only, lead's condition), the integration merge (lead says when).

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


## Lead review of the round-2 loader fixes (copied verbatim, 2026-11-17)

# Lead review: masterdata-fit loader fix round (e18090f10..1cd7892d)

Reviewed 2026-11-17 by the lead (Opus pass, two lenses, each blocking finding checked by three refuters). Verdict: FAIL on two blocking findings (resume reporting; resume with changed options). L2, L3, L4 are fixed. Read from code only: no test or database was run.

## correctness verdict=fail
SUMMARY: Read only; I compared e18090f10..1cd7892d on lane/masterdata-fit. The L2 fix holds: every row of a duplicated code, generated codes included, becomes an error and none wins. The L4 fix holds: a zone move is applied whole, and a route of another zone is an error with the route never moved. Hierarchy chunks, reps and assignments are idempotent on re-run, and the progress mark of an outlet batch commits in the same transaction as the batch, so a crash between a commit and its mark cannot happen. L1 is only half fixed, and I mark it blocking for two reasons. (1) A resumed run counts every staged row of a skipped batch as 'unchanged' and drops that batch's error codes, so rows that were never loaded (route in another zone, zone or cluster that does not resolve, merged outlet) are reported as fine (MasterDataLoader.kt:377). The stored per-batch counts are never read back. (2) The run id is bound to the file hash but not to the options: coordinatesConfirmed and businessDate are outside the fingerprint and the stored options are never compared, so a resume with changed options skips batches and reports them unchanged although the new option was never applied to them. The lows cover pass 2 not re-checking the file hash, a rep's home zone moving while his route stays (a partial move), a changed primary rep silently dropped and counted unchanged (pre-existing), a READ COMMITTED race inside a batch, a case-insensitive sub-channel join that can fan out, guard parameters supplied by the caller, and dry-run lock scope. The portal route-code narrowing is safe: the table has carried that exact CHECK since V0002. No migration, gate or CI file changed, no test was loosened, and the fixtures are synthetic. What I could not check: I ran no tests or database. A crash during COMMIT is untested. PostgreSQL server-side logs may still print the failing row's values on a constraint error (outside the app). I did not trace how long scope tokens keep the old zone after a home-zone change. No CLI or job entry point exists yet, so I could not confirm the guard gets its profile from Settings.

### BLOCKING upheld=3/3: A resumed run reports a skipped batch's error rows as 'unchanged' and drops their issue codes
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/loader/MasterDataLoader.kt:377 (skip branch: c.unchanged += batch.size); compare :427-433 and :450-455 (mdload.batch stores created/updated/unchanged/errors but the resume never reads them)
WHY: When a batch is skipped on resume, every staged row in it is counted unchanged. That includes the rows the batch had rejected through RESOLVED: zone_does_not_resolve, cluster_does_not_resolve, route_does_not_resolve, route_is_not_in_the_zone_of_the_row and outlet_was_merged. Those rows were never loaded, and their issue codes and sample codes are missing from the final report. The loader's own rule (line 427, 'never unchanged') is broken, and so is the L4 test's promise 'an error is never counted unchanged', which is only tested on an uninterrupted run. aRunIdIsBoundToItsFileAndAResumeLosesNothing checks only count(app.outlet) and uses a file with no RESOLVED errors, so it cannot catch this. The rows created in a skipped batch are also reported unchanged.
SCENARIO: A 1,200-row synthetic file at batchSize 300. Batch 1 holds 40 rows whose route is stored in another zone, so the first run counts them as errors. The process is killed during batch 3. The operator resumes with the same run id and the same file, which is the documented path, and keeps that report as the final one. It shows outlet error 0 for batch 1, unchanged +300 and no route_is_not_in_the_zone_of_the_row issue. The total still equals rows read, so the run reads as complete while 40 shops were never loaded. Fix: on a skip, add the stored created/updated/unchanged/errors of that mdload.batch row instead of batch.size, and keep the problem codes, either stored per batch or by re-running the RESOLVED problem query read-only.

### BLOCKING upheld=3/3: Resume under the same run id ignores changed options: skipped batches keep the old option and are reported unchanged
WHERE: MasterDataLoader.kt:205-217 (openRun stores options with ON CONFLICT DO NOTHING but compares only file_hash); :387-391 (the fingerprint covers row values and status, not coordinatesConfirmed or businessDate)
WHY: The run id is bound to the file but not to the options that decide what gets written. A resume with coordinatesConfirmed flipped, or on a different business date, skips the finished batches by fingerprint. Those rows are reported unchanged under the new option, but the option was never applied to them. The result is a mixed state with a success report: the same 'resumed run skipped rows that differ' class as L1, moved from the file to the options. (Only storeOutletsWithoutVisitPlan and unassignedOutletStatus change the staged set or status, so only those two are covered.)
SCENARIO: A run with coordinatesConfirmed=false is killed after batch 2. The owner then confirms the coordinates, and the operator resumes with the same run id and the confirmed switch on. Batches 1-2 are skipped as unchanged, so their pins inside Bangladesh stay location_confirmed=false. Batches 3-4 load confirmed. The report says every row was handled under 'confirmed'. A fresh run id would fix the data, but nothing tells the operator one is needed. Fix: refuse a resume whose recorded options (and business date) differ from the current ones, with the same kind of message as the file-hash refusal.

LOWS:
- MasterDataLoader.kt:368 (loadOutlets calls open() again): pass 2 re-reads the file and never checks its hash against the pass-1 fileDigest. If the file is replaced during a long run, pass 2 applies rows pass 1 never scanned: no duplicate check, statuses from the wrong rep facts, possibly two rows of one code in a batch, which aborts on the unique key. So the run id's binding to its file holds for pass 1 only. Fix: hash the pass-2 stream and refuse or flag a mismatch, or load from one immutable copy.
- MasterScan.kt:106 + MasterDataLoader.kt:517-525: a rep's home_zone_id is set from the zone most of his rows name, including rows that pass 2 then rejects as route_is_not_in_the_zone_of_the_row. When a whole route is re-zoned in the file, its outlets and route stay in zone A while the rep's home zone moves to B. That is a partial move, against L4's 'an error that moves nothing', and home_zone_id feeds AmoTeamReads, ConfigScope, ConfigTools and device zone. The upsert at MasterDataLoader.kt:293-297 also still rewrites the name and visit plan of a route stored in another zone.
- MasterDataLoader.kt:551 and :557 (pre-existing, not changed in this delta): when the file names a different primary rep for a route that already has an open primary, nothing is written and the pair is counted 'unchanged' with no issue code. The new rep gets neither primary nor cover. Pairs whose route was never created, because its zone did not resolve, are also counted unchanged rather than as errors.
- MasterDataLoader.kt:430 vs :436-442: problems, the reactivated count, CREATE and UPDATE are separate READ COMMITTED statements that each re-evaluate RESOLVED. A concurrent portal merge or zone or cluster change inside the batch transaction can turn a row into a problem after the problem count was taken. It is then neither created nor updated and is absorbed as 'unchanged' (batch.size - problems - created - updated). This is a race, but it is exactly the 'concurrent portal edit between chunks' case. REPEATABLE READ per batch, or computing every count from one materialised CTE, closes it.
- MasterDataLoader.kt:586: LEFT JOIN app.sub_channel ON lower(code) = lower(r.sub_channel), while app.sub_channel.code is UNIQUE case-sensitive (V0004). If the portal ever holds 'Gold' and 'gold', the staged row fans out to two f rows. A create then aborts the batch on outlet_code's unique key, and an existing outlet gets its problems double-counted.
- Duplicate codes: whitespace variants are caught (MasterCsv.Row.get trims), but case variants ('abc1' vs 'ABC1') are not. That is consistent with app.outlet.code UNIQUE being case-sensitive, but a shop keyed both ways in the workbook loads twice. A malformed record's code never enters duplicate detection (MasterScan.kt:85), so its twin loads. A codeless row's generated code hashes name and owner (ImportRules.generatedOutletCode), so a later file with an edited name creates a second outlet and leaves the first active. All of these are counted and none is silent, but worth a report line.
- MasterDataLoader.kt:417: hasRep uses scan.routes reps, which include held (short-id) and other-role reps, so outlets of such a route load 'active' although no route_assignment is written for that rep. The owner's default for a route with no rep is archived.
- Environment guard (LoadOptions:30-32): env and syntheticTestData are public, caller-supplied parameters. No production entry point (CLI or job) exists yet, so I could not check that a future caller passes Settings.env rather than a literal PROD. The test account runs ARON_ENV=dev (infra/apps.bicep:124/182/441; Settings defaults to dev), so the guard holds if it is wired from Settings. Making syntheticTestData internal, like shortRepIdsAllowedForTests, would remove the self-declared bypass.
- The dry run (MasterDataLoader.kt:122-131) holds one transaction for the whole file. On a fleet file in the final account it keeps row locks on every hierarchy row and outlet it would change until rollback, which blocks portal edits and outlet writes for the duration. It also consumes identity values, which is harmless. The advisory lock, the staging table and mdload.run are correctly released or rolled back.
- The session advisory lock sits on a separate idle connection (MasterDataLoader.kt:97). If a pool, proxy or server idle timeout drops that connection mid-run, the lock is released silently and a second loader could start. A heartbeat, or re-checking pg_advisory_lock ownership before each batch, would close this.
- LoadReport: rowsUnreadable is a subset of the outlet errors (stage() counts a malformed row as an error too), not an additive bucket. The identity line compares outlet.total with rowsRead, which is correct, but a reader who adds errors and unreadable double-counts. Say so in the report text.
- Checked and fine: the portal ROUTE_CODE_RE was narrowed to HIERARCHY_CODE (OutletImportApi.kt:30). app.route.code has carried that same CHECK since V0002 and no migration alters it, so no seeded or live route code can be refused. No db/migrations file changed in e18090f10..1cd7892d, so V0199 is byte-identical. No gate, CI or workflow file changed. Removed test lines were restructured, not loosened. New fixtures use only placeholders ('Shop X', 'Owner X', 01711000001, 01899887766), and there are no real-looking name and phone pairs. L3 sanitising drops the cause and keeps SQLSTATE and constraint only. Zone move: zone, cluster, route (or route cleared) and placement history are applied with the business date; the loader does not touch visits or sales.

## pii-guard-rules verdict=fail
SUMMARY: Verdict: fail, on one blocking item. Read-only review of e18090f10..1cd7892d (one commit, 1cd7892d3).

Repository hygiene is clean:
- `git diff -- db/` is empty, so V0199 is byte-identical and no shipped migration changed.
- No CI or gate script changed (no .github or tools/ci files in the diff).
- DECISIONS.md only appends D-MD-31..34.
- Fixtures and test rows are synthetic ('Shop X', 'Owner X', 01711000001, 01899887766 with 'Blocked Owner'). I saw no real name and phone pair.
- Changed tests are new intent. The one small loosening is the dropped 'Cluster ' leak token (in lows).

Findings L1 to L4:
- **L1 (resume):** fixed for data. The run id is bound to the whole-file SHA-256 and the batch fingerprint hashes every loaded value. Separate issue: a resumed run counts every row of a skipped batch as 'unchanged', including rows that pass 2 refused (merged outlet, zone or cluster that does not resolve, route in another zone). A resumed run can therefore report zero errors for rows that were never loaded. That is the blocking item; mdload.batch already stores the right counts.
- **L2 (duplicate codes):** fixed. Every row of a duplicated code, generated codes included, is an error and none wins.
- **L3 (personal data):** fixed in the loader's own output. Every database failure goes through tx/sanitize, which keeps only class, SQLSTATE and constraint name, with no cause and no message. Report samples are codes only. NormRow error and note strings are fixed codes. LoaderException messages carry run id, hash prefix and lock key only.
- **L4 (zone moves):** fixed. A zone move is applied whole, a route of another zone is an error that moves nothing, and the loader never moves a route.

Guard: it lives in code (MasterDataLoader.kt:88). No route, job or CLI calls the loader, so syntheticTestData cannot be reached from a request. It still trusts the env the caller passes in (in lows).

Rule set: I checked five MasterRules fields against the migrations myself: hierarchy code (V0002), outlet code (V0004:20), route code (V0002:132), route display_label ≤60, and name 1..120. All match. The portal's tighter route-code rule cannot refuse stored routes, because the table CHECK never allowed '.' or '/'.

What I could not check:
- I ran no Gradle, vitest or database tests, so MasterRulesTest and the loader tests were not executed.
- I did not inspect V0197's memo-username CHECK text or the shared TextRules.normalisePhone, which is not in this delta. So the owner rule that phones without a leading zero are accepted is unverified here.
- I did not look at the live test-account database or its server log settings.
- I did not verify which ARON_ENV value the test account's deployment uses beyond seeing it comes from the bicep environmentName.

### BLOCKING upheld=3/3: A resumed run reports the row errors of skipped batches as 'unchanged', so a run can report success while rows were never loaded
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/loader/MasterDataLoader.kt:377 (skip path: `report.batchesSkipped++; c.unchanged += batch.size`), and :430-447 (database-level problems are counted only inside applyBatch). The counts are stored in mdload.batch (created/updated/unchanged/errors, :450-455) but the resume never reads them back (:362-365 reads only the fingerprint).
WHY: The pass-2 problems `zone_does_not_resolve`, `cluster_does_not_resolve`, `route_does_not_resolve`, `route_is_not_in_the_zone_of_the_row` and `outlet_was_merged` are found only when a batch is applied. When the batch is skipped on resume, every row in it, including rows that were refused and are not in app.outlet, is added to `unchanged`. The per-row issue lines for those batches are also missing. The report's check line 'outlet total == rows read' still passes, so the report shows zero errors for rows that were dropped. The interrupted run threw an exception, so the operator never saw the original counts. This is the failure mode the brief calls blocking: 'a run that reports success while rows were lost'. The L1 test (`aRunIdIsBoundToItsFileAndAResumeLosesNothing`, MasterDataLoaderTest.kt:150-160) only checks batchesSkipped/Applied and the outlet total. It never checks the error or unchanged counts of the resumed report, so it cannot catch this.
SCENARIO: A synthetic file of 15,000 rows, batch size 5,000. Batch 1 contains 40 rows whose outlet code is stored as status 'merged' (or rows whose route is stored in another zone). Run 1 applies batch 1: 40 errors. Run 1 is killed during batch 3. Run 2 uses the same run id and the same file, and batch 1 is skipped. Run 2's report shows outlet error = 0, unchanged includes those 40 rows, and it has no `outlet_was_merged` line. The 40 shops are not loaded (or not updated) and nothing in the final report says so. Fix: when a batch is skipped, add its stored created/updated/unchanged/errors from mdload.batch to the report (and keep or re-derive its issue codes). Add an assertion to the resume test that the resumed report's error count equals the uninterrupted run's.

LOWS:
- Environment guard (MasterDataLoader.kt:88): the guard trusts the caller. `env` is passed in by the caller, not read from Settings (ARON_ENV), and `syntheticTestData` is a public constructor parameter. Today it cannot be bypassed: the only callers of MasterDataLoader/LoadOptions are MasterDataLoaderTest and MasterDataLoaderBench, there is no HTTP route, job or CLI, and the flag is not reachable from any request. A future CLI must take env from `Settings.env` and must not expose syntheticTestData as a flag (docs/status already says 'ServerEnv from the process settings, never a flag'). Consider deriving env inside the loader, or making syntheticTestData internal.
- PostgreSQL server log: sanitize() (MasterDataLoader.kt:160-177) correctly drops the message and the cause chain, keeping only class, SQLSTATE and constraint name. But when a CHECK or unique violation does reach the database, PostgreSQL writes 'DETAIL: Failing row contains (...)' with the row's name, owner and phone to its own server log (and to Azure diagnostics if enabled). That is outside the loader's control; validating every row in pass 1 makes it rare. In the final account, check log_error_verbosity and diagnostic settings, or set log_error_verbosity=terse for the loader session.
- Small loosening of the leak check: assertNoPersonalData in MasterDataLoaderTest.kt dropped the forbidden token 'Cluster ' (the old version had it). It added 'Shop ' and 'Blocked'. SyntheticMaster cluster names are 'Cluster <zone>-n', so a future leak of a cluster name would no longer be caught. The current report emits only zone codes for cluster issues, so nothing leaks today. Restore the token.
- Skipped batches also report their created rows as 'unchanged' (same line, :377). This is a miscount, not lost data; the blocking item above has the same fix.
- Resume does not compare the options recorded in mdload.run (coordinatesConfirmed, unassigned status, storeOutletsWithoutVisitPlan) with the current ones. A resume with different switches skips finished batches that were applied under the old switches. Refuse a resume when the options differ.
- Loader and portal differ on an existing outlet that holds the retired Silver tier (this was already true at e18090f10, not introduced here). When the file says 'Silver', RESOLVED gives eff_sub = NULL because sub_active is false, and UPDATE_SQL clears sub_channel_id (MasterDataLoader.kt:600-602, 641). The portal import keeps a stored retired tier (OutletImportApi.kt:244). The text is kept in source_sub_channel, so nothing is lost, but the claim that the two paths cannot drift does not hold for this case.
- Portal route-code rule (OutletImportApi.kt:30, MasterRules.HIERARCHY_CODE): checked, does not refuse live data. app.route.code has had CHECK '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$' since V0002:132, and no later migration changes it, so no stored or seeded route code can contain '.' or '/'. Before this change such codes already failed as unknown_route; now they fail as invalid. Zone and outlet-code regexes are unchanged in meaning, MEMO_USERNAME is the same text, and AdminUsers PHONE_RE is untouched. Not a finding.
- Web (server.ts:52-60, pages.tsx:66-75, outlet-change/page.tsx:39): pickers hide items with attrs.retired except the held value, and lists keep everything. This is correct and tested (checker-v13-stubs.test.ts). Keeping a held Silver in the edit form is safe because AdminOutlets.kt:76 re-checks the tier only when sub_channel_id or channel changes. Timestamp truncation to microseconds (ImportRules.kt:128) is correct and tested (OutletImportTest). Neither is a finding.

