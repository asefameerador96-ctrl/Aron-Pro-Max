# Lead review: android-gates step 5 (b680476e..abfa1ef4)

Reviewed 2026-11-18 by the lead (Opus pass, two lenses). Read from code only: nothing built, run or walked on a device.

## close-and-marks verdict=pass
SUMMARY: Verdict for this lens (B1, the phone_mark marker, the settle gate): pass, with lows only. I read the code of abfa1ef4 (delta b680476e..abfa1ef4). Nothing was built, run or tried on a device.

B1 is fixed on every SR path I found. I grepped recordVisitClose, closeVisit, closeCall, abandon and session.close, and every SR visit_close is one of these:
- Sale-screen finish: SrSaleHosts.kt:186, leave { kit.closeCall() }.
- Back press of a fresh call and of a call restored after a kill: SrSaleHosts.kt:128 and :139, kit.closeCall().
- Gate 'Close the open call': SrCheckInGate, SrApp.kt:935, day.sale.closeCall(), with a catch.
- The phone's 04:00 end: SrDay.abandonStaleCall, sale.closeCall(phoneEnd=true).
- NoSale reasons: SrSaleHosts.kt:204, sale.closeVisit(code). It keeps the reason the rep picked and takes the same day.closeLock.

closeCall (SrSaleKit.kt:237) is the one classification. It reads the call's live (non-superseded) memo for the visit's date:
- memo with lines: sold, is_zero_sale=false;
- saved zero-sale or QC-only memo: zero_sale_stock_ok, true;
- survey only or nothing: abandoned, true (SrCallLock.abandon).

Two closes of one visit cannot both be written. Every close runs under SrDay.closeLock, and the second one finds visitSession.current null, because SrLockGate.closeCall closes the session after the write. A close stored before a kill stops restoreOpenVisit from bringing the call back. There is no 'already closed' check inside the recordVisitClose transaction itself; it relies on the lock plus the session. The only other session-closing path, endRefusedCall / endedBy, writes no close. Lock order is resumeLock, then closeLock, and nothing that holds closeLock calls resumeDay, so I found no deadlock. The AMO still goes through AmoDay.closeVisit under its own closeLock.

phone_mark is written in the same transaction as the close it describes:
- CALL_ENDED: VisitCloser.close or SrCallLock.abandon, then CaptureRepository.recordVisitClose, inside db.withTransaction.
- DAY_CLOSED: recordCheckOut, inside its transaction.
- AMO: the same, through sale.closeVisit and closeAbandoned.
- DRAFT_DROPPED and EDIT_DROPPED: written only when dropUnsavedDraft returned true. They cannot share a transaction because the draft is a file.

phone_mark is never uploaded. It is not in RecordMapping or the outbox, the support bundle dumps no tables, and grep finds it only in its entity, CaptureDao and the schema. The notice is rebuilt from the markers only, so it survives a kill. The rep's own close after midnight passes no mark, which the test theRepsOwnCloseAfterMidnightIsNeverToldAsThePhones covers.

The settle gate works on SR and AMO. AttendanceFlow(settleFirst=true) starts with checkInEnabled=false and settling=true, and checkIn/checkOut return Ignored while settling. The 'one moment' text is in bn and en. resumeDay's finally runs restore, settle, refreshDayGate and restoreRolloverNotice under NonCancellable, so a throw or a cancellation cannot leave the screen stuck. The location attempt runs outside resumeLock, guarded by fixLock.tryLock, so there is one attempt at a time, and closeForgottenDays re-checks forgottenDays() under the lock before writing. catching() rethrows CancellationException. SR Tasks now refuses through the named-reason WriteGuard. The SR Close button and the leave paths catch a failing close, keep the call and show att_close_failed.

Main low: the closeCall KDoc claims a sale committed between the memo read and the close cannot be missed. SaleFlow's commit does not take closeLock, so a Save that lands at exactly 04:00 can still be closed as 'abandoned'.

Not checked:
- runtime timing of the races;
- the B2 / parent_uuid lens;
- whether the 10.json schema matches the generated AutoMigration (I trusted the build and Migration910Test);
- the merge resolution of the AMO look and SaleScreens;
- the server's handling of a memo that arrives after an abandoned close.

Worktree I used: /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/wt (detached at abfa1ef4).

LOWS:
- The docstring overstates what the lock covers. SrSaleKit.kt:237-249 (closeCall) says 'the memo read and the close are under ONE lock, so a sale committed between them cannot be missed'. But SaleFlow/SaleCommitStep never take day.closeLock (grep: closeLock is used only in SrSaleKit.closeVisit/closeCall and SrDay), and recordSale does not check for an existing visit_close (CaptureRepository.kt ~131-160). Scenario: at exactly 04:00 the 04:00 timer runs resumeDay, then abandonStaleCall, then closeCall(phoneEnd=true), which reads no memo. The rep's Save-and-print commits the memo, and then abandonLocked writes 'abandoned' / is_zero_sale=true for a sold call. The window is milliseconds at the 04:00 boundary only. This is the step-4 low carried over, now with a false comment. Fix: take closeLock in the commit, or refuse a memo for a closed visit inside the recordSale transaction.
- The NoSale reason rows are not caught. SrSaleHosts.kt:204 runs scope.launch { ...closeVisit(code); onDone() } with no try. SrLockGate.closeCall rethrows any non-DayRefusal (SrDayLockGate.kt:48-50), so a persistent write error crashes the app on every tap of a reason. The picked reason itself is kept correctly (closeVisit(code) under closeLock). Only the gate Close and the back press / finish ('leave') got the new catch.
- Gate Close and phone end say nothing when the route-day refuses the close. If closeCall returns a DayRefusal (Final Submit or Sales Submit standing; endCallIfClosed / SrCallLock.endedBy), SrCheckInGate (SrApp.kt:933-936) treats it as ok and goes Home without a text. In abandonStaleCall the session is closed with no visit_close, so no CALL_ENDED marker is stored and the rep is never told that the phone ended his call. This is honest, since nothing was written, but the rep gets no explanation.
- DRAFT_DROPPED and EDIT_DROPPED markers are written after the draft drop, not with it. SrDay.kt:552-553 and :569-570 (the same in AmoDay ~327, ~339). The draft is a file, so no shared transaction is possible, and the KDoc on addPhoneMark says so. A kill between dropUnsavedDraft and addPhoneMark drops the draft and never tells the rep. Also, after a phone close, a kill before the drop leaves the draft file of a closed visit in the store with no notice. No data harm.
- Dismissing the notice is not durable across a kill. dismissRolloverNotice sets dismissedUpTo in memory and deletes the markers on a background coroutine (SrDay.kt:521-526, AmoDay). A kill before that delete shows the same notice once more after relaunch. This is acceptable, but 'read once then cleared' is only approximate.
- The rollover test was turned into a tautology and points to a test that does not exist. SrMidnightRestoreTest.kt ~229 now closes each 'rep' call with sale.closeCall(), the same function the phone end uses. So theAutomaticEndWritesWhatTheRepsOwnWayOutWrites can no longer catch a disagreement between the two paths. The explicit kinds are asserted in SrCallCloseExitsTest, but the comment names 'SrCallCloseClassificationTest', which does not exist. No assertion is weaker, so I do not count this as loosening.
- One B1 exit has no real-button test. The sale-screen FINISH (SrSaleHosts.kt:184-187, onFinished -> leave { kit.closeCall() }) is untested, as the lane says. I read the code and it is right: a zero-sale goes through confirmZeroSale -> Review -> Save, so a memo with lineCount 0 exists when onFinished runs, and it classifies as zero_sale_stock_ok exactly as the old draft.zeroSale flag did. A finish with no saved memo now writes 'abandoned' instead of 'sold', which is more correct.
- The AMO marker paths have no tests, as the lane says. I read them: AmoDay.closeVisit(phoneEnd) passes the CALL_ENDED mark into sale.closeVisit / closeAbandoned, then into recordVisitClose, in one transaction. DAY_CLOSED goes through recordCheckOut. The AMO rep's own close (MainActivity.kt:338, ControlCallHost.kt:102) passes no mark. This is the same shape as the SR and correct as read. MainActivity.kt:338 dayScope.launch { day?.closeVisit(); back() } still has no catch for a non-refusal failure, which is the AMO counterpart of the SR Close crash.
- The settle gate is ended by the second caller, not the first. If the start's resumeDay holds fixLock during a forgotten day's location attempt, the ON_RESUME caller's tryLock fails, and its finally runs attendance.settle(). Checked: attendanceForScreen() then restores the earlier open check-in, so checkInEnabled=false (inAt != null) and check-out stays off (pastDay). No D+1 check-in is possible, and closeForgottenDays re-checks forgottenDays() under resumeLock before writing. Not a trap, but the 'one moment' banner disappears while the day is still being closed. On Home, a check-in tap during settling is silently Ignored (AttendanceFlow.checkIn) and the banner appears only on the Attendance screen.

## parents-migration-merge verdict=pass
SUMMARY: Verdict: pass. B2, the Room migration and the merge hold up; nothing blocking. Read statically at abfa1ef4 against b680476e and the merge 8753e96a. Nothing was run here (no Android SDK in this container). CI run 44235716898 on abfa1ef4 is green for the Android unit tests, lint and release APKs; only the Web job failed and Infra was skipped.

**B2 (Send again)** — no rank fallback remains. How each record type finds its parent now:
- memo_line, memo_discount, qc_line: their memo, through the domain tables.
- print_event: its memo, through a new join on print_event.memo_client_uuid.
- AMO distribution_check_line and call_assessment_answer: the new outbox.parent_uuid.
- Every rank ≥ 1 row also counts the family's visit as a parent.

The reader (RoomDayAdapters.kt:103-106) and the SQL guard (OutboxDao.kt:251-260) apply the same rule. Rank-1 AMO records, price_compliance_check and survey resolve to the visit, which matches the server's TypeRules. The TSO answer (rank 1, family = its assessment) is covered by the family rule.

Both earlier failing cases are tested through production code (RoomDayAdaptersTest):
- A refused survey next to an exhausted print: Send again is offered and the SQL really re-queues one row. If the print's memo is refused for good, it is withheld.
- A refused assessment next to an exhausted distribution line: same result, withheld only when its check is refused.

**parent_uuid stays local.** It is set at capture in RecordMapping.amoRecord (from check_client_uuid / assessment_client_uuid in the payload). It is not part of record(), so payload_json, payload_sha256 and sig are byte-identical to before. SyncEngine uploads only payloadJson. TsoOutbox builds OutboxEntity with named arguments, so it is unaffected.

**Old rows** keep parent_uuid NULL. That never crashes and never wrongly withholds Send again. Memo lines and prints still find their parent through the domain tables, which existed in version 9. Only AMO children lose the guard, which is a low (see lows).

**Room 9 -> 10.** Version 10 adds outbox.last_message_key, outbox.seen_at and outbox.parent_uuid (all nullable TEXT, no defaults) and the phone_mark table (no indices, never uploaded). It uses AutoMigration(9, 10). 10.json matches the entities: the column is added last on the outbox, phone_mark matches its entity, and the hash was regenerated. There is one 10.json on the branch and the integration branch is still at version 9. 9.json is untouched. Migration910Test fills a version-9 outbox with rows in five states, runs runMigrationsAndValidate, and checks that every row survives and the new columns and table work. A version-9 phone with real data only gets columns and a table added, so no data is lost.

**Merge of integration 951292a4.**
- SaleScreens.kt: the first resolution was broken (a SaleTags object sat where the SKU items(...) call belonged). Repair 167267a7 fixed it: one SaleTags (QC_ONLY, STOCK_FIRST, PRINT_LATER), and the items call passes both this lane's rewardBase and the parity lane's amoCard. The stock stop (freeBase net of the rep's own reward packs) is also enforced on the AMO card (plusEnabled, coerceIn(max)).
- SaleRoute.kt keeps onOpenStock and every AMO host option.
- AmoSaleHosts.kt keeps this lane's refusal parameters (seen, onSendAgain, outbox and the rest) alongside blueSubmit, plus the parity header and print-later options and the MemoDetail editDisabledReason (DayRefusal).
- Guard references in AmoSaleHosts, AmoOutletOwnForms and ControlCallHost are the same count before and after the merge.
- DECISIONS.md: no entry from either side is missing and there are no conflict markers.
- Goldens came from the integration side and CI's Android unit tests are green on the head.
- No edit to core-ui in the delta.
- The three new strings are in both values/ and values-bn/. (The values-bn att_amo_already_in/out lines are English; that comes from the integration side.)
- closed_by is not on the wire.

**Not checked:** nothing ran on a device; I did not compile, so the regenerated Room identity hash is unconfirmed (it looks regenerated, not hand-edited); I did not re-run the lane's mutation proofs; the B1 code paths and the AMO write-guard host tests were outside this lens.

LOWS:
- Rows queued before the migration get parent_uuid NULL, so a version-9 AMO `distribution_check_line` or `call_assessment_answer` has no parent link. Scenario: an AMO phone upgrades with lines still pending, their check is later refused for good (schema_invalid), the line runs out of retries. RoomDayAdapters.kt:105 and OutboxDao.kt:255-260 then offer Send again. Each resend can only come back parent_missing, exhaust again and offer Send again again, and an exhausted row cannot be marked seen. Before this delta the rank fallback held such rows back. Nothing is lost and nothing is wrongly withheld; it is a loop the rep cannot end. Fix: an AutoMigrationSpec.onPostMigrate for 9 -> 10 that fills outbox.parent_uuid from amo_record.payload (check_client_uuid / assessment_client_uuid) for those two types. It changes local metadata only, never payload_json or sig. Rare at pilot size.
- Some parents the server requires (backend TypeRules.kt:25-38) still sit outside the guard, because the SQL looks for the parent only inside the same family (`p.family_uuid = outbox.family_uuid`, OutboxDao.kt:255). Scenario: a memo reprinted after its route-day is submitted becomes a rank-0 print_event in its own family (RoomPrintLedger.kt:62-64). If its memo was refused for good, the print exhausts on parent_missing and Send again stays offered. The same gap covers a due_collection against a refused memo (rank 0, `against_memo_client_uuid`) and an edit memo whose superseded memo was refused (`supersedes_client_uuid`). This predates the delta and is not the B2 defect (nothing is withheld), but the parent set is not yet the server's.
- RefusedRecordsTest.kt:148: the test that a refused parent withholds Send again now sets `parent = memo` on a raw memo_line outbox row. Production never sets outbox.parent_uuid on a memo_line; its parent comes from the memo_line table join. The assertion is unchanged and the test is not loosened, but it now runs a path production does not take for memo lines. Insert a memo_line domain row instead.
- Stale wording: the AronDatabase.kt:57 comment on AutoMigration(9, 10) still names only D-AGATE-04 (not parent_uuid / phone_mark), and Migration910Test's name still says `AddsTwoEmptyColumns`.
- Version 10 changed its identity hash inside the same version (1da71bfa -> d01744bf). A device holding a step-4 lane build at version 10 would fail Room's integrity check on open, and destructive migration is off. The integration branch never shipped version 10 and the lane says nothing ran on a device, so this only matters if a lab phone got a lane APK.
- Migration910Test fills only outbox rows in its version-9 database, not amo_record, print_event or memo rows. The migration only adds columns and a table, so the risk is low.
