# Status: lane android-amo-outlet (A4), started 2026-11-06

Branch `lane/android-amo-outlet` (from INT 7f34a1d2). Push only that branch; merge INT before every push; one push per CI run.

## Push 1 (all rows 46 to 64)
- **Menu (46):** `AmoOutletMenuContent` (two headings, three verification tiles with red badges incl. the zero badge, five operation tiles; one column from 190 % font). Test `OutletFormsTest.menuShowsTheEightTilesAndTheZeroBadgesOfTheVendor`; goldens `amo_outlet_hub_{bn,bn_fs2,sunlight_bn,en}`.
- **Verification (47 to 53):** `VerifyListContent` (route, cluster, name, owner, contact), `VerifyFormContent` + `AmoVerifyHost`: Sub-Channel / Geo Classification drop-downs, GEO + photo re-capture with thumbnail (new), Save approves, Cancel rejects after the হ্যাঁ/না question, success dialog. Tests `OutletFormsTest.verifyingANewOutletNeedsTheRecapturedGeoAndPhoto`, `AmoRecordWriterReplayTest.aVerificationUsesTheFixCapturedWithThePhotoAndClaimsThePhotoOnce`, `aMockedCapturedFixIsNeverQueuedAsAVerification`, `aVerificationDecisionSurvivesRelaunchAndNeverDoublesForTheSameRequest`; goldens `amo_verify_*`.
- **Own forms (54 to 61):** `AmoNewShopHost`, `AmoCloseHost`, `AmoChangeInfoHost` in `AmoOutletOwnForms.kt`; dialogs per screenshot; goldens `amo_new_shop_*`, `amo_close_*`, `amo_info_*`, `amo_outlet_saved_dialog_*`.
- **Update Base (62 to 64):** `AmoUpdateBaseHost` + `UpdateBaseMapContent`; tests `OutletFormsTest.aDropBeyondTheCapLandsOnTheCircleEdgeAndInsideStaysPut`, `UpdateBasePointTest`; goldens `amo_base_*`.
- Side-by-sides: `docs/evidence/ui-parity/amo-{46,47,50,52,55,57,59,62,63,64}.png`.
- Decisions D-AMO-OUTLET-01..05.

## Open / to wire when the lead says the foundation train landed
- Route and cluster on the verification form: DONE (D-AMO-OUTLET-08/09; `ROUTE_CLUSTER_EDITABLE` is gone).
- The `cfg.geo.update_base_*` keys are read by name already (`AmoDay`); nothing to change.
- Device rows (camera, live map drag, printer-free): 48, 56, 60, 64 (walk on a pilot phone).

## Push 4 (2026-11-11): route_id / cluster_id on verification (D-AMO-OUTLET-08) and HANDOVER
- Done: pushes 1-3 (rows 46-64, checker fixes D-AMO-OUTLET-06/07, train 83 landed), push 4 wires the contract 1.11.1 members.
- HANDOVER: nothing open for A4 except device rows 48, 56, 60, 64 (camera, live map drag; walk on a pilot phone) and the owner's decision on editable name/owner/mobile in verification (no contract member). Low, unfixed: a photo captured and then followed by a reject stays unclaimed. Traps: `tools/android-sdk.sh` + `echo sdk.dir=/opt/android-sdk > local.properties`; goldens only on Linux with `-Proborazzi.record=true --tests '*AmoOutletScreenshotTests*'`; grep BUILD, never trust a piped exit code.

## Push 5 (2026-11-11): lead follow-ups (D-AMO-OUTLET-09)
- Only the AMO's picks are sent; a changed route needs a cluster; refusedDecision ordered and re-read on open, on every return to the screen and after each save (no polling); a held (quarantined) decision blocks a new one; `VerifyDecision.kt` (`decideFromForm`) is the tested save. Flaky, not mine: `AmoDayLockHostsTest.editOnTheMemoDetailFollowsTheLockThatLandsWhileItIsOnScreen` failed once under load, passes alone and on rerun.
