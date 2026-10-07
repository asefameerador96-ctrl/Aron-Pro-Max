package com.aktcl.aron.core.geo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial checker tests (N-021). */
class N021AdvFixManagerTest {
    private val clock = FakeClock()
    private val source = FakeSource(clock)
    private val access = FakeAccess()
    private val ledger = MemoryFixLedger()
    private var settings = FixSettings()
    private val manager = FixManager(source, access, { honestDevice }, clock, ledger, settings = { settings })

    @Test fun c1_aReusedFixReportsThePriorityItWasActuallyTakenWith() = runTest {
        manager.take(FixPurpose.VISIT_OPEN, cycle = "list") // balanced request
        settings = FixSettings(priority = FixPriority.HIGH_ACCURACY)
        clock.advance(10_000)
        val f = manager.take(FixPurpose.VISIT_OPEN, cycle = "list")
        assertTrue(f.reused)
        assertEquals(listOf(FixPriority.BALANCED), source.priorities)
        assertEquals("reused fix claims a request priority never used", FixPriority.BALANCED, f.requestPriority)
    }

    @Test fun c2_permissionRevokedOrLocationOffIsNotHiddenByAReusedFix() = runTest {
        manager.take(FixPurpose.VISIT_OPEN, cycle = "list")
        access.state = LocationAccessState.PERMISSION_DENIED
        clock.advance(10_000)
        val denied = manager.take(FixPurpose.VISIT_OPEN, cycle = "list")
        assertNotEquals("denied permission still yields an OK fix", FixStatus.OK, denied.fixStatus)
    }

    @Test fun c3_warmUpOnEnteringTheListKeeps60OutletsUnder80Fixes() = runTest {
        // D-74 usage as documented on warmUp: acquisition starts on entering the outlet list; the rep then walks ~90 s.
        manager.take(FixPurpose.ATTENDANCE_IN)
        repeat(60) {
            clock.advance(4 * 60_000)
            manager.warmUp("outlet-list")
            clock.advance(90_000)
            manager.take(FixPurpose.VISIT_OPEN, cycle = "outlet-list")
        }
        manager.take(FixPurpose.ATTENDANCE_OUT)
        assertTrue("provider requests: ${source.requests}", source.requests <= 80)
    }
}
