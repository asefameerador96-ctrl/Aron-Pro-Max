package com.aktcl.aron.core.printing.flow

import java.util.UUID

/** The reference ledger passes the shared contract (the Room one runs the same in core-database). */
class MemLedgerContractTest : PrintLedgerContract() {
    private val mem = PrintFlowTest.MemLedger()
    override val ledger: PrintLedger get() = mem

    override suspend fun newMemo() = UUID.randomUUID().toString()
    /** The reference has no stock rows: a Save is its rows, flagged through the slip's (first) uuid. */
    private val saveOf = HashMap<String, String>()
    override suspend fun newStockSave(skus: Int) = List(skus) { UUID.randomUUID().toString() }.also { rows -> rows.forEach { saveOf[it] = rows.first() } }
    override suspend fun memoPrintedAtMs(memo: String) = mem.printedAt(memo)
    override suspend fun memoPrintCount(memo: String) = ReprintPolicy.counted(mem.history(memo))
    override suspend fun slipPrinted(stock: String) = mem.printedAt(saveOf.getValue(stock)) != null
    override suspend fun outboxRecords(eventUuid: String): Int? = null
    override suspend fun restart() = Unit // in memory: the same state is the "reopened" storage
}
