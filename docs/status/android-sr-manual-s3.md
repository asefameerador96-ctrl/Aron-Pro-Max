# Status: lane android-sr-manual S3 (A3 attendance-stock-printer), 2026-11-12

Branch `lane/android-sr-manual-s3` from INT. Decisions D-SR-MAN-S3-01..04 (end of DECISIONS.md; S3-04 amends S3-02(a) and (c), which are shipped on INT and stay as written).

| row | verdict | state |
|---|---|---|
| SRM-A-16 day cards | MATCH | unchanged (units: rule 5); strike-rate formula check left to the device lab |
| SRM-A-17 sales card | MATCH | unchanged |
| SRM-A-18 check-in | MATCH | the screen content is as before, but the check-in state now renders through the `statusBoxes` path of SRM-A-19 (green check-in and grey check-out boxes/buttons from the same row, dome hold sheet, Latin-digit chip, no day lines); covered by `AttendanceScreenTest` and `SrStatusBoxesTest` |
| SRM-A-19 after check-in boxes | GAP | **done**: `statusBoxes` for SR (done box, amber box, red button after 17:00 trusted time); `SrStatusBoxesTest`; owner question Q5 (Sales Submit before check-out) still open, behaviour kept |
| SRM-A-20 check-out done | MATCH+box | **done**: "চেক আউট সম্পন্ন হয়েছে" box beside the check-in box and the thank-you message |
| SRM-A-21 stock issue | MATCH | unchanged (`StockScreenTest`); badge meaning is owner question Q6 |
| SRM-A-22 Bluetooth | MATCH | unchanged; locked-down phone check is device-pending |
| SRM-A-23 pair with PIN | MATCH | unchanged |
| SRM-A-24 connect in the app | GLITCH | **done**: the nearby-devices permission is asked once per install and the shared visible toast shows, both from ONE holder per screen: the Stock frame and the sale's VISIT frame (D-SR-MAN-S3-04), green connected badge (Default 0x17692F, Sunlight 0x0F5A24). Not done: the not-connected badge stays red crossed (matches screenshot 17), not grey; the badge circle is not grey |

Needs contract: none. Owner questions: Q5, Q6 (as in the gap table). Device-pending: all acceptances (no phone here), including the permission prompt order and the toast.
Automated coverage of the assist: `PrinterToastTest` (transition rule), `SrPrinterAssistTest` (call sites, one toast, prompt launched once across two entries), `SrSaleAssistHostTest` (real SaleHost and SaleRoute, one toast, one more after a mid-sale reconnect). Only the Android system dialog itself is device-pending.

Run statement: every test in this lane is JVM/Robolectric (no emulator, no phone); the Gradle runs were `--no-build-cache` with clean test directories.
CI: lane run 43214864833 (on ec8cf4bdb) failed only in the Web e2e job (`support-menu.spec.ts:24`, `goto` ERR_ABORTED after login); that is not this lane (no web code), the login-helper fix rides train 105. Its Android, release APK and size-gate, repository-gate and backend jobs passed.

## Review fixes (lead review of be15cd1a8 = FAIL; D-SR-MAN-S3-01/02 revised in place)
1. One assist holder per SR screen. First version: Stock frame and `SaleRoute`; superseded in the follow-up round: Stock frame and the VISIT frame hold, `SaleRoute` and the Memo/Summary frames do not (see D-SR-MAN-S3-04). `SrPrinterAssistTest` (one connect, one toast; memo frame none).
2. "Asked once" persisted per install (`PrinterAssistPrompt`), assist limited to Stock and the sale's VISIT frame.
3. Notes 12/13: centred check-out message in the amber state, day lines hidden with the boxes (one STATUS node); asserted in `SrStatusBoxesTest` and `SrAttendanceWiringTest`.
4. Green badge: Default (SR/AMO) and Sunlight set, TSO night restored to 0xB9A6FF; KDoc fixed; `PrinterBadgeContrastTest`; goldens re-recorded in that round: amo_stock_connected_saved_bn, amo_stock_connected_saved_bn_sunlight; the follow-up round re-recorded amo_stock_connected_saved_bn again for the final green 0x17692F (no other golden shows a connected badge).
5. Real SR wiring host test (`SrAttendanceWiringTest` via `SrAttendanceContent`); trusted-time gate cited (`AttendanceFlowTest`); amber label reads the configured minutes (`SrDay.checkoutAtLabel`, tested with 18:30).

## Follow-up round (second review lows; D-SR-MAN-S3-04)
All five lows done; one design change worth the lead's eye: the sale's printer assist moved from SaleRoute to the VISIT frame (SaleRoute appears only after Start-call/AV/KV/POSM, when a granted printer is already connected, so its toast could not show). Golden re-recorded again: amo_stock_connected_saved_bn (new green 0x17692F; the Sunlight twin is unchanged). Remaining: owner questions Q5 and Q6; device-pending acceptances.

## Train 105 round (lead gate on db71bc4f1 = PASS; no behaviour change except the wording below)
Merged train 105 (INT 20fe1d4fb + S2 + Loyalty web). Tests added or tightened: `SrSaleAssistHostTest` asserts SaleRoute really composed after "Yes" (its "Proceed" button) before reading the toast count, and a mid-sale reconnect (the link drops, comes back) gives exactly one more toast; `PrinterBadgeContrastTest` covers the AMO look (`AmoPurple`, `AmoSunlight`) in the chip and in-card forms; the source-pin test strips block, KDoc and inline `//` comments before matching. Wording (for the owner, record only): the amber box's Bangla hour now reads "সন্ধ্যা" for 18:00 to 19:59 and "রাত" from 20:00 to 04:59 (midnight "রাত ১২"), "সকাল" 05:00 to 11:59, "দুপুর ১২" at noon; 13:00 to 17:59 stay bare, so the default 17:00 is "৫টার" exactly as screenshot 12. Reason: a bare "৭টার/৯টার" for 19:00 to 21:59 can be read as morning.
