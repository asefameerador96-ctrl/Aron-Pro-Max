# Status: lane android-print (Day 3, 2026-10-07, session #2)

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

- **PrintLedger contract** (persistence half of F-SR-028/073; session #2): `src/testContract/.../PrintLedgerContract.kt` runs the same scenarios against `MemLedger` (core-printing `MemLedgerContractTest`) and android-core's `RoomPrintLedger` (core-database `RoomPrintLedgerContractTest`, Robolectric file DB with real close/reopen, plus a trigger-forced rollback proving event + outbox + job delete + flags are one transaction). Opus checker: 2 blocking + 6 minor; android-print half fixed with tests (void slip / due receipt are not copies: `ReprintPolicy.isCopy`; `MemoPrinting` keeps an in-flight set so `recover()` never closes a live job, released on cancel/failure; sticky `paperOut`; real v4 uuids in flow tests); re-check clean on the fixes. Room half filed (`docs/requests/android-print-ledger-findings.md`) and fixed by android-core (0b29181, d0a14b9); the contract now also holds both ledgers to: a slip flags every row of its Save and no other Save, void slip / due receipt never a copy, history in recorded order, paper_out never taken back, printed_at with milliseconds (Room 14/14, reference 12/12).
- **`MemoPrinting.printDaySummary`** (F-SR-036 print): kind `day_summary`, no limit, no marker (AP-09).

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

- **AP-09:** the day summary is a report of the moment printed: no reprint limit, no duplicate marker; `ref_client_uuid` = a stable UUID v4 per user and business date.
- **AP-10:** prints that name a memo but are other papers (`void_slip`, `due_receipt`) never count as copies of it (no `printed_at`, no `print_count`, no marker effect).
- **AP-11:** one `MemoPrinting` per process; `recover()` skips the jobs that instance has in flight.

## Feature rows: core-printing part built and checked; BLOCKED on other lanes
- **F-SR-013, F-SR-028, F-SR-073** (Opus checker: 2 blocking + 4 minor findings, all fixed with tests, re-check clean, commit 2cca990/d918c28):
  - `ui/PrinterUi.kt`: `PrinterIcon` (green / red slashed), `PrinterBanner`, `PrinterPickerDialog` (bonded devices, Bluetooth settings, BLUETOOTH_CONNECT), `HoldPrinter`, `SaveAndPrintDialogs`.
  - `flow/SaveAndPrint.kt`: commit before and regardless of printing; Retry / Print later; "ছাপা ঠিক আছে?".
  - `flow/PrintFlow.kt`: `MemoPrinting` (job saved before the printer call, paper-out sets printed_at, `recover()` at start), `ReprintPolicy` (failed and failed_user never count; limit `cfg.memo.reprint_max`), `PrintEvent.payload()` = contract `PrintEventPayload`.
  - **Blocked:** the rows are not done until android-core implements `PrintLedger` on Room (`print_event`, `print_job`, `memo.printed_at`/`print_count`) and android-sr wires the screens. See `docs/requests/android-print-integration.md`. The same code serves F-SR-031/F-SR-066 (reprint, marker "পুনর্মুদ্রণ #n", "supersedes") and F-SR-015 (stock slip), which also wait for F-SR-030 and F-SR-014.
- Ruling asked of the lead (request §3): config keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`, `cfg.sale.require_printer_before_sale`; the marker after a rejected reprint.
- **AP-07:** a rejected print (`failed_user`) does not count; the next print has no marker only while no copy has counted (prevents an unmarked second original).
- **AP-08:** the printer UI lives in core-printing with Compose (no dependency on core-ui; themed by the host app), so every app reuses one picker and icon.

## In progress / waiting
- Requests open: `docs/requests/android-print-wiring-gaps.md` (sr-a items 1-8, sr-b 9-14, android-core 15; lanes messaged once) and `docs/requests/android-print-ledger-findings.md` (android-core: all five fixed and in the contract).
- No SR module calls the printer yet (INT, lane/android-sr-a, lane/android-sr-b checked 2026-10-07).

## Next three rows
1. **F-SR-015** (multi-row flag is in; waits for sr-a's Save hook): Room-level slip test, then D-P2 slip print.
2. **F-SR-031/066 + F-SR-028/073** end to end once sr-b wires Review and Memo menu: verify marker/limit on the Room ledger through the screens, then mark done; D-P2 with the owner (message `@parent` when the SR app installs with a print screen).

## Traps
- The contract is a shared source dir (`core-printing/src/testContract`) compiled into both modules' unit tests; keep it free of core-printing test-only classes.
- `HardcodedStringScanTest` (core-ui) scans `core-printing/src/main` too: diagnostics are short codes, test samples live under `src/test/` (shared with androidTest via `src/test/shared/kotlin`).
- Regenerate goldens only with `-Paron.updateGoldens=true` and look at every changed file.
- `build/test-results` XML can be stale when two Gradle runs overlap; read failures from the console.
