package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-PAR-AMO-05 (screenshot 26): Previous sale data picks its date in a calendar field, not with step arrows; the ISO date sits under it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class PreviousSalesDateFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theDateFieldShowsTheLongDateAndOpensACalendarWithNoArrows() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        var picked: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN, amo = true) {
                    PreviousSalesContent("2026-11-05", "2026-11-06", PreviousSales.parseServer("""{"lines":[]}""", "2026-11-05"), onPick = { picked = it })
                }
            }
        }
        compose.onNodeWithTag(PreviousSalesTags.ISO).assertTextEquals("2026-11-05")
        compose.onNodeWithText(formDateText("2026-11-05")).assertIsDisplayed()
        compose.onNodeWithText("‹").assertDoesNotExist()
        compose.onNodeWithText("›").assertDoesNotExist()
        compose.onNodeWithTag(PreviousSalesTags.DATE).performClick()
        compose.waitForIdle()
        // the calendar dialog is open; confirming keeps the chosen date (inside the 90-day window) and hands it to the host
        compose.onNodeWithText(ctx.getString(R.string.amo_team_ok)).performClick()
        compose.waitForIdle()
        assertEquals("2026-11-05", picked)
    }
}
