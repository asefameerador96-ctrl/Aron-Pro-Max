package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Sales Submit, review round 2 of device defect D1 (findings D1-1 .. D1-7 of the lead): each test below failed against the first
 * version of slice 4 (or could not be written) and is the proof of one ruling. Real Room, no mocks of the database.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RefusedReaderNoPayloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val source = RoomDaySource(db)
    private val today = "2026-11-13"
    private val dao get() = db.outboxDao()

    private suspend fun row(
        state: String, code: String?, type: String = "memo_line", date: String = today, family: String? = null, payload: String = "{\"payload\":{\"qty\":1}}", seen: String? = null,
    ): String {
        val uuid = ClientIds.newUuid()
        dao.insert(listOf(OutboxEntity(
            clientUuid = uuid, recordType = type, familyUuid = family ?: uuid, rank = 1, businessDate = date, payloadJson = payload, payloadSha256 = "x",
            state = state, lastCode = code, createdAt = date + "T05:00:00.000Z", seenAt = seen,
        )))
        return uuid
    }

    /** The names of a family come from the memo it holds, not from any payload: a payload that is not even JSON is never read. */
    @Test fun theListReadsNoPayloadAndNamesTheMemoFromTheDomainTable() = runTest {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta, "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta, visit, 1, "abc334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, false, null, 2, 0, 0),
            listOf(
                MemoLineEntity(ClientIds.newUuid(), meta, memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000),
                MemoLineEntity(ClientIds.newUuid(), meta, memo, 2, 106, "sale", 1, "dozen", 1, 1, "outlet", "2026-09-01", 28_000, 1, 28_000),
            ),
        ))
        // the phone gave up on the whole call; every payload is replaced by text that is not JSON: reading one would fail the test
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'retry_exhausted', payload_json = 'NOT JSON {{{'")
        val e = source.rejectedRows("2026-10-05").single()
        assertEquals("abc334001-261005-001", e.memoNo)
        assertTrue("a family is one entry: visit, memo, two lines", e.familySize >= 4)
    }

    /**
     * Round-2 review: EVERY record type the phone queues is named again (outlet and/or memo number) from the domain tables, never from a payload.
     * Each refused row below carries a payload that is not JSON, so a reader that touched it could not name anything.
     */
    @Test fun everyRecordTypeIsNamedFromTheDomainTablesAndNeverFromAPayload() = runTest {
        val date = "2026-10-05"
        val meta = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
        val cap = db.captureDao()
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(VisitEntity(visit, meta, "sr_call", 701, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        cap.insertMemo(MemoEntity(memo, meta, visit, 702, "abc334001-261005-009", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, false, null, 2, 0, 0))
        // one refused row per record type, each with its own domain row; the family of the call is the visit's uuid
        fun ob(type: String, uuid: String, family: String = uuid, rank: Int = 0) = OutboxEntity(
            clientUuid = uuid, recordType = type, familyUuid = family, rank = rank, businessDate = date, payloadJson = "NOT JSON {{{", payloadSha256 = "x",
            state = "rejected", lastCode = "scope_out_of_reach", createdAt = date + "T05:00:00.000Z",
        )
        val due = ClientIds.newUuid(); val skip = ClientIds.newUuid(); val close = ClientIds.newUuid(); val qc = ClientIds.newUuid()
        val stock = ClientIds.newUuid(); val amo = ClientIds.newUuid(); val amoAlone = ClientIds.newUuid()
        cap.insertDueCollection(com.aktcl.aron.core.database.entity.DueCollectionEntity(due, meta, 703, memo, "abc334001-261001-004", "2026-10-01", 5_000, false, 9_000))
        cap.insertVisitSkip(com.aktcl.aron.core.database.entity.VisitSkipEntity(skip, meta, 704, "closed"))
        cap.insertVisitClose(com.aktcl.aron.core.database.entity.VisitCloseEntity(close, meta, visit, "sold", null, false, "2026-10-05T04:40:00.000Z", false))
        cap.insertQcLines(listOf(com.aktcl.aron.core.database.entity.QcLineEntity(qc, meta, visit, memo, true, 105, "damaged", "g", 1, 100, 100)))
        cap.insertStock(listOf(com.aktcl.aron.core.database.entity.StockMovementEntity(stock, meta, "issue", 105, 5, "dozen", 1, 5, null, false)))
        cap.insertAmoRecord(com.aktcl.aron.core.database.entity.AmoRecordEntity(amo, meta, "call_assessment_answer", visit, 2, "{}"))
        cap.insertAmoRecord(com.aktcl.aron.core.database.entity.AmoRecordEntity(amoAlone, meta, "risk_review", amoAlone, 0, "{}"))
        db.outboxDao().insert(listOf(
            ob("due_collection", due), ob("visit_skip", skip), ob("visit_close", close, visit, 1), ob("qc_line", qc, visit, 2),
            ob("stock_movement", stock), ob("call_assessment_answer", amo, visit, 2), ob("risk_review", amoAlone),
        ))
        val byType = db.outboxDao().refusedList(100).associateBy { it.recordType }
        assertEquals(setOf("due_collection", "visit_skip", "visit_close", "qc_line", "stock_movement", "call_assessment_answer", "risk_review"), byType.keys)
        assertEquals("due: its outlet", 703L, byType.getValue("due_collection").outletId)
        assertEquals("due: the memo it is collected against", "abc334001-261001-004", byType.getValue("due_collection").memoNo)
        assertEquals("skip: its outlet", 704L, byType.getValue("visit_skip").outletId)
        assertEquals("close: its visit's outlet", 701L, byType.getValue("visit_close").outletId)
        assertEquals("qc: its memo", "abc334001-261005-009", byType.getValue("qc_line").memoNo)
        assertEquals("qc: the memo's outlet", 702L, byType.getValue("qc_line").outletId)
        assertEquals("an AMO call record: the call's outlet through its visit family", 701L, byType.getValue("call_assessment_answer").outletId)
        assertEquals("stock has no outlet or memo: named by type", null, byType.getValue("stock_movement").outletId)
        assertEquals("a standalone AMO record has none in a domain column", null, byType.getValue("risk_review").outletId)
        // and through the screen's own source, one entry per family, the rows above never read a payload
        val entries = source.rejectedRows(date)
        assertEquals("abc334001-261001-004", entries.first { it.recordType == "due_collection" }.memoNo)
        assertTrue(entries.any { it.recordType == "stock_movement" } && entries.any { it.recordType == "risk_review" })
    }

    /** B4: the source marks a refused day_submit of a date that already carries a check-out, so the screen uses the after-check-out text. */
    @Test fun aRefusedDaySubmitIsMarkedAfterCheckOutOnlyWhenTheDateHasACheckOut() = runTest {
        val date = "2026-10-05"
        val meta = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1)
        val repo = CaptureRepository(db) { "2026-10-05T11:30:00.000Z" }
        val submit = ClientIds.newUuid()
        db.outboxDao().insert(listOf(OutboxEntity(clientUuid = submit, recordType = "day_submit", familyUuid = submit, rank = 0, businessDate = date, payloadJson = "{}", payloadSha256 = "x", state = "rejected", lastCode = "schema_invalid", createdAt = date + "T05:00:00.000Z")))
        assertTrue("not checked out yet: the old text applies", !source.rejectedRows(date).single().afterCheckOut)
        val inUuid = ClientIds.newUuid(); val outUuid = ClientIds.newUuid()
        val inFix = GeoFixEntity(ClientIds.newUuid(), inUuid, "attendance_in", "ok", 23.79, 90.4, 8.0, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordAttendance(com.aktcl.aron.core.database.entity.AttendanceEventEntity(inUuid, meta, "check_in", inFix.clientUuid, null), inFix)
        val outFix = inFix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = outUuid, purpose = "attendance_out")
        repo.recordCheckOut(com.aktcl.aron.core.database.entity.AttendanceEventEntity(outUuid, meta, "check_out", outFix.clientUuid, null), outFix) { emptyList() }
        assertTrue("checked out: the after-check-out text applies", source.rejectedRows(date).single().afterCheckOut)
    }
}
