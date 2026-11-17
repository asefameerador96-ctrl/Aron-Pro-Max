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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
        // the calendar dialog is open; confirming the date it opened with is no change
        compose.onNodeWithText(ctx.getString(R.string.amo_team_ok)).performClick()
        compose.waitForIdle()
        assertEquals(null, picked) // the same date again is no change: nothing is sent to the host
    }

    private fun showAt(date: String, today: String, history: SaleHistory?, onPick: (String) -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN, amo = true) { PreviousSalesContent(date, today, history, onPick = onPick) } } }
        compose.waitForIdle()
    }

    /** The calendar only offers the window: today (the Dhaka business date) is pickable, tomorrow is not, so a pick past today never reaches the host. */
    @Test fun theCalendarTakesTodayAndRefusesTomorrow() {
        val today = com.aktcl.aron.rules.BusinessDate.of(java.time.Instant.parse("2026-11-06T19:00:00Z").toEpochMilli()).toString() // 7 Nov in Dhaka
        assertEquals("2026-11-07", today)
        var picked: String? = null
        showAt("2026-11-05", today, PreviousSales.parseServer("""{"lines":[]}""", "2026-11-05")) { picked = it }
        compose.onNodeWithTag(PreviousSalesTags.DATE).performClick(); compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Sunday, November 8, 2026")).performClick() // tomorrow: not selectable
        compose.onNodeWithText(androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>().getString(R.string.amo_team_ok)).performClick(); compose.waitForIdle()
        assertEquals(null, picked) // nothing selectable was chosen: the host hears nothing
        picked = null
        compose.onNodeWithTag(PreviousSalesTags.DATE).performClick(); compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Saturday, November 7, 2026")).performClick() // today in Dhaka, although it is still the 6th in UTC
        compose.onNodeWithText(androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>().getString(R.string.amo_team_ok)).performClick(); compose.waitForIdle()
        assertEquals("2026-11-07", picked)
    }

    /** The guard: a pick outside the 90-day window (a stale saved state, a hand-built pick) leaves the date as it was; the edge day goes through. */
    @Test fun aPickOutsideTheWindowIsDropped() {
        val today = "2026-11-07"
        assertEquals("2026-08-10", PreviousSales.accept("2026-08-10", today, "2026-11-05")) // the first day inside
        assertEquals("2026-11-05", PreviousSales.accept("2026-08-09", today, "2026-11-05")) // one day too old
        assertEquals("2026-11-05", PreviousSales.accept("2026-11-08", today, "2026-11-05")) // tomorrow
        assertEquals("2026-11-05", PreviousSales.accept("junk", today, "2026-11-05"))
    }

    /** A date with no sales shows the empty answer under the date, not a table and not the previous date's lines. */
    @Test fun aDateWithNoSalesShowsTheEmptyAnswerUnderTheDate() {
        showAt("2026-11-05", "2026-11-07", PreviousSales.parseServer("""{"lines":[]}""", "2026-11-05")) {}
        compose.onNodeWithTag(PreviousSalesTags.ISO).assertTextEquals("2026-11-05")
        compose.onNodeWithTag(PreviousSalesTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(PreviousSalesTags.TOTAL).assertDoesNotExist()
    }
}
