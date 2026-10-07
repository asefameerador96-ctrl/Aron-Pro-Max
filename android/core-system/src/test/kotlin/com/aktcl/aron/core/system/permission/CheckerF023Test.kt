package com.aktcl.aron.core.system.permission

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checker (F-SYS-023): defects found in review. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckerF023Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun permanentlyDeniedBluetoothMustNotTellTheRepToChangeLocation() {
        val d = GateDecision.Blocked(GatedFeature.PRINT, RuntimePermission.BLUETOOTH, GateAction.OPEN_APP_SETTINGS)
        rule.setContent { PermissionBlockedContent(d, onAction = {}, onBack = {}) }
        rule.onNodeWithText("Location", substring = true).assertDoesNotExist()
    }

    @Test fun permanentlyDeniedCameraMustNotTellTheRepToChangeLocation() {
        val d = GateDecision.Blocked(GatedFeature.PHOTO, RuntimePermission.CAMERA, GateAction.OPEN_APP_SETTINGS)
        rule.setContent { PermissionBlockedContent(d, onAction = {}, onBack = {}) }
        rule.onNodeWithText("Location", substring = true).assertDoesNotExist()
    }

    @Test fun manifestAuditCatchesSingleQuotedMicrophone() {
        val p = "android.permission.RECORD_AUDIO"
        val text = "<manifest xmlns:android='http://schemas.android.com/apk/res/android'><uses-permission android:name='$p' /></manifest>"
        assertTrue(p in ManifestAudit.requestedPermissions(text))
    }
}
