package com.aktcl.aron.core.system.permission

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** F-SYS-023 acceptance on the real composable: the blocked screen, its Bangla rationale and the Settings deep link. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PermissionGateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun denyLocationForGood() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Asked before, and Robolectric reports no rationale hint: the system prompt can no longer be shown.
        app.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE).edit().putBoolean("PRECISE_LOCATION", true).commit()
    }

    @Test @Config(qualifiers = "bn")
    fun deniedLocationBlocksAttendanceWithTheBanglaRationaleAndOpensTheAppSettingsPage() {
        denyLocationForGood()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
        rule.setContent { PermissionGate(GatedFeature.ATTENDANCE, onBack = {}) { Text("attendance-content") } }

        rule.onNodeWithTag(PermissionGateTags.BLOCKED).assertExists()
        rule.onNodeWithText("হাজিরার জন্য লোকেশন দরকার").assertIsDisplayed()
        rule.onNodeWithText("attendance-content").assertDoesNotExist()
        rule.onNodeWithText("সেটিংস খুলুন").assertIsDisplayed()

        rule.onNodeWithTag(PermissionGateTags.ACTION).performClick()
        val started = shadowOf(rule.activity).nextStartedActivity ?: shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
    }

    @Test @Config(qualifiers = "bn")
    fun deniedLocationBlocksSaleToo() {
        denyLocationForGood()
        rule.setContent { PermissionGate(GatedFeature.SALE, onBack = {}) { Text("sale-content") } }
        rule.onNodeWithText("বিক্রির জন্য লোকেশন দরকার").assertIsDisplayed()
        rule.onNodeWithText("sale-content").assertDoesNotExist()
    }

    @Test
    fun theGateOpensByItselfWhenTheRepComesBackFromSettingsWithTheGrant() {
        denyLocationForGood()
        rule.setContent { PermissionGate(GatedFeature.SALE, onBack = {}) { Text("sale-content") } }
        rule.onNodeWithText("Sale needs your location").assertIsDisplayed()

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.onNodeWithText("sale-content").assertIsDisplayed()
    }

    @Test
    fun deniedBluetoothLeavesSaleOpenAndBlocksOnlyPrinting() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        rule.setContent { PermissionGate(GatedFeature.SALE, onBack = {}) { Text("sale-content") } }
        rule.onNodeWithText("sale-content").assertIsDisplayed()
        val snap = AndroidPermissions.snapshot(rule.activity)
        assertTrue(PermissionPolicy.allowed(GatedFeature.SALE, snap))
        assertTrue(!PermissionPolicy.allowed(GatedFeature.PRINT, snap))
    }

    @Test
    fun aFirstDenialOffersTheSystemPromptAgainNotSettings() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        rule.setContent { PermissionGate(GatedFeature.ATTENDANCE, onBack = {}) { Text("x") } }
        rule.onNodeWithText("Allow").assertIsDisplayed()
        rule.onNodeWithTag(PermissionGateTags.ACTION).performClick()
        // The request goes to the system prompt, never to Settings.
        val started = shadowOf(app).nextStartedActivity
        assertTrue(started == null || started.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
}
