package com.aktcl.aron.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbRecheckTest {
    private class Client : BreadcrumbClient {
        var starts = 0; var stops = 0; var active = false; var interval = 0L
        override fun start(intervalMs: Long, minDisplacementM: Double) { starts++; active = true; interval = intervalMs }
        override fun stop() { stops++; active = false }
    }
    private val clock = FakeClock()
    private val client = Client()
    private val stored = mutableListOf<TakenFix>()
    private var settings = BreadcrumbSettings(enabled = true, intervalMin = 10)
    private var failNext = false
    private val store = MemoryBreadcrumbStateStore()
    private val c = BreadcrumbController(client, store, {
        if (failNext) { failNext = false; throw IllegalStateException("db") }; stored += it }, { honestDevice }, clock) { settings }

    private fun loc(m: Int, elapsedMs: Long = clock.elapsedMs) = RawLocation(23.7 + m * 0.00108, 90.4, 20.0, provider = "fused",
        timeMs = clock.wallMs, elapsedRealtimeNanos = elapsedMs * 1_000_000L, isMock = false)

    @Test fun r1_intervalChangeWhileRunningIsAppliedToTheRequest() {
        c.onCheckIn(); assertEquals(600_000L, client.interval)
        settings = settings.copy(intervalMin = 30)
        c.reconcile(80, false)
        assertEquals("platform request still at old interval", 1_800_000L, client.interval)
    }

    @Test fun r2_deliveryWhileOffRemovesStaleRegistration() {
        c.onCheckIn(); assertTrue(client.active)
        settings = BreadcrumbSettings(enabled = false) // policy changed, reconcile not (yet) called
        clock.advance(600_000)
        c.onLocations(listOf(loc(10)), 80, false)
        assertTrue(stored.isEmpty())
        assertFalse("stale request keeps taking fixes while off", client.active)
    }

    @Test fun r3_rebootMidDayResumesBreadcrumbs() {
        clock.elapsedMs = 60_000 // phone booted just before check-in
        c.onCheckIn()
        repeat(240) { clock.advance(60_000); c.onLocations(listOf(loc(it)), 80, false) }
        val before = stored.size
        clock.elapsedMs = 30_000 // reboot: elapsed realtime restarts
        c.reconcile(80, false)
        repeat(120) { clock.advance(60_000); c.reconcile(80, false); c.onLocations(listOf(loc(1000 + it)), 80, false) }
        assertTrue("kept after reboot: ${stored.size - before}", stored.size - before >= 10)
    }

    @Test fun r4_checkInBelowBatteryFloorDoesNotStart() {
        c.onCheckIn() // battery 10% not passed to onCheckIn
        c.reconcile(10, false)
        assertFalse(client.active)
    }

    @Test fun r5_sinkFailureThenOverlappingBatchKeepsSpacing() {
        val t0 = clock.elapsedMs
        c.onCheckIn()
        clock.advance(31 * 60_000)
        failNext = false
        val b1 = (1..31).map { loc(it, t0 + it * 60_000L) }
        // fail on the third store
        var n = 0
        val c2 = BreadcrumbController(client, store, { n++; if (n == 3) throw IllegalStateException("db"); stored += it }, { honestDevice }, clock) { settings }
        c2.onLocations(b1, 80, false)
        val b2 = (15..40).map { loc(it, t0 + it * 60_000L) }
        clock.advance(10 * 60_000)
        c2.onLocations(b2, 80, false)
        val t = stored.map { it.fixElapsedRealtimeMs!! }.sorted()
        t.zipWithNext().forEach { (a, b) -> assertTrue("spacing ${(b - a) / 60000} min in $t", b - a >= 600_000L) }
    }

    @Test fun r6_eightHoursEveryMinuteDuplicatedAndShuffledKeepsAtMost48() {
        c.onCheckIn()
        val start = clock.elapsedMs
        var pending = mutableListOf<RawLocation>()
        for (m in 1..480) {
            clock.advance(60_000)
            val l = loc(m); pending += l; pending += l
            if (m % 7 == 0) { c.onLocations(pending.shuffled(), 80, false); pending = mutableListOf() }
        }
        c.onLocations(pending, 80, false)
        assertTrue("kept ${stored.size}", stored.size <= 48)
        assertTrue(stored.all { it.fixTime!!.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")) && it.fixAgeMs!! >= 0 })
        assertTrue(stored.all { it.fixElapsedRealtimeMs!! > start })
    }
}
