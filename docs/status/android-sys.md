# Status: lane android-sys (2026-10-07)

## Done
- **F-SYS-023** runtime-permission gate (module `:android:core-system`, package `com.aktcl.aron.core.system.permission`). Opus checker: 2 defects, fixed with tests (`CheckerF023Test`). 19 tests green locally (Robolectric included).
  - `PermissionPolicy.decide(GatedFeature, PermissionSnapshot)`: location blocks ATTENDANCE, SALE, OUTLET_REQUEST; camera blocks only PHOTO; Bluetooth blocks only PRINT. First denial = ask again; permanent denial = app Settings page; location switch off = location settings; coarse-only = "precise needed".
  - `PermissionGate(GatedFeature.X, onBack) { content }` (Compose): Bangla/English rationale, one action, re-reads on every resume. Uses the same "asked" prefs keys as the SR onboarding (`aron-permissions`: PRECISE_LOCATION, CAMERA, BLUETOOTH).
  - Print button: `PermissionPolicy.allowed(GatedFeature.PRINT, AndroidPermissions.snapshot(activity))`.
  - `ManifestPermissionAuditTest` parses the merged manifests of app-sr, app-amo, app-tso (the test task depends on their `processDebugMainManifest`) and every source manifest: no RECORD_AUDIO, no background location.
  - DEVICE-PENDING: D-S1 in `docs/status/device-checks.md`. **Wiring for android-sr-a:** wrap `SrScreen.ATTENDANCE` and `SrScreen.PICKER`/`VISIT` in `PermissionGate(GatedFeature.ATTENDANCE / SALE, onBack = { screen = SrScreen.HOME }) { ... }`, and the outlet request forms in `GatedFeature.OUTLET_REQUEST`.

## Interface for android-sr-a: camera and photos (F-SYS-030, stable 2026-10-07; module `:android:core-media`)
Package `com.aktcl.aron.core.media`. Add `implementation(project(":android:core-media"))` to app-sr (and feature-outlet only if it needs the types).
```kotlin
// App shell, once per signed-in user (SrDay):
val media = MediaComponents(context, userId, clock = sessionComponents.clock, businessDate = { businessDate() })
// Top of the composition, once (draws the camera only while a capture is open; behind the camera permission gate):
CameraCaptureOverlay(media.camera)
// Replace NoCameraPipeline in SrDay:
photoPipeline = object : PhotoPipeline {
    override suspend fun captureAndCompress(photoUuid: String) =
        media.capture(photoUuid)?.let { CapturedPhoto(photoUuid, it.thumbnailPath, it.item.bytes.toLong()) }
    override suspend fun discard(photoUuid: String) { media.discard(photoUuid) }
}
// BEFORE committing the record that references the photo (visit for Force Sale, outlet_change_request for requests):
media.attach(photoUuid, MediaRef(purpose = "force_sale" /* or "outlet_capture" */, refType = "visit" /* or "outlet_change_request" */, refClientUuid = recordUuid),
    PhotoStamp(lat = fix.lat, lng = fix.lng, accuracyM = fix.accuracyM, isMock = fix.isMock, fixClientUuid = storedFixUuid))
```
- `capture` returns null on cancel; on low storage (< 200 MB) or a failed save it also returns null and the overlay shows the reason (Bangla/English).
- The photo is compressed (<= 150 KB, long edge <= 1024 px, all EXIF removed, orientation applied), SHA-256 and dHash computed, and stored in `files/media/u<userId>/` before `capture` returns. Idempotent by uuid; kill-safe (temp file + fsync + rename).
- `attach` is idempotent for the same record and refuses moving a photo to another record; `discard` never drops an attached photo.
- The camera (CameraX, about 1280 x 720, flash auto) exists only while the camera screen is up and is unbound right after the shot. One retake stays your `GeoPhotoCapture` rule.
- Upload is F-SYS-010 (next): nothing to call; the media worker uploads attached photos after their record is acked.

- **F-SYS-030** done (on INT): Opus checker 2 defects fixed with tests; 28 core-media tests green locally (Robolectric native graphics for the real Bitmap codec, all 8 EXIF orientations). DEVICE-PENDING D-S2 (camera released, size on the phone, 1.5 s CPU).

## In progress
- F-SYS-010 media upload and F-SYS-037 Wi-Fi-only switch: built (55 core-media tests green), checker next.

## Requests filed
- `docs/requests/android-sys-media-meta.md` (android-core): `recordMediaMeta` and the `media_meta` record mapping.
- `docs/requests/android-sys-string-scan.md` (android-core-ui): add core-system and core-media to `HardcodedStringScanner`.

## Traps
- Robolectric and Maven resolve locally through the committed mirror; `tools/android-sdk.sh` must run once per container.
