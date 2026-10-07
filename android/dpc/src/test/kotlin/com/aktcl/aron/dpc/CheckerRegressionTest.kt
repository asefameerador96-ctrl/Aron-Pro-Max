package com.aktcl.aron.dpc

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.dpc.blocking.AndroidSuspendGateway
import com.aktcl.aron.dpc.blocking.BlockingEngine
import com.aktcl.aron.dpc.blocking.BlockingStore
import com.aktcl.aron.dpc.blocking.InstalledApp
import com.aktcl.aron.dpc.blocking.SuspendGateway
import com.aktcl.aron.dpc.policy.FakeGateway
import com.aktcl.aron.dpc.policy.PolicyApplier
import com.aktcl.aron.dpc.policy.asDev
import com.aktcl.aron.dpc.policy.policyFixture
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Defects the N-029 / N-032 checker proved; each test failed before its fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CheckerRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = Instant.parse("2026-10-07T03:00:00Z").toEpochMilli()

    private class Phone(val beforeSuspend: () -> Unit = {}) : SuspendGateway {
        override val ownPackage = "com.aktcl.aron.sr"
        val suspended = sortedSetOf<String>()
        var throwInstalled = false
        override fun isDeviceOwner() = true
        override fun setSuspended(packages: List<String>, suspended: Boolean): List<String> {
            if (suspended) beforeSuspend()
            packages.forEach { if (suspended) this.suspended += it else this.suspended -= it }
            return emptyList()
        }
        override fun installed(): List<InstalledApp> {
            if (throwInstalled) throw RuntimeException("DeadSystemException / TransactionTooLarge")
            return listOf(InstalledApp("com.facebook.katana", false, true), InstalledApp("com.instagram.android", false, true))
        }
    }

    @Test fun aFailedStateSaveNeverLeavesAppsSuspendedAfterCheckOut() {
        val dir = Files.createTempDirectory("blk").toFile()
        var failSaves = false
        val phone = Phone { if (failSaves) File(dir, "blocking-state.tmp").mkdirs() }
        val e = BlockingEngine(phone, BlockingStore(dir), { policyFixture() }, { now }, { true })
        failSaves = true
        e.onCheckIn() // the final save fails after suspending; the write-ahead record already lists the apps
        failSaves = false
        File(dir, "blocking-state.tmp").deleteRecursively()
        e.onCheckOut()
        assertEquals(emptyList<String>(), phone.suspended.toList())
    }

    @Test fun aPackageManagerFailureDoesNotEscape() {
        val phone = Phone().apply { throwInstalled = true }
        val e = BlockingEngine(phone, BlockingStore(Files.createTempDirectory("blk").toFile()), { policyFixture() }, { now }, { true })
        val out = e.onCheckIn()
        assertEquals(listOf(BlockingEngine.EVALUATE_FAILED), out.failed)
        assertTrue(runCatching { e.evaluate() }.isSuccess)
        assertTrue(runCatching { e.onCheckOut() }.isSuccess)
    }

    @Test fun prodToDevRestoresUsbDebugging() {
        val gw = FakeGateway()
        val a = PolicyApplier(gw)
        a.apply(policyFixture())
        assertEquals("0", gw.globals["adb_enabled"])
        a.apply(policyFixture().asDev())
        assertEquals("1", gw.globals["adb_enabled"])
    }

    @Test fun anUpdatedSystemAppIsStillASystemApp() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.android.vending"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.android.vending"
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            }
        })
        assertTrue(AndroidSuspendGateway(app).installed().single { it.packageName == "com.android.vending" }.system)
    }

    @Test fun theTrustedClockConfiguredByTheAppWinsOverAnEarlierBootCaller() {
        DeviceOwnerPolicy::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        shadowOf(dpm).setDeviceOwner(ComponentName(app, AronDeviceAdminReceiver::class.java))
        DeviceOwnerPolicy.get(app).reapply() // the boot receiver came first
        val trusted = 1_791_342_012_345L
        val dop = DeviceOwnerPolicy.get(app)
        dop.configure({ trusted }, { true })
        dop.receive(policyFixture())
        assertEquals(trusted, dop.onCheckInCommitted().since)
    }

    @Test fun theBatteryPromptIsOpenedWhenNotExempt() {
        assertTrue(BatteryExemption.requestIfNeeded(app, policyWantsIt = true))
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shadowOf(app).nextStartedActivity.action)
        assertTrue(!BatteryExemption.requestIfNeeded(app, policyWantsIt = false))
    }

    @Test fun aBlockWithoutHardEndIsReleasedAtTheNextBusinessDay() {
        assertEquals(Instant.parse("2026-10-07T18:00:00Z").toEpochMilli(), BlockingEngine.nextBusinessDayStartMs(now))
    }

    @Test fun aFailedSecondCheckInReportsTheStoredBlockSoTheAlarmStaysArmed() {
        val dir = Files.createTempDirectory("blk").toFile()
        val phone = Phone()
        val e = BlockingEngine(phone, BlockingStore(dir), { policyFixture() }, { now }, { true })
        assertTrue(e.onCheckIn().active)
        File(dir, "blocking-state.tmp").mkdirs() // saves fail from now on
        val again = e.onCheckIn()
        assertEquals(phone.suspended.isNotEmpty(), again.active)
        assertEquals(phone.suspended.toList(), again.suspended)
    }
}
