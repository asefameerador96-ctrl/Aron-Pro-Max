package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.raster.MonoBitmap

/** Binary PBM (P4): the golden-image format; it stores exactly the printer's bits and any image viewer opens it. */
object Pbm {
    fun encode(b: MonoBitmap): ByteArray = "P4\n${b.width} ${b.height}\n".toByteArray(Charsets.US_ASCII) + b.data

    fun decode(bytes: ByteArray): MonoBitmap {
        var i = 0
        fun token(): String {
            while (bytes[i].toInt().toChar().isWhitespace()) i++
            val s = i
            while (!bytes[i].toInt().toChar().isWhitespace()) i++
            return String(bytes, s, i - s, Charsets.US_ASCII)
        }
        require(token() == "P4") { "not P4" }
        val w = token().toInt()
        val h = token().toInt()
        i++ // single whitespace before the raster
        val b = MonoBitmap(w, h)
        System.arraycopy(bytes, i, b.data, 0, b.data.size)
        return b
    }
}
