package com.aktcl.aron.core.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Screens that show or take a device OTP are FLAG_SECURE only while they are shown. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecureScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test fun theWindowIsSecureWhileTheScreenIsShownAndClearedWhenItLeaves() {
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureScreen() }
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secure())
        shown = false
        rule.waitForIdle()
        assertEquals(0, secure())
    }

    @Test fun aWindowThatWasAlreadySecureStaysSecure() {
        rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureScreen() }
        rule.waitForIdle()
        shown = false
        rule.waitForIdle()
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secure())
    }
}
