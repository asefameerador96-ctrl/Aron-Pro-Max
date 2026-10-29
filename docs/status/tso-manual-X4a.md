# Lane X4a: the SR and AMO phones honour the day lock (integration round, R-W-11)

Branch `tso-man/X4a`, worktree `.claude/worktrees/tso-X4a`, base `tso-man/X1` (f4ba4501d). Build contract
`docs/requests/tso-manual-build-2026-10.md` s10 R-W-11; the asks are A3's asks 1, 10 and 11 and D-A3-22
(`docs/status/tso-manual-A3.md`). Nothing pushed, no pull request.

## Done

| item | what | proving test |
|---|---|---|
| 1. CaptureRepository names the record type (A3 ask 10) | `requireOpenRouteDay(meta, recordType)` is `RouteDayLock.requireOpen`: `recordVisitOpen` "visit", `recordSale` "memo", `recordVisitClose` "visit_close", `recordDueCollection` "due_collection" (route only), `recordVisitSkip` "visit_skip". Never typed: `recordDaySubmit`, `recordAmo`, `recordSurveyResponse`, `recordContentView` (the print ledger does not come through here). `recordNumberedSale` checks the lock before it reserves a number (D-X4a-03). | `core-database` `CaptureRepositoryDayLockTest` (new, 5), `RouteDayLockTest.aLateSalesSubmitIsQueuedAndTheLockedTypesAreRefused` (A3's untyped case rewritten) |
| 2a. feature-memo: Mark Paid (A3 ask 11) | `MemoViewModel(markPaidLocked = …)`: `MemoUiState.dayLocked` hides Mark Paid (`canMarkPaid` false) while the collection would meet the lock; a Mark Paid tapped before the lock was known catches `RouteDayFinalSubmittedException`, stores nothing and sets `lockRefused` (the host shows the lock text; `dismissLockRefusal`). Reprint is unchanged. | `feature-memo` `MemoDayLockTest` (new, 3: hidden on a final day and reprint allowed; refused with the text and nothing stored; an open day keeps Mark Paid), `MemoViewModelTest` 6/6 unchanged |
| 2b. Memo edit (A3 ask 11) | SR and AMO memo detail: Edit is disabled with the lock text (`editDisabledReason`) when the edit (a new `memo` on the old memo's route today) would be refused (`SrSaleKit.editLocked`, `AmoSaleKit.editLocked`); `EditHost` / `AmoEditHost` do not start an edit on a locked route (no fix taken) and show the lock text for a save the lock refuses. Memo void and return have no screen on the SR or AMO phone (nothing to catch). | the save refusal is the same `SrLockGate.saveAndPrint` / `AmoLockGate.saveAndPrint` chain as the sale (2c) |
| 2c. Sale save in a call open when the lock arrives | `SrSaleKit` / `AmoSaleKit.newSaleViewModel` wrap the commit in `SrLockGate.saveAndPrint` / `AmoLockGate.saveAndPrint`: a refusal sets `lockRefused` and is rethrown, so `SaveAndPrint` stops at SaveFailed before the print step. `SaleHost` / `AmoSaleHost` then show the lock text in place of the sale and its "save failed" dialog; OK ends the call on the phone (SR: `day.endCallIfDayFinal()`, then Home with the banner; AMO: `kit.endCallIfDayFinal()`, then the outlet list). No memo, no lines, no number, no print. | `app-sr` `SrDayLockSaleTest` (new), `app-amo` `AmoDayLockGateTest` (new) |
| 2d. Visit close (A3 ask 11) | `SrSaleKit.closeVisit` starts with `day.endCallIfDayFinal()`; the write goes through `SrLockGate.closeCall`, so a lock that lands between the check and the write ends the call on the phone too (no `visit_close`, nothing thrown). The same for `SrCallLock.abandon` (call prompt No and back), `AmoSaleKit.closeVisit` and `AmoDay.closeAbandoned` (`AmoLockGate.closeCall`). | `SrDayLockSaleTest.aCloseWhoseLockLandsDuringTheWriteEndsTheCallOnThePhone`, `AmoDayLockGateTest.aCloseOrSkipOnTheClosedRouteWritesNothingAndNeverThrows`, A3's `SrDayLockCaptureTest` 4/4 |
| 2e. New calls and skips (A3 ask 11) | SR outlet picker: before reading a fix, `SrSaleKit.callLocked(outlet.routeId)` (the outlet's own route; for the day's route this is `SrDay.finalLocked()`) shows the lock text and stays on the list. SR visit screen: a `CommitFailed` whose route is locked (the lock landed during the fix) shows the lock text and OK goes Home, not the generic retry. AMO `ControlCallHost` (control and joint, and the Sale tile's call): the same on the outlet pick (`day.routeLocked`) and on the check step. Skip: `SrSaleKit` / `AmoSaleKit.skipOutlet` return false when refused; `SkipHost` shows the lock text. | `SrDayLockSaleTest.aSkipAfterTheLockIsRefusedAndNothingIsStored`, `AmoDayLockGateTest`; the committers' refusal is A3's `SrDayLockCaptureTest` / `AmoDayLockCallTest` |
| 2f. Sales Submit | Unchanged: `day_submit` is never typed, so `SalesSubmitViewModel` still submits after the lock (D-X4a-04). | `feature-dayclose` `SalesSubmitDayLockTest` (new: queued through the production writer, the day counts as submitted, DAY_SUBMIT sync asked, no crash), A3's `SrDayLockCaptureTest.salesSubmitAfterTheLockIsQueuedAndNeverCrashes` |
| 2g. Mark Paid hidden on the SR (R-W-11 (3)) | `SrSaleKit.newMemoViewModel(markPaidLocked = { day.finalLocked() })`; AMO: `FinalSubmitLock.refuses(db, day.metaProvider.meta(0L), "due_collection")` (the collection's own meta: the open call's route, none outside a call). Both memo screens refresh on every applied push pull. | `MemoDayLockTest`, `SrDayLockCaptureTest.markPaidAfterTheLockIsRefusedWithTheLockTextAndNeverCrashes` (A3's case rewritten), `AmoDayLockGateTest.markPaidFollowsTheCollectionsRoute` |
| 3. Banners (A3 ask 1) | SR `SrApp.kt` Home: `banner = srHomeBanner(data.downloading) { srDayLockBannerText(day) }` (the download text first, else the lock). AMO `MainActivity.kt`: `AmoApp(banners = amoDayLockBanners(day))`. A3's `amoDayLockText` now reads its words from `AmoDayLockWords` (same strings), so a plain helper test checks them. | `app-sr` `SrDayLockHomeTest` (new compose test, 6: lock on the Home in en and bn, download first, open day shows none, the refusal text and OK in en and bn), `AmoDayLockGateTest.theHomeBannerWordsInEnglishAndBangla`, `theRefusalTextCountsAtLeastTheRefusedRoute` |
| 4. Relaunch | A call on a locked route-day is not restored (A3's `FinalSubmitLock.callToRestore`); after a refused sale a relaunch does not take the rep back into the call, the picker refuses a new one. | `SrDayLockSaleTest.aSaleSavedAfterTheLockArrivedMidCallIsRefusedAndNothingIsStoredOrPrinted` (twice `callToRestore` null), `AmoDayLockGateTest` |

The lock text everywhere is A3's existing strings: SR `sr_lock_title` + `sr_lock_body` with `sr_ok` (`SrDayLockDialog`), AMO
`amo_lock_title` + `amo_lock_body` (today's closed routes, at least one) with `amo_ok` (`AmoDayLockDialog`). No new string.

## Decisions

- **D-X4a-01 The type list is B3's.** The phone's `FinalSubmitLock.LOCKED_TYPES` and B3's `IngestService.DAY_LOCKED_TYPES`
  are the same 13 types on this base (visit, visit_close, visit_skip, sale_abort, memo, memo_line, memo_discount, qc_line,
  memo_void, due_collection, stock_movement, redemption, redemption_line). CaptureRepository names only the five R-W-11
  types; its other captures keep their untyped Sales Submit check (stock, for one, stays as it was).
- **D-X4a-02 The refusal is shown where it is caught, with A3's words.** A dialog with the lock title and body (SR) or the
  AMO title and body with the count of closed routes. A refused sale replaces the sale screen (and its generic "save
  failed" dialog) with the lock text; OK ends the call on the phone only when the route-day is still final (a reopen in
  between leaves the call open).
- **D-X4a-03 A refused sale takes no memo number.** `recordNumberedSale` refuses a locked sale before `MemoNumbers.reserve`
  (after the replay check, so a sale stored before the lock and replayed after a kill gets its own number back). The
  Sales Submit refusal is unchanged (it is still checked inside `recordSale`).
- **D-X4a-04 SalesSubmitViewModel is not changed.** `day_submit` is never typed, so it never meets the lock; a catch there
  would be dead code. The test proves the submit after the lock.
- **D-X4a-05 The SR's new-call check uses the outlet's route.** The visit records `metaProvider.meta(outlet.routeId)`, so the
  picker asks the lock for that route (`SrSaleKit.callLocked`); for the day's route it equals `SrDay.finalLocked()`.
- **D-X4a-06 Mark Paid and Edit follow the push.** The SR and AMO memo screens refresh on `PushRuntime.pulled` (the push
  `day_final_submitted` pulls the delta), so the button goes while the memo is on screen; a tap that is still faster is
  refused with the text.
- **D-X4a-07 No screenshot cases.** The lock strings live in app-sr and app-amo, which ui-screenshots does not depend on;
  adding the dependency would change an existing ui-screenshots file (X4b's). The compose test `SrDayLockHomeTest` covers
  the SR Home and the refusal text; the AMO words are covered by `AmoDayLockGateTest`.

## Deviations (files outside the lane's list)

- `core-database/.../repo/RouteDayLock.kt` (A3's file): KDoc only, it no longer says CaptureRepository names no type.
- `core-database/src/test/.../RouteDayLockTest.kt` and `app-sr/src/test/.../SrDayLockCaptureTest.kt` (A3's tests): the cases
  that described the state before ask 10 (untyped captures queued, Mark Paid queued) now assert the typed refusal.
- `app-sr/.../SrDay.kt` (`SrCallLock.abandon`) and `app-amo/.../AmoDay.kt` (`closeAbandoned`, and `amoDayLockText` /
  `amoDayLockBanners` reading `AmoDayLockWords`): the close write goes through the lock gate; the banner words are unchanged.

## Asks

1. **Lead (seen while wiring the AMO Mark Paid, not the lock, not fixed here):** the AMO's Mark Paid outside a call looks
   like a crash on this base. `AmoSaleKit.newMemoViewModel` builds the collection with `day.metaProvider.meta(0L)` (route =
   `AmoDay.routeId`, null outside a call) and `visitUuidOf` = the outlet's last visit today. A memo sold in a control call
   has such a visit, so `CaptureRepository.recordDueCollection` runs `requireRoute(due.meta)` with a null route and throws
   `IllegalArgumentException` inside `MemoViewModel.markPaid`'s coroutine, which does not catch it. Read from the code
   only; no test ran it. A fix needs a ruling on the route a later AMO collection carries (the visit's route, or no visit).
2. **Lead / device lab:** s8 row 26 ("SR phone shows lock") on a pilot phone against dev, now that the banners are drawn:
   with the TSO's "Submit anyway", the SR's Sales Submit, Mark Paid, a sale saved in an open call and leaving a call do
   not crash; a new call is refused with the lock text; the Home banner shows. The same for the AMO's control call.

## Verify (this worktree, laptop, through gl.sh; CI not run, nothing pushed)

| command | result |
|---|---|
| `gl.sh … :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*CaptureRepositoryDayLockTest*' --tests '*CaptureRepositoryTest*' --tests '*DayRecordsTest*' --tests '*ContentSurveyTest*' --tests '*AmoRecordsTest*' --tests '*RoomPrintLedgerTest*'` (step 1) | 60 tests, 1 failed: `CheckerAmoRecordsTest.version5To6KeepsEveryRowAndAddsAmoRecord` (matched by the filter), the known Windows-path Room MigrationTestHelper failure. CaptureRepositoryDayLockTest 5/5, RouteDayLockTest 9/9, CaptureRepositoryTest 9/9, DayRecordsTest 10/10, ContentSurveyTest 6/6, AmoRecordsTest 6/6, RoomPrintLedgerTest 9/9 |
| `gl.sh … --continue :android:feature-memo:testDebugUnitTest --tests '*MemoDayLockTest*' --tests '*MemoViewModelTest*' :android:feature-dayclose:testDebugUnitTest --tests '*SalesSubmitDayLockTest*' --tests '*SalesSubmitViewModelTest*' :android:app-sr:testDebugUnitTest --tests '*SrDayLock*' :android:app-amo:testDebugUnitTest --tests '*AmoDayLock*'` (run 1) | 2 failures, both in the new tests' own setup: the success case's print source threw on purpose, so a sale that did save went to SaveFailed (`SrDayLockSaleTest.beforeTheLockTheSaleSavesAndAsksToPrint`, `AmoDayLockGateTest.aSaleSavedAfterTheLockArrivedIsRefusedAndAnotherRouteStillSells`). Fixed: the tests load the stored memo through the production memo store, as the kits do, and count the loads. All other cases passed |
| same SR and AMO filters (run 2) | BUILD SUCCESSFUL: SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockSaleTest 4/4, AmoDayLockCallTest 1/1, AmoDayLockGateTest 5/5 |
| **Lane verify** `gl.sh … --continue :android:core-database:testDebugUnitTest :android:feature-memo:testDebugUnitTest :android:feature-dayclose:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:core-ui:testDebugUnitTest --tests '*HardcodedStringScanTest*'` | BUILD FAILED in 1m 5s on two tasks, neither from this lane. **Green:** feature-memo 15 classes, 52 tests, 0 failures (MemoDayLockTest 3/3); feature-dayclose 8 classes, 43 tests, 0 failures (SalesSubmitDayLockTest 1/1); app-sr 17 classes, 55 tests, 0 failures; app-amo 7 classes, 37 tests, 0 failures; app-sr and app-amo debug APKs built. **core-database:** 28 classes, 208 tests, 9 failed, all the known Windows-path Room MigrationTestHelper cases ("This driver is configured to open a database named '…' but 'C:\…' was requested": SchemaMigrationTest 6, Migration67Test, Migration78Test, CheckerAmoRecordsTest); CaptureRepositoryDayLockTest 5/5 and RouteDayLockTest 9/9 green. **HardcodedStringScanTest:** 5 tests, 1 failed with exactly 3 violations, all in `feature-tso/.../leave/LeaveApply.kt:22` ("Casual", "Sick", "Earn"): on the base `tso-man/X1`; the integration branch fixed them in a1b5041b3, which X1 does not contain; feature-tso is not this lane's. No violation in a file of this lane |
| `gl.sh … --continue :android:app-sr:assembleDebug :android:app-sr:testDebugUnitTest` (after the KDoc move in SrApp.kt) | BUILD SUCCESSFUL in 1m 30s: app-sr compiled, 17 classes, 55 tests, 0 failures, debug APK built |

## Goldens needed

None (D-X4a-07: no screenshot case was added).

## Not done

- No screenshot cases (D-X4a-07).
- Device check s8 row 26 on a pilot phone (ask 2): the lock arriving by push on a real phone and printer was not tried.
- The AMO Mark Paid outside a call (ask 1) is reported, not fixed.
- CI did not run; nothing is pushed.

## Next step

The lead merges `tso-man/X4a` with the integration round and answers ask 1; the device lab runs s8 row 26 (ask 2).
