# Lane brief: android-print

Session model: **Opus** (docs/29 s3). Owns: `android/core-printing`.

Read `docs/lanes/README.md` first.

- Scope: the Bangla memo renderer (`N-018`): Bengali text shaped with the bundled font and rasterised to a 1-bit bitmap, sent as graphics to the 58 mm printer (MP-58N, ESC/POS) over Bluetooth Classic; stock slip and summary print; reprint; printing from each app with the same paired printer (F-SYS-044 device part); memo format as today (`docs/ui-reference/sr/memo.md`; the sponsor will supply photos of a real printout, track in `docs/status/device-checks.md`).
- The rendering engine must be testable without a printer: render to a bitmap, golden-image tests for Bangla conjuncts, digits and the memo layout; the device check is the owner's printer.
- Printed total equals the stored total (checker proves with the shared memo oracle). Never block a sale on a printer error: queue and retry.
- Every row is T1: Opus checker.

## Wave 4: Thermal Crest MP-58N (owner directive 2026-10-22, docs/34 s4.1, D-PRINT-02)

The owner's printer is the Thermal Crest 58 mm MP-58N and "does not work" on build 1258. Build the Printer
diagnostics screen (Settings → Printer): Bluetooth and permission state, bonded devices with names and
addresses (no name filter), connect secure then insecure with the error text, a test page in three modes
(`GS v 0` raster, `ESC *` 24-dot bit image, ASCII self-test), a text diagnostics export without secrets; then
fix what the export shows (candidates: the Android 12+ runtime permission flow before listing bonded
devices, the SPP connect path, the raster command or band size, the init sequence) keeping the golden tests
green. T1, Opus checker. The lead relays the owner's export.
