package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.RouteDayFinalSubmittedException
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual A3, checker fix (medium): on a route closed by its zone's Final Submit the AMO starts no control, joint or sale
 * call (the visit flow's committer refuses it, [AmoDay.routeLocked] reads the same state), and a call already open when
 * the lock arrives ends on the phone without a close the server would refuse; neither throws (D-TSO-MAN-31, D-A3-22).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoDayLockCallTest {
    private val user = 12L
    private val date = "2026-10-26"
    private val route = 10231L
    private val other = 10232L
    private val now = "2026-10-26T12:00:00.000Z"
    private lateinit var databases: UserDatabases
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository

    @Before fun setUp() {
        databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null }
        db = runBlocking { databases.of(user) }
        repo = CaptureRepository(db) { now }
    }

    @After fun tearDown() = databases.close(user)

    private fun meta(routeId: Long = route) = CaptureMeta(
        businessDate = date, capturedAt = now, capturedElapsedMs = 1_000, bootCount = 3, clockOffsetMs = 0L, capturedOffline = true,
        routeId = routeId, bundleVersion = "$date:1", configVersion = 1L,
    )

    private suspend fun finalSubmitted(routeId: Long) = db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
        """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-26T11:30:00.000Z"}}""")))

    private suspend fun controlCall(routeId: Long, seq: Int): OpenVisit {
        val u = ClientIds.newUuid()
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = now, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = meta(routeId), visitKind = "amo_control_call", outletId = 50001, openedAt = now, sequenceNo = seq, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        return OpenVisit(u, 50001, routeId, "in_range", "sale_allowed", true, false, null, now)
    }

    @Test fun aCallOnAClosedRouteCannotStartAndOneInProgressEndsOnThePhone() = runBlocking {
        val v = controlCall(route, 1)
        val session = VisitSession().also { it.restore(v) }
        finalSubmitted(route) // the lock arrives during the control call
        assertTrue(AmoCallLock.endsOnPhone(db, session, meta()))
        assertNull(session.current.value)
        assertTrue("no close the server would refuse", db.captureDao().visitClosesOn(date).isEmpty())
        assertNull("not restored after a relaunch", FinalSubmitLock.callToRestore(db, date) { it.visitKind.startsWith("amo_") })
        val e = assertThrows(RouteDayFinalSubmittedException::class.java) { runBlocking { FinalSubmitLock.requireCallOpen(db, meta()) } }
        assertEquals(route, e.routeId)
        // another route of the zone stays open: a call there starts and ends as before
        FinalSubmitLock.requireCallOpen(db, meta(other))
        val open = controlCall(other, 2)
        assertFalse(AmoCallLock.endsOnPhone(db, VisitSession().also { it.restore(open) }, meta(other)))
        assertEquals(open.visitUuid, FinalSubmitLock.callToRestore(db, date) { it.visitKind.startsWith("amo_") }?.clientUuid)
    }
}
