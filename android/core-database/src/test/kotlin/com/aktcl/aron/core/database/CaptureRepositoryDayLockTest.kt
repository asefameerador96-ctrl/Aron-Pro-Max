package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ContentViewEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.SurveyResponseEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.record.AmoPayloads
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.core.database.repo.RouteDaySubmittedException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
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
 * tso-manual X4a (R-W-11, A3 ask 10, D-TSO-MAN-31): CaptureRepository names its record type for the captures the server
 * refuses after the zone's Final Submit (B3's DAY_LOCKED_TYPES, which FinalSubmitLock.LOCKED_TYPES copies): the call
 * (`visit`), the sale or edit (`memo`), Mark Paid (`due_collection`), a skip (`visit_skip`) and the close (`visit_close`).
 * Once the route-day is final each is refused with [RouteDayFinalSubmittedException] before anything is stored, and a
 * refused numbered sale takes no memo number. The state records the server accepts after the lock (day_submit, AMO
 * records, survey answers, content views) are still queued.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CaptureRepositoryDayLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T16:00:00.000Z" }
    private val route = TestRows.meta().routeId!!
    private val date = TestRows.meta().businessDate
    private val numbering = MemoNumbering("sr334001", 0, 500)

    @Before fun lockingPolicy() = runBlocking {
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        TestRows.latePolicy(db, "reject")
    }

    @After fun tearDown() = db.close()

    /** The zone's Final Submit as the phone learns it: the route section's day_state says final_submitted. */
    private suspend fun finalSubmitted(routeId: Long = route) = db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
        """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-05T15:00:00.000Z"}}""")))

    private fun refused(type: String, block: suspend () -> Any?) {
        val e = assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { block() } }
        assertEquals(type, e.recordType); assertEquals(route, e.routeId); assertEquals(date, e.businessDate)
    }

    @Test fun everyLockedCaptureIsRefusedBeforeAnythingIsStored() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix) // the call opened before the lock
        finalSubmitted()
        val outboxBefore = db.outboxDao().unsentCount()

        val (late, lateFix) = TestRows.visit(seq = 2)
        refused("visit") { repo.recordVisitOpen(late, lateFix) }
        val sale = TestRows.sale(visit.clientUuid)
        refused("memo") { repo.recordSale(sale) }
        refused("visit_close") { repo.recordVisitClose(TestRows.close(visit.clientUuid)) }
        refused("visit_skip") { repo.recordVisitSkip(VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "shop_closed")) }
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        refused("due_collection") { repo.recordDueCollection(due) }

        assertEquals("no outbox row was written", outboxBefore, db.outboxDao().unsentCount())
        val dao = db.captureDao()
        assertEquals(listOf(visit.clientUuid), dao.visitsOn(date).map { it.clientUuid })
        assertTrue(dao.memosOn(date).isEmpty()); assertTrue(dao.visitClosesOn(date).isEmpty())
        assertTrue(dao.visitSkipsOn(date).isEmpty()); assertTrue(dao.dueCollectionsOn(date).isEmpty())
    }

    @Test fun aRefusedNumberedSaleTakesNoMemoNumber() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val before = repo.recordNumberedSale(TestRows.sale(visit.clientUuid), numbering) // one sale before the lock
        assertEquals(1, db.referenceDao().memoCounter(date))
        finalSubmitted()
        refused("memo") { repo.recordNumberedSale(TestRows.sale(visit.clientUuid), numbering) }
        assertEquals("the counter did not move", 1, db.referenceDao().memoCounter(date))
        assertEquals(listOf(before), db.captureDao().memosOn(date).map { it.memoNo })
    }

    @Test fun aSaleStoredBeforeTheLockReplaysWithItsOwnNumber() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        val no = repo.recordNumberedSale(sale, numbering)
        finalSubmitted()
        // a retry after a kill and relaunch of the commit that landed: its number back, nothing refused or written again
        assertEquals(no, repo.recordNumberedSale(sale, numbering))
        assertEquals(1, db.captureDao().memosOn(date).size)
    }

    @Test fun theStateRecordsAreStillQueuedAfterTheLock() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        finalSubmitted()
        // an AMO record in the call's family (rank 1, carries the route), a survey answer, a content view, the day's submit
        val amo = AmoRecordEntity(ClientIds.newUuid(), TestRows.meta(), "distribution_check", visit.clientUuid, 1, AmoPayloads.distributionCheck(visit.clientUuid, true, null).toString())
        assertTrue(repo.recordAmo(amo))
        val answer = SurveyResponseEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 7, 3, 71, "bool").copy(answerBool = true)
        repo.recordSurveyResponse(answer)
        val view = ContentViewEntity(ClientIds.newUuid(), TestRows.meta(), visit.clientUuid, 2, 2, "av", "viewed", 1, "2026-10-05T04:32:00.000Z", 30_000)
        assertTrue(repo.recordContentView(view))
        val submit = DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false)
        repo.recordDaySubmit(submit)
        for ((uuid, type) in listOf(amo.clientUuid to "distribution_check", answer.clientUuid to "survey_response", view.clientUuid to "content_view", submit.clientUuid to "day_submit")) {
            assertEquals(type, db.outboxDao().byClientUuid(uuid)?.recordType)
        }
    }

    @Test fun aRoutelessCollectionAndAnotherRouteStayOpen() = runTest {
        finalSubmitted()
        // the AMO's Mark Paid outside a call carries no route: the server never locks it, neither does the phone
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        repo.recordDueCollection(due)
        assertNotNull(db.outboxDao().byClientUuid(due.clientUuid))
        // another route of the same date is not closed by this route's Final Submit
        val (other, fix) = TestRows.visit()
        val onOther = other.copy(meta = other.meta.copy(routeId = route + 1))
        repo.recordVisitOpen(onOther, fix)
        assertNotNull(db.outboxDao().byClientUuid(onOther.clientUuid))
        assertTrue(FinalSubmitLock.isFinal(db, date, route))
    }

    /**
     * tso-manual X4a fix round (checker high 2): [CaptureRepository.acceptsDueCollection] answers what [recordDueCollection]
     * would do with the same meta, for the Final Submit lock and for the rep's own Sales Submit, whose refusal is now the
     * named [RouteDaySubmittedException] (still an IllegalStateException with the old message).
     */
    @Test fun acceptsDueCollectionMatchesTheWriteForSalesSubmitAndFinalSubmit() = runTest {
        fun due(routeId: Long?) = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(routeId = routeId), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        assertTrue("an open route-day takes the collection", repo.acceptsDueCollection(TestRows.meta()))
        // the SR's own Sales Submit closes the route-day: Mark Paid is not drawn and a tap is refused by name
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        assertFalse(repo.acceptsDueCollection(TestRows.meta()))
        val e = assertThrows(RouteDaySubmittedException::class.java) { runBlocking { repo.recordDueCollection(due(route)) } }
        assertEquals(date, e.businessDate); assertEquals(route, e.routeId)
        assertTrue("the same IllegalStateException as before", e is IllegalStateException)
        assertTrue(e.message!!.contains("is submitted; nothing more can be captured"))
        // another route stays open; a routeless collection is never checked
        assertTrue(repo.acceptsDueCollection(TestRows.meta(routeId = route + 1)))
        assertTrue(repo.acceptsDueCollection(TestRows.meta(routeId = null)))
        // a Final Submit on the other route closes it too, with the lock's own type
        finalSubmitted(route + 1)
        assertFalse(repo.acceptsDueCollection(TestRows.meta(routeId = route + 1)))
        assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { repo.recordDueCollection(due(route + 1)) } }
        assertTrue(db.captureDao().dueCollectionsOn(date).isEmpty())
    }

    /**
     * tso-manual X4a follow-up (R-W-22): `stock_movement` is locked on the phone as on the server. A Save on a route-day
     * closed by its zone's Final Submit is refused before anything is stored or queued; a routeless movement (the AMO
     * outside a call) and another route stay open; the rep's own Sales Submit does not refuse stock (D-X4a-13).
     */
    @Test fun aStockSaveOnAFinalRouteDayIsRefusedAndNothingIsQueued() = runTest {
        repo.recordStock(listOf(TestRows.stock(skuId = 100))) // loaded before the lock
        finalSubmitted()
        val outboxBefore = db.outboxDao().unsentCount()
        refused("stock_movement") { repo.recordStock(listOf(TestRows.stock(skuId = 100), TestRows.stock(skuId = 101))) }
        assertEquals("no outbox row was written", outboxBefore, db.outboxDao().unsentCount())
        assertEquals("only the movement stored before the lock", 1, db.captureDao().stockOn(date).size)
        // the AMO outside a call (no route) and another route are not closed by this route's Final Submit
        val routeless = TestRows.stock(skuId = 102).let { it.copy(meta = it.meta.copy(routeId = null)) }
        repo.recordStock(listOf(routeless))
        val other = TestRows.stock(skuId = 103).let { it.copy(meta = it.meta.copy(routeId = route + 1)) }
        repo.recordStock(listOf(other))
        assertNotNull(db.outboxDao().byClientUuid(routeless.clientUuid)); assertNotNull(db.outboxDao().byClientUuid(other.clientUuid))
        // a Sales Submit of the other route does not refuse stock (the server accepts stock after a day_submit)
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(routeId = route + 1), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        val afterSubmit = TestRows.stock(skuId = 104).let { it.copy(meta = it.meta.copy(routeId = route + 1)) }
        repo.recordStock(listOf(afterSubmit))
        assertNotNull(db.outboxDao().byClientUuid(afterSubmit.clientUuid))
    }

    /**
     * tso-manual X4a follow-up (R-W-23): [CaptureRepository.dayRefusal] answers, per type, what the write would do with the
     * same meta (the screens ask it before a fix or a form), and [DayRefusal.of] names the refusal a write threw.
     */
    @Test fun dayRefusalAnswersWhatEachWriteWouldDo() = runTest {
        val types = listOf("visit", "memo", "visit_close", "visit_skip", "due_collection", "stock_movement", "survey_response", "distribution_check")
        types.forEach { assertNull("$it on an open day", repo.dayRefusal(TestRows.meta(), it)) }
        // the rep's own Sales Submit: every route-day capture but stock is refused by name
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        types.forEach { assertEquals(it, if (it == "stock_movement") null else DayRefusal.SALES_SUBMIT, repo.dayRefusal(TestRows.meta(), it)) }
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "shop_closed")
        val thrown = runCatching { repo.recordVisitSkip(skip) }.exceptionOrNull()
        assertEquals(DayRefusal.SALES_SUBMIT, DayRefusal.of(thrown))
        repo.recordStock(listOf(TestRows.stock())) // stock is not refused by a Sales Submit
        // the zone's Final Submit on another route: the locked types meet the lock, the others pass
        val other = TestRows.meta(routeId = route + 1)
        finalSubmitted(route + 1)
        for (t in types) {
            val want = if (t in FinalSubmitLock.LOCKED_TYPES) DayRefusal.FINAL_SUBMIT else null
            assertEquals(t, want, repo.dayRefusal(other, t))
        }
        val e = runCatching { repo.recordStock(listOf(TestRows.stock().let { it.copy(meta = other) })) }.exceptionOrNull()
        assertEquals(DayRefusal.FINAL_SUBMIT, DayRefusal.of(e))
        assertNull("any other failure is not a refusal", DayRefusal.of(IllegalStateException("x")))
        // a routeless collection is never checked, as recordDueCollection
        assertNull(repo.dayRefusal(TestRows.meta(routeId = null), "due_collection"))
        assertEquals(DayRefusal.SALES_SUBMIT, repo.dayRefusal(TestRows.meta(), "due_collection"))
    }

    /**
     * tso-manual X4a follow-up (R-W-22): the phone's [FinalSubmitLock.LOCKED_TYPES] is B3's IngestService.DAY_LOCKED_TYPES,
     * read from the server source in this repository, so neither side can add or drop a type alone.
     */
    @Test fun thePhonesLockedTypesAreTheServersDayLockedTypes() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile && java.io.File(it, "backend").isDirectory }
        val source = java.io.File(root, "backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt").readText()
        val literal = Regex("""val DAY_LOCKED_TYPES = setOf\(([^)]*)\)""").find(source)?.groupValues?.get(1)
        assertNotNull("IngestService.DAY_LOCKED_TYPES is a setOf literal", literal)
        val server = Regex("\"([a-z_]+)\"").findAll(literal!!).map { it.groupValues[1] }.toSet()
        assertTrue("stock_movement is locked on the server", "stock_movement" in server)
        assertEquals(server, FinalSubmitLock.LOCKED_TYPES)
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
