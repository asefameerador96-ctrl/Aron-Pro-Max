package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronFonts
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-018 on the login screen: Bangla strings, Bengali digits and the bundled Bengali font; English the same in ASCII. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LoginScreenLocaleTest {
    @get:Rule val compose = createComposeRule()

    private fun render(language: AppLanguage, state: LoginUiState = LoginUiState()): FontFamily? {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = AppLocale.wrap(base, language)
        var family: FontFamily? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(language) {
                    family = MaterialTheme.typography.bodyMedium.fontFamily
                    LoginContent(state, "ARON SR", "0.1.0 (12)", {}, {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        return family
    }

    @Test
    fun banglaShowsBanglaTextBengaliDigitsAndTheBengaliFont() {
        val family = render(AppLanguage.BN, LoginUiState(message = LoginMessage.InvalidCredentials))
        assertEquals(AronFonts.Bengali, family)
        compose.onNodeWithText("লগইন করুন").assertIsDisplayed()
        compose.onNodeWithText("ইউজারনেম").assertIsDisplayed()
        compose.onNodeWithText("সংস্করণ ০.১.০ (১২)").assertExists() // below the fold on the small test screen
        compose.onNodeWithText("ইউজারনেম বা পাসওয়ার্ড ভুল।").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
    }

    @Test
    fun englishShowsEnglishTextAndAsciiDigits() {
        val family = render(AppLanguage.EN)
        assertEquals(AronFonts.Latin, family)
        compose.onNodeWithTag(LoginTags.SUBMIT).assertIsDisplayed()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Version 0.1.0 (12)").assertExists()
        compose.onNodeWithText("বাংলা").assertIsDisplayed()
    }

    @Test
    fun lockedMessageUsesBengaliDigitsForMinutes() {
        render(AppLanguage.BN, LoginUiState(message = LoginMessage.Locked(15)))
        compose.onNodeWithText("আপনার অ্যাকাউন্ট লক করা আছে। ১৫ মিনিট পরে আবার চেষ্টা করুন।").assertIsDisplayed()
    }
}
