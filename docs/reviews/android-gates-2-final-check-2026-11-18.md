# Lead final check: lane/android-gates-2 at c64e60fb (before the Android train)

Reviewed 2026-11-18 by the lead (Opus pass, two lenses). Read from code only: nothing built, run or walked on a device.

## gates-and-writers verdict=pass
SUMMARY: Read-only review of what lands, ffbe8d6fd...c64e60fb (59 files, android/ plus DECISIONS and the status file). I looked hardest at the change since the lead's pass at 6a86c275: the lows round cb326e99/4af157d6, the integrity-train merge 736ec1cf, the scan change 662a4e74 and the base merge c64e60fb. Nothing was built, run or walked on a device. I found no CI result for c64e60fb.

**Every writer and its gate (final tree)**

CaptureRepository: ActionGate.requireOpen() is the first statement in every operational writer: recordAttendance, recordStock, recordVisitOpen, recordSale, recordNumberedSale, recordVisitClose, recordDueCollection, recordAmo, recordVisitSkip, recordContentView, recordSurveyResponse, recordDaySubmit, recordCheckOut, recordClusterCreate, recordOutletRequest(s) and resolveTask. In recordStock, recordSale and recordNumberedSale the call comes before the require checks and the transaction; in the others it is the first line inside the transaction.

Exempt, by the scan's own list: recordBreadcrumb, recordMediaMeta, recordDeviceStatus (the integrity and device status reports keep flowing while the gate is up) and addPhoneMark (local only).

The three exemptions the owner was told about are exemptions from rule 1 (check-in), not from the Developer options gate:
- writeDaySubmit (Sales Submit) and recordVisitClose (close open call) have no requireCheckedIn.
- RoomPrintLedger (print and reprint) never asks the gate.

Writers outside the repository:
- TSO: TsoOutbox.enqueueAll (enqueue goes through it) gates before its transaction; the TSO Final Submit POST gates first. The TSO has no check-in gate, as the owner's pending decision says.
- SR and AMO visit commit: the gate runs before FinalSubmitLock, the photo claim and recordVisitOpen.
- AmoRecordWriter and SR saveSurvey write through recordAmo and recordSurveyResponse.
- Programme records: none are on this branch.
- Ungated system records outside the repository: consent_accept (ConsentRepository), config_ack (ReferenceRepository), print_event (RoomPrintLedger), ActivityLog and ErrorReporter.

The merge took nothing away from the integrity train:
- The diff against the base removes no ActionGate line.
- core-system, core-common and app-tso are unchanged against the base.
- All four activities (SR, AMO and TSO MainActivity, SR OutletMapActivity) use setGatedContent; there is no plain setContent in the app or feature modules.

So with Developer options on, the app is blocked at activity level and at writer level:
- The UI is not composed at all.
- A writer raced in the window throws GateClosedException. It is a CancellationException, so it ends the coroutine quietly with no crash. AttendanceFlow, saveCallStep, catching and SaleFlow all rethrow it.
- catchingLive in resumeDaySafely turns it into a logged failure.
- It never raises the close lock-out counter, because catching rethrows before closeFailures.failed is reached.

**Check-out, end to end**

Hold, then pressLock (a double tap waits and then finds the day checked out), then the press check of open work, then busy, then the press-time meta, then the fix read, then recordCheckOut in one transaction:
1. The Developer options gate.
2. The purged replay check.
3. The open-work guard (rep only; it reads openWorkNow plus the stored call through the same lookup restoreOpenVisit uses, and takes no lock).
4. The "open check-in, no check_out yet" check.
5. The automatic Sales Submits, built inside the transaction, one per route-day without a standing submit; they get the lower outbox seq.
6. The fix and the check_out row (closed_by=phone only with a DAY_CLOSED mark of that date).
7. The phone marks.

A kill leaves all of it or nothing; after a relaunch the stored check_out stops a second one. Order on the wire: the submit waits for the day's earlier rows, and the check-out waits for the submit. OpenWorkAtCheckOutException rolls everything back and is shown as Refused with the open-call words. The phone's own close passes no guard, so a forgotten day is never refused by it.

**Stock limit**

SaleCommitter is the only caller of recordSale and recordNumberedSale, and it always passes enforceStock=true. requireStockHeld runs before a memo number is reserved and again inside the saving transaction, reading stored rows: issue + adjustment − qc_return − lines of live memos − reward packs. Local unsynced memos therefore reduce the stock. An edit gets back the quantity of the memo it supersedes. Stepper, typed entry, edit and Force Sale all end in this one commit path. A replayed memo skips the check, and nothing new is written for it.

**Tests and scans**

- No test was deleted or weakened. The removed assertions were replaced by stronger ones: closed_by is read from the queued payload; the 'own date' case is now in CheckOutSubmitTest; the wording assertion follows the new string; visitSession.close() became the real closeVisit.
- The exempt list of EveryWriterIsGatedTest is unchanged. All new user-facing strings exist in both values and values-bn.

**Not verified**

That anything compiles or passes. The Compose and Back behaviour of the lock-out overlay on a device. Real midnight and fix timing. FakeIngestServer's fidelity to the real server. Whether an AMO step is ever saved after its call's visit_close; I found none, but did not walk every host.

LOWS:
- EveryWriterIsGatedTest.theWritersOutsideTheRepositoryAreGatedToo is a fixed list of four files, not a discovery scan. A new direct outboxDao().insert or withTransaction writer in an app module would not fail it, and neither would a second visit committer in SrDay or AmoDay: the test only compares the first indexOf of the gate with the first indexOf of recordVisitOpen. For TsoReadModels it only checks that the gate string appears somewhere in the file, so moving the Final Submit gate after the POST would still pass. Five writers outside the repository sit in neither list: ConsentRepository (consent_accept), ReferenceRepository (config_ack), RoomPrintLedger (print_event, which is the reprint exemption), ActivityLog and ErrorReporter. All are system or describe-records and the activity gate covers their UI, but the KDoc's claim that the test covers 'the writers outside CaptureRepository' is wider than the test. Suggest a scan of android/*/src/main for outboxDao().insert and withTransaction against a named allow-list.
- AmoRecordWriter outlet verification (pre-existing, outside the lane's diff): claimPhoto runs before capture.recordAmo, so the gate is not first. In the race window (Developer options switched on during a Save) the photo is attached to a record that is then refused. The same reason the SR and AMO visit commits gate before the claim ('a refused visit must not leave a claimed photo behind') applies here. The AMO and SR saveSurvey claims come after the transaction and are fine.
- The midnight race is narrowed, not closed. forgottenDays() and the closeForgottenDays loop look at attendance.state.busy, but busy is set only in capture(), after pressLock and the suspending openWork read. Scenario: the rep presses at 23:59:59.9; the midnight timer's resume runs forgottenDays() at 00:00:00 while the press is still in the openWork read (busy is false), and the phone starts a close of D. If the phone's commit lands first, the rep's press gets Failed and the day is recorded as closed_by=phone. There is still never a second record (the in-transaction 'no check_out yet' check). Setting busy, or an in-flight flag, at the top of checkOutLocked would close it.
- SaleFlow replay notice: 'differs' compares only netMtk and dueMtk between the stored memo and the draft on screen. A later change that keeps both totals equal, such as a SKU swap at the same price or a quantity moved between lines, answers with the stored memo and clears the draft with no 'later changes not saved' dialog.
- CallAlreadyClosedException (D-AGATE-25) reaches the SR survey path as SurveySaveResult(dayRefusal = DayRefusal.of(e)) and the AMO step path as StepSave(false, DayRefusal.of(e)). I did not check what DayRefusal.of returns for this type; it is probably null, so the rep would see the generic 'not saved' text rather than 'this call has already ended'. There is no crash either way.
- While the close lock-out is up, the rep cannot open the sync sheet, log out or print, because the overlay covers the shell. Background sync still runs. This is the lead's approved design; the status file lists it as device-walk item 1.
- There is no recorded CI run for 6a86c275 or c64e60fb (the status file lists a run for pushes 1 and 2 only). The mutation proofs in D-AGATE-24 and -25 were not re-run here.
- The new D-AGATE-25 tests use Room-executor holds with withTimeout/withTimeoutOrNull guards (3 s and 10 s). They are safe when the code is correct, but on a very slow CI machine forgottenDaysIsEmptyWhileTheRepsCheckOutIsInFlight could time out falsely.

## lows-round-and-merge verdict=pass
SUMMARY: Read-only review of lane/android-gates-2 at c64e60fb against base ffbe8d6fd. Lens: the lows round, the two merges, and the tests. Nothing was built, run or walked on a device. I did read `gh run list`: the ci run on c64e60fb is green. The run on 662a4e74 failed; I did not read its log. The base's train-121 gitleaks note may be the cause, but that is a guess.

Commits since 6a86c275: cb326e99 (the lows round), 4af157d6 (DECISIONS addendum only), 736ec1cf (merge of train 121 / integrity, plus the EveryWriterIsGatedTest extension), 662a4e74 (test fix) and c64e60fb (merge of the base; no lane-side content in android/).

The eight lows of D-AGATE-25, one by one:
1. `forgottenDays()` returns empty while `attendance.state.busy`, and `closeForgottenDays` breaks out of its loop when the rep presses. Real fix.
2. The check-out shows the stored `event.meta.capturedAt`. Real fix, and it matches what a restore shows.
3. Lock-out screen: it consumes touches; the hosts add `BackHandler` and `clearAndSetSemantics`. `CloseFailures.failed` now counts failures within 10 s as one attempt, with a value format of `count|code|lastMs`. `lockedOut` still reads old `count|code` rows. Real fix. The older lock-out tests now move the trusted clock a minute per resume, which is the right adjustment, not a loosening.
4. `recordAmo` (non-own families only) and `recordSurveyResponse` throw `CallAlreadyClosedException` inside the transaction. The check sits after the gate and after the replay check, and nothing is written. The AMO host order is assessment Save, then `leaveCall`, so no legitimate flow writes after a `visit_close`.
5. `catchingLive` rethrows only the caller's own cancellation. The four AMO `runCatching { resumeDay() }` sites and the SR `reload()` sites now go through `resumeDaySafely` / `reloadSafely`. Logs carry the exception class only.
6. The race tests no longer sleep or poll: both coroutines start UNDISPATCHED, the test checks `check(closeLock.isLocked)` and an exact `submittedCount`, and the end-state assertions are unchanged.
7. The replay sets `laterChangesNotSaved` and the sale screen shows `review_later_*` in bn and en.
8. The device-walk list is in `docs/status/android-gates-2.md`.

The addendum honestly records one gap: removing the touch-consuming `pointerInput` does not fail its test.

Merges:
- 736ec1cf conflicts: AmoDay and SrDay keep the lane's check-out guard and drop the `DeviceOwnerPolicy` block, as the integrity train intended. RecordMapping keeps the integrity `signals` / `SPOOF_CODES` and the lane's `closedByPhone` parameter. CaptureRepository.recordCheckOut keeps `ActionGate.requireOpen()` first, then purge and require, then `guard?.invoke()`. SaleRoute keeps the parity `CreditDialog`, `manual = quantityPopup` and the lane's replay dialog. The strings files keep the qc_* keys and the review_later_* keys.
- Nothing from the integrity train is lost in the final diff against the base: `setGatedContent` is still in the SR and AMO MainActivity, and no integrity, GeoFix, lab-exemption or device-status file is touched. No line removes `requireOpen`.
- Strings: in feature-sale, feature-attendance and feature-amo, values and values-bn have no duplicate keys and identical key sets. `sale_yes_ok` exists. No new hardcoded user-facing text; the new Log.w lines are logs.
- 662a4e74 is a real fix, not a loosening. The old regex matched `import androidx.room.withTransaction` at line 6, and `enqueue` delegates to `enqueueAll`, the only write in TsoOutbox.

Tests changed since 6a86c275:
- No @Ignore, and no allow-list widened in EveryWriterIsGatedTest (the exempt set is unchanged), EveryActivityIsGatedTest or HardcodedStringScan. The last two are not in the final diff at all.
- The removed `anAuditSaveWaitsForTheCloseLockAndLandsAfterIt` was replaced by `AmoAuditLockWiringTest`, which proves the production wiring; the lead asked for this and D-AGATE-25 records it.
- Goldens: none changed by this lane. The srp1b_* images in `6a86c275..c64e60fb` come from the base (parity-sr train 120), and the final diff has no png.

Outside android/ in the final diff: only DECISIONS.md (5 lines added, 0 removed, so append-only) and `docs/status/android-gates-2.md`. Both belong to this lane. The shared/contract and backend changes in `6a86c275..c64e60fb` come only from the base.

None of the blocking conditions applies: no writer can write while the gate is closed outside the exemptions, no sale beyond stock, no check-out that skips or doubles the submit, no legitimate operation blocked by a gate, no crash path found, no hardcoded string, no weakened test or scan, and no base work reverted.

Not verified: compile, unit tests, lint, the merged UI on a device (Back, TalkBack, the lock-out look, the Bangla dialogs), and real midnight or 04:00 timing.

LOWS:
- Back on the lock-out screen (SrApp around line 372, AMO MainActivity around line 351). The host's `BackHandler(enabled = closeLockout != null) {}` is composed BEFORE the screens below it, and those screens have their own `BackHandler`s (SrApp, SrSaleHosts, SaleExtraScreens, the AMO hubs and hosts). Those register later and therefore win. Scenario: the lock-out shows while the rep is on a sale or outlet-hub screen; system Back runs that screen's handler and navigates invisibly underneath. Nothing is written, since the day is locked. Fix: put the no-op `BackHandler` inside `DayCloseFailedScreen`, which is composed last. This is already a device-walk item; the code shows it would fail that walk.
- SR survey refused for a closed call goes unworded (CaptureRepository.recordSurveyResponse is also used by SrDay.saveSurvey). `SurveySaveResult` maps `CallAlreadyClosedException` to `dayRefusal = DayRefusal.of(e) = null`, so CallContentHost calls `onDone()`. The answers are silently not stored and the rep moves on to the sale, where the Save is then refused with CallClosed. On the AMO side, `saveCallStep` returns `StepSave(false, null)`: a generic not-saved, with no 'call ended' text. No crash, nothing wrong is stored, but the rep is never told why.
- Busy guard cost (SrDay/AmoDay `forgottenDays`). `busy` is also true during a check-IN press, and when a rep's check-out press then fails (`AttendanceResult.Failed`). If the midnight timer fires during such a press, the forgotten-day close is skipped and the timer re-arms for the next midnight. The day then stays open until the next ON_RESUME, call end or Try again. Rare; worth noting in the device-walk list.
- Race tests (SrSaveCloseRaceTest, AmoSaveCloseRaceTest): the old assertion that the FIRST coroutine's command is at the held executor (`exec.waiting() == 1`) was replaced by `check(closeLock.isLocked)` plus an unchanged `submittedCount` for the second coroutine. The test no longer asserts that the first coroutine reached Room before the second started. The mutation proof (commit lock removed, all 8 tests fail) suggests it is still sensitive. A slight loss, not a weakening of the rule under test.
- SaleRoute replay dialog: `laterShown` starts true for each `committed.memoUuid`, so `review_later_*` can show on top of the SaveAndPrintDialogs print prompt (two stacked dialogs). Also, `laterChangesNotSaved` is never cleared in SaleUiState; only the composable flag hides it. Cosmetic; device-walk item 6 covers it.
- AmoAuditLockWiringTest covers only `saveAssessment` under `callLock`. Joint call, survey and price check are under `underCallLock` by code reading only, which is the same coverage the replaced test had.
- DECISIONS.md: the D-AGATE-22..25 entries sit after D-AGATE-21 in the middle of the file, not at its end. Additions only (0 removed lines vs base), so append-only holds; only the ordering is unusual.
- docs/status/android-gates-2.md HANDOVER does not record the post-merge pushes (736ec1cf, 662a4e74, c64e60fb) or their ci results. The ci run on 662a4e74 failed and the one on c64e60fb succeeded (from `gh run list`); the failure's cause is not written down.
