# Constant-date offenders in seed-dependent tests (qa #3, 2026-10-25; D-TEST-DATE-PIN-01, D-QA-DATE-PIN-GATE-01)

`tools/ci/date-pin-scan.py` (a step of the Repository gates job) found 106 `2026-MM-DD` literals in 11 test files that use the dev seed (seed 06 rolls from today's Dhaka date). qa fixed nothing: each is a row of the **shrink-only** baseline `tools/ci/date-pin-baseline.txt`. A new literal fails CI.

**What the owning lane does with a row.** Read the test: (a) it asserts something the *seed's clock* decides (a count, a working-day total, the first of the month) -> derive the day from the seed clock (`app.dhaka_date(now())` at load, the way `DataFlowTraceSupport` now does) and delete the row; (b) the literal is a fixed fact of the test (a past `valid_from` floor, a migration test on its own rows that loads the seed only for reference data) -> add `// date-pin-ok: <reason>` on the line or the line above and delete the row. Do not widen the allow-list (`date-pin-allow.txt` holds only the holidays the seed names: 2026-10-21, 2026-12-16, 2026-12-25).

Likely class (qa's reading, not a ruling): `ApiWorld.kt` and `DataFlowTraceSupport.kt` 2026-01-01 are price/assignment `valid_from` floors, so (b); the db `V00xx` tests load `SeedLoader` for reference data and pin their own business dates, probably (b) after a check that no assertion depends on a seed row dated by the clock; `SeedGulshanBananiTest` pins the working-day totals of Oct/Nov/Dec 2026 only where the seed window holds those months (a conditional pin), probably (b).

## backend-app

| File | Literals | Lines |
|---|---|---|
| `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/ApiWorld.kt` | 2026-01-01, 2026-06-01 | 5 |
| `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/DataFlowTraceSupport.kt` | 2026-01-01 | 3 |

## db

| File | Literals | Lines |
|---|---|---|
| `db/src/test/kotlin/com/aktcl/aron/db/BackendCoreAsksV0061Test.kt` | 2026-10-08 | 15 |
| `db/src/test/kotlin/com/aktcl/aron/db/ClientErrorMfaKindsV0069Test.kt` | 2026-10-08, 2026-10-09 | 6 |
| `db/src/test/kotlin/com/aktcl/aron/db/DuesAdjustmentSkuImageV0074Test.kt` | 2026-10-15 | 1 |
| `db/src/test/kotlin/com/aktcl/aron/db/DwEnteredBySupervisorDayV0082Test.kt` | 2026-10-05, 2026-10-06 | 7 |
| `db/src/test/kotlin/com/aktcl/aron/db/EntrySourceV0077Test.kt` | 2026-10-05, 2026-10-08 | 6 |
| `db/src/test/kotlin/com/aktcl/aron/db/OwnerDirectiveV0087Test.kt` | 2026-01-01, 2026-10-01, 2026-10-05, 2026-10-06, 2026-10-20, 2026-11-01, 2026-11-02, 2026-12-01, 2026-12-17, 2026-12-20, 2026-12-31 | 51 |
| `db/src/test/kotlin/com/aktcl/aron/db/SeedGulshanBananiTest.kt` | 2026-10-01, 2026-11-01, 2026-12-01 | 3 |
| `db/src/test/kotlin/com/aktcl/aron/db/SeedTest.kt` | 2026-10-07 | 1 |
| `db/src/test/kotlin/com/aktcl/aron/db/VisitSupersedeV0081Test.kt` | 2026-10-05 | 8 |
