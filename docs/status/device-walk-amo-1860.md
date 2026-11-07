# Device walk: AMO APK 1860 (look + Home only), written 2026-11-07

For the device-lab operator: a local Claude Code session on the owner's Windows laptop inside the Aron folder, pilot phone on adb, signed APK 1860 already installed. The owner may also run it by hand. Source of the rows: `docs/requests/amo-manual-build-2026-11.md` s8 (77 rows); reference images: `docs/ui-reference/amo/` (each `NN-<slug>.jpg` has a note `NN-<slug>.md`).

## 1. Purpose and scope for 1860

APK 1860 holds ONLY shell push 1 (`docs/status/android-amo-shell.md`): the AMO look (purple bar, light grey page), the 17-tile Home in the manual's order, the Outlet badge, the chevron that folds the grid, four target cards that print "—" (no target source yet), and the navigation skeleton. Nothing else of the AMO build is in it.

**Checkable now (rows 1, 2, 3, 4, 8, 9, 75):** launch, launcher label, login screen, the location permission dialog, the camera/audio/Bluetooth dialogs only if the app raises them (see rows 4 and 12 below), Home look and tile order against `08-dashboard_menu.jpg`, target cards "—", Outlet badge, chevron, Settings as far as present.

**Must wait for later APKs (mark `blocked`, do not hunt for them):**
- Push 2 of the shell (A1): rows 5, 6, 7 (update flows), 10 to 13 (Bluetooth, printer, Stock permission, printer icon), 14 to 16 (attendance), 41 (Stock), 76 and 77 (PDA send, logout dialog), the login and Settings parity of rows 3 and 75 beyond what is present, the release copy name of row 1.
- Calls (A2): rows 17 to 32, 42 to 45, 65, 66.
- Team (A3): rows 33 to 40, 71 to 74. Outlet (A4): rows 46 to 64.
- Deferred by owner (docs/27, Astha): rows 67 to 70, never built.
Status files `android-amo-outlet.md` and `android-amo-team.md` were not present in the doc worktree when this brief was written; treat outlet and team as not in 1860.

## 2. adb commands (Windows PowerShell)

Package id: `com.aktcl.aron.amo` (`android/app-amo/build.gradle.kts` applicationId; a test copy would carry a suffix such as `.copy`, so confirm with the list command). Launcher activity: `.MainActivity` (exported, MAIN/LAUNCHER in `android/app-amo/src/main/AndroidManifest.xml`).

```
adb devices
adb shell pm list packages | findstr aktcl
adb shell dumpsys package com.aktcl.aron.amo | findstr versionName
adb shell dumpsys package com.aktcl.aron.amo | findstr versionCode
adb shell settings get secure location_mode
adb shell am start -n com.aktcl.aron.amo/.MainActivity
adb shell screencap -p /sdcard/aron-01.png
adb pull /sdcard/aron-01.png device\amo-1860\01.png
adb logcat -d | findstr aron
```

Notes:
- `adb devices` must show exactly one device as `device` (not `unauthorized`). If it does not, stop and tell the owner.
- `location_mode` prints 0 (off) to 3 (high accuracy). It only reads; note the value in the report.
- Create the folder first on the laptop: `mkdir device\amo-1860` (a local folder, not on the phone). Name each screenshot `<nn>.png` with the row number (two digits), for example `device\amo-1860\08.png`; extra shots add a letter (`08b.png`). Use `/sdcard/aron-<nn>.png` on the phone with the same `<nn>`.
- Pass if `versionName`/`versionCode` match build 1860; if not, stop (wrong APK) and report it.
- Allowed on the phone: launching the app, tapping inside the app, taking screenshots, reading settings and logs. NOT allowed: `adb install`, `adb uninstall`, `pm clear`, `pm grant/revoke`, `settings put`, `svc`, `input` taps outside the app, anything that changes the phone. If an install is wanted, the owner says so first.
- Permission dialogs are answered on the phone by hand or with taps inside the dialog; take the screenshot BEFORE answering. If a dialog was answered earlier and no longer appears, do not reset it (no `pm revoke`): mark `blocked: already granted` and ask the owner whether to revoke it in Android Settings.
- Logcat: read the whole `adb logcat -d | findstr aron` once after the walk, plus `findstr FATAL` and `findstr AndroidRuntime`; copy only error or crash lines, never lines that contain a token, password or phone number.

## 3. Rows checkable on 1860

Reference path prefix: `docs/ui-reference/amo/`. Verdict: `match`, `differs: how`, or `blocked: why`. Owner directive 14a: where the screenshot and our look disagree on layout, the screenshot wins; intended differences (rulings R1 to R5 of the request s9) are listed in the "Expected, not a defect" column.

| Row | Screen | Reference image | What to compare | Screenshot to save | Expected, not a defect | Verdict |
|---|---|---|---|---|---|---|
| 1 | Find the APK and install | `01-find_the_apk_and_install.jpg` | Only: the launcher icon is present with a name. The file name of the APK (release copy name) is a later push. | `device/amo-1860/01.png` (launcher with the icon) | Install itself already done; file name check blocked | match / blocked |
| 2 | Installation complete | `02-installation_complete.jpg` | Launcher label reads "ARON AMO"; app opens from the icon. | `device/amo-1860/02.png` | Our icon, not the vendor icon | |
| 3 | Login and location permission | `03-login_and_location_permission.jpg` | Login: gradient header with logo, labels ইউজারনেম and পাসওয়ার্ড, gradient-outline fields, pill button লগইন (coral to purple), footer. Right half: the Android location permission dialog over the login. | `device/amo-1860/03.png` (login), `03b.png` (permission dialog) | Footer reads "ARON AMO v<version>"; vendor credit "Developed by Apsis Solutions" must NOT appear (R1); our login may have a language toggle and password eye | |
| 4 | Camera and audio permission | `04-camera_and_audio_permission.jpg` | Android camera and microphone dialogs. In 1860 they appear only if the app asks at login; the capture screens that ask are outlet forms (later). | `device/amo-1860/04.png` if shown | If no dialog appears: `blocked: camera/audio asked only in outlet capture, not in 1860` | |
| 8 | Dashboard menu | `08-dashboard_menu.jpg` | Header: avatar, bold "<name> (<username>)", second line "<zone> AMO, <date>" (with a space). Purple top bar, light grey page, white card, 3-column white tiles in this order: অ্যাটেনডেন্স, জয়েন্ট কল, কন্ট্রোল কল / টিম লোকেশন, টিম পারফর্মেন্স, টাস্ক ডেলিগেশন / লাইভ ড্যাশবোর্ড, এস আর স্টক, আউটলেট (red badge) / স্টক, বিক্রয়, মেমো / সারসংক্ষেপ, বিক্রয় জমা, আস্থা-position tile / Report, বিক্রয় যাত্রা, Campaign Enrollment-position tile / সেটিং. 17 visible tiles in total; scroll to see the last row. | `device/amo-1860/08.png` (top), `08b.png` (scrolled to the last row) | Astha and Campaign Enrollment are deferred (docs/27): hidden, so the 17 tiles close up; Report is always shown (R5); header spacing fixed (R3); badge digit in locale digits (R4) | |
| 9 | Target cards (bottom of Home) | `09-target_cards_bottom_of_the_dashboard.jpg` | Chevron "^" under the grid folds and unfolds the tiles; below it four sky-blue cards: আজকের টার্গেট, টোটাল কল টার্গেট, কন্ট্রোল কল টার্গেট, জয়েন্ট কল টার্গেট. | `device/amo-1860/09.png` (cards), `09b.png` (grid folded) | Figures print "—" in 1860 where the vendor shows "0/0" (no target source yet); this is intended | |
| 75 | Settings | `75-settings.jpg` | Whatever Settings shows: language tile with items "en" and "বাংলা"; language change applies at once; Log out row. Compare only the parts that exist. Also try the Sunlight switch if present. | `device/amo-1860/75.png` | PDA/send-data and logout dialog (rows 76, 77) are blocked; Exceptions is a Settings row | |

Extra checks to record under the row they belong to, no extra screenshot unless something is wrong: tap each of the 17 tiles once; every tile must open some screen (a skeleton or empty state is fine) and Back must return to Home without a crash; the Outlet tile opens the Outlet menu skeleton. Note the first Home load time by eye ("under 3 s / slow").

## 4. SR scenarios and the TSO pointer (same build)

The two SR scenarios for the SR app of this build are not written out as such in `lead-state.md` (it only names "SR 1807 scenarios", row AJ); use these:
- **Scenario A:** a full sale with a memo print on the smoke route: log in as the dev test SR, open the smoke route, visit an outlet inside range, sell a few SKUs (packets / dozens / box per the units rule), save, print the memo on the MP-58N, check the memo text and total. Screenshots `device/amo-1860/srA-<n>.png`.
- **Scenario B:** a visit refused out of range, then Manual Override: go to an outlet while standing out of range, expect the "not within range" refusal, take the Manual Override path (outlet photo, camera permission), finish the visit and confirm the visit is flagged. Screenshots `device/amo-1860/srB-<n>.png`.
Each scenario: verdict per step, the exact text on screen, any logcat error lines. The SR app and the AMO app each need their own phone or a reset (one phone holds one device-owner app, `docs/status/qa-device-lab-honor.md` s "Not on this phone"); if only one phone is attached, do the AMO walk first and ask the owner before switching apps.

**TSO:** the 44-row TSO conformance ledger is `docs/status/tso-manual-conformance.md` (device rows there are walked by the owner against the TSO APK; not part of 1860). Run it only if the owner asks, and report per row in the same format.

## 5. How to report

If the operator is a Claude session: send ONE message to the lead session `session_01MbUQSxrP7AB9tbyANjUTPS` with the `send_message` tool (priority `later`). If the owner runs it by hand: paste the same text to the owner. Format, one line per row:

```
AMO APK 1860 device walk <date>, phone <model>, versionName <x>, location_mode <n>
row 3 | match | device/amo-1860/03.png, 03b.png | logcat: none
row 8 | differs: tile order, Report before Sales Journey missing | 08.png, 08b.png | logcat: <lines>
row 9 | match (cards print "—" as intended) | 09.png | -
rows 5,6,7,10-16,17-66,71-74,76,77 | blocked: not in 1860
SR A: <verdict per step> | SR B: <verdict per step>
Crashes: <FATAL / AndroidRuntime lines or none>
Questions for the owner: <only what needs their hands: permissions to revoke, a second phone>
```
Screenshots stay on the laptop in `device\amo-1860\`; say where they are and give the owner the folder. A one-line verdict per row is enough; describe a difference in words (what the screenshot shows, what the phone shows). Also add a line for each D-AMO-* item you touched, in the `docs/status/device-checks.md` style: `**D-AMO-HOME** (F-AMO-001): <what you saw>` (HOME for rows 8 and 9, plus rows 3 and 75 as D-AMO-LOGIN, D-AMO-SETTINGS if the owner wants them logged).

## 6. Safety

- Never print, paste or store a password, token, enrolment token or OTP. Credentials for the dev test accounts (the AMO test user `amo1001`, the SR and TSO test users) come from the owner's vault by the owner's hand; type them on the phone, never into chat, files or logs. Redact phone numbers in logcat excerpts.
- Never push to the integration branch `claude/wonderful-thompson-k6ejnf`, never run git commit or push for this walk; keep results in `device\amo-1860\` and in the report message.
- Never run gradle (or any build) on the laptop for this walk.
- No `adb install`, `uninstall`, `pm clear`, `pm grant/revoke`, `settings put`. Read and screenshot only.
- If anything looks wrong with the phone or the app crashes on launch, stop, report the logcat lines, and wait for the owner.
