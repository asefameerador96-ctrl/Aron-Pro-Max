package com.aktcl.aron.feature.dayclose

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
@Config(sdk = [36])
class SalesSubmitScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clean = OutboxStates(0, 0, 40, 0, 0)

    @Test fun enabledAfterSyncWarnsOnDuesAndSubmitsOnce() {
        var submitted = 0
        val counts = listOf(CountRow("memo", 6, 6))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(2, 125_000), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, {}, { submitted++ }) } }
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("2 retailers still owe 125.00 ৳. You can still submit.").assertExists()
        compose.onNodeWithText("Submit sales").assertIsEnabled().performClick()
        assertEquals(1, submitted)
    }

    @Test fun blankServerColumnBlocksAndNamesTheMismatch() {
        val counts = listOf(CountRow("memo", 6, null))
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, {}, {}) } }
        compose.onNodeWithText("Submit sales").assertIsNotEnabled()
        compose.onNodeWithText("memo: device 6, server —.").assertExists()
    }

    @Test fun syncNeedsConnectionAndSuccessShowsOnlyWhenSettled() {
        val gate = SalesSubmitRules.gate(clean, emptyList(), DuesAtSubmit(0, 0), false)
        compose.setContent { AronTheme(AppLanguage.EN) { SalesSubmitScreen(false, emptyList(), gate, SubmitProgress.Queued, { it }, {}, {}) } }
        compose.onNodeWithText("Sync data").assertIsNotEnabled()
        compose.onNodeWithText("Submit is saved and will be sent when you are online.").assertExists()
        compose.onNodeWithText("Your sales for today are submitted.").assertDoesNotExist()
        compose.onNodeWithText("Submit sales").assertIsNotEnabled()
    }
}
