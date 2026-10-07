# Status: lane android-print (Day 3, 2026-10-07)

## Done (builder, then an independent Opus checker, every confirmed defect fixed with a test, re-check clean)
- **N-018** Bangla memo renderer, `android/core-printing`:
  - `text/`: own OpenType reader (cmap, glyf, GDEF, GSUB 1/2/4/5/6, GPOS 1/2/4/6/8) and a HarfBuzz-style Indic (bng2) shaper. `ShaperOracleTest` compares it with the JVM's HarfBuzz on 4,880 strings per weight (every consonant + sign, every two-consonant conjunct, reph, phala forms, nukta, joiners, digits, Latin, mixed). Equal glyph for glyph.
  - `raster/`: integer-only rasteriser (1/64 dot, 4x4 samples, non-zero winding), so the JVM and ART give the same bits.
  - `template/`: templates as validated JSON (schema 1; text, pair, rule, space, table, if); embedded defaults in `src/main/resources/aron/print/*.json` for the six memo kinds, stock slip, day summary, void (cancel) slip and due receipt; a bundle template wins when valid, else the embedded one; the version is reported for `print_event.template_version`.
  - `doc/`: print models filled from stored milli-taka only; Latin or Bengali digits (identifiers never change); 3 decimals when an amount is not a whole paisa; quantities of different units are never added.
  - Labels: `print_*` strings in `values` and `values-bn`; `AndroidPrintLabels` reads them in the print language.
  - Goldens: `src/test/resources/goldens/*.pbm` (seeded sale in both digit styles and English, reprint, edited memo, 40 lines, stock slip, day summary, void slip, due receipt). `MemoTotalsPrintTest` parses printed amounts back to mtk against `MemoMath` over 500 random memos.
- **N-019** Bluetooth printing, `core-printing/bt` and `escpos`:
  - `BluetoothSppTransport` (SPP UUID, secure then insecure RFCOMM), `PrinterManager` (state flow for the icon, hold/release, 120 s idle release, drop detection by the reader with no polling, reconnect backoff 2/5/15 s while held), `GS v 0` in 24-row bands, 512-byte chunks paced against a 3,072-byte / 300-rows-per-second model, DLE EOT 4 paper checks, idempotent job ids.
  - Tested against `SimPrinter` (flags buffer overflow and garbage bytes) and the checker's printer that keeps its parser over a link drop.

## Device-pending
- D-P1 (A06 golden run, `connectedDebugAndroidTest`), D-P2 (MP-58N print, switch-off and paper-out) in `docs/status/device-checks.md`.

## Known gaps (logged, not blocking)
- A vowel sign or virama after a ZWJ inside a broken cluster (invalid text, already shown with a dotted circle) lands about 5 dots left of HarfBuzz's position.
- If a printer's closing status reply comes later than 800 ms after a complete print, the job is reported DISCONNECTED and a retry prints a second copy (errs towards no lost memo).
- The retry after a dropped link starts with up to about 1,160 zero bytes (finishes a stranded band, otherwise NULs that ESC/POS ignores); D-P2 checks there is no gap or garbage on the MP-58N.
- `PaperTooLongException` (more than 16,000 dots) must be caught by the caller and treated as a print failure.

## Decisions taken (DECISIONS.md is read-only for lanes)
- **AP-01:** shaping and rasterising are done in Kotlin, not by Android's text stack, so a JVM golden is the phone's output bit for bit (minSdk 26 has no public shaping API; Canvas text differs by OS version).
- **AP-02:** printed digits default to Latin, as the current on-screen memo shows (`84.00`); a template or app setting switches to Bengali digits.
- **AP-03:** layout follows the on-screen memo until the sponsor's photos arrive (N-020); quantity totals are printed only within one unit (category subtotals on the stock slip), never across sticks, pieces and dozens.
- **AP-04:** an amount that is not a whole paisa prints with 3 decimals instead of being rounded on paper.
- **AP-05:** the paired printer is kept in each app's own SharedPreferences (`aron_printer`); each app pairs once with the same MP-58N (F-SYS-044 device part).
- **AP-06:** Print stays enabled after "paper out" (the link is up) so the seller can retry after changing the roll.

## Feature rows: core-printing part built and checked; BLOCKED on other lanes
- **F-SR-013, F-SR-028, F-SR-073** (Opus checker: 2 blocking + 4 minor findings, all fixed with tests, re-check clean, commit 2cca990/d918c28):
  - `ui/PrinterUi.kt`: `PrinterIcon` (green / red slashed), `PrinterBanner`, `PrinterPickerDialog` (bonded devices, Bluetooth settings, BLUETOOTH_CONNECT), `HoldPrinter`, `SaveAndPrintDialogs`.
  - `flow/SaveAndPrint.kt`: commit before and regardless of printing; Retry / Print later; "ছাপা ঠিক আছে?".
  - `flow/PrintFlow.kt`: `MemoPrinting` (job saved before the printer call, paper-out sets printed_at, `recover()` at start), `ReprintPolicy` (failed and failed_user never count; limit `cfg.memo.reprint_max`), `PrintEvent.payload()` = contract `PrintEventPayload`.
  - **Blocked:** the rows are not done until android-core implements `PrintLedger` on Room (`print_event`, `print_job`, `memo.printed_at`/`print_count`) and android-sr wires the screens. See `docs/requests/android-print-integration.md`. The same code serves F-SR-031/F-SR-066 (reprint, marker "পুনর্মুদ্রণ #n", "supersedes") and F-SR-015 (stock slip), which also wait for F-SR-030 and F-SR-014.
- Ruling asked of the lead (request §3): config keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`, `cfg.sale.require_printer_before_sale`; the marker after a rejected reprint.
- **AP-07:** a rejected print (`failed_user`) does not count; the next print has no marker only while no copy has counted (prevents an unmarked second original).
- **AP-08:** the printer UI lives in core-printing with Compose (no dependency on core-ui; themed by the host app), so every app reuses one picker and icon.

## In progress
- Nothing. Every remaining row waits on another lane (above).

## Next three rows (when unblocked)
1. **F-SR-028 + F-SR-073** end to end: once android-core's Room `PrintLedger` and android-sr's Review screen exist, run `PrintFlowTest`'s scenarios against the Room ledger (instrumented), confirm printed_at/print_count/outbox rows, then mark done.
2. **F-SR-013**: verify the icon/banner/picker inside the SR screens (Stock, Review, Memo, Summary), then D-P2 on the MP-58N.
3. **F-SR-031/F-SR-066** (after F-SR-030 memo menu), then **F-SR-015** (after F-SR-014 stock screen): wire `MemoPrinting.printMemo` / `printStockSlip`; the policy, marker and goldens already exist.

## Traps
- `HardcodedStringScanTest` (core-ui) scans `core-printing/src/main` too: diagnostics are short codes, test samples live under `src/test/` (shared with androidTest via `src/test/shared/kotlin`).
- Regenerate goldens only with `-Paron.updateGoldens=true` and look at every changed file.
- `build/test-results` XML can be stale when two Gradle runs overlap; read failures from the console.
