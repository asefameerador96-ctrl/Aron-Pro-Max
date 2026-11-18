package com.aktcl.aron.core.geo.spoof

import com.aktcl.aron.core.geo.RawLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofEvaluatorTest {
    private val nowElapsed = 10_000_000L
    private val nowWall = 1_800_000_000_000L

    private fun loc(
        lat: Double = 23.7925, lng: Double = 90.4078, acc: Double? = 12.0, provider: String? = "gps", mock: Boolean = false,
        ageMs: Long = 2_000, timeSkewMs: Long = 0,
    ) = RawLocation(lat, lng, acc, provider = provider, timeMs = nowWall - ageMs + timeSkewMs, elapsedRealtimeNanos = (nowElapsed - ageMs) * 1_000_000L, isMock = mock)

    private fun eval(
        loc: RawLocation? = loc(), gnss: GnssFacts? = GnssFacts(9), prev: PreviousFix? = null, net: NetworkFix? = null,
        facts: SpoofFacts = SpoofFacts(), reused: Boolean = false,
    ) = SpoofEvaluator.evaluate(SpoofInput(loc, nowElapsed, nowWall, reused, gnss, prev, net, facts))

    @Test fun aCleanFixHasNoSignals() { assertEquals(SpoofResult.NONE, eval()) }

    @Test fun theMockFlagIsHard() {
        val r = eval(loc(mock = true)); assertEquals(listOf(SpoofCode.MOCK_FLAG), r.codes); assertTrue(r.hard)
    }

    @Test fun aMockAppSelectedIsHardEvenWithoutAFix() {
        val r = eval(loc = null, facts = SpoofFacts(mockAppSelected = true)); assertTrue(r.hard); assertEquals(listOf(SpoofCode.MOCK_APP_SELECTED), r.codes)
    }

    @Test fun anUnknownProviderNameIsATestProviderAndHard() {
        for (p in listOf("test", "mock", "flp-mock", "gps_fake", "")) assertTrue(p, eval(loc(provider = p)).codes.contains(SpoofCode.TEST_PROVIDER))
        for (p in listOf("fused", "gps", "network", "passive", null)) assertFalse(p.toString(), eval(loc(provider = p)).codes.contains(SpoofCode.TEST_PROVIDER))
        assertTrue(eval(loc(provider = "test")).hard)
    }

    @Test fun softSignalsNeverMakeTheResultHard() {
        val r = eval(
            loc(provider = "gps", acc = 5.0), gnss = GnssFacts(0),
            facts = SpoofFacts(fakeGpsAppInstalled = true, rootHints = listOf("su_binary"), emulator = true, hookFramework = true, gpsProviderLooksLikeTestProvider = true),
        )
        assertTrue(r.codes.containsAll(listOf(SpoofCode.FAKE_GPS_APP_INSTALLED, SpoofCode.GNSS_NO_SATS, SpoofCode.ROOT_HINT, SpoofCode.EMULATOR, SpoofCode.HOOK_FRAMEWORK, SpoofCode.TEST_PROVIDER_PROPS)))
        assertFalse(r.hard)
    }

    @Test fun gnssZeroSatellitesOnAGpsFix() {
        assertTrue(eval(gnss = GnssFacts(0)).codes.contains(SpoofCode.GNSS_NO_SATS))
        assertFalse(eval(gnss = GnssFacts(4)).codes.contains(SpoofCode.GNSS_NO_SATS))
        // A fused fix legitimately uses no satellite: the rule is about the gps provider only.
        assertFalse(eval(loc(provider = "fused"), gnss = GnssFacts(0)).codes.contains(SpoofCode.GNSS_NO_SATS))
    }

    @Test fun noGnssStatusWhileClaimingAHighPrecision() {
        assertTrue(eval(loc(provider = "gps", acc = 8.0), gnss = null).codes.contains(SpoofCode.GNSS_NONE))
        assertFalse(eval(loc(provider = "gps", acc = 35.0), gnss = null).codes.contains(SpoofCode.GNSS_NONE)) // a coarse fix needs no satellites
        assertTrue(eval(loc(provider = "fused", acc = 4.0), gnss = null).codes.contains(SpoofCode.GNSS_NONE))
        assertFalse(eval(loc(provider = "fused", acc = 15.0), gnss = null).codes.contains(SpoofCode.GNSS_NONE)) // a Wi-Fi fix of 15 m is normal
    }

    @Test fun staleFixAndAClockThatDisagrees() {
        assertTrue(eval(loc(ageMs = 200_000)).codes.contains(SpoofCode.FIX_STALE))
        assertFalse(eval(loc(ageMs = 200_000), reused = true).codes.contains(SpoofCode.FIX_STALE)) // a reuse is old by design
        assertFalse(eval(loc(ageMs = 30_000)).codes.contains(SpoofCode.FIX_STALE))
        assertTrue(eval(loc(timeSkewMs = 3_600_000)).codes.contains(SpoofCode.FIX_CLOCK_MISMATCH))
        assertTrue(eval(loc(timeSkewMs = -3_600_000)).codes.contains(SpoofCode.FIX_CLOCK_MISMATCH))
        assertFalse(eval(loc(timeSkewMs = 10_000)).codes.contains(SpoofCode.FIX_CLOCK_MISMATCH))
    }

    @Test fun jumpAndSpeedAgainstThePreviousFix() {
        val here = loc()
        val farFast = PreviousFix(23.9, 90.4078, (here.elapsedRealtimeNanos / 1_000_000L) - 10_000) // about 12 km in 10 s
        assertTrue(eval(here, prev = farFast).codes.contains(SpoofCode.JUMP))
        val farSlower = PreviousFix(23.9, 90.4078, (here.elapsedRealtimeNanos / 1_000_000L) - 60_000) // 12 km in 1 min = 200 m/s
        val r = eval(here, prev = farSlower).codes
        assertTrue(r.contains(SpoofCode.SPEED)); assertFalse(r.contains(SpoofCode.JUMP))
        val walking = PreviousFix(23.7926, 90.4078, (here.elapsedRealtimeNanos / 1_000_000L) - 60_000)
        assertFalse(eval(here, prev = walking).codes.any { it == SpoofCode.JUMP || it == SpoofCode.SPEED })
        val bus = PreviousFix(23.7925 + 0.09, 90.4078, (here.elapsedRealtimeNanos / 1_000_000L) - 1_200_000) // 10 km in 20 min = 8 m/s
        assertFalse(eval(here, prev = bus).codes.any { it == SpoofCode.JUMP || it == SpoofCode.SPEED })
    }

    @Test fun aRebootBetweenTwoFixesComparesNothing() {
        val here = loc()
        val before = PreviousFix(10.0, 10.0, (here.elapsedRealtimeNanos / 1_000_000L) + 5_000_000) // earlier boot: larger elapsed
        assertFalse(eval(here, prev = before).codes.any { it == SpoofCode.JUMP || it == SpoofCode.SPEED })
    }

    @Test fun fusedAndNetworkProvidersThatDisagree() {
        val here = loc(provider = "fused")
        assertTrue(eval(here, net = NetworkFix(23.9, 90.4078, 800.0, 30_000)).codes.contains(SpoofCode.PROVIDER_DISAGREE))
        assertFalse(eval(here, net = NetworkFix(23.7930, 90.4080, 800.0, 30_000)).codes.contains(SpoofCode.PROVIDER_DISAGREE))
        assertFalse(eval(here, net = NetworkFix(23.9, 90.4078, 800.0, 600_000)).codes.contains(SpoofCode.PROVIDER_DISAGREE)) // too old to compare
        assertFalse(eval(here, net = NetworkFix(23.9, 90.4078, 20_000.0, 30_000)).codes.contains(SpoofCode.PROVIDER_DISAGREE)) // a cell fix this coarse proves nothing
    }

    @Test fun theHardSetIsExactlyMockFlagMockAppAndTestProvider() {
        assertEquals(setOf(SpoofCode.MOCK_FLAG, SpoofCode.MOCK_APP_SELECTED, SpoofCode.TEST_PROVIDER), SpoofCode.entries.filter { it.hard }.toSet())
    }

    @Test fun codesAreStableOrderedAndNeverDuplicated() {
        val r = eval(loc(mock = true, provider = "mock"), facts = SpoofFacts(mockAppSelected = true, emulator = true))
        assertEquals(r.codes.sortedBy { it.ordinal }, r.codes); assertEquals(r.codes.size, r.codes.distinct().size)
    }

    @Test fun everyWireCodeIsUniqueSnakeCase() {
        val w = SpoofCode.entries.map { it.wire }
        assertEquals(w.size, w.toSet().size); assertTrue(w.all { Regex("^[a-z][a-z_]*$").matches(it) })
    }
}
