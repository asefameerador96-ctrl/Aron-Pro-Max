package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
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
class StarScreenTest {
    @get:Rule val compose = createComposeRule()

    /** The manual's screenshot 62: Target 20.00, STD 2.83, Projection 7.37, Achievement 36.83 %, LMS 12.25, LMADS 0.47, PADS 0.28, Growth -39.87 %, status Keep Pushing, no slab. */
    private fun star(through: String = "2026-11-10", ewd: Int = 10, projection: Long? = 7_366, pads: Long? = 283, growth: Long? = -3_987, slab: Long? = null, bn: String? = "ম্যাচ বক্স লিফটিং স্টার") = RouteStar(
        3, "Match Box Lifting Star", bn, "2026-11", through, "2026-11-11T01:00:00Z", "MD. ABDUR RAHMAN EMON", "sr22233", "Agora Goli-22233", 26, 26, ewd,
        20_000, 2_833, projection, 3_683, 12_250, 471, pads, growth, "Keep Pushing", "চালিয়ে যান", null, slab, null, null,
    )

    private fun show(language: AppLanguage, stars: List<RouteStar>, truncated: Boolean = false, offline: Boolean = false) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { StarScreen(stars, loc, truncated = truncated, offline = offline) } } }
        compose.waitForIdle()
    }

    private fun tagHas(tag: String, text: String) = compose.onNodeWithTag(tag).assert(hasAnyDescendant(hasText(text)))

    @Test fun theManualsScreenshotFiguresAreShownAsSentWithTwoDecimals() {
        show(AppLanguage.EN, listOf(star()))
        compose.onNodeWithTag(StarTags.TITLE).assert(hasText("Match Box Lifting Star (November-2026)"))
        compose.onNodeWithText("SR Name: MD. ABDUR RAHMAN EMON (sr22233)").assertExists(); compose.onNodeWithText("SR Route: Agora Goli-22233").assertExists()
        compose.onNodeWithText("LMWD: 26 | WD: 26 | EWD: 10").assertExists()
        tagHas(StarTags.TARGET, "20.00"); tagHas(StarTags.STD, "2.83"); tagHas(StarTags.PROJECTION, "7.37"); tagHas(StarTags.ACHIEVEMENT, "36.83%")
        tagHas(StarTags.LMS, "12.25"); tagHas(StarTags.LMADS, "0.47"); tagHas(StarTags.PADS, "0.28"); tagHas(StarTags.GROWTH, "↓ -39.87%")
        tagHas(StarTags.STATUS, "Keep Pushing"); tagHas(StarTags.SLAB, "—"); tagHas(StarTags.SLAB_AMOUNT, "— Tk")
        compose.onNodeWithText("Server figures through 2026-11-10").assertExists()
    }

    @Test fun aFigureTheServerCouldNotComputeIsADashNeverZero() {
        show(AppLanguage.EN, listOf(star(ewd = 0, projection = null, pads = null, growth = null)))
        tagHas(StarTags.PROJECTION, "—"); tagHas(StarTags.PADS, "—"); tagHas(StarTags.GROWTH, "—")
    }

    @Test fun banglaUsesTheBanglaProgrammeNameStatusAndDigitsAndASlabAmountShowsTaka() {
        show(AppLanguage.BN, listOf(star(slab = 1_500_000)))
        compose.onNodeWithTag(StarTags.TITLE).assert(hasText("ম্যাচ বক্স লিফটিং স্টার (নভেম্বর-২০২৬)"))
        tagHas(StarTags.STATUS, "চালিয়ে যান"); tagHas(StarTags.TARGET, "২০.০০"); tagHas(StarTags.SLAB_AMOUNT, "১,৫০০ Tk")
    }

    @Test fun lastMonthNotClosedAtTheCutIsSaidAndAClosedOneIsNot() {
        show(AppLanguage.EN, listOf(star(through = "2026-10-28")))
        compose.onNodeWithTag(StarTags.LM_OPEN).assertIsDisplayed()
        assertEquals(true, StarFormat.lastMonthOpen("2026-11", "2026-10-30")); assertEquals(false, StarFormat.lastMonthOpen("2026-11", "2026-10-31")); assertEquals(false, StarFormat.lastMonthOpen("2026-11", "2026-11-10"))
    }

    @Test fun noStarIsTheEmptyStateAndTruncationAndOfflineAreSaid() {
        show(AppLanguage.EN, emptyList(), offline = true)
        compose.onNodeWithTag(StarTags.EMPTY).assertIsDisplayed(); compose.onNodeWithTag(StarTags.OFFLINE).assertExists()
    }

    @Test fun aCutProgrammeListSaysSo() {
        show(AppLanguage.EN, listOf(star()), truncated = true)
        compose.onNodeWithTag(StarTags.TRUNCATED).assertExists()
        assertEquals(0, compose.onAllNodesWithTag(StarTags.EMPTY).fetchSemanticsNodes().size)
    }

    @Test fun decimalAndGrowthFormattingRoundOnceAndKeepTheSign() {
        assertEquals("0.01", StarFormat.decimal(5, UiLocale.EN)); assertEquals("0.00", StarFormat.decimal(4, UiLocale.EN)); assertEquals("—", StarFormat.decimal(null, UiLocale.EN))
        assertEquals("↑ 5.00%", StarFormat.growth(500, UiLocale.EN)); assertEquals("↓ -39.87%", StarFormat.growth(-3_987, UiLocale.EN)); assertEquals("0.00%", StarFormat.growth(0, UiLocale.EN))
    }
}
