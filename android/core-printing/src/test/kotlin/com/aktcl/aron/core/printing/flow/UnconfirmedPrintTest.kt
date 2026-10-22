package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    /** Default pacing (3 KB buffer, the printer keeps up): most of the memo is out while the loop still runs. */
    private fun TestScope.defaultEnv(): Env {
        val sim = SimPrinter({ testScheduler.currentTime })
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        val ledger = PrintFlowTest.MemLedger()
        val printing = MemoPrinting(pm, { Fixtures.renderer() }, ledger, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, { 5 }, { true })
        return Env(sim, ledger, pm, printing)
    }

    private fun memoBytes(): Long = com.aktcl.aron.core.printing.escpos.EscPos.job(Fixtures.renderer().memo(Fixtures.seededSale).bitmap).sumOf { it.size }.toLong()

    /** Checker T1 (2bea9ecd) #1: Bluetooth lost at 90% of the memo, inside the write loop. */
    @Test fun aDropAt90PercentInsideTheLoopAsksTheSellerAndNoMeansTheOriginalAgain() = runTest {
        val e = defaultEnv()
        e.sim.switchOffAtByte = 3 + memoBytes() * 9 / 10
        val a = e.printing.printMemo(memoUuid, Fixtures.seededSale)
        assertTrue("asked, not failed: $a", a is PrintAttempt.AwaitingConfirmation)
        assertTrue(e.ledger.pending().single().paperOut)
        e.sim.on = true
        e.sim.switchOffAtByte = Long.MAX_VALUE
        e.printing.confirm(a as PrintAttempt.AwaitingConfirmation, false)
        val b = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(b, true)
        assertEquals(listOf(PrintEvent.FAILED_USER, PrintEvent.PRINTED), e.ledger.events.map { it.outcome })
        assertEquals("memo", e.ledger.events.last().documentKind)
    }

    @Test fun aDropBeforeTheFirstBandIsAPlainFailureNothingIsOnPaper() = runTest {
        val e = defaultEnv()
        e.sim.switchOffAtByte = 3 + 3 // inside ESC @ / ESC 3, before any image band
        val a = e.printing.printMemo(memoUuid, Fixtures.seededSale)
        assertTrue("$a", a is PrintAttempt.Failed)
        assertEquals(PrintEvent.FAILED, e.ledger.events.single().outcome)
        assertTrue(e.sim.sessions.single().isEmpty())
    }

    /** Checker T1 (2bea9ecd) #2: the app killed mid-print, after ink started: recover() counts it, never a second original. */
    @Test fun killedMidPrintAfterInkStartedRecoversAsPrinted() = runTest {
        val e = defaultEnv()
        val job = launch { e.printing.printMemo(memoUuid, Fixtures.seededSale) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("printing", e.sim.sessions.single().isNotEmpty())
        job.cancel()
        job.join()
        val after = MemoPrinting(e.pm, { Fixtures.renderer() }, e.ledger, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, { 5 }, { false })
        after.recover()
        assertEquals(PrintEvent.PRINTED, e.ledger.events.single().outcome)
        assertEquals(PrintAttempt.Done, after.printMemo(memoUuid, Fixtures.seededSale))
        assertEquals("memo_reprint", e.ledger.events.last().documentKind)
    }

    /** Checker T1 (3adf7b81) #6: the roll ran out after ink started, through MemoPrinting. */
    @Test fun rollOutAfterInkAsksTheSellerKeepsPrintEnabledAndNoMeansTheOriginalAgain() = runTest {
        val e = defaultEnv()
        e.sim.paperRows = 300
        val a = e.printing.printMemo(memoUuid, Fixtures.seededSale)
        assertTrue("asked: $a", a is PrintAttempt.AwaitingConfirmation)
        assertEquals(com.aktcl.aron.core.printing.bt.PrinterState.PaperOut, e.pm.state.value)
        assertTrue(e.pm.canPrint)
        e.printing.confirm(a as PrintAttempt.AwaitingConfirmation, false)
        assertNull(e.ledger.printedAt(memoUuid))
        e.sim.paperRows = Int.MAX_VALUE
        val b = e.printing.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        e.printing.confirm(b, true)
        assertEquals("memo", e.ledger.events.last().documentKind)
    }

    /** #1: a ledger that cannot store paper_out stops the job before any ink: FAILED is then true. */
    @Test fun aLedgerThatCannotMarkThePaperStopsBeforeInk() = runTest {
        val e = defaultEnv()
        val broken = object : PrintLedger by e.ledger {
            override suspend fun savePending(job: PendingPrint) { if (job.paperOut) throw java.io.IOException("disk full") else e.ledger.savePending(job) }
        }
        val printing = MemoPrinting(e.pm, { Fixtures.renderer() }, broken, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, { 5 }, { true })
        val a = printing.printMemo(memoUuid, Fixtures.seededSale)
        assertTrue("$a", a is PrintAttempt.Failed)
        assertTrue("nothing on paper", e.sim.sessions.single().isEmpty())
        assertEquals(PrintEvent.FAILED, e.ledger.events.single().outcome)
    }
}
