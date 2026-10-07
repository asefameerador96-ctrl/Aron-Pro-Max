package com.aktcl.aron.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** F-SYS-030: at most 150 KB, long edge at most 1024 px, no EXIF, SHA-256 of the stored bytes. Real JPEG bytes (ImageIO). */
class PhotoCompressorTest {
    private val compressor = PhotoCompressor(JvmImageCodec)

    private fun camera(w: Int, h: Int, noise: Int) = JvmImageCodec.withExif(JvmImageCodec.encode(JvmImageCodec.frame(w, h, noise), 95))

    @Test fun aTypicalCameraFrameFitsTheBudgetAt1024() {
        val raw = camera(1280, 720, 30)
        assertTrue("raw camera frame should be over budget: ${raw.size}", raw.size > 150 * 1024)
        val p = compressor.compress(raw)
        assertTrue(p.bytes <= 150 * 1024)
        assertEquals(1024, maxOf(p.width, p.height))
        assertEquals(576, minOf(p.width, p.height)) // aspect kept
        assertTrue(p.quality in 40..70)
    }

    @Test fun aWorstCaseNoisyFrameStillEndsUnderTheBudget() {
        val p = compressor.compress(camera(1600, 1200, 255))
        assertTrue("${p.bytes} bytes", p.bytes <= 150 * 1024)
        assertTrue(maxOf(p.width, p.height) <= 1024)
    }

    @Test fun theLadderStepsQualityTo40ThenTheEdgeTo800ThenShrinks() {
        val c = PhotoCompressor(JvmImageCodec)
        val steps = c.steps(4000)
        assertEquals(1024 to listOf(70, 60, 50, 40), steps[0])
        assertEquals(800 to listOf(70, 60, 50, 40), steps[1])
        assertEquals(640 to listOf(40), steps[2])
        assertEquals(320, steps.last().first)
        assertTrue(steps.drop(2).zipWithNext().all { (a, b) -> b.first < a.first })
        // A small source is never upscaled.
        assertEquals(600, c.steps(600)[0].first)
        assertTrue(c.steps(600).none { it.first > 600 })
    }

    @Test fun aSmallPhotoIsNeverUpscaled() {
        val p = compressor.compress(camera(640, 480, 10))
        assertEquals(640, p.width); assertEquals(480, p.height)
        assertEquals(70, p.quality)
    }

    @Test fun exifAndEveryOtherMetadataSegmentIsGone() {
        val raw = camera(1280, 960, 20)
        assertTrue(0xE1 in JpegMetadata.headerMarkers(raw))
        val p = compressor.compress(raw)
        val markers = JpegMetadata.headerMarkers(p.jpeg)
        assertTrue("markers ${markers.map { "%02x".format(it) }}", markers.none { it in 0xE1..0xEF || it == 0xFE })
        assertFalse(String(p.jpeg, Charsets.ISO_8859_1).contains("GPS"))
        assertFalse(String(p.jpeg, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test fun stripRemovesApp1To15AndCommentsButKeepsTheImageDecodable() {
        val base = JvmImageCodec.encode(JvmImageCodec.frame(200, 100, 10), 80)
        val com = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 7) + "hello".toByteArray()
        val dirty = JvmImageCodec.withExif(base).let { it.copyOfRange(0, 2) + com + it.copyOfRange(2, it.size) }
        val clean = JpegMetadata.strip(dirty)
        assertTrue(JpegMetadata.headerMarkers(clean).none { it in 0xE1..0xEF || it == 0xFE })
        assertEquals(200, javax.imageio.ImageIO.read(clean.inputStream()).width)
        assertTrue(clean.contentEquals(JpegMetadata.strip(clean))) // idempotent
    }

    @Test fun sha256IsOfTheStoredBytesAndPhashIs16Hex() {
        val p = compressor.compress(camera(1280, 720, 30))
        val expect = MessageDigest.getInstance("SHA-256").digest(p.jpeg).joinToString("") { "%02x".format(it) }
        assertEquals(expect, p.sha256)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(p.sha256))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(p.phash))
    }

    @Test fun theSamePhotoGivesTheSameHashes() {
        val raw = camera(1280, 720, 30)
        assertEquals(compressor.compress(raw).sha256, compressor.compress(raw).sha256)
    }

    @Test fun aLowerConfiguredBudgetIsHonoured() {
        val p = PhotoCompressor(JvmImageCodec, PhotoBudget(maxBytes = 60 * 1024)).compress(camera(1280, 720, 60))
        assertTrue(p.bytes <= 60 * 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun notAJpegIsRefusedByTheStripper() { JpegMetadata.strip(byteArrayOf(1, 2, 3, 4)) }
}
