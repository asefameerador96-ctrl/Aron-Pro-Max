package com.aktcl.aron.feature.tso

import androidx.compose.runtime.Composable
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme

/**
 * The TSO theme (D-TSO-MAN-13, ruling 7): the TSO look ([com.aktcl.aron.core.ui.AronLook.TsoNight]: navy page, glow cards, cyan
 * figures, gradient titles) for the login AND every signed-in TSO screen, on the kit's Dark mode for the shared Calm Glass parts
 * (enrolment, location notice, support upload). Sunlight wins over the night look (docs/32 s2a: legibility first): solid light
 * surfaces and black ink ([com.aktcl.aron.core.ui.AronLook.TsoSunlight]). [loggedOut] no longer changes the look; it is kept for the
 * callers.
 */
@Composable
fun TsoLoginTheme(language: AppLanguage, sunlight: Boolean = false, @Suppress("UNUSED_PARAMETER") loggedOut: Boolean = true, content: @Composable () -> Unit) {
    AronTheme(language, dark = !sunlight, sunlight = sunlight, tso = true, content = content)
}

/** True when the system bars need light icons over the page: the navy TSO page, unless Sunlight is on. */
@Suppress("UNUSED_PARAMETER")
fun tsoLoginNeedsLightBarIcons(loggedOut: Boolean, sunlight: Boolean): Boolean = !sunlight
