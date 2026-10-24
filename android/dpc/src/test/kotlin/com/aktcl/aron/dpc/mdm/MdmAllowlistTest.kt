package com.aktcl.aron.dpc.mdm

import com.aktcl.aron.dpc.blocking.InstalledApp
import com.aktcl.aron.dpc.blocking.SuspendGateway
import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Work-hours allow-list (D-NIGHT-MDM-ALLOWLIST-01) against a fake DevicePolicyManager: lock-out and release paths. */
class MdmAllowlistTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** Fake DevicePolicyManager + PackageManager: records suspensions, can refuse packages and fail the resolvers. */
    private class FakeDpm : SuspendGateway {
        override val ownPackage = "com.aktcl.aron.sr"
        val suspended = sortedSetOf<String>()
        var refuse = setOf<String>()
        var resolverFails = false
        var deviceOwner = true
        override fun isDeviceOwner() = deviceOwner
        override fun setSuspended(packages: List<String>, suspended: Boolean): List<String> {
            packages.filter { it !in refuse }.forEach { if (suspended) this.suspended += it else this.suspended -= it }
            return packages.filter { it in refuse }
        }
        override fun installed() = listOf(
            InstalledApp("com.facebook.katana", true, true), // an OEM preload: system and launchable
            InstalledApp("com.google.android.youtube", true, true),
            InstalledApp("com.ludo.king", false, true),
            InstalledApp("com.whatsapp", false, true),
            InstalledApp("com.hihonor.calculator", true, true),
            InstalledApp("com.hihonor.phone", true, true), // resolved as the dialler only
            InstalledApp("com.swiftkey", false, true), // a keyboard with a launcher icon
            InstalledApp("com.android.settings", true, true),
            InstalledApp("com.aktcl.aron.sr", false, true),
            InstalledApp("com.aktcl.aron.tso", false, true),
            InstalledApp("com.google.android.gms", true, false), // no icon: never touched
        )
        override fun protectedPackages(): Set<String> {
            if (resolverFails) throw IllegalStateException("no dialler resolved")
            return setOf("com.hihonor.phone", "com.swiftkey", "com.hihonor.launcher")
        }
    }

    private class Kiosk : KioskGateway {
        override fun setLockTaskPackages(packages: List<String>) {}
        override fun startLockTask(pkg: String) {}
        override fun clearLockTask() {}
    }

    // 2026-10-07 is a Wednesday (ISO 3). 09:00 Dhaka = 03:00Z, 18:00 Dhaka = 12:00Z.
    private val window = MdmWorkHoursAllowlist(true, listOf("com.whatsapp", "com.hihonor.calculator"), MdmAllowlistSchedule("window", listOf(1, 2, 3, 4, 7), "09:00", "18:00"), graceMin = 5)

    private fun applier(doc: MdmPolicyDoc, dpm: FakeDpm, now: () -> Long, dir: java.io.File = Files.createTempDirectory("al").toFile(), workingDay: (String) -> Boolean? = { null }, held: Set<String> = emptySet()) =
        MdmPolicyApplier(dpm, Kiosk(), MdmPolicyStore(dir).also { it.save(1, 1, doc) }, dir, now, { null }, { held }, workingDay)

    private val blockedInWindow = setOf("com.facebook.katana", "com.google.android.youtube", "com.ludo.king")

    @Test fun insideTheWindowEveryLauncherAppOutsideTheListIsSuspendedAndProtectedOnesNever() {
        val dpm = FakeDpm()
        val o = applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }).evaluate()
        assertTrue(o.allowlistActive)
        assertEquals(blockedInWindow, dpm.suspended.toSet())
        // allowed, ARON (own and the other flavours), dialler, keyboard, Settings and icon-less packages stay open
        for (p in listOf("com.whatsapp", "com.hihonor.calculator", "com.aktcl.aron.sr", "com.aktcl.aron.tso", "com.hihonor.phone", "com.swiftkey", "com.android.settings", "com.google.android.gms"))
            assertFalse(p, p in dpm.suspended)
    }

    @Test fun graceDelaysTheStartAndTheEndIsExclusive() {
        val dpm = FakeDpm()
        var now = ms("2026-10-07T03:04:00Z") // 09:04, inside the 5 min grace
        val a = applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { now })
        assertFalse(a.evaluate().allowlistActive); assertTrue(dpm.suspended.isEmpty())
        now = ms("2026-10-07T03:05:00Z"); assertTrue(a.evaluate().allowlistActive)
        now = ms("2026-10-07T11:59:00Z"); assertTrue(a.evaluate().allowlistActive)
        now = ms("2026-10-07T12:00:00Z")
        val after = a.evaluate()
        assertFalse(after.allowlistActive); assertTrue(dpm.suspended.isEmpty())
        // Friday (5) is not a listed weekday
        now = ms("2026-10-09T05:00:00Z"); assertFalse(a.evaluate().allowlistActive)
    }

    @Test fun edgesAreTheBlockingStartAndTheEnd() {
        val doc = MdmPolicyDoc(workHoursAllowlist = window)
        assertEquals(ms("2026-10-07T03:05:00Z"), MdmWindows.nextBoundaryMs(doc, ms("2026-10-07T02:00:00Z")))
        assertEquals(ms("2026-10-07T12:00:00Z"), MdmWindows.nextBoundaryMs(doc, ms("2026-10-07T03:05:00Z")))
        // Thursday 18:00 ends; Friday and Saturday are off; next start is Sunday 09:05 Dhaka = Sun 03:05Z
        assertEquals(ms("2026-10-11T03:05:00Z"), MdmWindows.nextBoundaryMs(doc, ms("2026-10-08T12:00:00Z")))
        assertEquals(null, MdmWindows.nextBoundaryMs(MdmPolicyDoc(workHoursAllowlist = window.copy(enabled = false)), 0))
    }

    @Test fun calendarModeBlocksOnlyOnAKnownWorkingDay() {
        val cal = window.copy(schedule = MdmAllowlistSchedule("calendar", emptyList(), "09:00", "18:00"))
        val now = ms("2026-10-09T05:00:00Z") // Friday 11:00
        val working = FakeDpm()
        assertTrue(applier(MdmPolicyDoc(workHoursAllowlist = cal), working, { now }, workingDay = { true }).evaluate().allowlistActive)
        assertEquals(blockedInWindow, working.suspended.toSet())
        val holiday = FakeDpm()
        assertFalse(applier(MdmPolicyDoc(workHoursAllowlist = cal), holiday, { now }, workingDay = { false }).evaluate().allowlistActive)
        assertTrue(holiday.suspended.isEmpty())
        // no bundle calendar yet: fail open, never lock a rep out on a day nobody knows is a working day
        val unknown = FakeDpm()
        assertFalse(applier(MdmPolicyDoc(workHoursAllowlist = cal), unknown, { now }).evaluate().allowlistActive)
        assertTrue(unknown.suspended.isEmpty())
    }

    @Test fun anUnresolvedProtectedSetBlocksNothing() {
        val dpm = FakeDpm().apply { resolverFails = true }
        val o = applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }).evaluate()
        assertFalse(o.allowlistActive)
        assertTrue(dpm.suspended.isEmpty())
        assertTrue(MdmPolicyApplier.PROTECTED_UNRESOLVED in o.failed)
    }

    @Test fun anUnresolvedProtectedSetReleasesWhatAnEarlierRunSuspended() {
        val dir = Files.createTempDirectory("al").toFile()
        val dpm = FakeDpm()
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }, dir).evaluate()
        assertEquals(blockedInWindow, dpm.suspended.toSet())
        dpm.resolverFails = true
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:10:00Z") }, dir).evaluate()
        assertTrue(dpm.suspended.isEmpty())
    }

    @Test fun killAndRebootInsideTheWindowKeepsTheBlockAndReleasesAfter() {
        val dir = Files.createTempDirectory("al").toFile()
        val dpm = FakeDpm()
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }, dir).evaluate()
        // a new process over the same files (kill, reboot) re-asserts the block
        dpm.suspended.clear() // an OEM that dropped the suspensions at boot
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T06:00:00Z") }, dir).evaluate()
        assertEquals(blockedInWindow, dpm.suspended.toSet())
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T12:30:00Z") }, dir).evaluate()
        assertTrue(dpm.suspended.isEmpty())
    }

    @Test fun switchingTheListOffReleasesAndAttendanceHeldAppsStay() {
        val dir = Files.createTempDirectory("al").toFile()
        val dpm = FakeDpm()
        val now = ms("2026-10-07T05:00:00Z")
        applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { now }, dir).evaluate()
        val o = applier(MdmPolicyDoc(workHoursAllowlist = window.copy(enabled = false)), dpm, { now }, dir, held = setOf("com.facebook.katana")).evaluate()
        assertFalse(o.allowlistActive)
        assertEquals(setOf("com.facebook.katana"), dpm.suspended.toSet()) // the check-in blocking still owns it
    }

    @Test fun refusedPackagesAreReportedAndRetried() {
        val dir = Files.createTempDirectory("al").toFile()
        val dpm = FakeDpm().apply { refuse = setOf("com.ludo.king") }
        val o = applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }, dir).evaluate()
        assertTrue("com.ludo.king" in o.failed)
        assertTrue("com.ludo.king" in o.suspended) // tracked, so the release after the window covers it
    }

    @Test fun kioskPackagesAndExplicitDiallersInABlockWindowAreNeverSuspended() {
        val dpm = FakeDpm()
        val doc = MdmPolicyDoc(
            blockWindows = listOf(MdmBlockWindow(listOf(3), "09:00", "18:00", listOf("com.android.dialer", "com.hihonor.phone", "com.aktcl.aron.tso", "com.ludo.king"))),
            kiosk = MdmKiosk(true, listOf("com.whatsapp")),
            workHoursAllowlist = window.copy(packages = emptyList()),
        )
        applier(doc, dpm, { ms("2026-10-07T05:00:00Z") }).evaluate()
        assertFalse("com.whatsapp" in dpm.suspended) // the kiosk app would otherwise start suspended
        assertFalse("com.hihonor.phone" in dpm.suspended)
        assertFalse("com.aktcl.aron.tso" in dpm.suspended)
        assertTrue("com.ludo.king" in dpm.suspended)
    }

    @Test fun notDeviceOwnerTouchesNothing() {
        val dpm = FakeDpm().apply { deviceOwner = false }
        val o = applier(MdmPolicyDoc(workHoursAllowlist = window), dpm, { ms("2026-10-07T05:00:00Z") }).evaluate()
        assertFalse(o.allowlistActive); assertTrue(dpm.suspended.isEmpty())
    }

    @Test fun anInvalidScheduleFailsOpen() {
        for (bad in listOf(
            window.copy(schedule = MdmAllowlistSchedule("window", listOf(3), "18:00", "09:00")), // overnight is not a working day
            window.copy(schedule = MdmAllowlistSchedule("window", listOf(3), "9:00", "18:00")),
            window.copy(schedule = MdmAllowlistSchedule("weekly", listOf(3), "09:00", "18:00")),
            window.copy(graceMin = 600), // clamped to 30: still a valid span
        )) {
            val dpm = FakeDpm()
            val active = applier(MdmPolicyDoc(workHoursAllowlist = bad), dpm, { ms("2026-10-07T05:00:00Z") }).evaluate().allowlistActive
            assertEquals(bad.toString(), bad.graceMin == 600, active)
        }
    }

    @Test fun theCommandDocumentDecodesWithTheAllowList() {
        val text = """{"allowed_packages":[],"block_windows":[],"kiosk":{"enabled":false,"packages":[]},"location_sampling":{"mode":"on_demand","interval_min":null},
            "work_hours_allowlist":{"enabled":true,"packages":["com.whatsapp"],"schedule":{"mode":"calendar","days":[],"start":"09:00","end":"18:00"},"grace_min":0},"future":1}"""
        val doc = Json { ignoreUnknownKeys = true }.decodeFromString(MdmPolicyDoc.serializer(), text)
        assertEquals(MdmWorkHoursAllowlist(true, listOf("com.whatsapp"), MdmAllowlistSchedule("calendar", emptyList(), "09:00", "18:00"), 0), doc.workHoursAllowlist)
        // a policy from before the allow-list still decodes (absent member)
        assertEquals(null, Json { ignoreUnknownKeys = true }.decodeFromString(MdmPolicyDoc.serializer(), """{"allowed_packages":[]}""").workHoursAllowlist)
    }

    @Test fun aCheckOutInsideTheWindowKeepsTheAllowListBlock() {
        val dir = Files.createTempDirectory("al").toFile()
        val dpm = FakeDpm()
        val now = ms("2026-10-07T05:00:00Z") // Wednesday 11:00, inside the window
        val ps = com.aktcl.aron.dpc.policy.PolicyStore(dir)
        val dop = com.aktcl.aron.dpc.DeviceOwnerPolicy(ps, com.aktcl.aron.dpc.policy.PolicyApplier(com.aktcl.aron.dpc.policy.FakeGateway()), com.aktcl.aron.dpc.blocking.BlockingStore(dir), dpm, { now }, { true })
        // check-in blocking also holds Facebook and YouTube (the default block list of the fixture policy)
        dop.receive(com.aktcl.aron.dpc.policy.policyFixture())
        val mdm = MdmPolicyApplier(dpm, Kiosk(), MdmPolicyStore(dir).also { it.save(1, 1, MdmPolicyDoc(workHoursAllowlist = window)) }, dir, { now }, { ps.load() },
            { com.aktcl.aron.dpc.blocking.BlockingStore(dir).load().suspended.toSet() })
        var hooks = 0
        dop.mdmHook = { hooks++; mdm.evaluate() }
        dop.onCheckInCommitted()
        assertTrue("com.facebook.katana" in dpm.suspended)
        dop.onCheckOutCommitted()
        assertTrue(hooks >= 2)
        assertEquals(blockedInWindow, dpm.suspended.toSet()) // the allow-list still wants Facebook suspended
    }

    @Test fun theAlarmDelayComesFromTrustedTimeAndIsNeverShorterThanAMinute() {
        assertEquals(30 * 60_000L, MdmAlarm.delayMs(1_000_000L + 30 * 60_000L, 1_000_000L))
        assertEquals(MdmAlarm.MIN_DELAY_MS, MdmAlarm.delayMs(1_000_000L, 1_000_000L)) // an edge already reached never loops
        assertEquals(MdmAlarm.MIN_DELAY_MS, MdmAlarm.delayMs(0L, 1_000_000L))
    }
}
