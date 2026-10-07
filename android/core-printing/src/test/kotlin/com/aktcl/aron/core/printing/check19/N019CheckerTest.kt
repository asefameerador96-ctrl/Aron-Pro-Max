package com.aktcl.aron.core.printing.check19

import com.aktcl.aron.core.printing.bt.PrintJob
import com.aktcl.aron.core.printing.bt.PrintOutcome
import com.aktcl.aron.core.printing.bt.PrintPacing
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.raster.MonoBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Independent checker for N-019. Each test states a defect; it fails while the defect is present. */
class N019CheckerTest {
    private class Store(var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")) : SavedPrinterStore {
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    /** 384-dot memo-sized image, every row inked (rows differ so misalignment is visible). */
    private fun image(rows: Int = 1300): MonoBitmap = MonoBitmap(384, rows).also { b ->
        for (y in 0 until rows) for (x in 0 until b.bytesPerRow) b.data[y * b.bytesPerRow + x] = ((y * 7 + x) % 200 + 32).toByte()
    }

    /**
     * Defect 1 (blocking): the printer is switched off near the end of the job. On a real phone the RFCOMM link is
     * only declared dead after the supervision timeout, so the last writes still land in the stack's buffer and the
     * reader sees nothing. The printer answered the first DLE EOT on this link, so a missing answer to the closing
     * query means the printer is gone; PrinterManager treats it as "fine" and reports Printed (and caches it, so the
     * retry returns AlreadyPrinted): a half memo reported as printed.
     */
    @Test fun silentDeathAfterLastWriteIsNotReportedAsPrinted() = runTest {
        val replies = Channel<Int>(Channel.UNLIMITED)
        var alive = true
        var received = 0L
        val t = object : PrinterTransport {
            override suspend fun connect() {}
            override suspend fun write(bytes: ByteArray) {
                if (!alive) return // swallowed by the dead link, no error yet
                for (i in bytes.indices) {
                    received++
                    if (received >= 50_000) { alive = false; return }
                    if (bytes[i].toInt() == 0x10 && i + 1 < bytes.size && bytes[i + 1].toInt() == 0x04) replies.trySend(0x12)
                }
            }
            override suspend fun read(): Int = replies.receive()
            override fun close() { replies.close() }
        }
        val m = PrinterManager({ t }, Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        val img = image()
        assertTrue(img.bytesPerRow * img.height > 55_000)
        val out = m.print(PrintJob("m1", img))
        assertNotEquals("printer died mid-job but print reported Printed", PrintOutcome.Printed, out)
    }

    /** Defect 2 (minor): pacing with rowsPerSecond = 0 (a bad config value) throws ArithmeticException out of print(). */
    @Test fun badPacingNeverThrows() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val m = PrinterManager(sim.factory(), Store(), backgroundScope, pacing = PrintPacing(rowsPerSecond = 0), nowMs = { testScheduler.currentTime })
        val r = runCatching { m.print(PrintJob("m1", image(100))) }
        assertTrue("print threw ${r.exceptionOrNull()}", r.isSuccess)
    }

    /**
     * Defect 3 (minor, test realism): SimPrinter computes its drained bytes from connect time, so a link held idle
     * for a minute gets a minute of free "drain" credit and never flags overflow, even for a completely unpaced
     * 60 KB burst into its 8 KB buffer. Tests could pass while the real printer prints garbage.
     */
    @Test fun simPrinterFlagsUnpacedBurstAfterIdleHold() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val unpaced = PrintPacing(rowsPerSecond = 1_000_000, bufferBytes = Int.MAX_VALUE / 2, chunkBytes = 65_536)
        val m = PrinterManager(sim.factory(), Store(), backgroundScope, pacing = unpaced, nowMs = { testScheduler.currentTime })
        val release = m.hold()
        runCurrent()
        advanceTimeBy(60_000)
        // Writes are instantaneous in virtual time: ~60 KB arrive at once into an 8 KB buffer.
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", image())))
        release()
        assertTrue("60 KB burst into an 8 KB buffer was not flagged", sim.overflowed)
    }

    /**
     * Defect 4 (blocking-risk, device-pending): the link drops mid-band (out of range) while the printer stays on.
     * The printer's parser is still inside a `GS v 0` waiting for the rest of the band; the retry starts with
     * ESC @ + ESC 3 0 + a new header, which the printer swallows as raster data, so the retry prints garbage or a
     * shifted image. SimPrinter hides this because every Link gets a fresh parser.
     */
    @Test fun retryAfterRangeDropMidBandPrintsWholeMemo() = runTest {
        val p = PersistentPrinter()
        val m = PrinterManager({ p.link() }, Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        val img = image()
        p.dropAtByte = 20_000 + 500 // inside a band
        assertTrue(m.print(PrintJob("m1", img)) is PrintOutcome.Failed)
        p.dropAtByte = Long.MAX_VALUE
        p.rows.clear()
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", img)))
        assertEquals("garbage bytes on retry", 0, p.garbage)
        // Builder's fix: the stranded band of the failed attempt is finished first (its remaining rows, at most one
        // band of 24), then the whole memo follows, row for row.
        val extra = p.rows.size - img.height
        assertTrue("stranded rows $extra", extra in 0..24)
        for (y in 0 until img.height) assertTrue("row $y", p.rows[extra + y].contentEquals(img.data.copyOfRange(y * 48, y * 48 + 48)))
    }

    /** A printer whose ESC/POS parser survives a dropped Bluetooth link (power stays on). */
    private class PersistentPrinter {
        var dropAtByte = Long.MAX_VALUE
        var total = 0L
        var garbage = 0
        val rows = ArrayList<ByteArray>()
        private val pending = ByteArrayOutputStream()
        private var parsed = 0

        fun link(): PrinterTransport = object : PrinterTransport {
            val replies = Channel<Int>(Channel.UNLIMITED)
            var open = false
            val dead = CompletableDeferred<Unit>()
            override suspend fun connect() { open = true }
            override suspend fun write(bytes: ByteArray) {
                if (!open) throw IOException("down")
                for (b in bytes) {
                    if (total >= dropAtByte) { open = false; replies.close(IOException("range")); throw IOException("range") }
                    total++
                    pending.write(b.toInt())
                }
                parse { replies.trySend(0x12) }
            }
            override suspend fun read(): Int = try { replies.receive() } catch (e: Exception) { throw IOException(e) }
            override fun close() { open = false; replies.close() }
        }

        private fun parse(status: () -> Unit) {
            val d = pending.toByteArray()
            var i = parsed
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                when {
                    b == 0x1B && i + 1 < d.size && d[i + 1].toInt() == 0x40 -> i += 2
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x33 -> i += 3
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x64 -> i += 3
                    b == 0x10 && i + 2 < d.size && d[i + 1].toInt() == 0x04 -> { status(); i += 3 }
                    b == 0x1D && i + 7 < d.size && d[i + 1].toInt() == 0x76 -> {
                        val w = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8)
                        val h = (d[i + 6].toInt() and 0xFF) or ((d[i + 7].toInt() and 0xFF) shl 8)
                        if (i + 8 + w * h > d.size) break
                        for (r in 0 until h) rows.add(d.copyOfRange(i + 8 + r * w, i + 8 + (r + 1) * w))
                        i += 8 + w * h
                    }
                    b == 0x00 -> i++ // NUL is ignored by ESC/POS printers (builder's note: the padding relies on it)
                    b == 0x1B || b == 0x10 || b == 0x1D -> break
                    else -> { garbage++; i++ }
                }
            }
            parsed = i
        }
    }
}
