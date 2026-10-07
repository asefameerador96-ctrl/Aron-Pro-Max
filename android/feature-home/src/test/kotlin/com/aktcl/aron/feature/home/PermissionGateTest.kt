package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-003. */
class PermissionGateTest {
    @Test fun neverAsksForTheMicrophone() = assertFalse(PermissionGate.requested().any { it.contains("RECORD_AUDIO") })

    @Test fun firstRunAsksLocationCameraBluetoothAndNotificationsInOrder() =
        assertEquals(listOf(AppPermission.PRECISE_LOCATION, AppPermission.CAMERA, AppPermission.BLUETOOTH, AppPermission.NOTIFICATIONS), PermissionGate.initial().toAsk)

    @Test fun visitNeedsLocationButPrintingAndPhotosOnlyBlockTheirOwnScreens() {
        val s = PermissionState(mapOf(AppPermission.PRECISE_LOCATION to PermissionStatus.GRANTED, AppPermission.CAMERA to PermissionStatus.DENIED, AppPermission.BLUETOOTH to PermissionStatus.DENIED_PERMANENTLY, AppPermission.NOTIFICATIONS to PermissionStatus.DENIED))
        assertTrue(s.canOpenVisit); assertFalse(s.canTakePhoto); assertFalse(s.canPrint)
        assertEquals(listOf(AppPermission.BLUETOOTH), s.needsSettings); assertTrue(s.toAsk.isEmpty())
    }

    @Test fun deniedLocationBlocksTheVisit() = assertFalse(PermissionState(mapOf(AppPermission.PRECISE_LOCATION to PermissionStatus.DENIED)).canOpenVisit)
}
