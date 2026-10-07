package com.aktcl.aron.core.system.logout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnsentItemsDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(qualifiers = "bn")
    fun showsTheCountInBanglaWithSyncNowAndCancel() {
        var syncs = 0; var cancels = 0
        rule.setContent { AronTheme(AppLanguage.BN) { UnsentItemsDialog(12, { syncs++ }, { cancels++ }) } }
        rule.onNodeWithText("১২টি আইটেম এখনো পাঠানো হয়নি", substring = true).assertExists()
        rule.onNodeWithText("এখনই সিঙ্ক").performClick()
        rule.onNodeWithText("বাতিল").performClick()
        assertEquals(1, syncs); assertEquals(1, cancels)
    }

    @Test fun showsTheCountInEnglish() {
        rule.setContent { AronTheme(AppLanguage.EN) { UnsentItemsDialog(3, {}, {}) } }
        rule.onNodeWithText("3 items not yet sent", substring = true).assertExists()
        rule.onNodeWithText("Sync now").assertExists()
    }
}
