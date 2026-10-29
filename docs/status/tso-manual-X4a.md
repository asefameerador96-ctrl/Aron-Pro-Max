# Lane X4a: the SR and AMO phones honour the day lock (integration round, R-W-11)

Branch `tso-man/X4a`, worktree `.claude/worktrees/tso-X4a`, base `tso-man/X1` (f4ba4501d). Build contract
`docs/requests/tso-manual-build-2026-10.md` s10 R-W-11; the asks are A3's asks 1, 10 and 11 and D-A3-22
(`docs/status/tso-manual-A3.md`). Nothing pushed, no pull request.

## Done

| item | what | proving test |
|---|---|---|
| 1. CaptureRepository names the record type (A3 ask 10) | `requireOpenRouteDay(meta, recordType)` is `RouteDayLock.requireOpen`: `recordVisitOpen` "visit", `recordSale` "memo", `recordVisitClose` "visit_close", `recordDueCollection` "due_collection" (route only), `recordVisitSkip` "visit_skip". Never typed: `recordDaySubmit`, `recordAmo`, `recordSurveyResponse`, `recordContentView` (and the print ledger does not come through here). `recordNumberedSale` checks the lock before it reserves a number (D-X4a-03). | `core-database` `CaptureRepositoryDayLockTest` (new, 5), `RouteDayLockTest.aLateSalesSubmitIsQueuedAndTheLockedTypesAreRefused` (A3's untyped case rewritten) |

## Decisions

- **D-X4a-01 The type list is B3's.** The phone's `FinalSubmitLock.LOCKED_TYPES` and B3's `IngestService.DAY_LOCKED_TYPES`
  are the same 13 types (checked by eye on this base: visit, visit_close, visit_skip, sale_abort, memo, memo_line,
  memo_discount, qc_line, memo_void, due_collection, stock_movement, redemption, redemption_line). CaptureRepository names
  only the five R-W-11 lists; its other captures keep their untyped Sales Submit check.
- **D-X4a-03 A refused sale takes no memo number.** `recordNumberedSale` refuses a locked sale before `MemoNumbers.reserve`
  (after the replay check, so a sale stored before the lock and replayed after a kill gets its own number back). The
  Sales Submit refusal is unchanged (it is still checked inside `recordSale`).

## Deviations (files outside the lane's list)

- `core-database/.../repo/RouteDayLock.kt` (A3's file): KDoc only, it no longer says CaptureRepository names no type.
- `core-database/src/test/.../RouteDayLockTest.kt` (A3's test): the case "captures that do not name their type never throw
  for the lock" described the state before ask 10; it now asserts the typed refusal and the queued day_submit.

## Asks

None yet.

## Verify (this worktree, laptop, through gl.sh; CI not run)

| command | result |
|---|---|
| `gl.sh … :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*CaptureRepositoryDayLockTest*' --tests '*CaptureRepositoryTest*' --tests '*DayRecordsTest*' --tests '*ContentSurveyTest*' --tests '*AmoRecordsTest*' --tests '*RoomPrintLedgerTest*'` | 60 tests, 1 failed: `CheckerAmoRecordsTest.version5To6KeepsEveryRowAndAddsAmoRecord` (matched by the `*AmoRecordsTest*` filter), the known Windows-path Room MigrationTestHelper failure ("This driver is configured to open a database named 'migration-56' but 'C:\…' was requested"). CaptureRepositoryDayLockTest 5/5, RouteDayLockTest 9/9, CaptureRepositoryTest 9/9, DayRecordsTest 10/10, ContentSurveyTest 6/6, AmoRecordsTest 6/6, RoomPrintLedgerTest 9/9 |

## Not done

Steps 2 to 4 (callers, banners, tests) are in progress.

## Next step

Callers catch the refusal (feature-memo, SR and AMO hosts), then the banners, then the tests.
