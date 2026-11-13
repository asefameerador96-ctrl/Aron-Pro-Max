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
| Code | `backend/masterdata/.../loader/MasterCsv.kt` (streaming reader), `MasterScan.kt` (pass 1), `MasterDataLoader.kt` (pass 2), `LoadReport.kt` |
| Input | the workbook saved as CSV, the 32 columns of the profile. Header names are matched by letters and digits only, so the column ORDER and the spelling of separators do not matter; a column the file does not carry is reported once and read as empty. |
| Output | rows in `app.*`, and a report of counts and codes |
| Rules | every mapping rule comes from `ImportRules` — the same object the portal import calls, so the two paths cannot drift |
| Staging | schema `mdload`, created by the tool itself (no migration owns it): `run` and `batch` are small and logged (they make a run resumable), `outlet_row` is UNLOGGED, holds one batch at a time and is purged at the end of the run |

## How it runs

1. **Pass 1, the scan.** Reads the whole file and writes nothing. It decides, in memory, what the file says: the hierarchy (a child with more than one
   parent: the parent on the most rows wins and the case is listed by code), each route's visit plan (the pattern of the most of its outlets that have
   one; `none` when none has one), each route's primary rep (the rep on the most of its rows; a tie goes to the smallest id) and each cluster's type.
   It also counts the data exceptions of the profile. Memory: the hierarchy, 104,876 clusters, 6,954 reps and the set of outlet codes (to catch a code
   that appears twice) — tens of megabytes, never the rows.
2. **Pass 2, the load.** Hierarchy first (wing, division, territory, house, zone, route, cluster), then the outlets in batches of 5,000, then the reps
   and the assignments. Each outlet batch is ONE transaction that stages its rows, creates, updates, writes the pin and placement history, counts, and
   records itself in `mdload.batch` with a fingerprint of the rows it held.

## The promises, and the test that proves each

| Promise | Test |
|---|---|
| A dry run writes nothing and produces the full report (its OUTLET counts are exact; for the hierarchy levels it reports what already exists as "updated" without comparing every column) | `aDryRunWritesNothingAndTheLoadLoadsEveryRowExactlyOnce` |
| Every input row is counted exactly once (created, updated, unchanged, held, error); the report states the equality | the same test, and `LoadReport.text()` prints it |
| Running the same file again changes NOTHING: no row, no version bump, no history row, every row `unchanged` | `loadingTheSameFileAgainChangesNothing` |
| An interrupted run resumes under the same run id, skips the batches that finished and loses no row | `anInterruptedRunIsResumedAndTheResultIsTheSame` |
| A rep id too short for the installed app is HELD, not created (D-MD-24) | `aRepIdTooShortForTheInstalledAppIsHeldNotCreated` |
| The owner's coordinates switch confirms only the pins inside Bangladesh | `theCoordinatesSwitchConfirmsOnlyThePinsInsideBangladesh` |
| No personal data in the report: no outlet name, owner name, rep name or phone number | `assertNoPersonalData` in the same test (names and a phone pattern) |

A batch is skipped on a resume only when its fingerprint matches, so a run resumed over a file that GREW re-applies the batch at that position instead of
skipping rows. Applying a batch again is harmless (it is keyed by code and detects "unchanged"), so skipping is only an optimisation.

## The owner's switches

| Option | Default | What it does |
|---|---|---|
| `coordinatesConfirmed` | false | On: a pin inside Bangladesh loads `location_confirmed` and replaces a pin the field confirmed. Off: such a master pin is recorded as a `candidate` in the location history and the field's pin stays (D-MD-26). |
| `storeOutletsWithoutVisitPlan` | true | The owner's ruling (D-MD-03): the route gets `visit_kind` `none` and its outlets load as usual. They are never planned and never count a target outlet. |
| `unassignedOutletStatus` | `archived` | The status of an outlet whose route has no rep (92,961 rows in the profile), so nobody's coverage counts it. |
| `allowShortRepIds` | false | Normally decided by the server's own rule, `shortRepIdAllowed` (`cfg.release.short_rep_id_version_code`, D-MD-24). |
| `batchSize` | 5,000 | Rows per transaction. |
| `runId` | new | Pass the SAME id to resume an interrupted run. |

## How long the real file would take

**Measured** on synthetic data in this container (4 vCPU, PostgreSQL 16 on the same machine, batch 5,000, the database freshly migrated):

| Rows | First load | Second load (everything unchanged) |
|---|---|---|
| 40,000 | 11.5 to 11.8 s (about 3,400 rows/s) | 3.5 s (about 11,500 rows/s) |
| 200,000 | 55.9 s (3,575 rows/s) | 17.7 s (11,300 rows/s) |

**Estimate for the real file, not a measurement:** at 3,575 rows/s the 737,159 rows are about **3.5 minutes**, and a second run about **1 minute**.
Allow **5 to 10 minutes** in practice: the real file carries 11,325 routes and 104,876 clusters (about 4 and 100 times the 200,000-row sample's), so the
hierarchy phase is bigger, and the final account's managed PostgreSQL has different I/O from this container. The measurement is reproducible with
`ARON_LOADER_BENCH=1 ./gradlew :backend:masterdata:test --tests '*MasterDataLoaderBench*'` (skipped unless that variable is set, so CI never pays for it).

## What is NOT done in push 2

* The **supervisor roster** (AMO, TSO, DMO, Wing Manager and their `user_scope` rows, M14): the loader takes the outlet file only. The roster needs its own
  input from the owner.
* `app.norm_cluster_name` (V0191 on `lane/sr-server`) is not on the integration branch yet: cluster names are matched exactly, as the portal import does.
  When V0191 lands, the loader should match normalised names (one line, plus a test for two names that differ only in case or inner spaces).
* The loader has **no CLI entry point** yet (it is called from Kotlin). The final-account runbook needs one, with the switches above as flags.
* It has never run against the test account, and must not: the workbook is not loaded there (standing rule 1).
