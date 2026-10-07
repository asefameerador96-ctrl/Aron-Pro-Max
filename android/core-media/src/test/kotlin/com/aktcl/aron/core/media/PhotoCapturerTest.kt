package com.aktcl.aron.core.media

import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoCapturerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_791_000_000_000L
    private val clock = object : WallClock { override fun nowMs() = now; override fun elapsedRealtimeMs() = now }
    private var shots = 0
    private val raw = JvmImageCodec.withExif(JvmImageCodec.encode(JvmImageCodec.frame(1280, 720, 30), 95))
    private val camera = CameraSource { shots++; raw }
    private fun store() = MediaStore(File(tmp.root, "media/u7"))
    private fun capturer(store: MediaStore = store(), cam: CameraSource = camera, free: Long = Long.MAX_VALUE) =
        PhotoCapturer(cam, JvmImageCodec, store, clock, { "2026-10-07" }, freeBytes = { free }, cpu = Dispatchers.Unconfined)

    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val u2 = "22222222-2222-4222-8222-222222222222"
    private val visit = "33333333-3333-4333-8333-333333333333"
    private val ref = MediaRef("force_sale", "visit", visit)
    private val stamp = PhotoStamp(23.8103, 90.4125, 8.5, isMock = false, fixClientUuid = "44444444-4444-4444-8444-444444444444")

    @Test fun aCaptureIsCompressedFingerprintedAndStoredBeforeItIsReturned() = runTest {
        val s = store()
        val out = capturer(s).capture(u1) as CaptureOutcome.Captured
        val item = out.item
        assertTrue(item.bytes <= 150 * 1024)
        assertTrue(maxOf(item.width, item.height) <= 1024)
        val onDisk = s.readPhoto(u1)
        assertEquals(item.bytes, onDisk.size)
        assertEquals(PhotoCompressor.sha256Hex(onDisk), item.sha256)
        assertTrue(JpegMetadata.headerMarkers(onDisk).none { it in 0xE1..0xEF })
        assertEquals("2026-10-07", item.businessDate)
        assertEquals(MediaState.CAPTURED, item.state)
        assertTrue(File(out.thumbnailPath).length() in 1..(40 * 1024))
        // Nothing uncompressed is kept anywhere in the queue directory.
        assertTrue(s.dir.listFiles()!!.all { it.length() <= 150 * 1024 })
    }

    @Test fun attachStampsTheFixAndTheOwnerOnceAndForAll() = runTest {
        val c = capturer()
        c.capture(u1)
        val a = c.attach(u1, ref, stamp)
        assertEquals(MediaState.ATTACHED, a.state)
        assertEquals(stamp, a.stamp)
        assertEquals(ref, a.ref)
        assertTrue(a.evidence)
        assertEquals(a, c.attach(u1, ref, stamp)) // idempotent
        try { c.attach(u1, ref.copy(refClientUuid = u2), stamp); fail() } catch (_: IllegalStateException) { }
        try { c.attach(u2, ref, stamp); fail() } catch (_: IllegalArgumentException) { }
        try { c.attach(u1, ref.copy(purpose = "selfie"), stamp); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun aRetakeDiscardsTheReplacedPhotoButAClaimedPhotoIsNeverDiscarded() = runTest {
        val s = store(); val c = capturer(s)
        c.capture(u1); c.capture(u2)
        assertTrue(c.discard(u1))
        assertNull(s.get(u1)); assertFalse(s.photoFile(u1).exists())
        c.attach(u2, ref, stamp)
        assertFalse(c.discard(u2))
        assertTrue(s.photoFile(u2).exists())
    }

    @Test fun captureIsIdempotentByUuid() = runTest {
        val c = capturer()
        val a = (c.capture(u1) as CaptureOutcome.Captured).item
        val b = (c.capture(u1) as CaptureOutcome.Captured).item
        assertEquals(a, b)
        assertEquals(1, shots) // the camera is not opened again
    }

    @Test fun cancelledAndFailedCapturesStoreNothing() = runTest {
        val s = store()
        assertEquals(CaptureOutcome.Cancelled, capturer(s, cam = { null }).capture(u1))
        assertTrue(capturer(s, cam = { byteArrayOf(1, 2, 3) }).capture(u2) is CaptureOutcome.Failed)
        assertTrue(s.all().isEmpty())
        assertTrue(s.dir.listFiles()!!.isEmpty())
    }

    @Test fun lowStorageRefusesThePhotoWithoutOpeningTheCamera() = runTest {
        assertEquals(CaptureOutcome.NoSpace, capturer(free = 150L * 1024 * 1024).capture(u1))
        assertEquals(0, shots)
    }

    @Test fun aKillDuringPutLeavesNoHalfItemAndTheSweepCleansUp() = runTest {
        val s = store()
        capturer(s).capture(u1)
        // Simulate a kill after the photo was written but before its sidecar: a photo file and a temp file without a sidecar.
        File(s.dir, "$u2.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(s.dir, "$u2.json.tmp").writeText("{")
        val relaunched = MediaStore(s.dir)
        assertEquals(listOf(u1), relaunched.all().map { it.mediaUuid })
        assertEquals(2, relaunched.sweepUnfinished())
        assertTrue(relaunched.photoFile(u1).exists())
        assertFalse(File(s.dir, "$u2.jpg").exists())
    }

    @Test fun theQueueSurvivesARelaunch() = runTest {
        val s = store(); val c = capturer(s)
        c.capture(u1); c.attach(u1, ref, stamp)
        val again = MediaStore(s.dir).get(u1)!!
        assertEquals(MediaState.ATTACHED, again.state)
        assertEquals(stamp, again.stamp)
    }

    @Test fun aNonV4OrUpperCaseUuidIsRefused() = runTest {
        try { store().photoFile("../../etc/passwd"); fail() } catch (_: IllegalArgumentException) { }
        try { store().photoFile("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee".uppercase()); fail() } catch (_: IllegalArgumentException) { }
    }
}
