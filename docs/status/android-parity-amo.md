# CHECKED 6d46b2f9 (run 43405636098) and 282d7bba (run 43406851895): every lane ci job finished green or skipped. Both reports were sent to the lead by message; the second send failed on a connector credential error (retry pending).

# Lane P-AMO2 (2026-11-13): lows 1 to 5 pushed (6d46b2f9, D-PAR-AMO-04). Slice 2 lane-owned parts committed (D-PAR-AMO-05); shared sale-screen items requested (docs/requests/android-parity-amo.md R-PAR-AMO-01..05).

# HANDOVER (2026-11-13, lane P-AMO, context near the limit)
State: branch lane/android-parity-amo head fec17bd5 pushed. Slice 1 gated by the lead at 83678c2c. Done and pushed: Home 19 tiles with hooks, vector icons, Manual Override direct camera, Control call tile art and "All", AMO update screens (05, 07, processing) with tests, lows 6 to 9 of the lead's list.
Open, in this order: (A) lead's lows 1 to 5 (listed below in "Lead's 9 lows"); (B) rest of slice 2: Sales route/outlet pick (dropdowns, alphabet bar, Sales tile on empty state), Sale entry (packshots + blue badge, outlet card with রুট, rate/discount/stock column, discount zero), Review (printer-off icon, print-later note, paid-amount dialog), Previous sale data (calendar date field). Shared SR screens (feature-sale/stock/memo) must not be edited: parameterise from app-amo (AmoSaleKit.kt, AmoSaleHosts.kt); (C) slices 3 to 5 as in the plan message. Rows not yet in the table: everything from slice 2 on.
How to work: `ANDROID_HOME=/opt/android-sdk` + local.properties sdk.dir (installed in this container; re-run tools/android-sdk.sh in a new one); `./gradlew -q :android:app-amo:testDebugUnitTest :android:feature-amo:testDebugUnitTest`; goldens `./gradlew -q :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*Amo*ScreenshotTests*'`; each Gradle run is 1 to 3 minutes, run in background and wait with Monitor. Push rule from the lead: "checked <sha>" only after every lane ci job on that sha finished; push again only after "gated" for a candidate. Always record goldens BEFORE the push (CI compares).

# Lane P-AMO (android-parity-amo): AMO app parity with the existing Apsis app

Branch `lane/android-parity-amo` from INT 4e064555. Scope: `android/app-amo`, `android/feature-amo` only (core-ui, feature-sale/stock/memo are shared: not touched; differences go in as AMO wiring parameters or lead requests in `docs/requests/android-parity-amo.md`).
Nothing in this lane is walked on a device. Proof is unit tests, Roborazzi images and the lane CI Android job.

## Table (finding, decision, commit, image)

| Finding (audit AMO section) | Decision | Commit | Image |
|---|---|---|---|
| Home: row 6 holds Report + Campaign Enrollment, Astha row 5 | FIX: Astha and Campaign Enrollment hook tiles in the old place, open "coming later" (D-PAR-AMO-01) | 5a47720e, 50470e83 | amo_home_* |
| Home: old icon set (8 icons) | FIX: icons cropped from the owner's screenshot 08 into feature-amo res (core-ui untouched) | 5a47720e, 50470e83 | amo_home_* |
| Control call: Manual Override opens the camera directly | FIX: camera opens on entry, menu follows the photo; page stays as fallback (D-PAR-AMO-02) | 5a47720e, 50470e83 | n/a (system camera) |
| Control call: tiles in place under route/outlet/alphabet bar | RECORD: code already shows them in place (golden amo_cc_tiles_bn); the audited image was older. Icons now the old art | 5a47720e, 50470e83 | amo_cc_tiles_bn |
| Control call: alphabet "সব" | FIX: "All" as the old app | 5a47720e, 50470e83 | amo_control_call_pick_* |
| Control call: Manual Override fallback (camera cancelled, camera off, waiting) | FIX: tested fallback, camera-off reason, labelled waiting state (D-PAR-AMO-04) | this push | amo_manual_override_waiting_en, amo_manual_override_camera_off_bn |
| Update screens 05 and 07 (look) | FIX: vector back with label, token colours and sizes, Sunlight goldens (D-PAR-AMO-04) | this push | amo_update_* |
| Sales pick: Sales tile missing on the empty state (21, 22) | FIX: tile under the fields before a retailer is chosen (D-PAR-AMO-05) | slice 2 | amo_sales_pick_empty_bn |
| Sales pick: drop-downs, alphabet bar | RECORD: already the old picker (slice 1 code) | - | amo_control_call_pick_* |
| Sale entry: header card, rate/discount/stock column, blue badge, green ± | REQUEST: shared feature-sale/core-ui (R-PAR-AMO-01, 02) | - | amo_sale_entry_bn |
| Review: crossed-out printer icon | FIX: printer state on the bar during the sale (D-PAR-AMO-05) | slice 2 | n/a (host) |
| Review: print-later note; totals with quantity | REQUEST: R-PAR-AMO-03, 05 | - | amo_review_bn |
| Paid-amount dialog not recorded | RECORD: amo_paid_amount_bn; dialog presentation requested (R-PAR-AMO-04) | slice 2 | amo_paid_amount_bn |
| Previous sale data: calendar date field | FIX: date field + picker (90-day window) + ISO line (D-PAR-AMO-05) | slice 2 | amo_previous_sales_* |
| Control call: extra "মূল্য সম্মতি" tile | KEEP-WITH-REASON: only when the server price-compliance flag is on (default off) | - | - |

## Icons from the owner's screenshots (lead corrections 2026-11-13)
First version: 112x112 px webp crops of screenshots 08 and 20 (icon content about 85 px), drawn at 52 to 72 dp = 156 to 216 px on xxhdpi, so upscaled about 2x and blurry. Replaced by vector drawables traced from the same crops with `tools/trace-amo-icons.py` + `tools/amo-icon-jobs.py` (2x upscale, median filter, colour quantise, vtracer, white background dropped, offsets folded into the path data, no groups). The first trace was 435 KB for 13 icons (33 KB each); lead asked for at most about 8 KB and 60 paths each. After simplifying (12 down to 6 colours, speckle filter, longer minimum segment) the 13 icons total 146 KB, none over 24 paths, at 8 to 14 KB each. Four are at 8 KB (sales_journey 7.9, report 8.2, team_performance 8.4, cc_assess 8.6). The other nine are over because below 12 colours the detail that makes them recognisable is lost (the clock, the "$", the "LIVE" word, the Astha crest): live_dashboard 10.7, team_location 10.8, control_call 11.4, sr_stock 12.7, cc_sale 12.8, joint_call 13.5, cc_survey 13.5, campaign 13.9, astha 13.9. Lead decides per icon: lower budget (loses the detail) or keep.
Crops hold artwork only: no status bar, name, phone number or other personal data. Drawn size is irrelevant to sharpness now. The update screen illustrations (shown once per release, not on Home) are 8.7, 22 and 20 KB with 15 to 22 paths. The earlier webps were about 25 KB in total; the APK delta is read from the lane CI size gate (docs/04) and will be stated here.

## Lead's 9 lows on gated 83678c2c (2026-11-13): status
Done: (6) amo_cc_tiles goldens use the real new art (re-record pending in the next push); (7) amo_art_sales_journey.xml deleted (Home keeps the owner art `aron_art_sales_journey`); (8) "এস আর স্টক" with the space (Control Call tile "এসআর পারফ." matches screenshot 20); (9) addendum written in DECISIONS.md.
Done in lane P-AMO2 (D-PAR-AMO-04): (1) fallback-path test plus a real second confirm in AmoControlCallHostTest; (2) camera-off reason on the fallback page (bn, en); (3) waiting label and content description, ManualOverrideContentTest; (4) no new sweeper needed: MediaUploader.housekeeping drops an unclaimed CAPTURED photo after 24 h on the next uploader run, MediaStore.sweepUnfinished drops half-written files (MediaUploaderTest pins it); (5) AmoUpdateScreen moved to feature-amo, vector back with a string description, token colours and sizes, 8 update goldens + 2 Manual Override goldens.
