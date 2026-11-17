# Lane android-gates — status

Branch `lane/android-gates` from `origin/claude/wonderful-thompson-k6ejnf`. Android only: no server code, no contract
change. Specification: `DECISIONS.md` D-OWNER-PREREQ-01 (owner) and D-LEAD-PREREQ-02 (lead),
`docs/reference/prereq-gates-map-2026-11-13.md` (the map, re-checked against the code before building on it),
`docs/device-lab/live-walk-report-2026-11-13.md` (defects D1, D2, force-sale state).

Slices: **1 stock gate — done (pushed ec7b807a0, CI green)**, **4 defect D1 — done locally (push 2)**, 2 check-in gate, 3 check-out submits the sales. My own decisions
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
