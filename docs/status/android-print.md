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

## Next (feature rows)
- F-SR-013 printer pairing and state UI, F-SR-028 print memo flow, F-SR-073 print confirmation: building the print flow (dialogs, reprint/confirmation policy, `print_event` payload) in core-printing; persistence (`print_event` table, `memo.printed_at`, `print_count`) and the sale-screen hook need android-core and android-sr, see `docs/requests/android-print-integration.md`.
- F-SR-015 (needs F-SR-014 stock screen), F-SR-031 and F-SR-066 (need F-SR-030 memo menu): waiting for android-sr.

## Traps
- `HardcodedStringScanTest` (core-ui) scans `core-printing/src/main` too: diagnostics are short codes, test samples live under `src/test/` (shared with androidTest via `src/test/shared/kotlin`).
- Regenerate goldens only with `-Paron.updateGoldens=true` and look at every changed file.
- `build/test-results` XML can be stale when two Gradle runs overlap; read failures from the console.
