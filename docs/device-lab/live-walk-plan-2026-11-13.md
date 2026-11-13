# Live device walk over ADB: plan (owner's proposal of 2026-11-13)

Who runs it: a Claude session on the owner's laptop (the phone is connected by USB with debugging allowed), driven by the lead.
The lead's cloud session cannot reach the laptop. Target: dev (test account), seeded pilot users only. No real master data.
Nothing here needs a secret typed into chat: the dev logins come from the seed the laptop session can read in the repository
documentation; an OTP or password is never printed into a report or a commit.

## 0. Before the walk
1. `adb devices` shows exactly one device in state `device`; record model, Android version, RAM, free storage, battery level
   (`adb shell getprop ro.product.model`, `ro.build.version.release`, `dumpsys battery`, `df /data`).
2. Download the signed APK artefact of record from the latest green INT run (GitHub Actions artefact `aron-release-signed-dev-<n>`;
   the lead names the run) into a folder outside the repository; verify the three APKs' SHA-256 against the artefact's checksum file.
3. Install SR, AMO and TSO (`adb install -r`); record versionName and versionCode (`dumpsys package`), APK size on disk.
4. Start a log capture per app (`adb logcat -c`, then `adb logcat -v time` filtered to the app's pid, into a file per step).
   Logs are scanned for crashes and ERROR lines; lines that look like a token, password or OTP are redacted before anything is kept.

## 1. Method for every screen
For each step: perform the action (`adb shell input tap/text/swipe`, or UI Automator by text/resource id from
`adb shell uiautomator dump`), wait for idle, then save (a) a screenshot (`adb exec-out screencap -p`), (b) the UI dump, (c) the
log slice. Name files `<app>/<nn>-<screen>-<state>.png`. Keep everything in a folder outside the repository; only the report and
down-scaled screenshots without personal data go to the lead's docs branch.
Each screen is checked three ways: it works (the action has its effect), it matches the owner's reference screenshot of the same
screen (docs/ui-reference/<app>/, manual corrections win where they exist), and it shows nothing broken (cut text, overlapping
elements, English where Bangla is expected, wrong units: packets of 20s and 10s, dozens, box of 25).

## 2. SR app: the journey of a selling day
Login and device code; language; home tiles and KPIs; attendance / check-in with location; today's route and outlet list; search;
outlet menu; call start (and the call photo once built); stock; sale: pick SKUs with pack pictures, quantities in field units,
totals panel; memo preview; print (see section 5); credit / due collection; zero sale with reason; edit a memo with a reason;
new outlet request and outlet information change; tasks; Astha and programme screens (as far as built); sales summary;
Sales Submit; data update / sync screen; inbox; settings; logout and login again.

## 3. AMO app and TSO app
AMO: login; home; route / zone view; outlet verification of an SR's request (verify and discard); surveys; visit journey; reports;
sync. TSO: login; home and KPIs; team list and live status; device codes for the team (once built on the app); route-day view and
Final Submit; reopen; outlet request approval; call-photo review; my call (TSO's own visit); reports; sync.

## 4. The hard cases (each is a pass/fail row)
- OFFLINE DAY: airplane mode on (`adb shell cmd connectivity airplane-mode enable`); do a full sale, a due collection and a zero
  sale; everything works and prints; airplane mode off; sync; the server count equals the device count; a second sync changes nothing.
- KILL AND RELAUNCH: `adb shell am force-stop` in the middle of a sale, of a memo save and of a sync; reopen; nothing lost, nothing doubled.
- DUPLICATE UPLOAD: sync twice quickly; turn the network off mid-upload and on again; one memo on the server.
- LOCATION: location off; mock location on with a fake-GPS app (the flag must be recorded and surfaced; this is a rep's cheat,
  tested on our own app only); an outlet far away (geofence refusal or force-sale path as the manual says); an outlet with no coordinates.
- TIME: device date changed by a day and by an hour (business date and the clock guard).
- PERMISSIONS: location, camera, Bluetooth, notifications denied then granted.
- ROTATION, font size largest, display size largest, dark mode off/on, Sunlight mode.
- LOW RESOURCES: `adb shell am send-trim-memory`, battery saver on, data saver on.

## 5. Printer (needs the owner's hands once)
Pair the 58 mm printer; print a cash memo, a credit memo and a long memo; Bangla glyphs, alignment, totals, the cut; printer off in
the middle; reprint. The diagnostics screen (MP-58N). Photograph each slip beside the old app's slip.

## 6. Budgets measured on the phone (docs/04)
Cold start time (`am start -W`), time to first route list, memory after a 50-outlet day (`dumpsys meminfo`), battery and data used
over a scripted one-hour session (`dumpsys batterystats --reset` first; `dumpsys netstats`), APK and data size.

## 7. Report
One table per app: step, pass / fail / blocked, screenshot, mismatch against the reference, log errors. Then the list of defects
ordered by what a field user would notice first, and what could NOT be tested and why. Stated plainly: one phone, one Android
version, test data, test account.
