# Status: lane P-SR2 (android-parity-sr-2), SR parity, second half of the journey

Branch `lane/android-parity-sr-2` from INT 4e0645556. Lead `session_01MbUQSxrP7AB9tbyANjUTPS`. Directive: D-OWNER-PARITY-02 (no user feels a change), D-OWNER-REA-01 (parity from screenshots and manuals). Spec: audit "SR app" section, manual pages 23-65, `docs/ui-reference/PARITY-SPEC.md`.

## Handover block
- Local build works: SDK at /opt/android-sdk (cmdline-tools zip from dl.google.com), `local.properties` with `sdk.dir` at the REPO ROOT (git-ignored). No `--offline`. Fast loop: `./gradlew :android:feature-memo:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest -q`.
- Goldens: `android/ui-screenshots/src/test/kotlin/.../Sr2Shot.kt` (this lane's), record with `-Paron.screenshots.recordTo=src/test/screenshots --tests '*Sr2Shot*'`; images are `sr2_*.png`.
- The SR host passes `vendorLayout = true` to `MemoDetailView`; the AMO and TSO keep their layout (`VendorMemo.kt` holds the new pieces).
- core-ui is NOT touched (P-SR1 owns it). Nothing here was walked on a device.

## Audit table (finding, decision, commit, image)

| Finding (audit SR app) | Decision | Commit | Image |
|---|---|---|---|
| Collect a due: amount-entry screen | KEEP, already one tap: `MemoDetailView` has the green "পরিশোধিত করুন", the "আপনি কি নিশ্চিত?" confirm and the success dialog; no amount form exists in the SR. Full settlement is ONE `due_collection` record for the whole outstanding (`DueLedger.markPaid`), so the contract needs no change | slice 1 | sr2_memo_credit_bn, sr2_memo_paid_bn |
| Memo list / detail: outlet dropdown, A-Z bar, printer tab, table | FIX: selector reads "name : amount ৳ বাকি", no duplicate header card, dark letter bar outside the table, gradient header with "দাম", grey category block and white "মোট" row, purple "মোট QC" / "সর্বমোট" lines (discount line only when nonzero), due box with card icon, red divider and green button, pencil on "এডিট" | slice 1 | sr2_memo_credit_bn (+ font 2.0) |
| Memo: quantity in the total row (68 in the vendor image) | KEEP-WITH-REASON: units of different kinds are never added (standing rule 5); the "মোট" row carries the money only | slice 1 | sr2_memo_credit_bn |
| Memo: printer circle grey vs red-crossed | KEEP: the kit's printer badge on the bar edge (state by colour AND icon); position is the host's, P-SR1 shell | n/a | n/a |
| Edit a sale: reason dialog with three reasons | FIX (look): lilac dialog, centred dark title, white bordered drop-down; reasons = the manual's three; "কারণ" spelled correctly (vendor typo "কারন", standing rule) | slice 1 | sr2_edit_reason_bn |
| Sale history (সেল ডাটা) | PLANNED slice 2 | | |
| Sales summary (title, printer circle, rows, ম্যাচ row) | PLANNED slice 2 | | |
| Sync and submit rows, no extra warning boxes | PLANNED slice 2 | | |
| Tasks (swipe-to-resolve, card layout, no raw codes) | PLANNED slice 2 | | |
| Product QC (tiles, confirm dialog, table, saved summary, submit bar) | PLANNED slice 3 | | |
| DRP slide, KV and POSM survey | PLANNED slice 3 | | |
| Change information, new shop, permanent close, clusters, outlet photo / Photo Capture, Sales Journey, Tutorial, Settings (fourth green Update tile), Logout | PLANNED slice 4 | | |
| KPI Dashboard, Brand-wise KPI, Target by SKU | PLANNED slice 3/4: hook screens (what the old app shows, "figures arrive later"), listed for the lead as an owner question | | |

## Tests run
- feature-memo unit (incl. `MemoManualScreensTest`: selector assertion changed to the manual's wording, new `vendorLayoutHas...` test), feature-sale unit, app-sr unit, ui-screenshots verify (all green locally).
- Not run: device or emulator; lane CI Android job (see the report to the lead).
