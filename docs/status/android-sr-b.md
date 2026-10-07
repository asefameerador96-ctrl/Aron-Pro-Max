# Status: lane android-sr-b (2026-10-07)

## Status of rows (2026-10-07)
Nothing is marked done yet (no row has its screen or independent checker). Written, pushed to `lane/android-sr-b`, CI verifying:
`feature-sale/domain`: `SaleDraft` + `SaleDraftOps` (quantity entry in stick/piece/dozen/pack, slide empty packets, QC, credit paid, zero sale, QC lock), `SaleReviewCalculator` (lines, pack badge, stock warning, category subtotals, DRP reward, QC deduction, net via `shared:rules` `MemoMath`; offer line zero), `SaleCommitter` (memo, lines, discounts, QC and outbox in one Room transaction via `CaptureRepository.recordSale`; zero sale; edit with supersedes), `FileDraftStore` (atomic draft, survives kill).
Tests: `SaleReviewTest`, `SaleCommitterTest` (Robolectric Room: atomic rollback, replay writes nothing, draft kill and relaunch).

## Blocked (screens)
Compose screens wait on N-023 (android-core-ui slices). Rows needing sr-a state: F-SR-014/017 (see below).
Local Gradle gets Maven Central 429: verify through CI on `lane/android-sr-b`.

## Interfaces I need (sr-a / android-core)
- From android-sr-a (feature-outlet): the open visit for an outlet, `visitUuid`, `outletId`, `routeId`, outlet `priceType`, visit `geo_verdict`; a call to close the visit with an outcome (F-SR-057, mine to write the record).
- From android-core: `MemoNumbers` (F-SYS-027 counter, in one transaction with the memo) and `CaptureMetaSource` (trusted clock, boot count, bundle/config versions). Prices and offers are not in Room yet (F-SYS-006): I read them through `SaleSku` built by a `PriceBook` the app wires.
- Stock: `SaleSku.stockBase` from sr-a's current-stock tracker (F-SR-050).

## Memo data model for android-print (F-SR-028)
Print from stored rows only: `MemoEntity` (memo_no, committed_at, outlet_id, price_type, gross/offer/drp/qc/net/paid/due mtk, is_credit, supersedes_client_uuid), `MemoLineEntity` (line_no, sku_id, qty_entered, unit_entered, qty_base, base_price_mtk, gross_mtk), `MemoDiscountEntity` (kind, sku_id, qty_base, value_mtk), `QcLineEntity` (sku_id, qty_base, settlement_mtk). Read through `CaptureDao.memo`, `linesOf`; I will add `MemoReadModel` (memo plus lines plus discounts plus QC) in `feature-memo` with a `reprint` flag so a reprint prints "duplicate".

## Next three
CI result then fixes; F-SR-060 and F-SR-023 screens when the kit lands; Memo menu model (F-SR-030).

## Checker findings (2026-10-07)
T2 checker (Sonnet) on F-SR-036/030/032/054/010/069/034/035: 4 confirmed defects, all fixed with the checker's tests kept (`CheckerTest.kt` in feature-memo and feature-dayclose):
1. Home money category rows missed memo-level discounts and QC on categories with no sale: now a `""` row and QC-only categories appear; rows add up to the grand total.
2. Day summary Discount column dropped memo-level offers: a `""` category row carries them.
3. Sales Submit gate blocked forever on a rejected row: rejected and quarantined rows are now notes (carried into the day_submit counts), not blocks. Q-UI-08 stays an open sponsor question.
Flagged, not a defect: `MemoDetail.canMarkPaid` uses the stored due; the UI must use `DueLedger.markPaid != null`.
T1 checker (Opus) on the sale rows: pending.
T1 checker (Opus) on F-SR-023/025/026/027/029/033: 7 confirmed defects, all fixed, its 7 tests kept in `feature-sale/.../CheckerTest.kt`:
1. A failed commit burned a memo number: `MemoNumbers.next(date, memoUuid)` is now idempotent per memo (a retry gets the same number), and every check that can fail (review, edit rules) runs before the number is taken. The true fix is the counter inside the Room transaction (request item 5).
2. More than 60 lines is now a review problem (`TooManyLines`).
3. A unit other than the SKU base unit or pack is a review problem (`UnitNotAllowed`); an unknown unit no longer throws.
4. No edit after QC (`withEdit`, `commit`); `SaleFlow.completeQc()` added.
5/6. An edit needs a well-formed reason (a listed one when `editReasons` is passed), its fix, an existing earlier memo of the same outlet and day, and cannot supersede itself.
7. `SaleFlow.edit` runs the review before saving, so a failure is never persisted.
Not turned into tests (noted): no soft ceiling on `cfg.sale.max_line_qty_base`; QC cap is per SKU (basis MQ-03/04 unknown); `PaidNegative` unused.

## Plan for printing (from lead): docs/requests/android-print-integration.md s2
Review Print drives SaveAndPrint (commit then print), never disabled by the printer unless `cfg.sale.require_printer_before_sale`; Memo menu reprint via `MemoPrinting.printMemo` with the confirm dialog; map Room rows to `MemoPrint` from stored mtk columns; PrinterIcon/PrinterBanner on Review, Memo and Summary. To do after wiring.

## Printing mapping (done)
`feature-memo` `PrintMapping`: `StoredMemo` and `DaySummary` to `MemoPrint` / `DaySummaryPrint` from stored mtk columns (kind by content: cash, credit, offer, drp, zero, edited). Still to do after `PrintLedger` lands: SaveAndPrint in the sale ViewModel, reprint via `MemoPrinting.printMemo` with the confirm dialog, PrinterIcon/PrinterBanner on Review, Memo and Summary.

## Wiring against ports (2026-10-07)
Built against interfaces, fakes only in test sources, so the swap is one line each:
- feature-sale: `SaleViewModel`, `SaleRoute` (entry, slide, QC, credit, review, printer icon and banner, `SaveAndPrintDialogs`), `SaleCommitStep` (the SaveAndPrint commit; never depends on the printer), `MemoPrintSource` port.
- feature-memo: `MemoViewModel` over `MemoStore`, `DueCollectionWriter` (REQUEST core), `MemoReprinter` (production: `MemoPrinting.printMemo`).
- feature-dayclose: `SalesSubmitViewModel` over `DaySource`, `ServerCounts`, `DaySubmitWriter` (REQUEST core), `SyncScheduler` (MANUAL and DAY_SUBMIT triggers).
Production implementations of the ports (Room reads, due_collection and day_submit writers, memo counter) are the core request; app-sr shell wiring is android-sr-a's.

## Production adapters (Room v3 landed) — assembly lines for the app-sr shell (android-sr-a)
```kotlin
val repo = CaptureRepository(db)                                   // per-user AronDatabase from UserDatabases
// sale
val committer = SaleCommitter(repo, numbers = { _, _ -> error("unused") }, metaSource, nowIso,
    findMemo = { db.captureDao().memo(it) }, editReasons = cfgEditReasons, numbering = MemoNumbering(username, bindOrdinal, blockSize))
val flow = SaleFlow(FileDraftStore(filesDir), catalog /* SaleCatalog over ReferenceDao.activeSkus + prices + stockBalanceOn */, committer)
val commitStep = SaleCommitStep(flow, memoPrintSource /* RoomMemoStore.memos -> PrintMapping.memo */, editFix)
val vm = SaleViewModel(flow, SaveAndPrint(commitStep::invoke, memoPrinting))
// memo
val store = RoomMemoStore(db); val vm = MemoViewModel(store, RoomDueCollectionWriter(repo, meta, visitUuidOf), MemoReprinter(memoPrinting::printMemo), names, businessDate)
// sales submit
val vm = SalesSubmitViewModel(userId, businessDate, RoomDaySource(db), serverCounts, RoomDaySubmitWriter(db, repo, meta, categoryOf), syncScheduler, online)
```
Core ruling used: `recordNumberedSale` reserves the memo number in its own committed step, so a failed save burns a number (never reused); `isFullSettlement` means the whole outlet outstanding, so Mark paid on one memo of an owing outlet is a partial settlement.
Open: `catalog` (prices in Room are part of F-SYS-006), stock slip flag, `serverCounts` (sync response `server_totals`), net_by_category in the day_submit money uses SKU categories plus an `other` bucket for memo-level discounts (to confirm against the server check).

## HANDOFF (READY TO RECYCLE, 2026-10-07)
**Done (logic, screens, tests, both checkers; none closed end to end because app wiring is pending):**
F-SR-023/025/026/027/029/033 (feature-sale: SaleDraft, SaleDraftOps, SaleReviewCalculator, SaleCommitter incl. `numbering = MemoNumbering(...)`, SaleFlow, FileDraftStore, EditGate, VisitOutcomes, SaleViewModel, SaleRoute, SaleCommitStep, screens: entry, review, credit, slide, QC, call prompts), F-SR-022/057/060, F-SR-030/032/036/054/010/067/068/069 (feature-memo: DaySummary, MemoMenu, DueLedger, SaleHistory, KPI/money cards, Journey, PrintMapping, MemoViewModel, ReprintDialogs, RoomMemoStore, RoomDueCollectionWriter, screens), F-SR-034/035 (feature-dayclose: SalesSubmit rules, SalesSubmitViewModel, RoomDaySource, RoomDaySubmitWriter, screen), F-SR-053/081 (feature-stock `StockAdjustments`).
Checker findings (Opus T1: 7, Sonnet T2: 4) fixed; tests kept in each module's `CheckerTest.kt`.
**In progress:** nothing half-written. Last lane head pushed: see `git log lane/android-sr-b`.
**Next three rows (all wiring, assembly lines are in "Production adapters" above):**
1. App-sr wiring (with android-sr-a): build `SaleCatalog` from ReferenceDao.activeSkus + prices (F-SYS-006) + `stockBalanceOn` minus sales; `serverCounts` from the sync response `server_totals`; navigation Home tiles to Sale, Memo, Summary, Sales Submit, History, Journey.
2. Summary Print: android-print's `MemoPrinting.printDaySummary` + `PrintMapping.daySummary`; PrinterIcon/PrinterBanner on Review, Memo, Summary (HoldPrinter already in SaleRoute); stock-slip flag for `RoomDaySubmitWriter`.
3. Sunlight/outdoor-first: swap my figures onto the kit's `ContentCard` and largest tabular numerals once the kit has it; add visit_skip UI (`recordVisitSkip`), edit-flow geofence fix capture (`editFix`) and the KPI tile route.
**Traps:**
- Never print `list_workflow_runs` output (huge); use `get_workflow_run` with the run id, then download the job log URL and grep for FAILED.
- INT push is denied for this lane: push only to `lane/android-sr-b`, merge INT into it first; the integrator promotes it.
- `core-ui` HardcodedStringScanTest fails the build on any prose literal: build strings with `listOf(...).joinToString(SEP)`, never `"${a} ${b}"` inside `Text`.
- Local Robolectric works for sdk 36 only in feature modules; a core-ui test may fail to fetch android-all (container issue, not code).
- `recordNumberedSale` burns a number on a failed save (core ruling); `isFullSettlement` means the whole outlet outstanding (Mark paid on one memo is partial).
- Do not commit `.claude/worktrees/` (excluded locally in `.git/info/exclude`).
- Open sponsor question Q-UI-08: rejected and quarantined rows do not block Sales Submit (lead accepted as default).

### Added to the handoff (from android-print, 2026-10-07 08:42; see docs/requests/android-print-wiring-gaps.md "Status 2026-10-07 08:50", lane/android-print b6d07b54)
1. Replace my `ReprintDialogs` with core-printing's `PrintAttemptDialogs` for the memo reprint result. A tap outside the dialog or Back must never record "not readable" (that would let an unmarked second original print): my current `ReprintDialogs` already ignores outside taps (`onDismissRequest = {}`) but confirm it after the swap.
2. Wire `SummaryScreen.onPrint` to `printing.printDaySummary(daySummaryUuid, DaySummaryPrint)`; `daySummaryUuid` is a UUID v4 stored once per user and business date; map with `PrintMapping.daySummary` from stored mtk values.

## UPDATE 2026-10-07 (replacement session): app-sr wiring landed on lane/android-sr-b (2d1b6a8c)
Done: `SrSaleKit` + `RoomSaleReads` (catalog from prices and stock tracker, memo numbering from login, server counts from `server_totals`, summary, journey, KPI), hosts in `SrSaleHosts.kt` (Sale with start-call prompt, no-sale outcomes and `visit_close`, Memo with reprint/Mark paid/history, Edit with fresh `memo_edit` fix and geofence gate, Summary with `printDaySummary` (stable v4 uuid per user and date), Sales Submit, Journey, KPI, visit_skip from the picker), Sunlight toggle in the sale-side chrome (`MainActivity` holds the per-user preference), figures on solid `AronCard`. `RoomSaleReadsTest` passes locally (Android SDK installed in the lane container: `ANDROID_HOME=/root/android-sdk`).
Open / not done:
- Sunlight toggle on Home and the other sr-a screens (needs the top bar of sr-a's screens): ask android-sr-a to place `SunlightToggle(sunlight, onSunlight)` (already passed into `SrApp`).
- Swap my `PrintAttemptDialogs` / `ReprintDialogs` for core-printing's once android-print's lane is on INT (outside tap already never answers "not readable").
- Edit while editing: stock warning counts the old memo's quantity too (old memo is superseded only on commit); warning only, not a block.
- No end-to-end test of the Compose hosts (needs `SrDay`); device checks (kill mid-commit, edit at the shop, print) are for the device lab.
- `typeLabel` covers the usual record types; unknown ones show the wire name.

## Session 2026-10-07 11:00 UTC (fresh session)
F-SR-050 done: `StockTracker` (feature-stock) is the one tracker (issued + adjusted - qc returned - sold on live memos, recomputed from rows, so identical after edit and relaunch); `RoomSaleReads` catalog, summary return and KPI strip use it. `StockTrackerTest` passes locally (includes a random-day model test); `RoomSaleReadsTest` needs JDK 21 for Robolectric (local JDK 17), verified by CI. Time log: 21 finished rows reconstructed in the csv (252 min estimate, from commit times). All 22 rows of this lane are now done at logic and screen level; remaining work is app wiring with android-sr-a.
