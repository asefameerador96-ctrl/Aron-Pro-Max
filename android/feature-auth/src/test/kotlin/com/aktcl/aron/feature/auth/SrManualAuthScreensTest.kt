package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** SR manual screens 03 (login) and 04 (OTP) through [LoginScreen] with [LoginVariant.Sr] (D-SR-MAN-S1-01, SRM-A-03 and SRM-A-06). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrManualAuthScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var calls = 0
    private var boundWith: String? = null

    private fun render(language: AppLanguage, outcome: LoginOutcome = LoginOutcome.InvalidCredentials, activityContext: Boolean = false): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val vm = LoginViewModel { _, _ -> calls++; outcome }
        vm.bind = { _, otp, _ -> boundWith = otp; com.aktcl.aron.core.session.BindOutcome.Failed("ERR_AUTH_OTP_INVALID") }
        compose.setContent {
            // activityContext: the real activity as LocalContext, as in the app (FLAG_SECURE needs a window)
            CompositionLocalProvider(LocalContext provides (if (activityContext) LocalContext.current else context)) {
                AronTheme(language) { LoginScreen(vm, "ARON SR", "1.0.25", {}, variant = LoginVariant.Sr) }
            }
        }
        compose.waitForIdle()
        return context
    }

    private fun goToOtp() {
        compose.onNodeWithTag(LoginTags.USERNAME).performTextInput("sr0001")
        compose.onNodeWithTag(LoginTags.PASSWORD).performTextInput("fake-pw")
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun loginShowsTheManualLabelsInBangla() {
        render(AppLanguage.BN)
        compose.onNodeWithText("ইউজারনেম", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("পাসওয়ার্ড", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("লগইন", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SrLoginTags.HERO).assertExists()
        compose.onNodeWithTag(SrLoginTags.LOGO).assertExists()
    }

    @Test fun loginShowsTheManualLabelsInEnglish() {
        render(AppLanguage.EN)
        compose.onNodeWithText("Username", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Password", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Login", useUnmergedTree = true).assertExists()
    }

    @Test fun footerIsSrAppWithTheVersionAndNoVendorCredit() {
        render(AppLanguage.BN)
        compose.onNodeWithTag(LoginTags.VERSION).performScrollTo().assertTextEquals("SR App\n(version - 1.0.25)")
        compose.onNodeWithText("Apsis", substring = true, ignoreCase = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun enrolLinkIsAbsentUnlessTheServerSaidTheDeviceIsNotEnrolled() {
        render(AppLanguage.EN)
        compose.onNodeWithTag(LoginTags.ENROL).assertDoesNotExist()
    }

    @Test fun emptySubmitSaysWhyAndNeverCallsLogin() {
        val context = render(AppLanguage.EN)
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.auth_error_empty), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test fun bindRequiredShowsTheManualOtpScreenInEnglish() {
        val context = render(AppLanguage.EN, LoginOutcome.BindRequired("tok"))
        goToOtp()
        compose.onNodeWithText("OTP verification", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(OtpTags.MANUAL_HEADING).assertTextEquals("Enter OTP")
        compose.onNodeWithText("Enter the 4-digit OTP provided by your TSO.", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(OtpTags.MANUAL_NOTICE).assertTextEquals(context.getString(R.string.otp_manual_notice))
        compose.onNodeWithTag(OtpTags.VERIFY).assertIsNotEnabled()
        compose.onNodeWithTag(LoginTags.SUBMIT).assertDoesNotExist()
    }

    @Test fun otpScreenInBangla() {
        render(AppLanguage.BN, LoginOutcome.BindRequired("tok"))
        goToOtp()
        compose.onNodeWithText("OTP যাচাইকরণ", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(OtpTags.MANUAL_HEADING).assertTextEquals("OTP লিখুন")
        compose.onNodeWithText("যাচাই করুন", useUnmergedTree = true).assertExists()
    }

    @Test fun fourDigitsEnableVerifyAndAWrongCodeShowsTheDangerLineWithoutLeakingTheCode() {
        val context = render(AppLanguage.EN, LoginOutcome.BindRequired("tok"))
        goToOtp()
        compose.onNodeWithTag(OtpTags.BOXES).performTextInput("3560")
        compose.waitForIdle()
        compose.onNodeWithTag(OtpTags.VERIFY).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("3560", boundWith)
        compose.onNodeWithText(context.getString(R.string.otp_invalid), useUnmergedTree = true).assertIsDisplayed()
        // a wrong code clears the boxes (the next try starts empty) and Verify waits for four digits again
        compose.onNodeWithTag(OtpTags.VERIFY).assertIsNotEnabled()
    }

    @Test fun backFromOtpReturnsToTheLoginAndDropsTheBindToken() {
        render(AppLanguage.EN, LoginOutcome.BindRequired("tok"))
        goToOtp()
        compose.onNodeWithContentDescription("Back to login").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LoginTags.SUBMIT).assertExists()
    }

    @Test fun theOtpStepIsSecureForTheScreenCaptureAndTheLoginIsNot() {
        render(AppLanguage.EN, LoginOutcome.BindRequired("tok"), activityContext = true)
        val secure = { compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE }
        assertEquals(0, secure())
        goToOtp()
        assertEquals(android.view.WindowManager.LayoutParams.FLAG_SECURE, secure())
        compose.onNodeWithContentDescription("Back to login").performClick()
        compose.waitForIdle()
        assertEquals(0, secure())
    }
}
