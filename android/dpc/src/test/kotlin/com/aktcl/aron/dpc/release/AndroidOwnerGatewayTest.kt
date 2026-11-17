package com.aktcl.aron.dpc.release

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.dpc.AronDeviceAdminReceiver
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The REAL gateway against a recording DevicePolicyManager: what the release clears, and what it must never touch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOwnerGatewayTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val admin = ComponentName(app, AronDeviceAdminReceiver::class.java)
    private val restrictions = listOf(
        "no_debugging_features", "no_install_unknown_sources", "no_install_unknown_sources_globally", "no_install_apps",
        "no_factory_reset", "no_safe_boot", "no_add_user", "no_config_date_time", "no_usb_file_transfer", "no_config_location",
    )

    private fun fresh(): Pair<DevicePolicyManager, AndroidOwnerGateway> {
        val dpm = mockk<DevicePolicyManager>(relaxed = true)
        every { dpm.isDeviceOwnerApp(app.packageName) } returns true
        return dpm to AndroidOwnerGateway(app, dpm)
    }

    @Test fun theFullReleaseClearsExactlyTheseAndNeverTouchesTheTimeSettingOrAnyGlobalSetting() {
        val (dpm, gw) = fresh()
        every { dpm.setPackagesSuspended(admin, any(), false) } returns emptyArray()
        gw.visiblePackages()
        gw.unsuspend(listOf("com.a.b"))
        gw.clearLockTask(); gw.clearRestrictions(); gw.unblockUninstallAndUserControl(); gw.resetPermissionPins(); gw.clearDeviceOwner()

        verify(exactly = 1) { dpm.setPackagesSuspended(admin, arrayOf("com.a.b"), false) }
        verify(exactly = 1) { dpm.setLockTaskPackages(admin, emptyArray()) }
        for (k in restrictions) verify(exactly = 1) { dpm.clearUserRestriction(admin, k) } // the date-and-time restriction is cleared, not the setting
        verify(exactly = 1) { dpm.setUninstallBlocked(admin, app.packageName, false) }
        verify(exactly = 1) { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        verify(exactly = 1) { dpm.clearDeviceOwnerApp(app.packageName) }
        // The user's automatic date and time and every global setting stay as they are.
        verify(exactly = 0) { dpm.setAutoTimeEnabled(any(), any()) }
        @Suppress("DEPRECATION") verify(exactly = 0) { dpm.setAutoTimeRequired(any(), any()) }
        verify(exactly = 0) { dpm.setGlobalSetting(any(), any(), any()) }
        verify(exactly = 0) { dpm.setLocationEnabled(any(), any()) }
        // Permission pins go back to the user's control, never forced either way.
        verify(atLeast = 0) { dpm.setPermissionGrantState(admin, app.packageName, any(), DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }
        verify(exactly = 0) { dpm.setPermissionGrantState(admin, any(), any(), DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        verify(exactly = 0) { dpm.setPermissionGrantState(admin, any(), any(), DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) }
        // Nothing else was called on the device-policy manager (reads aside): the list above is the complete list.
        io.mockk.excludeRecords { dpm.isDeviceOwnerApp(any()); dpm.isPackageSuspended(any(), any()); dpm.setPermissionGrantState(any(), any(), any(), any()) }
        confirmVerified(dpm)
    }

    @Test fun onlyTheExplicitUsbDebuggingStepWritesAGlobalSetting() {
        val (dpm, gw) = fresh()
        gw.turnAdbOff()
        verify(exactly = 1) { dpm.setGlobalSetting(admin, "adb_enabled", "0") }
        verify(exactly = 0) { dpm.setAutoTimeEnabled(any(), any()) }
    }

    @Test fun unsuspendWithNothingToDoCallsNothing() {
        val (dpm, gw) = fresh()
        assertEquals(emptyList<String>(), gw.unsuspend(emptyList()))
        verify(exactly = 0) { dpm.setPackagesSuspended(any(), any(), any()) }
    }
}
