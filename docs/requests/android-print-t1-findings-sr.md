# Request: T1 check of the printer wiring on INT (android-print to android-sr-a, android-sr-b; 2026-10-07 19:10 UTC)

An independent Opus checker reviewed F-SR-013/015/028/031/066/073 on INT b85d81fd. Host tests: core-printing 90, core-database print ledger 30, feature-memo 39, feature-sale 52, feature-stock 23, app-sr 10; all green.
Host side, F-SR-013/028/073/066 pass. F-SR-015 and F-SR-031 have gaps (below). The MP-58N runs D-P1/D-P2a/D-P2b are separate.

## android-sr-a (app-sr)
1. **BLOCKING (F-SR-015): one slip must cover exactly one Save.** `SrDay.printUnprintedStock` prints every unprinted stock row of the day as one slip, with the lowest-sku_id row of all of them as its uuid. `RoomPrintLedger` flips only the rows of that row's Save (same `captured_at` and kind).
   Repro: Save A (sku 103, 105) while the printer is off, then Save B (sku 100), then print. The slip is recorded against B's row, A's two rows stay `slip_printed = false`, Sales Submit keeps warning, and the next Print reprints A's rows as an **unmarked original** under a new uuid (against lead ruling 2).
   Fix: group the unprinted rows by Save (`captured_at` + kind) and print one slip per Save, with uuid = the lowest sku_id row of that Save. Several unprinted Saves give several slips, oldest first.
2. **F-SR-031/073 config:** `SrDay.printing` uses the defaults. Pass `reprintMax = { cfg.memo.reprint_max }` and `confirmAfterPrint = { cfg.print.confirm_after_print }` (keys in db V0024). `SrModule` hardcodes `idleDisconnectMs = 120_000` instead of `cfg.print.disconnect_idle_s`. `cfg.sale.require_printer_before_sale` (default false) is not read; the behaviour is correct only while it stays false.
3. Minor: print and confirm launched in `SrDay.printScope` and `PrintRunner` have no exception handler, so a ledger write error crashes the app (the sale is safe). Wrap them in `runCatching` and keep the attempt so the answer can be given again.
4. Minor: `recoverPrinting()` runs in `SrApp`'s `LaunchedEffect(Unit)`, so it runs again on every recreate (rotation, language switch). Today that is harmless, because `MemoPrinting` skips jobs it has in flight. Make it once per login.
5. Minor: stock-slip lines print `categoryCode`; print the category's Bangla label from the bundle if the code is not what the paper shows.

## android-sr-b (feature-memo, SrSaleHosts)
6. Minor: `MemoViewModel.confirmPrint` has no exception handler (same as 3).
7. Minor: `SummaryHost` keeps the print attempt in `remember`. Leaving Summary while "ছাপা ঠিক আছে?" is open loses the dialog, and the job is only finished at the next app start. Keep the attempt in the ViewModel or SrDay, as the memo reprint does.
8. Minor (test): no test covers `RoomMemoStore.toStored` filling `supersedesMemoNo` from the original memo (F-SR-066 Room path).

android-print added `feature-memo/src/test/.../StoredMemoPrintTotalsTest.kt`: a memo stored in Room, read by `RoomMemoStore`, mapped by `PrintMapping` and laid out by core-printing prints exactly the stored net, paid, due, discount, QC, rounding and line amounts in both digit styles. It fails if the mapper recomputes the net.
