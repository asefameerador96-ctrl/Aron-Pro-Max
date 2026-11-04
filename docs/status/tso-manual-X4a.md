# Lane X4a: the SR and AMO phones honour the day lock (integration round, R-W-11)

Branch `tso-man/X4a`, worktree `.claude/worktrees/tso-X4a`, base `tso-man/X1` (f4ba4501d). Build contract
`docs/requests/tso-manual-build-2026-10.md` s10 R-W-11; the asks are A3's asks 1, 10 and 11 and D-A3-22
(`docs/status/tso-manual-A3.md`). Nothing pushed, no pull request. The fix round after the checker's report on
5b11e9307 is in "Fix round" below; the rows it changed say so. The follow-up pass (R-W-22 stock lock, R-W-23 no capture
refusal escapes a host) is in "Follow-up pass" below; the fix round after the checker's report on 01642c53d is in "Fix
round 2" below.

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

Follow-up pass (R-W-22, R-W-23):

- **D-X4a-13 Stock meets the Final Submit lock only.** `recordStock` names `stock_movement` to the lock (`requireNotFinal`),
  not to the Sales Submit check: the server accepts stock after a `day_submit` and the phone always did (a stock return
  after the Sales Submit stays possible). This supersedes D-X4a-01's "stock stays as it was" for the lock. A routeless
  movement never meets the lock, as on the server (`routeId != null` in B3's `dayLocked`).
- **D-X4a-14 (superseded by D-X4a-20 in fix round 2) "No stock slip prints after the lock" is read as: no slip for a refused Save.** A Save the lock refuses stores
  nothing, so no slip exists, none is offered and the Print tap prints nothing. A slip of a Save stored before the lock
  still prints: it is the paper of a capture the server holds or accepts, `print_event` is accepted after the lock, and
  the lock text itself says "Printing still works". Ask 5 asks the lead to confirm.
- **D-X4a-15 One refusal kind and one rule.** `DayRefusal` lives next to the two exceptions in core-database;
  `RouteDayLock.refusal` is the rule `requireOpen` throws by, and `CaptureRepository.dayRefusal` mirrors each record
  method, so no screen's check can disagree with the write. The kit and day APIs stay additive: `onRefused` callbacks with
  defaults, `callLocked` / `editLocked` / `refusedByLock` keep meaning the Final Submit lock, `lockRefused` keeps its
  meaning and `submitRefused` sits beside it.
- **D-X4a-16 The submit text is existing strings.** feature-dayclose's `submit_title` ("Sales submit") over
  `submit_blocked_done` ("Today's sales are already submitted."), with `sr_ok` / `amo_ok` (`SrDaySubmittedDialog`,
  `AmoDaySubmittedDialog`). The lock text is A3's, unchanged. No new string, no screenshot case.
- **D-X4a-17 A call whose route-day no longer takes its records cannot go on.** The SR can submit the day with a call open
  (the submit gate does not block on it) and come back to it. `SaleHost` checks the call on entry and again when the rep
  leaves it (No, back, the close after Review); `NoSaleHost` before its close: the text shows first and OK ends the call
  on the phone with no close written (the composable is gone once the call ends, so the text cannot come after). A
  refusal that lands during the write itself ends the call silently, as the fix round's lock did. On the AMO the
  ControlCallHost outlives the call, so the close runs and the outlet list shows why. Such a call is not restored after a
  relaunch (it would come back on every launch otherwise); a server void of the submit brings it back.
- **D-X4a-18 A sale the Sales Submit refuses takes no memo number.** `recordNumberedSale` runs the full route-day check
  (lock and Sales Submit) before it reserves; D-X4a-03 covered the lock only. Scope (fix round 2, checker low): this holds where the production
  `MemoNumbering` is built (`recordNumberedSale`). If its construction fails in `MainActivity` (`runCatching`), the
  fallback `SaleCommitter` path (`numbering == null`) reserves with `numbers.next()` before `recordSale`, so a refused sale
  there burns one number; gapped numbers are allowed by design. `SaleCommitter` is feature-sale's, not changed here.
- **D-X4a-19 Writes with no Save of their own stay silent** (fix round 2: the SR survey now says why, D-X4a-23; the content
  view stays as below). The SR's AV/KV content view (logged after an item ends) and
  the SR survey keep their `runCatching`: nothing is stored on a refusal, the call's entry guard shows the text before
  them and a submit landing mid-call is worded by the sale's save.

- **D-X4a-20 (fix round 2, R-W-22 read as written; closes ask 5) No stock slip prints after the lock, also not the slip of
  a Save stored before it.** The Print tap prints the oldest unprinted Save among those whose route-day is not closed by
  its zone's Final Submit (`SrDay.printableStockSave` / `AmoDay.printableStockSave`, checked per Save with
  `CaptureRepository.dayRefusal(meta, "stock_movement")`); both Stock hosts draw Print only when such a slip exists
  (`stockSlipPrintable`, re-read on every print attempt and every change of `day.dayLock`), and `printUnprintedStock`
  checks again, so a button drawn just before the lock lands prints nothing. The locked Save stays unprinted (the
  screen's "slip not printed" banner stays, without the retry action) and its slip prints once the day reopens. The
  AMO's routeless Saves (outside a call) never meet the lock, so their slips still print.
- **D-X4a-21 (fix round 2, checker low) The AMO's stock carries the open call's route, none outside a call.** `AmoDay.saveStock`
  takes `stockMeta()` (the visit session's route) and `AmoRecordWriter.correctStock` the writer's `routeOfCall()`, never
  `AmoDay.routeId` alone: the outlet pick sets that before the fix is read, and backing out of the check step left it set
  with no call open, so the AMO's own stock could carry a stale route and be refused with the lock text.
- **D-X4a-22 (fix round 2, R-W-23, checker low) The control call has an entry guard, as the SR sale host.** When
  `ControlCallHost` meets a call in progress (back to it, or restored), `AmoDay.endCallIfClosed` ends it on the phone with
  no close written when its route-day no longer takes its records (the Final Submit lock, or the supervisor-day submit);
  the host goes to the outlet list and shows why (`AmoRefusalDialog`). It is keyed on the open call, not on a lock that
  lands while a step is being filled: that step's Save or the close words it, as in the follow-up pass.
- **D-X4a-23 (fix round 2, R-W-23, checker low) The SR survey's refused Save is worded.** `SrDay.saveSurveyRefusal` names a
  route-day refusal; `CallContentHost` hands it to `SaleHost`, which shows the matching text and OK ends the call on the
  phone, as at its entry. Any other failure goes on to the sale as before. The AV/KV content view (a log written after an
  item ends, no Save of its own) stays silent (D-X4a-19).

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
- Follow-up pass: `core-database/.../repo/RouteDayLock.kt` (A3's file) gains `DayRefusal` and `RouteDayLock.refusal`
  (`requireOpen` throws by it, same exceptions and messages). `feature-stock` gains `StockSaving.kt`, one new file and no
  change to any existing feature-stock file: the Save the SR's `StockHost` and the AMO's `AmoStockHost` each wrote inline
  (and identically) moves there so both meet the lock the same way and the refusal can be tested on the real `SrDay` /
  `AmoDay` without composing a screen; its non-refusal behaviour (SAVED, NOTHING_ENTERED, REFUSED_SAME_VALUES, SAVE_FAILED,
  the re-save guard) is the hosts' old code, and feature-stock's own 33 tests are unchanged and green. `app-sr/.../SrApp.kt`: `StockHost` is `internal` (was `private`) so the compose test can show
  it. `app-amo/.../AmoStockCorrectHost.kt` and `ControlCallHost.kt` word the refusals. The SR and AMO test fixtures gain
  `catalogOnly`, `reopened` and a `closeDay` parameter on `refusedSale`.
- Fix round 2: `app-sr/.../CallContentHost.kt` gains an `onRefused` parameter (default: nothing), and `SurveyStep` uses
  `SrDay.saveSurveyRefusal`; `app-amo/.../AmoRecordWriter.correctStock` builds its meta with the writer's call route;
  `AmoSaleKit.submitWriter()` is the supervisor-day writer `newSubmitViewModel` built inline (same arguments). No string,
  no resource and no file of another module changed.

## Asks

1. **Lead: answered in the fix round (checker high 1).** The AMO Mark Paid crash outside a call is fixed by D-X4a-08 (the
   collection carries its visit's route). If the lead rules instead that a later AMO collection carries no visit, the
   change is one line in `VisitDueCollectionWriter.target`; tell me.
2. **Lead / device lab:** s8 row 26 ("SR phone shows lock") on a pilot phone against dev, now that the banners are drawn:
   with the TSO's "Submit anyway", the SR's Sales Submit, Mark Paid, a sale saved in an open call and leaving a call do
   not crash; a new call is refused with the lock text; the Home banner shows. The same for the AMO's control call.
   Follow-up: add a stock Save after the lock (the lock text, no slip) and, on an open day, the SR's own Sales Submit
   with a call still open, then back to that call (the submit text, OK goes Home, the call is not restored on relaunch).
   **The offline window to check and accept:** the phone refuses only after it has learned the lock (the push's pull, or
   the next sync answer's `day_states`). A sale saved offline after the server's `final_submitted_at` but before the
   phone learns of it is stored and printed, and the server then refuses it `day_final_submitted` (the Home banner counts
   it under "not accepted"). This is designed and tested on the laptop only; no device has run it.
3. **Answered by R-W-22, done in the follow-up pass:** stock is locked on the phone as on the server (D-X4a-13, D-X4a-14).
4. **Answered by R-W-23, done in the follow-up pass:** the skip after the SR's own Sales Submit, and every other path of the
   sweep, is worded and never thrown.
5. **Closed in fix round 2 (checker medium):** R-W-22 is applied as written (D-X4a-20): no stock slip prints after the
   lock, also not one stored before it. If the lead prefers the narrower D-X4a-14 reading (a slip of a Save stored before
   the lock may print, as a memo reprint may), the change is the filter in `printableStockSave` on each app.
6. **Lead (R-W-23 sweep, not changed):** the AMO's routeless supervisor records (outlet-request verification, exception
   review, task assignment) are refused after the AMO's own supervisor-day Sales Submit (the phone's rule since before
   this lane). Their hosts catch it with their own failure text ("could not save" style); nothing is thrown. Should they
   say the submit text instead? Three hosts outside this lane's list (AmoOutletHub, AmoTeamHub, AmoTaskHub).
7. **Lead / owner (strings, not changed):** A3's lock body says "sales, memo edits and collections are not accepted"; stock
   is now refused too. Adding "stock" to `sr_lock_body` / `amo_lock_body` (en and bn) is a string change for A3's owner or
   the Bangla reviewer; the screens already show the lock title and body for a refused stock Save.

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
| L4 | stock after Final Submit not raised | not changed (R-W-11 does not name stock, D-X4a-01); raised as ask 3 | none (no code change); done in the follow-up pass (R-W-22) |

## Follow-up pass (R-W-22, R-W-23; lead rulings after the integration round)

This pass ran in three attempts. The first wrote the code below and was cut off by a usage limit before compiling it. The
second compiled and ran it (one targeted run, BUILD SUCCESSFUL at 01:16 on 2026-11-04, scratch log only) and committed it
in four pieces (1bcd7ef06 core-database, d827aa273 feature-stock, fe784831d app-sr, 0f790cc5f app-amo); it was cut off
with the negative proof's batch A applied to the tree and waiting for the build lock, so that batch never ran then. The
third attempt (this report's Verify rows "follow-up") ran the negative proof and the green run itself: a scratch script
(`x4a_negative.py`, outside the repository) undoes one batch of rules at a time in the committed tree (A: the stock lock in
recordStock and the parity guard; B: the refusals named by the gates, kits and hosts and the stock Save's wording; C: the
call ended on the phone and not restored after a Sales Submit); each batch's targeted tests ran and failed as intended,
`git checkout -- android` restored the tree after each, and the targeted classes then ran green on the committed code. No
source changed in the third attempt; only this file.

| item | what | proving test |
|---|---|---|
| R-W-22 core | `CaptureRepository.recordStock` names `stock_movement` to the Final Submit lock inside its transaction (each distinct meta of the Save): a Save on a route-day closed by its zone's Final Submit is refused with `RouteDayFinalSubmittedException("stock_movement")` and nothing is stored or queued. A routeless movement (the AMO outside a call) never meets the lock, as on the server; a Sales Submit does not refuse stock (D-X4a-13). | `core-database` `CaptureRepositoryDayLockTest.aStockSaveOnAFinalRouteDayIsRefusedAndNothingIsQueued` |
| R-W-22 parity | The phone's `FinalSubmitLock.LOCKED_TYPES` must equal B3's `IngestService.DAY_LOCKED_TYPES`: a test reads the `setOf(...)` literal from `backend/sync/.../IngestService.kt` in this repository and compares the sets. They are equal on this base (13 types, `stock_movement` among them). | `CaptureRepositoryDayLockTest.thePhonesLockedTypesAreTheServersDayLockedTypes` |
| R-W-22 screens | One Save of the Stock screen for both apps is `StockSaving.save` (feature-stock, new): stored → `SAVED`; refused by the route-day → no message, `refused` says why, `StockLoad.commitFailed` gives the re-save guard back (the same values save once the day reopens); any other failure → `SAVE_FAILED` as before. `SrDay.saveStock` (the day's route) and `AmoDay.saveStock` (the open call's route, none outside a call) wire it; `StockHost` (SR) and `AmoStockHost` show the lock text (`SrRefusalDialog` / `AmoRefusalDialog`) for a refused Save. `AmoStockCorrectHost` shows the lock text for a refused correction (`correctStock` goes through `recordStock`). Nothing is stored, so no slip is offered (`slipNotPrinted` false) and the Print tap prints nothing (D-X4a-14). | `app-sr` `SrRefusalSweepTest`: lock already there (refused, nothing stored or queued, no slip offered, the Print tap starts no print and queues no `print_event`, the typed quantity stays); lock arriving mid-entry (the same, then the same two dozen save once on a reopened day); an open day saves as before and the SR's own Sales Submit does not refuse stock. `SrRefusalHostsTest.theStockScreenShowsTheLockTextForARefusedSaveAndQueuesNothing` (composed). `app-amo` `AmoRefusalSweepTest`: the same two cases in a call on a final route, stock outside a call still saves with no route, a correction in such a call is refused by name and stores nothing; `AmoRefusalHostsTest.theStockScreenInACallOnAFinalRouteShowsTheLockTextAndQueuesNothing` |
| R-W-23 core | `DayRefusal` (FINAL_SUBMIT, SALES_SUBMIT; `DayRefusal.of(throwable)`), and `RouteDayLock.refusal`, the one rule `requireOpen` now uses. `CaptureRepository.dayRefusal(meta, type)` answers what the record method would do with the same meta (stock: the lock only; routeless due collection: never), so a screen asks it before a fix or a form. `acceptsDueCollection` is `dayRefusal(…) == null`. `recordNumberedSale` runs the full route-day check before reserving a number, so a sale the Sales Submit refuses takes no number either (D-X4a-18). | `CaptureRepositoryDayLockTest.dayRefusalAnswersWhatEachWriteWouldDo`; the "no memo number taken" asserts of the two sale cases below |
| R-W-23 SR (ask 4) | The gate (`SrLockGate.saveAndPrint`, `closeCall`, `skip`) catches both refusals and says which (`onRefused`); the kit asks `dayRefusal` before anything: `startCall` / `newCallRefusal` (picker), `commitRefusal` (visit screen), `editRefusal` (memo detail and edit host), `openCallRefusal` (a call in progress), `skipOutlet(… onRefused)`, `closeVisit` returns why the call ended on the phone, `submitRefused` keyed like `lockRefused` for a sale or edit save, `endRefusedCall`. Hosts: `SkipHost`, the picker and the visit screen (SrApp), `SaleHost` (entry guard, the start prompt's No and back, the close after Review), `NoSaleHost`, `MemoHost` (Edit disabled with the submit text), `EditHost`, `StockHost` show `SrRefusalDialog` (the lock text, or the submit text `SrDaySubmittedDialog`). A call whose route-day is closed is not restored after a relaunch (D-X4a-17). | `SrRefusalSweepTest`: a skip after the SR's own Sales Submit is named, not thrown (and the lock is named as the lock); a new call is refused by name before a fix; a submit landing while the fix is read is worded, not a retry; a sale refused by the submit takes no number and OK ends the call; leaving a call (close after Review, No-sale, start prompt's No) ends it on the phone and says why; a submit landing during the close write ends the call without a throw; such a call is not restored; an edit is refused by name and the lock wins over the submit. `SrRefusalHostsTest` (composed): the skip host, a call whose route-day was submitted (entry), the sale host, the no-sale host and the edit host (no fix read) show "Today's sales are already submitted." and OK leaves; nothing is stored. Mark Paid: the fix round's `SrKitLockTest.markPaidIsHiddenAfterTheSrsOwnSalesSubmit…` |
| R-W-23 AMO | The same on the AMO: `AmoLockGate` catches both; `AmoDay.newCallRefusal`, `openCall(… onRefused)`, `commitRefusal`, `saveCallStep` (assessment, joint call, survey, price check: `StepSave(saved, refused)`), `AmoDay.closeVisit` returns why the call ended on the phone, `AmoSaleKit.submitRefused`, `editRefusal`, `endCallIfClosed`, `skipOutlet(… onRefused)`. `ControlCallHost` (outlet pick, check step, every way out of a call, the four steps), `AmoSaleHost`, `AmoMemoHost`, `AmoEditHost`, `AmoStockHost`, `AmoStockCorrectHost` show `AmoRefusalDialog` (the lock text with the closed-route count, or `AmoDaySubmittedDialog`). A call the supervisor-day submit closed is not restored. | `AmoRefusalSweepTest`: a new call after the supervisor-day submit is refused by name before a fix; a submit landing while the fix is read is worded; a sale refused by the submit takes no number and OK ends the call; leaving a call ends it on the phone and says why; not restored; the four call steps return `StepSave(false, SALES_SUBMIT)` and store nothing, an open day saves and a plain failure is not worded as a refusal; an edit and a skip are refused by name. `AmoRefusalHostsTest` (composed): the sale host and the edit host (no fix read) show the submit text |

The sweep (every place on SR and AMO that can throw `RouteDayFinalSubmittedException` or `RouteDaySubmittedException`):

| path | before this pass | now |
|---|---|---|
| SR skip (`SkipHost`) | a Sales Submit refusal crashed the screen (ask 4) | submit text, OK leaves |
| SR new call (picker), visit commit (visit screen) | the submit refusal read a fix and then showed the generic failed commit with a retry that could never pass | refused before a fix with the submit text; a submit landing during the fix: the submit text, not the retry |
| SR sale save (`SaleHost` via `SaveAndPrint`) | generic "save failed" with a retry; a memo number was burned | submit text in place of the sale, OK ends the call; no number |
| SR close (Review's close, `NoSaleHost`, start prompt's No and back) | the submit refusal escaped `SrLockGate.closeCall` / `SrCallLock.abandon`: a crash | text first, OK ends the call on the phone (no close); a refusal during the write ends the call without a throw |
| SR memo edit (`MemoHost`, `EditHost`) | Edit drawn; a fix was read and the save failed generically | Edit disabled with the submit text; the edit host reads no fix |
| SR Mark Paid | fix round (hidden, a tap it beat is worded) | unchanged |
| SR stock | not locked (ask 3) | the lock text, nothing queued or printed (R-W-22) |
| SR AV/KV content view, SR survey (`CallContentHost`) | refusals caught (`runCatching`), nothing stored | unchanged; the call's entry guard shows the text first, a submit landing mid-call is worded by the sale's save (D-X4a-19) |
| SR memo void and return | no screen on the SR phone | nothing to catch |
| AMO control / joint / sale call (outlet pick, check step) | lock only; the submit refusal read a fix and then a generic failed commit | the submit text before a fix; during the fix: the submit text |
| AMO call steps (assessment, joint, survey, price) | caught, no words (the form stays) | the submit text, the form stays |
| AMO close (menu, back, after a step, after the sale) | the submit refusal escaped `AmoLockGate.closeCall`: a crash | ends the call on the phone, the outlet list shows why |
| AMO sale, edit, Mark Paid, stock, stock correction | sale/edit generic failure; stock not locked | the lock or submit text, as on the SR |
| AMO verification, exception review, task assignment (routeless supervisor records) | caught by their hosts with their own failure text | unchanged, never thrown (ask 6) |
| `IllegalArgumentException` from `requireRoute` | every SR and AMO visit-family write carries the visit's or the outlet's route (non-null in the visit row and `OutletEntity`); the routeless content-view and survey metas are caught by their hosts | unchanged, no path left that can reach a host uncaught |

## Fix round 2 (checker report on 01642c53d)

The checker passed the follow-up pass with one medium item and seven lows (it re-ran no test). Fixed, each with a test that
fails without the fix (Verify, "fix round 2 negative"): the medium, L1, L2 and L3's SR survey. L5 gains a test of an
untested path and L7's scope is stated. Not changed, with the reason below: L4, L6, L3's AMO hubs (ask 6) and L5's
composed cases.

| # | checker defect | fix | test that fails without it |
|---|---|---|---|
| M | R-W-22 "no stock slip prints after the lock" read narrowly (a slip of a Save stored before the lock still printed) | D-X4a-20: Print hidden and the print refused for a Save on a final route-day, in both apps | `SrRefusalSweepTest.aSlipOfASaveStoredBeforeTheLockDoesNotPrintAfterItAndPrintsOnceReopened`, `AmoRefusalSweepTest.aSlipOfASaveStoredInACallBeforeTheLockDoesNotPrintAfterIt` (the slip is not printable, the Print tap starts no print and queues no `print_event`, the Save stays unprinted; it prints once reopened, and the AMO's routeless slip still prints) |
| L1 | AMO stock took a stale route from `AmoDay.routeId` | D-X4a-21: the call's route, none outside a call (`stockMeta`, `correctStock`) | `AmoRefusalSweepTest.aRouteLeftBehindByABackedOutCallNeverTagsTheAmosOwnStock` (route left set with no call open, that route final: the Save and the correction are stored routeless) |
| L2 | no AMO call entry guard | D-X4a-22: `AmoDay.endCallIfClosed` on `ControlCallHost`'s entry | `AmoRefusalSweepTest.aCallWhoseRouteDayClosedEndsAtTheControlCallsEntryAndSaysWhy` (open day: the call stays; Final Submit: FINAL_SUBMIT; supervisor-day submit: SALES_SUBMIT; the call ends on the phone, no `visit_close`). The host's one-line wiring is not composed in a test |
| L3 | silent survey and content paths; AMO hubs (ask 6) | D-X4a-23: the SR survey names the refusal and the sale host words it; the content view stays silent (D-X4a-19); the AMO hubs wait on ask 6 (outside this lane's files) | `SrRefusalSweepTest.aSurveySaveTheSalesSubmitRefusedIsNamedAndStoresNothing` (refused: SALES_SUBMIT, nothing stored or queued; open day: saved). The `CallContentHost` to `SaleHost` wiring is not composed in a test |
| L4 | the SR leave and no-sale lambdas drop the `DayRefusal` of a refusal that lands during the write | not changed: the accepted race of D-X4a-17 (no crash, no record); once the call ends the sale host is gone, so no text can follow | none |
| L5 | composed-test gaps; no AMO supervisor-day submit after Final Submit test | the AMO supervisor-day submit writer is `AmoSaleKit.submitWriter()` (the view model uses it; same arguments) and is tested; the composed ControlCallHost, AmoStockCorrectHost and force-sale fallback cases are not added (time) | `AmoRefusalSweepTest.theSupervisorDaySubmitAfterTheFinalSubmitIsQueued` (covers an existing behaviour; no rule was changed, so no negative proof) |
| L6 | phone-server parity by a regex over the server source | not changed: moving `DAY_LOCKED_TYPES` into `shared/contract` touches B3's server module and the contract, outside this lane; the regex test fails loudly on a reformat | none |
| L7 | D-X4a-18's claim holds only with `MemoNumbering` | scope stated in D-X4a-18 (`SaleCommitter` is feature-sale's) | none |

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
| **follow-up, attempt 2** (scratch log `run1.log`, 2026-11-04 01:16 +0600, before the four commits at 01:17): `gl.sh … :android:core-database:testDebugUnitTest --tests '*CaptureRepositoryDayLockTest*' --tests '*RouteDayLockTest*' :android:feature-stock:testDebugUnitTest :android:app-sr:testDebugUnitTest --tests '*SrRefusal*' --tests '*SrDayLock*' --tests '*SrKitLock*' :android:app-amo:testDebugUnitTest --tests '*AmoRefusal*' --tests '*AmoDayLock*' --tests '*AmoKitLock*'` | BUILD SUCCESSFUL in 1m 14s (the follow-up code compiled; read from the log and the test-result files, not rerun by attempt 3 under this command) |
| **follow-up negative proof A** (attempt 3; `x4a_negative.py A`: recordStock's `requireNotFinal(…, STOCK_MOVEMENT)` removed and a `phone_only_type` added to `FinalSubmitLock.LOCKED_TYPES`): `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*CaptureRepositoryDayLockTest*' :android:app-sr:testDebugUnitTest --tests '*SrRefusal*' :android:app-amo:testDebugUnitTest --tests '*AmoRefusal*'` | BUILD FAILED in 1m 34s as intended, 10 failures: CaptureRepositoryDayLockTest 3 of 9 (aStockSaveOnAFinalRouteDayIsRefusedAndNothingIsQueued, thePhonesLockedTypesAreTheServersDayLockedTypes, dayRefusalAnswersWhatEachWriteWouldDo); SrRefusalSweepTest aStockSaveWithTheLockAlreadyThere…, aLockThatArrivesWhileTheRepTypes…; SrRefusalHostsTest theStockScreenShowsTheLockText…; AmoRefusalSweepTest aStockSaveInACallOnAFinalRoute…, aLockThatArrivesWhileTheAmoTypes…, aStockCorrectionInACallOnAFinalRoute…; AmoRefusalHostsTest theStockScreenInACallOnAFinalRoute…. Every other case passed. Tree restored with `git checkout -- android` |
| **follow-up negative proof B** (`x4a_negative.py B`: the gates, kits and hosts name only the Final Submit lock, the stock Save does not word a refusal, `recordNumberedSale` checks only the lock before the reserve, the SR sale host's entry and No guards removed): the same command | BUILD FAILED in 1m 40s as intended, 23 failures (core-database 9/9 passed: the memo-number reserve is asserted in the app tests): app-sr 13 of 17 (SrRefusalSweepTest: both stock cases, the skip, the new call, the submit while the fix is read, the sale's number, the edit; SrRefusalHostsTest: the stock screen, the skip host, the call on a submitted day, the sale host, the no-sale host, the edit host); app-amo 10 of 14 (AmoRefusalSweepTest: both stock cases, the new call, the submit while the fix is read, the sale's number, the call steps, the edit and skip; AmoRefusalHostsTest: the stock screen, the sale host, the edit host). Tree restored |
| **follow-up negative proof C** (`x4a_negative.py C`: `closeVisit` names only the lock, a call on a submitted route-day is restored after a relaunch): `gl.sh … --continue :android:app-sr:testDebugUnitTest --tests '*SrRefusal*' :android:app-amo:testDebugUnitTest --tests '*AmoRefusal*'` | BUILD FAILED in 53s as intended, 9 failures: SrRefusalSweepTest aCallOnASubmittedRouteDayIsNotRestoredAfterARelaunch, leavingACallAfterTheSalesSubmitEndsItOnThePhoneAndSaysWhy, aSaleRefusedByTheSalesSubmitTakesNoNumberAndOkEndsTheCall; SrRefusalHostsTest theSaleHost…, aCallWhoseRouteDayWasSubmitted…, theNoSaleHost…; AmoRefusalSweepTest aCallTheSupervisorDaySubmitClosedIsNotRestored…, aSaleRefusedByTheSupervisorDaySubmit…; AmoRefusalHostsTest theSaleHost…. Tree restored |
| **follow-up green** (attempt 3, committed tree, `git status` clean under `android/`): `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*CaptureRepositoryDayLockTest*' --tests '*RouteDayLockTest*' --rerun :android:feature-stock:testDebugUnitTest --tests '*StockLoadTest*' --rerun :android:app-sr:testDebugUnitTest --tests '*SrRefusal*' --tests '*DayLock*' --tests '*LockGate*' --tests '*KitLock*' --rerun :android:app-amo:testDebugUnitTest --tests '*AmoRefusal*' --tests '*DayLock*' --tests '*LockGate*' --tests '*KitLock*' --rerun` | BUILD SUCCESSFUL in 37s, 111 tests, 0 failures: CaptureRepositoryDayLockTest 9/9, RouteDayLockTest 9/9; StockLoadTest 13/13 (the `committed` / `commitFailed` guard StockSaving uses; feature-stock has no test class of this lane); SrRefusalSweepTest 11/11, SrRefusalHostsTest 6/6, SrKitLockTest 8/8, SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockHostsTest 4/4, SrDayLockSaleTest 4/4; AmoRefusalSweepTest 11/11, AmoRefusalHostsTest 3/3, AmoKitLockTest 8/8, AmoDayLockCallTest 1/1, AmoDayLockGateTest 5/5, AmoDayLockHostsTest 4/4 |
| **fix round 2, run 1** (uncommitted fix tree, scratch log `x4a-fix-run1.log`): `gl.sh … --continue :android:app-sr:testDebugUnitTest --tests '*SrRefusal*' --tests '*SrDayLock*' --tests '*SrKitLock*' --tests '*StockSlipsTest*' --rerun :android:app-amo:testDebugUnitTest --tests '*AmoRefusal*' --tests '*AmoDayLock*' --tests '*AmoKitLock*' --tests '*AmoRecordWriterReplayTest*' --rerun` | BUILD FAILED in 4m 36s (compile included), 106 tests, 1 failed, in a new test's own wait: `AmoRefusalSweepTest.aSlipOfASaveStoredInACallBeforeTheLockDoesNotPrintAfterIt` ("the routeless slip print starts": the AMO's lazily built print chain took longer than the test's fixed 300 ms). Fixed in the test: both new slip cases wait with the fixtures' `await` (10 s) instead of a fixed delay. Rerun `:android:app-amo:testDebugUnitTest --tests '*AmoRefusalSweepTest*' --rerun`: BUILD SUCCESSFUL in 28 s, 15/15. Then committed (3f58b9be0, 59ea1928a) |
| **fix round 2 negative** (committed tree; scratch script `x4a_fixneg.py` undoes N1 the printable-slip filter in SrDay and AmoDay, N2 the AMO stock meta back to `meta(0L)` in AmoDay and AmoRecordWriter, N3 `AmoDay.endCallIfClosed` always null, N4 `SrDay.saveSurveyRefusal` swallowing to null): `gl.sh … --continue :android:app-sr:testDebugUnitTest --tests '*SrRefusalSweepTest*' --rerun :android:app-amo:testDebugUnitTest --tests '*AmoRefusalSweepTest*' --rerun` | BUILD FAILED in 42 s as intended, 5 failures, exactly the five new rule tests: SrRefusalSweepTest 11/13 (aSlipOfASaveStoredBeforeTheLock… "Print is not drawn after the lock" [N1]; aSurveySaveTheSalesSubmitRefused… "expected SALES_SUBMIT but was null" [N4]); AmoRefusalSweepTest 12/15 (aSlipOfASaveStoredInACallBeforeTheLock… "Print is not drawn after the lock" [N1]; aRouteLeftBehindByABackedOutCall… "refused=FINAL_SUBMIT" [N2]; aCallWhoseRouteDayClosedEndsAtTheControlCallsEntry… "expected FINAL_SUBMIT but was null" [N3]). Every other case passed, `theSupervisorDaySubmitAfterTheFinalSubmitIsQueued` among them (coverage of unchanged behaviour). Tree restored with `git checkout -- android`, `git status` clean |
| **fix round 2 final** (committed tree 59ea1928a, `git status` clean): the run 1 command, one invocation | BUILD SUCCESSFUL in 40 s, 106 tests, 0 failures: SrRefusalSweepTest 13/13, SrRefusalHostsTest 6/6, SrKitLockTest 8/8, SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockHostsTest 4/4, SrDayLockSaleTest 4/4, StockSlipsTest 4/4; AmoRefusalSweepTest 15/15, AmoRefusalHostsTest 3/3, AmoKitLockTest 8/8, AmoDayLockCallTest 1/1, AmoDayLockGateTest 5/5, AmoDayLockHostsTest 4/4, AmoRecordWriterReplayTest 16/16 (the correction now takes the writer's call route) |
| fix round 2, not run on the laptop (lead's instruction: targeted classes only) | the full module suites of app-sr and app-amo (and core-database, feature-memo, feature-dayclose, feature-stock, none changed in this round), `:android:app-sr:assembleDebug`, `:android:app-amo:assembleDebug`, `HardcodedStringScanTest` (no string changed): the lead runs them on the Linux CI runners after the merge |
| follow-up, not run on the laptop (lead's instruction: targeted classes only) | the full module suites core-database, feature-memo, feature-dayclose, feature-stock, app-sr, app-amo, `:android:app-sr:assembleDebug`, `:android:app-amo:assembleDebug` and `HardcodedStringScanTest`: the lead runs them on the Linux CI runners after the merge |

## Goldens needed

None (D-X4a-07: no screenshot case was added).

## Not done

- No screenshot cases (D-X4a-07).
- Device check s8 row 26 on a pilot phone (ask 2): the lock arriving by push on a real phone and printer was not tried.
- **The offline window:** the phone refuses a capture only after it has learned the lock (the push's pull or the next
  sync answer). A sale saved offline after the server's `final_submitted_at` and before the phone learns it is stored and
  printed, and the server then refuses it `day_final_submitted`. No phone-side change can close that window; it is shown
  afterwards in the Home banner's "not accepted" count.
- Stock after Final Submit (ask 3) and a skip after the SR's own Sales Submit (ask 4): done in the follow-up pass (R-W-22,
  R-W-23). Open from it: ask 5 (may a slip of a Save stored before the lock still print), ask 6 (the AMO's routeless
  supervisor records after its own submit keep their own failure text), ask 7 (the lock body does not name stock).
- Follow-up: a close or skip write that fails for a reason other than the two route-day refusals (a database error, a
  contract `require`) is still rethrown by `SrLockGate` / `AmoLockGate`, as before this lane; there is no generic failure
  string for those screens. The sweep found no SR or AMO host path on which `requireRoute` can be reached (the visit family
  always carries the visit's or the outlet's route), so no such failure is known; it is stated, not tested.
- Follow-up: `ControlCallHost`'s four call steps and its leave-call, and the SR picker and visit screen composables, are not
  composed in a test; their decisions (`AmoDay.saveCallStep`, `AmoDay.closeVisit`, `AmoDay.openCall`, `commitRefusal`,
  `SrSaleKit.startCall`, `commitRefusal`) are tested on the real day.
- The SR memo host's re-check of Edit on a push pull has no test (`PushRuntime.pulledNow` is internal to core-sync); the
  AMO host's twin is tested through `day.dayLock`.
- ControlCallHost and the SR picker composable are not composed in a test; their decisions are the tested helpers.
- Follow-up: only the targeted classes ran on the laptop (Verify, "follow-up green"). The full module suites of
  core-database, feature-memo, feature-dayclose, feature-stock, app-sr and app-amo, both `assembleDebug` tasks and
  `HardcodedStringScanTest` were not run for the follow-up; the lead runs them on the Linux CI runners after the merge.
  The Room MigrationTestHelper path cases of core-database are a known Windows-only failure.
- Fix round 2: only the targeted classes ran (Verify, "fix round 2"). Not run on the laptop: the full module suites of
  app-sr, app-amo, core-database, feature-memo, feature-dayclose, feature-stock, both `assembleDebug` tasks and
  `HardcodedStringScanTest` (the lead runs them on the Linux CI runners after the merge). Not fixed, with the reason in
  "Fix round 2": L4 (the race paths' dropped reason), L5's composed cases (ControlCallHost steps and leave, its new entry
  guard, AmoStockCorrectHost, the force-sale fallback, the SR survey to sale host wiring, the Stock hosts' Print button
  under a granted print permission), L6 (parity through a shared contract constant), L7 (memo number on the fallback path).
  Ask 6 (AMO hubs' text) and ask 7 (the lock body names no stock) stay open.
- CI did not run; nothing is pushed.

## Next step

The lead merges `tso-man/X4a` with the integration round and rules on asks 6 and 7 (ask 5 closed by D-X4a-20); the device lab runs s8 row 26 with
the follow-up's stock and submit steps (ask 2).

## Late policy on the phone (owner 2026-11-04)

**Owner decision (2026-11-04, binding, given to the laptop session in chat):** when a phone sells after the TSO's Final
Submit, the sale is accepted and flagged, never refused; the SR and AMO phones' day lock stays OFF by default. quarantine
and reject remain config switches, and when an admin sets one of them, the phone lock this lane built applies exactly as
built. Base: `tso-man/handover` 5c4fad492 (the cloud's `lane/tso-manual` 86236433b with D-TSO-MAN-49, plus X2 and X4a
merged). Commits: f93767211 (V0142), 6101561ad (core-database predicate, fixtures), 3db0af853 (SR and AMO days, new
tests), 2e5d75235 (X2's end-to-end test after the merge), and this section. Nothing pushed, no pull request;
DECISIONS.md not edited (the lead writes the decision lines).

| item | what | proving test |
|---|---|---|
| 1. The policy reaches the phone | `db/migrations/V0142__late_policy_reaches_the_phone.sql` (V0142 was free; V0141 was the last): `cfg.day.late_sync_after_final_policy` delivery `server` -> `both`, nothing else of the key changes. The bundle (`ScopedConfig.deviceValues`) and every config delta (`ConfigDelta`, delivery not `server`) now carry it with the registry default `accept_and_flag`. docs/19 and docs/24 s9.5 name the delivery. No data-dictionary change (it documents tables, not keys). | `TsoManualV0130Test.theBuildsSettingsPermissionAndEventsAreRegistered` (the key's row pinned: `both`, default, enum, scope, risk, editor; `cfg.day.auto_final_submit_enabled` stays `server`); `BundleUnitTest.theLateSalePolicyIsInTheDeviceBundle` (in the device values, scope `default`, value `accept_and_flag`; the auto close switch is not) |
| 2. One predicate | `FinalSubmitLock.lockApplies(db, nowIso)` (RouteDayLock.kt): true only when the policy in force is `quarantine` or `reject` (`LOCKING_POLICIES`); false for `accept_and_flag`, an absent key or an unreadable value (not a JSON string, unknown, wrong case), the server's own fallback. It reads the held config through `ReferenceRepository.config(key, nowIso)`, the same path app-tso reads `cfg.tso.team_location_max_age_min` (`TsoReadModels.configRaw`) and the SR and AMO days read `cfg.memo.reprint_max` and `cfg.geo.update_base_*`; a scheduled value counts from its start. | `LatePolicyLockTest.onlyQuarantineOrRejectLockAndAnAbsentOrUnreadablePolicyDoesNot`, `theLockingPoliciesAreTheServersLatePoliciesButItsFallback` (IngestService's `LATE_POLICIES` minus `accept_and_flag`, and its fallback `accept_and_flag`, read from the server source as the LOCKED_TYPES parity) |
| 3. Every lock use goes through it | `FinalSubmitLock.refuses` asks `lockApplies` after the type check and before reading the day state; `locks` (`lockApplies` and `isFinal`) and `lockedRoutes` are the gated reads. Routed: `RouteDayLock.isOpen`/`refusal`/`requireOpen` (FINAL_SUBMIT branch only), `CaptureRepository.dayRefusal`/`requireNotFinal`/`acceptsDueCollection` (Mark Paid hiding through `VisitDueCollectionWriter.accepts` and `MemoViewModel`), `FinalSubmitLock.requireCallOpen` (both visit committers) and `callToRestore`, `SrDay`/`AmoDay.printableStockSave` (the slip filter, D-X4a-20), `SrCallLock.refusal`/`endedBy` (SaleHost's entry guard, D-X4a-17; `abandon`), `AmoCallLock.endedBy` (`AmoDay.endCallIfClosed`, D-X4a-22; `AmoSaleKit.endCallIfClosed`; `AmoDay.closeVisit`), the kits' `newCallRefusal`/`callLocked`/`commitRefusal`/`refusedByLock`/`editRefusal`/`editLocked`/`skipOutlet`/`closeVisit`, `AmoDay.newCallRefusal`/`openCall`. The lock banners and the SR check-out gate: `SrDay.refreshLock` and `finalLocked` read `locks`, `AmoDay.refreshLock` reads `lockedRoutes`, `AmoDay.routeLocked` reads `locks`. Every `RouteDayFinalSubmittedException` comes from `requireOpen`, `requireNotFinal` or `requireCallOpen`, so every `DayRefusal.FINAL_SUBMIT` a gate, kit, host, `MemoViewModel` or `StockSaving` shows comes from the predicate. After the grep no production code reads `isFinal` or `finalRoutes` outside `locks`, `lockedRoutes` and `refuses`. | `LatePolicyLockTest.withNoPolicyInTheBundleAFinalRouteDayTakesEveryCapture`, `underAcceptAndFlagAFinalRouteDayTakesEveryCapture` (CaptureRepository: a call, a numbered sale, a skip, a collection, stock and the close are stored and queued on a final route-day; `dayRefusal` null for every locked type; Mark Paid accepted; `requireCallOpen` passes; the call is restored); `SrLatePolicyTest` (6) and `AmoLatePolicyTest` (6) on the real `SrDay` and `AmoDay`, each case run with the key absent and with `accept_and_flag`: no lock banner (`dayLock` null), `finalLocked`/`routeLocked` false, a new call reads its fix and opens, the entry guards leave the call open and a relaunch restores it; the kit's sale saves with its number, asks to print, the print runs (no printer in the fixture: `PrintFailed`, one `print_event` queued) and Edit is allowed; Mark Paid is drawn and collects on the call's route; a skip and the closes are queued; stock (and the AMO's correction) saves on the route, Print is drawn and the slip print starts |
| 4. Trusted now | `CaptureRepository` passes its own clock (`nowIso()`) to every lock check; `SrDay`/`AmoDay` pass `iso(clock.nowMs())` to `locks`, `lockedRoutes` and `callToRestore`. Without a clock (`refuses` called directly, `requireCallOpen`) the capture's own trusted `captured_at` is used. So a stored Save (the slip filter) is judged by the policy in force now, not when it was captured. | `LatePolicyLockTest.aScheduledPolicyLocksFromItsStartAtTheRepositorysTrustedNow` (resolved `accept_and_flag`, scheduled `quarantine` from 17:00: the same 04:31 meta is open at 16:59 and FINAL_SUBMIT at 17:00) |
| 5. X4a's lock tests under quarantine and reject | `SrLockFixture` and `AmoLockFixture` take `latePolicy` (required) and write it as the bundle stores it (`latePolicyOn`; core-database `TestRows.latePolicy`); every X4a lock test sets it in its fixture or `@Before`: `reject` in `CaptureRepositoryDayLockTest`, `SrKitLockTest`, `AmoKitLockTest`; `quarantine` in all the others (`RouteDayLockTest`, `MemoDayLockTest`, `SalesSubmitDayLockTest`, `SrDayLockCaptureTest`, `SrDayLockSaleTest`, `SrDayLockHostsTest`, `SrRefusalSweepTest`, `SrRefusalHostsTest`, `AmoDayLockCallTest`, `AmoDayLockGateTest`, `AmoDayLockHostsTest`, `AmoRefusalSweepTest`, `AmoRefusalHostsTest`). No assertion changed or removed. | the X4a classes in Verify, all green |
| 6. The Sales Submit refusals are not part of it | `RouteDayLock.refusal`'s SALES_SUBMIT branch, `RouteDaySubmittedException` and every submit text are untouched; on a final route-day under `accept_and_flag` the SR's own Sales Submit and the AMO's supervisor-day submit still refuse with their own words. | `SrLatePolicyTest.theSrsOwnSalesSubmitStillRefusesAsBuilt`, `AmoLatePolicyTest.theSupervisorDaySubmitStillRefusesAsBuilt`, the Sales Submit part of `LatePolicyLockTest`'s capture cases, X4a's sweep tests |
| 7. LOCKED_TYPES parity | unchanged: the phone's `FinalSubmitLock.LOCKED_TYPES` equals this branch's `IngestService.DAY_LOCKED_TYPES` | `CaptureRepositoryDayLockTest.thePhonesLockedTypesAreTheServersDayLockedTypes` |
| 8. Merge: X2's end-to-end test | `FinalSubmitProjectionEndToEndTest` closes the zone-day through `AutoFinalSubmitJob`; the cloud ships `cfg.day.auto_final_submit_enabled` false, so after the merge the job closed nothing. `setUp` switches it on through `TsoWorld.cfg`, as `AutoFinalSubmitJobTest` does; assertions unchanged, the shipped default untouched. | baseline run: "expected: [(2, 2027-01-02)] but was: []"; green after |

Decisions (lane level; the lead writes the DECISIONS.md lines):

- **D-X4a-24 `isFinal` stays the fact.** `FinalSubmitLock.isFinal` and `finalRoutes` keep answering "the phone learned the
  Final Submit" whatever the policy (tests use it); the lock is `lockApplies` plus that fact, in `refuses`, `locks` and
  `lockedRoutes`. `lockApplies` is checked before the day state is parsed, so under the default the route section's JSON
  is never read for a lock.
- **D-X4a-25 The policy is read at trusted now.** As item 4; a scheduled change applies from its start on the phone as on
  the server.
- **D-X4a-26 Under accept_and_flag the Final Submit no longer opens the SR's check-out.** `SrDay.finalLocked` fed A3's
  check-out gate ("the zone's Final Submit closed the route-day for the rep, so it opens the check-out as a Sales Submit
  would"). With the lock off the rep can still sell, so the check-out waits for the rep's own Sales Submit, as on a day
  with no Final Submit ("nothing on the phone changes"). Under quarantine or reject it opens as before. Ask 8.
- **D-X4a-27 The "held / not accepted" counts are not gated.** `FinalSubmitLock.metLock` counts records the server held
  (`after_final_submit`) or refused (`day_final_submitted`); under accept_and_flag the server does neither, so the banner
  has nothing to show, and a day that ran under quarantine still says what happened to its records.
- **D-X4a-28 The offline window.** Under accept_and_flag there is none (the server accepts a late sale and flags it).
  Under quarantine or reject it is exactly X4a's (ask 2).

Negative proofs (scratch script `neg.py` outside the repository: one batch of rules undone in the uncommitted tree, the
targeted classes run, the files restored from its backup; `git status` showed the same tree after each):

| batch | undone | command | result |
|---|---|---|---|
| A | R2: `refuses` ignores the policy; R3: `SrDay.refreshLock`/`finalLocked` and `AmoDay.refreshLock`/`routeLocked` read `isFinal`/`finalRoutes` directly; R5: V0142 removed | `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*LatePolicyLockTest*' :android:app-sr:testDebugUnitTest --tests '*SrLatePolicyTest*' :android:app-amo:testDebugUnitTest --tests '*AmoLatePolicyTest*' :backend:sync:test --tests '*BundleUnitTest*' :db:test --tests '*TsoManualV0130Test*'` | BUILD FAILED in 1m 20s as intended, 17 failures: LatePolicyLockTest 3 of 5 (both capture cases at "refuses false", the scheduled case at 16:59) [R2]; SrLatePolicyTest 6 of 6 (the banner case at "no lock banner" [R3]; sale, Mark Paid, skip, stock and the Sales Submit's words [R2]); AmoLatePolicyTest 6 of 6 (the same, the banner case at "no lock banner" [R3]); BundleUnitTest.theLateSalePolicyIsInTheDeviceBundle (no such key) and TsoManualV0130Test (`server` where `both` was expected) [R5] |
| B | R1: `lockApplies` always true | `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*LatePolicyLockTest*'` | BUILD FAILED in 13 s as intended, 4 of 5 (the predicate case, both capture cases at "does not lock", the scheduled case); the server parity case passed (the set itself was not changed) |
| C | R4: CaptureRepository reads the policy at the capture's `captured_at` (no trusted now) | the same | BUILD FAILED in 13 s as intended, 1 of 5: the scheduled case, "expected FINAL_SUBMIT but was null" at 17:00 |
| merge | X2's test without the switch | the baseline run below | the one failure the merge brought, fixed by item 8 |

Verify (this worktree, laptop, through gl.sh; CI not run, nothing pushed):

| command | result |
|---|---|
| **baseline** (merged head 5c4fad492, before any change): `gl.sh … --continue :backend:app:test --tests '*FinalSubmitProjectionEndToEndTest*' --tests '*LateAfterFinalTest*' --tests '*LateAfterFinalDefaultPolicyTest*' --tests '*DayApiTest*' --tests '*AutoFinalSubmitJobTest*' :db:test --tests '*TsoManualV0130Test*' --tests '*TsoIntegrationV0141Test*'` | BUILD FAILED in 2m 20s, 24 backend tests, 1 failed: `FinalSubmitProjectionEndToEndTest` ("the job closes the open service zone-day", expected `[(2, 2027-01-02)]` but was `[]`); AutoFinalSubmitJobTest 5/5, DayApiTest 11/11, LateAfterFinalDefaultPolicyTest 1/1, LateAfterFinalTest 6/6; TsoManualV0130Test 17/17, TsoIntegrationV0141Test 3/3 |
| run 1 (uncommitted fix): the android, backend and db classes of the final row, without `--rerun` | BUILD FAILED in 3m 23s, 2 failures, both in the new stock cases' own setup: the second policy of a case reused the first policy's stock re-save guard (it lives in the app's SharedPreferences, `aron-stock-guard-<user>`, not the database), so the same three dozen were refused as `REFUSED_SAME_VALUES`. Fixed in the tests: each policy runs on a fresh phone (the guard is cleared). Everything else green, the backend and db classes included |
| run 2: `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*LatePolicyLockTest*' :android:app-sr:testDebugUnitTest --tests '*SrLatePolicyTest*' :android:app-amo:testDebugUnitTest --tests '*AmoLatePolicyTest*'` | BUILD SUCCESSFUL in 28 s: LatePolicyLockTest 5/5, SrLatePolicyTest 6/6, AmoLatePolicyTest 6/6 |
| negative A, B, C | as the table above |
| **final** (committed tree 2e5d75235, `git status` clean): `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*CaptureRepositoryDayLockTest*' --tests '*LatePolicyLockTest*' --rerun :android:feature-memo:testDebugUnitTest --tests '*MemoDayLockTest*' --rerun :android:feature-dayclose:testDebugUnitTest --tests '*SalesSubmitDayLockTest*' --rerun :android:app-sr:testDebugUnitTest --tests '*SrLatePolicyTest*' --tests '*SrRefusal*' --tests '*DayLock*' --tests '*LockGate*' --tests '*KitLock*' --tests '*StockSlipsTest*' --rerun :android:app-amo:testDebugUnitTest --tests '*AmoLatePolicyTest*' --tests '*AmoRefusal*' --tests '*DayLock*' --tests '*LockGate*' --tests '*KitLock*' --tests '*AmoRecordWriterReplayTest*' --rerun :backend:sync:test --tests '*BundleUnitTest*' --rerun :backend:app:test --tests '*FinalSubmitProjectionEndToEndTest*' --tests '*LateAfterFinalTest*' --tests '*LateAfterFinalDefaultPolicyTest*' --tests '*DayApiTest*' --tests '*AutoFinalSubmitJobTest*' --rerun :db:test --tests '*TsoManualV0130Test*' --tests '*TsoIntegrationV0141Test*' --rerun` | BUILD SUCCESSFUL in 2m 53s, 198 tests, 0 failures. core-database: CaptureRepositoryDayLockTest 9/9 (the LOCKED_TYPES parity among them), LatePolicyLockTest 5/5, RouteDayLockTest 9/9; feature-memo MemoDayLockTest 6/6; feature-dayclose SalesSubmitDayLockTest 1/1; app-sr: SrLatePolicyTest 6/6, SrDayLockBannerTest 5/5, SrDayLockCaptureTest 4/4, SrDayLockHomeTest 6/6, SrDayLockHostsTest 4/4, SrDayLockSaleTest 4/4, SrKitLockTest 8/8, SrRefusalHostsTest 6/6, SrRefusalSweepTest 13/13, StockSlipsTest 4/4; app-amo: AmoLatePolicyTest 6/6, AmoDayLockCallTest 1/1, AmoDayLockGateTest 5/5, AmoDayLockHostsTest 4/4, AmoKitLockTest 8/8, AmoRecordWriterReplayTest 16/16, AmoRefusalHostsTest 3/3, AmoRefusalSweepTest 15/15; backend:sync BundleUnitTest 6/6; backend:app FinalSubmitProjectionEndToEndTest 1/1, LateAfterFinalTest 6/6, LateAfterFinalDefaultPolicyTest 1/1, DayApiTest 11/11, AutoFinalSubmitJobTest 5/5; db TsoManualV0130Test 17/17, TsoIntegrationV0141Test 3/3 |

Asks:

8. **Lead:** D-X4a-26. Under accept_and_flag, should the TSO's Final Submit still open the SR's check-out (A3's rule)?
   As built here it does not: the rep is not locked and can still sell, so the check-out waits for the rep's own Sales
   Submit. The change back is one line in `SrDay`'s `salesSubmitted` (`isFinal` instead of `finalLocked`).
9. **Lead / docs owner (not changed):** docs/24 s9.5's default column for this key still says `quarantine (V0134 ...)`,
   and docs/16 DQ-57 and docs/17 s4.9 call quarantine "the default"; since D-TSO-MAN-49 the shipped default is
   accept_and_flag. Only the delivery column was changed here.
10. **Device lab:** s8 row 26 now has two variants: the default (a sale after the TSO's Final Submit is saved and printed
    on the phone with no banner, and the server accepts and flags it) and an admin-set quarantine or reject (X4a's lock as
    in ask 2).

Not done:

- Only the targeted classes above ran on the laptop. Not run: the full module suites of core-database, feature-memo,
  feature-dayclose, feature-stock, app-sr, app-amo, backend:sync, backend:app and db; `:android:app-sr:assembleDebug`,
  `:android:app-amo:assembleDebug`; `HardcodedStringScanTest` (no string or resource changed); `tools/ci/migrations-check.sh`
  (squawk is not on the laptop; V0142 has `SET lock_timeout` and one row UPDATE). The lead runs them on the Linux CI
  runners after the push.
- No composed (UI) test of the banner under the new default: the tests read `SrDay.dayLock` / `AmoDay.dayLock` (null),
  which is what `srDayLockBannerText` and `amoDayLockBanners` draw from; the composed banner itself is X4a's
  `SrDayLockHomeTest` (unchanged).
- No device has run either variant (ask 10). No web change: the portal's config console reads the delivery from the API.
