package com.aktcl.aron.dpc.blocking

import com.aktcl.aron.dpc.policy.DevicePolicy
import com.aktcl.aron.dpc.policy.policyFixture
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingEngineTest {
    private class Phone(var owner: Boolean = true) : SuspendGateway {
        override val ownPackage = "com.aktcl.aron.sr"
        val suspended = sortedSetOf<String>()
        val refuse = mutableSetOf<String>()
        var apps = listOf(
            InstalledApp("com.facebook.katana", false, true), InstalledApp("com.instagram.android", false, true),
            InstalledApp("com.zhiliaoapp.musically", false, true), InstalledApp("com.google.android.youtube", true, true),
            InstalledApp("com.snapchat.android", false, true), InstalledApp("com.dts.freefireth", false, true),
            InstalledApp("com.whatsapp", false, true), InstalledApp("com.google.android.dialer", true, true),
            InstalledApp("com.samsung.android.messaging", true, true), InstalledApp("com.google.android.apps.maps", true, true),
            InstalledApp("com.sec.android.app.camera", true, true), InstalledApp("com.aktcl.aron.sr", false, true),
            InstalledApp("com.android.systemui", true, false), InstalledApp("com.ludo.king", false, true),
        )
        override fun isDeviceOwner() = owner
        override fun setSuspended(packages: List<String>, suspended: Boolean): List<String> {
            val failed = packages.filter { it in refuse }
            packages.filter { it !in refuse }.forEach { if (suspended) this.suspended += it else this.suspended -= it }
            return failed
        }
        override fun installed() = apps
    }

    private val dir = Files.createTempDirectory("blk").toFile()
    private val phone = Phone()
    private var policy: DevicePolicy? = policyFixture()
    private var now = Instant.parse("2026-10-07T03:00:00Z").toEpochMilli() // 09:00 Dhaka, a Wednesday
    private var working: Boolean? = true
    private fun engine(store: BlockingStore = BlockingStore(dir)) = BlockingEngine(phone, store, { policy }, { now }, { working })

    private val social = listOf("com.dts.freefireth", "com.facebook.katana", "com.google.android.youtube", "com.instagram.android",
        "com.snapchat.android", "com.zhiliaoapp.musically")

    @Test fun checkInSuspendsTheListAndKeepsPhoneSmsMapsCameraAndAron() {
        val out = engine().onCheckIn()
        assertTrue(out.active)
        assertEquals(social, phone.suspended.toList())
        for (p in listOf("com.whatsapp", "com.google.android.dialer", "com.samsung.android.messaging", "com.google.android.apps.maps",
            "com.sec.android.app.camera", "com.aktcl.aron.sr")) assertFalse(p, p in phone.suspended)
        assertEquals(now, out.since)
    }

    @Test fun checkOutReleasesEverything() {
        engine().onCheckIn()
        now += 8 * 3_600_000L
        val out = engine().onCheckOut()
        assertFalse(out.active)
        assertTrue(phone.suspended.isEmpty())
        assertNull(out.since)
    }

    @Test fun itWorksOfflineAndSurvivesAKillAndAReboot() {
        engine().onCheckIn() // no network anywhere in this engine
        phone.suspended.clear() // a reboot on an OEM that dropped the suspension
        val afterRestart = engine(BlockingStore(dir)).evaluate() // fresh process, state from disk
        assertTrue(afterRestart.active)
        // The stored list says they are suspended, so a dropped suspension is only repaired by re-asserting: evaluate
        // must suspend what the phone reports as not suspended.
        assertEquals(social, phone.suspended.toList())
    }

    @Test fun theHardEndTimeReleasesWithoutACheckOut() {
        engine().onCheckIn()
        now = Instant.parse("2026-10-07T13:59:59Z").toEpochMilli() // 19:59:59 Dhaka
        assertTrue(engine().evaluate().active)
        now += 1_000 // 20:00
        val out = engine().evaluate()
        assertFalse(out.active)
        assertTrue(phone.suspended.isEmpty())
    }

    @Test fun noHardEndMeansOnlyCheckOutReleases() {
        policy = policy!!.copy(schedule = policy!!.schedule.copy(hardEndTime = null))
        engine().onCheckIn()
        now = Instant.parse("2026-10-07T17:59:00Z").toEpochMilli() // 23:59 Dhaka
        assertTrue(engine().evaluate().active)
    }

    @Test fun aNewBusinessDateReleasesYesterdaysBlock() {
        policy = policy!!.copy(schedule = policy!!.schedule.copy(hardEndTime = null))
        engine().onCheckIn()
        now = Instant.parse("2026-10-07T18:00:00Z").toEpochMilli() // 00:00 Dhaka next day
        assertFalse(engine().evaluate().active)
        assertTrue(phone.suspended.isEmpty())
    }

    @Test fun nonWorkingDaysBlockNothingWhenWorkingDaysOnly() {
        working = false
        assertFalse(engine().onCheckIn().active)
        assertTrue(phone.suspended.isEmpty())
        policy = policy!!.copy(schedule = policy!!.schedule.copy(workingDaysOnly = false))
        assertTrue(engine().evaluate().active)
        working = null // calendar unknown: the rep checked in, so it is a working day
        policy = policyFixture()
        assertTrue(engine().evaluate().active)
    }

    @Test fun disabledScheduleOrNoPolicyOrNotOwnerBlocksNothing() {
        policy = policy!!.copy(schedule = policy!!.schedule.copy(enabled = false))
        assertFalse(engine().onCheckIn().active)
        policy = null
        assertFalse(engine().evaluate().active)
        policy = policyFixture()
        phone.owner = false
        assertFalse(engine().evaluate().active)
        assertTrue(phone.suspended.isEmpty())
    }

    @Test fun aClockWoundBackBeforeTheCheckInDoesNotBlock() {
        engine().onCheckIn()
        now -= 60_000
        assertFalse(engine().evaluate().active)
    }

    @Test fun aSecondCheckInKeepsTheFirstTime() {
        val first = engine().onCheckIn().since
        now += 3_600_000
        assertEquals(first, engine().onCheckIn().since)
    }

    @Test fun anAlwaysAllowedOrOwnPackageOnTheBlocklistIsNeverSuspended() {
        policy = policy!!.copy(appControl = policy!!.appControl.copy(blockedPackages = policy!!.appControl.blockedPackages + "com.whatsapp" + "com.aktcl.aron.sr"))
        engine().onCheckIn()
        assertFalse("com.whatsapp" in phone.suspended)
        assertFalse("com.aktcl.aron.sr" in phone.suspended)
    }

    @Test fun allowlistModeSuspendsEveryOtherLaunchableUserApp() {
        policy = policy!!.copy(appControl = policy!!.appControl.copy(mode = "allowlist", allowedPackages = listOf("com.dts.freefireth")))
        engine().onCheckIn()
        assertEquals(listOf("com.facebook.katana", "com.instagram.android", "com.ludo.king", "com.snapchat.android", "com.zhiliaoapp.musically"), phone.suspended.toList())
    }

    @Test fun aListChangeDuringTheDayReleasesRemovedApps() {
        engine().onCheckIn()
        policy = policy!!.copy(appControl = policy!!.appControl.copy(blockedPackages = listOf("com.facebook.katana")))
        val out = engine().evaluate()
        assertEquals(listOf("com.facebook.katana"), phone.suspended.toList())
        assertTrue(out.changed)
    }

    @Test fun aRefusedReleaseIsRetriedOnTheNextEvaluation() {
        engine().onCheckIn()
        phone.refuse += "com.facebook.katana"
        val out = engine().onCheckOut()
        assertEquals(listOf("com.facebook.katana"), out.failed)
        assertEquals(listOf("com.facebook.katana"), out.suspended)
        phone.refuse.clear()
        assertTrue(engine().evaluate().suspended.isEmpty())
        assertTrue(phone.suspended.isEmpty())
    }

    @Test fun hardEndIsDhakaTime() {
        assertEquals(Instant.parse("2026-10-07T14:00:00Z").toEpochMilli(), BlockingEngine.hardEndMs("2026-10-07", "20:00"))
        assertNull(BlockingEngine.hardEndMs("2026-10-07", "25:00"))
    }
}
