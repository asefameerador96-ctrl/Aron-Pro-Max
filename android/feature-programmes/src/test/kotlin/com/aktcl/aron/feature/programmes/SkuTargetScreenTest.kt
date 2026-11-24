package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SkuTargetScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: Long = 900, name: String = "Maxim", factor: Long = 20, target: Long = 500_000, achieved: Long = 120_000, bp: Long? = 2_400, unit: String = "packet") =
        SkuTargetRow(id, name, "ম্যাক্সিম", "cigarette", unit, factor, target, achieved, (target - achieved).coerceAtLeast(0L), bp, 8_571, 35_714, 8_571, 34_545)
    private fun data(rows: List<SkuTargetRow> = listOf(row()), fromFirst: Boolean = false, hasCalendar: Boolean = true, truncated: Boolean = false) =
        SkuTargetData(rows, "2026-11", "2026-11-17", "2026-11-18T01:00:00Z", fromFirst, hasCalendar, truncated)

    private fun show(language: AppLanguage, d: SkuTargetData?, today: Map<Long, LocalToday> = emptyMap(), unconfirmed: Int = 0, fork: Int = 0, offline: Boolean = false) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { SkuTargetScreen(d, loc, todayBySku = today, unconfirmedEarlier = unconfirmed, forkLeftOut = fork, offline = offline) } } }
        compose.waitForIdle()
    }

    @Test fun aCardShowsTheServerFigureInPacketsWithTheClosedDayAndNothingLocalInIt() {
        show(AppLanguage.EN, data())
        compose.onNodeWithTag(SkuTargetTags.card(900)).assertIsDisplayed()
        compose.onNodeWithText("Server figures through 2026-11-17").assertExists()
        compose.onNodeWithText("25").assertExists() // 500 sticks = 25 packets
        compose.onNodeWithText("packet").assertExists()
        compose.onNodeWithText("24%").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(SkuTargetTags.today(900)).fetchSemanticsNodes().size)
    }

    @Test fun banglaShowsTheManualLabelsAndBanglaDigits() {
        show(AppLanguage.BN, data())
        compose.onNodeWithText("এস টি ডি").assertExists()
        compose.onNodeWithText("টার্গেট").assertExists(); compose.onNodeWithText("অ্যাচিভ").assertExists(); compose.onNodeWithText("বাকি").assertExists(); compose.onNodeWithText("অর্জন").assertExists()
        compose.onNodeWithText("২৫").assertExists()
        compose.onNodeWithText("সার্ভারের হিসাব ২০২৬-১১-১৭ পর্যন্ত").assertExists()
    }

    @Test fun todayOnThisPhoneIsASeparateLabelledLineAndTheUnconfirmedCountIsAtTheTop() {
        show(AppLanguage.EN, data(), today = mapOf(900L to LocalToday(40, 2, 40_000, mapOf(20 to 40L))), unconfirmed = 3, fork = 1)
        compose.onNodeWithTag(SkuTargetTags.today(900)).assertIsDisplayed()
        compose.onNodeWithText("Today on this phone: 2 packet (not in the figures above)").assertExists()
        compose.onNodeWithText("3 memos of earlier days are not confirmed by the server yet; they are not in the figures.").assertExists()
        compose.onNodeWithTag(SkuTargetTags.FORK).assertExists()
    }

    @Test fun theFirstOfTheMonthIsNotLabelledAsCompleteThroughLastMonth() {
        show(AppLanguage.EN, data(fromFirst = true))
        compose.onNodeWithText("Server figures: no day of this month has closed yet (from the 1st)").assertExists()
    }

    @Test fun missingFiguresShowADashNeverZero() {
        show(AppLanguage.EN, data(listOf(row(bp = null).copy(adsMilli = null, tadsMilli = null, padsMilli = null, radsMilli = null)), hasCalendar = false))
        compose.onNodeWithTag(SkuTargetTags.NO_CALENDAR).assertExists()
        assertEquals(5, compose.onAllNodesWithText("—").fetchSemanticsNodes().size) // % and the four daily figures
    }

    @Test fun emptyAndOfflineAndTruncatedStates() {
        show(AppLanguage.EN, null, offline = true)
        compose.onNodeWithTag(SkuTargetTags.EMPTY).assertIsDisplayed(); compose.onNodeWithTag(SkuTargetTags.OFFLINE).assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.count() = fetchSemanticsNodes().size
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(t: String) = onAllNodes(androidx.compose.ui.test.hasText(t))
}
