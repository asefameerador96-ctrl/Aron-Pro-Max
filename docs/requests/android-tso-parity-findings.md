# TSO parity audit: findings that are not android-tso's to fix (2026-10-18)

Source: the Opus parity audit of the TSO screens against docs/08, the manual and delta-tso (full table in docs/status/android-tso.md). Fixed by android-tso in the same pass: date picker on every date input (long form "April 26, 2026"), leave labels of the manual, My Call hub order and subtitles, drawer logout icon, "Log Out!" title, Final Submit "Success" dialog and "Routes" heading, English first run (D-181), Visit Query answers optional with text questions, Periphery subtitles.

## android-amo / backend (TSO dashboard block, F-TSO-002..007, request android-amo-tso-dashboard-backend.md item 3)
- **Blocker:** Not Logged In / Not Uploaded show route code and name only. The manual lists the SR or AMO name, "User Name:", "Route:" and "Dep Name:", and Not Uploaded is a superset (any route without a submit, logged in or not). Needs the user name, username and department on the read.
- Both lists are two untitled leaf screens titled "Dashboard"; the manual has one screen "Login & Bikroy Joma Status" with two tabs.
- Card order: manual is Sales, By Channel STD, CPR, Segment, Brand, Login; ours puts CPR before Channel. CPR title is "Live CPR" and the captions "Target Outlet / Successful Calls / Strike Rate" are replaced by "x of y"; Login and Bikroy Joma captions likewise ("Target Route / Total Login", "Login Count / Total Bikroy Joma"); title "Bikroy Joma Status".
- By Channel STD donut is computed from net money; F-TSO-003 says STD volume (`qtyBase`).
- Sales tiles: the manual caption "Total Sales".

## contract / backend
- Zone names (and the Wing/Division/Territory/House/Zone cascade): every picker shows "Zone <id>" (already in android-tso-record-mapping.md).
- The Visit Query rubric must be seeded with the two printed Bangla questions as `text` criteria (the app now asks both `text` and `bool` criteria, all optional); the Visit Plan card needs the outlet `channel` and the "Code / Owner / Contact / Channel / Cluster" labels (the Outlet read has channel; the card shows the fields without labels today).
- `cfg.tso.assignable_task_types` should limit the Assign Task list (today every `task_type` code is offered).

## android-sys (core-media)
- The manual's feedback image comes from the device gallery ("Browse Gallery"); ours uses the camera pipeline. A gallery-backed `CameraSource` (Android photo picker, no storage permission) is needed in core-media, then the Add photo button can offer both.

## feature-auth
- Login button reads "Log in"; the manual says "Login".
