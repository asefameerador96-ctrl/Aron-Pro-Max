package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.RouteDaySubmittedException
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
 * Owner decision 2026-11-04 (D-TSO-MAN-49): a capture after the TSO's Final Submit is accepted and flagged by the server,
 * never refused, so the phone's day lock is off by default. [FinalSubmitLock.lockApplies] is the one predicate: the
 * late-sale policy `cfg.day.late_sync_after_final_policy` in the held config (in the device bundle since V0142) is
 * quarantine or reject. Absent, unreadable or accept_and_flag, a final-submitted route-day takes every capture through
 * CaptureRepository as an open one; the Sales Submit refusal is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LatePolicyLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val now = "2026-10-05T16:00:00.000Z"
    private val repo = CaptureRepository(db) { now }
    private val route = TestRows.meta().routeId!!
    private val date = TestRows.meta().businessDate
    private val numbering = MemoNumbering("sr334001", 0, 500)

    @After fun tearDown() = db.close()

    /** The zone's Final Submit as the phone learns it: the route section's day_state says final_submitted. */
    private suspend fun finalSubmitted(routeId: Long = route) = db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
        """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-05T15:00:00.000Z"}}""")))

    /** A raw config row of the policy key, as a bundle could hold it ([json] is the stored JSON text). */
    private fun row(json: String, scheduled: Boolean = false, from: String? = null, to: String? = null) = ConfigValueEntity(
        key = FinalSubmitLock.KEY_LATE_POLICY, valueJson = json, scopeType = "global", scopeId = null, effectiveFrom = from,
        effectiveTo = to, configVersion = 1, requiresAck = false, scheduled = scheduled,
    )

    /**
     * X4a fix round (checker medium): an admin's quarantine or reject with an end time (`effective_to`, ConfigService) stops
     * locking at that end on the phone, as the server's DbServerConfig stops applying it there and falls back to
     * accept_and_flag. No config delta brings that reversion (the ended row and the default resolve alike), so the phone
     * must not keep the ended value until its next full bundle. A scheduled value still wins inside its own window.
     */
    @Test fun aTimeLimitedQuarantineOrRejectStopsLockingAtItsEnd() = runTest {
        finalSubmitted()
        val before = "2026-10-05T16:59:59.999Z"
        val end = "2026-10-05T17:00:00.000Z"
        val later = "2026-10-05T18:30:00.000Z"
        for (policy in listOf("quarantine", "reject")) {
            db.referenceDao().deleteConfigKey(FinalSubmitLock.KEY_LATE_POLICY)
            // the resolved row of the bundle: the admin's value, valid until 17:00
            db.referenceDao().insertConfig(listOf(row("\"$policy\"", from = "2026-10-05T08:00:00.000Z", to = end)))
            assertTrue(policy, FinalSubmitLock.lockApplies(db, before))
            assertFalse("$policy ended at 17:00", FinalSubmitLock.lockApplies(db, end))
            assertFalse("$policy stays ended", FinalSubmitLock.lockApplies(db, later))
            // every gated read follows the predicate: the capture checks, Mark Paid, the banners and the AMO route check
            assertEquals(DayRefusal.FINAL_SUBMIT, CaptureRepository(db) { before }.dayRefusal(TestRows.meta(), "memo"))
            assertNull(CaptureRepository(db) { end }.dayRefusal(TestRows.meta(), "memo"))
            assertNull(CaptureRepository(db) { later }.dayRefusal(TestRows.meta(), "stock_movement"))
            assertTrue("Mark Paid is drawn again", CaptureRepository(db) { end }.acceptsDueCollection(TestRows.meta()))
            assertTrue(FinalSubmitLock.locks(db, date, route, before))
            assertFalse(FinalSubmitLock.locks(db, date, route, end))
            assertEquals(emptyList<Long>(), FinalSubmitLock.lockedRoutes(db, date, listOf(route), end))
            assertFalse(FinalSubmitLock.refuses(db, TestRows.meta(), "visit", end))
            // the plain config read keeps its rule for its other callers (unchanged by this fix)
            assertEquals("\"$policy\"", com.aktcl.aron.core.database.repo.ReferenceRepository(db).config(FinalSubmitLock.KEY_LATE_POLICY, later))
        }
        // a scheduled value that follows the ended one applies inside its own window
        db.referenceDao().deleteConfigKey(FinalSubmitLock.KEY_LATE_POLICY)
        db.referenceDao().insertConfig(listOf(
            row("\"quarantine\"", from = "2026-10-05T08:00:00.000Z", to = end),
            row("\"reject\"", scheduled = true, from = "2026-10-05T18:00:00.000Z"),
        ))
        assertFalse(FinalSubmitLock.lockApplies(db, "2026-10-05T17:30:00.000Z"))
        assertTrue(FinalSubmitLock.lockApplies(db, later))
    }

    @Test fun onlyQuarantineOrRejectLockAndAnAbsentOrUnreadablePolicyDoesNot() = runTest {
        assertFalse("no policy in the bundle: the server's fallback, accept_and_flag", FinalSubmitLock.lockApplies(db, now))
        for ((policy, locks) in listOf("accept_and_flag" to false, "quarantine" to true, "reject" to true)) {
            TestRows.latePolicy(db, policy)
            assertEquals(policy, locks, FinalSubmitLock.lockApplies(db, now))
        }
        // unreadable values read as accept_and_flag, as on the server
        for (raw in listOf("quarantine", "\"QUARANTINE\"", "\"\"", "1", "true", "null", "[\"reject\"]", "{\"v\":\"reject\"}", "not json")) {
            db.referenceDao().deleteConfigKey(FinalSubmitLock.KEY_LATE_POLICY)
            db.referenceDao().insertConfig(listOf(row(raw)))
            assertFalse(raw, FinalSubmitLock.lockApplies(db, now))
        }
    }

    /**
     * The phone locks under exactly the server's late policies but accept_and_flag, and the server's fallback for an
     * unreadable value is accept_and_flag (IngestService, read from the source in this repository, as the LOCKED_TYPES parity).
     */
    @Test fun theLockingPoliciesAreTheServersLatePoliciesButItsFallback() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile && java.io.File(it, "backend").isDirectory }
        val source = java.io.File(root, "backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt").readText()
        val literal = Regex("""val LATE_POLICIES = setOf\(([^)]*)\)""").find(source)?.groupValues?.get(1)
        assertNotNull("IngestService.LATE_POLICIES is a setOf literal", literal)
        val server = Regex("\"([a-z_]+)\"").findAll(literal!!).map { it.groupValues[1] }.toSet()
        val fallback = Regex("""takeIf \{ it in LATE_POLICIES \} \?: "([a-z_]+)"""").find(source)?.groupValues?.get(1)
        assertEquals("the server falls back to accept_and_flag", "accept_and_flag", fallback)
        assertEquals(server - "accept_and_flag", FinalSubmitLock.LOCKING_POLICIES)
    }

    @Test fun aScheduledPolicyLocksFromItsStartAtTheRepositorysTrustedNow() = runTest {
        db.referenceDao().insertConfig(listOf(row("\"accept_and_flag\""), row("\"quarantine\"", scheduled = true, from = "2026-10-05T17:00:00.000Z")))
        finalSubmitted()
        assertFalse(FinalSubmitLock.lockApplies(db, "2026-10-05T16:59:59.999Z"))
        assertTrue(FinalSubmitLock.lockApplies(db, "2026-10-05T17:00:00.000Z"))
        // the same stored meta (captured at 04:31) answers by the repository's now, not by its capture time
        assertNull(CaptureRepository(db) { "2026-10-05T16:59:59.999Z" }.dayRefusal(TestRows.meta(), "memo"))
        assertEquals(DayRefusal.FINAL_SUBMIT, CaptureRepository(db) { "2026-10-05T17:00:00.000Z" }.dayRefusal(TestRows.meta(), "memo"))
        assertEquals(DayRefusal.FINAL_SUBMIT, CaptureRepository(db) { "2026-10-05T17:00:00.000Z" }.dayRefusal(TestRows.meta(), "stock_movement"))
        assertFalse(FinalSubmitLock.locks(db, date, route, "2026-10-05T16:59:59.999Z"))
        assertTrue(FinalSubmitLock.locks(db, date, route, "2026-10-05T17:00:00.000Z"))
    }

    @Test fun withNoPolicyInTheBundleAFinalRouteDayTakesEveryCapture() = runTest { everyCaptureIsTaken() }

    @Test fun underAcceptAndFlagAFinalRouteDayTakesEveryCapture() = runTest {
        TestRows.latePolicy(db, "accept_and_flag")
        everyCaptureIsTaken()
    }

    private suspend fun everyCaptureIsTaken() {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix) // the call opened before the Final Submit
        finalSubmitted()
        assertTrue("the phone learned the Final Submit", FinalSubmitLock.isFinal(db, date, route))
        assertFalse("but does not lock", FinalSubmitLock.locks(db, date, route, now))
        assertEquals(emptyList<Long>(), FinalSubmitLock.lockedRoutes(db, date, listOf(route), now))
        assertEquals(listOf(route), FinalSubmitLock.finalRoutes(db, date, listOf(route)))
        for (t in FinalSubmitLock.LOCKED_TYPES) {
            assertFalse(t, FinalSubmitLock.refuses(db, TestRows.meta(), t))
            assertNull(t, repo.dayRefusal(TestRows.meta(), t))
        }
        assertTrue("Mark Paid is drawn", repo.acceptsDueCollection(TestRows.meta()))
        FinalSubmitLock.requireCallOpen(db, TestRows.meta())
        assertEquals("a relaunch restores the call", visit.clientUuid, FinalSubmitLock.callToRestore(db, date) { true }?.clientUuid)

        val (late, lateFix) = TestRows.visit(seq = 2)
        repo.recordVisitOpen(late, lateFix)
        val memoNo = repo.recordNumberedSale(TestRows.sale(visit.clientUuid), numbering)
        assertEquals(1, db.referenceDao().memoCounter(date))
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "shop_closed")
        repo.recordVisitSkip(skip)
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        repo.recordDueCollection(due)
        val stock = TestRows.stock(skuId = 100)
        repo.recordStock(listOf(stock))
        val close = TestRows.close(visit.clientUuid)
        repo.recordVisitClose(close)
        for ((uuid, type) in listOf(late.clientUuid to "visit", skip.clientUuid to "visit_skip", due.clientUuid to "due_collection", stock.clientUuid to "stock_movement", close.clientUuid to "visit_close")) {
            assertEquals(type, db.outboxDao().byClientUuid(uuid)?.recordType)
        }
        assertEquals(listOf(memoNo), db.captureDao().memosOn(date).map { it.memoNo })

        // the SR's own Sales Submit still closes the route-day with its own refusal (X4a's SALES_SUBMIT, unchanged)
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        assertEquals(DayRefusal.SALES_SUBMIT, repo.dayRefusal(TestRows.meta(), "memo"))
        assertFalse(repo.acceptsDueCollection(TestRows.meta()))
        assertThrows(RouteDaySubmittedException::class.java) { runBlocking { repo.recordVisitSkip(VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50003, "shop_closed")) } }
        assertNull("stock is not refused by a Sales Submit", repo.dayRefusal(TestRows.meta(), "stock_movement"))
        assertNotNull(db.outboxDao().byClientUuid(close.clientUuid))
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
