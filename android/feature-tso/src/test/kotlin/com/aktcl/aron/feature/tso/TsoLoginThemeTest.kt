package com.aktcl.aron.feature.tso

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import com.aktcl.aron.core.ui.AronMode
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.LocalSunlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-TSO-MAN-13: the TSO look on the login and on every signed-in screen; Sunlight overrides it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoLoginThemeTest {
    @get:Rule val compose = createComposeRule()

    private fun render(loggedOut: Boolean, sunlight: Boolean): Triple<AronMode?, AronLookColors?, Boolean> {
        var mode: AronMode? = null
        var look: AronLookColors? = null
        var sun = false
        compose.setContent { TsoLoginTheme(AppLanguage.EN, sunlight = sunlight, loggedOut = loggedOut) { mode = LocalAronColors.current.mode; look = LocalAronLook.current; sun = LocalSunlight.current; Text("x") } }
        compose.waitForIdle()
        return Triple(mode, look, sun)
    }

    @Test fun loginIsTheTsoNightLook() {
        val (mode, look, _) = render(loggedOut = true, sunlight = false)
        assertEquals(AronMode.Dark, mode)
        assertSame(AronLook.TsoNight, look)
    }

    @Test fun signedInDayIsTheTsoNightLookToo() {
        val (mode, look, _) = render(loggedOut = false, sunlight = false)
        assertEquals(AronMode.Dark, mode)
        assertSame(AronLook.TsoNight, look)
    }

    @Test fun sunlightOverridesTheNightLook() {
        val (mode, look, sun) = render(loggedOut = false, sunlight = true)
        assertEquals(AronMode.Sunlight, mode)
        assertSame(AronLook.TsoSunlight, look)
        assertTrue(sun)
    }

    @Test fun barIconsAreLightOnTheNavyPageUnlessSunlight() {
        assertTrue(tsoLoginNeedsLightBarIcons(loggedOut = true, sunlight = false))
        assertTrue(tsoLoginNeedsLightBarIcons(loggedOut = false, sunlight = false))
        assertFalse(tsoLoginNeedsLightBarIcons(loggedOut = true, sunlight = true))
        assertFalse(tsoLoginNeedsLightBarIcons(loggedOut = false, sunlight = true))
    }
}
