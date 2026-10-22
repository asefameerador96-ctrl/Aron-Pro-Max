package com.aktcl.aron.core.sync.device

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.geo.BreadcrumbClient
import com.aktcl.aron.core.geo.BreadcrumbController
import com.aktcl.aron.core.geo.FixDeviceState
import com.aktcl.aron.core.geo.FixPriority
import com.aktcl.aron.core.geo.FixProvider
import com.aktcl.aron.core.geo.FixPurpose
import com.aktcl.aron.core.geo.FixStatus
import com.aktcl.aron.core.geo.MemoryBreadcrumbStateStore
import com.aktcl.aron.core.geo.RawLocation
import com.aktcl.aron.core.geo.TakenFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-MDM-BREADCRUMB-01: the controller is installed for SR and AMO through DeviceRuntime; this is the shared logic they call. */
class BreadcrumbWiringTest {
    private class Client : BreadcrumbClient {
        var starts = 0; var stops = 0; var active = false; var intervalMs = 0L
        override fun start(intervalMs: Long, minDisplacementM: Double) { starts++; active = true; this.intervalMs = intervalMs }
        override fun stop() { stops++; active = false }
    }

    private class Clock : WallClock {
        var wall = 1_800_000_000_000L; var elapsed = 10_000_000L
        override fun nowMs() = wall
        override fun elapsedRealtimeMs() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }

    private val client = Client()
    private val clock = Clock()
    private val device = FixDeviceState(deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
    private val kept = mutableListOf<TakenFix>()
    private var cfgEnabled = false
    private var cfgInterval = 30
    private var mdmMode: String? = "on_demand"
    private var mdmInterval: Int? = null
    private val controller = BreadcrumbController(
        client, MemoryBreadcrumbStateStore(), { kept += it }, { device }, clock,
        BreadcrumbWiring.settings({ cfgEnabled }, { cfgInterval }, { mdmMode }, { mdmInterval }),
    )

    @Test fun offByDefaultNothingStartsAtCheckInOrReconcile() {
        controller.reconcile(80, false)
        controller.onCheckIn(80, false)
        controller.reconcile(80, false)
        assertEquals(0, client.starts)
        assertFalse(client.active)
    }

    @Test fun mdmIntervalStartsItAtThePolicyIntervalWithAFloorOfFiveMinutes() {
        mdmMode = "interval"; mdmInterval = 15
        controller.onCheckIn(80, false)
        assertTrue(client.active); assertEquals(15 * 60_000L, client.intervalMs)
        mdmInterval = 1
        controller.reconcile(80, false)   // a policy change restarts the request with the floored interval
        assertEquals(5 * 60_000L, client.intervalMs); assertEquals(2, client.starts)
    }

    @Test fun mdmOffStopsItEvenWhenTheConfigWantsIt() {
        cfgEnabled = true
        controller.onCheckIn(80, false)
        assertTrue(client.active); assertEquals(30 * 60_000L, client.intervalMs)
        mdmMode = "off"
        controller.reconcile(80, false)
        assertFalse(client.active)
        mdmMode = "on_demand"; controller.reconcile(80, false)
        assertTrue(client.active)   // back to the config: on
    }

    @Test fun aMissingDpcPolicyLeavesTheConfigAlone() {
        mdmMode = null; cfgEnabled = false
        controller.onCheckIn(80, false)
        assertEquals(0, client.starts)
        cfgEnabled = true; controller.reconcile(80, false)
        assertEquals(1, client.starts)
    }

    @Test fun mdmIntervalNeedsTheBackgroundGrantWhenTheConfigIsOff() {
        val noGrant = BreadcrumbWiring.settings({ false }, { 30 }, { "interval" }, { 15 }, backgroundGranted = { false })()
        assertFalse(noGrant.enabled)
        val granted = BreadcrumbWiring.settings({ false }, { 30 }, { "interval" }, { 15 }, backgroundGranted = { true })()
        assertTrue(granted.enabled); assertEquals(15, granted.intervalMin)
        assertFalse(BreadcrumbWiring.settings({ true }, { 30 }, { "off" }, { null }, backgroundGranted = { false })().enabled)
    }

    @Test fun aWindowBelongsToOneUserAndOneDay() {
        val o = BreadcrumbOwner("2026-10-22", 7)
        assertTrue(BreadcrumbWiring.ownerStillValid(o, "2026-10-22", 7))
        assertFalse(BreadcrumbWiring.ownerStillValid(o, "2026-10-23", 7))   // forgot to check out
        assertFalse(BreadcrumbWiring.ownerStillValid(o, "2026-10-22", 8))   // shared phone, another rep signed in
        assertFalse(BreadcrumbWiring.ownerStillValid(o, "2026-10-22", null)) // logged out
        assertFalse(BreadcrumbWiring.ownerStillValid(null, "2026-10-22", 7))
    }

    @Test fun anOffControllerStopsAtMostOncePerProcess() {
        controller.reconcile(80, false); controller.reconcile(80, false); controller.reconcile(80, false)
        assertEquals(1, client.stops)
        mdmMode = "interval"; mdmInterval = 10; controller.onCheckIn(80, false)
        mdmMode = "off"; controller.reconcile(80, false)
        assertEquals(2, client.stops)
        controller.reconcile(80, false)
        assertEquals(2, client.stops)
    }

    @Test fun mdmIntervalDoesNothingBeforeCheckInOrAfterCheckOut() {
        mdmMode = "interval"; mdmInterval = 10
        controller.reconcile(80, false)
        assertEquals(0, client.starts)
        controller.onCheckIn(80, false); controller.onCheckOut()
        assertFalse(client.active)
    }

    @Test fun aLowBatteryStopsIt() {
        mdmMode = "interval"; mdmInterval = 10
        controller.onCheckIn(80, false)
        controller.reconcile(10, false)
        assertFalse(client.active)
    }

    @Test fun aMockedFixIsKeptWithItsMockFlagForTheServer() {
        mdmMode = "interval"; mdmInterval = 5
        controller.onCheckIn(80, false)
        clock.advance(6 * 60_000L)
        val n = controller.onLocations(listOf(RawLocation(23.78, 90.41, 12.0, provider = "fused", timeMs = clock.wall, elapsedRealtimeNanos = clock.elapsed * 1_000_000L, isMock = true)), 80, false)
        assertEquals(1, n); assertTrue(kept.single().isMock)
        assertEquals(FixPurpose.BREADCRUMB, kept.single().purpose)
    }

    private val taken = TakenFix(
        FixPurpose.BREADCRUMB, FixStatus.OK, 23.78, 90.41, 12.0, null, null, null, null, FixProvider.FUSED, "2026-10-22T10:00:00.000Z",
        5_000L, 1_000L, null, FixPriority.BALANCED, true, false, 0, null, device,
    )
    private val meta = CaptureMeta(businessDate = "2026-10-22", capturedAt = "2026-10-22T10:00:00.000Z", capturedElapsedMs = 5L, bootCount = 1, clockOffsetMs = 0L, capturedOffline = false, routeId = null, bundleVersion = "b1", configVersion = 3L)

    @Test fun theSinkWritesOneFixKeyedToItsOwnBreadcrumbAndSwallowsFailures() {
        var written: Triple<String, CaptureMeta, com.aktcl.aron.core.database.entity.GeoFixEntity>? = null
        var n = 0
        val ids = generateSequence { java.util.UUID.randomUUID().toString() }.iterator()
        BreadcrumbWiring.sink({ ids.next() }, { meta }) { id, m, f -> written = Triple(id, m, f) }.store(taken)
        val (id, _, fix) = written!!
        assertEquals(id, fix.ownerClientUuid); assertEquals("breadcrumb", fix.purpose); assertEquals("fused", fix.provider)
        assertEquals("balanced", fix.requestPriority); assertTrue(fix.isMock); assertTrue(fix.deviceOwner)
        // No signed-in user: nothing is written. A failing write never reaches the receiver.
        BreadcrumbWiring.sink({ ids.next() }, { null }) { _, _, _ -> n++ }.store(taken)
        assertEquals(0, n)
        BreadcrumbWiring.sink({ ids.next() }, { meta }) { _, _, _ -> error("db closed") }.store(taken)
    }
}
