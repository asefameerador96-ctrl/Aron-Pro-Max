package com.aktcl.aron.dpc.policy

import com.aktcl.aron.dpc.policy.DpmGateway.Companion.GRANT_DEFAULT
import com.aktcl.aron.dpc.policy.DpmGateway.Companion.GRANT_DENIED
import com.aktcl.aron.dpc.policy.DpmGateway.Companion.GRANT_GRANTED
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyApplierTest {
    private val gw = FakeGateway()
    private val applier = PolicyApplier(gw)
    private val prod = policyFixture()

    @Test fun prodLocksTheAcceptanceItemsDown() {
        val r = applier.apply(prod)
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        // Developer options and USB debugging cannot be turned on; unknown sources and installs off; factory reset refused.
        assertTrue(gw.restrictionSet.containsAll(listOf(
            "no_debugging_features", "no_install_unknown_sources", "no_install_unknown_sources_globally", "no_install_apps",
            "no_factory_reset", "no_safe_boot", "no_add_user", "no_config_date_time", "no_usb_file_transfer", "no_config_location",
        )))
        assertEquals("0", gw.globals["adb_enabled"])
        assertTrue(gw.autoTime); assertTrue(gw.locationOn)
        // Aron cannot be uninstalled, force-stopped or cleared.
        assertTrue(gw.uninstallBlockedNow); assertTrue(gw.userControlOff); assertTrue(r.uninstallBlocked)
        // Location permission granted and pinned (a pinned grant cannot be revoked by the user).
        assertEquals(GRANT_GRANTED, gw.grants["android.permission.ACCESS_FINE_LOCATION"])
        assertEquals(GRANT_GRANTED, gw.grants["android.permission.ACCESS_COARSE_LOCATION"])
        assertTrue(r.restrictionsApplied.values.all { it })
        assertTrue(r.deviceOwner)
    }

    @Test fun devKeepsUsbDebuggingAndLeavesGrantsUnpinned() {
        gw.globals["adb_enabled"] = "1"
        val r = applier.apply(prod.asDev())
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals("1", gw.globals["adb_enabled"]) // never touched in dev
        assertFalse("no_debugging_features" in gw.restrictionSet)
        assertFalse("no_factory_reset" in gw.restrictionSet)
        assertFalse(gw.uninstallBlockedNow)
        assertTrue(gw.calls.indexOf("p:android.permission.ACCESS_FINE_LOCATION:$GRANT_GRANTED") <
            gw.calls.indexOf("p:android.permission.ACCESS_FINE_LOCATION:$GRANT_DEFAULT"))
        assertEquals(GRANT_DEFAULT, gw.grants["android.permission.ACCESS_FINE_LOCATION"])
    }

    @Test fun movingFromDevToProdAndBackClearsAndSetsRestrictions() {
        applier.apply(prod)
        applier.apply(prod.asDev())
        assertFalse("no_install_unknown_sources_globally" in gw.restrictionSet)
        assertTrue("no_usb_file_transfer" in gw.restrictionSet) // same in both levels
        applier.apply(prod)
        assertTrue("no_debugging_features" in gw.restrictionSet)
    }

    @Test fun applyingTwiceIsIdempotent() {
        applier.apply(prod)
        val before = Triple(gw.restrictionSet.toSet(), gw.grants.toMap(), gw.globals.toMap())
        val r = applier.apply(prod)
        assertEquals(before, Triple(gw.restrictionSet.toSet(), gw.grants.toMap(), gw.globals.toMap()))
        assertTrue(r.errors.isEmpty())
    }

    @Test fun notDeviceOwnerAppliesNothingAndSaysSo() {
        gw.owner = false
        val r = applier.apply(prod)
        assertEquals(listOf(PolicyApplier.NOT_DEVICE_OWNER), r.errors)
        assertFalse(r.deviceOwner)
        assertTrue(gw.calls.isEmpty())
    }

    @Test fun oneFailingCallIsReportedAndTheRestStillApplies() {
        gw.failing += "r:no_safe_boot"
        gw.failing += "p:android.permission.CAMERA:$GRANT_GRANTED"
        val r = applier.apply(prod)
        assertTrue("restriction:no_safe_boot" in r.errors)
        assertTrue("permission:android.permission.CAMERA" in r.errors)
        assertFalse(r.restrictionsApplied["no_safe_boot"]!!)
        assertTrue("no_factory_reset" in gw.restrictionSet)
        assertEquals(GRANT_GRANTED, gw.grants["android.permission.ACCESS_FINE_LOCATION"])
    }

    @Test fun permissionsBelowTheirMinApiOrNotDeclaredAreSkipped() {
        gw.sdkInt = 30 // POST_NOTIFICATIONS is API 33, BLUETOOTH_* API 31
        applier.apply(prod)
        assertNull(gw.grants["android.permission.POST_NOTIFICATIONS"])
        assertNull(gw.grants["android.permission.BLUETOOTH_SCAN"])
        assertNull(gw.grants["android.permission.RECORD_AUDIO"]) // not declared by the app
        gw.declared = gw.declared + "android.permission.RECORD_AUDIO"
        applier.apply(prod)
        assertEquals(GRANT_DENIED, gw.grants["android.permission.RECORD_AUDIO"])
    }

    @Test fun backgroundLocationFollowsTheBreadcrumbSwitchOnly() {
        gw.declared = gw.declared + PolicyApplier.BACKGROUND_LOCATION
        val withListGrant = prod.copy(permissionGrants = prod.permissionGrants + PermissionGrant(PolicyApplier.BACKGROUND_LOCATION, "granted", 29))
        applier.apply(withListGrant)
        assertEquals(GRANT_DENIED, gw.grants[PolicyApplier.BACKGROUND_LOCATION])
        applier.apply(prod.copy(location = prod.location.copy(breadcrumbsEnabled = true)))
        assertEquals(GRANT_GRANTED, gw.grants[PolicyApplier.BACKGROUND_LOCATION])
    }

    @Test fun theOwnUpdateLiftsNoInstallAppsOnlyWhileItRuns() {
        val r = applier.apply(prod, installingOwnUpdate = true)
        assertFalse("no_install_apps" in gw.restrictionSet)
        assertTrue("no_install_unknown_sources" in gw.restrictionSet)
        assertTrue(r.errors.isEmpty())
        applier.apply(prod)
        assertTrue("no_install_apps" in gw.restrictionSet)
    }

    @Test fun aMissingBatteryExemptionIsReported() {
        gw.batteryExempt = false
        val r = applier.apply(prod)
        assertEquals(listOf(PolicyApplier.BATTERY_NOT_EXEMPT), r.errors)
        assertFalse(r.batteryOptimisationExempt)
    }

    @Test fun userControlIsOnlyTouchedOnApi30AndUp() {
        gw.sdkInt = 29
        applier.apply(prod)
        assertFalse("usercontrol" in gw.calls)
        assertTrue("no_install_unknown_sources_globally" in gw.restrictionSet) // API 29 has the global key
        val older = FakeGateway(sdkInt = 28)
        PolicyApplier(older).apply(prod)
        assertFalse("no_install_unknown_sources_globally" in older.restrictionSet)
        assertNull(older.grants[PolicyApplier.BACKGROUND_LOCATION])
    }

    @Test fun theStoreKeepsTheNewestPolicyAndSurvivesARestart() {
        val dir = Files.createTempDirectory("dpc").toFile()
        val store = PolicyStore(dir)
        assertNull(store.load())
        store.save(prod)
        val older = prod.copy(policyVersion = prod.policyVersion - 1, lockdownLevel = "dev")
        assertEquals(prod, store.save(older)) // a replayed older policy cannot loosen the phone
        assertEquals(prod, PolicyStore(dir).load())
        val newer = prod.copy(policyVersion = prod.policyVersion + 1)
        assertEquals(newer, store.save(newer))
        java.io.File(dir, "device-policy.json").writeText("{broken")
        assertNull(PolicyStore(dir).load())
    }
}
