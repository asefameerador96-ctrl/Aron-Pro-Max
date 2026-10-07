package com.aktcl.aron.core.media

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Host codec for the compressor tests: ImageIO decode and baseline JPEG encode. Orientation is not applied (no EXIF reader). */
object JvmImageCodec : ImageCodec<BufferedImage> {
    override fun decodeUpright(bytes: ByteArray, minLongEdge: Int): BufferedImage = ImageIO.read(bytes.inputStream()) ?: error("undecodable")
    override fun width(image: BufferedImage) = image.width
    override fun height(image: BufferedImage) = image.height
    override fun scaled(image: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, w, h, null); g.dispose()
        return out
    }
    override fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray = encode(image, quality)
    override fun luma(image: BufferedImage, w: Int, h: Int): IntArray {
        val s = scaled(image, w, h)
        return IntArray(w * h) { i -> val c = s.getRGB(i % w, i / w); (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
    }

    fun encode(image: BufferedImage, quality: Int): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else scaled(image, image.width, image.height)
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { w.output = it; w.write(null, IIOImage(rgb, null, null), p) }
        w.dispose()
        return bos.toByteArray()
    }

    /** A camera-like frame: smooth gradients plus [noise] (0..255) of per-pixel noise, which makes JPEG work hard. */
    fun frame(w: Int, h: Int, noise: Int, seed: Long = 1): BufferedImage {
        val r = java.util.Random(seed)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            fun ch(base: Int) = (base + if (noise > 0) r.nextInt(noise) - noise / 2 else 0).coerceIn(0, 255)
            img.setRGB(x, y, (ch(x * 255 / w) shl 16) or (ch(y * 255 / h) shl 8) or ch(((x + y) * 127) / (w + h)))
        }
        return img
    }

    /** Inserts an EXIF APP1 segment (with a fake GPS IFD marker string) right after SOI, like a camera file. */
    fun withExif(jpeg: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000MM\u0000*GPSLatitude=23.81;GPSLongitude=90.41".toByteArray(Charsets.ISO_8859_1)
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }
}
