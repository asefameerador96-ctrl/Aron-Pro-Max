package com.aktcl.aron.core.printing.escpos

import com.aktcl.aron.core.printing.raster.MonoBitmap

/**
 * ESC/POS for the MP-58N and RPP02N-class 58 mm printers (N-019, docs/24 s5.5). Text never goes to the printer
 * as characters (it has no Bengali font): every document is a raster image sent with `GS v 0` in bands.
 */
object EscPos {
    /** ESC @: reset the printer (clears the buffer and modes). */
    val INIT = byteArrayOf(0x1B, 0x40)

    /** ESC 3 0: line spacing 0, so consecutive bands touch without white gaps. */
    val LINE_SPACING_0 = byteArrayOf(0x1B, 0x33, 0x00)

    /** ESC d n: print and feed n lines. */
    fun feedLines(n: Int): ByteArray = byteArrayOf(0x1B, 0x64, n.coerceIn(0, 255).toByte())

    /** Line spacing of the closing feed (dots); [TEAR_OFF_LINES] of it is over an inch at 203 dpi. */
    const val TEAR_OFF_SPACING = 26

    const val TEAR_OFF_LINES = 9

    /** Paper the closing feed moves (234 dots, about 29 mm): margin over the inch in case a firmware's unit is coarser. */
    const val TEAR_OFF_DOTS = TEAR_OFF_SPACING * TEAR_OFF_LINES

    /**
     * The end of every document (owner 2026-10-24, D-PRINT-09): at least one inch (203 dots) of blank paper after the last
     * printed row, so tearing at the bar never cuts it. `ESC d` feeds in units of the current line spacing, and the raster
     * job sets that to 0 (`ESC 3 0`, so bands touch): before, the job's `ESC d 3` fed next to nothing on firmware that
     * honours it. The spacing is set explicitly (26 dots) and 9 lines are fed: 234 dots, about 29 mm.
     */
    val TEAR_OFF: ByteArray = byteArrayOf(0x1B, 0x33, TEAR_OFF_SPACING.toByte()) + feedLines(TEAR_OFF_LINES)

    /** DLE EOT n: real-time status. n = 1 printer, 2 offline cause, 4 paper roll sensor. */
    fun statusQuery(n: Int): ByteArray = byteArrayOf(0x10, 0x04, n.toByte())

    /** Header of `GS v 0` (normal size) for a block [widthBytes] x [rows]. */
    fun rasterHeader(widthBytes: Int, rows: Int): ByteArray {
        require(widthBytes in 1..255 * 256 && rows in 1..0xFFFF) { "raster $widthBytes x $rows" }
        return byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (widthBytes and 0xFF).toByte(), (widthBytes ushr 8).toByte(),
            (rows and 0xFF).toByte(), (rows ushr 8).toByte(),
        )
    }

    /** One band of [bitmap], rows [from, to), as a complete `GS v 0` command. */
    fun rasterBand(bitmap: MonoBitmap, from: Int, to: Int): ByteArray {
        val rows = to - from
        val header = rasterHeader(bitmap.bytesPerRow, rows)
        val out = ByteArray(header.size + rows * bitmap.bytesPerRow)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(bitmap.data, from * bitmap.bytesPerRow, out, header.size, rows * bitmap.bytesPerRow)
        return out
    }

    /**
     * The whole job: reset, then the image in bands of [bandRows] rows (small bands keep each command inside
     * the printer's receive buffer, so a slow printer never drops bytes mid-command), then [TEAR_OFF].
     * Trailing white rows are trimmed to save paper and time.
     */
    fun job(bitmap: MonoBitmap, bandRows: Int = 24): List<ByteArray> {
        require(bandRows in 1..255) { "bandRows $bandRows" }
        val out = ArrayList<ByteArray>()
        out.add(INIT + LINE_SPACING_0)
        val height = lastInkRow(bitmap) + 1
        var y = 0
        while (y < height) {
            val to = minOf(height, y + bandRows)
            out.add(rasterBand(bitmap, y, to))
            y = to
        }
        out.add(TEAR_OFF)
        return out
    }

    /** ESC 3 24: line spacing 24 dots, so 24-dot bit-image bands ([bitImage24Band]) touch. */
    val LINE_SPACING_24 = byteArrayOf(0x1B, 0x33, 24)

    /** ESC 2: back to the default line spacing (for text after an image). */
    val LINE_SPACING_DEFAULT = byteArrayOf(0x1B, 0x32)

    /**
     * Rows [from, from + 24) of [bitmap] as `ESC * 33 nL nH d1..dk LF` (24-dot double density, D-PRINT-02): the
     * bit-image command older ESC/POS firmware supports when it has no `GS v 0`. Column major: three bytes per dot
     * column, MSB = top dot. Rows past the bitmap's end are white.
     */
    fun bitImage24Band(bitmap: MonoBitmap, from: Int): ByteArray {
        val n = bitmap.width
        val out = ByteArray(5 + 3 * n + 1)
        out[0] = 0x1B; out[1] = 0x2A; out[2] = 33; out[3] = (n and 0xFF).toByte(); out[4] = (n ushr 8).toByte()
        for (x in 0 until n) {
            for (k in 0 until 3) {
                var v = 0
                for (bit in 0 until 8) if (bitmap[x, from + k * 8 + bit]) v = v or (0x80 ushr bit)
                out[5 + 3 * x + k] = v.toByte()
            }
        }
        out[out.size - 1] = 0x0A
        return out
    }

    /** The whole image as 24-dot bit-image bands: reset, spacing 24, the bands, default spacing, [TEAR_OFF]. */
    fun bitImageJob(bitmap: MonoBitmap): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out.add(INIT + LINE_SPACING_24)
        val height = lastInkRow(bitmap) + 1
        var y = 0
        while (y < height) { out.add(bitImage24Band(bitmap, y)); y += 24 }
        out.add(LINE_SPACING_DEFAULT + TEAR_OFF)
        return out
    }

    /**
     * Plain text in the printer's own font (no image at all): reset, the ASCII [lines] each ended by LF, [TEAR_OFF].
     * Any character outside printable ASCII is sent as '?', so no byte can start a command.
     */
    fun textJob(lines: List<String>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        out.add(INIT)
        for (l in lines) out.add(ByteArray(l.length + 1) { i -> if (i == l.length) 0x0A else l[i].let { c -> if (c.code in 0x20..0x7E) c.code.toByte() else '?'.code.toByte() } })
        out.add(TEAR_OFF)
        return out
    }

    private fun lastInkRow(b: MonoBitmap): Int {
        for (y in b.height - 1 downTo 0) {
            val from = y * b.bytesPerRow
            for (i in from until from + b.bytesPerRow) if (b.data[i].toInt() != 0) return y
        }
        return -1
    }

    /** Paper roll status (DLE EOT 4): bits 5 and 6 set mean the roll is out. */
    fun paperOut(statusByte: Int): Boolean = statusByte and 0x60 == 0x60

    /** Paper near end (DLE EOT 4): bits 2 and 3. */
    fun paperNearEnd(statusByte: Int): Boolean = statusByte and 0x0C == 0x0C

    /** A valid DLE EOT reply has bit 1 set and bits 0, 4 and 7 clear. */
    fun isStatusByte(b: Int): Boolean = b and 0x93 == 0x12
}
