# Device checks (owner's phones and printer)

The lead posts each check here as soon as the build for it exists, with exact steps (about 10 minutes each). The owner runs them in the evening and replies with what happened. Lanes add rows marked DEVICE-PENDING.

## Device day 2026-10-08: start here (infra, updated 2026-10-08 05:45 UTC)

**Status: enrolment is ON on dev, and the phone can now enrol itself (path 2).** Dev requires enrolment
(`cfg.device.require_enrolled` = true, the registry default; integrity off; lockdown as seeded). An unknown phone is
refused at login with "this phone is not enrolled" (403 `ERR_DEVICE_NOT_ENROLLED`) before the password is checked.
Two ways in:

- **Path 2 (default, needs nobody's decision): enrol the phone with a token.** android-core's enrolment screen
  (59631840, f357e344, 1d48cddb) is on INT from 2b76764f in all three apps: a new install shows "Enrol this phone"
  before login (Scan QR or paste the token). Use the **release-signed** APK from the first green `ci` run on INT
  2b76764f or later (run 687+). Steps: **D-ENR** below (normally installed phone). The server side is on dev: one
  published app_release per app (sr, amo, tso; CI signing certificate 468d9b4e...a4fb) so an admin can mint tokens.
  Minting a token needs an **admin** web login, which needs MFA (next block).
- **Path 1 (only if the owner says so): enrolment off on dev.** The lead has asked the owner. Only after the words
  "owner approves dev enrolment off" reach infra through the lead does infra write it, as a new audited config version
  through the dev seed path (author `aron.system`, like seed 04). **No admin login is needed for path 1**: the
  config API (`/v1/admin/config/changes`) is maker-checker and would need two MFA admins. The phone checks then need
  only the APK, `sr1001` and the bind code from `tso1001`'s web page. D-04 needs it back ON (infra writes a new
  version that turns it on; nothing is edited in place).

**Web logins on dev: who needs MFA** (lead read of backend-core 6541cf94; checked by infra against INT d596637f:
V0006, `contract/openapi.yaml`, `LoginService.kt`)
- `cfg.auth.mfa_required_roles` = `["ADMIN","SUPERADMIN","SUPPORT"]` (V0006 default; no seed row changes it or enrols
  anyone). So `admin1001`, `superadmin1001` and `support1001` need MFA. **`tso1001` (and the AMO/DMO accounts) do
  not**: `tso1001` logs in on the web with the password alone and reads the bind code on `/device-otp`, today.
- The MFA routes exist only from backend-core ee9755c0, which is on INT d596637f (deploy run 37732865997, started
  05:32 UTC). On any older deploy an admin web login could not finish. Check `GET /v1/health` shows build
  `d596637f...` or later before trying.
- **Admin first login is trust on first use. There is no web enrolment screen yet** (web-dashboard is held). The
  **laptop session** does it once for `admin1001` through the API:
  1. `POST /v1/auth/login` `{"username":"admin1001","password":<seed password>,"client":"web"}`: the answer says
     `status` `mfa_required` and an `mfa_token` (5 minutes).
  2. `POST /v1/auth/mfa/enrol` with `Authorization: Bearer <mfa_token>`: **200** with the TOTP `otpauth://` URI
     (it contains the secret) and ten recovery codes, **shown once**. Write the whole answer straight to a local
     file readable only by you (`chmod 600`), or into a password manager. **Never print it, paste it in chat, put it
     in a log or a commit.**
  3. Compute the current 6-digit code from that secret locally (for example `oathtool --totp -b <secret>`, read
     from the file, not typed on the command line), then `POST /v1/auth/mfa/verify`
     `{"mfa_token":"<from step 1>","code":"123456"}`: **200** with the full session. A recovery code
     (`XXXX-XXXX`) also works once enrolment is confirmed. Wrong codes count towards the account lockout.
  4. Later logins: steps 1 and 3 only (enrol answers 409 once confirmed; only an admin's `reset_mfa` starts over).
- **Owner's hands (only if the owner wants to log in to the web as admin1001 themself):** the laptop renders the
  `otpauth://` URI as a QR **locally** (for example `qrencode -t ansiutf8 < file`); the owner scans it with an
  authenticator app. Nobody else ever sees it. After that the normal web login works for them: password, then the
  login form's code step (status `mfa_required`) takes the 6-digit code. The form has no enrolment step, so the
  first enrolment must be the API one above.
- These enrolments are sealed with the key derived from the JWT signing key until `aron-mfa-key` is live (infra
  bd4ad030). They stay readable after that; they are lost only if the signing key rotates first.

**Laptop-session check (proves tokens can be minted on dev), after the admin login above:** as `admin1001`, open
`/admin/enrolment` in the web dashboard and create a token (app SR, lockdown **dev**, 2 uses, 24 h), or call
`POST /v1/admin/enrolment-tokens` with `{"flavour":"sr","lockdown_level":"dev","max_uses":2,"expires_in_h":24}` and
the bearer from step 3. Expected: **201** with `enrolment_token` and `qr_text`. The token is shown once: never paste
it into chat, a file or a commit; show the QR on the laptop screen for the phone to scan. 404 "no published release
of this flavour to provision" means the release seed (infra 09b) is missing on dev: tell infra. The token's app must
match the APK (an SR token for the SR app); 2 uses covers both test phones.

**Checks that need NO login, run these first on the A06:** D-P1 (on-phone print goldens), D-PERF-04 (lab
benchmark), D-DB-VAC (SQLCipher device test), D-UI-01 if it runs on the seeded day without a server login.

**What the owner needs either way**
- **APK:** GitHub, Actions, the latest green `ci` run on the integration branch, artifact
  `aron-release-signed-dev-<run>` (use this one, not `aron-debug-apks-*`: enrolment checks the release signing
  certificate). Unzip, phone on USB with USB debugging on, then `adb install -r aron-sr-0.1.<run>-dev.apk`, then enrol it (D-ENR) before the login below.
  The APK talks to the dev API `https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net` (baked in by CI).
- **Login:** username `sr1001` and the dev seed password (Key Vault `aron-dev-seed-password`; the laptop session
  reads it, never paste it in chat). There is no phone-number/SMS login and no fixed dev OTP.
- **Binding the phone:** the first login on a new phone answers "bind required" and asks for a 4-digit code.
  Read it in the web dashboard as `tso1001` or `admin1001` on the Device OTP page (`/device-otp`); valid 2 hours.
  A second phone binds the same way (up to 4 phones per user). sr1001 is the only seeded SR, so both test phones
  use sr1001.
- **Route and sale:** sr1001 has routes MIR-SR-D (today's), MIR-SR-3F and MIR-SR-2F; 60 outlets in Mirpur 10 with
  SKUs and prices in the bundle. The geofence is 100 m, so away from Mirpur use **Force Sale** (pick a reason, take
  the outlet photo); the sale is saved and flagged "not geo-validated", which is expected.
- **Server check afterwards** (laptop session or infra): the sale appears once. Web dashboard tile for the day shows
  it; or in the dev database
  `SELECT memo_no, count(*) FROM app.memo WHERE business_date = '2026-10-08' GROUP BY memo_no HAVING count(*) > 1;`
  returns no rows, and `SELECT memo_no, net_mtk, status FROM app.memo WHERE business_date = '2026-10-08' ORDER BY id;`
  lists each phone sale once. The API-level proof of the same path runs on every dev deploy (slice smoke).

| # | Check | Needs | Status |
|---|---|---|---|
| D-01 | Install the SR debug APK from the CI artifact on the Galaxy A06, log in (dev API through Front Door), see Home | A06 + USB debugging, `adb install -r` | waits for android-sr-a first run |
| D-02 | Airplane mode: check in, open a visit, sell, review, save; network on; the sale appears once on the server | A06 | waits for SR slice |
| D-03 | Print a memo and a stock slip on the MP-58N from the SR app; compare with the photos of the current printout | A06 + MP-58N, photos of a real printout (owner to supply) | waits for android-print |
| D-04 | Factory-reset test phone: enrol as device owner with the portal QR (steps in D-ENR below), log in, check in suspends the chosen apps, check out releases them | a reset phone (A07 or Honor X5c Plus) | waits for android-geo-dpc |
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

### D-ENR phone enrolment, needed before any login on dev with the enrolment gate on (android-core, 2026-10-07)

Use the **release-signed** APK from CI (the server checks the app's signing certificate against the published app_release; a debug-signed APK is refused with "could not verify this phone or app").

Normally installed phone (D-01 and the other checks):
1. Laptop: admin portal `/admin/enrolment`, create a token: app SR (or AMO, TSO), lockdown **dev**, 1 use. The QR shows once; keep the page open. (Dev has a published release row per app, cert digest 468d9b4e…a4fb. Without the portal, signed in as admin1001: `POST /v1/admin/enrolment-tokens` with `{"flavour":"sr","lockdown_level":"dev","max_uses":2,"expires_in_h":24}`; the answer's `enrolment_token` can be pasted in the app (`qr_text` is the QR content).)
2. Phone: install the APK and open it. The app shows "Enrol this phone". Tap **Scan QR** and scan the laptop screen (Google's scanner opens; no camera permission is asked). Without the scanner, paste the token text instead.
3. "Enrolling…", then the login screen. Log in with the user of that app. The app asks for a 4-digit code: open `/device-otp` on the laptop, read the code (valid 120 minutes), enter it. Home opens.
4. If it says the token expired or was used, create a new one (step 1). "No connection": the token is kept; tap **Try again** when online.

Device-owner phone (D-04):
1. Factory-reset the phone. Laptop: create a token for SR with lockdown **prod** (or **dev** to keep USB debugging).
2. On the phone's first welcome screen tap six times, connect to Wi-Fi, scan the QR. The phone downloads the SR app and makes it device owner; when set-up ends, open ARON SR.
3. The app shows "Enrolling…" and finishes by itself (tap **Try again** if it was offline). Then log in and enter the code from `/device-otp` as above. Continue with D-04 (check-in suspends the chosen apps, check-out releases them).

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

### D-PERF-04 phone speed gates (android-core AUD-PERF-04, laptop session or lab device)
Host proof: the :android:benchmark module and benchmark build types build in CI. The numbers need a real phone.
On the Galaxy A06 (or the lab device), release-like build, laptop session with USB debugging:
1. Run the macrobenchmark cold-start test for the SR app. Record the median of 10 runs. Gate: cold start 2.5 s.
2. Record sale save time on the Review screen (gate 300 ms) and scroll smoothness on the outlet list (no frame above 32 ms in 95 percent of frames).
3. Record the release APK size against the 30 MB budget and the INT baseline (+5 percent warn, +15 percent fail).
4. Write the numbers and the device model in docs/status/android-core.md; a miss is a row for android-core.

### D-SRA-6 First bundle on a new phone (F-SR-001; android-sr-a)
Host proof: FirstBundleScreenTest, LoginScreenLocaleTest. On a wiped A06 log in online. Pass: "Getting your day ready" with a moving bar; airplane mode half-way shows "no connection"; back online, Try again finishes. Rotate or switch language during the download: no false failure and no second download. Skip: check-in still works. Login version equals Settings version; footer reads (c) AKTCL.

### Device-owner preparation (laptop session, 2026-10-15)

Prepared 2026-10-16 (every check below ran on 2026-10-16; the heading keeps the brief's title).
Owner runbook: [`docs/runbooks/honor-device-owner.md`](../runbooks/honor-device-owner.md). The owner approved the factory
reset and device owner for the Honor X5c Plus; the laptop session prepared and verified, the reset waits for the owner's
hands. Nothing on the phone was installed, changed or reset. Dev API build `4085e0d0` at the time of the checks.

**Headline: the first-boot QR path (D-ENR "Device-owner phone", docs/24 s10.4 steps 2 and 3) is blocked on dev today, by
two defects outside this lane. The adb path (s10.4 step 5) is the one that works and the runbook leads with it.**

1. **The published SR release on dev is the seed row, not build 722.** `GET /v1/admin/releases` answers 404 (the admin
   releases API is not built; `backend/analytics` has only the enrolment-token and device routes), so the row was read
   through `GET /v1/app/update-check?flavour=sr` as admin1001: `release_id` 1, **version 0.1.516 (code 516)**, sha256
   `95bee189…`, `size_bytes` 20,000,000 (a placeholder, per the seed), `download_url` =
   `https://github.com/asefameerador96-ctrl/Aron-Pro-Max/actions/runs/37652960567`, cert `468d9b4e…a4fb`, published
   2026-10-07. That is exactly `infra/sql/devseed-app-release.sql` (09b), whose own comment says QR provisioning from it
   is not possible. Expected 722 / `98f571c8…` / 12,542,325 bytes: **mismatch on version, sha256 and size**; the
   signing certificate matches, which is the only field enrolment checks (the Honor enrolled with 722 on 2026-10-15 as
   device 2, so that check is proven).
2. **The QR's download location is not an APK.** `curl -I` of the `download_url` (anonymous, as Android's provisioning
   downloads) returns `200 text/html` (the Actions run page). Android would fail the download. No anonymous APK URL
   exists on dev yet: CI's `release-manifest.json` has no `download_url` ("added by the store upload", N-064), and the
   release-signed APKs live only in the Actions artifact (login required, zip). **Fix (infra / backend-admin):** put
   `aron-sr-0.1.722-dev.apk` on an anonymous HTTPS URL (blob behind Front Door) and publish a 722 release row (or
   build `POST /v1/admin/releases`).
3. **Build 722 lacks the Android 12+ provisioning activities.** `aapt dump xmltree` of the APK the owner has (sha256
   `98f571c81f27d8666ea698d4d6adcef664ca69db25d73bf37f50a1cde27ef658`, 12,542,325 bytes, `versionCode` 722, signer
   `468d9b4e…a4fb` by `apksigner verify --print-certs`, copied to `%USERPROFILE%\AronApk\do-check\`) declares
   `AronDeviceAdminReceiver` (BIND_DEVICE_ADMIN, `PROFILE_PROVISIONING_COMPLETE`) but **no**
   `android.app.action.GET_PROVISIONING_MODE` / `ADMIN_POLICY_COMPLIANCE` activity. The classes exist
   (`android/dpc/.../ProvisioningActivities.kt`); the manifest entries wait on android-core's "allowlisted" reply to
   `docs/requests/android-geo-dpc-provisioning-activities.md` (`AppCoexistenceTest` still lists only MainActivity and the
   receiver). On Android 12+ a QR-triggered fully-managed setup aborts ("Can't set up device") when the DPC does not
   resolve both; the Honor is Android 15.
4. **What is right:** token minting works (admin1001 MFA login reused from the saved enrolment, enrol answers 409);
   the QR payload names `com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver`, `LEAVE_ALL_SYSTEM_APPS_ENABLED`
   true, admin extras `aron.api_base_url` = the dev Front Door origin, `aron.env` dev, `aron.flavour` sr,
   `aron.lockdown_level` dev; `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` base64url-decodes to
   `468d9b4eccb776e9f40e20e20aaf0f14627e2316e901d53af3fc9aa4e393a4fb` = the certificate digest; `qr_text` is 961 chars.
   The in-app enrolment screen (`DeviceEnrolment.readInput`) accepts the same QR text or the bare token, so the minted
   token serves the adb path too. Dev lockdown (docs/24 s10.2 table, `PolicyApplier`): `adb_enabled` set to 1,
   `no_debugging_features` off, uninstall and factory reset not blocked, permissions granted but not pinned.
5. **Token minted for this run:** `token_id` **3**, flavour sr, lockdown dev, max_uses 2, used 0, expires
   **2026-10-19 17:30 UTC**, note "Honor X5c Plus device-owner enrolment". Secret and QR only in
   `%USERPROFILE%\AronSecrets\sr-do-token.txt` and `sr-do-qr.png` (owner-only ACL). No other active token on dev.
6. **Phone facts (adb reads, 2026-10-16 17:33 UTC; screenshot in the session scratchpad):** HONOR `NLA-LX2P`
   (X5c Plus), Android **15** (API 35), MagicOS 9.0.0, patch 2026-07-01; `dpm list-owners` = **no owners**,
   `dumpsys device_policy` shows no device admin (only the Android 15 role holders
   `com.google.android.devicelockcontroller` and `com.hihonor.realtimedevicemanager`, not owners); `com.aktcl.aron.sr`
   0.1.722 installed (installer null); `adb_enabled` 1, developer options on; **Accounts: 0** (no Google, no HONOR ID),
   one user; Facebook (`com.facebook.katana`) and TikTok (`com.ss.android.ugc.trill`) installed as Honor preloads
   (`pm path` → `/preload/app/Facebook/Facebook.apk`, `/preload/app/TikTokSEA/TikTokSEA.apk`) and YouTube
   (`com.google.android.youtube`) is a system package (`pm list packages -s`), so all three default-block-list apps
   return by themselves after the reset (D-04 needs no reinstall); no mock-location app selected (`appops query-op android:mock_location allow`: none);
   screen: Wi-Fi settings, connected, no SIM, battery 100 percent. Server view, device 2: `enrolled`, `device_owner`
   false, lockdown dev, bound to sr1001, last status trigger `check_in` on 2026-10-15.
7. **Also noted:** `docs/setup/device-owner-test-phone.md` says `adb uninstall` removes a dev device owner; Android
   refuses to uninstall an active device admin, and there is no in-app unenrol yet, so the runbook names the factory
   reset (allowed under dev) as the way out. Owner of that page to confirm on the phone and correct.
8. **Review fixes applied to the runbook (2026-10-16):** D-04 now states its precondition (working day, before
   `hard_end_time` 20:00 Dhaka: `BlockingEngine.shouldBlock` returns false otherwise, backend defaults
   `working_days_only` true) and lists YouTube with Facebook and TikTok; the Google account for the D-05 Play installs
   is added only after `dpm set-device-owner` succeeds (dev policy allows accounts and adb installs after); the bind code
   must be typed within the 10-minute `bind_token` window (the OTP itself lasts 120 min); the battery-optimisation
   prompt is hedged (`BatteryExemption.requestIfNeeded` is not wired in build 722); the adb error list names "already
   several users" (second user / Private Space) and drops the "already set-up" message, which Android raises only for
   non-shell callers; the QR path moved to an appendix. The runbook is about 1,500 words including the four adb blocks (the
   brief's eight required parts plus the review's additions), a little over two printed pages.

### D-04 result, Honor X5c Plus (owner + laptop session, 2026-10-17 Dhaka 10:55 to 11:09)

- Factory reset by the owner; `adb install -r aron-sr-0.1.724-dev.apk` (local release-signed build, cert `468d9b4e…a4fb`);
  `adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver` → "Device owner set";
  `dpm list-owners` lists it as DeviceOwner, Affiliated; USB debugging stayed on (dev lockdown). Path A of the runbook.
- Enrolment: a token typed over adb was refused as unknown (the IME altered the injected text, most likely); token 4 scanned
  with the in-app QR scanner from the laptop screen → enrolled. Login sr1001 and the bind code (Device OTP panel) → bound.
  Location notice accepted and permissions granted by the owner.
- **Check-in suspends the blocked apps: PASS.** Before: `com.facebook.katana`, `com.ss.android.ugc.trill`,
  `com.google.android.youtube` not suspended. After the hold-to-confirm check-in (11:08:59): all three `suspended=true`,
  `dumpsys device_policy` shows `suspendedPackages=[youtube, trill, facebook]`; YouTube Music and Chrome untouched.
- Note for android-geo-dpc: at the same second PackageManager logged "Cannot suspend package" for `com.hihonor.ridemode`,
  `com.android.vending`, `com.hihonor.contacts`, `com.hihonor.systemmanager` and `com.aktcl.aron.sr` (system-exempt,
  verifier, default dialer, active admin). Harmless, but the policy apparently asks the OS to suspend packages that can
  never be suspended; trim the list or filter by `PackageManager` exemptions to keep the log clean.
- **Check-out releases them: PENDING** (the check-out gate opens at 17:00 Dhaka; run it this evening and re-check
  `suspended=` on the three packages). **D-05 (mock-location apps): PENDING**; a Google account may now be added to the
  phone (device owner is set) so Play can install the five mock apps and the cloning tool, or sideload them with adb.

### D-05 result, mock-location detection (laptop session over adb, 2026-10-17 11:15 to 11:19 Dhaka)

- Method: no third-party fake-GPS app was installed (Play needs the owner's Google account; APK mirrors are not a trusted
  source). Instead the new device-lab test `android/core-geo/src/androidTest/.../MockGpsHoldTest.kt` (test APK with the
  mock-location app-op, run with `am instrument`) held a fake fix at 23.8069, 90.3687 for 150 s: on the platform GPS test
  provider AND on Google's fused client (`setMockMode` + `setMockLocation`), because the app reads the fused client
  (`AndroidLocation.kt`) and a platform-only test provider never reaches it. A shell-created test provider cannot be used:
  Android removes it when the `cmd location` process exits.
- Visit check on "Mirpur 10 Store 1" (seeded route, radius 100 m): with the platform-only mock the app took the real
  network fix (5,554 m away, no mock line). With the fused feed, Refresh gave **"নকল লোকেশন ধরা পড়েছে। নকল লোকেশন থেকে এই
  দোকানে বিক্রয় করা যাবে না।"**, refresh count 2, the range view with the phone inside the circle, and the bar offered only
  Force Sale and Refresh: **a mocked fix is never geo-valid (PASS for the mechanism; policy `warn_rep`)**.
- Pending: the five real fake-GPS apps and the cloning tool (owner installs them from Play once a Google account is
  added, or hands the APKs to the laptop); the server re-check of the stored visit flags (`is_mock`) after a sync.
- Observation for android-sr-a / the real-data plan: on Saturday 2026-10-17 the picker opened the seeded "Mirpur 10 Daily"
  route without offering the real route 16685 (Sat/Mon/Wed); check whether seed 06's plan rows mark it `plannedToday`
  and whether the route picker (F-SR-065) is reached when two routes are planned.
- **Check-out releases them: PASS** (laptop session over adb, 2026-10-17 17:03 Dhaka). Before: `com.facebook.katana`,
  `com.ss.android.ugc.trill`, `com.google.android.youtube` all `suspended=true`. Attendance screen → "Check out" →
  hold sheet ("Checking out", 17:03, no location reading indoors) → press-and-hold 1.5 s → within 6 s all three
  `suspended=true` counts were 0 and `dumpsys device_policy` no longer lists `suspendedPackages`. Note: the Home dock
  read "Sales submit" after 17:00 (day-state order: submit, then check out), so the check-out was taken from the
  Attendance bar. **D-04 complete** on the Honor X5c Plus (device owner by adb path A).

## android-amo (wave 2), DEVICE-PENDING (nothing here has run on a phone; unit and Robolectric tests only)
Login as `amo1001` on the AMO app (bind OTP like the SR), seeded zone, Honor X5c Plus (the Galaxy A06 is not in reach).
- **D-AMO-HOME** (F-AMO-001): the eight hub tiles show; Outlet carries a badge only while verification requests are pending.
- **D-AMO-ATT** (F-AMO-003): check-in with press-and-hold, address online and coordinates in airplane mode, check-out only from 17:00, Bangla and English.
- **D-AMO-CC** (F-AMO-004/005/036): Control Call tile, pick route then retailer; stand away from the retailer: "AMO is not within the retailer's range" with Refresh and Manual Override; Manual Override takes the outlet photo (camera permission), the call proceeds, a location-change request appears in the outbox; the 11th override of a day is refused (default limit 10); repeat the radius check in airplane mode; kill the app on the menu and relaunch (back on the menu, no second visit).
- **D-AMO-ASSESS** (F-AMO-007/008/009): in a Control Call open SR Perf. Assessment: brands from the product list, OOS only after the brand is ticked, POSM yes/no/unanswered, Save shows "Saved" and returns to the retailer list with the route kept; after sync the server shows one distribution_check and one line per brand.
- **D-AMO-JOINT** (F-AMO-011): Joint Call shows the route's SR, stars for the enabled criteria, Save, in Bangla and English.
- **D-AMO-OUTLET** (F-AMO-025/026/027/022/023/024/028): Outlet hub: New shop, Permanent close (dues warning), Change information, My requests; Verify requests lists an SR's pending request: a new outlet needs sub-channel and geo class before Save; Cancel returns with nothing queued; airplane mode shows the summary and Save still queues. Update Base: confirm, photo, move the pin with the 10 m buttons (not beyond 100 m), Send for approval; Bangla and English.
- **D-AMO-TEAM** (F-AMO-016/017/018/019/020/021/038): Team hub: Team Location (needs the build with the Maps key; offline shows the list and the last image), Task Delegation (assign to an SR for an outlet; the task shows "Waiting to be sent" in airplane mode and the SR gets it after sync), Live Dashboard (Filter applies only on the button; strike rate 1 of 81 reads 1.23 %), SR Stock (masked phones, lifted stock by SKU), Exceptions (review/dismiss/confirm offline; one event each), Team Performance (empty state while targets are deferred).
- **D-AMO-REPORT** (F-AMO-032/033/034): Report tile: STD Memo Report (first of the month to yesterday, first column stays while the table scrolls sideways) and Sales Summary Up To Now (a zero target shows a dash); Settings: language, Sunlight, PDA to Support queues the file offline and sends on reconnect, Log Out; Bangla and English.

### D-QA-1 Day 1 ten-minute check script (N-015; qa) — DEVICE-PENDING, laptop session
Script: `tools/checks/day1-check.ps1` (tested in the cloud against a fake adb and a mock API: all-pass, wrong
password and no-tools runs; check 4 passes against dev from the cloud: `/v1/health` 200 via Front Door, build f84b22b).
Steps (laptop session, the Honor X5c Plus on USB, about 5 minutes):
1. `git pull`, then in a PowerShell window (not double-click) from the repository folder:
   `powershell -ExecutionPolicy Bypass -File .\tools\checks\day1-check.ps1` (add `-Apk <path>` to
   install or update the SR APK first). It asks for the password of `tso1001` (not shown, never stored; or set
   `$env:ARON_CHECK_PASSWORD` in that window only).
2. When it says so, the owner logs in on the phone as `sr1001` and answers `y` if the home screen opened.
3. Pass = `ALL PASS (5 checks)`: phone listed, APK installed, login succeeded (API), login on phone, /health from
   Azure. Paste the window (it holds no secret) into `docs/status/laptop.md` and tell the lead.

## android-tso (wave 2, 2026-10-17): checks that need the phone
- D-TSO-001 (F-TSO-001): on the Honor X5c Plus or Galaxy A06 the TSO login is dark navy in Bangla and English; the sun switch on the login row turns on Sunlight mode (light, solid); log out with unsent items is refused with the count, with nothing unsent it wipes and returns to the dark login.
- D-TSO-008/009 (F-TSO-008/009): leave list in Bangla and English against the seeded zone; apply one leave in flight mode: the card shows "not sent yet"; switch the data on: it becomes "DMO approval pending" after the next list refresh.
- D-TSO-018/029 (F-TSO-018/029): feedback saved offline shows "not sent yet" and becomes a server row after sync; the category list is the seeded one.
- D-TSO-028 (F-TSO-028): the blue Home button sits bottom centre above any bottom bar on Leave apply / Feedback form and does not cover Send; drawer opens by the hamburger and lists seven entries with subtitles in Bangla and English.
- D-TSO-011/012 (F-TSO-011/012): with the Maps key in the build, My Team shows one lite map with a pin per SR who has synced a fix, legend by route code, age of each fix; flight mode: last image and list stay with the cached banner. Retailer: Allow location prompt appears once, radius 50/100/300 each shows pins around the TSO, a mock location shows the warning.
- D-TSO-024 (F-TSO-024): Settings: language switch recreates the screen in the other language; Send data file queues offline and uploads on Wi-Fi; change password with the shared dev user (never write the password anywhere).
- D-TSO-018 (photo): feedback with one photo: the camera opens, the compressed photo (docs/04 budget) is queued, offline it waits, on Wi-Fi it uploads; logout is refused while the photo is unsent.
### D-QA-2 Day 2 ten-minute check script (N-022; qa) — DEVICE-PENDING, laptop session + owner
Script: `tools/checks/day2-check.ps1` (shared helpers in `tools/checks/AronCheck.psm1`). It was tested in the cloud with
a fake adb and a mock API: the all-pass path, a doubled memo (FAIL "2 new memos for one sale") and a run without
tools. It needs an SR APK with sale and print (F-SR-028, android-print / android-sr-b) on the phone, logged in as
`sr1001`, with the MP-58N paired.
Steps (about 10 minutes; the Galaxy A06 or the Honor X5c Plus on USB):
1. `git pull`. In a PowerShell window, run `powershell -ExecutionPolicy Bypass -File .\tools\checks\day2-check.ps1`. It asks for `tso1001`'s
   password, which is not shown and never stored.
2. The script switches airplane mode ON itself and checks that the phone has no network. When it says so, the owner
   checks in, opens an outlet, sells ONE memo and prints it, then answers `y` if exactly one memo printed. If the
   printer needs Bluetooth, switch only Bluetooth back on: not Wi-Fi, not mobile data.
3. The script switches airplane mode OFF and waits up to 5 minutes for the memo on the server, then once more a
   minute later.
4. Pass = `ALL PASS (10 checks)`: phone, app, web login, airplane on, offline sale, printed memo, network on, on the
   server, still once, print on server. The script always switches airplane mode back off, even when it fails. Paste the window into `docs/status/laptop.md`.

### D-QA-3 Day 4 ten-minute check script (N-050; qa) — DEVICE-PENDING, laptop session + owner outdoors
Script: `tools/checks/day4-check.ps1`. It was tested in the cloud with a fake adb and a mock API: a readable config, a
refused config (403) and a config outage. Stand about 70 m from the outlet.
1. `git pull`; `powershell -ExecutionPolicy Bypass -File .\tools\checks\day4-check.ps1 -Outlet MIR-D-001`. It asks
   for `tso1001`'s password and reads `cfg.geo.radius_m` resolved AT THE OUTLET, which is what the phone's bundle
   carries.
2. In the web portal, set 50 m for that outlet's zone (or the outlet) and approve it. Config changes are
   maker-checker, so a second admin may be needed. The script waits up to 10 minutes for the outlet to resolve 50.
3. On the phone, Sync. At about 70 m from the outlet, read the visit screen: it must say "limit 50 m" (সীমা ৫০ মি) and
   a distance between 50 and 100 m, and the sale must stay closed (Refresh, then Force Sale). An accuracy, mock or
   no-fix refusal does not count. Force-sell or close the visit so it syncs.
4. Check 7, "stamped new radius", FAILS by design until `docs/requests/qa-visit-radius-read.md` is done. Until then
   the laptop session reads `app.visit.radius_m_used` of that visit on dev.
5. Set it back to 100 m in the portal and approve it. Check 8 waits for the outlet to resolve 100 again.

### D-QA-4 Day 3 ten-minute check script (N-043; qa) — DEVICE-PENDING, laptop session + owner
Script: `tools/checks/day3-check.ps1`. It was tested in the cloud with a fake adb that has real-format per-user output
(user 0 versus a work profile): no spoofing app (FAIL "not tested"), a visit accepted (FAIL), check-in, and check-out
(including "not checked in" FAIL). It runs on the ENROLLED Honor X5c Plus (device owner by adb path A, D-04).
1. Morning, checked out:
   `powershell -ExecutionPolicy Bypass -File .\tools\checks\day3-check.ps1 -SkipCheckout -SpoofApks <N-039 apks>`.
   At least one spoofing app must be on the phone, or check 3 FAILS as not tested. For each app the owner switches it
   on (fake location next to an outlet), opens that outlet in the SR app, and answers whether the visit was refused
   with the fake-location message. That answer, or a suspension, decides; the mock app-op is logged as information.
2. When asked, check in (press and hold). Pass = every chosen app suspended for user 0 within 30 s.
3. After 17:00, still checked in: `... day3-check.ps1 -CheckoutOnly`, then check out. Pass = the apps were suspended
   before and none is suspended within 30 s after.
4. Record each spoofing app in `docs/status/qa-n039-antispoofing.md`. Remove the spoofing apps afterwards
   (`adb uninstall <package>`).

### D-QA-5 Release install and upgrade without Google Play (N-042; qa) — DEVICE-PENDING, laptop session
Script: `tools/checks/release-install-check.ps1`. It was tested in the cloud with a fake SDK and a fake adb: a release
pair passes; two signers, a different or debug signer, and a failed install each FAIL; odd build-tools folder names
are handled. You need two release-signed SR APKs from CI (an older and a newer run's `aron-sr-*.apk`) and the SDK
build-tools on the laptop.
1. On the enrolled Honor X5c Plus: `powershell -ExecutionPolicy Bypass -File .\tools\checks\release-install-check.ps1
   -OldApk <older>.apk -NewApk <newer>.apk -ExpectedCertSha256 <the CI release certificate SHA-256>`.
2. The field path (`-ViaInAppUpdate`) cannot pass yet: the backend does not serve the release publish routes
   (`docs/requests/qa-release-publish-route.md`).
3. Each run appends one line to `release-install-check.log`: phone, Android and patch, path, the result and the install
   output. It states that Google's developer-verification rule is not exercised by these installs (first rollout
   September 2026 in Brazil, Indonesia, Singapore and Thailand). Copy the lines into `docs/status/laptop.md`. N-042
   stays open until the Galaxy A06 and A07 runs are logged.
- D-TSO-A11Y-01 (DEVICE-PENDING): TalkBack on the TSO app: Leave apply, Feedback, Assign Task, Change Password fields announce their names; the drawer and Home button read in order; every control reachable by swipe.

- D-SIZE-01 DEVICE-PENDING (android-tso, 2026-10-18): install sr-release and tso-release (native libraries now deflated, `useLegacyPackaging`; R8 `-repackageclasses`, `-processkotlinnullchecks remove`); cold start, log in, open the encrypted DB (SQLCipher + argon2 load from the extracted libraries), sell offline, sync, print. Expect no difference; the installed size is about 1.3 MB larger than before.
