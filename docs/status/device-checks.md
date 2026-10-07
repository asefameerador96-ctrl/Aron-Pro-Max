# Device checks (owner's phones and printer)

The lead posts each check here as soon as the build for it exists, with exact steps (about 10 minutes each). The owner runs them in the evening and replies with what happened. Lanes add rows marked DEVICE-PENDING.

| # | Check | Needs | Status |
|---|---|---|---|
| D-01 | Install the SR debug APK from the CI artifact on the Galaxy A06, log in (dev API through Front Door), see Home | A06 + USB debugging, `adb install -r` | waits for android-sr-a first run |
| D-02 | Airplane mode: check in, open a visit, sell, review, save; network on; the sale appears once on the server | A06 | waits for SR slice |
| D-03 | Print a memo and a stock slip on the MP-58N from the SR app; compare with the photos of the current printout | A06 + MP-58N, photos of a real printout (owner to supply) | waits for android-print |
| D-04 | Factory-reset test phone: enrol as device owner (adb command from docs/setup), install SR, check in suspends the chosen apps, check out releases them | a reset phone (A07 or Honor X5c Plus) | waits for android-geo-dpc |
| D-05 | Five mock-location apps and one cloning tool refused or flagged | same phone | waits for android-geo-dpc |
| D-06 | 8-hour scripted battery and data run | A06 | Day 6 |
| D-P1 | DEVICE-PENDING (android-print, N-018): on the Galaxy A06 run the on-phone golden test: `./gradlew :android:core-printing:connectedDebugAndroidTest` with the phone on USB (about 3 min). Pass = `GoldenOnDeviceTest` 2/2 green (the phone renders the memo, stock slip, day summary, void slip and due receipt bit for bit like the JVM goldens) | A06 + USB debugging; can run from the laptop session | ready |
| D-P2a | DEVICE-PENDING (android-print, N-019 + F-SR-015): install the SR app (debug APK from CI) on the Galaxy A06; pair the MP-58N in Android Bluetooth settings (PIN 0000); in the app open Stock, tap the printer icon and pick the MP-58N (icon turns green). Enter 3 SKUs, Save, tap Print: the slip prints per SKU with category totals, answer "হ্যাঁ" to "ছাপা ঠিক আছে?"; the "slip not printed" banner disappears. Then: (1) Print again and switch the printer off half way: a failed message shows; switch it on, tap Print: the whole slip prints once from the top (a few white rows first are expected). (2) Open the cover / remove the roll, tap Print: "paper out" shows; put the roll back, Print again. Pass = no cut-off, no garbage, no blank gap, the app never froze, Save never waited for the printer | A06 + MP-58N + roll | ready (Stock print is on INT) |
| D-P2b | DEVICE-PENDING (android-print, F-SR-028/031/066): with D-P2a paired, sell the 40-line test sale and Print on Review: Yes, Yes, then "হ্যাঁ" to "ছাপা ঠিক আছে?"; the whole Bangla memo prints with totals equal to the screen. Reprint from the Memo menu: the paper shows "পুনর্মুদ্রণ #1". Edit that memo (Memo menu, Edit, any reason, change one quantity, Print, Yes): the new paper has its own memo number and the line "প্রতিস্থাপিত মেমো: <old number>", no reprint marker. Open Sales Summary and tap Print: the day summary prints (no marker, prints again on every tap). Pass = no cut-off, totals equal, marker present on the reprint only, supersedes line on the edit | A06 + MP-58N + roll | waits for sr-b's `SrSaleHosts` (Sale, Memo, Summary hosts; on `lane/android-sr-b` d4dc7e4) to reach INT |
| D-S1 | DEVICE-PENDING (android-sys, F-SYS-023): on a NON-enrolled test phone with the SR debug APK: (1) Settings > Apps > Aron SR > Permissions > Location: Don't allow; open Attendance: the Bangla screen "হাজিরার জন্য লোকেশন দরকার" shows, tap "অনুমতি দিন" or "সেটিংস খুলুন": the app's own Settings page opens; allow location "only while using", precise on; press Back: Attendance opens by itself. Same for Sale. (2) Set Location to Approximate only: the screen says precise location is needed. (3) Nearby devices (Bluetooth): Don't allow; open Sale: it opens normally; only the print button is disabled. (4) `adb shell dumpsys package com.aktcl.aron.sr \| grep -i RECORD_AUDIO` prints nothing | test phone (not device owner: prod pins grants) | waits for android-sr-a to wrap Attendance and Sale in `PermissionGate` |
| D-S2 | DEVICE-PENDING (android-sys, F-SYS-030), once android-sr-a has wired the camera: in the SR app open Force Sale (or New shop) and press the shutter: the camera screen opens, take a photo; the thumbnail shows. Then `adb shell run-as com.aktcl.aron.sr ls -l files/media/u*/` : each `.jpg` is at most 153600 bytes; `adb exec-out run-as com.aktcl.aron.sr cat files/media/u<id>/<uuid>.jpg > p.jpg` and open it on the laptop: upright, 1024 px long edge, no EXIF/GPS (`exiftool p.jpg` shows no GPS and no Orientation). Take a retake once: the second shutter is refused after that. After the shot the camera indicator (green dot, top right) goes off within a second. Time from shutter to thumbnail under 2 s | A06 | waits for android-sr-a wiring |
| D-S3 | DEVICE-PENDING (android-sys, F-SYS-019): on the Galaxy A06 with the seeded day, Settings > language: tap English: every screen switches at once, no dialog; numbers show 0-9; swipe the app away (kill) and reopen: still English; switch to বাংলা: Bengali digits, Bangla labels; kill and reopen: still Bangla. Open a half-filled stock or sale screen before switching: what was typed is still there after the switch | A06 + seeded day | ready once the SR build is installed |
| D-S4 | DEVICE-PENDING (android-sys, F-SYS-020), once the shell wires the updater and a release N+1 is published in the admin portal: on a NON-enrolled test phone with build N and 3 sales saved offline (airplane mode): turn Wi-Fi on, log in: the update page shows N+1 with notes in Bangla; tap Download, at 40% swipe the app away, reopen: it resumes (not from 0); at 100% tap Install: if asked, allow "Install unknown apps" through the guidance; the OS installs; reopen: the 3 sales are still in Sync (not lost, not doubled) and upload once. Then set min_version_code above N+1 for the SR flavour: with the day already open the banner asks to finish the day; after Sales Submit, the next day cannot start until updated | test phone (not device owner) + admin portal release | waits for the app wiring |
| D-N38 | DEVICE-PENDING (android-core, N-038 push): install the SR debug APK from a CI run whose `GOOGLE_SERVICES_JSON` secret is set and log in online as an SR on the Galaxy A06 (this registers the push token). On a phone that is NOT enrolled as device owner, first allow Settings > Apps > Aron SR > Notifications (Android 13+ starts with them off; enrolled phones get them from the policy). (1) In the admin portal (or as an AMO) assign a task to that SR: within about 30 s the phone shows "নতুন কাজ" / "আপনার একটি নতুন কাজ এসেছে। কাজের তালিকা খুলতে চাপুন।" (app in Bangla). (2) Screen off: assign another task: the notification shows on the lock screen. (3) Settings > Battery: Aron SR Unrestricted; swipe the app out of Recents; assign a task: the notification still arrives. (Settings > Force stop is different: Android delivers nothing to a force-stopped app until it is opened again; note what happens but it is not a fail.) (4) Tap the notification: the app opens on the task list and the new task is in it within 20 s. If a sale or memo screen was open, the app stays there and opens Tasks when you go back to Home | A06 + an AMO or admin login | ready once a CI build with the Firebase secret is installed |
| D-PERF-05 | DEVICE-PENDING (android-core, AUD-PERF-05): SR debug APK on the Galaxy A06, logged in once online and with a day loaded. (1) Swipe the app out of Recents, then `adb shell am start -W -n com.aktcl.aron.sr/.MainActivity` three times (swipe away between runs): note `TotalTime`. (2) `adb logcat -s AronPerf StrictMode` during each start: note `session_restore ms=` and `db_open user_db ms=` (pass: restore under 150 ms, first database open under 300 ms; a PBKDF2 key would show 500 ms or more). (3) Copy every `StrictMode` line (disk reads/writes on the main thread): android-core turns disk penaltyDeath on once that list is empty. No crash, no blank screen longer than 1 s | A06 + USB debugging | ready with the next SR debug build from CI |
| D-PERF-04 | DEVICE-PENDING (android-core, AUD-PERF-04; laptop session can run it): a LAB A06 that is NOT a device-owner field phone (debug-signed variants cannot replace a release-signed device-owner app, and force-stop clears its alarms). (1) `./gradlew :android:benchmark:connectedBenchmarkAndroidTest -Paron.benchmarkApp=sr`: note the StartupTimingMetric medians (Partial vs None) and `coldStartMeetsTheGate` (pass: process-cold median under 2,500 ms). (2) `./gradlew :android:benchmark:connectedBenchmarkProfileAndroidTest -Paron.benchmarkApp=sr -Pandroid.testInstrumentationRunnerArguments.class=com.aktcl.aron.benchmark.BaselineProfileGenerator`, copy the `*-baseline-prof.txt` from `android/benchmark/build/outputs/connected_android_test_additional_output/` to `android/app-sr/src/main/baseline-prof.txt` and hand it to android-core to commit. Repeat with `amo` and `tso` when those test phones are in the lab | lab A06 + USB debugging + laptop | ready now |
| D-MAP-074 | DEVICE-PENDING (android-core, F-SYS-074 + N-053): SR debug APK from a CI run whose `MAPS_ANDROID_KEY` secret is set, Galaxy A06, Bangla. (1) Online: Check In; the coordinates show at once, then within about 5 s the street address replaces them (pass: an address, in Bangla where Google has one). (2) Airplane mode, walk under 300 m, Check Out: the coordinates show with "(কাছাকাছি: <the check-in address>, সর্বশেষ জানা)"; no spinner, no wait. (3) Airplane mode far from there (or a new day elsewhere): coordinates only. (4) The SR home, route and sale screens show no map at any time; the out-of-range Map button still opens the N-041 map. The lite map screen itself is checked by android-amo/android-tso when Team Location / My Team land (map renders as one static image, list below; airplane mode shows the image as last seen and the list with ages) | Galaxy A06 + SIM/Wi-Fi | ready now (address part) |
| D-DB-VAC | DEVICE-PENDING (android-core, F-SYS-028 follow-up; laptop session can run it): lab A06 (or any phone/emulator) on USB: `./gradlew :android:core-database:connectedDebugAndroidTest --tests '*SqlCipherDeviceTest'` (about 2 min). Pass: all tests green, including the new assertion that a new encrypted user file reports `PRAGMA auto_vacuum` = 2 (INCREMENTAL), so the nightly purge returns disk space. Also covered by the CI emulator job once infra lands AUD-TP-4 | lab phone + USB debugging + laptop | ready now |
| D-ERR | DEVICE-PENDING (android-core, F-SYS-032): on the SR, AMO and TSO debug APKs (test account, logged in online): force a crash with `adb shell am crash com.aktcl.aron.sr` (then `.amo`, `.tso`), reopen the app and let it sync; force an ANR with `adb shell am hang` is not available on all builds, so instead skip ANR unless the lab has a debug ANR button. In the dev database: `SELECT kind, exception_class, device_id, left(stack, 200) FROM app.app_error ORDER BY id DESC LIMIT 5`: one row per app with device_id set and a stack trace; no phone number, outlet name or username in message or stack | A06 (+ the AMO/TSO test phones) + dev DB read access | ready with the next debug builds from CI |

## android-geo-dpc details (steps for D-04, D-05 and the rows below)


### N-021 location fix manager (android-geo-dpc)
Host proof: `ScriptedSrDayTest` (60-outlet day: 68 provider requests, at most 80; every fix stored with its mock flag).
On the Galaxy A06, with the SR app:
1. `adb shell dumpsys batterystats --reset`, then run a 60-outlet scripted day (check-in, 60 outlet opens, check-out).
2. `adb shell dumpsys location > loc.txt` and `adb shell dumpsys batterystats > bs.txt`.
3. Pass: at most 80 location requests by `com.aktcl.aron.sr`, no request still active after each outlet, GPS time at most 15 min; `geo_fix.is_mock` set on every row (export the day's records from the outbox).
4. Repeat steps with a mock-location app selected (dev phone): every fix has `is_mock = 1`.

### N-026 key attestation and Play Integrity (android-geo-dpc)
Host proof: `IntegrityCodecTest`, `IntegrityEvidenceServiceTest`, `DeviceKeySpecTest`.
On each test phone: `./gradlew :android:core-geo:connectedDebugAndroidTest --tests '*DeviceKeyStoreDeviceTest'` (key created, attestation extension present, signature verifies with the sent JWK). Play Integrity needs the Google Cloud project number in the build; on a phone without Google services the status report carries no token and the reason `no_play_services`.

### N-029 device-owner policy core (android-geo-dpc)
Host proof: `PolicyApplierTest`, `DevicePolicyContractTest`, `AndroidDpmGatewayTest`.
On an enrolled Galaxy A06 with the **prod** policy applied (last check: see the warning in the setup page):
1. Settings > About phone > tap Build number seven times: developer options do not turn on.
2. Settings > Apps > Aron SR: Uninstall and Force stop are greyed out; `adb uninstall` is impossible (USB debugging is off).
3. Settings > Apps > Aron SR > Permissions > Location: cannot be changed from "Allow".
4. Settings > General management > Reset > Factory data reset: refused ("blocked by your IT admin").
5. Reboot: after boot, `dumpsys device_policy` (from a dev phone, or the status report in the portal) shows the same restrictions.

## android-sr-b details (sale, memo, dues, Sales Submit) — wired in the SR app shell, DEVICE-PENDING
Host proof: `SaleReviewTest`, `SaleCommitterTest`, `SaleFlowTest`, `CheckerTest` (feature-sale), `DaySummaryTest`, `MemoDomainTest`, `HomeKpiTest` (feature-memo), `SalesSubmitTest` (feature-dayclose), and the Compose screen tests.
On the Galaxy A06 (SR app, airplane mode on, seeded day):
1. **Offline sale (F-SR-023/025):** enter 20 sticks of a cigarette SKU and 3 pieces of a lighter; the pack badge, unit and running total show; Review shows category subtotals and the net. Save. Pass: the memo number is shown, nothing waits for the network.
2. **Kill mid-sale (F-SR-025):** enter quantities, force-stop the app from Recents, reopen: the same outlet and the same quantities come back. Then tap Save and force-stop immediately: after relaunch there is exactly one memo for that visit (Memo menu), never two.
3. **Credit (F-SR-026):** at Review tick Credit, type a paid amount below the total with two decimals: the due shows to the paisa; the amount equal to the total is refused.
4. **Zero sale (F-SR-029):** open an outlet, proceed with no SKU, confirm: a memo with no lines exists and the visit counts as visited and no-sale.
5. **Slide (F-SR-022) and QC (F-SR-027):** with an offered SKU, 10 empty packets give one reward pack shown as a deduction; QC deduction is defect sticks times price; after QC is done the sale cannot be edited.
6. **Edit (F-SR-033):** at the shop edit a memo (reason list shows three); away from the shop the edit is refused with the geofence message.
7. **Dues (F-SR-032):** Memo menu shows the due in red; Mark paid asks first, settles the whole due once; a second tap does nothing.
8. **Sales Submit (F-SR-034/035), then online:** Sync data retries until device and server counts match; Submit is enabled; with a retailer owing, a warning shows but Submit still works; turned offline it queues and the success text only shows after the server settles.
9. **Bangla, font 1.3:** all of the above in Bangla with the system font scale at 1.3: no truncation, 48 dp targets, Bengali digits.

### D-UI-01 outdoor legibility (android-core-ui, owner)
Host proof: `TokenContrastTest` (key figures 7:1, body 7:1 in tier B, secondary 4.5:1, 35 percent glare proxy), Roborazzi goldens.
Owner, on the Galaxy A06, outdoors in direct sun, screen brightness at 100 percent, SR debug APK:
1. Light mode (default). Open Home: read aloud the three tile labels, the sync chip and today's sales figure. Pass: every one readable at arm's length without shading the screen with a hand.
2. Open Sale entry: read the quantity, the line total and the "Next" button text. Pass: all three readable.
3. Open Review: read the grand total, the due amount and the Confirm button. Pass: readable.
4. Open the Memo preview: read the memo number, total and the Print button. Pass: readable.
5. Repeat steps 1 to 4 after tapping the Sunlight (sun icon) switch in the top bar. Pass: at least as readable as light mode, and the screens are fully solid (no translucent panels).
6. Say which of the 4 screens, if any, you had to shade; note the time of day and weather. Fail on any number or primary action you could not read: send a photo to the lead.

### D-UI-02 press-and-hold 1.2 s (android-core-ui)
Host proof: the accessibility long-click path is unit-tested; the timed hold cannot run on the host.
On the A06, on a screen using `AronHoldToConfirm` (for example submit Review):
1. Press and hold the button for about 0.5 s, then release. Pass: nothing is confirmed, the progress ring resets.
2. Press and hold for 1.0 s, release. Pass: not confirmed.
3. Press and hold for 1.5 s without lifting. Pass: confirms once (one vibration tick), never twice.
4. Press, hold 1.0 s, slide the finger off the button and release. Pass: cancelled.
5. Turn TalkBack on, double-tap and hold (or use the Confirm custom action). Pass: confirms.

### D-SRA-1 SR Settings language and logout (F-SR-005, F-SR-007; android-sr-a)
Host proof: `SettingsScreenTest` (logout confirmation, Bangla digits, optional rows), AppLocale tests.
On the Galaxy A06 with the seeded day:
1. Settings, tap the Bangla switch. Pass: every visible string is Bangla with Bengali digits. Kill the app from Recents and reopen: still Bangla. Repeat for English.
2. Settings, Log out, Cancel. Pass: still signed in. Log out again, confirm. Pass: login screen; sign in again as the same SR: yesterday's saved visits and unsent memos are still there and upload.
3. Kill and relaunch mid-way through step 2's confirmation dialog. Pass: no crash, session state is consistent.

### D-SRA-2 Force Sale photo (F-SR-018; android-sr-a)
Host proof: ForceSaleController and screen tests, camera pipeline tests (core-media).
On the A06, standing more than the outlet radius from a seeded outlet: open Sale, pick the outlet, Force Sale, choose a reason, take the outlet photo. Pass: sale proceeds; the visit shows photo_validated true, geo_validated false; offline then online: one location-change request and one photo upload per Force Sale. Deny the camera permission: Pass: the permission gate explains and blocks, no crash.

### D-SRA-3 PDA to Support (F-SR-006; android-sr-a)
Blocked until the support public key is configured. Then: Settings, PDA to Support, Send in airplane mode. Pass: "will be sent when online". Turn the network on. Pass: after the worker runs the screen shows Sent at HH:MM (Dhaka time).

### D-SRA-4 Outlet map on tap (N-041; android-sr-a)
After the Maps key restriction includes the SR package: out of range at an outlet, tap Map. Pass: pin, radius circle and own position show; with the network off the text distance and Force Sale still work.

### D-SRA-5 My requests (F-SR-040; android-sr-a)
Host proof: `OwnRequestsTest` (merge, statuses, reason, Bangla, empty). On the A06: Outlet, send one new-outlet request offline. Pass: My requests shows it as "Saved on this phone, waiting to be sent". Go online, sync, refresh the bundle (open the app next morning or pull a delta). Pass: it shows Pending, then Verified/Approved/Rejected as the office decides; a rejected one shows "Reason: ...". Kill and relaunch: the list is unchanged. Repeat in Bangla.
