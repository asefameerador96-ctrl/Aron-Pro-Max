package com.aktcl.aron.core.printing.bt

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.raster.MonoBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N-019 and F-SR-013 against a simulated MP-58N in virtual time. */
class PrinterManagerTest {
    private class Store(var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")) : SavedPrinterStore {
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    private val memo: MonoBitmap by lazy { Fixtures.renderer().memo(Fixtures.fortyLines).bitmap }

    /** The rows a correct print puts on paper: the bitmap without its trailing white rows. */
    private fun expectedRows(b: MonoBitmap): List<ByteArray> {
        var h = b.height
        while (h > 0 && (0 until b.bytesPerRow).all { b.data[(h - 1) * b.bytesPerRow + it].toInt() == 0 }) h--
        return (0 until h).map { b.data.copyOfRange(it * b.bytesPerRow, (it + 1) * b.bytesPerRow) }
    }

    private fun assertWhole(rows: List<ByteArray>, b: MonoBitmap) {
        val want = expectedRows(b)
        assertEquals(want.size, rows.size)
        want.indices.forEach { assertArrayEquals("row $it", want[it], rows[it]) }
    }

    private fun TestScope.setup(store: Store = Store(), scope: CoroutineScope = backgroundScope): Pair<SimPrinter, PrinterManager> {
        val sim = SimPrinter({ testScheduler.currentTime })
        val m = PrinterManager(sim.factory(), store, scope, nowMs = { testScheduler.currentTime })
        return sim to m
    }

    @Test fun fortyLineMemoPrintsWholeWithoutOverflow() = runTest {
        val (sim, m) = setup()
        assertTrue(memo.height > 1200)
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", memo)))
        assertFalse("receive buffer overflowed (garbage on paper)", sim.overflowed)
        assertWhole(sim.sessions.single(), memo)
        assertEquals(PrinterState.Connected, m.state.value)
    }

    @Test fun switchedOffMidPrintFailsThenRetryPrintsTheWholeMemoOnce() = runTest {
        val (sim, m) = setup()
        sim.switchOffAtByte = 30_000
        assertEquals(PrintOutcome.Failed(PrintFailure.DISCONNECTED), m.print(PrintJob("m1", memo)))
        assertEquals(PrinterState.Off, m.state.value)
        assertFalse(m.canPrint)
        val partial = sim.sessions[0].size
        assertTrue(partial in 1 until expectedRows(memo).size)

        sim.on = true
        sim.switchOffAtByte = Long.MAX_VALUE
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", memo)))
        assertEquals(2, sim.sessions.size)
        assertWhole(sim.sessions[1], memo)
        // The same job again prints nothing: one memo on paper, not two.
        val bytes = sim.totalBytes
        assertEquals(PrintOutcome.AlreadyPrinted, m.print(PrintJob("m1", memo)))
        assertEquals(bytes, sim.totalBytes)
        assertFalse(sim.overflowed)
    }

    @Test fun printerOffIsUnreachableAndNeverThrows() = runTest {
        val (sim, m) = setup()
        sim.on = false
        assertEquals(PrintOutcome.Failed(PrintFailure.UNREACHABLE), m.print(PrintJob("m1", memo)))
        assertEquals(PrinterState.Off, m.state.value)
        assertEquals(PrintOutcome.Failed(PrintFailure.NO_PRINTER), setup(Store(null)).second.print(PrintJob("m2", memo)))
    }

    @Test fun emptyRollBeforePrintSendsNoImage() = runTest {
        val (sim, m) = setup()
        sim.paperRows = 0
        assertEquals(PrintOutcome.Failed(PrintFailure.PAPER_OUT), m.print(PrintJob("m1", memo)))
        assertEquals(PrinterState.PaperOut, m.state.value)
        assertTrue("retry stays possible", m.canPrint)
        assertTrue(sim.totalBytes < 10)
        sim.paperRows = Int.MAX_VALUE
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", memo)))
        assertWhole(sim.sessions.last(), memo)
    }

    @Test fun rollRunningOutMidPrintFailsAndRetryPrintsWhole() = runTest {
        val (sim, m) = setup()
        sim.paperRows = 500
        assertEquals(PrintOutcome.Failed(PrintFailure.PAPER_OUT), m.print(PrintJob("m1", memo)))
        sim.paperRows = Int.MAX_VALUE
        val before = sim.sessions.single().size
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", memo)))
        // Same link: the second print's rows follow the partial first one.
        assertWhole(sim.sessions.single().drop(before), memo)
    }

    @Test fun printerWithoutStatusRepliesStillPrints() = runTest {
        val (sim, m) = setup()
        sim.answersStatus = false
        assertEquals(PrintOutcome.Printed, m.print(PrintJob("m1", memo)))
        assertWhole(sim.sessions.single(), memo)
    }

    @Test fun stuckWriteTimesOutAsDisconnected() = runTest {
        val (sim, m) = setup()
        sim.hangWrites = true
        assertEquals(PrintOutcome.Failed(PrintFailure.DISCONNECTED), m.print(PrintJob("m1", memo)))
        assertEquals(PrinterState.Off, m.state.value)
    }

    @Test fun jobsPrintOneAtATime() = runTest {
        val (sim, m) = setup()
        val small = Fixtures.renderer().voidSlip(Fixtures.voidSlip).bitmap
        val a = async { m.print(PrintJob("a", memo)) }
        val b = async { m.print(PrintJob("b", small)) }
        assertEquals(PrintOutcome.Printed, a.await())
        assertEquals(PrintOutcome.Printed, b.await())
        val rows = sim.sessions.single()
        val first = expectedRows(memo)
        assertWhole(rows.take(first.size), memo)
        assertWhole(rows.drop(first.size), small)
    }

    @Test fun heldLinkShowsOffAtOnceWhenSwitchedOffAndReconnects() = runTest {
        val (sim, m) = setup()
        val release = m.hold()
        runCurrent()
        assertEquals(PrinterState.Connected, m.state.value)
        assertTrue(m.canPrint)
        sim.switchOff()
        runCurrent()
        assertEquals(PrinterState.Off, m.state.value)
        assertFalse(m.canPrint)
        sim.on = true
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(PrinterState.Connected, m.state.value)
        // Held for ten minutes: the link stays up.
        advanceTimeBy(600_000)
        assertEquals(PrinterState.Connected, m.state.value)
        // Released: idle disconnect after 120 s (cfg.print.disconnect_idle_s).
        release()
        advanceTimeBy(119_000)
        assertEquals(PrinterState.Connected, m.state.value)
        advanceTimeBy(2_000)
        assertEquals(PrinterState.Off, m.state.value)
        assertEquals(2, sim.connects)
    }

    @Test fun reconnectKeepsTryingOnlyWhileAScreenHoldsTheLink() = runTest {
        val (sim, m) = setup()
        val release = m.hold()
        runCurrent()
        sim.switchOff()
        advanceTimeBy(120_000)
        assertEquals(PrinterState.Off, m.state.value)
        val tries = sim.attempts
        assertTrue("kept trying while held: $tries", tries >= 5)
        release()
        advanceTimeBy(600_000)
        assertTrue("stops after release", sim.attempts <= tries + 1)
        assertEquals(1, sim.connects)
    }

    @Test fun screenOpenedWithPrinterOffConnectsWhenItIsSwitchedOn() = runTest {
        val (sim, m) = setup()
        sim.on = false
        m.hold()
        runCurrent()
        assertEquals(PrinterState.Off, m.state.value)
        sim.on = true
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(PrinterState.Connected, m.state.value)
    }

    @Test fun selectingAndForgettingAPrinter() = runTest {
        val store = Store(null)
        val (_, m) = setup(store)
        assertEquals(PrinterState.NoPrinter, m.state.value)
        assertFalse(m.canPrint)
        assertTrue(m.select(SavedPrinter("AA:BB:CC:DD:EE:FF", "MP-58N")))
        assertEquals(PrinterState.Connected, m.state.value)
        assertEquals("AA:BB:CC:DD:EE:FF", store.p?.address)
        m.forget()
        assertEquals(PrinterState.NoPrinter, m.state.value)
        assertEquals(null, store.p)
    }
}
