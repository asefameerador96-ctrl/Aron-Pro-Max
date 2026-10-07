# Request: printer wiring still missing in the SR modules (android-print, 2026-10-07)

From: android-print. To: android-sr-a (app-sr shell, feature-stock), android-sr-b (feature-sale, feature-memo),
android-core (one ledger point). Basis: `docs/requests/android-print-integration.md` s2 and the lead ruling at its end.

Checked: `origin/claude/wonderful-thompson-k6ejnf`, `lane/android-sr-a` and `lane/android-sr-b` at 2026-10-07. No
module outside `core-printing` / `core-database` references `PrinterManager`, `MemoPrinting`, `SaveAndPrint`,
`HoldPrinter`, `PrinterIcon`, `PrinterBanner` or `RoomPrintLedger` yet (a `PrintMapping` was mentioned; it is on no
pushed branch). `RoomPrintLedger` now passes the shared `PrintLedgerContract` (core-database
`RoomPrintLedgerContractTest`), so persistence is ready to wire.

Every item: the printer is never required to save; printed amounts come from the stored mtk columns, never recomputed;
strings from resources.

## android-sr-a: app-sr shell (`app-sr`)
1. **One `PrinterManager` per process** (Hilt `@Singleton` in `SrModule`): `PrinterManager(BluetoothSppTransport.factory(context), PrefsSavedPrinterStore(context), appScope, idleDisconnectMs = cfg.print.disconnect_idle_s * 1000)`.
2. **One `MemoPrinting` per process for the signed-in user** (its `recover()` skips the jobs that instance has in flight, so a second instance would break that guard), built where `SrDay` gets the user database:
   `MemoPrinting(printerManager, { renderer }, RoomPrintLedger(db, envelope), ClientIds::newUuid, clock::nowMs, reprintMax = { cfg.memo.reprint_max }, confirmAfterPrint = { cfg.print.confirm_after_print })`.
   `envelope` = the same `CaptureMeta` source the captures use (trusted clock, boot count, bundle/config versions); it receives the memo's own meta while its route-day is open, else null.
3. **Renderer**: `PaperRenderer(PrintFonts(regular, bold), TemplateSet(bundle.templates, labels), labels)` with the fonts read from core-ui `R.font.noto_sans_bengali_regular` / `_bold` (`resources.openRawResource(...).readBytes()`) and `labels = AndroidPrintLabels(context, AppLanguage.BN)`. Build it lazily once (font parsing is the expensive part).
4. **`printing.recover()` once after login opens the user database, before any screen can print** (finishes jobs a killed process left: paper out becomes `printed`, else `failed`).
5. **Routes**: `SrScreen` has no SALE (review), MEMO (memo menu) or SUMMARY destination yet; sr-b's screens need hosts. On each of Stock, Review, Memo and Summary: `HoldPrinter(pm)`, `PrinterIcon(pm)` in the top bar (tap opens `PrinterPickerDialog(pm)`), `PrinterBanner(pm)` under it.
6. app-amo / app-tso: the same items 1 to 5 when their lanes start (F-SYS-044 device part: each app pairs once with the same printer, AP-05).

## android-sr-a: `feature-stock` (F-SR-015)
7. `SrApp.StockHost` passes `slipNotPrinted = true` always. Read it from Room: the last save's rows with `slip_printed == false`.
8. After a successful Save (never blocked by the printer), offer Print: `printing.printStockSlip(slipUuid, slip)` where `slipUuid` is the **first saved movement's client uuid** (sorted by sku_id) and `slip: StockSlipPrint` is mapped from the stored rows (SKU short name, `qty_entered` + `unit_entered`, category subtotals within one unit only, SR and distributor names). Handle `AwaitingConfirmation` with the "ছাপা ঠিক আছে?" dialog (`ui_print_readable_question`) and `LimitReached` with `ui_print_limit_reached`. The Print button may follow `pm.canPrintFlow` (collected as state).

## android-sr-b: `feature-sale` (F-SR-028, F-SR-073)
9. **Review Print is the commit and is never disabled by the printer** unless `cfg.sale.require_printer_before_sale` is true (lead ruling 1; default false). Then gate on `pm.canPrintFlow`.
10. The ViewModel holds `SaveAndPrint(commit = { SaleCommitter commit; CommittedMemo(memoUuid, memoPrint) }, printing)` and the screen renders `SaveAndPrintDialogs(flow, viewModelScope) { printed -> navigate on }` (the ViewModel scope, so rotation never cancels a save or a print). The commit must not touch the printer.
11. `MemoPrint` mapping from the stored rows only (`MemoEntity` totals in mtk, lines, discounts, QC; outlet `name (code)`, SR, route). `supersedesMemoNo` = the edited memo's original number for an edit (F-SR-066).

## android-sr-b: `feature-memo` (F-SR-030/031/066, day summary)
12. `MemoDetailScreen.onPrint` → `printing.printMemo(memoUuid, memoPrint)`; dialog for `AwaitingConfirmation` → `printing.confirm(attempt, readable)`; `LimitReached` → `ui_print_limit_reached`; `Failed` → Retry / Later. The marker number comes from `MemoPrinting` (do not set `reprintNo` yourself).
13. The on-screen `reprintIsDuplicate = printedAtIso != null` is consistent with the paper (a rejected only copy clears `printed_at`); keep it reading `printed_at`, not `print_count`.
14. `SummaryScreen.onPrint` → `printing.printDaySummary(...)` (android-print adds it, see 16).

## android-core: `RoomPrintLedger`
15. A stock slip is one Save = several `stock_movement` rows (one per SKU), but the ledger flips `slip_printed` only on the row named by `ref_client_uuid`. Sales Submit warns while any row is unprinted, so a printed slip would still warn. Wanted: set and clear `slip_printed` on **every row of the same Save** as the named row. Rows of one Save share one `CaptureMeta`, so `business_date` + `captured_at` + `kind` identify the Save without a schema change; a `save_uuid` column is the cleaner option if you prefer a migration. android-print's contract test will add the multi-row case once you answer.

## android-print (mine, in progress)
16. Done: `MemoPrinting.printDaySummary(daySummaryUuid, DaySummaryPrint)` (kind `day_summary`, no limit, no marker; `daySummaryUuid` = a stable UUID v4 per user and business date). Next: F-SR-015 / F-SR-031 / F-SR-066 cases on the Room ledger.
