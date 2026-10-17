# Status: lane android-tso (wave 2, started 2026-10-17)

## Done
- F-TSO-001 checked (Opus), defects fixed. DEVICE-PENDING: Galaxy A06 check in Bangla and English (not yet in device-checks.md).

## Was in progress
- F-TSO-001 (T1): dark navy login as the kit Dark mode (`TsoLoginTheme` in feature-tso, wired in `app-tso` MainActivity; status/nav bar icons follow; sunlight overrides dark). Logout dialog and reconciled-only wipe pre-exist (`ShellLogout`, `UnsentItemsDialog`, Settings confirm). Tests green locally. Opus checker running; row goes to the CSV when it reports.

## Next three rows
F-TSO-008 Leave list, F-TSO-018 My Feedback, F-TSO-028 chrome.

## Traps
- Local Gradle works online (not `--offline`): `echo sdk.dir=/opt/android-sdk > local.properties` after `tools/android-sdk.sh`; filter the `Picked up JAVA_TOOL_OPTIONS` noise.
- Decision: only the login is dark; the signed-in day stays Calm Glass light (revisit in F-TSO-028).
