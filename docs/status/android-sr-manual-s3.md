# Status: lane android-sr-manual S3 (A3 attendance-stock-printer), 2026-11-12

Branch `lane/android-sr-manual-s3` from INT. Decisions D-SR-MAN-S3-01..03 (end of DECISIONS.md).

| row | verdict | state |
|---|---|---|
| SRM-A-16 day cards | MATCH | unchanged (units: rule 5); strike-rate formula check left to the device lab |
| SRM-A-17 sales card | MATCH | unchanged |
| SRM-A-18 check-in | MATCH | unchanged; existing `AttendanceScreenTest` covers prompt, buttons, hold sheet |
| SRM-A-19 after check-in boxes | GAP | **done**: `statusBoxes` for SR (done box, amber box, red button after 17:00 trusted time); `SrStatusBoxesTest`; owner question Q5 (Sales Submit before check-out) still open, behaviour kept |
| SRM-A-20 check-out done | MATCH+box | **done**: "চেক আউট সম্পন্ন হয়েছে" box beside the check-in box and the thank-you message |
| SRM-A-21 stock issue | MATCH | unchanged (`StockScreenTest`); badge meaning is owner question Q6 |
| SRM-A-22 Bluetooth | MATCH | unchanged; locked-down phone check is device-pending |
| SRM-A-23 pair with PIN | MATCH | unchanged |
| SRM-A-24 connect in the app | GLITCH | **done**: permission on entering Stock/Sale, shared visible toast, green connected badge. Not done: the not-connected badge stays red crossed (matches screenshot 17), not grey; the badge circle is not grey |

Needs contract: none. Owner questions: Q5, Q6 (as in the gap table). Device-pending: all acceptances (no phone here), including the permission prompt order and the toast.
Not covered by an automated test: the `assist` permission prompt itself (system dialog); the transition logic of the toast is covered by `PrinterToastTest`.

## Review fixes (lead review of be15cd1a8 = FAIL; D-SR-MAN-S3-01/02 revised in place)
1. One assist holder per SR screen (Stock frame, SaleRoute); VISIT/MEMO/SUMMARY frames do not assist; `SrPrinterAssistTest` (one connect, one toast; memo frame none).
2. "Asked once" persisted per install (`PrinterAssistPrompt`), assist limited to Stock and Sale.
3. Notes 12/13: centred check-out message in the amber state, day lines hidden with the boxes (one STATUS node); asserted in `SrStatusBoxesTest` and `SrAttendanceWiringTest`.
4. Green badge: Default (SR/AMO) and Sunlight set, TSO night restored to 0xB9A6FF; KDoc fixed; `PrinterBadgeContrastTest`; goldens re-recorded: amo_stock_connected_saved_bn, amo_stock_connected_saved_bn_sunlight (no other golden shows a connected badge).
5. Real SR wiring host test (`SrAttendanceWiringTest` via `SrAttendanceContent`); trusted-time gate cited (`AttendanceFlowTest`); amber label reads the configured minutes (`SrDay.checkoutAtLabel`, tested with 18:30).
