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
        var unsuspended = listOf<String>()
        override fun isDeviceOwner() = owner
        override fun visiblePackages() = visible
        override fun unsuspend(packages: List<String>): List<String> { log += "unsuspend"; unsuspended = packages; return packages.filter { it in unsuspendFails } }
        override fun stillSuspended(pkg: String) = pkg in stuck
        override fun clearLockTask() { log += "locktask" }
        override fun clearRestrictions() { log += "restrictions" }
        override fun unblockUninstallAndUserControl() { log += "uninstall" }
        override fun resetPermissionPins() { log += "permissions" }
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
}
