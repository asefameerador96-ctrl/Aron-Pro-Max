package com.aktcl.aron.core.printing.checkflow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.flow.CommittedMemo
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.core.printing.flow.PrintEvent
import com.aktcl.aron.core.printing.flow.PrintFlowTest
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent checker: each test reproduces one defect found in F-SR-013 / F-SR-028 / F-SR-073 (core-printing). */
class PrintFlowCheckerTest {
    private class Store : SavedPrinterStore {
        var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    private val memoUuid = "11111111-1111-4111-8111-111111111111"

    private fun TestScope.printing(sim: SimPrinter, ledger: PrintFlowTest.MemLedger, prefix: String = "e"): MemoPrinting {
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        var n = 0
        return MemoPrinting(pm, { Fixtures.renderer() }, ledger, { "$prefix-${++n}" }, { testScheduler.currentTime })
    }

    /** F-SR-028: a commit that throws (disk full, constraint) must not leave the flow stuck on "Saving" forever. */
    @Test fun commitFailureDoesNotLeaveTheFlowStuckInSaving() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        var fail = true
        val flow = SaveAndPrint({ if (fail) error("db write failed") else CommittedMemo(memoUuid, Fixtures.seededSale) }, printing(sim, PrintFlowTest.MemLedger()))
        flow.onPrintTapped()
        runCatching { flow.onSaveAnswer(true) }
        assertNotEquals("stuck on the saving spinner, no way back", SaveAndPrint.Step.Saving, flow.step.value)
        fail = false
        flow.onPrintTapped()
        runCatching { flow.onSaveAnswer(true) }
        assertEquals(SaveAndPrint.Step.AskPrint, flow.step.value)
    }

    /**
     * docs/17 s9.4: the job is recorded when the paper is out ("done", printed_at on the first success); the
     * "ছাপা ঠিক আছে?" answer only adds user_confirmed / failed_user. Here nothing is recorded until the answer, so
     * a process death (or leaving the dialog) after a good print makes the next print a second unmarked original.
     */
    @Test fun paperOutButUnansweredLeavesNoRecordAndTheNextPrintIsASecondOriginal() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val ledger = PrintFlowTest.MemLedger()
        val first = printing(sim, ledger, "a").printMemo(memoUuid, Fixtures.seededSale)
        assertTrue(first is PrintAttempt.AwaitingConfirmation)
        // process dies here: the attempt object is lost, a fresh MemoPrinting starts on the same ledger.
        // Builder's fix: the job is persisted with paper out (printed_at set) before the question.
        assertTrue("a printed paper left no record", ledger.pending().single().paperOut)
        assertTrue(ledger.printedAt(memoUuid) != null)
        // Without recovery the next print is already a reprint (marker), never a second original ...
        val second = printing(sim, ledger, "b").printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        assertEquals(2, sim.sessions.count { it.isNotEmpty() })
        printing(sim, ledger, "c").confirm(second, true)
        assertEquals("memo_reprint", ledger.events.last().documentKind)
        // ... and recovery at start records the unanswered paper as printed without confirmation.
        printing(sim, ledger, "d").recover()
        assertTrue(ledger.pending().isEmpty())
        assertEquals(listOf("memo", "memo_reprint"), ledger.events.sortedBy { it.printCount }.map { it.documentKind })
    }

    /** If recording the answer fails once, the Yes/No dialog stays up but both buttons do nothing (answer lost). */
    @Test fun aFailedRecordOfTheReadableAnswerCanBeAnsweredAgain() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val mem = PrintFlowTest.MemLedger()
        var failOnce = true
        val ledger = object : com.aktcl.aron.core.printing.flow.PrintLedger {
            override suspend fun history(documentClientUuid: String) = mem.history(documentClientUuid)
            override suspend fun pending() = mem.pending()
            override suspend fun savePending(job: com.aktcl.aron.core.printing.flow.PendingPrint) = mem.savePending(job)
            override suspend fun record(event: PrintEvent) {
                if (failOnce) { failOnce = false; error("db write failed") }
                mem.record(event)
            }
        }
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        var n = 0
        val p = MemoPrinting(pm, { Fixtures.renderer() }, ledger, { "e-${++n}" }, { testScheduler.currentTime })
        val flow = SaveAndPrint({ CommittedMemo(memoUuid, Fixtures.seededSale) }, p)
        flow.onPrintTapped(); flow.onSaveAnswer(true); flow.onPrintAnswer(true)
        assertEquals(SaveAndPrint.Step.AskReadable, flow.step.value)
        runCatching { flow.onReadableAnswer(true) }
        runCatching { flow.onReadableAnswer(true) } // the seller taps Yes again
        assertEquals(SaveAndPrint.Step.Done(printed = true), flow.step.value)
        assertEquals(PrintEvent.PRINTED, mem.events.single().outcome)
    }

    /** Data dictionary: print_event.print_count = "number of copies printed so far"; failed attempts printed nothing. */
    @Test fun printCountDoesNotCountFailedAttemptsAsCopies() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val ledger = PrintFlowTest.MemLedger()
        val p = printing(sim, ledger)
        sim.on = false
        p.printMemo(memoUuid, Fixtures.seededSale)
        p.printMemo(memoUuid, Fixtures.seededSale)
        sim.on = true
        val a = p.printMemo(memoUuid, Fixtures.seededSale) as PrintAttempt.AwaitingConfirmation
        p.confirm(a, true)
        val printed = ledger.events.single { it.outcome == PrintEvent.PRINTED }
        assertEquals("memo", printed.documentKind)
        assertEquals("first copy on paper", 1, printed.printCount)
    }

    /** F-SR-013 "auto-reconnects": screen opened with the printer off, then the SR switches it on. */
    @Test fun heldScreenReconnectsWhenThePrinterIsSwitchedOnAfterOpening() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        sim.on = false
        val pm = PrinterManager(sim.factory(), Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        pm.hold()
        runCurrent()
        assertEquals(PrinterState.Off, pm.state.value)
        sim.on = true
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("Print stays disabled, so no print attempt can reconnect either", PrinterState.Connected, pm.state.value)
    }
}
