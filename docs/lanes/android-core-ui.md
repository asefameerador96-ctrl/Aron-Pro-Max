# Lane brief: android-core-ui

Session model: **Sonnet** (docs/29 s3); every row of this lane gets a fresh Opus checker (foundation rows that three apps and two SR lanes depend on). Owns: `android/core-ui` (the shared Compose kit), the permission flow, the photo pipeline and media queue in `android/core-common` or the module the android-core status file names for them.

Read `docs/lanes/README.md` first, then `docs/status/android-core.md` (section "Interfaces for feature lanes" and the traps).

## Why this lane exists and what is urgent
The SR lanes (android-sr-a, android-sr-b) are waiting on the Compose UI kit. **N-023 is the first thing you build and you publish it in slices**: push the theme (Bangla typography with the bundled fonts, light and dark, large touch targets), Bengali-digit text, and the three or four most used components (list row, tile grid, stepper, primary button, banner) within the first hour, then continue with the rest. Each slice is a pushed commit with a usage example in `docs/status/android-core-ui.md` ("Kit for feature lanes"), so the SR lanes can start at once. Do not hold the kit until it is complete.

Then: F-SYS-023 runtime permission flow (location on demand, camera, Bluetooth, notifications; gated onboarding), F-SYS-030 photo capture and compression (long edge about 1024 px, 100 to 200 KB), F-SYS-010 media queue and upload (Wi-Fi preferred), F-SYS-019 language toggle, F-SYS-022 logout (keeps the upload grant), F-SYS-037, F-SYS-020, F-SYS-021.

## Rules for the kit
- Bangla first; every string a resource with a twin; no hard-coded text (the `HardcodedStringScanTest` fails the build).
- Smooth on the Galaxy A06 (2 GB class): no heavy recomposition, stable keys in lists, no blocking work on the main thread; add `@Preview` and a small screenshot or semantics test per component.
- Match the look of the screens in `docs/ui-reference/` (not pixel copy: the same information in a clean Material 3 style; the owner will review it on the phone).
- No continuous sensors, no wake locks; photos compressed before queueing.
- The core-session, core-database and core-sync modules belong to the android-core lane: use their interfaces, never edit them.
