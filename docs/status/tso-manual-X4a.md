# Lane X4a: the SR and AMO phones honour the day lock (integration round, R-W-11)

Branch `tso-man/X4a`, worktree `.claude/worktrees/tso-X4a`, base `tso-man/X1` (f4ba4501d). Build contract
`docs/requests/tso-manual-build-2026-10.md` s10 R-W-11; the asks are A3's asks 1, 10 and 11 and D-A3-22
(`docs/status/tso-manual-A3.md`). Nothing pushed, no pull request. The fix round after the checker's report on
5b11e9307 is in "Fix round" below; the rows it changed say so.

## Done

| item | what | proving test |
|---|---|---|
| 1. CaptureRepository names the record type (A3 ask 10) | `requireOpenRouteDay(meta, recordType)` is `RouteDayLock.requireOpen`: `recordVisitOpen` "visit", `recordSale` "memo", `recordVisitClose` "visit_close", `recordDueCollection` "due_collection" (route only), `recordVisitSkip` "visit_skip". Never typed: `recordDaySubmit`, `recordAmo`, `recordSurveyResponse`, `recordContentView` (the print ledger does not come through here). `recordNumberedSale` checks the lock before it reserves a number (D-X4a-03). | `core-database` `CaptureRepositoryDayLockTest` (new, 5; 6 with the fix round's `acceptsDueCollection` case), `RouteDayLockTest.aLateSalesSubmitIsQueuedAndTheLockedTypesAreRefused` (A3's untyped case rewritten) |
| 2a. feature-memo: Mark Paid (A3 ask 11; fix round) | `MemoViewModel`: `MemoUiState.dayLocked` hides Mark Paid (`canMarkPaid` false) while the selected memo's collection would be refused; the check is per memo and by default the writer's own (`markPaidLocked = { m -> !dues.accepts(m.outletId) }`, `DueCollectionWriter.accepts`). A Mark Paid tapped before the lock was known stores nothing: `RouteDayFinalSubmittedException` sets `lockRefused` (the lock text), `RouteDaySubmittedException` sets `submitRefused` ("Mark paid" + "Today's sales are already submitted."), any other failure leaves the due and the button; nothing leaves the scope. The refusal is written with the reload in one state write. Reprint is unchanged. | `feature-memo` `MemoDayLockTest` (6: hidden on a final day and reprint allowed; refused with the text and nothing stored; an open day keeps Mark Paid and collects on the visit's route; the refusal is never shown while Mark Paid is still drawn; Sales Submit hides it and a tap it beat is worded; a caller with no route collects on the visit's route), `MemoViewModelTest` 6/6 unchanged |
| 2b. Memo edit (A3 ask 11) | SR and AMO memo detail: Edit is disabled with the lock text (`editDisabledReason`) when the edit (a new `memo` on the old memo's route today) would be refused (`SrSaleKit.editLocked`, `AmoSaleKit.editLocked`); `EditHost` / `AmoEditHost` do not start an edit on a locked route (no fix taken) and show the lock text for a save the lock refuses. Memo void and return have no screen on the SR or AMO phone (nothing to catch). | the save refusal is the same `SrLockGate.saveAndPrint` / `AmoLockGate.saveAndPrint` chain as the sale (2c) |
| 2c. Sale save in a call open when the lock arrives (fix round: keyed) | `SrSaleKit` / `AmoSaleKit.newSaleViewModel` wrap the commit in `SrLockGate.saveAndPrint` / `AmoLockGate.saveAndPrint`: a refusal records the refused draft's key (`refusalKey`: the call's visit uuid, or the edited memo's uuid) in `lockRefused` and is rethrown, so `SaveAndPrint` stops at SaveFailed before the print step. `SaleHost` / `AmoSaleHost` show the lock text, for their own call only, in place of the sale and its "save failed" dialog; OK runs `afterSaleRefusal`: the call ends on the phone when its route is still final (SR: then Home with the banner; AMO: then the outlet list, else the call menu) and the refusal is cleared last. No memo, no lines, no number, no print. | `app-sr` `SrDayLockSaleTest`, `SrKitLockTest.aRefusedSaleBelongsToItsCallAndOkEndsTheCallBeforeTheRefusalClears`, `SrDayLockHostsTest` (2 sale-host cases); `app-amo` `AmoDayLockGateTest`, `AmoKitLockTest` (same case), `AmoDayLockHostsTest` (2 sale-host cases) |
| 2d. Visit close (A3 ask 11) | `SrSaleKit.closeVisit` starts with `day.endCallIfDayFinal()`; the write goes through `SrLockGate.closeCall`, so a lock that lands between the check and the write ends the call on the phone too (no `visit_close`, nothing thrown). The same for `SrCallLock.abandon` (call prompt No and back), `AmoSaleKit.closeVisit` and `AmoDay.closeAbandoned` (`AmoLockGate.closeCall`). | `SrDayLockSaleTest.aCloseWhoseLockLandsDuringTheWriteEndsTheCallOnThePhone`, `AmoDayLockGateTest.aCloseOrSkipOnTheClosedRouteWritesNothingAndNeverThrows`, A3's `SrDayLockCaptureTest` 4/4; fix round, on the real kits: `SrKitLockTest.closeVisitOnAFinalRouteEndsTheCallWithNoCloseAndNeverThrows`, `AmoKitLockTest.theClosesOnAFinalRouteEndTheCallWithNoCloseAndNeverThrow` (kit close and `AmoDay.closeVisit`) |
| 2e. New calls and skips (A3 ask 11; fix round: helpers) | SR outlet picker: `SrSaleKit.startCall(outlet)` returns false before reading a fix when `callLocked(outlet.routeId)` (the outlet's own route); the picker shows the lock text and stays on the list. SR visit screen: `SrSaleKit.refusedByLock(state)` (a `CommitFailed` whose route is locked: the lock landed during the fix) shows the lock text and OK goes Home, not the generic retry. AMO `ControlCallHost` (control and joint, and the Sale tile's call): `AmoDay.openCall(outlet, kind)` on the outlet pick (no fix, no route taken) and `AmoDay.refusedByLock(state)` on the check step (both in `AmoDayLockGate.kt`). Skip: `SrSaleKit` / `AmoSaleKit.skipOutlet` return false when refused; `SkipHost` shows the lock text. | `SrDayLockSaleTest.aSkipAfterTheLockIsRefusedAndNothingIsStored`, `AmoDayLockGateTest`, A3's `SrDayLockCaptureTest` / `AmoDayLockCallTest`; fix round on the real kits: `SrKitLockTest.thePickerRefusesANewCallOnAFinalRouteBeforeReadingAFix`, `aLockThatLandsWhileTheFixIsReadIsShownAsTheLockNotARetry`, `theEditCheckAndTheSkipMeetTheLockOnTheirRoute`, `SrDayLockHostsTest.theSkipHostWordsARefusedSkipAndStoresNothing`; `AmoKitLockTest.theOutletPickRefusesACallOnAFinalRouteBeforeReadingAFix`, `aLockThatLandsWhileTheFixIsReadIsShownAsTheLockNotARetry`, `theEditCheckAndTheSkipMeetTheLockOnTheirRoute` |
| 2f. Sales Submit | Unchanged: `day_submit` is never typed, so `SalesSubmitViewModel` still submits after the lock (D-X4a-04). | `feature-dayclose` `SalesSubmitDayLockTest` (new: queued through the production writer, the day counts as submitted, DAY_SUBMIT sync asked, no crash), A3's `SrDayLockCaptureTest.salesSubmitAfterTheLockIsQueuedAndNeverCrashes` |
| 2g. Mark Paid hidden on the SR and the AMO (R-W-11 (3); fix round) | Both kits build the memo screen with `VisitDueCollectionWriter` (feature-memo): a collection attaches to the outlet's last visit today and carries THAT visit's route (D-X4a-08), and `accepts` asks `CaptureRepository.acceptsDueCollection` with the very meta the write uses (the Final Submit lock and a standing Sales Submit, route-day or the AMO's supervisor day). So Mark Paid is hidden on the SR after the lock and after the SR's own Sales Submit, and on the AMO outside a call (no route of its own) when the memo's call route is final or the supervisor day is submitted. Both memo screens refresh on every applied push pull. | `SrKitLockTest` (Mark Paid: 3), `AmoKitLockTest` (Mark Paid: 3), `MemoDayLockTest`, `CaptureRepositoryDayLockTest.acceptsDueCollectionMatchesTheWriteForSalesSubmitAndFinalSubmit`, `SrDayLockCaptureTest.markPaidAfterTheLockIsRefusedWithTheLockTextAndNeverCrashes` |
| 3. Banners (A3 ask 1) | SR `SrApp.kt` Home: `banner = srHomeBanner(data.downloading) { srDayLockBannerText(day) }` (the download text first, else the lock). AMO `MainActivity.kt`: `AmoApp(banners = amoDayLockBanners(day))`. A3's `amoDayLockText` now reads its words from `AmoDayLockWords` (same strings), so a plain helper test checks them. | `app-sr` `SrDayLockHomeTest` (new compose test, 6: lock on the Home in en and bn, download first, open day shows none, the refusal text and OK in en and bn), `AmoDayLockGateTest.theHomeBannerWordsInEnglishAndBangla`, `theRefusalTextCountsAtLeastTheRefusedRoute` |
| 4. Relaunch | A call on a locked route-day is not restored (A3's `FinalSubmitLock.callToRestore`); after a refused sale a relaunch does not take the rep back into the call, the picker refuses a new one. | `SrDayLockSaleTest.aSaleSavedAfterTheLockArrivedMidCallIsRefusedAndNothingIsStoredOrPrinted` (twice `callToRestore` null), `AmoDayLockGateTest` |

The lock text everywhere is A3's existing strings: SR `sr_lock_title` + `sr_lock_body` with `sr_ok` (`SrDayLockDialog`), AMO
`amo_lock_title` + `amo_lock_body` (today's closed routes, at least one) with `amo_ok` (`AmoDayLockDialog`). A Mark Paid refused
by a Sales Submit (fix round) says feature-memo's `memo_mark_paid` and feature-dayclose's `submit_blocked_done` with `sr_ok` /
`amo_ok` (`SrMarkPaidSubmittedDialog`, `AmoMarkPaidSubmittedDialog`). No new string.

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
  refused with the text. Fix round: the Edit check re-runs on every applied pull (both hosts) and, on the AMO, on every
  change of `day.dayLock` (the banner refreshes it on each pull), not on Mark Paid's own state. A lock learned at the next
  sync answer (no push) shows when the memo screen is opened or refreshed; a tap before that is refused with the text.
- **D-X4a-07 No screenshot cases.** The lock strings live in app-sr and app-amo, which ui-screenshots does not depend on;
  adding the dependency would change an existing ui-screenshots file (X4b's). The compose test `SrDayLockHomeTest` covers
  the SR Home and the refusal text; the AMO words are covered by `AmoDayLockGateTest`.

- **D-X4a-08 (fix round) A collection carries the route of the visit it attaches to.** `VisitDueCollectionWriter` takes the
  outlet's last visit today (as the kits did) and its route; with no visit of the outlet today, the caller's own route
  (`metaFor(null)`: the SR's day route, the AMO's open call route or none) and no visit, as before. On the AMO this ends the
  crash of checker high 1 (a routeless collection with a visit). On the SR the route is the same as before whenever the
  visit is on the day's route; a visit on another route of the SR's bundle (the picker lists every route's outlets when one
  route is planned) now gets a collection on that route, consistent with its visit family.
- **D-X4a-09 (fix round) Mark Paid's check is the writer's.** `DueCollectionWriter.accepts(outletId)` (default true, so the
  fakes and other callers are unchanged) and `CaptureRepository.acceptsDueCollection(meta)` (the same rule as
  `recordDueCollection`: routeless never checked, else `RouteDayLock.isOpen(meta, "due_collection")`), so the button and
  the write cannot disagree. `MemoViewModel.markPaidLocked` stays as a parameter, now per memo, defaulting to it.
- **D-X4a-10 (fix round) The Sales Submit refusal is named.** `RouteDaySubmittedException` (RouteDayLock.kt) is the same
  IllegalStateException with the same message, so every `assertThrows(IllegalStateException)` holds; Mark Paid words it with
  existing strings. Any other failure of a Mark Paid write stores nothing (one transaction) and is caught without words: the
  due and the button stay, so the rep sees nothing was collected (no new string for an error we have not seen).
- **D-X4a-11 (fix round) A refused save belongs to its draft.** The kits keep the refused sale's or edit's key, not a flag;
  a stale refusal of another call never shows on a valid call. OK on the lock text ends the call and navigates before the
  refusal is cleared, so the sale's generic "save failed" is never drawn in between.
- **D-X4a-12 (fix round) Host decisions are kit or day helpers, tested on a real day.** `SrSaleKit.startCall`,
  `refusedByLock`, `afterSaleRefusal`; `AmoDay.openCall`, `AmoDay.refusedByLock` (`AmoDayLockGate.kt`), `AmoSaleKit.afterSaleRefusal`.
  The tests build a real `SrDay` / `AmoDay` on an in-memory database (`SrLockFixture`, `AmoLockFixture`: plain cipher and
  verifier, a counting location source, no printer; nothing reaches the network) and compose the SR sale, edit and skip
  hosts and the AMO sale, edit and memo hosts. ControlCallHost and the SR picker composable themselves are not composed;
  their decisions are the helpers.

## Deviations (files outside the lane's list)

- `core-database/.../repo/RouteDayLock.kt` (A3's file): KDoc only, it no longer says CaptureRepository names no type.
- `core-database/src/test/.../RouteDayLockTest.kt` and `app-sr/src/test/.../SrDayLockCaptureTest.kt` (A3's tests): the cases
  that described the state before ask 10 (untyped captures queued, Mark Paid queued) now assert the typed refusal.
- `app-sr/.../SrDay.kt` (`SrCallLock.abandon`) and `app-amo/.../AmoDay.kt` (`closeAbandoned`, and `amoDayLockText` /
  `amoDayLockBanners` reading `AmoDayLockWords`): the close write goes through the lock gate; the banner words are unchanged.
- Fix round: `core-database/.../repo/RouteDayLock.kt` (A3's file) gains `RouteDaySubmittedException` and throws it where it
  threw a plain `check` failure (same type family, same message). `feature-memo/.../RoomMemoAdapters.kt` gains
  `VisitDueCollectionWriter` and `RoomDueCollectionWriter.accepts`. `app-amo/build.gradle.kts`: test-only dependencies
  `kotlinx-coroutines-test` and the compose BOM with `ui-test-junit4`, as `app-sr` has them (no test manifest).
  `app-amo/src/test/.../AmoDayLockGateTest.kt`: the old Mark Paid case is renamed and says only what it proves.

## Asks

1. **Lead: answered in the fix round (checker high 1).** The AMO Mark Paid crash outside a call is fixed by D-X4a-08 (the
   collection carries its visit's route). If the lead rules instead that a later AMO collection carries no visit, the
   change is one line in `VisitDueCollectionWriter.target`; tell me.
2. **Lead / device lab:** s8 row 26 ("SR phone shows lock") on a pilot phone against dev, now that the banners are drawn:
   with the TSO's "Submit anyway", the SR's Sales Submit, Mark Paid, a sale saved in an open call and leaving a call do
   not crash; a new call is refused with the lock text; the Home banner shows. The same for the AMO's control call.
   **The offline window to check and accept:** the phone refuses only after it has learned the lock (the push's pull, or
   the next sync answer's `day_states`). A sale saved offline after the server's `final_submitted_at` but before the
   phone learns of it is stored and printed, and the server then refuses it `day_final_submitted` (the Home banner counts
   it under "not accepted"). This is designed and tested on the laptop only; no device has run it.
3. **Lead (checker low, not ruled by R-W-11, not changed here): stock after Final Submit.** `stock_movement` is in B3's
   DAY_LOCKED_TYPES, but `CaptureRepository.recordStock` checks no lock at all (D-X4a-01). A stock save after the Final
   Submit is queued (and may print a slip), the server refuses it `day_final_submitted`, and the rep is told only by the
   banner's "not accepted" count. Ruling wanted: name `stock_movement` in `recordStock` with the lock text on the SR and
   AMO stock screens, or have the server accept stock after the lock.
4. **Lead (seen in the fix round, not the Final Submit lock, not changed here):** a skip from the SR outlet list after the
   SR's own Sales Submit throws `RouteDaySubmittedException` out of `SkipHost`'s coroutine (`SrLockGate.skip` and
   `AmoLockGate.skip` catch only the Final Submit refusal). The same was true on X1 before this lane (a plain
   IllegalStateException). Read from the code; no test ran it. A fix needs words for "the day is submitted" on the skip
   list (feature-dayclose's `submit_blocked_done` would do).

## Fix round (checker report on 5b11e9307)

Every high and medium defect is fixed, and every low one but the stock ruling (ask 3). Each fix has a test that fails
without it: I undid the production changes on the laptop (kits back to the old Mark Paid wiring, the old refusal order in
MemoViewModel, the hosts' refusal flag unkeyed, the AMO Edit check keyed as before, OK clearing first), ran the new tests
and saw them fail, then restored the fixes (Verify, row "negative check").

| # | checker defect | fix | test that fails without it |
|---|---|---|---|
| H1 | AMO Mark Paid crashes (routeless meta with a visit; IllegalArgumentException out of `markPaid`) and is never hidden on a final route | `VisitDueCollectionWriter`: the collection carries the route of the visit it attaches to (D-X4a-08); Mark Paid's check is the writer's `accepts`, per memo (D-X4a-09); `MemoViewModel.markPaid` lets nothing out of its scope | `AmoKitLockTest.markPaidOutsideACallCollectsOnTheCallsRouteAndNeverCrashes`, `markPaidOutsideACallIsHiddenWhenTheMemosRouteIsFinalAndATapItBeatIsWorded`, `theSupervisorDaySubmitHidesMarkPaidAndATapItBeatIsWorded` (real `AmoDay`, the kit's own `newMemoViewModel`); `MemoDayLockTest.aCallerWithNoRouteCollectsOnTheVisitsRoute` |
| H2 | SR Mark Paid after the SR's own Sales Submit crashes (plain IllegalStateException) and stays drawn | `RouteDaySubmittedException` (D-X4a-10); `CaptureRepository.acceptsDueCollection` runs the full route-day check; `markPaid` words it (`submitRefused`, `SrMarkPaidSubmittedDialog` / `AmoMarkPaidSubmittedDialog`, existing strings) | `SrKitLockTest.markPaidIsHiddenAfterTheSrsOwnSalesSubmitAndATapItBeatIsWordedNotACrash`, `MemoDayLockTest.aSalesSubmitHidesMarkPaidAndATapItBeatIsWordedNotACrash`, `CaptureRepositoryDayLockTest.acceptsDueCollectionMatchesTheWriteForSalesSubmitAndFinalSubmit` |
| M1 | flaky `MemoDayLockTest:129` (refusal written before the reload's reads) | the refusal is written with the reload in one state write (`reloadLocked(selected, also)`); `SrDayLockCaptureTest`'s Mark Paid case has the same cause and the same fix | `MemoDayLockTest.theRefusalIsNeverShownWhileMarkPaidIsStillDrawn`: a store that looks at the state on every re-read sees the old order on every run, not only under load |
| M2 | host wiring of R-W-11 (2) and (4) unproven | host decisions moved to kit or day helpers (D-X4a-12) and tested on a real `SrDay` / `AmoDay`; the SR sale, edit and skip hosts and the AMO sale, edit and memo hosts composed | `SrKitLockTest` (8), `SrDayLockHostsTest` (4), `AmoKitLockTest` (8), `AmoDayLockHostsTest` (4): picker and outlet pick refuse before a fix; a lock that lands while the fix is read shows the lock text (`refusedByLock`); `SrSaleKit.closeVisit`, `AmoSaleKit.closeVisit` and `AmoDay.closeVisit` on a final route write no `visit_close` and end the call; `callLocked`, `editLocked`, `skipOutlet` on the real kits; the sale host's OK ends the call; the edit host reads no fix |
| L1 | one `lockRefused` flag per kit; OK cleared it before ending the call | the refused draft's key (D-X4a-11); OK ends the call and navigates, then clears (`afterSaleRefusal`; the edit hosts likewise) | `SrDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall`, `AmoDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall`, `SrKitLockTest` / `AmoKitLockTest.aRefusedSaleBelongsToItsCallAndOkEndsTheCallBeforeTheRefusalClears` |
| L2 | AMO Edit not re-checked when the lock lands (D-X4a-06 untrue on the AMO) | both memo hosts re-check Edit on every applied push pull; the AMO host also on every change of `day.dayLock` | `AmoDayLockHostsTest.editOnTheMemoDetailFollowsTheLockThatLandsWhileItIsOnScreen` (the SR host's pull counter is not test-proven: `PushRuntime.pulledNow` is internal to core-sync) |
| L3 | the status promised more than the phone can do; reprint proven only with a fake | the offline window is stated in ask 2 and Not done; the reprint goes through the real chain | `SrKitLockTest.markPaidIsHiddenOnAFinalDayAndAReprintStillGoesToThePrintLedger`: `MemoPrintingReprinter(day.printing)` with the day's `RoomPrintLedger` after the lock: not refused, one `print_event` queued (no printer is saved, so the attempt is `Failed` and the event says so; a real paper print is the device check) |
| L4 | stock after Final Submit not raised | not changed (R-W-11 does not name stock, D-X4a-01); raised as ask 3 | none (no code change) |

## Verify (this worktree, laptop, through gl.sh; CI not run, nothing pushed)

| command | result |
|---|---|
| `gl.sh … :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*CaptureRepositoryDayLockTest*' --tests '*CaptureRepositoryTest*' --tests '*DayRecordsTest*' --tests '*ContentSurveyTest*' --tests '*AmoRecordsTest*' --tests '*RoomPrintLedgerTest*'` (step 1) | 60 tests, 1 failed: `CheckerAmoRecordsTest.version5To6KeepsEveryRowAndAddsAmoRecord` (matched by the filter), the known Windows-path Room MigrationTestHelper failure. CaptureRepositoryDayLockTest 5/5, RouteDayLockTest 9/9, CaptureRepositoryTest 9/9, DayRecordsTest 10/10, ContentSurveyTest 6/6, AmoRecordsTest 6/6, RoomPrintLedgerTest 9/9 |
| `gl.sh … --continue :android:feature-memo:testDebugUnitTest --tests '*MemoDayLockTest*' --tests '*MemoViewModelTest*' :android:feature-dayclose:testDebugUnitTest --tests '*SalesSubmitDayLockTest*' --tests '*SalesSubmitViewModelTest*' :android:app-sr:testDebugUnitTest --tests '*SrDayLock*' :android:app-amo:testDebugUnitTest --tests '*AmoDayLock*'` (run 1) | 2 failures, both in the new tests' own setup: the success case's print source threw on purpose, so a sale that did save went to SaveFailed (`SrDayLockSaleTest.beforeTheLockTheSaleSavesAndAsksToPrint`, `AmoDayLockGateTest.aSaleSavedAfterTheLockArrivedIsRefusedAndAnotherRouteStillSells`). Fixed: the tests load the stored memo through the production memo store, as the kits do, and count the loads. All other cases passed |
| same SR and AMO filters (run 2) | BUILD SUCCESSFUL: SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockSaleTest 4/4, AmoDayLockCallTest 1/1, AmoDayLockGateTest 5/5 |
| **Lane verify** `gl.sh … --continue :android:core-database:testDebugUnitTest :android:feature-memo:testDebugUnitTest :android:feature-dayclose:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:core-ui:testDebugUnitTest --tests '*HardcodedStringScanTest*'` | BUILD FAILED in 1m 5s on two tasks, neither from this lane. **Green:** feature-memo 15 classes, 52 tests, 0 failures (MemoDayLockTest 3/3); feature-dayclose 8 classes, 43 tests, 0 failures (SalesSubmitDayLockTest 1/1); app-sr 17 classes, 55 tests, 0 failures; app-amo 7 classes, 37 tests, 0 failures; app-sr and app-amo debug APKs built. **core-database:** 28 classes, 208 tests, 9 failed, all the known Windows-path Room MigrationTestHelper cases ("This driver is configured to open a database named '…' but 'C:\…' was requested": SchemaMigrationTest 6, Migration67Test, Migration78Test, CheckerAmoRecordsTest); CaptureRepositoryDayLockTest 5/5 and RouteDayLockTest 9/9 green. **HardcodedStringScanTest:** 5 tests, 1 failed with exactly 3 violations, all in `feature-tso/.../leave/LeaveApply.kt:22` ("Casual", "Sick", "Earn"): on the base `tso-man/X1`; the integration branch fixed them in a1b5041b3, which X1 does not contain; feature-tso is not this lane's. No violation in a file of this lane |
| `gl.sh … --continue :android:app-sr:assembleDebug :android:app-sr:testDebugUnitTest` (after the KDoc move in SrApp.kt) | BUILD SUCCESSFUL in 1m 30s: app-sr compiled, 17 classes, 55 tests, 0 failures, debug APK built |
| **Fix round** `gl.sh … :android:app-sr:testDebugUnitTest --tests '*SrKitLockTest*' --tests '*SrDayLockHostsTest*'` | BUILD SUCCESSFUL: SrKitLockTest 8/8, SrDayLockHostsTest 4/4 (first run) |
| fix round `gl.sh … --continue` core-database `*CaptureRepositoryDayLockTest*` `*RouteDayLockTest*`, feature-memo `*MemoDayLockTest*` `*MemoViewModelTest*` `*RoomMemoAdaptersTest*`, app-amo `*AmoKitLockTest*` `*AmoDayLockHostsTest*` `*AmoDayLockGateTest*` `*AmoDayLockCallTest*`, app-sr `*SrDayLock*` | 1 failure, in a new test's own data: `MemoDayLockTest.aCallerWithNoRouteCollectsOnTheVisitsRoute` stored two memos with one memo number (SQLiteConstraintException). Fixed in the test (its second memo has its own number); rerun `:android:feature-memo:testDebugUnitTest --tests '*MemoDayLockTest*'`: BUILD SUCCESSFUL, 6/6. Everything else green: CaptureRepositoryDayLockTest 6/6, RouteDayLockTest 9/9, MemoViewModelTest 6/6, RoomMemoAdaptersTest 2/2, AmoKitLockTest 8/8, AmoDayLockHostsTest 4/4, AmoDayLockGateTest 5/5, AmoDayLockCallTest 1/1, SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockHostsTest 4/4, SrDayLockSaleTest 4/4 |
| **negative check** (fixes undone in the worktree by a scratch script, then restored before any commit): `gl.sh … --continue` feature-memo `*MemoDayLockTest*`, app-sr `*SrKitLockTest*` `*SrDayLockHostsTest*`, app-amo `*AmoKitLockTest*` `*AmoDayLockHostsTest*` | BUILD FAILED as intended, 10 failures, each a fix-round test: MemoDayLockTest.theRefusalIsNeverShownWhileMarkPaidIsStillDrawn; SrKitLockTest.markPaidIsHiddenAfterTheSrsOwnSalesSubmit…, aRefusedSaleBelongsToItsCall…; SrDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall; AmoKitLockTest.markPaidOutsideACallCollectsOnTheCallsRoute… ("collected": the old wiring's write failed), markPaidOutsideACallIsHiddenWhenTheMemosRouteIsFinal…, theSupervisorDaySubmitHidesMarkPaid…, aRefusedSaleBelongsToItsCall…; AmoDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall, editOnTheMemoDetailFollowsTheLock… |
| **Lane verify (fix round)** `gl.sh … --continue :android:core-database:testDebugUnitTest :android:feature-memo:testDebugUnitTest :android:feature-dayclose:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:core-ui:testDebugUnitTest --tests '*HardcodedStringScanTest*'` | BUILD FAILED in 1m 4s on two tasks, neither from this lane's code. **Green:** feature-memo 15 classes, 55 tests, 0 failures; feature-dayclose 8 classes, 43 tests, 0 failures; app-sr 19 classes, 67 tests, 0 failures; app-amo 9 classes, 49 tests, 0 failures; app-sr and app-amo debug APKs built. **core-database:** 28 classes, 209 tests, 10 failed: the 9 known Windows-path Room MigrationTestHelper cases (SchemaMigrationTest 6, Migration67Test, Migration78Test, CheckerAmoRecordsTest) and `ZoneScaleBudgetTest.anElevenThousandOutletZoneStaysInsideTheBudgets` ("one route query 114 ms > 100", a timing budget, the laptop was running other lanes' builds; no DAO or query changed here; see the rerun row). **HardcodedStringScanTest:** 5 tests, 1 failed with the same 3 violations as before, all in `feature-tso/.../leave/LeaveApply.kt:22` (on the base `tso-man/X1`; fixed on the integration branch in a1b5041b3). No violation in a file of this lane |
| reruns, three times: `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*ZoneScaleBudgetTest*' --rerun :android:feature-memo:testDebugUnitTest --tests '*MemoDayLockTest*' --rerun :android:app-sr:testDebugUnitTest --tests '*SrDayLockCaptureTest*' --tests '*SrKitLockTest*' --rerun` | BUILD SUCCESSFUL all three times (19 s, 21 s, 21 s): ZoneScaleBudgetTest 1/1, MemoDayLockTest 6/6, SrDayLockCaptureTest 4/4, SrKitLockTest 8/8 in each run; the timing budget holds when the laptop is quieter, and the two formerly flaky Mark Paid cases did not fail |

## Goldens needed

None (D-X4a-07: no screenshot case was added).

## Not done

- No screenshot cases (D-X4a-07).
- Device check s8 row 26 on a pilot phone (ask 2): the lock arriving by push on a real phone and printer was not tried.
- **The offline window:** the phone refuses a capture only after it has learned the lock (the push's pull or the next
  sync answer). A sale saved offline after the server's `final_submitted_at` and before the phone learns it is stored and
  printed, and the server then refuses it `day_final_submitted`. No phone-side change can close that window; it is shown
  afterwards in the Home banner's "not accepted" count.
- Stock after Final Submit (ask 3) and a skip after the SR's own Sales Submit (ask 4) are raised, not changed.
- The SR memo host's re-check of Edit on a push pull has no test (`PushRuntime.pulledNow` is internal to core-sync); the
  AMO host's twin is tested through `day.dayLock`.
- ControlCallHost and the SR picker composable are not composed in a test; their decisions are the tested helpers.
- CI did not run; nothing is pushed.

## Next step

The lead merges `tso-man/X4a` with the integration round and rules on asks 3 and 4; the device lab runs s8 row 26 (ask 2).
