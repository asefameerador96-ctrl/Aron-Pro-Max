# Prompt for the laptop session: record the current app's user journey from a real SR's phone

Paste everything below the line into the laptop session. Owner's plan of 2026-11-13.

---

You are the laptop operator session of the Aron rebuild. Task: record the USER JOURNEY of the app the field uses today, from a real SR's phone connected to this laptop over USB (ADB), so the lead can compare it with our SR app screen by screen. AKTCL owns that app; the SR is present and has agreed. Read `CLAUDE.md`, `docs/ui-reference/PARITY-SPEC.md` and `docs/ui-reference/PARITY-AUDIT-2026-11-13.md` first.

## Hard limits (stop and tell the owner if any cannot be kept)

1. **Look only.** You record what the screen shows. Allowed ADB commands: `adb devices`, `adb shell screencap`, `adb shell screenrecord`, `adb shell uiautomator dump`, `adb shell wm size`, `adb shell wm density`, `adb shell dumpsys window` (current screen name only), `adb shell input` (tap, swipe, back) and `adb pull` of the files YOU created under `/sdcard/aron-capture/`. Nothing else.
2. **Never** copy the app's package off the phone, decompile it, read its private data or databases, run `run-as`, `adb backup`, `bugreport` or `logcat`, intercept its network traffic, install a certificate or a proxy, or call its server from the laptop. (D-OWNER-REA-01: the journey is the measure, not the code.)
3. **This is the live system with real money and real stock. You never create, change or send a record.** You may open screens, scroll, open lists, open a form and look at it, then go BACK. You never press a button that saves, submits, confirms, prints, syncs, checks in, checks out, marks attendance, ends the day, deletes or logs out. If you are not sure what a button does, do not press it: screenshot it and list it under "not pressed".
4. Every step that writes something is done **by the SR's own hand, as part of real work they had to do anyway** (see part B). You only record.
5. **Personal data never leaves this laptop through Git.** The repository is public. Raw screenshots, recordings and UI dumps go to a folder OUTSIDE the repository (`<home>/aron-captures/apsis-journey-<date>/`) and are never committed. Before finishing, run `git status` and confirm no capture file is staged or untracked inside the repository.
6. Never type, read aloud, store or screenshot the SR's password or an OTP. If a login screen must be recorded, the SR logs out and in by hand while you capture only the EMPTY form and the error states; skip this if the SR prefers.
7. Install nothing on the SR's phone. Do not change its settings. When finished, tell the owner to switch USB debugging off again on that phone.

## Part A: read-only tour (you drive, SR watches)

1. `adb devices`: confirm exactly which serial is the SR's phone; if our test phone is also connected, use `-s <serial>` on every command. Record model, Android version, screen size and density.
2. From the home screen of the app, visit EVERY tile, menu item, tab, drawer entry, filter, dropdown and dialog you can reach without breaking limit 3. For each screen save, numbered in visit order: a screenshot (`NNN-<name>.png`) and a UI dump (`NNN-<name>.xml`).
3. For every list, scroll to the end and capture the top, a middle and the bottom. For every form, capture it empty, with the keyboard open on each field type, and every dropdown open.
4. Capture the states you can reach safely: empty lists, no network (ask the SR to switch on aeroplane mode for one minute, capture home, route, outlet list and the sale form, then switch it off), GPS off message, date pickers, validation messages shown WITHOUT saving (leave a required field empty and move focus, never press save).

## Part B: one real selling day, recorded while the SR works (SR drives)

The desk cannot show a real sale (the geofence needs the outlet). So:

1. Ask the SR to start the phone's own screen recorder (or record with `adb shell screenrecord` in 3-minute pieces while the cable is connected) and do their NORMAL work: morning stock or load, attendance, opening the route, visiting an outlet, a sale with at least two products, the memo print, a due collection if one is owed, a no-sale visit with a reason, a return or damage if one is real today, day end and the summary.
2. Nothing is done for our benefit: no test sale, no fake outlet, no extra visit. If something does not happen today (no return, no due), write "not seen" and leave it.
3. Afterwards pull the recording files to the captures folder and cut stills from each step.
4. Ask the SR to keep one printed memo (or photograph it): the memo layout matters down to the line.

## Part C: what you write (this is the only thing that goes to Git)

File `docs/ui-reference/apsis-journey/sr-journey-<date>.md` on branch `lane/laptop-apsis-journey` (create from the integration branch). Text only, personal data replaced: every person's name by `<NAME>`, every phone number by `<PHONE>`, every outlet name by `<OUTLET n>`, every address by `<ADDRESS>`, codes of people by `<CODE>`; product names, brand names, prices, labels and messages stay exactly as shown.

1. **Screen map:** every screen with its number, title, how it is reached (which tap from which screen) and where BACK leads.
2. **Per screen:** every label, button, tab, column header, hint, unit and message **verbatim, in the script shown (Bangla stays Bangla, with an English gloss)**; order of elements top to bottom; which fields are required; field types and keyboards; default values; what is disabled and when; colours and rough sizes only where they carry meaning (status colours, totals panel).
3. **Journeys:** the exact step order of each flow in part B, with tap count, what the app asks, what it shows after each step, every confirmation dialog word for word, and what the SR can and cannot change afterwards.
4. **Numbers and units:** how quantities are entered and shown (packets, sticks, dozens, boxes), how totals are rounded, what the totals panel adds up, the memo line by line.
5. **Behaviour:** what happens offline, when GPS is off or far from the outlet (the exact message and distance shown), when stock is short, on a duplicate tap, after the app is reopened mid-sale.
6. **Compared with ours:** open our SR app on the test phone (APK `aron-release-signed-dev-2116`) beside it and list every difference in three groups: step order or missing step; wording or unit; look. Reference the audit finding number where one exists, mark the new ones NEW.
7. **Not pressed / not seen:** every button you did not press and every flow that did not occur, so the lead knows what is still unknown.

Commit only that file (and nothing from the captures folder), push ONCE to `lane/laptop-apsis-journey`, and tell the owner the commit and the captures folder path. If a second SR, an AMO or a TSO phone is offered later, repeat with a new file name (`amo-journey-...`, `tso-journey-...`).
