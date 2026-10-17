package com.aktcl.aron.feature.tso

import androidx.compose.runtime.Composable
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme

/**
 * The TSO login keeps the dark navy theme of the manual (docs/08) as the kit's Dark mode, not a copy of the kit: the same
 * tokens, components and contrast ledger, so the pre-sign-in screens (enrolment, login) stay legible. Sunlight wins over
 * dark (docs/32 s2a: legibility first), so the sun switch on the login row still lifts the screen to solid light. Signed in ([loggedOut] false) the day is the light Calm Glass theme.
 */
@Composable
fun TsoLoginTheme(language: AppLanguage, sunlight: Boolean = false, loggedOut: Boolean = true, content: @Composable () -> Unit) {
    AronTheme(language, dark = loggedOut, sunlight = sunlight, content = content)
}

/** True when the system bars need light icons over the page: dark navy login, and only while sunlight is off. */
fun tsoLoginNeedsLightBarIcons(loggedOut: Boolean, sunlight: Boolean): Boolean = loggedOut && !sunlight
