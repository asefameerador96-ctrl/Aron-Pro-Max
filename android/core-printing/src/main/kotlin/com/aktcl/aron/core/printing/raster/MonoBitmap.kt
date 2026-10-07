package com.aktcl.aron.core.printing.raster

/**
 * A 1-bit image, packed MSB first, one row after another, `bytesPerRow = ceil(width / 8)`. Bit set = black dot.
 * This is exactly the payload of ESC/POS `GS v 0`, so the printer gets these bytes unchanged.
 */
class MonoBitmap(val width: Int, val height: Int) {
    val bytesPerRow: Int = (width + 7) / 8
    val data: ByteArray = ByteArray(bytesPerRow * height)

    init {
        require(width > 0 && height >= 0) { "bad size $width x $height" }
    }

    operator fun get(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return data[y * bytesPerRow + (x ushr 3)].toInt() and (0x80 ushr (x and 7)) != 0
    }

    fun set(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * bytesPerRow + (x ushr 3)
        data[i] = (data[i].toInt() or (0x80 ushr (x and 7))).toByte()
    }

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in maxOf(0, y0) until minOf(height, y1)) for (x in maxOf(0, x0) until minOf(width, x1)) set(x, y)
    }

    /** A copy cut to rows [from, to). */
    fun rows(from: Int, to: Int): MonoBitmap {
        val out = MonoBitmap(width, to - from)
        System.arraycopy(data, from * bytesPerRow, out.data, 0, (to - from) * bytesPerRow)
        return out
    }

    fun blackDots(): Int = data.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    /** Text dump ('#' and '.') for test failure messages and goldens a person can read. */
    fun toAscii(): String = buildString {
        for (y in 0 until height) {
            for (x in 0 until width) append(if (get(x, y)) '#' else '.')
            append('\n')
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MonoBitmap && other.width == width && other.height == height && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * (31 * width + height) + data.contentHashCode()

    companion object {
        /** Stacks [parts] (same width) top to bottom. */
        fun stack(width: Int, parts: List<MonoBitmap>): MonoBitmap {
            val out = MonoBitmap(width, parts.sumOf { it.height })
            var row = 0
            for (p in parts) {
                require(p.width == width) { "width ${p.width} != $width" }
                System.arraycopy(p.data, 0, out.data, row * out.bytesPerRow, p.data.size)
                row += p.height
            }
            return out
        }
    }
}
