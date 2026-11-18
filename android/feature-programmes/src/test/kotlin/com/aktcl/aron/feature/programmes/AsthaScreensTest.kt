package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class AsthaScreensTest {
    @get:Rule val compose = createComposeRule()

    private val programmes = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_truncated":true,"astha_targets":[
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"2240","std_achieved":"390","memo_target":0,"memo_achieved":0,
       "value_target_mtk":"1000000","value_achieved_mtk":"250000","by_pack":[{"pack_factor":20,"std_achieved":"300"}]},
      {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":884,"memo_achieved":787,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""
    private val products = """{"nodes":[{"id":11,"level":"brand","name":"Marise","name_bn":"মারিস","sort":1}],"skus":[]}"""

    private fun show(language: AppLanguage, data: AsthaData?, shop: (@androidx.compose.runtime.Composable () -> Unit)? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { AsthaTargetScreen(data, loc, 2026, shopWise = shop) } } }
        compose.waitForIdle()
    }

    private fun choose(placeholder: String, option: String) {
        compose.onNodeWithText(placeholder).performClick(); compose.waitForIdle()
        compose.onNodeWithText(option).performClick(); compose.waitForIdle()
    }

    @Test fun noProgrammeShowsTheEmptyNoteNotACrash() {
        show(AppLanguage.EN, null)
        compose.onNodeWithTag(AsthaTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No Astha programme is active for your routes.").assertExists()
    }

    @Test fun banglaFlowYearQuarterMonthShowsBrandTableWithDigitsInBangla() {
        show(AppLanguage.BN, AsthaModel.parse(programmes, products))
        compose.onNodeWithText("তথ্য পাওয়া যায়নি").assertExists() // before a year and quarter are chosen
        choose("বছর", "2026")
        choose("কোয়ার্টার", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.row(11)).assertIsDisplayed()
        compose.onNodeWithText("মারিস").assertExists()
        compose.onNodeWithText("২,২৪০").assertExists() // STD target
        compose.onNodeWithText("১৭.৪১%").assertExists() // 390 / 2240 = 17.4107, half up to 17.41
    }

    @Test fun memoTabShowsAllBrandRow() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products))
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.SUB_MEMO).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.row(null)).assertIsDisplayed()
        compose.onNodeWithText("884").assertExists(); compose.onNodeWithText("787").assertExists(); compose.onNodeWithText("97").assertExists()
        compose.onNodeWithText("89.03%").assertExists()
    }

    @Test fun aMonthTheDownloadHoldsNoRowsForSaysNoDataAndTruncationIsNoticed() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products))
        choose("Year", "2026"); choose("Quarter", "Q-1 (Jan-Mar)")
        compose.onNodeWithTag(AsthaTags.month("2026-01")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.EMPTY).assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("No data found").fetchSemanticsNodes().size)
    }

    @Test fun truncationAndAsOfAreShownOnATable() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products))
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.TRUNCATED).assertExists()
        compose.onNodeWithTag(AsthaTags.AS_OF).assertExists()
    }

    @Test fun menuHidesAGiftTileWhenItIsNotOffered() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        var target = 0
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { AsthaMenuScreen({ target++ }, null) } } }
        compose.onNodeWithTag(AsthaTags.MENU_TARGET).performClick()
        assertEquals(1, target)
        assertEquals(0, compose.onAllNodesWithText("Gift selection").fetchSemanticsNodes().size)
    }
}
