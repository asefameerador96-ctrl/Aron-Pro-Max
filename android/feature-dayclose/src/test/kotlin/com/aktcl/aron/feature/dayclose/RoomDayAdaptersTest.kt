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
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomDayAdaptersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private fun meta() = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    private suspend fun sell(due: Long) {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000 - due, due, due > 0, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
    }

    @Test fun submitIsTheLastRecordCountsDuesAndMoneyAreRealAndTheDayLocks() = runTest {
        sell(due = 50_000)
        val source = RoomDaySource(db)
        assertEquals(DuesAtSubmit(1, 50_000), source.dues("2026-10-05"))
        assertEquals(1, source.deviceCounts("2026-10-05")["memo"])
        assertFalse(source.alreadySubmitted("2026-10-05"))

        val gate = SalesSubmitRules.gate(source.outboxStates("2026-10-05"), listOf(CountRow("memo", 1, 1)), source.dues("2026-10-05"), false)
        RoomDaySubmitWriter(db, repo, { meta() }, { "match" }).queue("2026-10-05", gate, listOf(CountRow("memo", 1, 1)))
        assertTrue(source.alreadySubmitted("2026-10-05"))
        assertFalse(source.submitSettled("2026-10-05"))
        val submit = db.captureDao().daySubmitsOn("2026-10-05").single()
        assertTrue(submit.submittedWithDues); assertEquals(50_000, submit.duesOutstandingMtk); assertEquals(1, submit.submitCycle)
        assertTrue(submit.deviceMoneyJson.contains("\"net_mtk\":84000")); assertTrue(submit.deviceMoneyJson.contains("\"sold_qty_base_by_sku\":{\"105\":3}"))
        assertEquals("day_submit", db.outboxDao().nextPending(100).last().recordType)
        // nothing more is captured after the submit
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { sell(0) } }
    }

    /** D-NIGHT-DAYCLOSE-01 (b): the refused rows are listed from the phone's own outbox, with memo number, outlet, reason and outcome. */
    @Test fun rejectedRowsAreListedFromTheAckRegistry() = runTest {
        db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("reason_texts",
            "{\"memo_no_duplicate\": {\"bn\": \"এই মেমো নম্বর আগেই ব্যবহার হয়েছে\", \"en\": \"This memo number was already used\"}}")))
        sell(due = 0)
        val source = RoomDaySource(db)
        assertTrue(source.rejectedRows("2026-10-05").isEmpty())
        val rows = db.outboxDao().nextPending(100)
        val memo = rows.first { it.recordType == "memo" }
        val visit = rows.first { it.recordType == "visit" }
        val line = rows.first { it.recordType == "memo_line" }
        db.outboxDao().applyAck(memo.clientUuid, "quarantined", "memo_no_duplicate", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(line.clientUuid, "rejected", "price_invalid", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(visit.clientUuid, "pending", "resync", null, "2026-10-05T05:00:00.000Z")
        // the phone's own markers on queued rows (resync, digest_resend) are not rejections
        assertEquals(2, source.rejectedRows("2026-10-05").size)
        db.outboxDao().applyAck(visit.clientUuid, "pending", "scope_stale", null, "2026-10-05T05:00:00.000Z")
        val listed = source.rejectedRows("2026-10-05").associateBy { it.recordType }
        assertEquals(3, listed.size)
        listed.getValue("memo").let {
            assertEquals(RejectedOutcome.STORED_FLAGGED, it.outcome); assertEquals("sr334001-261005-001", it.memoNo)
            assertEquals("এই মেমো নম্বর আগেই ব্যবহার হয়েছে", it.reasonBn); assertEquals("This memo number was already used", it.reasonEn)
        }
        listed.getValue("memo_line").let {
            assertEquals(RejectedOutcome.DROPPED, it.outcome); assertEquals("price_invalid", it.code); assertEquals(null, it.reasonEn)
            assertEquals("sr334001-261005-001", it.memoNo) // through its parent memo
        }
        assertEquals(RejectedOutcome.RETRY, listed.getValue("visit").outcome)
    }

    @Test fun anAmoSubmitIsASupervisorDayWithNoRoute() = runTest {
        val source = RoomDaySource(db)
        val noRoute = { meta().copy(routeId = null) }
        val gate = SalesSubmitRules.gate(source.outboxStates("2026-10-05"), emptyList(), source.dues("2026-10-05"), false)
        RoomDaySubmitWriter(db, repo, noRoute, { "match" }, scope = "supervisor_day").queue("2026-10-05", gate, emptyList())
        val submit = db.captureDao().daySubmitsOn("2026-10-05").single()
        assertEquals("supervisor_day", submit.scope); assertEquals(null, submit.meta.routeId); assertEquals(1, submit.submitCycle)
        assertTrue(source.alreadySubmitted("2026-10-05"))
    }
}
