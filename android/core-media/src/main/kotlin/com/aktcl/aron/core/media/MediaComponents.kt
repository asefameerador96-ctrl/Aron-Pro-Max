package com.aktcl.aron.core.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri // tso-manual A2
import com.aktcl.aron.core.common.WallClock

/**
 * What an app shell creates once per signed-in user (F-SYS-030). Draw `CameraCaptureOverlay(camera)` once at the top of
 * the composition; feature code calls [capture], [attach] and [discard] only.
 */
class MediaComponents(
    context: Context,
    userId: Long,
    clock: WallClock,
    businessDate: () -> String,
    config: () -> MediaConfig = { MediaConfig() },
    val camera: CameraCaptureHost = CameraCaptureHost(),
    /** The media job scheduler (F-SYS-010); null in previews and tests. */
    private val scheduler: MediaWorkScheduler? = null,
) {
    val store: MediaStore = MediaStore.forUser(context.applicationContext.filesDir, userId)
    val capturer: PhotoCapturer<Bitmap> = PhotoCapturer(camera, AndroidImageCodec, store, clock, businessDate, config = config)

    /** One photo, compressed and queued; null when the rep cancelled or no photo could be taken (the reason is shown). */
    suspend fun capture(mediaUuid: String): CaptureOutcome.Captured? = when (val o = capturer.capture(mediaUuid)) {
        is CaptureOutcome.Captured -> o
        CaptureOutcome.Cancelled, CaptureOutcome.Busy -> null
        CaptureOutcome.NoSpace -> null.also { camera.show(CameraCaptureHost.Notice.NO_SPACE) }
        is CaptureOutcome.Failed -> null.also { camera.show(CameraCaptureHost.Notice.FAILED) }
    }

    /** Claim the photo for its record (with the shutter fix) BEFORE committing that record. */
    suspend fun attach(mediaUuid: String, ref: MediaRef, stamp: PhotoStamp?): MediaItem =
        capturer.attach(mediaUuid, ref, stamp).also { scheduler?.photoQueued(it) }

    /** At app start: re-arm the upload and every evidence fallback (WorkManager keeps them, this only fills gaps). */
    suspend fun resume() {
        store.all().filter { it.state == MediaState.ATTACHED || it.state == MediaState.UPLOADED }.forEach { scheduler?.photoQueued(it) }
    }

    suspend fun discard(mediaUuid: String) = capturer.discard(mediaUuid)

    // tso-manual A2: gallery pictures (D-TSO-MAN-23 ticket photos, D-TSO-MAN-36 feedback; GalleryImport.kt)
    val gallery: GalleryImporter<Bitmap> = GalleryImporter(AndroidImageCodec, store, clock, businessDate, config = config)
    private val resolver = context.applicationContext.contentResolver

    /**
     * A picture picked with [rememberGalleryPicker]: read once, compressed to the camera's budget with every EXIF segment
     * dropped, stored, claimed for [ref] and queued exactly like a camera photo ([attach], with no shutter fix). Idempotent by
     * [mediaUuid]: a repeat returns the stored item without reading [uri] again. Call it right after the pick (the read
     * grant does not outlive the screen). Throws [GalleryImportException] (show its `messageRes`) when the picture cannot be
     * added; an evidence purpose is refused (camera-only).
     *
     * [claim] false only STAGES the picture (stored compressed, not claimed, nothing queued), like a camera [capture]: a form
     * whose record is saved later claims it then with [attach], and [discard] drops a staged picture the user removed or
     * abandoned (tickets, D-TSO-MAN-23: a removed picture must never be sent).
     */
    suspend fun importFromGallery(mediaUuid: String, uri: Uri, ref: MediaRef, claim: Boolean = true): MediaItem {
        val staged = gallery.import(mediaUuid, ref) { resolver.openInputStream(uri) }
        return if (claim) attach(mediaUuid, ref, stamp = null) else staged
    }
}
