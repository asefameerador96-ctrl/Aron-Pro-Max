# Lane P-AMO (android-parity-amo): AMO app parity with the existing Apsis app

Branch `lane/android-parity-amo` from INT 4e064555. Scope: `android/app-amo`, `android/feature-amo` only (core-ui, feature-sale/stock/memo are shared: not touched; differences go in as AMO wiring parameters or lead requests in `docs/requests/android-parity-amo.md`).
Nothing in this lane is walked on a device. Proof is unit tests, Roborazzi images and the lane CI Android job.

## Table (finding, decision, commit, image)

| Finding (audit AMO section) | Decision | Commit | Image |
|---|---|---|---|
| Home: row 6 holds Report + Campaign Enrollment, Astha row 5 | FIX: Astha and Campaign Enrollment hook tiles in the old place, open "coming later" (D-PAR-AMO-01) | slice 1 | amo_home_* |
| Home: old icon set (8 icons) | FIX: icons cropped from the owner's screenshot 08 into feature-amo res (core-ui untouched) | slice 1 | amo_home_* |
| Control call: Manual Override opens the camera directly | FIX: camera opens on entry, menu follows the photo; page stays as fallback (D-PAR-AMO-02) | slice 1 | n/a (system camera) |
| Control call: tiles in place under route/outlet/alphabet bar | RECORD: code already shows them in place (golden amo_cc_tiles_bn); the audited image was older. Icons now the old art | slice 1 | amo_cc_tiles_bn |
| Control call: alphabet "সব" | FIX: "All" as the old app | slice 1 | amo_control_call_pick_* |
| Control call: extra "মূল্য সম্মতি" tile | KEEP-WITH-REASON: only when the server price-compliance flag is on (default off) | - | - |
