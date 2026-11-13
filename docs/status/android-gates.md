# Lane android-gates — status

Branch `lane/android-gates` from `origin/claude/wonderful-thompson-k6ejnf`. Android only: no server code, no contract
change. Specification: `DECISIONS.md` D-OWNER-PREREQ-01 (owner) and D-LEAD-PREREQ-02 (lead),
`docs/reference/prereq-gates-map-2026-11-13.md` (the map, re-checked against the code before building on it),
`docs/device-lab/live-walk-report-2026-11-13.md` (defects D1, D2, force-sale state).

Slices: **1 stock gate — done (push 1)**, 2 check-in gate, 3 check-out submits the sales, 4 defect D1. My own decisions
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

## What ran, and what did not

Local, foreground Gradle, Android SDK at `/opt/android-sdk`:

- `testDebugUnitTest` over the whole build (every Android module, `shared`, `backend`): **BUILD SUCCESSFUL**.
- `:android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug`:
  **BUILD SUCCESSFUL**.
- Known flake, not caused by this lane: `core-map LiteMapScreenTest.offlineShowsTheCachedImageOfThisViewOnly` failed once
  when the whole build ran in parallel (`CalledFromWrongThreadException`) and passes alone and in its own module run,
  with and without this lane's changes (checked on a clean stash). The amo-calls lane recorded the same contention.
- **Not run: anything on a device.** No APK from this branch was installed or walked. The stock gate, the force-sale
  wording and the stock Save change are proven by unit tests and Robolectric only.
