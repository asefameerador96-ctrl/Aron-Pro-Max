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
     * the printer's receive buffer, so a slow printer never drops bytes mid-command), then a feed to the tear bar.
     * Trailing white rows are trimmed to save paper and time.
     */
    fun job(bitmap: MonoBitmap, bandRows: Int = 24, feed: Int = 3): List<ByteArray> {
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
        out.add(feedLines(feed))
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
