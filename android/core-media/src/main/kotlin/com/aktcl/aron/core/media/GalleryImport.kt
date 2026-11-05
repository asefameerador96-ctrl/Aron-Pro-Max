package com.aktcl.aron.core.media

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

// tso-manual A2: pictures from the phone's gallery (D-TSO-MAN-23 ticket photos, D-TSO-MAN-36 feedback "Browse Gallery").

/** Why a picked picture could not be added. Show [messageRes]; the form stays as it was and the rep can pick again. */
class GalleryImportException(val reason: Reason, cause: Throwable? = null) : Exception("gallery import failed: $reason", cause) {
    enum class Reason {
        /** Less than [PhotoCapturer.MIN_FREE_BYTES] free, the camera's own rule. Nothing was read. */
        NO_SPACE,
        /** The picked file is over [GalleryImporter.MAX_SOURCE_BYTES]; it is never read into memory in full. */
        TOO_LARGE,
        /** The file could not be opened (the read grant ended) or is not a picture this phone decodes (HEIC before Android 9). */
        UNREADABLE,
    }

    @get:StringRes
    val messageRes: Int
        get() = when (reason) {
            Reason.NO_SPACE -> R.string.media_no_space
            Reason.TOO_LARGE -> R.string.media_gallery_too_large
            Reason.UNREADABLE -> R.string.media_gallery_failed
        }
}

/**
 * A picture picked in the system photo picker goes through the camera's own pipeline: [PhotoCapturer] with the picked file
 * as its source. So it is compressed to the same `cfg.media.*` budget (150 KB, 1024 px long edge), turned upright, every
 * EXIF/XMP/ICC segment is dropped (no location, no device, no original date), it is fingerprinted (SHA-256, dHash), gets a
 * thumbnail and is stored in the media queue under the same low-storage rule. `taken_at` is the import time on the trusted
 * clock, because the picture's own date is neither trusted nor kept. Idempotent by media UUID: a second import of a stored
 * UUID reads nothing and returns the stored item. The caller then claims it like a camera photo
 * ([MediaComponents.importFromGallery] does both).
 *
 * Gallery pictures are never evidence: force sale and outlet photos stay camera-only (docs/24 s5.5).
 */
class GalleryImporter<I : Any>(
    private val codec: ImageCodec<I>,
    private val store: MediaStore,
    private val clock: WallClock,
    private val businessDate: () -> String,
    private val freeBytes: () -> Long = { store.dir.usableSpace },
    private val config: () -> MediaConfig = { MediaConfig() },
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxSourceBytes: Int = MAX_SOURCE_BYTES,
) {
    /**
     * Reads the picture [open] gives (once, capped at [maxSourceBytes]) and stores it compressed under [mediaUuid].
     * Throws [GalleryImportException] when it cannot be added, and IllegalArgumentException for an unknown or an evidence
     * purpose (a programming error, checked before anything is read).
     */
    suspend fun import(mediaUuid: String, ref: MediaRef, open: () -> InputStream?): MediaItem {
        require(ref.purpose in PhotoCapturer.MEDIA_PURPOSES) { "unknown media purpose ${ref.purpose}" }
        require(ref.purpose !in MediaItem.EVIDENCE_PURPOSES) { "${ref.purpose} photos are camera-only" }
        val picked = CameraSource { withContext(io) { readCapped(open) } }
        return when (val o = PhotoCapturer(picked, codec, store, clock, businessDate, freeBytes, config, cpu).capture(mediaUuid)) {
            is CaptureOutcome.Captured -> o.item
            CaptureOutcome.NoSpace -> throw GalleryImportException(GalleryImportException.Reason.NO_SPACE)
            is CaptureOutcome.Failed -> throw (o.cause as? GalleryImportException ?: GalleryImportException(GalleryImportException.Reason.UNREADABLE, o.cause))
            // The picked source never cancels and is never busy; kept total so a future outcome cannot pass silently.
            CaptureOutcome.Cancelled, CaptureOutcome.Busy -> throw GalleryImportException(GalleryImportException.Reason.UNREADABLE)
        }
    }

    private fun readCapped(open: () -> InputStream?): ByteArray {
        val input = open() ?: throw GalleryImportException(GalleryImportException.Reason.UNREADABLE)
        return input.use { s ->
            val out = ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > maxSourceBytes) throw GalleryImportException(GalleryImportException.Reason.TOO_LARGE)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    companion object {
        /** Larger than any 50 MP phone JPEG; the read stops here, so a huge file cannot exhaust a 2 GB phone's memory. */
        const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    }
}

/** Still images only: no video, so nothing large to read and nothing to play. */
fun galleryImagesRequest(): PickVisualMediaRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

/** At most [maxItems] distinct pictures, in the order picked; the documents fallback of old phones does not enforce the limit. */
internal fun pickedWithin(uris: List<Uri>, maxItems: Int): List<Uri> = uris.distinct().take(maxOf(0, maxItems))

/**
 * The system photo picker (`PickVisualMedia`, images only). Android 11 and later (and older phones with the Play services
 * backport) show the photo picker; other phones fall back to the documents picker. Neither needs a storage permission: the
 * app only gets a read grant for the picked items, so import them at once ([MediaComponents.importFromGallery]) and keep
 * nothing but the media UUIDs. [maxItems] is read at launch: 0 does nothing, 1 picks one picture, more picks several (at
 * most [maxItems]). [onPicked] is not called when the rep backs out. [onUnavailable] runs when the phone has no picker.
 * Returns the launch action.
 */
@Composable
fun rememberGalleryPicker(maxItems: Int, onUnavailable: () -> Unit = {}, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val limit by rememberUpdatedState(maxItems)
    val picked by rememberUpdatedState(onPicked)
    val unavailable by rememberUpdatedState(onUnavailable)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked(listOf(uri))
    }
    // PickMultipleVisualMedia needs at least 2; its limit is fixed per contract, so a new limit makes a new contract.
    val multiLimit = maxOf(2, maxItems)
    val multiContract = remember(multiLimit) { ActivityResultContracts.PickMultipleVisualMedia(multiLimit) }
    val multi = rememberLauncherForActivityResult(multiContract) { uris ->
        pickedWithin(uris, limit).takeIf { it.isNotEmpty() }?.let { picked(it) }
    }
    return remember(single, multi) {
        {
            val n = limit
            try {
                when {
                    n <= 0 -> Unit
                    n == 1 -> single.launch(galleryImagesRequest())
                    else -> multi.launch(galleryImagesRequest())
                }
            } catch (_: ActivityNotFoundException) {
                unavailable()
            }
        }
    }
}
