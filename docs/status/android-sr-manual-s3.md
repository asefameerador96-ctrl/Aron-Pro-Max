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
