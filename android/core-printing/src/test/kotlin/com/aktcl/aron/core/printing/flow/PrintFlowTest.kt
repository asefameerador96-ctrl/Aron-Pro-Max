package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.render.Pbm
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F-SR-028, F-SR-073, F-SR-031, F-SR-066 and F-SR-015 (print part) against a simulated MP-58N. */
class PrintFlowTest {
    private class Store(var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")) : SavedPrinterStore {
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    /** In-memory ledger; the Room one (android-core) must behave the same. */
    class MemLedger : PrintLedger {
        val events = ArrayList<PrintEvent>()
        override suspend fun history(documentClientUuid: String) =
            events.filter { it.memoClientUuid == documentClientUuid || it.refClientUuid == documentClientUuid }
        override suspend fun record(event: PrintEvent) {
            require(events.none { it.clientUuid == event.clientUuid }) { "duplicate print_event ${event.clientUuid}" }
            events.add(event)
        }
        fun printedAt(memo: String) = events.firstOrNull { it.memoClientUuid == memo && it.outcome == PrintEvent.PRINTED }?.atEpochMs
    }

    private class Env(val sim: SimPrinter, val ledger: MemLedger, val printing: MemoPrinting)

    private fun TestScope.env(reprintMax: Int = 5, confirm: Boolean = true): Env {
        val sim = SimPrinter({ testScheduler.currentTime })
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        val ledger = MemLedger()
        var n = 0
        val printing = MemoPrinting(pm, { Fixtures.renderer() }, ledger, { "e-${++n}" }, { testScheduler.currentTime }, { reprintMax }, { confirm })
        return Env(sim, ledger, printing)
    }

    private val memoUuid = "11111111-1111-4111-8111-111111111111"

    private fun lastInk(b: com.aktcl.aron.core.printing.raster.MonoBitmap): Int {
        for (y in b.height - 1 downTo 0) if ((0 until b.bytesPerRow).any { b.data[y * b.bytesPerRow + it].toInt() != 0 }) return y
        return -1
    }

    @Test fun yesCommitsBeforePrintingAndThePrinterNeverDecidesTheSale() = runTest {
        val e = env()
        val order = ArrayList<String>()
        e.sim.on = false // printer switched off: the sale must still be saved
        val flow = SaveAndPrint({ order.add("commit"); CommittedMemo(memoUuid, Fixtures.seededSale) }, e.printing)
        flow.onPrintTapped()
        assertEquals(SaveAndPrint.Step.AskSave, flow.step.value)
        flow.onSaveAnswer(true)
        assertEquals(listOf("commit"), order)
        assertEquals(SaveAndPrint.Step.AskPrint, flow.step.value)
        flow.onPrintAnswer(true)
        assertEquals(SaveAndPrint.Step.PrintFailed(PrintFailure.UNREACHABLE), flow.step.value)
        assertEquals(PrintEvent.FAILED, e.ledger.events.single().outcome)
        assertNull("printed_at stays empty", e.ledger.printedAt(memoUuid))
        // Switch on and retry: prints, asks "ছাপা ঠিক আছে?", Yes marks the memo printed.
        e.sim.on = true
        flow.onRetry()
        assertEquals(SaveAndPrint.Step.AskReadable, flow.step.value)
        flow.onReadableAnswer(true)
        assertEquals(SaveAndPrint.Step.Done(printed = true), flow.step.value)
        assertEquals(listOf("commit"), order) // committed exactly once
        val printed = e.ledger.events.last()
        assertEquals("memo", printed.documentKind)
        assertEquals(true, printed.userConfirmed)
        assertTrue(e.ledger.printedAt(memoUuid) != null)
        assertEquals(1, e.sim.sessions.count { it.isNotEmpty() })
    }

    @Test fun noOnTheFirstDialogSavesNothingAndNoOnTheSecondLeavesItUnprinted() = runTest {
        val e = env()
        var commits = 0
        val flow = SaveAndPrint({ commits++; CommittedMemo(memoUuid, Fixtures.seededSale) }, e.printing)
        flow.onPrintTapped()
        flow.onSaveAnswer(false)
        assertEquals(SaveAndPrint.Step.Idle, flow.step.value)
        assertEquals(0, commits)
        flow.onPrintTapped()
        flow.onSaveAnswer(true)
        flow.onSaveAnswer(true) // double tap
        assertEquals(1, commits)
        flow.onPrintAnswer(false)
        assertEquals(SaveAndPrint.Step.Done(printed = false), flow.step.value)
        assertTrue(e.ledger.events.isEmpty())
        assertTrue(e.sim.sessions.isEmpty())
    }

    @Test fun reprintsCarryTheMarkerCountAndStopAtTheLimit() = runTest {
        val e = env(reprintMax = 2)
        val kinds = ArrayList<String>()
        repeat(3) {
            val a = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
            e.printing.confirm(a, true)
            kinds.add(e.ledger.events.last().documentKind)
        }
        assertEquals(listOf("memo", "memo_reprint", "memo_reprint"), kinds)
        assertEquals(PrintAttempt.LimitReached, e.printing.printMemo(memoUuid, Fixtures.seededSale))
        assertEquals(listOf(1, 2, 3), e.ledger.events.map { it.printCount })
        // The paper: original without marker, reprints with it (taller by the marker lines).
        val heights = e.sim.sessions.flatten().size
        val original = lastInk(Fixtures.renderer().memo(Fixtures.seededSale).bitmap) + 1
        val reprint1 = lastInk(Fixtures.renderer().memo(Fixtures.seededSale.copy(reprintNo = 1)).bitmap) + 1
        val reprint2 = lastInk(Fixtures.renderer().memo(Fixtures.seededSale.copy(reprintNo = 2)).bitmap) + 1
        assertTrue(reprint1 > original)
        assertEquals(original + reprint1 + reprint2, heights)
    }

    @Test fun notReadableIsFailedUserAndTheNextPrintHasNoMarkerAndDoesNotCount() = runTest {
        val e = env(reprintMax = 0)
        val a = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(a, false)
        e.printing.confirm(a, false) // double tap records once
        assertEquals(1, e.ledger.events.size)
        assertEquals(PrintEvent.FAILED_USER, e.ledger.events[0].outcome)
        assertEquals(false, e.ledger.events[0].userConfirmed)
        assertNull(e.ledger.printedAt(memoUuid))
        // Even with reprint_max = 0 the next print is allowed: it is still the original, without the marker.
        val b = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(b, true)
        assertEquals("memo", e.ledger.events.last().documentKind)
        val original = lastInk(Fixtures.renderer().memo(Fixtures.seededSale).bitmap) + 1
        assertEquals(2 * original, e.sim.sessions.flatten().size)
        assertEquals(PrintAttempt.LimitReached, e.printing.printMemo(memoUuid, Fixtures.seededSale))
    }

    @Test fun editedMemoPrintsSupersedesAndItsOwnNumber() = runTest {
        val e = env()
        val edited = Fixtures.seededSale.copy(kind = "edited_memo", memoNo = "sr001-261007-0043", supersedesMemoNo = "sr001-261007-0042")
        val a = e.printing.printMemo("22222222-2222-4222-8222-222222222222", edited) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(a, true)
        // Paper equals the reviewed golden that shows "প্রতিস্থাপিত মেমো: sr001-261007-0042" and the new number.
        val golden = Pbm.decode(File("src/test/resources/goldens/memo_edited.pbm").let { if (it.isFile) it else File("android/core-printing/src/test/resources/goldens/memo_edited.pbm") }.readBytes())
        val rows = e.sim.sessions.single()
        assertEquals(lastInk(golden) + 1, rows.size)
        rows.forEachIndexed { y, r -> assertTrue("row $y", r.contentEquals(golden.data.copyOfRange(y * 48, y * 48 + 48))) }
    }

    @Test fun withoutConfirmationAPrintIsRecordedAtOnce() = runTest {
        val e = env(confirm = false)
        assertEquals(PrintAttempt.Done, e.printing.printMemo(memoUuid, Fixtures.seededSale))
        assertEquals(null, e.ledger.events.single().userConfirmed)
        assertEquals(PrintEvent.PRINTED, e.ledger.events.single().outcome)
    }

    @Test fun stockSlipPrintsAndRecordsItsStockFamily() = runTest {
        val e = env()
        val stock = "33333333-3333-4333-8333-333333333333"
        val a = e.printing.printStockSlip(stock, Fixtures.stockSlip) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(a, true)
        val ev = e.ledger.events.single()
        assertEquals("stock_slip", ev.documentKind)
        assertEquals(stock, ev.refClientUuid)
        assertNull(ev.memoClientUuid)
        val p = ev.payload()
        assertEquals(setOf("document_kind", "memo_client_uuid", "ref_client_uuid", "print_count", "outcome", "user_confirmed", "template_version", "printer_model"), p.keys)
        assertFalse(e.sim.overflowed)
    }
}
