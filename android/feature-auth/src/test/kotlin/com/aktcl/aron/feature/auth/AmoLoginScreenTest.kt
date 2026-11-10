package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.session.LoginOutcome
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The AMO login (screenshot 03, D-AMO-SHELL-07): [LoginVariant.Amo] end to end through [LoginScreen]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class AmoLoginScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val footer = "ARON AMO v1.0.25"
    private var calls = 0

    private fun render(language: AppLanguage, outcome: LoginOutcome = LoginOutcome.InvalidCredentials): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val vm = LoginViewModel { _, _ -> calls++; outcome }
        // the app wires the bind step (MainActivity); without it BindRequired is only a message
        vm.bind = { _, _, _ -> error("not reached in these tests") }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) {
                    LoginScreen(vm, "ARON AMO", "1.0.25 (12)", {}, variant = LoginVariant.Amo, footerText = footer)
                }
            }
        }
        compose.waitForIdle()
        return context
    }

    @Test fun emptySubmitShowsTheMessageAndNeverCallsLogin() {
        val context = render(AppLanguage.EN)
        // the pill is pressable with empty fields (as on the vendor screen) and says why nothing happened
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LoginTags.MESSAGE).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.auth_error_empty), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test fun footerShowsTheGivenTextWithLatinDigitsInBangla() {
        render(AppLanguage.BN)
        compose.onNodeWithTag(LoginTags.VERSION).performScrollTo().assertTextEquals(footer)
    }

    @Test fun aWrongPasswordShowsTheRefusalOnTheSharedCard() {
        val context = render(AppLanguage.EN)
        compose.onNodeWithTag(LoginTags.USERNAME).performTextInput("amo5864")
        compose.onNodeWithTag(LoginTags.PASSWORD).performTextInput("bad")
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        compose.onNodeWithText(context.getString(R.string.auth_error_invalid_credentials), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun bindRequiredHandsOverToTheOtpStep() {
        render(AppLanguage.EN, LoginOutcome.BindRequired("tok"))
        compose.onNodeWithTag(LoginTags.USERNAME).performTextInput("amo5864")
        compose.onNodeWithTag(LoginTags.PASSWORD).performTextInput("pw")
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
        // the OTP step replaces the AMO login frame (the variant only draws the login step)
        compose.onNodeWithTag(OtpTags.VERIFY).assertExists()
        compose.onNodeWithTag(LoginTags.SUBMIT).assertDoesNotExist()
    }
}
