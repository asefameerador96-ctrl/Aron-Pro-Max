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
| Control call: extra "মূল্য সম্মতি" tile | KEEP-WITH-REASON: only when the server price-compliance flag is on (default off) | - | - |

## Icons from the owner's screenshots (lead corrections 2026-11-13)
First version: 112x112 px webp crops of screenshots 08 and 20 (icon content about 85 px), drawn at 52 to 72 dp = 156 to 216 px on xxhdpi, so upscaled about 2x and blurry. Replaced by vector drawables traced from the same crops with `tools/trace-amo-icons.py` + `tools/amo-icon-jobs.py` (2x upscale, median filter, colour quantise, vtracer, white background dropped, offsets folded into the path data, no groups). The first trace was 435 KB for 13 icons (33 KB each); lead asked for at most about 8 KB and 60 paths each. After simplifying (12 down to 6 colours, speckle filter, longer minimum segment) the 13 icons total 146 KB, none over 24 paths, at 8 to 14 KB each. Four are at 8 KB (sales_journey 7.9, report 8.2, team_performance 8.4, cc_assess 8.6). The other nine are over because below 12 colours the detail that makes them recognisable is lost (the clock, the "$", the "LIVE" word, the Astha crest): live_dashboard 10.7, team_location 10.8, control_call 11.4, sr_stock 12.7, cc_sale 12.8, joint_call 13.5, cc_survey 13.5, campaign 13.9, astha 13.9. Lead decides per icon: lower budget (loses the detail) or keep.
Crops hold artwork only: no status bar, name, phone number or other personal data. Drawn size is irrelevant to sharpness now. The update screen illustrations (shown once per release, not on Home) are 8.7, 22 and 20 KB with 15 to 22 paths. The earlier webps were about 25 KB in total; the APK delta is read from the lane CI size gate (docs/04) and will be stated here.
