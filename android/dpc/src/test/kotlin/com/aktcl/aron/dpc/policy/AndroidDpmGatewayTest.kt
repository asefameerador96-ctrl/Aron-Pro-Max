package com.aktcl.aron.dpc.policy

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.dpc.AronDeviceAdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real DevicePolicyManager calls under Robolectric's shadow; the phone half is in docs/status/device-checks.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidDpmGatewayTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)

    @Test fun withoutDeviceOwnerNothingIsApplied() {
        assertFalse(AndroidDpmGateway(app).isDeviceOwner())
        assertEquals(listOf(PolicyApplier.NOT_DEVICE_OWNER), PolicyApplier(AndroidDpmGateway(app)).apply(policyFixture()).errors)
    }

    @Test fun asDeviceOwnerRestrictionsAndUninstallBlockAreSet() {
        shadowOf(dpm).setDeviceOwner(ComponentName(app, AronDeviceAdminReceiver::class.java))
        val gw = AndroidDpmGateway(app)
        assertTrue(gw.isDeviceOwner())
        gw.setRestriction("no_factory_reset", true)
        gw.setRestriction("no_debugging_features", true)
        assertTrue(gw.restrictions().containsAll(setOf("no_factory_reset", "no_debugging_features")))
        gw.setRestriction("no_factory_reset", false)
        assertFalse("no_factory_reset" in gw.restrictions())
        gw.setUninstallBlocked(true)
        assertTrue(gw.isUninstallBlocked())
    }
}
