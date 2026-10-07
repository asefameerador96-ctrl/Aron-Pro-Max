# Status: lane android-sys (2026-10-07)

## Done
- **F-SYS-023** runtime-permission gate (module `:android:core-system`, package `com.aktcl.aron.core.system.permission`). Opus checker: 2 defects, fixed with tests (`CheckerF023Test`). 19 tests green locally (Robolectric included).
  - `PermissionPolicy.decide(GatedFeature, PermissionSnapshot)`: location blocks ATTENDANCE, SALE, OUTLET_REQUEST; camera blocks only PHOTO; Bluetooth blocks only PRINT. First denial = ask again; permanent denial = app Settings page; location switch off = location settings; coarse-only = "precise needed".
  - `PermissionGate(GatedFeature.X, onBack) { content }` (Compose): Bangla/English rationale, one action, re-reads on every resume. Uses the same "asked" prefs keys as the SR onboarding (`aron-permissions`: PRECISE_LOCATION, CAMERA, BLUETOOTH).
  - Print button: `PermissionPolicy.allowed(GatedFeature.PRINT, AndroidPermissions.snapshot(activity))`.
  - `ManifestPermissionAuditTest` parses the merged manifests of app-sr, app-amo, app-tso (the test task depends on their `processDebugMainManifest`) and every source manifest: no RECORD_AUDIO, no background location.
  - DEVICE-PENDING: D-S1 in `docs/status/device-checks.md`. **Wiring for android-sr-a:** wrap `SrScreen.ATTENDANCE` and `SrScreen.PICKER`/`VISIT` in `PermissionGate(GatedFeature.ATTENDANCE / SALE, onBack = { screen = SrScreen.HOME }) { ... }`, and the outlet request forms in `GatedFeature.OUTLET_REQUEST`.

## In progress
- F-SYS-030 photo capture and compression (`:android:core-media`).

## Requests filed
- `docs/requests/android-sys-string-scan.md` (android-core-ui): add core-system and core-media to `HardcodedStringScanner`.

## Traps
- Robolectric and Maven resolve locally through the committed mirror; `tools/android-sdk.sh` must run once per container.
