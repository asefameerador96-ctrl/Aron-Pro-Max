package com.aktcl.aron.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbTest {
    private class Client : BreadcrumbClient {
        var starts = 0
        var stops = 0
        var active = false
        var interval = 0L
        override fun start(intervalMs: Long, minDisplacementM: Double) { starts++; active = true; interval = intervalMs }
        override fun stop() { stops++; active = false }
    }

    private val clock = FakeClock()
    private val client = Client()
    private val stored = mutableListOf<TakenFix>()
    private var settings = BreadcrumbSettings(enabled = true, intervalMin = 10)
    private val c = BreadcrumbController(client, MemoryBreadcrumbStateStore(), { stored += it }, { honestDevice }, clock) { settings }

    /** One location per minute, moving 120 m per minute, delivered in batches of [batch] minutes. */
    private fun day(hours: Int, batch: Int = 10, battery: (Int) -> Int = { 80 }) {
        var pending = mutableListOf<RawLocation>()
        for (m in 1..hours * 60) {
            clock.advance(60_000)
            pending += RawLocation(23.7 + m * 0.00108, 90.4, 20.0, provider = "fused", timeMs = clock.wallMs,
                elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = false)
            if (m % batch == 0) { c.onLocations(pending, battery(m), charging = false); pending = mutableListOf() }
        }
    }

    @Test fun anEightHourDayAtTenMinutesKeepsAtMost48Points() {
        c.onCheckIn()
        assertTrue(client.active)
        assertEquals(600_000L, client.interval)
        day(8)
        assertTrue("kept ${stored.size}", stored.size <= 48)
        assertEquals(48, stored.size)
        assertTrue(stored.all { it.purpose == FixPurpose.BREADCRUMB && it.fixStatus == FixStatus.OK && !it.reused })
        // Kept points are at least one interval apart, judged by the fix's own time.
        stored.zipWithNext().forEach { (a, b) -> assertTrue(b.fixElapsedRealtimeMs!! - a.fixElapsedRealtimeMs!! >= 600_000L) }
    }

    @Test fun lateBatchedDeliveriesDoNotAddPoints() {
        c.onCheckIn()
        day(8, batch = 30) // the chip holds points for 30 minutes
        assertTrue(stored.size <= 48)
    }

    @Test fun withTheSettingOffNoFixIsEverRequestedOrKept() {
        settings = BreadcrumbSettings(enabled = false)
        c.onCheckIn()
        c.reconcile(80, false)
        day(8)
        assertEquals(0, client.starts)
        assertTrue(stored.isEmpty())
    }

    @Test fun nothingBeforeCheckInOrAfterCheckOut() {
        c.reconcile(80, false)
        assertEquals(0, client.starts)
        day(1)
        assertTrue(stored.isEmpty())
        c.onCheckIn(); day(1); c.onCheckOut()
        assertTrue(!client.active)
        val n = stored.size
        day(1)
        assertEquals(n, stored.size)
    }

    @Test fun lowBatteryStopsTheRequestAndKeepsNothing() {
        c.onCheckIn()
        day(4, battery = { m -> if (m > 120) 15 else 80 })
        val atTwoHours = stored.size
        assertTrue(atTwoHours <= 12)
        assertTrue(!client.active)
        assertTrue(stored.size == atTwoHours)
    }

    @Test fun aStandingRepAddsNoPoints() {
        c.onCheckIn()
        for (m in 1..120) {
            clock.advance(60_000)
            c.onLocations(listOf(RawLocation(23.7, 90.4 + (m % 2) * 0.0001, 15.0, provider = "fused", timeMs = clock.wallMs,
                elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = false)), 80, false)
        }
        assertEquals(1, stored.size) // the first point; then every fix is within 50 m of it
    }

    @Test fun theMockFlagIsKeptAndBadFixesDropped() {
        c.onCheckIn()
        clock.advance(11 * 60_000L)
        c.onLocations(listOf(
            RawLocation(Double.NaN, 90.4, 5.0, provider = "gps", timeMs = 1, elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = false),
            RawLocation(23.71, 90.4, 5.0, provider = "gps", timeMs = 1, elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = true),
        ), 80, false)
        assertEquals(1, stored.size)
        assertTrue(stored.single().isMock)
    }

    @Test fun theIntervalIsClampedToTheRegistryRange() {
        assertEquals(5 * 60_000L, BreadcrumbSettings(true, intervalMin = 1).intervalMs)
        assertEquals(120 * 60_000L, BreadcrumbSettings(true, intervalMin = 999).intervalMs)
    }

    @Test fun anExceptionInTheSinkNeverEscapes() {
        val bad = BreadcrumbController(client, MemoryBreadcrumbStateStore(), { error("disk full") }, { honestDevice }, clock) { settings }
        bad.onCheckIn()
        clock.advance(11 * 60_000L)
        assertEquals(0, bad.onLocations(listOf(RawLocation(23.71, 90.4, 5.0, provider = "gps", timeMs = 1,
            elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = false)), 80, false))
    }
}
