package com.aktcl.aron.dpc.release

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOwnerReleaseTest {
    private class Fake(var owner: Boolean = true) : OwnerGateway {
        val log = mutableListOf<String>()
        var visible = listOf<String>()
        var unsuspendFails = setOf<String>()
        var stuck = setOf<String>()
        var failOnClear = false
        var ownerThrows = false
        var adbOff = 0
        var unsuspended = listOf<String>()
        override fun isDeviceOwner(): Boolean { if (ownerThrows) error("platform"); return owner }
        override fun visiblePackages() = visible
        override fun unsuspend(packages: List<String>): List<String> { log += "unsuspend"; unsuspended = packages; return packages.filter { it in unsuspendFails } }
        override fun stillSuspended(pkg: String) = pkg in stuck
        override fun clearLockTask() { log += "locktask" }
        override fun clearRestrictions() { log += "restrictions" }
        override fun unblockUninstallAndUserControl() { log += "uninstall" }
        override fun resetPermissionPins() { log += "permissions" }
        override fun turnAdbOff() { adbOff++ }
        override fun clearDeviceOwner() { log += "clear"; if (failOnClear) error("boom"); owner = false }
    }

    private fun dir() = Files.createTempDirectory("rel").toFile()

    @Test fun releasesInTheRightOrderAndIsIdempotent() {
        val d = dir(); File(d, "blocking-state.txt").writeText("date=2026-11-13\nsuspended=com.android.chrome,com.example.game\n")
        val g = Fake().apply { visible = listOf("com.other.app") }
        val r = DeviceOwnerRelease(g, d)
        assertEquals(DeviceOwnerRelease.Outcome.RELEASED, r.run())
        assertEquals(listOf("unsuspend", "locktask", "restrictions", "uninstall", "permissions", "clear"), g.log) // unsuspend first, clear last
        assertEquals(setOf("com.android.chrome", "com.example.game", "com.other.app"), g.unsuspended.toSet())
        assertTrue(r.isDone())
        assertEquals(DeviceOwnerRelease.Outcome.ALREADY_DONE, DeviceOwnerRelease(g, d).run())
        assertEquals(6, g.log.size) // nothing repeated
    }

    @Test fun aPhoneThatIsNotDeviceOwnerOnlyWritesTheFlag() {
        val d = dir(); val g = Fake(owner = false)
        assertEquals(DeviceOwnerRelease.Outcome.NOT_OWNER, DeviceOwnerRelease(g, d).run())
        assertTrue(g.log.isEmpty())
        assertEquals(DeviceOwnerRelease.Outcome.ALREADY_DONE, DeviceOwnerRelease(g, d).run())
    }

    @Test fun aKillBeforeTheOwnerIsClearedRepeatsFromTheTop() {
        val d = dir(); val g = Fake().apply { failOnClear = true }
        val first = runCatching { DeviceOwnerRelease(g, d).run() }
        assertTrue(first.isFailure) // the process died at the last step
        assertFalse(File(d, "owner-release-done.txt").exists()) // the flag is written only after the clear
        g.failOnClear = false; g.log.clear()
        assertEquals(DeviceOwnerRelease.Outcome.RELEASED, DeviceOwnerRelease(g, d).run()) // next start: everything again, harmlessly
        assertEquals("unsuspend", g.log.first()); assertEquals("clear", g.log.last()); assertFalse(g.owner)
    }

    @Test fun ownershipIsNotClearedWhileAppsAreStillSuspendedUntilTheAttemptCap() {
        val d = dir(); val g = Fake().apply { visible = listOf("com.a.b"); unsuspendFails = setOf("com.a.b"); stuck = setOf("com.a.b") }
        assertEquals(DeviceOwnerRelease.Outcome.WAITING, DeviceOwnerRelease(g, d).run())
        assertEquals(DeviceOwnerRelease.Outcome.WAITING, DeviceOwnerRelease(g, d).run())
        assertTrue("clear" !in g.log)
        assertEquals(DeviceOwnerRelease.Outcome.PARTIAL_RELEASED, DeviceOwnerRelease(g, d).run()) // third start: stop holding the phone hostage
        assertTrue("clear" in g.log)
    }

    @Test fun anUninstalledPackageThatCannotBeUnsuspendedDoesNotBlockTheRelease() {
        val d = dir(); File(d, "mdm-suspended.txt").writeText("com.gone.app")
        val g = Fake().apply { unsuspendFails = setOf("com.gone.app") } // not in `stuck`: the platform says it is not installed
        assertEquals(DeviceOwnerRelease.Outcome.RELEASED, DeviceOwnerRelease(g, d).run())
    }

    @Test fun aCorruptStateFileIsIgnored() {
        val d = dir(); File(d, "blocking-state.txt").writeBytes(byteArrayOf(0, -1, 7, 10, 61))
        assertEquals(DeviceOwnerRelease.Outcome.RELEASED, DeviceOwnerRelease(Fake(), d).run())
    }

    @Test fun aPlatformErrorOnTheOwnerQuestionDoesNotMarkTheReleaseDone() {
        val d = dir(); val g = Fake().apply { ownerThrows = true }
        assertEquals(DeviceOwnerRelease.Outcome.WAITING, DeviceOwnerRelease(g, d).run())
        assertFalse(File(d, "owner-release-done.txt").exists())
        g.ownerThrows = false
        assertEquals(DeviceOwnerRelease.Outcome.RELEASED, DeviceOwnerRelease(g, d).run()) // next start does the work
        assertFalse(g.owner)
    }

    @Test fun usbDebuggingTheOldPolicyForcedOnIsSwitchedOffInReleaseBuildsAndRecorded() {
        val d = dir(); File(d, "device-policy.json").writeText("""{"policy_version":3,"global_settings":{"auto_time_required":true,"adb_enabled":true,"location_mode_high_accuracy":true}}""")
        val g = Fake()
        DeviceOwnerRelease(g, d).run()
        assertEquals(1, g.adbOff)
        assertEquals("released+adb_off", File(d, "owner-release-done.txt").readText())
    }

    @Test fun usbDebuggingIsLeftAloneWhenThePolicyDidNotForceItOrTheBuildIsDebuggable() {
        val d1 = dir(); File(d1, "device-policy.json").writeText("""{"global_settings":{"adb_enabled":false}}""")
        val g1 = Fake(); DeviceOwnerRelease(g1, d1).run(); assertEquals(0, g1.adbOff)
        val d2 = dir(); File(d2, "device-policy.json").writeText("""{"global_settings":{"adb_enabled":true}}""")
        val g2 = Fake(); DeviceOwnerRelease(g2, d2, restoreAdb = false).run(); assertEquals(0, g2.adbOff) // a lab phone keeps its ADB
        val d3 = dir(); val g3 = Fake(); DeviceOwnerRelease(g3, d3).run(); assertEquals(0, g3.adbOff) // no stored policy
        val d4 = dir(); File(d4, "device-policy.json").writeText("not json"); val g4 = Fake(); DeviceOwnerRelease(g4, d4).run(); assertEquals(0, g4.adbOff)
    }
}
