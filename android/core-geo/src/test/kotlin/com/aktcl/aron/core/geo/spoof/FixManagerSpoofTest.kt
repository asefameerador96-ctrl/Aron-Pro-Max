package com.aktcl.aron.core.geo.spoof

import com.aktcl.aron.core.geo.FakeAccess
import com.aktcl.aron.core.geo.FakeClock
import com.aktcl.aron.core.geo.FakeSource
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.FixPurpose
import com.aktcl.aron.core.geo.RawLocation
import com.aktcl.aron.core.geo.honestDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fix manager attaches the codes and the hard flag, and remembers a fresh fix as the previous one only after checking it. */
class FixManagerSpoofTest {
    private class Recording(val result: SpoofResult) : SpoofChecker {
        val fresh = mutableListOf<RawLocation>()
        var asked = 0
        val order = mutableListOf<String>()
        override suspend fun prepare() { order += "prepare" }
        override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean): SpoofResult { asked++; order += "check"; return result }
        override fun onFreshFix(loc: RawLocation) { fresh += loc }
    }

    @Test fun theResultCarriesCodesAndTheHardFlag() {
        val hard = SpoofResult(listOf(SpoofCode.MOCK_FLAG, SpoofCode.FAKE_GPS_APP_INSTALLED))
        assertTrue(hard.hard); assertEquals(listOf("mock_flag", "fake_gps_app_installed"), hard.wire)
        val soft = SpoofResult(listOf(SpoofCode.JUMP)); assertFalse(soft.hard)
        assertFalse(SpoofResult.NONE.hard); assertEquals(emptyList<String>(), SpoofResult.NONE.wire)
    }

    private fun manager(checker: SpoofChecker, clock: FakeClock, source: FakeSource) = FixManager(source, FakeAccess(), { honestDevice }, clock, spoof = checker)

    @Test fun theFixManagerCarriesTheCheckersCodesAndHardFlagOntoTheFix() = runTest {
        val clock = FakeClock()
        val hard = manager(Recording(SpoofResult(listOf(SpoofCode.MOCK_APP_SELECTED, SpoofCode.JUMP))), clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN)
        assertTrue(hard.hardSpoof); assertEquals(listOf("mock_app_selected", "jump"), hard.signals)
        // The platform mock flag was FALSE here: only the selected-app code makes this fix hard (a soft code alone does not).
        assertFalse(hard.isMock)
        val soft = manager(Recording(SpoofResult(listOf(SpoofCode.JUMP))), clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN)
        assertFalse(soft.hardSpoof); assertEquals(listOf("jump"), soft.signals)
    }

    /** Review lows: a checked clean fix is an EMPTY list (the contract's "checked, nothing found"); a check that throws is UNKNOWN (null), never clean. */
    @Test fun aCleanCheckedFixCarriesAnEmptyListAndAFailedCheckCarriesNone() = runTest {
        val clock = FakeClock()
        val clean = manager(Recording(SpoofResult.NONE), clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN)
        assertEquals(emptyList<String>(), clean.signals); assertFalse(clean.hardSpoof)
        val boom = object : SpoofChecker {
            override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean): SpoofResult = error("probe failed")
            override fun onFreshFix(loc: RawLocation) = Unit
        }
        val failed = manager(boom, clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN)
        assertEquals(null, failed.signals); assertFalse(failed.hardSpoof)
        assertEquals("ok", failed.fixStatus.wire)
    }

    @Test fun theSlowFactsAreReadFirstInTheSuspendingPrepareStepThenTheFixIsChecked() = runTest {
        val clock = FakeClock(); val rec = Recording(SpoofResult.NONE)
        manager(rec, clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN)
        assertEquals(listOf("prepare", "check"), rec.order)
    }

    @Test fun aFailingPrepareNeverBlocksTheFix() = runTest {
        val clock = FakeClock()
        val boom = object : SpoofChecker {
            override suspend fun prepare() { error("probe failed") }
            override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean) = SpoofResult.NONE
            override fun onFreshFix(loc: RawLocation) = Unit
        }
        assertEquals("ok", manager(boom, clock, FakeSource(clock)).take(FixPurpose.VISIT_OPEN).fixStatus.wire)
    }
}
