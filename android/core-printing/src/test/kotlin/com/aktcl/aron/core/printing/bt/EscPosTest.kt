package com.aktcl.aron.core.printing.bt

import com.aktcl.aron.core.printing.escpos.EscPos
import com.aktcl.aron.core.printing.raster.MonoBitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosTest {
    @Test fun rasterBandsCoverTheImageAndTrimTrailingWhite() {
        val b = MonoBitmap(384, 100)
        b.fillRect(0, 0, 384, 1)
        b.fillRect(10, 49, 20, 50)
        val cmds = EscPos.job(b, bandRows = 24)
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0x1B, 0x33, 0), cmds.first())
        assertArrayEquals(byteArrayOf(0x1B, 0x64, 3), cmds.last())
        val bands = cmds.drop(1).dropLast(1)
        assertEquals(listOf(24, 24, 2), bands.map { (it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8) })
        bands.forEach { assertEquals(48, it[4].toInt()); assertEquals(0, it[5].toInt()); assertEquals(8 + 48 * ((it[6].toInt() and 0xFF)), it.size) }
    }

    @Test fun blankImageSendsNoRaster() {
        assertEquals(2, EscPos.job(MonoBitmap(384, 50)).size)
    }

    @Test fun statusBits() {
        assertTrue(EscPos.isStatusByte(0x12))
        assertTrue(EscPos.isStatusByte(0x72))
        assertFalse(EscPos.isStatusByte(0x41))
        assertTrue(EscPos.paperOut(0x72))
        assertFalse(EscPos.paperOut(0x12))
        assertTrue(EscPos.paperNearEnd(0x1E))
    }
}
