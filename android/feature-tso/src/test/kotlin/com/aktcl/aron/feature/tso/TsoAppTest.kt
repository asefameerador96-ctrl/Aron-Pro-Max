package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeTags
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.tso.password.CpOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The signed-in TSO app (D-TSO-MAN-14): Bangla root, the placeholder of a Dashboard not wired yet, and Settings behind the gear. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoAppTest {
    @get:Rule val compose = createComposeRule()

    private val user = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = false, reauthRequired = true, updateRequired = false)
    private val languages = mutableListOf<AppLanguage>()

    private fun show(language: AppLanguage, sunlight: Boolean = false): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                TsoLoginTheme(language, sunlight = sunlight, loggedOut = false) {
                    TsoApp(
                        user, "2026-10-05", BundleStatus.Ready("b-17", "2026-10-05"), "0.1.0 (1)", onLanguageSelect = { languages += it }, onLogout = {},
                        sunlight = sunlight, onSunlight = {}, changePassword = { _, _ -> CpOutcome.Ok },
                    )
                }
            }
        }
        compose.waitForIdle()
        return context
    }

    private fun openSettings() {
        compose.onNodeWithTag(TsoShellTags.MENU).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoShellTags.GEAR).performClick(); compose.waitForIdle()
    }

    @Test fun banglaRootGreetsTheUserAndADashboardWithoutAHostIsThePlaceholder() {
        show(AppLanguage.BN)
        compose.onNodeWithText("শুভ দিন, Testing Dhaka North").assertIsDisplayed()
        compose.onNodeWithTag(TsoShellTags.PLACEHOLDER).assertExists()
        compose.onNodeWithTag(HomeTags.HEADER).assertDoesNotExist() // the tile Home is retired
    }

    @Test fun settingsHasLanguageSunlightVersionAndChangePasswordButNoLogout() {
        show(AppLanguage.EN)
        openSettings()
        compose.onNodeWithText("Version 0.1.0 (1)").performScrollTo().assertExists()
        compose.onNodeWithTag(RoleHomeTags.SET_SUNLIGHT).performScrollTo().assertExists()
        compose.onNodeWithTag(TsoSettingsTags.CHANGE_PASSWORD).performScrollTo().assertExists()
        // Log Out is the last drawer row with its own dialog (D-TSO-MAN-37)
        compose.onNodeWithTag(HomeTags.LOGOUT).assertDoesNotExist()
        compose.onNodeWithTag(RoleHomeTags.SET_LANG_BN).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(listOf(AppLanguage.BN), languages)
    }

    @Test fun changePasswordOpensFromSettingsAndBackReturns() {
        show(AppLanguage.EN)
        openSettings()
        compose.onNodeWithTag(TsoSettingsTags.CHANGE_PASSWORD).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoSettingsTags.CHANGE_PASSWORD).assertDoesNotExist()
    }

    @Test fun sunlightKeepsTheShell() {
        show(AppLanguage.EN, sunlight = true)
        compose.onNodeWithText("Good day, Testing Dhaka North").assertIsDisplayed()
        compose.onNodeWithTag(TsoKitTags.HOME).assertExists()
    }
}
