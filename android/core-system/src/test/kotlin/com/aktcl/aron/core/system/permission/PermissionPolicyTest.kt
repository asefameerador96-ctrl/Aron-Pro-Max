package com.aktcl.aron.core.system.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private fun snap(vararg s: Pair<RuntimePermission, PermissionStatus>, locationOn: Boolean? = true) =
        PermissionSnapshot(RuntimePermission.entries.associateWith { PermissionStatus.GRANTED } + s.toMap(), locationOn)

    @Test fun locationDeniedBlocksAttendanceSaleAndOutletRequestsOnly() {
        for (status in listOf(PermissionStatus.DENIED, PermissionStatus.NOT_ASKED, PermissionStatus.DENIED_PERMANENTLY)) {
            val s = snap(RuntimePermission.LOCATION to status)
            val blocked = GatedFeature.entries.filterNot { PermissionPolicy.allowed(it, s) }.toSet()
            assertEquals(setOf(GatedFeature.ATTENDANCE, GatedFeature.SALE, GatedFeature.OUTLET_REQUEST), blocked)
        }
    }

    @Test fun permanentDenialSendsToTheAppSettingsPageAndAPlainDenialAsksAgain() {
        val forever = PermissionPolicy.decide(GatedFeature.SALE, snap(RuntimePermission.LOCATION to PermissionStatus.DENIED_PERMANENTLY))
        assertEquals(GateDecision.Blocked(GatedFeature.SALE, RuntimePermission.LOCATION, GateAction.OPEN_APP_SETTINGS), forever)
        val once = PermissionPolicy.decide(GatedFeature.ATTENDANCE, snap(RuntimePermission.LOCATION to PermissionStatus.DENIED))
        assertEquals(GateAction.ASK_AGAIN, (once as GateDecision.Blocked).action)
    }

    @Test fun bluetoothDenialDisablesOnlyPrinting() {
        for (status in listOf(PermissionStatus.DENIED, PermissionStatus.DENIED_PERMANENTLY, PermissionStatus.NOT_ASKED)) {
            val s = snap(RuntimePermission.BLUETOOTH to status)
            val blocked = GatedFeature.entries.filterNot { PermissionPolicy.allowed(it, s) }.toSet()
            assertEquals(setOf(GatedFeature.PRINT), blocked)
        }
        assertEquals(setOf(GatedFeature.PRINT), PermissionPolicy.featuresBlockedBy(RuntimePermission.BLUETOOTH))
    }

    @Test fun cameraDenialDisablesOnlyPhotos() {
        val s = snap(RuntimePermission.CAMERA to PermissionStatus.DENIED_PERMANENTLY)
        assertEquals(setOf(GatedFeature.PHOTO), GatedFeature.entries.filterNot { PermissionPolicy.allowed(it, s) }.toSet())
    }

    @Test fun locationSwitchOffBlocksLocationFeaturesWithTheLocationSettingsAction() {
        val s = snap(locationOn = false)
        val d = PermissionPolicy.decide(GatedFeature.ATTENDANCE, s) as GateDecision.Blocked
        assertEquals(GateAction.OPEN_LOCATION_SETTINGS, d.action)
        assertTrue(PermissionPolicy.allowed(GatedFeature.PRINT, s))
        // Unknown switch state is not a block: the fix itself reports location_off.
        assertTrue(PermissionPolicy.allowed(GatedFeature.SALE, snap(locationOn = null)))
    }

    @Test fun everythingGrantedAllowsEverything() {
        assertTrue(GatedFeature.entries.all { PermissionPolicy.allowed(it, PermissionSnapshot.allGranted()) })
    }

    @Test fun aMissingStatusIsNotAGrant() {
        val s = PermissionSnapshot(emptyMap(), true)
        assertTrue(GatedFeature.entries.none { PermissionPolicy.allowed(it, s) })
    }
}
