# The master-data bulk loader (lane masterdata-fit, push 2)

The one-time tool that loads the owner's master-data workbook into a fresh database: the hierarchy, the routes and their visit plans, the clusters, the
737,159 outlets, the sales reps and their route assignments. It is a server-side TOOL, not an endpoint. The portal import
(`POST /v1/admin/outlets/import`) stays the path an administrator uses by hand for a sheet of up to 5,000 rows.

**The real workbook is not in this repository and is not loaded into the test account.** It is fleet-sized personal data (owner names, phone numbers, rep
names): standing rules 1 and 8, and `docs/reference/master-data-profile-2026-10.md` s5. Everything below was proved on SYNTHETIC data shaped like the
profile (`SyntheticMaster`), generated in the test from a seed.

## What it is

| | |
|---|---|
| Code | `backend/masterdata/.../loader/`: `MasterCsv.kt` (streaming reader, cell-count check), `NormRow.kt` (the ONE validation and normalisation of a row, used by both passes), `MasterScan.kt` (pass 1), `MasterDataLoader.kt` (pass 2), `LoadReport.kt` |
| Input | the workbook saved as CSV, the 32 columns of the profile. Header names are matched by letters and digits only, so the column ORDER and the separators in the names do not matter; a column the file does not carry is reported once and read as empty. |
| Output | rows in `app.*`, and a report of counts and codes |
| Rules | phones, channels, tiers, geo classes, coordinates, timestamps, visit plans and generated codes come from `ImportRules`, the shapes of codes and the length limits from `MasterRules`: the SAME objects the portal import and the user editor use. `MasterRulesTest` compares `MasterRules` with the live CHECK constraints of the migrated schema, so the rule set cannot drift from the tables. |
| Staging | schema `mdload`, created by the tool itself (no migration owns it): `run` and `batch` are small and logged (they make a run resumable, and bind a run id to its file), `outlet_row` is UNLOGGED, holds one batch at a time and is DROPPED at the end of a finished run (and, after a failed run, at the start of the next one) |
| Guards | **Environment (in code):** the loader refuses to run unless `LoadOptions.env` is `ServerEnv.PROD` (the final account) or `syntheticTestData` is set; there is no default. **One at a time:** a session-level advisory lock; a second loader is refused. **Short rep ids:** `shortRepIdsAllowedForTests` is `internal`: no CLI and no API can reach it; the only way a 3-character id loads is the B2 config gate. |

## How it runs

1. **Pass 1, the scan.** Reads the whole file and writes nothing. Every row goes through `NormRow.of` (the validation below); the rows that pass fold into the
   hierarchy and the route, cluster and rep facts. It hashes the whole file, finds every outlet code that appears on more than one row, and decides, in memory:
   a child with more than one parent (the parent on the most rows wins, the case is listed by code), each route's visit plan (the pattern of the most of its
   outlets that carry one; `none` when none does), each route's primary rep (the rep on the most of its rows; a tie goes to the smallest id) and each
   cluster's type.
2. **Pass 2, the load.** Hierarchy first (wing, division, territory, house, zone, route, cluster) in chunks of 5,000 with one transaction per chunk, then the
   outlets in batches of 5,000 (one transaction each), then the reps and their assignments, again one transaction per chunk. An outlet batch stages its rows,
   counts the rows that cannot be applied BY REASON, creates, updates, writes the pin and placement history, and records itself in `mdload.batch` with a hash
   of every loaded value of its rows.

### What is an error row (counted once, by reason, never "unchanged")

* a record with more or fewer cells than the header;
* a value a table would refuse: a malformed or missing wing, division, territory or zone code, a malformed route or outlet code, a name, owner, cluster,
  cluster type or address longer than its column (the loader never truncates a value silently), no outlet name or cluster;
* **every row of a duplicated outlet code**, the code of the file or the stable generated code of a codeless row: no row of such a code is loaded, so no shop
  can overwrite another (finding L2);
* a row whose zone or cluster does not resolve, whose route does not exist, whose route belongs to another zone than the row's, or whose outlet was merged
  (finding L4). A route stored in another zone than the file says is NEVER moved by the loader (its outlets carry the zone); it is reported by code.

A zone move of an existing outlet is applied as a whole: zone, cluster, route (a row without a route clears the old one) and the placement history.

## The promises, and the test that proves each

| Promise | Test |
|---|---|
| A dry run writes nothing and its report is EXACTLY the commit's (it runs the whole load in one transaction that is rolled back), unchanged versus updated included | `aDryRunWritesNothingAndTheLoadLoadsEveryRowExactlyOnce`, `loadingTheSameFileAgainChangesNothing` |
| Every input row is counted exactly once (created, updated, unchanged, held, error); the report prints the equality | the same tests |
| Running the same file again changes NOTHING: no row, no version bump, no history row | `loadingTheSameFileAgainChangesNothing` |
| A run id is bound to the hash of its file: the same file resumes (finished batches skipped, nothing lost); a different file under the same run id is REFUSED with a message, and a corrected file is a new run (L1) | `aRunIdIsBoundToItsFileAndAResumeLosesNothing` |
| Every row of a duplicated outlet code is held; no winner (L2) | `everyRowOfADuplicatedOutletCodeIsHeldAndNoWinnerIsPicked` |
| A value a table would refuse is a row error that the dry run reports; and when the database refuses a batch anyway, nothing that carries the failure holds a value of the file: no cause, only the class, the SQLSTATE and the constraint name (L3) | `valuesThatATableWouldRefuseAreRowErrorsAndNoFailureCarriesAValueOfTheFile` |
| Route, cluster and zone agree; a zone move is whole; a route of another zone is an error that moves nothing (L4) | `aZoneMoveIsAppliedWholeAndARouteOfAnotherZoneIsAnErrorThatMovesNothing` |
| One loader at a time; a leftover staging table never survives; the environment guard holds | `oneLoaderAtATimeTheStagingTableDoesNotSurviveAndTheEnvironmentGuardHolds` |
| A short or long record is counted as unreadable | `aShortOrLongRecordIsCountedAsUnreadable` |
| A rep never overwrites a user of another role; held cover reps are counted | `aRepNeverOverwritesAUserOfAnotherRoleAndHeldCoversAreCounted` |
| An outlet archived for "route has no rep" is reactivated when the route gets a rep (one closed by hand stays closed) | `anOutletArchivedForNoRepIsReactivatedWhenTheRouteGetsOne` |
| A placement that starts after the business date is corrected in place | `aPlacementThatStartsAfterTheBusinessDateIsCorrectedInPlace` |
| A rep id too short for the installed app is HELD (D-MD-24); the gate opens with the config key and nothing else | `aRepIdTooShortForTheInstalledAppIsHeldNotCreated`, `theShortRepIdGateOpensWithTheConfigKeyAndNothingElse` |
| The owner's coordinates switch confirms only pins inside Bangladesh; outlets of a route with no visit plan are held unless the owner says they are stored | `theCoordinatesSwitchConfirmsOnlyThePinsInsideBangladesh`, `outletsOfARouteWithNoVisitPlanAreHeldWhenTheOwnerHasNotSaidTheyAreStored` |
| No personal data in a report or an exception: no outlet name, owner name, rep name or phone number | `assertNoPersonalData` in the tests above |

## The owner's switches

| Option | Default | What it does |
|---|---|---|
| `env` | none (required) | `ServerEnv.PROD` is the final account. Anything else needs `syntheticTestData`. |
| `coordinatesConfirmed` | false | On: a pin inside Bangladesh loads `location_confirmed` and replaces a pin the field confirmed. Off: such a master pin is recorded as a `candidate` in the location history and the field's pin stays (D-MD-26). |
| `storeOutletsWithoutVisitPlan` | true | The owner's ruling (D-MD-03): the route gets `visit_kind` `none` and its outlets load as usual, never planned, never counting a target outlet. Off: those outlets are HELD. |
| `unassignedOutletStatus` | `archived` | The status of an outlet whose route has no rep (92,961 rows in the profile). It is reactivated when a later file gives the route a rep. |
| `batchSize` | 5,000 | Rows per transaction. |
| `runId` | new | The SAME id and the SAME file resume an interrupted run. |

## How long the real file would take, and how much memory

**Measured** on synthetic data in this container (4 vCPU, PostgreSQL 16 on the same machine, batch 5,000, freshly migrated database):

| Rows | First load | Second load (everything unchanged) |
|---|---|---|
| 200,000 | 97.3 s (2,055 rows/s) | 38.0 s (5,260 rows/s) |

(The push-2 build measured 55.9 s and 17.7 s on the same 200,000 rows. The review round made every row pass the shared validation twice, once in each pass, and
the batch SQL resolve more; the measurement was also taken on a busier container. Treat the two figures as the range.)

**Estimate for the real file, not a measurement:** at 2,055 rows/s the 737,159 rows are about **6 minutes**, a second run about **2.5 minutes**. Allow **8 to 15
minutes**: the real file carries 11,325 routes and 104,876 clusters (the 200,000-row sample has 2,670 and 972), and the final account's PostgreSQL has
different I/O. The measurement is reproducible with `ARON_LOADER_BENCH=1 ./gradlew :backend:masterdata:test --tests '*MasterDataLoaderBench*'` (it does nothing
unless that variable is set, so CI never pays for it; edit the row count in the file, the Gradle test JVM does not forward `ARON_LOADER_BENCH_ROWS`).

**Memory (measured, then extrapolated).** The scan retains 14 MB after 100,000 synthetic rows, 25 MB after 200,000 and 42 MB after 400,000: about **100 bytes
per outlet row**, almost all of it the set of outlet codes that finds duplicates. The real file's 104,876 clusters and 11,325 routes add an estimated 25 MB
and 12 MB (about 250 bytes per cluster and 1 KB per route). **Estimate for the real file: about 120 MB** held by the scan, plus one batch of 5,000 rows. Run the
loader with `-Xmx512m` or more. (The first version of this document said "tens of megabytes", which was a guess.)

## What is NOT done

* The **supervisor roster** (AMO, TSO, DMO, Wing Manager and their `user_scope` rows, M14): the loader takes the outlet file only. The roster needs its own
  input from the owner.
* `app.norm_cluster_name` (V0191 on `lane/sr-server`) is not on the integration branch yet: cluster names are matched exactly, as the portal import does.
  When V0191 lands, the loader should match normalised names (one line, plus a test for two names that differ only in case or inner spaces).
* The loader has **no CLI entry point** yet (it is called from Kotlin). The final-account runbook needs one, with the switches above as flags and
  `ServerEnv` taken from the process settings, never from a flag.
* It has never run against the test account, and must not: the workbook is not loaded there (standing rule 1). The environment guard enforces it.
* A crash in the middle of a COMMIT is not tested (the resume test interrupts between batches); a batch is one transaction, so it commits whole or not at all.
