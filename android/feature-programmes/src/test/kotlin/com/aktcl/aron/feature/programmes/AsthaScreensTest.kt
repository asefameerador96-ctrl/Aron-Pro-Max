package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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

    private fun show(language: AppLanguage, data: AsthaData?, shops: List<ShopInfo>? = null, initialShop: Long? = null, currentMonth: String? = null, awaiting: Int = 0, geography: Geography? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { AsthaTargetScreen(data, loc, 2026, shops = shops, initialShop = initialShop, currentMonth = currentMonth, awaiting = awaiting, geography = geography) } } }
        compose.waitForIdle()
    }

    @Test fun theAwaitingConfirmationLineShowsOnlyWhenMemosWaitAndCountsThem() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), awaiting = 3)
        compose.onNodeWithTag(AsthaTags.AWAITING).assertIsDisplayed()
        compose.onNodeWithText("3 memos are awaiting confirmation; the figures update after the next sync.").assertExists()
    }

    @Test fun noAwaitingLineWhenNothingWaits() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products))
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.AWAITING).fetchSemanticsNodes().size)
    }

    @Test fun theShopCardShowsTheUsersWingDivisionTerritoryAndZoneOrADash() {
        val g = Geography(GeoNode(1, "W", "Dhaka Wing", null), null, null, GeoNode(4, "Z", "Zone 4", null))
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), shops, geography = g)
        compose.onNodeWithTag(AsthaTags.TAB_SHOP).performClick(); compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText("Dhaka Wing").fetchSemanticsNodes().size) // one per shop card
        assertEquals(2, compose.onAllNodesWithText("Zone 4").fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText("Territory").fetchSemanticsNodes().size) // the label stays, its value is a dash
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
        assertEquals(1, compose.onAllNodesWithText("This month is not downloaded to this phone yet. Sync to see it.").fetchSemanticsNodes().size)
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

    private val shops = listOf(
        ShopInfo(501, "Ajij Tea Store (3700634-01716184335-Pink City Road)", "3700634", "Md.Ajij", "01716184335", "Gold"),
        ShopInfo(502, "Rubel Tea Store (2681254-01334363419-Agora Road)", "2681254", "Abdul Mannan", "01334363419", null),
    )
    private val outletRows = """{"astha_targets":[
      {"route_id":7,"outlet_id":501,"brand_id":11,"month":"2026-11","std_target":"100","std_achieved":"10","memo_target":2,"memo_achieved":1,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"2240","std_achieved":"390","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""

    @Test fun shopTabListsCardsSearchesAndOpensTheOutletsOwnTable() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), shops)
        compose.onNodeWithTag(AsthaTags.TAB_SHOP).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.shop(501)).assertIsDisplayed()
        compose.onNodeWithTag(AsthaTags.shop(502)).assertExists() // below the fold of the small test screen
        compose.onNodeWithText("Gold").assertExists()
        compose.onNodeWithTag(AsthaTags.SEARCH).performTextInput("0133")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.shop(501)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(AsthaTags.shop(502)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.SHOP_TITLE).assertIsDisplayed()
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.EMPTY).assertIsDisplayed() // outlet 502 has no rows in a downloaded month: no data, never the route's figures
        compose.onNodeWithText("No data found").assertExists()
        compose.onNodeWithTag(AsthaTags.SHOP_BACK).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.shop(502)).assertIsDisplayed()
    }

    @Test fun anOutletsTableShowsItsOwnRowsNotTheRoutes() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), shops, initialShop = 501)
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("100").assertExists() // the outlet's STD target, not the route's 2,240
        assertEquals(0, compose.onAllNodesWithText("2,240").fetchSemanticsNodes().size)
    }

    @Test fun emptyShopListSaysSo() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), emptyList())
        compose.onNodeWithTag(AsthaTags.TAB_SHOP).performClick(); compose.waitForIdle()
        compose.onNodeWithText("No Astha shops are on this phone.").assertExists()
    }

    @Test fun theAsOfDateIsTheDhakaDateOfTheServerInstant() {
        val evening = programmes.replace("2026-11-17T10:00:00Z", "2026-11-17T20:00:00Z") // 02:00 on 18 November in Dhaka
        show(AppLanguage.EN, AsthaModel.parse(evening, products))
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-11")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Data as of 2026-11-18").assertExists()
    }

    @Test fun thePageOpensOnTheCurrentMonthWithDataAlreadyShown() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), currentMonth = "2026-11")
        compose.onNodeWithTag(AsthaTags.row(11)).assertExists() // no picking needed: year, quarter and month are already chosen
        compose.onNodeWithText("Marise").assertExists()
    }
}
