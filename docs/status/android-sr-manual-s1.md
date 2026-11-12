# Status: lane android-sr-manual S1 (SR manual rows SRM-A-01 to SRM-A-12, TRAIN 1), 2026-11-12

Branch `lane/android-sr-manual-s1` from INT `claude/wonderful-thompson-k6ejnf`. Spec: `docs/requests/sr-manual-build-2026-11-part-a.md` and notes `docs/ui-reference/sr/manual/01..08` (lead docs branch). Decisions: `DECISIONS.md` D-SR-MAN-S1-01 to 09. **No contract change is needed for TRAIN 1** (see the end).

## Rows

| Row | Manual | State | What was done / what is left |
|---|---|---|---|
| SRM-A-01 | s2 / 01 APK name and size | **needs release tooling** | App label "ARON SR" matches. CI has `tools/ci/tso-apk-copy.sh` and `amo-apk-copy.sh` but no SR copy script for `aron_sr_app_<dd_mm_yyyy>_v<version>.apk`. Size budget unchanged. Infra lane. |
| SRM-A-02 | s2 / 02 installation complete | done (no change) | System UI; label and icon exist. Icon art to confirm in the device lab. |
| SRM-A-03 | s3 / 03 login | **done** | `LoginVariant.Sr` / `SrLoginContent`: gradient hero, labels, outlined fields, gradient "লগইন" pill, footer "SR App / (version - x)" without the vendor credit; shared login button word now "লগইন"/"Login" (golden login_bn_light and login_bn_sunlight re-recorded: only that word changed). Enrol link stays after the server's "not enrolled" refusal only. Owner question: one version number (1.0.25 vs 1.0.1). |
| SRM-A-04 | s3 / 03 location prompt | **done** | `LocationFirstGate`: precise location sheet right after login, once, before the notice and permission list. No hard block of the whole app (offline-first); gates for attendance, sale, outlet capture explain a denial. **Owner question Q-LOC:** hard block wanted? |
| SRM-A-05 | s3 / 03 Bangla-first | done (no change) | |
| SRM-A-06 | s3 / 04 OTP | **done (UI, TSO screen); owner question open** | `OtpManualContent` (bar, red heading, 4 red boxes, green Verify, red paragraph) for the SR; TSO app Device OTP screen against the existing `listDeviceOtps`/`issueDeviceOtp`, server-side scope, never kept, never logged. OTP once per device (default of the audit): **owner question Q1** (per installed version?). Paragraph wording differs from the vendor's on purpose (D-SR-MAN-S1-02). |
| SRM-A-07 | s3 / 04 no resend/timer | done (no change) | Ours is stricter: 5 attempts, 120 min TTL. |
| SRM-A-08 | s3 / 05 camera and audio | **done (camera); audio refused by rule** | Camera system prompt at first camera open (`PermissionGate(askOnFirstOpen)`), camera removed from the SR first-run list. No RECORD_AUDIO (F-SYS-023). Owner question Q2 default no. |
| SRM-A-09 | s4 / 06 new update | **done** | `SrUpdateContent`: strip, ribbon, cloud, heading, version, release notes by language, flat red "Download App & Install". `notes_en`/`notes_bn` are already in the contract and in the app DTO. Server must fill them (backend/release admin: check that `update-check` returns notes; the audit said it does not show them, the field is in `AppRelease`). |
| SRM-A-10 | s4 / 06, 07 download and update | **done** | Red bar "Downloading n%", red warning line, "Download completed", installer starts by itself once; Cancel leaves the Install button. Resume, Wi-Fi gating, sha256 and signature checks unchanged. |
| SRM-A-11 | s4 / 08 in-app data update | **done (UI + real step counter); delta prompt owner question** | `DataUpdateContent` (prompt, "অ্যাপ আপডেট হচ্ছে", "প্রসেসিং(১৪/৮৭)", tip) over the bundle download with `BundleProgress`. The download still starts automatically (day's login event); the prompt is the retry state. Not built: a prompt when only a newer delta exists (TRAIN 2 / owner). |
| SRM-A-12 | s5 / 09 dashboard header | done (no change) | MATCH in the audit. |

## Tests (green, three full runs)

Suite totals (unit tests, all green): feature-auth 62, feature-home 77, feature-tso 283, core-system 114, core-sync 301, app-sr 102, app-amo 122, app-tso 85; ui-screenshots 598. Modules run: feature-auth, feature-home, feature-tso, core-system, core-sync, app-sr, app-amo, app-tso (unit) and lintDebug for each, plus ui-screenshots (goldens). New tests:
- feature-auth `SrManualAuthScreensTest` (9: SR login labels bn/en, footer without vendor text, enrol link absence, empty submit, OTP screen en/bn, four digits to Verify with a fake bind and a wrong code, back to login).
- core-system `SrUpdateUiTest` (11: strip, ribbon, notes by language, offline, downloading, completed, required/optional Later, our extra states, no vendor name) and `PermissionGateFirstOpenTest` (5: camera asked once by itself and never the microphone, grant opens, asked before is not asked again, location keeps its explained screen, opt-out).
- feature-home `DataUpdateScreensTest` (8, incl. `PermissionState.deferred`).
- core-sync `BundleDownloaderTest` +3 (steps 1/2 then 2/2; paged 1..4 of 4; no steps on 304, never "done" on a failed run).
- app-sr `LocationFirstGateTest` (5, `hostComposeRule`).
- feature-tso `TsoDeviceOtpTest` (10: parser, no code in toString, issue body, refresh/offline/refused, only listed reps can be issued, merge, show more, screen en/bn); `TsoNavTest` list extended by DEVICE_OTP.
- app-tso `TsoApiTest` +3 (GET with limit/cursor and no scope, never written to the cache folder; 403 is Gone; POST body and answer mapping).
- Changed existing test: `LoginScreenLocaleTest` (button word "লগইন", the owner's manual) and `TsoNavTest` (new enum name); no test skipped, weakened or given a longer timeout. Roborazzi goldens login_bn_light/login_bn_sunlight re-recorded.
- Look captures (not goldens): `SrManualShot` (login, OTP empty/filled, update, downloading, completed, data prompt, data running) to `build/kitshots`.

## Contract members needed

**None for TRAIN 1.** The TSO OTP screen uses `listDeviceOtps` and `issueDeviceOtp` as they are; `notes_en` and `notes_bn` already exist in `AppRelease`/`UpdateCheck.latest` (release admin PATCH too). Items for the lead to schedule, not blocking: (a) backend/web: confirm `GET /v1/app/update-check` fills `notes_*` from the release row and the release admin page edits them; (b) infra: SR APK copy script for SRM-A-01; (c) optional TRAIN 2: a bundle "newer version available" prompt (SRM-A-11 delta) needs only the existing `KEY_BUNDLE_CURRENT` meta, no contract change.

## Not done / limits

- Device behaviour was not run on a phone (no emulator here); Robolectric host tests only. Device-lab checks to add: first-login location sheet then notice, camera sheet at first camera open, installer dialog after "Download completed", Bangla digits and fonts on the update pages.
- SR login hero is drawn (no bitmap); the logo plate uses the existing brand mark, not the vendor's pastel disc.
- Sunlight: the hero is solid; the green Verify uses a darker green in Sunlight (contrast not computed in a token test).
