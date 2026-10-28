package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.LockAcks
import com.aktcl.aron.core.database.repo.RoomPrintLedger
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
 * pulls) or the last sync answer's `day_states`.
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

    private fun refused(block: suspend () -> Unit) = assertThrows(IllegalStateException::class.java) { runBlocking { block() } }

    @Test fun captureIsOpenUntilTheRouteSectionSaysFinalSubmitted() = runTest {
        section("sales_submitted")
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        assertFalse(FinalSubmitLock.isFinal(db, date, route))

        section("final_submitted")
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        val (late, lateFix) = TestRows.visit(seq = 2)
        refused { repo.recordVisitOpen(late, lateFix) }
        refused { repo.recordSale(TestRows.sale(visit.clientUuid)) }
        refused { repo.recordVisitClose(TestRows.close(visit.clientUuid)) }
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        refused { repo.recordDueCollection(due) }
        // nothing of the refused captures reached the outbox
        assertNull(db.outboxDao().byClientUuid(late.clientUuid))
        assertNull(db.outboxDao().byClientUuid(due.clientUuid))
    }

    @Test fun printingStaysAllowedAfterTheLockAndLeavesTheClosedRouteDay() = runTest {
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
        assertEquals("its own family, not the closed visit's", e.clientUuid, row.familyUuid)
        assertFalse("no route: it belongs to no route-day", row.payloadJson.contains("\"route_id\":$route"))
        assertNotNull(db.captureDao().memo(sale.memo.clientUuid)!!.printedAt)
    }

    @Test fun theLastSyncAnswerLocksTooWhenThePushWasLost() = runTest {
        section("sales_submitted") // the delta has not come yet
        syncAnswer(dayState("final_submitted"))
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
        val (visit, fix) = TestRows.visit()
        refused { repo.recordVisitOpen(visit, fix) }
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
}
