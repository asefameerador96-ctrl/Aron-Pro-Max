package com.aktcl.aron.core.media

import android.content.Context
import android.graphics.Bitmap
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
}
