package com.aktcl.aron.core.media

import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** The `cfg.media.*` keys this module reads (docs/24 s9.5). Defaults are the registry defaults. */
data class MediaConfig(
    val longEdgePx: Int = 1024,
    val jpegQuality: Int = 70,
    val photoMaxKb: Int = 150,
    val wifiOnlyDefault: Boolean = true,
    /** 0 = evidence photos never fall back to mobile data. */
    val evidenceMobileFallbackH: Int = 6,
    val localKeepDays: Int = 2,
    val pendingPhotoGraceDays: Int = 7,
) {
    val budget: PhotoBudget get() = PhotoBudget(longEdgePx = longEdgePx, jpegQuality = jpegQuality, maxBytes = photoMaxKb * 1024)
}

/** One still from the in-app camera. The camera is opened for this call only and released before it returns. */
fun interface CameraSource {
    /** The camera's JPEG, or null when the rep backed out. */
    suspend fun takePicture(): ByteArray?
}

sealed interface CaptureOutcome {
    data class Captured(val item: MediaItem, val thumbnailPath: String) : CaptureOutcome
    data object Cancelled : CaptureOutcome
    /** Less than [PhotoCapturer.MIN_FREE_BYTES] free: no photo (docs/17 s8.6 D-404); force sale stays allowed. */
    data object NoSpace : CaptureOutcome
    /** The camera is already open for another capture (a double tap): nothing happened, nothing to show. */
    data object Busy : CaptureOutcome
    data class Failed(val cause: Throwable) : CaptureOutcome
}

/**
 * F-SYS-030: take one photo, compress it (at most 150 KB, 1024 px long edge, no EXIF), fingerprint it (SHA-256, dHash) and
 * store it in the media queue before anything else sees it; no uncompressed photo is ever queued (the camera's own file
 * lives only in memory). [attach] stamps it with the shutter fix and its owning record, which must be done before that
 * record is committed. A retake is a new capture followed by [discard] of the replaced one.
 */
class PhotoCapturer<I : Any>(
    private val camera: CameraSource,
    private val codec: ImageCodec<I>,
    private val store: MediaStore,
    private val clock: WallClock,
    private val businessDate: () -> String,
    private val freeBytes: () -> Long = { store.dir.usableSpace },
    private val config: () -> MediaConfig = { MediaConfig() },
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun capture(mediaUuid: String): CaptureOutcome {
        store.get(mediaUuid)?.let { return CaptureOutcome.Captured(it, store.thumbnailFile(mediaUuid).path) }
        if (freeBytes() < MIN_FREE_BYTES) return CaptureOutcome.NoSpace
        return try {
            val raw = camera.takePicture() ?: return CaptureOutcome.Cancelled
            val takenMs = clock.nowMs()
            val date = businessDate()
            val (photo, thumb) = withContext(cpu) {
                val p = PhotoCompressor(codec, config().budget).compress(raw)
                p to thumbnail(p.jpeg)
            }
            val item = MediaItem(
                mediaUuid = mediaUuid, businessDate = date, takenAt = Instant.ofEpochMilli(takenMs).toString(), createdAtMs = takenMs,
                sha256 = photo.sha256, phash = photo.phash, bytes = photo.bytes, width = photo.width, height = photo.height,
            )
            val stored = store.put(item, photo.jpeg, thumb)
            CaptureOutcome.Captured(stored, store.thumbnailFile(mediaUuid).path)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: CameraBusyException) {
            CaptureOutcome.Busy
        } catch (t: Throwable) {
            CaptureOutcome.Failed(t)
        }
    }

    /**
     * Claims a captured photo for its record and stamps it with the shutter fix. Call it before committing the record.
     * Repeating the same claim is a no-op; a photo cannot move to another record.
     */
    suspend fun attach(mediaUuid: String, ref: MediaRef, stamp: PhotoStamp?): MediaItem {
        require(ref.purpose in MEDIA_PURPOSES) { "unknown media purpose ${ref.purpose}" }
        val now = clock.nowMs()
        return store.update(mediaUuid) { cur ->
            when {
                cur.ref == null -> cur.copy(state = MediaState.ATTACHED, ref = ref, stamp = stamp, attachedAtMs = now)
                cur.ref == ref -> cur
                else -> throw IllegalStateException("photo $mediaUuid already belongs to ${cur.ref.refClientUuid}")
            }
        } ?: throw IllegalArgumentException("no stored photo $mediaUuid")
    }

    /** Drops a photo that was replaced by a retake or abandoned with its form. A claimed photo is never dropped. */
    suspend fun discard(mediaUuid: String): Boolean {
        return store.deleteIf(mediaUuid) { it.state == MediaState.CAPTURED }
    }

    private fun thumbnail(jpeg: ByteArray): ByteArray {
        val img = codec.decodeUpright(jpeg, THUMB_EDGE)
        try {
            val w = codec.width(img); val h = codec.height(img); val long = maxOf(w, h)
            val t = if (long <= THUMB_EDGE) img else codec.scaled(img, maxOf(1, w * THUMB_EDGE / long), maxOf(1, h * THUMB_EDGE / long))
            try { return JpegMetadata.strip(codec.encodeJpeg(t, 60)) } finally { if (t !== img) codec.release(t) }
        } finally {
            codec.release(img)
        }
    }

    companion object {
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        const val THUMB_EDGE = 256
        val MEDIA_PURPOSES = setOf("force_sale", "outlet_capture", "outlet_verification", "survey", "feedback", "support", "gift_photo")
    }
}
