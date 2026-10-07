package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import kotlinx.coroutines.CoroutineScope
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
