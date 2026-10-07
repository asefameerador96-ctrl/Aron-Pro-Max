package com.aktcl.aron.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.printing.flow.PrintEvent
import com.aktcl.aron.core.printing.flow.PrintLedger
import com.aktcl.aron.core.printing.flow.PrintLedgerContract
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * `RoomPrintLedger` against the shared [PrintLedgerContract] (the scenarios `PrintFlowTest.MemLedger` passes), on a
 * file database so a restart is a real close and reopen; plus the one-transaction proof only Room can give.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomPrintLedgerContractTest : PrintLedgerContract() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userId = 4_242L
    private var db = open()
    private var room = ledgerOf(db)
    override val ledger: PrintLedger get() = room

    private fun open() = AronDatabase.open(context, userId, null)
    private fun ledgerOf(d: AronDatabase) = RoomPrintLedger(d, { base -> base ?: TestRows.meta(routeId = null) }, { "2026-10-05T04:40:00.000Z" })
    private var memos = 0
    private val repo get() = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(AronDatabase.fileName(userId))
    }

    override suspend fun newMemo(): String {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-${100 + ++memos}")
        repo.recordSale(sale)
        return sale.memo.clientUuid
    }

    override suspend fun newStock(): String = TestRows.stock().also { repo.recordStock(listOf(it)) }.clientUuid
    override suspend fun memoPrintedAtMs(memo: String) = db.captureDao().memo(memo)!!.printedAt?.let { Instant.parse(it).toEpochMilli() }
    override suspend fun memoPrintCount(memo: String) = db.captureDao().memo(memo)!!.printCount
    override suspend fun slipPrinted(stock: String) = db.captureDao().stockOn("2026-10-05").first { it.clientUuid == stock }.slipPrinted
    override suspend fun outboxRecords(eventUuid: String): Int? = if (db.outboxDao().byClientUuid(eventUuid) == null) 0 else 1

    override suspend fun restart() {
        db.close()
        db = open()
        room = ledgerOf(db)
    }

    /** The event, its outbox record, the job delete and the memo flags are one transaction: a failure in the last write undoes all. */
    @Test fun aFailureInTheLastWriteRollsBackTheEventItsOutboxRecordAndTheFlags() = runTest {
        val memo = newMemo()
        val e = PrintEvent(java.util.UUID.randomUUID().toString(), "memo", memo, null, 1, PrintEvent.PRINTED, true, 3, null, 1_000)
        room.savePending(com.aktcl.aron.core.printing.flow.PendingPrint(e, paperOut = false))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER kill_print_count BEFORE UPDATE OF print_count ON memo BEGIN SELECT RAISE(ABORT, 'killed'); END",
        )
        val thrown = runCatching { room.record(e) }.exceptionOrNull()
        assertTrue("the injected failure surfaced: $thrown", thrown != null)
        assertTrue(room.history(memo).isEmpty())
        assertEquals(0, outboxRecords(e.clientUuid))
        assertNull(memoPrintedAtMs(memo))
        assertEquals("the job is still pending, so recover() finishes it", listOf(e.clientUuid), room.pending().map { it.event.clientUuid })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER kill_print_count")
        room.record(e)
        assertEquals(1, room.history(memo).size)
        assertEquals(1, outboxRecords(e.clientUuid))
        assertEquals(1, memoPrintCount(memo))
    }
}
