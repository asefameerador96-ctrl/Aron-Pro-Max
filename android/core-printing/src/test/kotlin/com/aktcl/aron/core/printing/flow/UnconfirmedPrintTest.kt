package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-PRINT-02: every byte went to a slow printer, then it was switched off before its (late) status answer. The paper
 * may be out, so the seller is asked; a memo is never printed twice unmarked and never recorded failed after it came out.
 */
class UnconfirmedPrintTest {
    private class Store(var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")) : SavedPrinterStore {
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    private class Env(val sim: SimPrinter, val ledger: PrintFlowTest.MemLedger, val pm: PrinterManager, val printing: MemoPrinting)

    private val memoUuid = "33333333-3333-4333-8333-333333333333"

    private fun TestScope.env(confirm: Boolean): Env {
        // a slow printer that answers DLE EOT only after printing; a buffer big enough that only the answer is late
        val sim = SimPrinter({ testScheduler.currentTime }, bufferBytes = 1 shl 20, rowsPerSecond = 120)
        sim.statusAfterDrain = true
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        val ledger = PrintFlowTest.MemLedger()
        val printing = MemoPrinting(pm, { Fixtures.renderer() }, ledger, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, { 5 }, { confirm })
        return Env(sim, ledger, pm, printing)
    }

    /** Prints the seeded memo and switches the printer off once all of it was sent, before the closing answer. */
    private suspend fun TestScope.printThenSwitchOffBeforeTheAnswer(e: Env): PrintAttempt {
        val rows = Fixtures.renderer().memo(Fixtures.seededSale).bitmap.height
        val a = async { e.printing.printMemo(memoUuid, Fixtures.seededSale) }
        // sent at the pacing model's 300 rows/s; the printer needs rows/120 s and answers only then
        advanceTimeBy(rows * 1000L / 300 + 500)
        runCurrent()
        assertTrue("the answer must still be pending", rows * 1000L / 120 > rows * 1000L / 300 + 500)
        e.sim.switchOff()
        val r = a.await()
        e.sim.on = true
        e.sim.statusAfterDrain = false
        return r
    }

    @Test fun unconfirmedAlwaysAsksEvenWithConfirmationOffAndThePaperIsMarkedOut() = runTest {
        val e = env(confirm = false)
        val a = printThenSwitchOffBeforeTheAnswer(e)
        assertTrue("asked, not failed: $a", a is PrintAttempt.AwaitingConfirmation)
        assertTrue(e.ledger.events.isEmpty())
        assertTrue("paper_out is stored before the answer", e.ledger.pending().single().paperOut)
    }

    @Test fun yesCountsTheCopySoTheNextPrintIsAMarkedReprint() = runTest {
        val e = env(confirm = true)
        val a = printThenSwitchOffBeforeTheAnswer(e) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(a, true)
        assertEquals(PrintEvent.PRINTED, e.ledger.events.single().outcome)
        val b = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(b, true)
        assertEquals(listOf("memo", "memo_reprint"), e.ledger.events.map { it.documentKind })
    }

    @Test fun noMeansNothingCameOutSoTheNextPrintIsTheOriginalAgain() = runTest {
        val e = env(confirm = true)
        val a = printThenSwitchOffBeforeTheAnswer(e) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(a, false)
        assertEquals(PrintEvent.FAILED_USER, e.ledger.events.single().outcome)
        assertNull(e.ledger.printedAt(memoUuid))
        val b = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(b, true)
        assertEquals("memo", e.ledger.events.last().documentKind)
    }

    @Test fun killedBeforeTheAnswerRecoverRecordsItPrintedNeverASecondOriginal() = runTest {
        val e = env(confirm = true)
        printThenSwitchOffBeforeTheAnswer(e)
        // a new process: the old one's in-flight set is gone
        val after = MemoPrinting(e.pm, { Fixtures.renderer() }, e.ledger, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, { 5 }, { false })
        after.recover()
        assertEquals(PrintEvent.PRINTED, e.ledger.events.single().outcome)
        assertNull(e.ledger.events.single().userConfirmed)
        assertEquals(PrintAttempt.Done, after.printMemo(memoUuid, Fixtures.seededSale))
        assertEquals("memo_reprint", e.ledger.events.last().documentKind)
    }

    @Test fun saleFlowShowsTheReadableQuestionNotRetry() = runTest {
        val e = env(confirm = false)
        val flow = SaveAndPrint({ CommittedMemo(memoUuid, Fixtures.seededSale) }, e.printing)
        flow.onPrintTapped()
        flow.onSaveAnswer(true)
        val rows = Fixtures.renderer().memo(Fixtures.seededSale).bitmap.height
        val p = async { flow.onPrintAnswer(true) }
        advanceTimeBy(rows * 1000L / 300 + 500)
        runCurrent()
        e.sim.switchOff()
        p.await()
        assertEquals(SaveAndPrint.Step.AskReadable, flow.step.value)
        flow.onReadableAnswer(true)
        assertEquals(SaveAndPrint.Step.Done(printed = true), flow.step.value)
    }
}
