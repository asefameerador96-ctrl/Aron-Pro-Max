package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-AGATE-06: which route-days the check-out submits, with what counts, and what it leaves alone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AutoDaySubmitsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val repo = CaptureRepository(db) { "2026-10-05T11:30:00.000Z" }
    private val date = "2026-10-05"

    private fun meta(route: Long?) = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, route, null, "b", false, 1)
    private fun stock(route: Long?) = StockMovementEntity(ClientIds.newUuid(), meta(route), "issue", 100, 10, "pack", 10, 100, null, false)
    private fun sr() = AutoDaySubmits(db, repo, { r -> meta(r) }, { "cigarette" })
    private fun amo() = AutoDaySubmits(db, repo, { meta(null) }, { "cigarette" }, scope = "supervisor_day")

    @Test fun oneSubmitPerRouteDayThatHasCapturesAndNoneForAnEmptyDay() = runTest {
        assertEquals("nothing captured: nothing to submit", emptyList<Any>(), sr().build(date))
        repo.recordStock(listOf(stock(10231), stock(10232)))
        val built = sr().build(date)
        assertEquals(listOf(10231L, 10232L), built.map { it.meta.routeId })
        assertTrue(built.all { it.scope == "route_day" && it.submitCycle == 1 && it.meta.businessDate == date })
        assertTrue(built.all { it.deviceCountsJson.contains("stock_movement") })
    }

    @Test fun aRouteDayWithAStandingSubmitIsLeftAloneAndTheNextCycleFollowsARefusedOne() = runTest {
        repo.recordStock(listOf(stock(10231), stock(10232)))
        val first = sr().build(date).first { it.meta.routeId == 10231L }
        repo.recordDaySubmit(first) // the rep's own submit of route 10231
        assertEquals("only the other route-day is submitted by the check-out", listOf(10232L), sr().build(date).map { it.meta.routeId })
        // the server refused route 10231's submit: it no longer stands, and the next cycle is 2
        db.outboxDao().applyAck(db.outboxDao().nextPending(100).first { it.recordType == "day_submit" }.clientUuid, "rejected", "schema_invalid", null, "t")
        val again = sr().build(date).first { it.meta.routeId == 10231L }
        assertEquals(2, again.submitCycle)
    }

    /**
     * Review round 3, B3: the rep's own submit stands AND the zone's Final Submit has locked the route-day (policy reject). The old filter asked
     * the Final Submit lock first, so it built a second submit that the writer refuses and every check-out failed. A standing submit is skipped
     * whatever the Final Submit state, and the check-out then goes through (writing only itself).
     */
    @Test fun aStandingSubmitIsSkippedWhateverTheFinalSubmitStateAndTheCheckOutStillWorks() = runTest {
        repo.recordStock(listOf(stock(10231)))
        repo.recordDaySubmit(sr().build(date).single()) // the rep's own Sales Submit
        db.referenceDao().insertConfig(listOf(com.aktcl.aron.core.database.entity.ConfigValueEntity(
            key = com.aktcl.aron.core.database.repo.FinalSubmitLock.KEY_LATE_POLICY, valueJson = "\"reject\"", scopeType = "global", scopeId = null,
            effectiveFrom = null, effectiveTo = null, configVersion = 1, requiresAck = false, scheduled = false,
        )))
        db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("route.10231",
            """{"route_id":10231,"targets":[],"day_state":{"route_id":10231,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-05T10:00:00.000Z"}}""")))
        assertEquals("a standing submit is never built again", emptyList<Any>(), sr().build(date))
        val ciUuid = ClientIds.newUuid()
        val inFix = com.aktcl.aron.core.database.entity.GeoFixEntity(
            ClientIds.newUuid(), ciUuid, "attendance_in", "ok", 23.79, 90.4, 8.0, provider = "fused", isMock = false, reused = false,
            deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        val outUuid = ClientIds.newUuid()
        val fx = inFix.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = outUuid, purpose = "attendance_out")
        repo.recordAttendance(com.aktcl.aron.core.database.entity.AttendanceEventEntity(ciUuid, meta(null), "check_in", inFix.clientUuid, null), inFix)
        repo.recordCheckOut(com.aktcl.aron.core.database.entity.AttendanceEventEntity(outUuid, meta(null), "check_out", fx.clientUuid, null), fx) { d -> sr().build(d) }
        assertEquals("the check-out wrote only itself: still the one submit", 1, db.captureDao().daySubmitsOn(date).size)
        assertTrue(db.outboxDao().nextPending(100).any { it.recordType == "attendance_event" })
    }

    @Test fun theAmoGetsOneSupervisorDaySubmitWithNoRoute() = runTest {
        repo.recordVisitSkip(VisitSkipEntity(ClientIds.newUuid(), meta(10231), 50001, "closed"))
        val built = amo().build(date)
        assertEquals(1, built.size); assertEquals("supervisor_day", built.single().scope); assertEquals(null, built.single().meta.routeId)
    }

    @Test fun theRealPendingCountAndTheRefusedCountsAreCarried() = runTest {
        val ciUuid = ClientIds.newUuid()
        val fx = com.aktcl.aron.core.database.entity.GeoFixEntity(
            ClientIds.newUuid(), ciUuid, "attendance_in", "ok", 23.79, 90.4, 8.0, provider = "fused", isMock = false, reused = false,
            deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordAttendance(com.aktcl.aron.core.database.entity.AttendanceEventEntity(ciUuid, meta(null), "check_in", fx.clientUuid, null), fx)
        repo.recordStock(listOf(stock(10231)))
        val refused = db.outboxDao().nextPending(10).first { it.recordType == "stock_movement" }.clientUuid
        db.outboxDao().applyAck(refused, "rejected", "price_invalid", null, "t")
        repo.recordStock(listOf(stock(10231).copy(skuId = 101)))
        // the builder reports the refused count; the transaction of the check-out sets the REAL pending count at that moment
        assertEquals("one row refused, counted", 1, sr().build(date).single().rejectedCount)
        val outUuid = ClientIds.newUuid()
        val outFix = fx.copy(clientUuid = ClientIds.newUuid(), ownerClientUuid = outUuid, purpose = "attendance_out")
        repo.recordCheckOut(com.aktcl.aron.core.database.entity.AttendanceEventEntity(outUuid, meta(null), "check_out", outFix.clientUuid, null), outFix) { d -> sr().build(d) }
        assertEquals("the check-in row and the second stock row are still waiting (was a constant 0)", 2, db.captureDao().daySubmitsOn(date).single().pendingCount)
    }
}
