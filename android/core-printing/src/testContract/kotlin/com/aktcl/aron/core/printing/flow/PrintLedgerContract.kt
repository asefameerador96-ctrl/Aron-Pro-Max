package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.doc.MemoPrint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The [PrintLedger] contract: the same scenarios run against the reference `PrintFlowTest.MemLedger` (core-printing)
 * and android-core's `RoomPrintLedger` (core-database, Robolectric). Shared as a source directory by both modules.
 *
 * A subclass supplies the storage: a ledger, real documents (a committed memo, a stock row) and probes of the
 * printed flags. [restart] simulates a killed process: the next ledger reads the same storage.
 */
abstract class PrintLedgerContract {
    protected abstract val ledger: PrintLedger

    /** A committed memo; returns its client uuid. */
    protected abstract suspend fun newMemo(): String

    /**
     * One stock Save of [skus] SKUs (one `stock_movement` per SKU, saved together, each Save at its own time); returns
     * the rows' client uuids. The slip names the first one.
     */
    protected abstract suspend fun newStockSave(skus: Int): List<String>

    private suspend fun newStock(): String = newStockSave(1).first()

    /** `memo.printed_at` (or the first printed time the reference keeps) as epoch ms; null when not printed. */
    protected abstract suspend fun memoPrintedAtMs(memo: String): Long?

    /** `memo.print_count`. */
    protected abstract suspend fun memoPrintCount(memo: String): Int

    /** `stock_movement.slip_printed`. */
    protected abstract suspend fun slipPrinted(stock: String): Boolean

    /** Outbox records of one print event, or null when the implementation has no outbox (the reference). */
    protected abstract suspend fun outboxRecords(eventUuid: String): Int?

    /** Simulates a killed and restarted app: closes the storage and opens it again; [ledger] is the new one after. */
    protected abstract suspend fun restart()

    private fun uuid() = UUID.randomUUID().toString()

    private fun memoEvent(memo: String, outcome: String, count: Int = 1, at: Long = 1_000, uuid: String = uuid()) = PrintEvent(
        uuid, if (count == 1) "memo" else "memo_reprint", memo, null, count, outcome,
        if (outcome == PrintEvent.FAILED) null else outcome == PrintEvent.PRINTED, 3, "MP-58N", at,
    )

    private fun slipEvent(stock: String, outcome: String, at: Long = 1_000, uuid: String = uuid()) = PrintEvent(
        uuid, "stock_slip", null, stock, 1, outcome, if (outcome == PrintEvent.FAILED) null else outcome == PrintEvent.PRINTED, 3, null, at,
    )

    /** Paper out, not yet answered: as `MemoPrinting` stores it before "ছাপা ঠিক আছে?". */
    private fun out(e: PrintEvent) = PendingPrint(e.copy(outcome = PrintEvent.PRINTED, userConfirmed = null), paperOut = true)

    @Test fun firstPrintSetsPrintedAtAndTheCountIsTheNumberOfPrintedEvents() = runTest {
        val memo = newMemo()
        assertNull(memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        ledger.record(memoEvent(memo, PrintEvent.PRINTED, at = 1_000))
        assertEquals(1_000L, memoPrintedAtMs(memo))
        assertEquals(1, memoPrintCount(memo))
        ledger.record(memoEvent(memo, PrintEvent.FAILED, count = 2, at = 2_000))
        ledger.record(memoEvent(memo, PrintEvent.FAILED_USER, count = 2, at = 3_000))
        assertEquals(1, memoPrintCount(memo))
        ledger.record(memoEvent(memo, PrintEvent.PRINTED, count = 2, at = 4_000))
        assertEquals("the first print keeps its time", 1_000L, memoPrintedAtMs(memo))
        assertEquals(2, memoPrintCount(memo))
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), ledger.history(memo).map { it.atEpochMs })
        assertEquals(2, ReprintPolicy.counted(ledger.history(memo)))
    }

    @Test fun aPrinterFailureLeavesTheMemoUnprintedAndClosesTheJob() = runTest {
        val memo = newMemo()
        val e = memoEvent(memo, PrintEvent.PRINTED)
        ledger.savePending(PendingPrint(e, paperOut = false))
        assertNull("a job before the printer answered sets nothing", memoPrintedAtMs(memo))
        ledger.record(e.copy(outcome = PrintEvent.FAILED, userConfirmed = null))
        assertNull(memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        assertTrue(ledger.pending().isEmpty())
        assertEquals(0, ReprintPolicy.nextReprintNo(ledger.history(memo)))
    }

    @Test fun paperOutMarksTheMemoAtOnceAndARejectedOnlyCopyClearsIt() = runTest {
        val memo = newMemo()
        val e = memoEvent(memo, PrintEvent.FAILED_USER, at = 5_000)
        ledger.savePending(PendingPrint(e.copy(outcome = PrintEvent.PRINTED, userConfirmed = null), paperOut = false))
        ledger.savePending(out(e)) // upsert of the same job
        assertEquals(1, ledger.pending().size)
        assertTrue(ledger.pending().single().paperOut)
        assertEquals("a printed paper is never forgotten", 5_000L, memoPrintedAtMs(memo))
        ledger.record(e)
        assertNull("a rejected paper is not a printed memo", memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        assertTrue(ledger.pending().isEmpty())
        assertEquals("the next print is still the original", 0, ReprintPolicy.nextReprintNo(ledger.history(memo)))
    }

    @Test fun aRejectedCopyKeepsTheFlagWhileAnotherCopyCounts() = runTest {
        val memo = newMemo()
        ledger.record(memoEvent(memo, PrintEvent.PRINTED, at = 1_000))
        ledger.record(memoEvent(memo, PrintEvent.FAILED_USER, count = 2, at = 2_000))
        assertEquals(1_000L, memoPrintedAtMs(memo))
        assertEquals(1, memoPrintCount(memo))
        // Lead ruling: after a counted original, a rejected reprint is followed by the same reprint number, marked.
        assertEquals(1, ReprintPolicy.nextReprintNo(ledger.history(memo)))

        // Only an unconfirmed paper counts so far: the rejected other copy must not clear the flag.
        val other = newMemo()
        val pendingCopy = memoEvent(other, PrintEvent.PRINTED, at = 3_000)
        val rejected = memoEvent(other, PrintEvent.FAILED_USER, at = 4_000)
        ledger.savePending(out(pendingCopy))
        ledger.savePending(out(rejected))
        ledger.record(rejected)
        assertEquals(3_000L, memoPrintedAtMs(other))
        assertEquals(0, memoPrintCount(other))
    }

    @Test fun aDuplicateClientUuidIsIgnored() = runTest {
        val memo = newMemo()
        val e = memoEvent(memo, PrintEvent.PRINTED)
        ledger.record(e)
        ledger.record(e)
        ledger.record(e.copy(outcome = PrintEvent.FAILED_USER, userConfirmed = false)) // same uuid, other outcome
        assertEquals(1, ledger.history(memo).size)
        assertEquals(PrintEvent.PRINTED, ledger.history(memo).single().outcome)
        assertNotNull(memoPrintedAtMs(memo))
        assertEquals(1, memoPrintCount(memo))
        outboxRecords(e.clientUuid)?.let { assertEquals(1, it) }
        ledger.savePending(out(e)) // a late pending save of a final event
        assertTrue(ledger.pending().isEmpty())
    }

    @Test fun eachFinalEventHasExactlyOneOutboxRecord() = runTest {
        val memo = newMemo()
        val stock = newStock()
        val events = listOf(
            memoEvent(memo, PrintEvent.FAILED), memoEvent(memo, PrintEvent.PRINTED, at = 2_000),
            memoEvent(memo, PrintEvent.FAILED_USER, count = 2, at = 3_000), slipEvent(stock, PrintEvent.PRINTED),
        )
        events.forEach { ledger.savePending(PendingPrint(it, paperOut = false)) }
        events.forEach { assertEquals("no outbox record before the outcome is final", 0, outboxRecords(it.clientUuid) ?: 0) }
        events.forEach { ledger.record(it) }
        events.forEach { e -> outboxRecords(e.clientUuid)?.let { assertEquals(1, it) } }
    }

    @Test fun aStockSlipSetsAndClearsSlipPrinted() = runTest {
        val printed = newStock()
        ledger.record(slipEvent(printed, PrintEvent.PRINTED))
        assertTrue(slipPrinted(printed))
        ledger.record(slipEvent(printed, PrintEvent.FAILED_USER, at = 2_000))
        assertTrue("another copy counts", slipPrinted(printed))

        val rejected = newStock()
        val e = slipEvent(rejected, PrintEvent.FAILED_USER)
        ledger.savePending(out(e))
        assertTrue(slipPrinted(rejected))
        ledger.record(e)
        assertTrue(!slipPrinted(rejected))
        assertEquals(1, ledger.history(rejected).size)
    }

    @Test fun aSlipFlagsEveryRowOfItsSaveAndNoOtherSave() = runTest {
        val saveA = newStockSave(3)
        val saveB = newStockSave(2)
        ledger.record(slipEvent(saveA.first(), PrintEvent.PRINTED))
        saveA.forEach { assertTrue("row of the printed Save", slipPrinted(it)) }
        saveB.forEach { assertTrue("another Save is untouched", !slipPrinted(it)) }
        val rejected = slipEvent(saveB.first(), PrintEvent.FAILED_USER, at = 2_000)
        ledger.savePending(out(rejected))
        saveB.forEach { assertTrue(slipPrinted(it)) }
        ledger.record(rejected)
        saveB.forEach { assertTrue("a rejected only copy clears its whole Save", !slipPrinted(it)) }
        saveA.forEach { assertTrue("and never another Save", slipPrinted(it)) }
    }

    @Test fun aVoidSlipOrDueReceiptIsNeverACopyOfTheMemo() = runTest {
        val memo = newMemo()
        for (kind in ReprintPolicy.NOT_COPIES) {
            val e = memoEvent(memo, PrintEvent.PRINTED).copy(documentKind = kind)
            ledger.savePending(out(e))
            ledger.record(e)
        }
        assertNull(memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        assertEquals(0, ReprintPolicy.nextReprintNo(ledger.history(memo)))
        // A rejected memo copy is not saved by a void slip that counts for nothing.
        ledger.record(memoEvent(memo, PrintEvent.PRINTED, at = 3_000))
        ledger.record(memoEvent(memo, PrintEvent.PRINTED, at = 4_000).copy(documentKind = "void_slip"))
        assertEquals(1, memoPrintCount(memo))
    }

    @Test fun historyIsInRecordedOrderWhenTheClockGoesBack() = runTest {
        val memo = newMemo()
        val later = memoEvent(memo, PrintEvent.PRINTED, at = 5_000)
        val earlier = memoEvent(memo, PrintEvent.FAILED_USER, count = 2, at = 3_000)
        ledger.record(later)
        ledger.record(earlier)
        assertEquals(listOf(later.clientUuid, earlier.clientUuid), ledger.history(memo).map { it.clientUuid })
    }

    @Test fun paperOutIsNeverTakenBack() = runTest {
        val memo = newMemo()
        val e = memoEvent(memo, PrintEvent.PRINTED, at = 1_000)
        ledger.savePending(out(e))
        ledger.savePending(PendingPrint(e, paperOut = false)) // a stale writer
        assertTrue(ledger.pending().single().paperOut)
        ledger.record(e.copy(outcome = PrintEvent.FAILED, userConfirmed = null))
        assertEquals("the paper came out", 1_000L, memoPrintedAtMs(memo))
    }

    @Test fun recoverAfterAKillFinishesEveryPendingJobOnce() = runTest {
        val outMemo = newMemo()
        val stuckMemo = newMemo()
        val outEvent = memoEvent(outMemo, PrintEvent.PRINTED, at = 1_000)
        val stuckEvent = memoEvent(stuckMemo, PrintEvent.PRINTED, at = 2_000)
        ledger.savePending(PendingPrint(outEvent, paperOut = false))
        ledger.savePending(PendingPrint(outEvent, paperOut = true)) // the paper came out, then the app died
        ledger.savePending(PendingPrint(stuckEvent, paperOut = false)) // died while printing

        restart()
        assertEquals("pending jobs survive the kill", setOf(outEvent.clientUuid, stuckEvent.clientUuid), ledger.pending().map { it.event.clientUuid }.toSet())
        assertEquals(1_000L, memoPrintedAtMs(outMemo))

        val printing = recoverer(backgroundScope)
        printing.recover()
        printing.recover() // a second start finds nothing to do
        assertTrue(ledger.pending().isEmpty())
        val outFinal = ledger.history(outMemo).single()
        assertEquals(PrintEvent.PRINTED, outFinal.outcome)
        assertNull("nobody confirmed this paper", outFinal.userConfirmed)
        assertEquals(1_000L, memoPrintedAtMs(outMemo))
        assertEquals(1, memoPrintCount(outMemo))
        assertEquals(PrintEvent.FAILED, ledger.history(stuckMemo).single().outcome)
        assertNull(memoPrintedAtMs(stuckMemo))
        assertEquals(0, memoPrintCount(stuckMemo))
        listOf(outEvent, stuckEvent).forEach { e -> outboxRecords(e.clientUuid)?.let { assertEquals(1, it) } }
        assertEquals("the next print of the recovered memo is reprint 1", 1, ReprintPolicy.nextReprintNo(ledger.history(outMemo)))
    }

    // ---- Flow scenarios: `MemoPrinting` on this ledger, the production renderer and a simulated MP-58N
    // (F-SR-031, F-SR-066, F-SR-073, F-SR-015). The paper is compared row for row with the expected rendering.

    private class OnePrinter : SavedPrinterStore {
        private var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    /** One app process: its `PrinterManager` and its one `MemoPrinting` on the current [ledger]. */
    private fun TestScope.process(sim: SimPrinter, reprintMax: Int = 5): MemoPrinting {
        val pm = PrinterManager(sim.factory(), OnePrinter(), backgroundScope, nowMs = { testScheduler.currentTime })
        return MemoPrinting(pm, { ContractPaper.renderer }, ledger, { uuid() }, { 10_000 + testScheduler.currentTime }, { reprintMax }, { true })
    }

    private suspend fun MemoPrinting.memo(memo: String, m: MemoPrint, readable: Boolean) {
        val a = printMemo(memo, m)
        assertTrue("printed and asking: $a", a is PrintAttempt.AwaitingConfirmation)
        confirm(a as PrintAttempt.AwaitingConfirmation, readable)
    }

    private suspend fun MemoPrinting.slip(stock: String, readable: Boolean) {
        val a = printStockSlip(stock, ContractPaper.slip)
        assertTrue("printed and asking: $a", a is PrintAttempt.AwaitingConfirmation)
        confirm(a as PrintAttempt.AwaitingConfirmation, readable)
    }

    private fun SimPrinter.paper() = sessions.flatten()

    @Test fun flowReprintsCarryTheMarkerAcrossARestartAndStopAtTheLimit() = runTest {
        val memo = newMemo()
        val sim = SimPrinter({ testScheduler.currentTime })
        var printing = process(sim, reprintMax = 2)
        printing.memo(memo, ContractPaper.memo, readable = true) // the original
        val first = memoPrintedAtMs(memo)
        assertNotNull(first)
        assertEquals(1, memoPrintCount(memo))
        printing.memo(memo, ContractPaper.memo, readable = false) // reprint 1, rejected: counts for nothing
        assertEquals(1, memoPrintCount(memo))

        restart() // a new process reads the count from storage, not from memory
        printing = process(sim, reprintMax = 2)
        printing.recover()
        printing.memo(memo, ContractPaper.memo, readable = true) // reprint 1 again
        printing.memo(memo, ContractPaper.memo, readable = true) // reprint 2
        val before = sim.paper().size
        assertEquals(PrintAttempt.LimitReached, printing.printMemo(memo, ContractPaper.memo))
        assertEquals("a refused reprint sends nothing", before, sim.paper().size)

        assertEquals(3, memoPrintCount(memo))
        assertEquals("the first print keeps its time", first, memoPrintedAtMs(memo))
        val h = ledger.history(memo)
        assertEquals(listOf("memo", "memo_reprint", "memo_reprint", "memo_reprint"), h.map { it.documentKind })
        assertEquals(listOf(PrintEvent.PRINTED, PrintEvent.FAILED_USER, PrintEvent.PRINTED, PrintEvent.PRINTED), h.map { it.outcome })
        assertEquals(listOf(1, 2, 2, 3), h.map { it.printCount })
        val r = ContractPaper.renderer
        val papers = listOf(0, 1, 1, 2).map { r.memo(ContractPaper.memo.copy(reprintNo = it)).bitmap }
        assertTrue("the marker changes the paper", !ContractPaper.same(ContractPaper.rows(papers[0]), listOf(papers[1])))
        assertTrue("original, reprint 1 twice, reprint 2", ContractPaper.same(sim.paper(), papers))
        assertTrue(!sim.overflowed)
    }

    @Test fun flowAnEditedMemoPrintsSupersedesAsItsOwnOriginal() = runTest {
        val original = newMemo()
        val edit = newMemo() // the edit is its own memo with its own number from the same series
        val sim = SimPrinter({ testScheduler.currentTime })
        val printing = process(sim)
        printing.memo(original, ContractPaper.memo, readable = true)
        printing.memo(edit, ContractPaper.edited, readable = true)
        assertEquals(1, memoPrintCount(original))
        assertEquals(1, memoPrintCount(edit))
        assertEquals(listOf("memo"), ledger.history(edit).map { it.documentKind })
        val r = ContractPaper.renderer
        assertTrue(
            "the supersedes line is on the paper",
            !ContractPaper.same(ContractPaper.rows(r.memo(ContractPaper.edited).bitmap), listOf(r.memo(ContractPaper.edited.copy(supersedesMemoNo = null)).bitmap)),
        )
        assertTrue(
            "the edited memo prints 'supersedes <no>' and no duplicate marker",
            ContractPaper.same(sim.paper(), listOf(r.memo(ContractPaper.memo).bitmap, r.memo(ContractPaper.edited).bitmap)),
        )
    }

    @Test fun flowAStockSlipFlagsItsWholeSaveAndAReprintIsMarked() = runTest {
        val save = newStockSave(3)
        val other = newStockSave(2)
        val sim = SimPrinter({ testScheduler.currentTime })
        val printing = process(sim)
        val a = printing.printStockSlip(save.first(), ContractPaper.slip) as PrintAttempt.AwaitingConfirmation
        save.forEach { assertTrue("paper out counts until a no", slipPrinted(it)) }
        printing.confirm(a, false)
        save.forEach { assertTrue("a rejected only slip clears the Save", !slipPrinted(it)) }
        printing.slip(save.first(), readable = true)
        printing.slip(save.first(), readable = true)
        save.forEach { assertTrue(slipPrinted(it)) }
        other.forEach { assertTrue("another Save is untouched", !slipPrinted(it)) }
        assertEquals(listOf(PrintEvent.FAILED_USER, PrintEvent.PRINTED, PrintEvent.PRINTED), ledger.history(save.first()).map { it.outcome })
        val r = ContractPaper.renderer
        val papers = listOf(0, 0, 1).map { r.stockSlip(ContractPaper.slip.copy(reprintNo = it)).bitmap }
        assertTrue("the marker changes the slip", !ContractPaper.same(ContractPaper.rows(papers[0]), listOf(papers[2])))
        assertTrue("rejected, original, reprint 1", ContractPaper.same(sim.paper(), papers))
    }

    @Test fun flowAPrinterSwitchedOffNeverMarksTheMemoAndTheRetryIsTheOriginal() = runTest {
        val memo = newMemo()
        val sim = SimPrinter({ testScheduler.currentTime })
        sim.on = false
        val printing = process(sim)
        assertTrue(printing.printMemo(memo, ContractPaper.memo) is PrintAttempt.Failed)
        assertNull(memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        assertTrue(ledger.pending().isEmpty())
        assertEquals(listOf(PrintEvent.FAILED), ledger.history(memo).map { it.outcome })
        sim.on = true
        printing.memo(memo, ContractPaper.memo, readable = true)
        assertEquals(1, memoPrintCount(memo))
        assertEquals("memo", ledger.history(memo).last().documentKind)
        assertTrue("one paper, no marker", ContractPaper.same(sim.paper(), listOf(ContractPaper.renderer.memo(ContractPaper.memo).bitmap)))
    }

    @Test fun flowAKillBeforeTheAnswerKeepsThePaperAndTheNextPrintIsReprint1() = runTest {
        val memo = newMemo()
        val sim = SimPrinter({ testScheduler.currentTime })
        assertTrue(process(sim).printMemo(memo, ContractPaper.memo) is PrintAttempt.AwaitingConfirmation)
        restart() // killed while "ছাপা ঠিক আছে?" was open
        assertNotNull("the paper-out flag was durable before the kill", memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        assertTrue(ledger.pending().single().paperOut)
        val printing = process(sim)
        printing.recover()
        assertNotNull(memoPrintedAtMs(memo))
        assertEquals(1, memoPrintCount(memo))
        assertNull("nobody answered", ledger.history(memo).single().userConfirmed)
        printing.memo(memo, ContractPaper.memo, readable = true)
        assertEquals(listOf("memo", "memo_reprint"), ledger.history(memo).map { it.documentKind })
        assertEquals(listOf(1, 2), ledger.history(memo).map { it.printCount })
        assertEquals(2, memoPrintCount(memo))
        val r = ContractPaper.renderer
        assertTrue(ContractPaper.same(sim.paper(), listOf(r.memo(ContractPaper.memo).bitmap, r.memo(ContractPaper.memo.copy(reprintNo = 1)).bitmap)))
    }

    @Test fun flowARejectedOriginalWithADoubleTapRecordsOnceAndTheNextPaperIsUnmarked() = runTest {
        val memo = newMemo()
        val sim = SimPrinter({ testScheduler.currentTime })
        val printing = process(sim)
        val a = printing.printMemo(memo, ContractPaper.memo) as PrintAttempt.AwaitingConfirmation
        printing.confirm(a, false)
        printing.confirm(a, false) // double tap
        printing.confirm(a, true) // a late other button
        assertEquals(listOf(PrintEvent.FAILED_USER), ledger.history(memo).map { it.outcome })
        assertNull(memoPrintedAtMs(memo))
        assertEquals(0, memoPrintCount(memo))
        printing.memo(memo, ContractPaper.memo, readable = true)
        assertEquals("memo", ledger.history(memo).last().documentKind)
        val original = ContractPaper.renderer.memo(ContractPaper.memo).bitmap
        assertTrue("two unmarked papers", ContractPaper.same(sim.paper(), listOf(original, original)))
    }

    @Test fun flowWithoutConfirmationAPrintIsRecordedAtOnce() = runTest {
        val memo = newMemo()
        val sim = SimPrinter({ testScheduler.currentTime })
        val pm = PrinterManager(sim.factory(), OnePrinter(), backgroundScope, nowMs = { testScheduler.currentTime })
        val printing = MemoPrinting(pm, { ContractPaper.renderer }, ledger, { uuid() }, { 10_000 + testScheduler.currentTime }, { 5 }, { false })
        assertEquals(PrintAttempt.Done, printing.printMemo(memo, ContractPaper.memo))
        val e = ledger.history(memo).single()
        assertEquals(PrintEvent.PRINTED, e.outcome)
        assertNull(e.userConfirmed)
        assertEquals(1, memoPrintCount(memo))
        assertTrue(ledger.pending().isEmpty())
        outboxRecords(e.clientUuid)?.let { assertEquals(1, it) }
    }

    /** `MemoPrinting` as the app builds it at start; recover() never touches the printer or the renderer. */
    private fun recoverer(scope: CoroutineScope): MemoPrinting {
        val store = object : SavedPrinterStore {
            override fun load(): SavedPrinter? = null
            override fun save(printer: SavedPrinter?) = Unit
        }
        val pm = PrinterManager(PrinterTransportFactory { error("recover() must not open the printer") }, store, scope)
        return MemoPrinting(pm, { error("recover() must not render") }, ledger, { uuid() }, { 9_000 })
    }
}
