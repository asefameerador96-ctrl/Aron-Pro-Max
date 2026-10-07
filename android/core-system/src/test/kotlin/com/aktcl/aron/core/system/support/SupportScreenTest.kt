package com.aktcl.aron.core.system.support

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class SupportScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "bn")
    fun offlineQueuedStateIsVisibleInBanglaWithVersionAndLastSync() {
        rule.setContent { AronTheme(AppLanguage.BN) { SupportContent(SupportStatus.Queued(online = false, needsWifi = false), "1.2.3+12", "07/10 11:00", {}) } }
        rule.onNodeWithText("ফোন অনলাইন হলে পাঠানো হবে", substring = true).assertExists()
        rule.onNodeWithText("অ্যাপ সংস্করণ: 1.2.3+12").assertExists()
        rule.onNodeWithText("শেষ সিঙ্ক: ০৭/১০ ১১:০০").assertExists()
    }

    @Test fun successAndFailureAreShown() {
        rule.setContent { AronTheme(AppLanguage.EN) { SupportContent(SupportStatus.Sent("11:05"), "1.2.3+12", null, {}) } }
        rule.onNodeWithText("Sent to support at 11:05.").assertExists()
        rule.onNodeWithText("Last sync: never").assertExists()
    }

    @Test fun aRefusalIsShown() {
        rule.setContent { AronTheme(AppLanguage.EN) { SupportContent(SupportStatus.Failed(SupportFailure.REFUSED), "1.2.3+12", null, {}) } }
        rule.onNodeWithText("Support could not receive the file", substring = true).assertExists()
    }
}
