# Lane android-gates — status

## ROUND 2 FROM THE LEAD (2026-11-17), copied verbatim so another session can carry it

Lead to A-GATE1, in four messages (the last three after the fleet restart). Slice 1 (`ec7b807a0`) is GATED (Opus pass, 2 lenses, no blocking finding) with 10 lows; slice 4 (`40731485b`) did NOT pass; slice 2 (`b7caeb46e`) and slice 3 (`74f588332`) are pushed and not yet reviewed. Status of this round: NOT STARTED when this was written (the work of slices 2 and 3 came first); see "Round 2 progress" below for what is done.

**A. Slice 1 lows, to fix in the next push, each with its test:**
(1) a draft opened before midnight and saved after: the screen reads stock for the draft's date, the transaction for today's: read both from the same date (the memo's), and make the refusal text and the row agree. (2) refuse an edit whose target memo is already superseded (stale list), with a clear message. (3) the row's max must subtract the SKU's reward packs like SaleFlow.setQuantity does; the "only N held" text uses the net figure. (4) StockHeld counts only issue/adjustment/qc_return while stockBalanceOn subtracts other kinds: use ONE definition of "held" for both. (5) when the transaction refuses at save time the rep must see the specific over-stock message with SKU, held figure and unit (not a vague one), and the OverStockException catch needs a test that really reaches it. (6) Bangla lines must show the Bangla SKU name where one exists. (7) "No stock of X" on every row when no stock is entered at all is noisy: show ONE banner on the list ("add today's stock first", with a way to the Stock screen) and keep rows quiet. (8) status file: correct the golden claim (2 of the 7 changed more than the + button: say what), and the kill-after-commit claim for stock Save (attempt UUIDs are in memory: state the real behaviour after a process kill, and whether a re-entry after relaunch can double an issue; if it can, persist the attempt key). (9) AmoDayLockGateTest: do not swallow a failed stock seed. (10) add tests: edit changing SKU or unit; a stock row of another business date; AMO joint and control call reaching the gate.
Also (message of 2026-11-13): relax the memo username rule in android/core-database/.../repo/MemoNumbers.kt:12 from `^[a-z][a-z0-9]{3,31}$` to `^[a-z][a-z0-9]{2,31}$` (the real field has 3-character rep ids; server and contract 1.18.0 widen to the same pattern); check every other place in the Android code that assumes the old length (login field validation, memo number formatting and parsing, the printed memo width with a 3-character prefix); add tests with a 3-character username end to end through a memo number; say in the report which app versionCode first carries it.

**B. Slice 4 (D1) findings, each fixed with a test that fails before the fix:**
D1-1 (3/3, both lenses). "Seen" changes what the server is told: RoomDaySource.outboxStates() now filters seenAt == null, and that result feeds uploadGate and RoomDaySubmitWriter (rejected_count / quarantined_count in day_submit). RULING: "seen" is a SCREEN matter only. The true states (every refused row, seen or not) feed the day_submit counts and the gates exactly as before your change; only the banner and list use the unseen view. Test: mark 3 seen, submit, the day_submit payload still says 3.
D1-2 (3/3, both lenses). A retry_exhausted row can be marked seen, then it has no Send again anywhere while the count mismatch keeps Submit blocked: a trap. RULING: a record the phone gave up on (never reached the server) cannot be marked seen; its only actions are Send again (and it stays in the main list until sent). If any seen row is retry_exhausted from an older build, show Send again in the seen list too.
D1-3 (3/3). seen_at survives a later state change: a quarantined record marked seen and later discarded or re-refused by the server never returns to the count. RULING: seen applies to one (state, last_code) outcome; any change of state or code clears seen_at (applyResolution, releaseQuarantined, applyAck paths), so a NEW outcome is shown again.
D1-4 (3/3). The read-failure guard does not cover the same refusedRows() read inside outboxStates(): a damaged read crashes the screen every time. One guarded read, used by both; the gates fail CLOSED with the error state when it fails (Submit not offered on unknown data).
D1-5 (2/3). Send again re-queues ONE row, not its family: a memo can go up without its lines, or lines before their memo (parent_missing, exhaust again). RULING: Send again acts on the whole family of the record (parent and children that are retry_exhausted), in the engine's normal family order, one button per family in the list (the list groups a family as one entry: "Sale to <outlet>, 8 lines").
D1-6 (2/3). Texts say what happened but not what to do: for every outcome where the record is NOT stored (dropped: unknown_outlet, schema_invalid and the like) the text must say plainly that the sale/collection is NOT on the server and what the rep does now (tell the TSO/AMO; where it makes sense: do the sale again from the Memo screen), and the record must be exportable to the support file with one tap from that entry. Correct meanings: business_date_out_of_window also covers a phone clock AHEAD (do not say only "too old"); memo_no_invalid per docs/24 s4.5. Replace "it cannot be doubled" with the provable claim ("sent with the same id; the server keeps it once").
D1-7 (1/3, but do it). A Room 9 to 10 migration test (runMigrationsAndValidate to 10 with a populated outbox), since SR, AMO and TSO share the database; correct the status file, which says one runs.
Lows in the same push: refusedRows() must not SELECT * with payloads and no limit over rows that are never purged: select only the columns the list needs, count with COUNT, and page the "earlier" group; fix the KDoc that says purge bounds it; "seen" gets an undo (move back) since a mis-tap is otherwise permanent; outbox_codes in the support file: newest first, included in the size-cap dropping order, with a test; reasonOfCode: a static code-to-R.string map (compile-checked, shrink-safe) instead of getIdentifier; transport codes get texts; the send-state line is scoped to the business date and shown per family, as ruled.

**C. Order and rule:** "Then your earlier 10 lows of slice 1, then push slice 2 (check-in gate) as its own push. One push per slice, every lane ci job, "checked <sha>"." (Slices 2 and 3 were already pushed before this round arrived; the fixes of A and B go in their own push(es), and any review finding on 2 and 3 after that.)

## Round 2 progress

(Updated as each item lands; nothing below is done yet unless it is ticked.)


## HANDOVER (2026-11-17, written at the end of slice 3)

All four slices are built, pushed and were CI-green when pushed (slice 1 `ec7b807a0`, slice 4 `40731485b`, slice 2 `b7caeb46e`, slice 3 `74f588332`). Nothing has been walked on a device. What is left is not code of this lane: (1) the lead's Opus pass on each diff, (2) a device walk of the four rules (the plan: stock stop and Save top-up; no check-in locks and the screens; check-out with a forgotten Sales Submit, offline, then sync and watch the server order; a refused day_submit; a day left unchecked-out and the next start), (3) the server requests in `docs/requests/android-gates.md` (over-stock flag, no-check-in flag, forgotten-day check-out flag, `attendance_duplicate` as an accepted duplicate). Build traps: `tools/android-sdk.sh` then `echo sdk.dir=/opt/android-sdk > local.properties` (git-ignored); a fresh container needs it again; run Gradle in the foreground; the full gate is `testDebugUnitTest` for every module, `assembleDebug` for the three apps and `lintDebug`. Behaviour to expect on the FIRST start of this build on a phone that holds an old open check-in (a test phone): `closeForgottenDays()` closes that old date at once (a submit if it had captures, and a flagged check-out); a date beyond the server's window may come back quarantined, which does not lock the phone.


Branch `lane/android-gates` from `origin/claude/wonderful-thompson-k6ejnf`. Android only: no server code, no contract
change. Specification: `DECISIONS.md` D-OWNER-PREREQ-01 (owner) and D-LEAD-PREREQ-02 (lead),
`docs/reference/prereq-gates-map-2026-11-13.md` (the map, re-checked against the code before building on it),
`docs/device-lab/live-walk-report-2026-11-13.md` (defects D1, D2, force-sale state).

Slices: **1 stock gate — done (pushed ec7b807a0, CI green)**, **4 defect D1 — done (pushed 40731485b, CI green)**, **2 check-in gate — done (pushed b7caeb46e)**, **3 check-out submits the sales — done locally (push 4)**. My own decisions
are `D-AGATE-01`..`03` in `DECISIONS.md`. **Nothing in this lane has been walked on a device.**

## Slice 1, the stock gate (owner rule 2, device defect D2)

### The one formula

`core-database/.../repo/StockHeld.kt` is the single source: held = `issue + adjustment - qc_return - sold - reward packs`
per SKU in base units over live memos; a SKU with **no stock row today holds zero**, never "unknown". `StockTracker`
(feature-stock) now delegates its live-memo and reward maths to it, so the Stock screen, the Home card, the sale catalog
and the in-transaction re-check cannot drift.

### Gaps of the map

The numbers are the claimed gaps of the stock-gate section of `docs/reference/prereq-gates-map-2026-11-13.md`. Each one
was re-read against the code at `4e0645556` before it was acted on; all of the ones below held.

| # | Gap | What I did | Where |
|---|---|---|---|
| 1 | Over-stock warns, Save stays enabled, the committer writes | **Closed.** Three layers: entry clamp, `ReviewProblem.OverStock` (Save off), re-check inside the saving transaction | `SaleReview.kt`, `ui/SaleScreens.kt`, `CaptureRepository.recordSale(enforceStock = true)` |
| 2 | A SKU with no stock movement today is `null` = no check | **Closed.** `SaleSku.stockBase` is a non-null `Long` (default 0); both `RoomSaleReads` read `current` for every SKU | `SaleModels.kt`, `app-sr`/`app-amo` `RoomSaleReads.kt` |
| 3 | Stock is self-declared, so the block can be bypassed by typing a bigger issue | **Kept, stated.** The gate proves the rep DECLARED the stock, not that he holds it. Confirmation by someone else is an open owner question and out of scope for this slice | — |
| 4 | No carry-over from yesterday | **Kept.** Explicitly out of scope ("do not build"); the rep re-enters leftovers as today's issue, as today | — |
| 5 | An edit is compared against stock its own memo already reduced | **Closed.** `SaleFlow.start(editing = …)` gives the superseded memo's lines and reward packs back into the catalog figure; the transaction passes `ignoring = supersedes_client_uuid` | `SaleFlow.kt`, `SrSaleKit`/`AmoSaleKit` `beginEdit`, `StockHeld.read` |
| 6 | Stock is read once at `flow.start` and that snapshot is used at commit | **Closed.** The commit re-reads stock INSIDE the Room transaction; on refusal the flow re-reads the catalog, republishes the screen and reports the transaction's own figures | `CaptureRepository.requireStockHeld`, `SaleFlow.commit` |
| 7 | DRP reward packs and QC sticks are not counted against stock | **Reward packs closed** (they count with the line, in review and in the transaction). **QC returns: kept** — `qcReturn()` is still never called from main code, so QC does not reduce stock; not in this slice's scope | `SaleReview.rewardBaseBySku`, `StockHeld.requested` |
| 8 | `qcReturn()` unused; the SR has no way to LOWER a wrong count | **Kept, stated.** With a hard gate a rep who typed too little can add more, but one who typed too much cannot reduce it (only the AMO has correct-total). This is a real field risk; it is an open owner question in the map and I did not invent an answer | — |
| 9 | Voided and server-refused memos still count as sold | **Kept.** Unchanged behaviour; under the gate it means a rep whose memo the server refused stays short of stock on the phone. Needs the server flag of `docs/requests/android-gates.md` plus a phone-side void path; not this slice | — |
| 10 | Negative stock is permitted | **Half closed.** A SALE can no longer produce a negative figure (the gate floors it at zero). A day that is already negative stays negative and simply blocks further sales of that SKU | — |
| 11 | No server stock check at ingest | **Not mine** (Android-only lane). Written as a request, with the two server aggregate bugs it depends on | `docs/requests/android-gates.md` |
| 12 | Server counts `adjustment` in neither issued nor returned | **Not mine.** Named in the request file as a prerequisite of the server flag | same |
| 13 | Server stock aggregate keyed by `route_id`; AMO movements may carry none | **Not mine.** Not in the request (it does not affect the phone gate); left for the server lane | — |

### The device walk's two other stock items

- **"Home stock 28 packets while the sale list showed 0 or 1."** They were never different reads. Home's card is the SUM
  over a category in that category's entry unit; the sale list is per SKU. Two real differences are gone (D-AGATE-02):
  the sale catalog no longer shows "–" for a SKU with no stock row (it is zero), and Home no longer drops those SKUs from
  its total. Both now read the same `StockBalance.current` per SKU, reward packs subtracted. So the category total is
  exactly the sum of the per-SKU figures shown on the sale list.
- **The 5-minute same-values refusal on stock Save** is removed (D-AGATE-01). With a hard gate it blocked a legitimate
  second issue of the same quantity. A double tap is still refused by the in-flight signature; a retry after a failed
  commit re-posts the **same client UUIDs**, and `recordStock` now stores a UUID once instead of throwing, so the same
  increments cannot post twice. `StockLoad.GuardStore` and both `PrefsGuardStore` classes are deleted.

### Force-sale state (D-AGATE-03)

The walk saw an outlet later say "You are within the outlet's range" with the phone unmoved, and it was correct again
after a process kill. `VisitFlow` was not the cause: every `open()` takes a fresh fix and decides again, and the session
is cleared when a call closes. The wrong words came from the screen: the open-visit card fell back to the in-range string
whenever it had no fix in memory (`geo == null`), which is every call restored after a relaunch — force sale or not. The
card now reads the visit's own stored row (`OpenVisit.cleanGeoSale`, `OpenVisit.verdict`); a force-sale call says so, and
an unconfirmed location says that, in Bangla and English. Proven by `ForceSaleStateTest`; the whole-screen behaviour is
covered by the existing outlet screen tests and NOT by a device walk.

### Tests changed to the new intent (none skipped, disabled, loosened, no timeout widened)

| Test | Pinned (old rule) | Now |
|---|---|---|
| `SaleReviewTest.packBadgeAndStockWarningAreReadOnlyAndNeverBlock` | over-stock warns, `canCommit` stays true | renamed `…AndOverStockBlocksTheSave`: `canCommit` is false and the problem carries the figures. Two new cases beside it: a SKU with no stock entry, and reward packs counting with the line |
| `EntryUnitSaleTest.stockWarningComparesBaseUnits` | the WARNING compares base units | renamed `theStockStopComparesBaseUnits`: the same base-unit maths, now asserting the save is blocked and the entry-unit figures |
| `StockLoadTest.sameValuesResaveWithinGuardIsRefusedThenAllowedAfterIt` | the same quantities within 5 minutes are refused | replaced by `aSecondRealIssueOfTheSameQuantityIsSavedWithNewUuids` and `aRetryOfTheSameSaveAfterAFailedCommitPostsTheSameUuids` |
| `StockLoadTest.guardSurvivesRelaunchThroughTheStore`, `…aKillBetweenTheCommitAndCommittedStillRefuses…`, `…aFailedCommitGivesTheGuardBack` | the persisted guard store | removed with the store; the kill/retry case is now covered by the same-UUID test plus `CaptureRepositoryTest` |
| `StockScreenTest.refusedMessageShowsInBangla` | the Bangla text "these values were just saved, wait a few minutes" | the new Bangla text "this Save is still running" (the only remaining refusal) |
| `CaptureRepositoryTest.theSameClientUuidTwiceIsRejectedInEveryDeviceTable` | `recordStock` of a stored UUID throws | it is stored once and changes nothing; the DAO's unique constraint is still asserted on the same row |
| `SrDayLockSaleTest.aSaleSavedAfterTheLockArrived…` | outbox after the refusal is `{visit:1}` | `{visit:1, stock_movement:1}` (the day's issue the gate now requires) |

New: `feature-sale/.../StockGateTest.kt` (7 cases on the real Room database: a stale catalog refused inside the
transaction, the remainder sellable to the last stick and never below zero, a double tap drawing once, no stock entry,
an edit getting its own quantity back and still capped, reward packs, and the flow reporting the transaction's figures
while keeping the draft) and `feature-outlet/.../ForceSaleStateTest.kt` (4 cases).

Fixtures that had to seed stock (the gate is real, so a test that sells must hold stock): `Fx.seedStock` in
`feature-sale` test fixtures, `UnitsAcceptanceTest`, `SrDayLockSaleTest`, `AmoDayLockGateTest`.

### Screenshot goldens re-recorded (another lane's files — P-SR1 / P-SR2 please note)

Seven goldens changed and were re-recorded with `-Proborazzi.record=true`: `sale_en_light`, `sale_bn_light`,
`sale_bn_light_font13`, `sale_bn_sunlight`, `sale_bn_long_name`, `sale_bn_font20`, `amo_sale_entry_bn`. The only pixel
difference is the `+` button of a SKU whose quantity has reached the stock held, which is now correctly disabled (grey).
No layout, colour or typography was touched.

## Slice 4, defect D1 (Sales Submit "6 records were rejected by the server.")

All causes of the ruling were re-read against the code first; every one held. Decision D-AGATE-04.

| Ruling | Result | Where |
|---|---|---|
| Count and list from the SAME query, every counted record listed, older ones under "earlier" | **Closed.** `OutboxDao.refusedRows()` feeds both `RoomDaySource.outboxStates` (counts) and `rejectedRows` (list), over every date; the 14-day lookback is gone. Proven by `RefusedRecordsTest.theCountAndTheListAreTheSameQueryOverEveryDate` (a 6-week-old row is counted AND listed) | `OutboxDao`, `RoomDayAdapters.kt` |
| What it was, the reason in Bangla and English, what to do | **Closed.** Type, memo number or outlet, date, the reason, and one "what to do" line per outcome (flagged / not stored / will be sent again / waiting on another phone / the phone stopped sending) | `SalesSubmitScreen.kt` |
| A rejected record can be marked "seen": leaves the count, NEVER deletes row or payload | **Closed.** `markRefusedSeen` sets `seen_at` only on a rejected or quarantined row; the test compares the whole row before and after (only `seen_at` differs) and shows an acked or pending row is untouched. Listed under "records you marked as seen" | `OutboxDao`, Room v10 |
| A failed read shows an error with retry and logs one line, no payload or personal data | **Closed.** `rejectedReadFailed` + "Try again"; the gate keeps the true count. Log: `AronSubmit rejected_read failed type=<class>` | `SalesSubmitViewModel.kt`; `…aFailedReadIsFlaggedNotSwallowedAndARetryReadsAgain` |
| `RecordAck.message_key` dropped | **Closed.** Stored in `outbox.last_message_key`, carried on `RejectedRow`, kept for support; never shown raw | `SyncEngine`, `OutboxDao.applyAck` |
| Every local and unknown code has proper Bangla and English text | **Closed.** 49 codes (the whole `RecordOutcomeCode` catalogue + the phone's own), asserted one by one in both languages; unknown reads as a sentence | `strings.xml` (en, bn), `RefusedRecordsTest.everyOutcomeCodeHasBanglaAndEnglishText` |
| `last_code` in the support file | **Closed.** `outbox_codes` in all three apps (SR, AMO, TSO) | `SupportBundle.kt`, the three `SystemShell.kt` |
| True send state per row; hide the server-comparison column until it exists | **Closed for the business table.** No "Waiting" / "-", no server column until the server has figures; one status line states sent / waiting / not accepted. **Kept:** the record-count table's server column (the submit gate reads it) | `SalesSubmitScreen.kt` |
| `retry_exhausted` gets "send again", same client UUID, proven no second record | **Closed.** `PHONE_GAVE_UP` outcome with a "Send again" button, `requeueRetryExhausted` (only that code, same uuid/payload/sig, attempts reset), then a manual sync; `…sendAgainRequeuesTheSameUuidAndCreatesNoSecondRecord` | `OutboxDao`, `RoomDaySource.sendAgain` |

Schema: Room 9 to 10 is an auto-migration (two nullable columns); `schemas/…/10.json` is committed and the migration test runs.
Not done on purpose: no server change (the ack already carries `message_key`); no "seen" for a record another phone sent (this phone cannot act on it).

## Slice 2, the check-in gate (owner rule 1)

Decision D-AGATE-05. The numbers are the claimed gaps of the check-in section of the map; each was re-read first.

| # | Gap | Result |
|---|---|---|
| 1 | No gate in the SR app (tiles open, writes commit) | **Closed.** `CaptureRepository(requireCheckIn = true)` in `SrDay`; locked tiles; `CheckInFirst` on the operational screens |
| 2 | No gate in the AMO app | **Closed.** Same repository flag in `AmoDay`; gated screens in the shell; tile leads to Attendance |
| 3 | TSO has no attendance | **Kept (the ruling).** Not gated, nothing built |
| 4 | The write layer has no check-in refusal | **Closed.** `AttendanceGate` inside the transaction of every operational write (list in D-AGATE-05); proven write by write in `AttendanceGateTest`, with "nothing left behind" asserted. A separate `AttendanceRefusedException`, not a third `DayRefusal` value (that would have touched every exhaustive `when` of the lock dialogs) |
| 5 | `cfg.day.checkin_gate` unread, D-329 | **Closed by decision.** Hard, not config-driven; D-329 superseded in D-AGATE-05; the key stays unread on purpose |
| 6 | In-memory attendance stale across Dhaka midnight (SR tick, AMO none) | **Closed.** SR boundary and resume, and AMO resume, call `restore()` from Room; `AttendanceFlowTest.restoringWithTheNewDatesEmptyRows…`. The gate itself never used the memory copy |
| 7 | Nothing closes after check-out | **Closed.** `CHECKED_OUT` refuses the operational writes; the screens say "you have checked out for today". The close of a call already open stays allowed |
| 8 | A second phone cannot know the user checked in | **Kept.** The local check-in is what counts; the server's `attendance_duplicate` for a second check-in has no Android handler and this lane did not add one (named in the request file) |
| 9 | The server neither refuses nor flags a no-check-in day | **Not mine.** Request in `docs/requests/android-gates.md` (a risk signal; never a refusal, an offline sale may upload before its check-in) |
| 10 | The SR boundary timer is hard-coded 17:00 | **Slice 3** (it reads `cfg.day.checkout_earliest_time` there) |

Exemptions, each with a test: check-in and check-out themselves; the close of an already open call (after the check-out too); Sales Submit; breadcrumbs and device status; media meta; print events (own ledger); uploads (the engine never uses the repository).

Honest limits: (a) the AMO Home tile is not drawn with a lock icon (the shell's role tiles are another lane's model); it leads straight to Attendance and the gated screen says why, so there is no dead end. (b) A write that loses a midnight race inside a generic handler shows that handler's own "failed" text, not the check-in text; nothing is stored and nothing crashes (skip and task resolve are handled explicitly). (c) Proven by unit tests and Robolectric only; nothing on a device.

## Slice 3, check-out submits the sales (owner rule 3)

Decision D-AGATE-06. The numbers are the claimed gaps of the check-out section of the map; each was re-read first.

| # | Gap | Result |
|---|---|---|
| 1 | Check-out is hard-blocked until a Sales Submit exists | **Closed.** The SR and AMO no longer pass `salesSubmitted`; the check-out writes the submit(s) itself (`recordCheckOut`, `AutoDaySubmits`) |
| 2 | Sales Submit cannot be made offline (needs a server answer) | **Closed for the check-out.** The automatic submit has its own rule: no "everything acked" gate; the outbox holds it until the date's earlier records are final. The MANUAL Sales Submit keeps its gate (unchanged on purpose) |
| 3 | Submit and check-out are two transactions | **Closed.** One transaction, step-by-step failure test shows all or nothing |
| 4 | `pending_count` hard-coded 0 | **Closed.** The real number, set inside the transaction; the manual writer reads it too |
| 5 | No check for an open call or memo edit | **Closed.** Check-out refused with a message; closing it opens the way |
| 6 | Nothing closes a rep who never checks out | **Closed on the phone** (forgotten day closed at next start, flagged). **Not built:** a per-rep server-side midnight close (stated to the owner) |
| 7 | The phone only looks at today's business date | **Closed.** The check-out carries the open check-in's date; forgotten days are found by `openAttendanceDates()` |
| 8 | Submit void / reopen not built | **Partly closed.** A submit the server refuses or holds stops locking the phone. There is still no void for an ACCEPTED submit made by mistake (server work, not mine) |
| 9 | The server does not enforce "day_submit is last" | **Not mine.** Unchanged; the outbox order now at least never sends the submit early |
| 10 | No server uniqueness on (route, date, scope, cycle) | **Not mine.** Cycles are still per phone |
| 11 | A rep on two routes has one check-out, two route-days | **Closed.** One submit per route-day with captures (`captureRoutesOn`) |
| 12 | The manual Sales Submit tile has no time gate | **Kept.** The tile stays and works (owner/lead ruling); it can still lock the day early by hand, as before. The 17:00 time of the CHECK-OUT is unchanged |
| 13 | TSO has no check-out | **Kept (the ruling).** Nothing built |
| — | The 17:00 boundary timer is a constant | **Closed.** Reads `cfg.day.checkout_earliest_time`; `SrCheckOutSubmitTest.theBoundaryTimerFollowsTheConfiguredCheckOutTime` |

Order, proved: `CheckOutSubmitTest` (the DAO, on Room) and `CheckOutOrderSyncTest` (the real engine and a fake ingest server). With the hold removed, five of those tests fail (checked by mutation, then restored).

Tests that changed intent: none of the SR/AMO wiring tests pinned "Sales Submit first". `AttendanceFlow` keeps the optional `salesSubmitted` gate and `CheckoutNeedsSubmitTest` / `AmoCheckoutNeedsSubmitTest` still prove that capability; production no longer passes it (a later clean-up can delete both).

Honest limits: (a) the forgotten-day check-out carries default device flags (`device_owner` false in its fix, no location read), because no fix is taken; the server flag request names it. (b) An automatic submit is irreversible once the server ACCEPTS it. (c) A forgotten day with an open call stored on that old date is submitted as is (the call is not closed for the rep). (d) Proven by unit tests, Robolectric and the fake ingest server; nothing on a device.

## What ran, and what did not

Local, foreground Gradle, Android SDK at `/opt/android-sdk`:

- (Slice 4, local, foreground) `testDebugUnitTest` over the whole build, `assembleDebug` for SR/AMO/TSO and `lintDebug`: **BUILD SUCCESSFUL**; the Sales Submit golden `submit_owner_case_bn_light` is unchanged.
- (Slice 1) `testDebugUnitTest` over the whole build (every Android module, `shared`, `backend`): **BUILD SUCCESSFUL**.
- `:android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug`:
  **BUILD SUCCESSFUL**.
- Known flake, not caused by this lane: `core-map LiteMapScreenTest.offlineShowsTheCachedImageOfThisViewOnly` failed once
  when the whole build ran in parallel (`CalledFromWrongThreadException`) and passes alone and in its own module run,
  with and without this lane's changes (checked on a clean stash). The amo-calls lane recorded the same contention.
- **Not run: anything on a device.** No APK from this branch was installed or walked. The stock gate, the force-sale
  wording and the stock Save change are proven by unit tests and Robolectric only.
