package com.aktcl.aron.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CaptureRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
    }
    @After fun tearDown() = db.close()

    private suspend fun count(table: String): Int = db.query("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aFullCallWritesDomainRowsAndOutboxRowsInFamilyOrder() = runTest {
        val (att, attFix) = TestRows.attendance()
        repo.recordAttendance(att, attFix)
        repo.recordStock(listOf(TestRows.stock(100), TestRows.stock(101)))
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        repo.recordVisitClose(TestRows.close(visit.clientUuid))

        val rows = db.outboxDao().nextPending(100)
        assertEquals(listOf("attendance_event", "stock_movement", "stock_movement", "visit", "memo", "memo_line", "memo_line", "memo_discount", "qc_line", "visit_close"), rows.map { it.recordType })
        assertEquals(rows.map { it.seq }.sorted(), rows.map { it.seq })
        val family = rows.filter { it.recordType in setOf("visit", "memo", "memo_line", "memo_discount", "qc_line", "visit_close") }
        assertTrue(family.all { it.familyUuid == visit.clientUuid })
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 1), family.map { it.rank })
        assertTrue(rows.all { it.state == OutboxState.PENDING && it.businessDate == "2026-10-05" })
        assertEquals(10, count("outbox"))
        assertEquals(2, count("geo_fix"))
        assertEquals(1, count("memo")); assertEquals(2, count("memo_line")); assertEquals(1, count("qc_line"))
        val counts = db.outboxDao().committedCounts("2026-10-05").associate { it.recordType to it.count }
        assertEquals(2, counts["memo_line"]); assertEquals(1, counts["memo"])
    }

    @Test
    fun theOutboxPayloadIsTheContractRecordBuiltFromTheStoredRow() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        repo.recordSale(TestRows.sale(visit.clientUuid))
        val memoRow = db.outboxDao().nextPending(10).single { it.recordType == "memo" }
        assertEquals(RecordMapping.sha256Hex(memoRow.payloadJson), memoRow.payloadSha256)
        val record = Json.parseToJsonElement(memoRow.payloadJson).jsonObject
        assertEquals("memo", record["type"]!!.jsonPrimitive.content)
        assertEquals(memoRow.clientUuid, record["client_uuid"]!!.jsonPrimitive.content)
        assertEquals(visit.clientUuid, record["family_uuid"]!!.jsonPrimitive.content)
        assertEquals(1, record["schema_version"]!!.jsonPrimitive.content.toInt())
        assertEquals(10231L, record["route_id"]!!.jsonPrimitive.long)
        val payload = record["payload"]!!.jsonObject
        assertEquals(192_000L, payload["net_mtk"]!!.jsonPrimitive.long)
        assertEquals("sr334001-261005-017", payload["memo_no"]!!.jsonPrimitive.content)
        // Identity is never in a record (docs/24 s4.3).
        assertTrue(listOf("user_id", "device_id", "device_uuid").none { it in record })
        // Coordinates carry at most 7 decimals.
        val visitRecord = Json.parseToJsonElement(db.outboxDao().nextPending(10).single { it.recordType == "visit" }.payloadJson).jsonObject
        assertEquals("23.7938123", visitRecord["payload"]!!.jsonObject["fix"]!!.jsonObject["lat"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSameClientUuidTwiceIsRejectedInEveryDeviceTable() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val dao = db.captureDao()
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertVisit(visit.copy(outletId = 50002)) } }
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertFix(fix.copy(ownerClientUuid = ClientIds.newUuid())) } }
        val stock = TestRows.stock()
        repo.recordStock(listOf(stock))
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordStock(listOf(stock.copy(qtyEntered = 99))) } }
        val (att, attFix) = TestRows.attendance()
        repo.recordAttendance(att, attFix)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertAttendance(att) } }
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertMemoLines(listOf(sale.lines[0].copy(lineNo = 9))) } }
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertMemoDiscounts(sale.discounts) } }
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertQcLines(sale.qcLines) } }
        val close = TestRows.close(visit.clientUuid)
        repo.recordVisitClose(close)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { dao.insertVisitClose(close.copy(visitClientUuid = ClientIds.newUuid())) } }
        assertEquals(1, count("visit")); assertEquals(1, count("stock_movement")); assertEquals(1, count("memo"))
    }

    @Test
    fun aRetriedSaveNeverDoublesTheSale() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        val before = listOf("memo", "memo_line", "memo_discount", "qc_line", "outbox").map { count(it) }
        // Same capture again (a double tap or a retried save after a crash): rejected as a whole.
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordSale(sale) } }
        // Same memo number under a new client UUID: rejected too (memo numbers are unique per user, docs/24 s7.5).
        val other = TestRows.sale(visit.clientUuid, memoNo = sale.memo.memoNo)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordSale(other) } }
        assertEquals(before, listOf("memo", "memo_line", "memo_discount", "qc_line", "outbox").map { count(it) })
    }

    @Test
    fun aFailureBeforeTheOutboxWriteRollsBackTheDomainRows() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        // An outbox row already holding the uuid of the second line makes the outbox insert fail after the domain inserts.
        db.outboxDao().insert(listOf(OutboxEntity(clientUuid = sale.lines[1].clientUuid, recordType = "memo_line", familyUuid = visit.clientUuid, rank = 2,
            businessDate = "2026-10-05", payloadJson = "{}", payloadSha256 = "0".repeat(64), createdAt = "2026-10-05T04:00:00.000Z")))
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordSale(sale) } }
        assertNull(db.captureDao().memo(sale.memo.clientUuid))
        assertEquals(0, count("memo")); assertEquals(0, count("memo_line")); assertEquals(0, count("qc_line"))
        assertNull(db.outboxDao().byClientUuid(sale.memo.clientUuid))
    }

    @Test
    fun inconsistentCapturesAreRefusedBeforeAnyWrite() = runTest {
        val (visit, fix) = TestRows.visit()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordSale(TestRows.sale(visit.clientUuid)) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.recordVisitOpen(visit, fix.copy(ownerClientUuid = "x")) } }
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.recordSale(sale.copy(memo = sale.memo.copy(lineCount = 3))) } }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordVisitClose(TestRows.close(ClientIds.newUuid())) } }
        assertEquals(1, count("outbox"))
    }

    @Test
    fun committedCapturesAndAnInFlightBatchSurviveAKillAndRelaunch() = runTest {
        val name = "kill-test.db"
        context.deleteDatabase(name)
        var fileDb = Room.databaseBuilder(context, AronDatabase::class.java, name).allowMainThreadQueries().build()
        val r = CaptureRepository(fileDb) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit()
        r.recordVisitOpen(visit, fix)
        r.recordSale(TestRows.sale(visit.clientUuid))
        val pending = fileDb.outboxDao().nextPending(3)
        val batch = ClientIds.newUuid()
        assertEquals(3, fileDb.outboxDao().markInFlight(batch, pending.map { it.seq }))
        fileDb.close() // the process dies here

        fileDb = Room.databaseBuilder(context, AronDatabase::class.java, name).allowMainThreadQueries().build()
        assertNotNull(fileDb.captureDao().memo(TestRows.sale(visit.clientUuid).memo.clientUuid).let { fileDb.captureDao().memosOn("2026-10-05").singleOrNull() })
        assertEquals(listOf(batch), fileDb.outboxDao().inFlightBatches())
        assertEquals(pending.map { it.clientUuid }, fileDb.outboxDao().inFlight(batch).map { it.clientUuid })
        assertEquals(6, fileDb.outboxDao().countInState(OutboxState.PENDING) + fileDb.outboxDao().countInState(OutboxState.IN_FLIGHT))
        // A definitive ack moves rows on; acked rows are purged only after the retention window.
        val first = pending.first()
        assertEquals(1, fileDb.outboxDao().applyAck(first.clientUuid, OutboxState.ACKED, null, 77, "2026-10-05T05:00:00.000Z"))
        // F-SYS-028: never purged while its family still has unsent rows (LocalPurge).
        assertEquals(0, kotlinx.coroutines.runBlocking { com.aktcl.aron.core.database.repo.LocalPurge(fileDb).purge("2026-10-30", "2026-10-30T00:00:00.000Z").total })
        assertEquals(2, fileDb.outboxDao().returnToPending(batch, "NETWORK"))
        assertEquals(5, fileDb.outboxDao().countInState(OutboxState.PENDING))
        fileDb.close()
        context.deleteDatabase(name)
    }

    @Test
    fun aValidEditedMemoCommitsWithItsEditFix() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val original = TestRows.sale(visit.clientUuid)
        repo.recordSale(original)
        val editUuid = ClientIds.newUuid()
        val editFix = TestRows.fix(editUuid, "memo_edit")
        val base = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-018", memoUuid = editUuid)
        repo.recordSale(base.copy(
            memo = base.memo.copy(supersedesClientUuid = original.memo.clientUuid, editReasonCode = "qty_wrong", editFixClientUuid = editFix.clientUuid),
            editFix = editFix,
        ))
        val payload = Json.parseToJsonElement(db.outboxDao().byClientUuid(editUuid)!!.payloadJson).jsonObject["payload"]!!.jsonObject
        assertEquals(original.memo.clientUuid, payload["supersedes_client_uuid"]!!.jsonPrimitive.content)
        assertEquals("memo_edit", payload["edit_fix"]!!.jsonObject["purpose"]!!.jsonPrimitive.content)
    }
}
