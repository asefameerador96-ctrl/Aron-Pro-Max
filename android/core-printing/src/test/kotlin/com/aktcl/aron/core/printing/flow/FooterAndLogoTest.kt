package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.bt.ImageMode
import com.aktcl.aron.core.printing.bt.PrintJob
import com.aktcl.aron.core.printing.bt.PrintOutcome
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.escpos.EscPos
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.render.PrintLogo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-PRINT-09 (owner 2026-10-24): an inch of paper after every document, and the Aron mark above the header. */
class FooterAndLogoTest {
    private class Store(var p: SavedPrinter?) : SavedPrinterStore {
        val logos = HashMap<String, Boolean>()
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer; printer?.let { logos[it.address] = it.printLogo } }
        override fun rememberedLogo(address: String) = logos[address]
    }

    private fun TestScope.env(printer: SavedPrinter): Triple<SimPrinter, PrinterManager, MemoPrinting> {
        val sim = SimPrinter({ testScheduler.currentTime })
        val pm = PrinterManager(sim.factory(), Store(printer), backgroundScope, nowMs = { testScheduler.currentTime })
        val p = MemoPrinting(
            pm, { Fixtures.renderer() }, PrintFlowTest.MemLedger(), { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime },
            confirmAfterPrint = { false }, renderContext = kotlin.coroutines.EmptyCoroutineContext,
        )
        return Triple(sim, pm, p)
    }

    /** The rows a printer puts on paper for [b]: the bitmap without its trailing white rows. */
    private fun rows(b: MonoBitmap): List<ByteArray> {
        var h = b.height
        while (h > 0 && (0 until b.bytesPerRow).all { b.data[(h - 1) * b.bytesPerRow + it].toInt() == 0 }) h--
        return (0 until h).map { b.data.copyOfRange(it * b.bytesPerRow, (it + 1) * b.bytesPerRow) }
    }

    private fun assertPaper(want: MonoBitmap, got: List<ByteArray>) {
        val w = rows(want)
        assertEquals(w.size, got.size)
        w.indices.forEach { assertArrayEquals("row $it", w[it], got[it]) }
    }

    private val memo = "11111111-1111-4111-8111-111111111111"

    @Test fun everyDocumentEndsWithAnInchOfPaperInBothImageModes() = runTest {
        for (mode in ImageMode.entries) {
            val (sim, pm, _) = env(SavedPrinter("00:11:22:33:44:55", "MP-58N", mode, printLogo = false))
            assertEquals(PrintOutcome.Printed, pm.print(PrintJob("j-$mode", Fixtures.renderer().memo(Fixtures.seededSale).bitmap)))
            assertTrue("$mode: ${sim.trailingFeedDots} dots after the last row", sim.trailingFeedDots >= 203)
            assertTrue("no default-spacing leftover on top of the tear-off", sim.trailingFeedDots <= EscPos.TEAR_OFF_DOTS)
            assertFalse(sim.overflowed)
        }
    }

    @Test fun theOldFeedLeftNoPaperAfterARasterJob() {
        // why the owner's last line was cut: the raster job's line spacing 0 times ESC d 3 is no feed at all
        val sim = SimPrinter({ 0L })
        val link = sim.factory().open("00:11:22:33:44:55")
        kotlinx.coroutines.runBlocking {
            link.connect()
            link.write(EscPos.INIT + EscPos.LINE_SPACING_0 + EscPos.rasterBand(MonoBitmap(384, 1).apply { set(0, 0) }, 0, 1) + EscPos.feedLines(3))
        }
        assertEquals(0, sim.trailingFeedDots)
    }

    @Test fun theMarkSitsAboveTheHeaderOfMemoStockSlipAndSummary() = runTest {
        val (sim, _, p) = env(SavedPrinter("00:11:22:33:44:55", "MP-58N"))
        assertEquals(PrintAttempt.Done, p.printMemo(memo, Fixtures.seededSale))
        assertEquals(PrintAttempt.Done, p.printStockSlip("22222222-2222-4222-8222-222222222222", Fixtures.stockSlip))
        assertEquals(PrintAttempt.Done, p.printDaySummary("33333333-3333-4333-8333-333333333333", Fixtures.daySummary))
        val r = Fixtures.renderer()
        val all = sim.sessions.flatten()
        val docs = listOf(r.memo(Fixtures.seededSale).bitmap, r.stockSlip(Fixtures.stockSlip).bitmap, r.daySummary(Fixtures.daySummary).bitmap)
        val want = docs.flatMap { rows(PrintLogo.onTop(it)) }
        assertEquals(want.size, all.size)
        want.indices.forEach { assertArrayEquals("row $it", want[it], all[it]) }
        val mark = PrintLogo.mark!!
        assertTrue("the mark adds its height and the gap", want.size == docs.sumOf { rows(it).size } + 3 * (mark.height + PrintLogo.GAP_DOTS))
    }

    @Test fun switchedOffThePaperIsTheTextHeaderAsBeforeAndTheChoiceIsKept() = runTest {
        val (sim, pm, p) = env(SavedPrinter("00:11:22:33:44:55", "MP-58N"))
        pm.setPrintLogo(false)
        assertFalse(pm.savedPrinter!!.printLogo)
        assertEquals(PrintAttempt.Done, p.printMemo(memo, Fixtures.seededSale))
        assertPaper(Fixtures.renderer().memo(Fixtures.seededSale).bitmap, sim.sessions.flatten())
        pm.select(SavedPrinter("00:11:22:33:44:55", "MP-58N")) // picked again: the choice stays
        assertFalse(pm.savedPrinter!!.printLogo)
        pm.select(SavedPrinter("AA:BB:CC:DD:EE:FF", "MP-58N")) // another printer starts with the mark
        assertTrue(pm.savedPrinter!!.printLogo)
        pm.select(SavedPrinter("00:11:22:33:44:55", "MP-58N")) // and the first one comes back without it
        assertFalse(pm.savedPrinter!!.printLogo)
    }

    @Test fun theBundledMarkIsSmallCentredAndReadable() {
        val mark = assertNotNullAnd(PrintLogo.mark)
        assertEquals(74, mark.width)
        assertEquals(112, mark.height)
        val ink = mark.blackDots().toDouble() / (mark.width * mark.height)
        assertTrue("ink $ink", ink in 0.15..0.75)
        val paper = MonoBitmap(384, 10)
        val out = PrintLogo.onTop(paper)
        assertEquals(10 + 112 + PrintLogo.GAP_DOTS, out.height)
        val xs = (0 until 384).filter { x -> (0 until 112).any { out[x, it] } }
        assertTrue("centred: ${xs.first()}..${xs.last()}", xs.first() >= (384 - 74) / 2 && xs.last() < (384 + 74) / 2 + 1)
        assertTrue("about 5.8 KB of raster", (out.height - 10) * 48 <= 6_000)
    }

    @Test fun aBadMarkLeavesThePaperAlone() {
        val paper = MonoBitmap(384, 10)
        assertSame(paper, PrintLogo.onTop(paper, null))
        assertSame(paper, PrintLogo.onTop(paper, MonoBitmap(400, 10)))
        assertSame(paper, PrintLogo.onTop(paper, MonoBitmap(64, 300)))
        for (bad in listOf("P1\n2 2\n0101", "P4\n8 2\n", "P4\n999 2\n" + "\u0000".repeat(250), "P4\nx 2\n")) {
            assertTrue(bad, runCatching { PrintLogo.decode(bad.toByteArray(Charsets.ISO_8859_1)) }.isFailure)
        }
        val ok = PrintLogo.decode("P4\n# comment\n8 2\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0x01))
        assertTrue(ok[0, 0] && ok[7, 1] && !ok[1, 0])
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull("logo.pbm is bundled", v); return v!! }
}
