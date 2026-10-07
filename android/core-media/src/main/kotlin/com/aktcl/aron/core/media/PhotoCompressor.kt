package com.aktcl.aron.core.media

import java.security.MessageDigest

/**
 * The image operations the compressor needs. Android uses Bitmap (`AndroidImageCodec`); the JVM tests use ImageIO, so the
 * ladder below is proven on the host with real JPEG bytes.
 */
interface ImageCodec<I : Any> {
    /** Decodes the camera file upright (EXIF orientation applied to the pixels), sampled so the long edge is at least [minLongEdge]. */
    fun decodeUpright(bytes: ByteArray, minLongEdge: Int): I
    fun width(image: I): Int
    fun height(image: I): Int
    /** A copy scaled to exactly [w] x [h]. */
    fun scaled(image: I, w: Int, h: Int): I
    /** Baseline JPEG with no metadata segments of its own. */
    fun encodeJpeg(image: I, quality: Int): ByteArray
    /** Luma (0..255) of each pixel of a [w] x [h] scaled copy, row by row. */
    fun luma(image: I, w: Int, h: Int): IntArray
    fun release(image: I) {}
}

/** The binding limits (docs/24 s4.11, docs/17 s8.6); the config keys `cfg.media.*` override them. */
data class PhotoBudget(
    val longEdgePx: Int = 1024,
    val jpegQuality: Int = 70,
    val minQuality: Int = 40,
    val fallbackLongEdgePx: Int = 800,
    val maxBytes: Int = 150 * 1024,
)

data class CompressedPhoto(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val quality: Int,
    /** Lower-case hex SHA-256 of [jpeg]: what is stored, sent in the SAS request and checked by the server. */
    val sha256: String,
    /** 64-bit difference hash as 16 hex digits (MediaMetaPayload.phash). */
    val phash: String,
) {
    val bytes: Int get() = jpeg.size
}

class PhotoTooLargeException(val limitBytes: Int) : IllegalStateException("photo still over $limitBytes bytes at the smallest step")

/**
 * Photo compression of F-SYS-030: long edge 1024 px at quality 70; while over 150 KB lower the quality by 10 down to 40,
 * then the long edge 800 px (quality ladder again); then, so the budget always holds, the long edge shrinks by a fifth per
 * step at quality 40 (never below 320 px). Never upscales. All EXIF and other metadata are removed (orientation is
 * applied to the pixels first, so nothing is lost); location lives in the media queue, never in the file.
 */
class PhotoCompressor<I : Any>(private val codec: ImageCodec<I>, private val budget: PhotoBudget = PhotoBudget()) {

    fun compress(cameraJpeg: ByteArray): CompressedPhoto {
        val src = codec.decodeUpright(cameraJpeg, budget.longEdgePx)
        try {
            for ((edge, qualities) in steps(maxOf(codec.width(src), codec.height(src)))) {
                val img = fit(src, edge)
                try {
                    for (q in qualities) {
                        val jpeg = JpegMetadata.strip(codec.encodeJpeg(img, q))
                        if (jpeg.size <= budget.maxBytes) {
                            return CompressedPhoto(jpeg, codec.width(img), codec.height(img), q, sha256Hex(jpeg), dHash(img))
                        }
                    }
                } finally {
                    if (img !== src) codec.release(img)
                }
            }
            throw PhotoTooLargeException(budget.maxBytes)
        } finally {
            codec.release(src)
        }
    }

    /** (long edge, qualities) in the order they are tried; edges never exceed the source's own long edge. */
    internal fun steps(sourceLongEdge: Int): List<Pair<Int, List<Int>>> {
        val out = mutableListOf<Pair<Int, List<Int>>>()
        val first = minOf(budget.longEdgePx, sourceLongEdge)
        out += first to qualityLadder()
        if (budget.fallbackLongEdgePx < first) out += budget.fallbackLongEdgePx to qualityLadder()
        var edge = minOf(first, budget.fallbackLongEdgePx)
        while (edge > MIN_EDGE) {
            edge = maxOf(MIN_EDGE, edge * 4 / 5)
            out += edge to listOf(budget.minQuality)
        }
        return out
    }

    private fun qualityLadder() = generateSequence(budget.jpegQuality) { it - 10 }.takeWhile { it >= budget.minQuality }.toList()
        .let { if (it.last() != budget.minQuality) it + budget.minQuality else it }

    private fun fit(src: I, edge: Int): I {
        val w = codec.width(src)
        val h = codec.height(src)
        val long = maxOf(w, h)
        if (long <= edge) return src
        val nw = maxOf(1, Math.round(w.toDouble() * edge / long).toInt())
        val nh = maxOf(1, Math.round(h.toDouble() * edge / long).toInt())
        return codec.scaled(src, nw, nh)
    }

    /** dHash: 9 x 8 luma, one bit per horizontal gradient, 64 bits as 16 hex digits. */
    private fun dHash(img: I): String {
        val l = codec.luma(img, 9, 8)
        var bits = 0UL
        for (y in 0 until 8) for (x in 0 until 8) {
            bits = (bits shl 1) or (if (l[y * 9 + x] > l[y * 9 + x + 1]) 1UL else 0UL)
        }
        return bits.toString(16).padStart(16, '0')
    }

    companion object {
        const val MIN_EDGE = 320

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Removes every APPn (except the JFIF APP0) and COM segment from a JPEG: EXIF, XMP, ICC, maker notes, comments. */
object JpegMetadata {
    fun strip(jpeg: ByteArray): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        val out = java.io.ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xDA) break // start of scan: the rest is image data
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            require(len >= 2 && i + 2 + len <= jpeg.size) { "corrupt JPEG segment" }
            val drop = (marker in 0xE1..0xEF) || marker == 0xFE || (marker == 0xE0 && !isJfif(jpeg, i + 4, len - 2))
            if (!drop) out.write(jpeg, i, 2 + len)
            i += 2 + len
        }
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }

    private fun isJfif(b: ByteArray, at: Int, n: Int) = n >= 5 && String(b, at, 5, Charsets.US_ASCII) == "JFIF\u0000"

    /** The markers of every header segment before the scan, for tests and the device check. */
    fun headerMarkers(jpeg: ByteArray): List<Int> {
        val m = mutableListOf<Int>()
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            m += marker
            if (marker == 0xDA) break
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + len
        }
        return m
    }
}
