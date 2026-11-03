package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.LockAcks
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.database.repo.RouteDayLock
import com.aktcl.aron.core.printing.flow.PrintEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual A3, D-TSO-MAN-31: after the zone-day's Final Submit the SR and AMO phones lock capture for the route-day
 * (the server would reject the rows `day_final_submitted`), while printing stays allowed. The lock comes from the route-day
 * state the phone holds: the route section's `day_state` (bundle, then every delta, which the push `day_final_submitted`
 * pulls) or the last sync answer's `day_states`. It refuses only the types the server refuses (D-A3-22).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RouteDayLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
    private val route = TestRows.meta().routeId!!
    private val date = TestRows.meta().businessDate

    @After fun tearDown() = db.close()

    private fun dayState(state: String, routeId: Long = route, businessDate: String = date, finalAt: String? = if (state == "final_submitted") "2026-10-05T15:00:00.000Z" else null) =
        """{"route_id":$routeId,"business_date":"$businessDate","state":"$state","submit_cycle":1,"submit_voided":false,"final_submitted_at":${finalAt?.let { "\"$it\"" } ?: "null"}}"""

    /** The route section as the bundle stores it (RouteSnapshot without route and outlets), with [state] as its day_state. */
    private suspend fun section(state: String, routeId: Long = route, businessDate: String = date) =
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId", """{"route_id":$routeId,"targets":[],"day_state":${dayState(state, routeId, businessDate)}}""")))

    private suspend fun syncAnswer(vararg states: String) =
        db.referenceDao().putMeta(SyncMetaEntity(FinalSubmitLock.KEY_SYNC_DAY_STATES, "[" + states.joinToString(",") + "]"))

    @Test fun theLockedTypesAreRefusedOnceTheRouteSectionSaysFinalSubmitted() = runTest {
        section("sales_submitted")
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        assertFalse(FinalSubmitLock.isFinal(db, date, route))
        assertTrue(RouteDayLock.isOpen(db, TestRows.meta(), "memo"))

        section("final_submitted")
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        // the types the server refuses after the lock (B3 DAY_LOCKED_TYPES) are refused on the phone
        for (t in listOf("visit", "visit_close", "visit_skip", "memo", "memo_line", "memo_void", "due_collection")) {
            assertTrue(t, FinalSubmitLock.refuses(db, TestRows.meta(), t))
            assertFalse(t, RouteDayLock.isOpen(db, TestRows.meta(), t))
            val e = assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { RouteDayLock.requireOpen(db, TestRows.meta(), t) } }
            assertEquals(t, e.recordType); assertEquals(route, e.routeId); assertEquals(date, e.businessDate)
        }
        // state records stay open (D-TSO-MAN-31: day_submit and attendance are accepted after the lock; printing stays allowed)
        for (t in listOf("day_submit", "attendance_event", "print_event", "amo_record", "survey_response")) {
            assertFalse(t, FinalSubmitLock.refuses(db, TestRows.meta(), t))
            assertTrue(t, RouteDayLock.isOpen(db, TestRows.meta(), t))
            RouteDayLock.requireOpen(db, TestRows.meta(), t)
        }
        // a routeless record never meets the lock
        assertFalse(FinalSubmitLock.refuses(db, TestRows.meta(routeId = null), "due_collection"))
    }

    /**
     * Checker fix (high): the lock never turns into a crash. CaptureRepository does not name its record types yet, so it is
     * not checked against Final Submit: the SR's Sales Submit after the TSO's "Submit anyway" (day_submit, accepted by the
     * server), leaving a call when the lock arrived mid-visit (visit_close) and Mark Paid (due_collection) are queued, not
     * thrown in a screen that does not catch them; the server answers the late ones `day_final_submitted`.
     */
    @Test fun capturesThatDoNotNameTheirTypeNeverThrowForTheLock() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        section("final_submitted") // the lock arrives mid-visit
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        repo.recordDueCollection(due)
        val submit = DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false)
        repo.recordDaySubmit(submit)
        listOf(due.clientUuid, submit.clientUuid).forEach { assertNotNull("queued: $it", db.outboxDao().byClientUuid(it)) }
        assertEquals("day_submit", db.outboxDao().byClientUuid(submit.clientUuid)!!.recordType)
        // the Sales Submit still closes its route-day for every capture, as before
        assertFalse(RouteDayLock.isOpen(db, TestRows.meta()))
        val (late, lateFix) = TestRows.visit(seq = 2)
        val e = assertThrows(IllegalStateException::class.java) { runBlocking { repo.recordVisitOpen(late, lateFix) } }
        assertFalse("a Sales Submit refusal is not the Final Submit's", e is RouteDayFinalSubmittedException)
    }

    @Test fun printingStaysAllowedAfterTheLock() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        section("final_submitted")
        val ledger = RoomPrintLedger(db, { base -> (base ?: TestRows.meta(routeId = null)).copy(capturedAt = "2026-10-05T16:00:00.000Z") }, { "2026-10-05T16:00:00.000Z" })
        val e = PrintEvent(ClientIds.newUuid(), "memo", sale.memo.clientUuid, null, 2, PrintEvent.PRINTED, true, 3, null, 2_000L)
        ledger.record(e) // a reprint of the day's memo after Final Submit
        val row = db.outboxDao().byClientUuid(e.clientUuid)
        assertNotNull("the print is queued", row)
        assertEquals("print_event", row!!.recordType)
        // a print is a state record the server accepts after the lock (B3): it stays the memo's child in the visit family
        assertEquals(visit.clientUuid, row.familyUuid)
        assertNotNull(db.captureDao().memo(sale.memo.clientUuid)!!.printedAt)
    }

    @Test fun theLastSyncAnswerLocksTooWhenThePushWasLost() = runTest {
        section("sales_submitted") // the delta has not come yet
        syncAnswer(dayState("final_submitted"))
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        assertTrue(FinalSubmitLock.refuses(db, TestRows.meta(), "visit"))
    }

    @Test fun onlyTheSameRouteAndBusinessDateLock() = runTest {
        section("final_submitted", businessDate = "2026-10-04") // yesterday's state still held
        syncAnswer(dayState("final_submitted", routeId = 99), dayState("final_submitted", businessDate = "2026-10-04"))
        assertFalse(FinalSubmitLock.isFinal(db, date, route))
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix) // today's route-day is open
        assertNotNull(db.outboxDao().byClientUuid(visit.clientUuid))
        assertTrue(FinalSubmitLock.isFinal(db, date, 99))
    }

    @Test fun finalSubmittedAtAloneIsFinalAndABrokenSourceIsNot() = runTest {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route", """{"day_state":${dayState("sales_submitted", finalAt = "2026-10-05T15:00:00.000Z")}}""")))
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route", "not json")))
        syncAnswer("garbage")
        assertFalse(FinalSubmitLock.isFinal(db, date, route))
    }

    @Test fun aReopenUnlocksOnceBothSourcesMoveOn() = runTest {
        section("final_submitted"); syncAnswer(dayState("final_submitted"))
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        syncAnswer(dayState("sales_submitted")) // the next sync answer after the admin's reopen
        assertTrue("the held delta still says final", FinalSubmitLock.isFinal(db, date, route))
        section("sales_submitted") // the next delta
        assertFalse(FinalSubmitLock.isFinal(db, date, route))
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
    }

    @Test fun finalRoutesListsTheLockedRoutesOfTheDay() = runTest {
        section("final_submitted", routeId = 1); section("in_field", routeId = 2)
        syncAnswer(dayState("final_submitted", routeId = 3))
        assertEquals(listOf(1L, 3L), FinalSubmitLock.finalRoutes(db, date, listOf(1, 2, 3, 4, 1)))
        assertEquals(emptyList<Long>(), FinalSubmitLock.finalRoutes(db, "2026-10-06", listOf(1, 2, 3)))
    }

    @Test fun metLockCountsTheDaysHeldAndRefusedRecords() = runTest {
        val uuids = (1..4).map { val (v, f) = TestRows.visit(seq = it); repo.recordVisitOpen(v, f); v.clientUuid }
        val w = db.openHelper.writableDatabase
        w.execSQL("UPDATE outbox SET state = 'quarantined', last_code = 'after_final_submit' WHERE client_uuid IN ('${uuids[0]}', '${uuids[1]}')")
        w.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'day_final_submitted' WHERE client_uuid = '${uuids[2]}'")
        w.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'schema_invalid' WHERE client_uuid = '${uuids[3]}'")
        assertEquals(LockAcks(held = 2, refused = 1), FinalSubmitLock.metLock(db, date))
        assertEquals(LockAcks(0, 0), FinalSubmitLock.metLock(db, "2026-10-06"))
        assertFalse(LockAcks(0, 0).any)
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
