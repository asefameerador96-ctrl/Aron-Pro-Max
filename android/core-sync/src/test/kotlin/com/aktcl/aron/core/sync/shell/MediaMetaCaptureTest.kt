package com.aktcl.aron.core.sync.shell

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.media.MediaItem
import com.aktcl.aron.core.media.MediaRef
import com.aktcl.aron.core.media.MediaState
import com.aktcl.aron.core.media.PhotoStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The media shell's `media_meta` mapping (android-sys-media-meta, docs/requests/android-sys-app-wiring.md item 2). */
class MediaMetaCaptureTest {
    private val meta = CaptureMeta(
        businessDate = "2026-10-08", capturedAt = "2026-10-08T03:00:00.000Z", capturedElapsedMs = 1, bootCount = 1, clockOffsetMs = 0,
        capturedOffline = false, routeId = null, bundleVersion = "2026-10-07:3", configVersion = 4,
    )
    private val item = MediaItem(
        mediaUuid = "6f1c2b8e-1d2a-4c3b-9e4f-5a6b7c8d9e0f", businessDate = "2026-10-07", takenAt = "2026-10-07T11:59:00.000Z",
        createdAtMs = 0, sha256 = "a".repeat(64), phash = "0123456789abcdef", bytes = 120_000, width = 1024, height = 768,
        state = MediaState.UPLOADED,
        ref = MediaRef("force_sale", "visit", "1b2c3d4e-5f60-4a1b-8c2d-3e4f5a6b7c8d"),
        stamp = PhotoStamp(23.8, 90.4, 12.0, false, fixClientUuid = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"),
        blobPath = "photos/2026-10-07/u/6f1c2b8e-1d2a-4c3b-9e4f-5a6b7c8d9e0f.jpg",
    )

    @Test
    fun theRecordIsThePhotoKeyedByItsUuidOnThePhotosBusinessDate() {
        val c = mediaMetaCapture(item, meta, fixStored = true)!!
        assertEquals(item.mediaUuid, c.mediaUuid)
        assertEquals("2026-10-07", c.meta.businessDate) // the shot's Dhaka date, not the upload's
        assertEquals("force_sale", c.purpose); assertEquals("visit", c.refType); assertEquals(item.ref!!.refClientUuid, c.refClientUuid)
        assertEquals(item.blobPath, c.blobPath); assertEquals(item.takenAt, c.takenAt); assertEquals(item.sha256, c.sha256)
        assertEquals(item.stamp!!.fixClientUuid, c.fixClientUuid)
    }

    @Test
    fun aFixThatIsNotOnThePhoneIsSentAsNull() {
        assertNull(mediaMetaCapture(item, meta, fixStored = false)!!.fixClientUuid)
    }

    @Test
    fun anUnclaimedOrNotYetUploadedPhotoHasNoRecord() {
        assertNull(mediaMetaCapture(item.copy(ref = null), meta, true))
        assertNull(mediaMetaCapture(item.copy(blobPath = null), meta, true))
    }
}
