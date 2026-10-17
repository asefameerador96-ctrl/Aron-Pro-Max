package com.aktcl.aron.feature.tso

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronMode
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalSunlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoLoginThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loginIsTheDarkNavyKitTheme() {
        var mode: AronMode? = null
        compose.setContent { TsoLoginTheme(AppLanguage.BN) { mode = LocalAronColors.current.mode; Text("x") } }
        compose.waitForIdle()
        assertEquals(AronMode.Dark, mode)
    }

    @Test
    fun signedInDayIsTheLightTheme() {
        var mode: AronMode? = null
        compose.setContent { TsoLoginTheme(AppLanguage.EN, loggedOut = false) { mode = LocalAronColors.current.mode; Text("x") } }
        compose.waitForIdle()
        assertEquals(AronMode.Light, mode)
    }

    @Test
    fun sunlightOverridesDarkSoTheSunSwitchStillWorks() {
        var mode: AronMode? = null
        var sun = false
        compose.setContent { TsoLoginTheme(AppLanguage.EN, sunlight = true) { mode = LocalAronColors.current.mode; sun = LocalSunlight.current; Text("x") } }
        compose.waitForIdle()
        assertEquals(AronMode.Sunlight, mode)
        assertTrue(sun)
    }

    @Test
    fun barIconsAreLightOnlyOnTheDarkLogin() {
        assertTrue(tsoLoginNeedsLightBarIcons(loggedOut = true, sunlight = false))
        assertFalse(tsoLoginNeedsLightBarIcons(loggedOut = true, sunlight = true))
        assertFalse(tsoLoginNeedsLightBarIcons(loggedOut = false, sunlight = false))
    }
}
