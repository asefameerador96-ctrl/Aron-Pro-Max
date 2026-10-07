# Lane brief: android-print

Session model: **Opus** (docs/29 s3). Owns: `android/core-printing`.

Read `docs/lanes/README.md` first.

- Scope: the Bangla memo renderer (`N-018`): Bengali text shaped with the bundled font and rasterised to a 1-bit bitmap, sent as graphics to the 58 mm printer (MP-58N, ESC/POS) over Bluetooth Classic; stock slip and summary print; reprint; printing from each app with the same paired printer (F-SYS-044 device part); memo format as today (`docs/ui-reference/sr/memo.md`; the sponsor will supply photos of a real printout, track in `docs/status/device-checks.md`).
- The rendering engine must be testable without a printer: render to a bitmap, golden-image tests for Bangla conjuncts, digits and the memo layout; the device check is the owner's printer.
- Printed total equals the stored total (checker proves with the shared memo oracle). Never block a sale on a printer error: queue and retry.
- Every row is T1: Opus checker.
