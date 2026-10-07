package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-002 screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OtpScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, state: OtpState) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { OtpContent(state, {}, {}) } } }
        compose.waitForIdle()
    }

    @Test fun verifyIsDisabledUntilFourDigits() {
        show(AppLanguage.EN, OtpState("12")); compose.onNodeWithTag(OtpTags.VERIFY).assertIsNotEnabled()
    }


    @Test fun fourDigitsEnableVerify() { show(AppLanguage.EN, OtpState("1234")); compose.onNodeWithTag(OtpTags.VERIFY).assertIsEnabled() }

    @Test fun wrongExpiredAndTooManyShowInBangla() {
        show(AppLanguage.BN, OtpState("", OtpError.INVALID)); compose.onNodeWithText("কোডটি ভুল। আবার চেষ্টা করুন।").assertExists()
    }

    @Test fun expiredAndAttemptsTextsEnglish() {
        show(AppLanguage.EN, OtpState("1234", OtpError.ATTEMPTS_EXCEEDED))
        compose.onNodeWithText("Too many wrong codes. Ask your TSO for a new one.").assertExists(); compose.onNodeWithTag(OtpTags.VERIFY).assertIsNotEnabled()
    }
}
