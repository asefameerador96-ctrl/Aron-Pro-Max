package com.aktcl.aron.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CheckerF030Test {
    @get:Rule val tmp = TemporaryFolder()

    /** 400 x 200 grey frame with a red block at the top-left quadrant; the camera stores it with [orientation]. */
    private fun file(orientation: Int, w: Int = 400, h: Int = 200, png: Boolean = false): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.GRAY)
        c.drawRect(0f, 0f, w / 4f, h / 4f, Paint().apply { color = Color.RED })
        val bytes = ByteArrayOutputStream().also { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        if (png) return bytes
        val f = File(tmp.root, "o$orientation.jpg"); f.writeBytes(bytes)
        ExifInterface(f).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return f.readBytes()
    }

    private fun isRed(c: Int) = Color.red(c) > 180 && Color.green(c) < 80

    /** Where the red marker must end up after the EXIF transform is applied (display orientation). */
    @Test fun allEightOrientationsPutTheMarkerWhereTheViewerSawIt() {
        // Stored marker at stored top-left. Displayed corner per EXIF spec:
        val expected = mapOf(
            1 to "TL", 2 to "TR", 3 to "BR", 4 to "BL",
            5 to "TL", 6 to "TR", 7 to "BR", 8 to "BL",
        )
        val bad = mutableListOf<String>()
        for ((o, corner) in expected) {
            val p = PhotoCompressor(AndroidImageCodec).compress(file(o))
            val b = BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size)
            val portrait = o >= 5
            if (portrait != (b.height > b.width)) bad += "o$o size ${b.width}x${b.height}"
            val x = if (corner.endsWith("L")) 5 else b.width - 6
            val y = if (corner.startsWith("T")) 5 else b.height - 6
            if (!isRed(b.getPixel(x, y))) bad += "o$o marker not at $corner"
        }
        assertTrue(bad.toString(), bad.isEmpty())
    }

    @Test fun extremeAspectTinyAndPngSourcesHoldTheBudget() {
        for ((w, h) in listOf(4000 to 50, 50 to 4000, 10 to 10, 1 to 1, 700 to 300)) {
            val p = PhotoCompressor(AndroidImageCodec).compress(file(1, w, h))
            assertTrue("$w x $h", maxOf(p.width, p.height) <= 1024 && p.bytes <= 150 * 1024)
            assertTrue(maxOf(p.width, p.height) <= maxOf(w, h))
        }
        val png = PhotoCompressor(AndroidImageCodec).compress(file(1, 1600, 900, png = true))
        assertEquals(1024, png.width)
        assertTrue(JpegMetadata.headerMarkers(png.jpeg).none { it in 0xE1..0xEF })
    }

    /** A second capture while one camera request is open is reported as "could not be saved" instead of being refused/ignored. */
    @Test fun aSecondCaptureWhileOneIsOpenIsNotAFailedSave() = runTest {
        val host = CameraCaptureHost()
        val clock = object : WallClock { override fun nowMs() = 0L; override fun elapsedRealtimeMs() = 0L }
        val store = MediaStore(File(tmp.root, "m"))
        val c = PhotoCapturer(host, JvmImageCodec, store, clock, { "2026-10-07" }, freeBytes = { Long.MAX_VALUE }, cpu = Dispatchers.Unconfined)
        val first = async { c.capture("11111111-1111-4111-8111-111111111111") }
        yield()
        val second = c.capture("22222222-2222-4222-8222-222222222222")
        host.cancel(); first.await()
        assertTrue("second capture outcome was $second", second !is CaptureOutcome.Failed)
    }
}
