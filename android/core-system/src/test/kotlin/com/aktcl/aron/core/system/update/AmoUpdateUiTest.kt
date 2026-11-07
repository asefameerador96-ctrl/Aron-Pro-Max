package com.aktcl.aron.core.system.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The AMO update texts (amo-shell A1, screenshots 5 to 7, R6): English wording of the vendor in English, Bangla in the Bangla locale; SR/TSO keep theirs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoUpdateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rel = ReleaseInfo("1.0.4", 12, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), null, null)

    @Test fun theEnglishScreenHasTheRibbonHeadingVersionAndDownloadButton() {
        rule.setContent { AronTheme(AppLanguage.EN) { UpdateContent(rel, false, NetworkStatus.WIFI, DownloadUi.Idle, {}, {}, {}, {}, amoStyle = true) } }
        rule.onNodeWithText("NEW UPDATE").assertExists()
        rule.onNodeWithText("Update Available").assertExists()
        rule.onNodeWithText("Version 1.0.4 available").assertExists()
        rule.onNodeWithText("Download App & Install").assertExists()
    }

    @Test @Config(qualifiers = "bn") fun theBanglaScreenNeverShowsTheVendorsEnglish() {
        rule.setContent { AronTheme(AppLanguage.BN) { UpdateContent(rel, false, NetworkStatus.WIFI, DownloadUi.Idle, {}, {}, {}, {}, amoStyle = true) } }
        rule.onNodeWithText("আপডেট পাওয়া যাচ্ছে").assertExists()
        rule.onNodeWithText("অ্যাপ ডাউনলোড ও ইনস্টল করুন").assertExists()
        rule.onNodeWithText("Download App & Install").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "bn") fun theProgressScreenSaysUpdatingWithAPercentAndTheDoNotCloseWarning() {
        rule.setContent { AronTheme(AppLanguage.BN) { UpdateContent(rel, true, NetworkStatus.WIFI, DownloadUi.Downloading(42), {}, {}, {}, {}, amoStyle = true) } }
        rule.onNodeWithTag(UpdateTags.AMO_HEADING).assertExists()
        rule.onNodeWithText("অ্যাপ আপডেট হচ্ছে").assertExists()
        rule.onNodeWithText("ডাউনলোড হচ্ছে ৪২%").assertExists()
        rule.onNodeWithText("ডাউনলোডের সময় অ্যাপটি বন্ধ করবেন না", substring = true).assertExists()
    }

    @Test fun theDefaultScreenIsUnchangedForSrAndTso() {
        rule.setContent { AronTheme(AppLanguage.EN) { UpdateContent(rel, false, NetworkStatus.WIFI, DownloadUi.Idle, {}, {}, {}, {}) } }
        rule.onNodeWithText("Download").assertExists()
        rule.onNodeWithText("NEW UPDATE").assertDoesNotExist()
    }
}
