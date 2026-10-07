package com.aktcl.aron.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** The real Android codec (Robolectric native graphics): orientation applied, budget held, no EXIF in the output. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImageCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A landscape 1600 x 1200 frame with detail, stored with EXIF orientation 6 (rotate 90), like a phone held upright. */
    private fun cameraFile(orientation: Int): ByteArray {
        val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint()
        val r = java.util.Random(3)
        for (i in 0 until 4000) { p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)); c.drawCircle(r.nextFloat() * 1600, r.nextFloat() * 1200, 4f + r.nextFloat() * 20, p) }
        val f = File(tmp.root, "cam.jpg")
        f.writeBytes(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray())
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setLatLong(23.81, 90.41)
            saveAttributes()
        }
        return f.readBytes()
    }

    @Test fun portraitPhotoComesOutUprightUnderBudgetWithoutExif() {
        val raw = cameraFile(ExifInterface.ORIENTATION_ROTATE_90)
        val p = PhotoCompressor(AndroidImageCodec).compress(raw)
        assertEquals(1024, p.height) // rotated: the long edge is now vertical
        assertEquals(768, p.width)
        assertTrue("${p.bytes} bytes", p.bytes <= 150 * 1024)
        assertTrue(JpegMetadata.headerMarkers(p.jpeg).none { it in 0xE1..0xEF })
        val exif = ExifInterface(p.jpeg.inputStream())
        assertEquals(null, exif.latLong)
    }

    @Test fun landscapeStaysLandscape() {
        val p = PhotoCompressor(AndroidImageCodec).compress(cameraFile(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(1024, p.width); assertEquals(768, p.height)
    }

    @Test fun decodeSamplesDownButNeverBelowTheTargetEdge() {
        val img = AndroidImageCodec.decodeUpright(cameraFile(ExifInterface.ORIENTATION_NORMAL), 1024)
        assertTrue(maxOf(img.width, img.height) >= 1024)
        val small = AndroidImageCodec.decodeUpright(cameraFile(ExifInterface.ORIENTATION_NORMAL), 256)
        assertTrue(maxOf(small.width, small.height) in 256..799) // sampled by 4: 400 x 300
    }
}
