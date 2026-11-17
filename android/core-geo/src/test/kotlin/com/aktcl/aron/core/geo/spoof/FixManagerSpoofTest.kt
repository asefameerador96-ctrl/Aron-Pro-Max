package com.aktcl.aron.core.geo.spoof

import com.aktcl.aron.core.geo.RawLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fix manager attaches the codes and the hard flag, and remembers a fresh fix as the previous one only after checking it. */
class FixManagerSpoofTest {
    private class Recording(val result: SpoofResult) : SpoofChecker {
        val fresh = mutableListOf<RawLocation>()
        var asked = 0
        override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean): SpoofResult { asked++; return result }
        override fun onFreshFix(loc: RawLocation) { fresh += loc }
    }

    @Test fun theResultCarriesCodesAndTheHardFlag() {
        val hard = SpoofResult(listOf(SpoofCode.MOCK_FLAG, SpoofCode.FAKE_GPS_APP_INSTALLED))
        assertTrue(hard.hard); assertEquals(listOf("mock_flag", "fake_gps_app_installed"), hard.wire)
        val soft = SpoofResult(listOf(SpoofCode.JUMP)); assertFalse(soft.hard)
        assertFalse(SpoofResult.NONE.hard); assertEquals(emptyList<String>(), SpoofResult.NONE.wire)
    }
}
