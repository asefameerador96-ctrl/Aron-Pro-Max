package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
class OutletTargetScreenTest {
    @get:Rule val compose = createComposeRule()

    private val rows = listOf(
        OutletTargetSku(900, "Maxim", "ম্যাক্সিম", "cigarette", "packet", 20, 500_000, 120_000, 24),
        OutletTargetSku(902, "Safe Match", null, "match", "dozen", 1, 24_000, 6_000, 25),
    )
    private fun view(today: LocalToday = LocalToday(0, 0, 0, emptyMap()), unconfirmed: Int = 0, fork: Int = 0, fromFirst: Boolean = false, skuMissing: Boolean = false, label: String = "Super Star Low") = OutletCampaignView(
        1, label, "সুপার স্টার লো", "2026-11", "2026-11-17", "2026-11-18T01:00:00Z", fromFirst, CampaignFigure(1000, 300, 700), CampaignFigure(10, 4, 6), rows,
        listOf(OutletTargetTotal("cigarette", "packet", 1_100_000, 320_000, 20, 28), OutletTargetTotal("match", "dozen", 24_000, 6_000, 1, 25)), skuMissing, today, unconfirmed, fork,
    )
    private fun data(v: OutletCampaignView = view()) = OutletTargetData(listOf(v), false)

    private fun show(language: AppLanguage, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable (UiLocale) -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) { AronTheme(language) { content(loc) } } }
        compose.waitForIdle()
    }

    @Test fun thePopupShowsTheSixServerFiguresWithTheClosedDay() {
        show(AppLanguage.EN) { OutletTargetPopup("Ruku Store (20)", data(), it, {}) }
        compose.onNodeWithTag(OutletTargetTags.POPUP).assertIsDisplayed()
        compose.onNodeWithText("Super Star Low (November)").assertExists()
        compose.onNodeWithText("Server figures through 2026-11-17").assertExists()
        compose.onNodeWithTag(OutletTargetTags.figure(1, "std_t")).assertExists(); compose.onNodeWithText("1,000").assertExists(); compose.onNodeWithText("300").assertExists(); compose.onNodeWithText("700").assertExists()
        compose.onNodeWithText("Remaining Memo").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(OutletTargetTags.today(1)).fetchSemanticsNodes().size)
    }

    @Test fun thePhonesOwnLinesAreSeparateAndLabelled() {
        show(AppLanguage.EN) { OutletTargetPopup("X", data(view(today = LocalToday(40, 2, 40_000, mapOf(20 to 40L)), unconfirmed = 3, fork = 1)), it, {}) }
        compose.onNodeWithTag(OutletTargetTags.today(1)).assertExists()
        compose.onNodeWithText("Today on this phone: 40 STD, 2 memo (not in the figures above)").assertExists()
        compose.onNodeWithTag(OutletTargetTags.UNCONFIRMED).assertExists(); compose.onNodeWithTag(OutletTargetTags.FORK).assertExists()
        compose.onNodeWithText("1,000").assertExists() // the server figure is unchanged
    }

    @Test fun noCampaignIsTheEmptyStateAndACutDownloadSaysItMayBeMissing() {
        show(AppLanguage.EN) { OutletTargetPopup("X", null, it, {}) }
        compose.onNodeWithTag(OutletTargetTags.EMPTY).assertExists(); assertEquals(0, compose.onAllNodesWithTag(OutletTargetTags.EMPTY_CUT).fetchSemanticsNodes().size)
    }

    @Test fun aCutListSaysTheCampaignMayBeMissing() {
        show(AppLanguage.EN) { OutletTargetPopup("X", OutletTargetData(emptyList(), true), it, {}) }
        compose.onNodeWithTag(OutletTargetTags.EMPTY).assertExists(); compose.onNodeWithTag(OutletTargetTags.EMPTY_CUT).assertExists()
    }

    @Test fun okayAndDetailsAreButtonsAndDetailsIsHiddenWithNoCampaign() {
        var okay = 0; var details = 0
        show(AppLanguage.EN) { OutletTargetPopup("X", data(), it, { okay++ }, onDetails = { details++ }) }
        compose.onNodeWithTag(OutletTargetTags.DETAILS).performClick(); compose.onNodeWithTag(OutletTargetTags.OKAY).performClick()
        assertEquals(1, details); assertEquals(1, okay)
    }

    @Test fun offlineIsNamedOnThePopup() {
        show(AppLanguage.EN) { OutletTargetPopup("X", data(), it, {}, offline = true) }
        compose.onNodeWithTag(OutletTargetTags.OFFLINE).assertExists()
    }

    @Test fun thePageShowsRowsInPhoneUnitsWithPercentsAndCategoryTotals() {
        show(AppLanguage.EN) { OutletTargetScreen("Ruku Store (20)", "Route 7", data(), it) }
        compose.onNodeWithTag(OutletTargetTags.row(900)).assertExists()
        compose.onNodeWithTag(OutletTargetTags.percent(900)).assertExists(); compose.onNodeWithTag(OutletTargetTags.percent(900)).assert(androidx.compose.ui.test.hasText("24%"))
        compose.onNodeWithText("25").assertExists() // 500 sticks = 25 packets
        compose.onNodeWithTag(OutletTargetTags.total("cigarette")).assertExists(); compose.onNodeWithTag(OutletTargetTags.total("match")).assertExists()
        compose.onNodeWithText("Route 7").assertExists()
    }

    @Test fun thePageEmptyStateAndSkuMissingNote() {
        show(AppLanguage.EN) { OutletTargetScreen("X", null, null, it) }
        compose.onNodeWithTag(OutletTargetTags.EMPTY).assertExists()
    }

    @Test fun aMissingSkuIsNamedOnThePage() {
        show(AppLanguage.EN) { OutletTargetScreen("X", null, data(view(skuMissing = true)), it) }
        compose.onNodeWithTag(OutletTargetTags.SKU_MISSING).assertExists()
    }

    @Test fun banglaUsesBanglaDigitsAndTheBanglaCampaignNameOnThePage() {
        show(AppLanguage.BN) { OutletTargetScreen("X", null, data(), it) }
        compose.onNodeWithText("সুপার স্টার লো", substring = true).assertExists(); compose.onNodeWithText("নভেম্বর", substring = true).assertExists()
        compose.onNodeWithText("২০২৬-১১-১৭", substring = true).assertExists() // Bangla digits in the closed day
        compose.onNodeWithTag(OutletTargetTags.percent(900)).assert(androidx.compose.ui.test.hasText("২৪%"))
    }

    @Test fun thePopupTakesTheBanglaCampaignNameFromTheLocale() {
        show(AppLanguage.BN) { OutletTargetPopup("X", data(), it, {}) }
        compose.onNodeWithText("সুপার স্টার লো", substring = true).assertExists()
        compose.onNodeWithText("১,০০০").assertExists()
    }

    @Test fun aLongBanglaNameAtFontScaleTwoStillShowsEveryFigure() {
        show(AppLanguage.BN, fontScale = 2.0f) { OutletTargetScreen("আউটলেটের একটি খুব লম্বা নাম যা কয়েক লাইনে ভাঙবে (১২৩৪৫)", "রুট ৭ - খুব লম্বা রুটের নাম", data(view(label = "x")), it) }
        compose.onNodeWithTag(OutletTargetTags.row(900)).assertExists(); compose.onNodeWithTag(OutletTargetTags.total("match")).assertExists()
    }
}
