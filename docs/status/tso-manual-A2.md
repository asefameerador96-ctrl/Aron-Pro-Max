# tso-manual A2 android-tickets: lane status

Lane A2 of `docs/requests/tso-manual-build-2026-10.md` (s5 A2). Branch `tso-man/A2`, no pull request.
Written 2026-10-29. Base: 95a35b7d (contract 1.10.0, V0130-V0140, F3 rules).

## Done

### Step 1: gallery import (core-media), A2's first commit, used by A3 (feedback gallery)

Branch pointer for A3: `tso-man/A2-gallery`.

| file | what |
|---|---|
| `android/core-media/src/main/kotlin/com/aktcl/aron/core/media/GalleryImport.kt` (new) | `GalleryImporter<I>`, `GalleryImportException` (+ `Reason`, `messageRes`), `rememberGalleryPicker(maxItems, onUnavailable, onPicked)`, `galleryImagesRequest()`, `pickedWithin` |
| `MediaComponents.kt` (append, `// tso-manual A2`) | `val gallery: GalleryImporter<Bitmap>`, `suspend fun importFromGallery(mediaUuid: String, uri: Uri, ref: MediaRef): MediaItem` (one import line `android.net.Uri` marked too) |
| `res/values/strings_a2.xml`, `res/values-bn/strings_a2.xml` (new, core-media) | `media_gallery_failed`, `media_gallery_too_large`, `media_gallery_unavailable` (en and bn) |
| `src/test/.../GalleryImportTest.kt` (new) | see Verify |

How a caller uses it (A2 ticket sheet and create screen, A3 feedback):

```kotlin
val pick = rememberGalleryPicker(maxItems = cfgMax - photos.size, onUnavailable = { show(R.string.media_gallery_unavailable) }) { uris ->
    scope.launch {
        uris.forEach { uri ->
            val id = newUuidV4()                       // keep it in the form's saved state with the record UUID
            try { photos += media.importFromGallery(id, uri, MediaRef("support", "support_ticket", ticketUuid)) }
            catch (e: GalleryImportException) { show(e.messageRes) }
        }
    }
}
```

Behaviour:
- The picture goes through `PhotoCapturer` itself (the picked file is its `CameraSource`): same compressor and
  `cfg.media.*` budget (150 KB, 1024 px), upright, every APPn/COM segment dropped (no GPS, maker, original date),
  SHA-256 and dHash, thumbnail, the same 200 MB low-storage rule, the same media store; then `MediaComponents.attach`
  (no shutter fix) claims it and calls `MediaWorkScheduler.photoQueued`, so upload, SAS, PUT and `media_meta` are the
  camera's path unchanged.
- Idempotent by media UUID: a repeat (or a relaunch with the read grant gone) returns the stored item without reading
  the URI; a claimed picture cannot move to another record (IllegalStateException, as for the camera).
- The read stops at 32 MiB (`MAX_SOURCE_BYTES`), so a huge file never fills a 2 GB phone's memory.
- Picker: `PickVisualMedia` / `PickMultipleVisualMedia`, images only. No storage permission anywhere.

## Decisions

- **D-A2-01** Gallery failures are a typed exception (`GalleryImportException.reason`, `messageRes`), not a camera
  notice, because the camera's "Take it again." text is wrong for a picked picture and `CameraCapture.kt` is not A2's
  file. No space reuses the existing `media_no_space` text. The three new texts live in new core-media files
  `strings_a2.xml` (en, bn), following the s5 "each lane has values/strings_<lane>.xml" rule; no existing key is touched.
- **D-A2-02** Gallery pictures are never evidence: `force_sale`, `outlet_capture`, `outlet_verification` are refused
  before anything is read (IllegalArgumentException), because MediaItem documents evidence as camera-only (docs/24 s5.5)
  and a gallery picture would defeat the anti-spoofing photo rule.
- **D-A2-03** `taken_at` of a gallery picture is the import time on the trusted clock. The picture's EXIF date is
  untrusted and is stripped with the rest; `MediaMetaPayload` has no source field, so nothing else changes on the wire.
- **D-A2-04** The picker enforces `maxItems` itself (`pickedWithin`: distinct, first N), because the documents-picker
  fallback on old phones ignores the photo picker's limit. `maxItems = 0` opens nothing; a phone without any picker
  calls `onUnavailable` (ActivityNotFoundException).
- **D-A2-05** The "no storage permission in the merged manifest" check is on the manifest Robolectric runs core-media's
  tests with, which AGP merges with every dependency's manifest (androidx.activity's picker included; the test proves
  the merge by resolving the test-only `ComponentActivity`). The app-level merged manifests are audited by core-system's
  `ManifestPermissionAuditTest` (not A2's file; see Asks).

## Deviations

None from s5 A2 for this step.

## Asks

- **lead / core-system owner (optional):** add the storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`,
  `READ_MEDIA_VISUAL_USER_SELECTED`, `MANAGE_EXTERNAL_STORAGE`, ...) to the forbidden set of
  `ManifestPermissionAuditTest.mergedAppManifestsRequestNoMicrophoneNorBackgroundLocation`, so the three app manifests
  are guarded too.
- **device lab (D-UI check, pilot phone on dev):** the photo picker opens on a DPC-managed pilot phone. If an app is
  ever pinned in lock-task mode, the picker's package (MediaProvider photo picker, or DocumentsUI on old phones) must be
  allowed, or the Browse Gallery button does nothing. Not testable on the laptop.

## Not done (later A2 steps)

feature-tickets module and `settings.gradle.kts` line, TicketsHost.kt, TicketApi.kt, the five ticket screens, states,
TicketModel, strings, TicketWriteReplayTest, TicketScreenshotTests (goldens for screenshots 10-18), docs/08 "Tickets"
section, the F1/F3 asks naming A2 (`SupportTicketSummary` without pct members: percent via `TicketMath` /
`TicketCounts.resolvedPctText` / `openPctText`; `TicketMath.counts(cached, unsentUuids)` offline).

## Verify

Step 1, on this laptop (JDK 21, UTC, via gl.sh), 2026-10-29:

| command | result |
|---|---|
| `gl.sh :android:core-media:testDebugUnitTest --tests 'com.aktcl.aron.core.media.GalleryImportTest'` | BUILD SUCCESSFUL; GalleryImportTest 13 tests, 0 failures |
| `gl.sh :android:core-media:testDebugUnitTest :android:core-ui:testDebugUnitTest --tests 'com.aktcl.aron.core.ui.HardcodedStringScanTest' --continue` | BUILD SUCCESSFUL; core-media 14 classes, 94 tests, 0 failures (GalleryImportTest 13); HardcodedStringScanTest 5, 0 failures (the new core-media strings have their Bangla) |

GalleryImportTest covers: compressed to the cap (150 KB, 1024 px) from a 2400 x 1600 camera JPEG; the configured cap
(40 KB, 640 px); every EXIF field gone (GPS, make, model, original date; no APP1..APP15 segment); orientation applied
first; PNG screenshot becomes a JPEG; same media UUID twice gives one item and one read (also after a relaunch with the
read grant gone, and two imports at once); the claim cannot move to another record; evidence purposes refused before any
read; unreadable, refused read grant, too large (the cap is inclusive), no space (nothing read): nothing stored; en and
bn messages; queued like a camera photo (the `aron-media` job enqueued, then MediaUploader SAS, PUT and `media_meta`
with no fix); no storage permission in the merged manifest (CAMERA present, so the parse is real); the picker asks for
`image/*` only, keeps the limit (3 picked, 2 kept), cancel adds nothing, limit 0 opens nothing.

Not run here: no mutation run of the manifest check (adding a storage permission to see it fail); the photo picker on a
real phone (device lab).

Golden names needed: none for this step (no screen).

## Next step

Step 2: create `android/feature-tickets` (role-agnostic; ports `TicketSource`, `TicketQueue`, `TicketPhotos`, `now`),
add `include(":android:feature-tickets")` after the feature-tso line in `settings.gradle.kts`, then TicketModel and
states with their tests, then the screens against screenshots 10-18. Merge only after A1's `a1-stubs` commit (s5).
