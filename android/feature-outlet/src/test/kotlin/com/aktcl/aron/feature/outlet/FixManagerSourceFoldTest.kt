package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.geo.FixDeviceState
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.FixPriority
import com.aktcl.aron.core.geo.LocationAccess
import com.aktcl.aron.core.geo.LocationAccessState
import com.aktcl.aron.core.geo.LocationSource
import com.aktcl.aron.core.geo.RawLocation
import com.aktcl.aron.core.geo.SourceResult
import com.aktcl.aron.core.geo.spoof.RawLocationView
import com.aktcl.aron.core.geo.spoof.SpoofChecker
import com.aktcl.aron.core.geo.spoof.SpoofCode
import com.aktcl.aron.core.geo.spoof.SpoofResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring from the spoof checker to the reading the visit rules see: a HARD code (selected mock app, test provider) makes the
 * reading `isMock` even when the platform flag was false, and a soft code does not. Deleting `|| hardSpoof` in `toReading` fails this.
 */
class FixManagerSourceFoldTest {
    private object Clock : WallClock { override fun nowMs() = 1_791_000_000_000L; override fun elapsedRealtimeMs() = 10_000_000L }
    private class Checker(val result: SpoofResult) : SpoofChecker {
        override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean) = result
        override fun onFreshFix(loc: RawLocation) = Unit
    }
    private val source = object : LocationSource {
        override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long) = SourceResult.Located(
            RawLocation(lat = 23.78, lng = 90.41, accuracyM = 10.0, provider = "fused", timeMs = Clock.nowMs(), elapsedRealtimeNanos = Clock.elapsedRealtimeMs() * 1_000_000L, isMock = false),
        )
    }
    private val access = object : LocationAccess { override fun state(requirePrecise: Boolean) = LocationAccessState.OK }
    private val device = FixDeviceState(deviceOwner = false, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)

    private suspend fun read(codes: List<SpoofCode>) =
        FixManagerSource(FixManager(source, access, { device }, Clock, spoof = Checker(SpoofResult(codes)))).readFix("visit_open", 0)

    @Test fun aSelectedMockAppMakesTheReadingMockedEvenWithoutThePlatformFlag() = runTest {
        val r = read(listOf(SpoofCode.MOCK_APP_SELECTED)); assertTrue(r.isMock); assertEquals(listOf("mock_app_selected"), r.signals)
    }

    @Test fun aTestProviderMakesTheReadingMocked() = runTest { assertTrue(read(listOf(SpoofCode.TEST_PROVIDER)).isMock) }

    @Test fun softCodesNeverMakeTheReadingMocked() = runTest {
        val r = read(listOf(SpoofCode.JUMP, SpoofCode.PROVIDER_DISAGREE)); assertFalse(r.isMock); assertEquals(listOf("jump", "provider_disagree"), r.signals)
    }
}
