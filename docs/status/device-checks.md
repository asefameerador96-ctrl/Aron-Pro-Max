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
| D-P2 | DEVICE-PENDING (android-print, N-019): pair the MP-58N in Android Bluetooth settings; in the SR app pick it from the printer icon; print the 40-line test memo. Pass = whole memo, no cut-off, no garbage, no blank gap at the top. Then start the print again and switch the printer off half way: the app shows the failed state; switch it on, tap retry: the whole memo prints once from the top (a few white rows before it are expected). Also: open the cover / remove the roll and print: "paper out" shows; put the roll back and retry | A06 + MP-58N + roll | waits for the SR print screen (android-sr) |
| D-S1 | DEVICE-PENDING (android-sys, F-SYS-023): on a NON-enrolled test phone with the SR debug APK: (1) Settings > Apps > Aron SR > Permissions > Location: Don't allow; open Attendance: the Bangla screen "হাজিরার জন্য লোকেশন দরকার" shows, tap "অনুমতি দিন" or "সেটিংস খুলুন": the app's own Settings page opens; allow location "only while using", precise on; press Back: Attendance opens by itself. Same for Sale. (2) Set Location to Approximate only: the screen says precise location is needed. (3) Nearby devices (Bluetooth): Don't allow; open Sale: it opens normally; only the print button is disabled. (4) `adb shell dumpsys package com.aktcl.aron.sr \| grep -i RECORD_AUDIO` prints nothing | test phone (not device owner: prod pins grants) | waits for android-sr-a to wrap Attendance and Sale in `PermissionGate` |
| D-S2 | DEVICE-PENDING (android-sys, F-SYS-030), once android-sr-a has wired the camera: in the SR app open Force Sale (or New shop) and press the shutter: the camera screen opens, take a photo; the thumbnail shows. Then `adb shell run-as com.aktcl.aron.sr ls -l files/media/u*/` : each `.jpg` is at most 153600 bytes; `adb exec-out run-as com.aktcl.aron.sr cat files/media/u<id>/<uuid>.jpg > p.jpg` and open it on the laptop: upright, 1024 px long edge, no EXIF/GPS (`exiftool p.jpg` shows no GPS and no Orientation). Take a retake once: the second shutter is refused after that. After the shot the camera indicator (green dot, top right) goes off within a second. Time from shutter to thumbnail under 2 s | A06 | waits for android-sr-a wiring |
| D-S3 | DEVICE-PENDING (android-sys, F-SYS-019): on the Galaxy A06 with the seeded day, Settings > language: tap English: every screen switches at once, no dialog; numbers show 0-9; swipe the app away (kill) and reopen: still English; switch to বাংলা: Bengali digits, Bangla labels; kill and reopen: still Bangla. Open a half-filled stock or sale screen before switching: what was typed is still there after the switch | A06 + seeded day | ready once the SR build is installed |

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
