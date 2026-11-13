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

## Icons from the owner's screenshots (lead correction 2026-11-13)
The first version used 112x112 px webp crops of screenshots 08 and 20 (icon content about 85 px, a 720 px-wide screenshot), drawn at 52 to 72 dp: 156 to 216 px on an xxhdpi phone, so upscaled about 2x and blurry. Replaced the same day by vector drawables traced from the same crops (`tools/trace-amo-icons.py`: 3x upscale, 12-colour quantise, vtracer, white background dropped; the gradient becomes colour bands). They are resolution independent, so the draw size no longer matters. The Astha logo (a raster logo in the old app) is vectorised the same way (20 colours).
Crops contain artwork only: no status bar, name, phone number or other personal data (the words "LIVE" and the Astha mark are part of the icon art). Size: 13 files, 435 KB of vector XML before aapt compiles and the APK compresses it (the earlier webps were 25 KB); APK size to be measured by the lane CI size gate against docs/04.
