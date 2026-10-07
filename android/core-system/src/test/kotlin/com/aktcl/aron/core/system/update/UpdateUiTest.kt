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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rel = ReleaseInfo("1.2.3", 12, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), "Fixes", "সমাধান")

    @Test @Config(qualifiers = "bn")
    fun banglaProgressNotesAndNoLaterWhenRequired() {
        rule.setContent { AronTheme(AppLanguage.BN) { UpdateContent(rel, required = true, network = NetworkStatus.WIFI, download = DownloadUi.Downloading(42), {}, {}, {}, {}) } }
        rule.onNodeWithText("ডাউনলোড হচ্ছে ৪২%").assertExists()
        rule.onNodeWithText("সমাধান").assertExists()
        rule.onNodeWithText("ডাউনলোডের সময় অ্যাপটি বন্ধ করবেন না", substring = true).assertExists()
        rule.onNodeWithTag(UpdateTags.LATER).assertDoesNotExist()
        rule.onNodeWithText("সংস্করণ 1.2.3", substring = true).assertExists() // the version name is an identifier
    }

    @Test fun anOptionalUpdateCanBeLeftForLaterAndUnknownSourcesIsExplained() {
        rule.setContent { AronTheme(AppLanguage.EN) { UpdateContent(rel, required = false, network = NetworkStatus.MOBILE, download = DownloadUi.NeedsUnknownSources, {}, {}, {}, {}) } }
        rule.onNodeWithTag(UpdateTags.LATER).assertExists()
        rule.onNodeWithTag(UpdateTags.UNKNOWN_SOURCES).assertExists()
        rule.onNodeWithText("Network: mobile data").assertExists()
    }

    @Test fun theDayGateBlocksOnlyANewDay() {
        rule.setContent { AronTheme(AppLanguage.EN) { DayGateBanner(DayGate.BLOCKED_UPDATE_REQUIRED, {}) } }
        rule.onNodeWithTag(UpdateTags.DAY_BLOCKED).assertExists()
        rule.onNodeWithText("Unsent sales still go out", substring = true).assertExists()
    }
}
