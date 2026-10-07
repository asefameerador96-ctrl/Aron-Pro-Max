package com.aktcl.aron.core.geo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixManagerTest {
    private val clock = FakeClock()
    private val source = FakeSource(clock)
    private val access = FakeAccess()
    private val ledger = MemoryFixLedger()
    private var settings = FixSettings()
    private val manager = FixManager(source, access, { honestDevice }, clock, ledger, settings = { settings })

    @Test fun oneFixIsOneBalancedRequestWithTheConfiguredTimeoutAndEveryFieldStamped() = runTest {
        val f = manager.take(FixPurpose.VISIT_OPEN)
        assertEquals(1, source.requests)
        assertEquals(listOf(FixPriority.BALANCED), source.priorities)
        assertEquals(listOf(15_000L), source.timeouts)
        assertEquals(FixStatus.OK, f.fixStatus)
        assertEquals(FixProvider.FUSED, f.provider)
        assertEquals(12.0, f.accuracyM!!, 0.0)
        assertFalse(f.isMock)
        assertFalse(f.reused)
        assertEquals(3_000L, f.timeToFixMs)
        assertEquals(0L, f.fixAgeMs) // the fake stamps the location at the end of its work
        assertEquals(clock.elapsedMs, f.fixElapsedRealtimeMs)
        assertTrue(f.fixTime!!.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        assertEquals(honestDevice, f.device)
        assertEquals(FixPriority.BALANCED, f.requestPriority)
    }

    @Test fun theMockFlagFromTheProviderIsStampedOnTheFix() = runTest {
        source.next = { source.located(mock = true) }
        val f = manager.take(FixPurpose.VISIT_OPEN)
        assertTrue(f.isMock)
        assertEquals(FixStatus.OK, f.fixStatus)
    }

    @Test fun highAccuracyModeAndTimeoutComeFromConfigWithinTheirRanges() = runTest {
        settings = FixSettings(timeoutS = 999, priority = FixPriority.ofConfig("high"))
        manager.take(FixPurpose.ATTENDANCE_IN)
        settings = FixSettings(timeoutS = 1, priority = FixPriority.ofConfig("bogus"))
        manager.take(FixPurpose.ATTENDANCE_IN)
        assertEquals(listOf(30_000L, 5_000L), source.timeouts)
        assertEquals(listOf(FixPriority.HIGH_ACCURACY, FixPriority.BALANCED), source.priorities)
    }

    @Test fun aFixOfTheSameCycleIsReusedUpTo60sAndOnlyWhenAccurateTo30m() = runTest {
        val first = manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        clock.advance(60_000)
        val second = manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        assertEquals(1, source.requests)
        assertTrue(second.reused)
        assertEquals(first.lat, second.lat)
        assertEquals(60_000L, second.fixAgeMs)
        assertEquals(FixPurpose.VISIT_OPEN, second.purpose)

        clock.advance(1) // 60.001 s old: fresh request
        val third = manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        assertEquals(2, source.requests)
        assertFalse(third.reused)
    }

    @Test fun aCoarseFixIsNeverReused() = runTest {
        source.next = { source.located(accuracy = 30.5) }
        manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        assertEquals(2, source.requests)
        source.next = { source.located(accuracy = null) }
        manager.take(FixPurpose.VISIT_OPEN, cycle = "x")
        manager.take(FixPurpose.VISIT_OPEN, cycle = "x")
        assertEquals(4, source.requests)
    }

    @Test fun aRefreshAnotherCycleOrNoCycleAlwaysTakesAFreshFix() = runTest {
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a")
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a", refreshCount = 1)
        manager.take(FixPurpose.VISIT_OPEN, cycle = "b")
        manager.take(FixPurpose.VISIT_OPEN)
        assertEquals(4, source.requests)
    }

    @Test fun reuseFollowsElapsedRealtimeNotTheWallClock() = runTest {
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a")
        clock.wallMs -= 3_600_000 // the rep winds the date back an hour
        assertTrue(manager.take(FixPurpose.VISIT_OPEN, cycle = "a").reused)
        clock.wallMs += 7_200_000 // or forward
        assertTrue(manager.take(FixPurpose.VISIT_OPEN, cycle = "a").reused)
        clock.elapsedMs = 5_000 // a reboot: elapsed realtime restarts, the old fix is from another boot
        assertFalse(manager.take(FixPurpose.VISIT_OPEN, cycle = "a").reused)
    }

    @Test fun reuseCanBeSwitchedOffAndAWarmUpIsReusedOnce() = runTest {
        manager.warmUp("outlets")
        manager.warmUp("outlets") // already warm: no second request
        assertEquals(1, source.requests)
        assertTrue(manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets").reused)
        settings = FixSettings(reuseMaxAgeS = 0)
        assertFalse(manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets").reused)
        assertEquals(2, source.requests)
        manager.forget("outlets")
        settings = FixSettings()
        manager.take(FixPurpose.VISIT_OPEN, cycle = "outlets")
        assertEquals(3, source.requests)
    }

    @Test fun aFailedFixClearsTheCycleSoItIsNeverReused() = runTest {
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a")
        source.next = { SourceResult.TimedOut }
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a", refreshCount = 1)
        source.next = { source.located() }
        assertFalse(manager.take(FixPurpose.VISIT_OPEN, cycle = "a").reused)
        assertEquals(3, source.requests)
    }

    @Test fun permissionDeniedOrLocationOffNeverAsksTheProvider() = runTest {
        access.state = LocationAccessState.PERMISSION_DENIED
        val a = manager.take(FixPurpose.VISIT_OPEN)
        access.state = LocationAccessState.LOCATION_OFF
        val b = manager.take(FixPurpose.VISIT_OPEN)
        assertEquals(FixStatus.PERMISSION_DENIED, a.fixStatus)
        assertEquals(FixStatus.LOCATION_OFF, b.fixStatus)
        assertEquals(0, source.requests)
        assertEquals(0, ledger.fixes(com.aktcl.aron.rules.BusinessDate.of(clock.wallMs).toString()))
        listOf(a, b).forEach { assertNull(it.lat); assertNull(it.lng); assertNull(it.accuracyM); assertFalse(it.isMock) }
        assertEquals(true, access.lastRequirePrecise)
    }

    @Test fun aHangingProviderEndsAsTimeoutAndIsReleased() = runTest {
        source.hang = true
        val job = async { manager.take(FixPurpose.VISIT_OPEN) }
        advanceTimeBy(15_000 + FixManager.TIMEOUT_GRACE_MS + 1)
        val f = job.await()
        assertEquals(FixStatus.TIMEOUT, f.fixStatus)
        assertNull(f.lat)
        assertFalse(f.isMock)
        assertEquals(1, source.requests)
    }

    @Test fun providerErrorsMapToStatusesAndCancellationPropagates() = runTest {
        source.throwing = SecurityException("revoked")
        assertEquals(FixStatus.PERMISSION_DENIED, manager.take(FixPurpose.VISIT_OPEN).fixStatus)
        source.throwing = IllegalStateException("play services")
        assertEquals(FixStatus.PROVIDER_UNAVAILABLE, manager.take(FixPurpose.VISIT_OPEN).fixStatus)
        source.throwing = null
        source.next = { SourceResult.Unavailable }
        assertEquals(FixStatus.PROVIDER_UNAVAILABLE, manager.take(FixPurpose.VISIT_OPEN).fixStatus)
        source.throwing = CancellationException("screen closed")
        var cancelled = false
        try { manager.take(FixPurpose.VISIT_OPEN) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun garbageCoordinatesAreAProviderFailureThatKeepsTheMockFlag() = runTest {
        source.next = { source.located(lat = Double.NaN, mock = true) }
        val f = manager.take(FixPurpose.VISIT_OPEN, cycle = "a")
        assertEquals(FixStatus.PROVIDER_UNAVAILABLE, f.fixStatus)
        assertNull(f.lat)
        assertTrue(f.isMock)
        source.next = { source.located(lat = 91.0) }
        assertEquals(FixStatus.PROVIDER_UNAVAILABLE, manager.take(FixPurpose.VISIT_OPEN, cycle = "a").fixStatus)
    }

    @Test fun outOfContractValuesAreDroppedNotSent() = runTest {
        source.next = {
            SourceResult.Located(
                RawLocation(23.7, 90.4, accuracyM = Double.NaN, verticalAccuracyM = -1.0, speedMps = -2.0, bearingDeg = 400.0,
                    provider = "com.fake.provider", timeMs = 0, elapsedRealtimeNanos = 0, isMock = false),
            )
        }
        val f = manager.take(FixPurpose.VISIT_OPEN, refreshCount = 50)
        assertNull(f.accuracyM); assertNull(f.verticalAccuracyM); assertNull(f.speedMps); assertNull(f.bearingDeg)
        assertNull(f.fixTime); assertNull(f.fixElapsedRealtimeMs); assertNull(f.fixAgeMs)
        assertEquals(FixProvider.UNKNOWN, f.provider)
        assertEquals(10, f.refreshCount)
    }

    @Test fun aDoubleTapSharesOneProviderRequestInsideACycle() = runTest {
        val a = async { manager.take(FixPurpose.VISIT_OPEN, cycle = "o") }
        val b = async { manager.take(FixPurpose.VISIT_OPEN, cycle = "o") }
        val fixes = listOf(a.await(), b.await())
        assertEquals(1, source.requests)
        assertEquals(1, fixes.count { it.reused })
    }

    @Test fun theLedgerCountsProviderRequestsPerBusinessDate() = runTest {
        // 2026-10-02 23:59 Dhaka, then two minutes later it is the next business date.
        clock.wallMs = java.time.Instant.parse("2026-10-02T17:59:00Z").toEpochMilli()
        source.busyMs = 0
        manager.take(FixPurpose.VISIT_OPEN)
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a")
        manager.take(FixPurpose.VISIT_OPEN, cycle = "a") // reused: not a provider request
        clock.wallMs += 120_000
        manager.take(FixPurpose.ATTENDANCE_OUT)
        assertEquals(2, ledger.fixes("2026-10-02"))
        assertEquals(1, ledger.fixes("2026-10-03"))
    }

    @Test fun deviceStateIsReadAtEachFixIncludingAReuse() = runTest {
        var reads = 0
        val m = FixManager(source, access, { reads++; honestDevice.copy(adbEnabled = reads > 1) }, clock, ledger)
        assertFalse(m.take(FixPurpose.VISIT_OPEN, cycle = "a").device.adbEnabled)
        assertTrue(m.take(FixPurpose.VISIT_OPEN, cycle = "a").device.adbEnabled)
        assertEquals(2, reads)
    }

    @Test fun gnssWindowIsOpenOnlyAroundTheProviderRequest() = runTest {
        var open = 0
        var closed = 0
        val m = FixManager(source, access, { honestDevice }, clock, ledger, gnss = {
            open++
            FixWindow { closed++; """{"window_ms":3000}""" }
        })
        source.throwing = IllegalStateException()
        m.take(FixPurpose.VISIT_OPEN)
        source.throwing = null
        val f = m.take(FixPurpose.VISIT_OPEN, cycle = "a")
        val r = m.take(FixPurpose.VISIT_OPEN, cycle = "a")
        access.state = LocationAccessState.LOCATION_OFF
        m.take(FixPurpose.VISIT_OPEN)
        assertEquals(2, open)
        assertEquals(2, closed)
        assertEquals("""{"window_ms":3000}""", f.gnssJson)
        assertEquals(f.gnssJson, r.gnssJson)
    }

    @Test fun cancellingTheCallerReleasesTheProviderRequest() = runTest {
        source.hang = true
        val job = launch { manager.take(FixPurpose.VISIT_OPEN) }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()
        source.hang = false
        assertEquals(FixStatus.OK, manager.take(FixPurpose.VISIT_OPEN).fixStatus) // the mutex was released
    }

    @Test fun failedWarmUpsCountTowardTheCapSoAnIndoorDayStaysWithin80() = runTest {
        source.next = { SourceResult.TimedOut }
        manager.take(FixPurpose.ATTENDANCE_IN)
        repeat(60) {
            clock.advance(4 * 60_000); manager.warmUp("outlet-list"); clock.advance(90_000)
            manager.take(FixPurpose.VISIT_OPEN, cycle = "outlet-list")
        }
        manager.take(FixPurpose.ATTENDANCE_OUT)
        assertTrue("provider requests: ${source.requests}", source.requests <= 80)
        assertEquals(FixManager.MAX_WASTED_WARMUPS_PER_DAY, manager.wastedWarmUps())
    }

    @Test fun warmUpsOnDistinctCyclesAreCounted() = runTest {
        repeat(30) { i -> clock.advance(60_000); manager.warmUp("outlet-$i") }
        assertEquals(FixManager.MAX_WASTED_WARMUPS_PER_DAY, source.requests)
    }

    @Test fun aReusedWarmUpIsNotWasted() = runTest {
        manager.warmUp("list")
        assertEquals(1, manager.wastedWarmUps())
        assertTrue(manager.take(FixPurpose.VISIT_OPEN, cycle = "list").reused)
        assertEquals(0, manager.wastedWarmUps())
        assertTrue(manager.take(FixPurpose.VISIT_OPEN, cycle = "list").reused)
        assertEquals(0, manager.wastedWarmUps())
    }
}
