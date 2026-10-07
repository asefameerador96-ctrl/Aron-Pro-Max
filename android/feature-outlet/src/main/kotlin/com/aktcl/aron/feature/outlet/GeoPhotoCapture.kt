package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.common.ClientIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The camera and compression pipeline of F-SYS-030 (android-core); this lane only calls it. */
interface PhotoPipeline {
    /** Captures one photo, compresses it and queues its media record; returns the stored photo, or null when cancelled. */
    suspend fun captureAndCompress(photoUuid: String): CapturedPhoto?
}

data class CapturedPhoto(val photoUuid: String, val thumbnailPath: String, val sizeBytes: Long)

data class GeoPhotoState(
    val photo: CapturedPhoto? = null,
    val fix: FixReading? = null,
    /** The 'GEO captured +/- n m' line, with n the rounded accuracy; null until a fix is stored. */
    val accuracyMeters: Int? = null,
    val retakesLeft: Int = 1,
    val busy: Boolean = false,
) {
    val complete: Boolean get() = photo != null && fix != null && fix.isOk
    val geoMocked: Boolean get() = fix?.isMock == true
}

/**
 * GEO and photo capture component (F-SR-079), shared by Force Sale, new shop, info change and the other outlet requests:
 * one fix at the shutter with its mock flag, the thumbnail, one retake, and a compressed photo. Offline by construction.
 */
class GeoPhotoCapture(
    private val fixes: LocationFixSource,
    private val photos: PhotoPipeline,
    private val purpose: String,
    private val maxRetakes: Int = 1,
    private val newUuid: () -> String = ClientIds::newUuid,
) {
    private val ui = MutableStateFlow(GeoPhotoState(retakesLeft = maxRetakes))
    val state: StateFlow<GeoPhotoState> = ui.asStateFlow()

    /** The first shutter press, or a retake (which consumes the single retake and replaces both photo and fix). */
    suspend fun shutter(): GeoPhotoState {
        val cur = ui.value
        if (cur.busy) return cur
        val isRetake = cur.photo != null
        if (isRetake && cur.retakesLeft <= 0) return cur
        ui.value = cur.copy(busy = true)
        try {
            val fix = fixes.readFix(purpose)
            val photo = photos.captureAndCompress(newUuid())
            ui.value = if (photo == null) {
                cur.copy(busy = false) // cancelled: keep what was there, keep the retake
            } else {
                GeoPhotoState(
                    photo = photo, fix = fix, accuracyMeters = if (fix.isOk) fix.accuracyM?.let { Math.round(it).toInt() } else null,
                    retakesLeft = if (isRetake) cur.retakesLeft - 1 else cur.retakesLeft, busy = false,
                )
            }
        } catch (t: Throwable) {
            ui.value = cur.copy(busy = false)
            throw t
        }
        return ui.value
    }
}
