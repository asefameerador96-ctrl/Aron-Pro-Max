package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.printing.flow.PendingPrint
import com.aktcl.aron.core.printing.flow.PrintEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Room print ledger behaves as core-printing's reference MemLedger (android-print request s1). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomPrintLedgerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
    private val envelopes = ArrayList<CaptureMeta?>()
    private val ledger = RoomPrintLedger(db, { base -> envelopes += base; (base ?: TestRows.meta(routeId = null)).copy(capturedAt = "2026-10-05T04:40:00.000Z") }, { "2026-10-05T04:40:00.000Z" })

    @After fun tearDown() = db.close()

    private suspend fun memo(): Pair<String, String> {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        return sale.memo.clientUuid to visit.clientUuid
    }

    private fun event(doc: String, outcome: String, count: Int = 1, uuid: String = ClientIds.newUuid(), memo: Boolean = true, at: Long = 1_000L) =
        PrintEvent(uuid, if (memo) "memo" else "stock_slip", if (memo) doc else null, if (memo) null else doc, count, outcome,
            if (outcome == PrintEvent.FAILED) null else outcome == PrintEvent.PRINTED, 3, null, at)

    @Test fun aPrintedMemoIsMarkedCountedAndQueuedInItsVisitFamily() = runTest {
        val (memo, visit) = memo()
        val e = event(memo, PrintEvent.PRINTED)
        ledger.savePending(PendingPrint(e, paperOut = false))
        assertEquals(1, ledger.pending().size)
        ledger.record(e)
        val m = db.captureDao().memo(memo)!!
        assertNotNull(m.printedAt)
        assertEquals(1, m.printCount)
        assertTrue(ledger.pending().isEmpty())
        val row = db.outboxDao().byClientUuid(e.clientUuid)!!
        assertEquals("print_event", row.recordType)
        assertEquals(visit, row.familyUuid)
        assertEquals(3, row.rank)
        val payload = Json.parseToJsonElement(row.payloadJson).jsonObject["payload"]!!.jsonObject
        assertEquals(e.payload(), payload)
        assertEquals(JsonNull, payload["printer_model"]) // required nullable members travel as null
        assertEquals("2026-10-05", Json.parseToJsonElement(row.payloadJson).jsonObject["business_date"]!!.toString().trim('"'))
        assertEquals(TestRows.meta().routeId, envelopes.single()!!.routeId) // the memo's own route-day
    }

    @Test fun recordingTheSameEventTwiceWritesOnce() = runTest {
        val (memo, _) = memo()
        val e = event(memo, PrintEvent.PRINTED)
        ledger.record(e); ledger.record(e)
        assertEquals(1, ledger.history(memo).size)
        assertEquals(1, db.outboxDao().nextPending(100).count { it.recordType == "print_event" })
        ledger.savePending(PendingPrint(e, paperOut = true)) // a late pending save of a final event is ignored
        assertTrue(ledger.pending().isEmpty())
    }

    @Test fun paperOutMarksTheMemoBeforeTheSellerAnswersAndARejectedCopyClearsIt() = runTest {
        val (memo, _) = memo()
        val e = event(memo, PrintEvent.FAILED_USER)
        ledger.savePending(PendingPrint(e.copy(outcome = PrintEvent.PRINTED, userConfirmed = null), paperOut = true))
        assertNotNull("a printed paper is never forgotten", db.captureDao().memo(memo)!!.printedAt)
        ledger.record(e)
        assertNull(db.captureDao().memo(memo)!!.printedAt)
        assertEquals(0, db.captureDao().memo(memo)!!.printCount)
    }

    @Test fun aRejectedCopyKeepsTheFlagWhenAnotherCopyCounts() = runTest {
        val (memo, _) = memo()
        ledger.record(event(memo, PrintEvent.PRINTED, at = 1_000))
        ledger.record(event(memo, PrintEvent.FAILED_USER, count = 2, at = 2_000))
        assertNotNull(db.captureDao().memo(memo)!!.printedAt)
        val other = event(memo, PrintEvent.PRINTED, count = 3, at = 3_000)
        ledger.savePending(PendingPrint(other, paperOut = true))
        ledger.record(event(memo, PrintEvent.FAILED_USER, count = 4, at = 4_000))
        assertNotNull(db.captureDao().memo(memo)!!.printedAt)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), ledger.history(memo).map { it.atEpochMs })
    }

    @Test fun aStockSlipSetsAndClearsSlipPrintedAndIsItsOwnFamily() = runTest {
        val stock = TestRows.stock()
        repo.recordStock(listOf(stock))
        val e = event(stock.clientUuid, PrintEvent.PRINTED, memo = false)
        ledger.record(e)
        assertTrue(db.captureDao().stockOn("2026-10-05").single().slipPrinted)
        assertEquals(e.clientUuid, db.outboxDao().byClientUuid(e.clientUuid)!!.familyUuid)
        val fresh = TestRows.stock(uuid = ClientIds.newUuid())
        repo.recordStock(listOf(fresh))
        val job = event(fresh.clientUuid, PrintEvent.FAILED_USER, memo = false)
        ledger.savePending(PendingPrint(job.copy(outcome = PrintEvent.PRINTED, userConfirmed = null), paperOut = true))
        assertTrue(db.captureDao().stockOn("2026-10-05").first { it.clientUuid == fresh.clientUuid }.slipPrinted)
        ledger.record(job)
        assertTrue(!db.captureDao().stockOn("2026-10-05").first { it.clientUuid == fresh.clientUuid }.slipPrinted)
    }

    @Test fun aSlipMarksEveryMovementOfItsSaveAndNoOther() = runTest {
        val save = listOf(TestRows.stock(skuId = 100), TestRows.stock(skuId = 103)) // one Save: same meta
        repo.recordStock(save)
        val other = TestRows.stock(skuId = 105).let { it.copy(meta = it.meta.copy(capturedAt = "2026-10-05T09:00:00.000Z")) }
        repo.recordStock(listOf(other))
        ledger.record(event(save.first().clientUuid, PrintEvent.PRINTED, memo = false))
        val rows = db.captureDao().stockOn("2026-10-05").associateBy { it.clientUuid }
        assertTrue(save.all { rows.getValue(it.clientUuid).slipPrinted })
        assertTrue(!rows.getValue(other.clientUuid).slipPrinted)
    }

    @Test fun aDaySummaryWithoutALocalDocumentGetsTheAppEnvelope() = runTest {
        val e = PrintEvent(ClientIds.newUuid(), "day_summary", null, ClientIds.newUuid(), 1, PrintEvent.PRINTED, true, 3, "MP-58N", 5_000)
        ledger.record(e)
        assertNull(envelopes.single())
        assertEquals(1, ledger.history(e.refClientUuid!!).size)
    }
}
