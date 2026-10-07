package com.aktcl.aron.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [ImageCodec] on Android Bitmaps. Decoding samples down by powers of two first, so a 13 MP camera frame never sits in
 * memory at full size on a 2 GB phone; EXIF orientation is applied to the pixels; `Bitmap.compress` writes no EXIF.
 */
object AndroidImageCodec : ImageCodec<Bitmap> {
    override fun decodeUpright(bytes: ByteArray, minLongEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("undecodable image")
        val matrix = orientationMatrix(runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL))
            ?: return decoded
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    internal fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        else -> null
    }

    override fun width(image: Bitmap) = image.width
    override fun height(image: Bitmap) = image.height
    override fun scaled(image: Bitmap, w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(image, w, h, true)

    override fun encodeJpeg(image: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream(64 * 1024).also { check(image.compress(Bitmap.CompressFormat.JPEG, quality, it)) { "JPEG encode failed" } }.toByteArray()

    override fun luma(image: Bitmap, w: Int, h: Int): IntArray {
        val small = Bitmap.createScaledBitmap(image, w, h, true)
        try {
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            return IntArray(px.size) { i -> val c = px[i]; (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
        } finally {
            if (small !== image) small.recycle()
        }
    }

    override fun release(image: Bitmap) = image.recycle()
}
