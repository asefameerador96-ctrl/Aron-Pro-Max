package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The TSO variant of the login (screenshot 5, D-TSO-MAN-15) in the TSO look: labels above glow fields, the eye, Login, the language toggle at the bottom. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class TsoLoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private var submits = 0
    private var languages = mutableListOf<AppLanguage>()

    private fun render(language: AppLanguage, initial: LoginUiState = LoginUiState(), sunlight: Boolean = false) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            var state by androidx.compose.runtime.remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language, dark = true, sunlight = sunlight, tso = true) {
                    TsoLoginContent(
                        state, { state = state.copy(username = it) }, { state = state.copy(password = it) },
                        { state = state.copy(passwordVisible = !state.passwordVisible) }, { submits++ }, { languages += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun englishShowsTheScreenshotLabelsAndNoVersionOrSunSwitch() {
        render(AppLanguage.EN)
        compose.onNodeWithTag(TsoLoginTags.PLATE).assertIsDisplayed()
        // the label above the field and the placeholder inside it both say Username (screenshot 5)
        assertTrue(compose.onAllNodesWithText("Username", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        assertTrue(compose.onAllNodesWithText("Password", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Login").assertExists()
        compose.onNodeWithTag(LoginTags.VERSION).assertDoesNotExist()
        compose.onNodeWithContentDescription("Sunlight mode", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(TsoLoginTags.LANGUAGE).performScrollTo().assertIsDisplayed()
    }

    @Test fun banglaLabelsAndTheLanguageToggleSwitches() {
        render(AppLanguage.BN)
        compose.onNodeWithText("লগইন").assertExists()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(AppLanguage.EN), languages)
    }

    @Test fun theEyeShowsAndHidesThePassword() {
        render(AppLanguage.EN, LoginUiState(username = "tso1001", password = "secret"))
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        compose.onNodeWithText("secret").assertExists()
    }

    @Test fun loginSubmits() {
        render(AppLanguage.EN, LoginUiState(username = "tso1001", password = "secret"))
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        assertEquals(1, submits)
    }

    @Test fun busyKeepsTheLabelAndIsNotPressable() {
        render(AppLanguage.EN, LoginUiState(username = "tso1001", password = "secret", busy = true))
        compose.onNodeWithText("Logging in...").assertExists()
        compose.onNodeWithTag(LoginTags.SUBMIT).assertIsNotEnabled()
    }

    @Test fun aRefusalShowsItsMessageAndSunlightKeepsTheScreen() {
        render(AppLanguage.EN, LoginUiState(message = LoginMessage.InvalidCredentials), sunlight = true)
        compose.onNodeWithTag(LoginTags.MESSAGE).assertExists()
        assertTrue(compose.onNodeWithTag(TsoLoginTags.PLATE).fetchSemanticsNode().size.width > 0)
    }
}
