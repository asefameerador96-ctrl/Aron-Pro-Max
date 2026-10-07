package com.aktcl.aron.feature.outlet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-079. */
class GeoPhotoCaptureTest {
    private var fixReads = 0
    private var next: FixReading = FixReading("ok", 23.79, 90.40, 11.6, false)
    private val fixes = object : LocationFixSource { override suspend fun readFix(purpose: String, refreshCount: Int): FixReading { fixReads++; return next } }
    private var cancel = false
    private val photos = object : PhotoPipeline {
        val discarded = mutableListOf<String>()
        override suspend fun captureAndCompress(photoUuid: String) = if (cancel) null else CapturedPhoto(photoUuid, "/t/$photoUuid.jpg", 48_000)
        override suspend fun discard(photoUuid: String) { discarded += photoUuid }
    }
    private var n = 0
    private fun cap() = GeoPhotoCapture(fixes, photos, "outlet_capture", newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })

    @Test fun oneFixAtTheShutterWithAccuracyAndThumbnail() = runTest {
        val s = cap().shutter()
        assertEquals(1, fixReads); assertEquals(12, s.accuracyMeters); assertTrue(s.complete); assertFalse(s.geoMocked)
        assertEquals("/t/00000000-0000-4000-8000-000000000001.jpg", s.photo!!.thumbnailPath)
    }

    @Test fun mockFlagIsCarried() = runTest {
        next = next.copy(isMock = true); assertTrue(cap().shutter().geoMocked)
    }

    @Test fun exactlyOneRetakeReplacesPhotoAndFix() = runTest {
        val c = cap(); val first = c.shutter().photo!!.photoUuid
        next = next.copy(accuracyM = 5.0)
        val second = c.shutter()
        assertTrue(second.photo!!.photoUuid != first); assertEquals(5, second.accuracyMeters); assertEquals(0, second.retakesLeft)
        val third = c.shutter()
        assertEquals(second, third); assertEquals(2, fixReads) // no further read after the retake is used
    }

    @Test fun retakeDiscardsTheFirstPhotoSoItIsNeverUploaded() = runTest {
        val c = cap(); val first = c.shutter().photo!!.photoUuid; c.shutter()
        assertEquals(listOf(first), photos.discarded)
    }

    @Test fun retakeWithAFailedFixKeepsTheGoodCaptureAndDiscardsTheNewPhoto() = runTest {
        val c = cap(); val good = c.shutter()
        next = FixReading("timeout", null, null, null, false)
        val after = c.shutter()
        assertEquals(good.photo, after.photo); assertTrue(after.complete); assertEquals(1, photos.discarded.size)
    }

    @Test fun failedFixLeavesTheCaptureIncomplete() = runTest {
        next = FixReading("timeout", null, null, null, false)
        val s = cap().shutter()
        assertFalse(s.complete); assertNull(s.accuracyMeters)
    }

    @Test fun cancelledCameraKeepsTheRetake() = runTest {
        cancel = true
        val c = cap(); val s = c.shutter()
        assertNull(s.photo); assertEquals(1, s.retakesLeft)
    }
}
