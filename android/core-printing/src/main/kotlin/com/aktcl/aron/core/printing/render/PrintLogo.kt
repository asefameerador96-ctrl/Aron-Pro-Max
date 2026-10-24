package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.raster.MonoBitmap

/**
 * The Aron mark printed above the "AKTCL" header (owner 2026-10-24, D-PRINT-09). It is a 1-bit image made once from
 * `assets/brand/aron-logo-original.png` (cropped, 112 dots high, Floyd-Steinberg dithered) and bundled as
 * `src/main/resources/aron/print/logo.pbm` (P4, about 1 KB), so the JVM goldens and the phone get the same bits with no
 * image decoding on the device. It is added on top of the rendered paper, so templates, their versions and the goldens of
 * the documents themselves do not change. When it is missing or malformed, or switched off for the printer
 * (`SavedPrinter.printLogo`), the paper starts with the text header as before. It adds about 120 rows (about 5.8 KB of
 * raster, under half a second at print speed).
 */
object PrintLogo {
    /** The bundled mark, or null when the resource is missing or unreadable (never throws). */
    val mark: MonoBitmap? by lazy {
        try {
            PrintLogo::class.java.getResourceAsStream("/aron/print/logo.pbm")?.use { decode(it.readBytes()) }
        } catch (_: Exception) {
            null
        }
    }

    /** White dots between the mark and the header line. */
    const val GAP_DOTS = 8

    /** The largest mark accepted: the paper width and a modest height (battery and print time). */
    const val MAX_HEIGHT = 200

    /** [paper] with [logo] centred above it, [GAP_DOTS] apart; [paper] itself when there is no logo or it is too wide. */
    fun onTop(paper: MonoBitmap, logo: MonoBitmap? = mark): MonoBitmap {
        if (logo == null || logo.width > paper.width || logo.height !in 1..MAX_HEIGHT) return paper
        val band = MonoBitmap(paper.width, logo.height + GAP_DOTS)
        val x0 = (paper.width - logo.width) / 2
        for (y in 0 until logo.height) for (x in 0 until logo.width) if (logo[x, y]) band.set(x0 + x, y)
        return MonoBitmap.stack(paper.width, listOf(band, paper))
    }

    /** Binary PBM (P4): "P4", width, height (whitespace separated, `#` comments allowed), one whitespace, the rows. */
    internal fun decode(bytes: ByteArray): MonoBitmap {
        var i = 0
        fun skip() {
            while (i < bytes.size) {
                val c = bytes[i].toInt().toChar()
                if (c == '#') { while (i < bytes.size && bytes[i].toInt() != '\n'.code) i++ } else if (c.isWhitespace()) i++ else return
            }
        }
        fun token(): String {
            skip()
            val s = i
            while (i < bytes.size && !bytes[i].toInt().toChar().isWhitespace()) i++
            return String(bytes, s, i - s, Charsets.US_ASCII)
        }
        if (token() != "P4") throw IllegalArgumentException("not a P4 image")
        val w = token().toIntOrNull() ?: throw IllegalArgumentException("width")
        val h = token().toIntOrNull() ?: throw IllegalArgumentException("height")
        if (w !in 1..384 || h !in 1..MAX_HEIGHT) throw IllegalArgumentException("size $w x $h")
        i++ // the single whitespace before the rows
        val b = MonoBitmap(w, h)
        if (bytes.size - i < b.data.size) throw IllegalArgumentException("truncated")
        System.arraycopy(bytes, i, b.data, 0, b.data.size)
        return b
    }
}
