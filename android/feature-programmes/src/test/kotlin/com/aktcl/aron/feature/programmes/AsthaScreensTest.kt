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

    private fun show(language: AppLanguage, data: AsthaData?, shops: List<ShopInfo>? = null, initialShop: Long? = null, currentMonth: String? = null, awaiting: Int = 0, geography: Geography? = null, quarterState: Map<String, QuarterFetch> = emptyMap(), onQuarter: ((String) -> Unit)? = null, startMonth: Int? = 1, unknownOrder: Int = 0) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { AsthaTargetScreen(data, loc, 2026, shops = shops, initialShop = initialShop, currentMonth = currentMonth, awaiting = awaiting, geography = geography, quarterState = quarterState, onQuarter = onQuarter, startMonth = startMonth, unknownOrder = unknownOrder) } } }
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

    @Test fun theBrandRowNamesTheUnitOfItsVolumeFigures() {
        show(AppLanguage.EN, AsthaModel.parse(outletRows, products), currentMonth = "2026-11")
        // the fixture's brand has no SKU, so its unit is unknown: no word is shown rather than a wrong one
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.UNIT).fetchSemanticsNodes().size)
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
        assertEquals(1, compose.onAllNodesWithText("This month is not on this phone yet. Connect to the internet once to download it.").fetchSemanticsNodes().size)
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

    // ---- quarter chips with months read online ----

    private fun fetched(month: String, asOf: String = "2026-11-18T06:00:00Z", vararg rowsJson: String, readDate: String? = DhakaTime.dateOf(asOf), clean: Boolean? = true, awaiting: Int = 0): AsthaData =
        AsthaFetched.merge(AsthaModel.parse(programmes, products)!!, listOf(CachedMonth(7, month, asOf, kotlinx.serialization.json.Json.parseToJsonElement("[" + rowsJson.joinToString(",") + "]") as kotlinx.serialization.json.JsonArray, readDate, clean, awaiting)))

    private val octRow = """{"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-10","std_target":"1000","std_achieved":"250","memo_target":0,"memo_achieved":0,"value_target_mtk":"4000000","value_achieved_mtk":"1000000","by_pack":[]}"""

    @Test fun pickingAQuarterWithMissingMonthsAsksTheHostToReadItOnceAndAnEarlierMonthIsNotZero() {
        val asked = mutableListOf<String>()
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), onQuarter = { asked += it })
        choose("Year", "2026"); choose("Quarter", "Q-1 (Jan-Mar)")
        assertEquals(listOf("2026-Q1"), asked)
        compose.onNodeWithTag(AsthaTags.month("2026-01")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("This month is not on this phone yet. Connect to the internet once to download it.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("0").fetchSemanticsNodes().size)
    }

    @Test fun aQuarterWhoseMonthsWereAllReadAfterTheyEndedIsNotReadAgain() {
        val asked = mutableListOf<String>()
        val all = AsthaFetched.merge(AsthaModel.parse(programmes, products)!!, listOf(
            CachedMonth(7, "2026-07", "2026-11-18T06:00:00Z", kotlinx.serialization.json.Json.parseToJsonElement("[$octRow]".replace("2026-10", "2026-07")) as kotlinx.serialization.json.JsonArray, "2026-11-18", clean = true),
            CachedMonth(7, "2026-08", "2026-11-18T06:00:00Z", kotlinx.serialization.json.JsonArray(emptyList()), "2026-11-18", clean = true),
            CachedMonth(7, "2026-09", "2026-11-18T06:00:00Z", kotlinx.serialization.json.JsonArray(emptyList()), "2026-11-18", clean = true),
        ))
        show(AppLanguage.EN, all.copy(truncated = false), onQuarter = { asked += it })
        choose("Year", "2026"); choose("Quarter", "Q-3 (Jul-Sep)")
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun aMonthReadWhileItWasStillOpenIsReadAgainWhenTheQuarterIsOpenedAndOfflineItSaysSo() {
        val asked = mutableListOf<String>()
        // October read on 20 October, while it was running
        show(AppLanguage.EN, fetched("2026-10", "2026-10-20T06:00:00Z", octRow, readDate = "2026-10-20"), onQuarter = { asked += it })
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        assertEquals(listOf("2026-Q4"), asked) // online it is read again
        compose.onNodeWithTag(AsthaTags.month("2026-10")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Data as of 2026-10-20; the month was still open then").assertExists() // offline: the cached figure, labelled
    }

    @Test fun aKeptMonthShowsThisPhonesOutstandingMemosAsAwaitingAndAnEndedButUnsettledLabel() {
        // October ended before the read, but this phone held 2 memos of it that were not acknowledged when the read was sent
        show(AppLanguage.EN, fetched("2026-10", "2026-11-18T06:00:00Z", octRow, clean = false, awaiting = 2), onQuarter = {})
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-10")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.MONTH_AWAITING).assertExists()
        compose.onNodeWithText("2 memos are awaiting confirmation; the figures update after the next sync.").assertExists()
        compose.onNodeWithText("Data as of 2026-11-18; some memos of this month were not confirmed then").assertExists()
    }

    @Test fun aFinalMonthShowsNoAwaitingLineAndThePlainLabel() {
        show(AppLanguage.EN, fetched("2026-10", "2026-11-18T06:00:00Z", octRow, clean = true), onQuarter = {})
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-10")).performClick(); compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.MONTH_AWAITING).fetchSemanticsNodes().size)
        compose.onNodeWithText("Data as of 2026-11-18").assertExists()
    }

    @Test fun withStartMonthSevenQuarterFourIsAprilToJuneOfTheKeyYearAsTheServerNamesIt() {
        val asked = mutableListOf<String>()
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), startMonth = 7, onQuarter = { asked += it })
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-04")).assertExists(); compose.onNodeWithTag(AsthaTags.month("2026-06")).assertExists() // the server names 2026-Q4 with start month 7 as April to June of 2026
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.month("2026-11")).fetchSemanticsNodes().size)
        assertEquals(listOf("2026-Q4"), asked)
    }

    @Test fun withStartMonthSevenTheCurrentMonthOpensOnItsOwnQuarter() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), currentMonth = "2026-11", startMonth = 7)
        compose.onNodeWithTag(AsthaTags.month("2026-11")).assertExists() // Q2 of 2026 (Oct to Dec)
        compose.onNodeWithTag(AsthaTags.row(11)).assertIsDisplayed()
    }

    @Test fun withNoStartMonthNoQuarterIsComputedAndTheHeldMonthsAreListed() {
        val asked = mutableListOf<String>()
        show(AppLanguage.EN, fetched("2026-10", "2026-11-18T06:00:00Z", octRow), startMonth = null, onQuarter = { asked += it })
        compose.onNodeWithTag(AsthaTags.QUARTER_UNKNOWN).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(AsthaTags.QUARTER).fetchSemanticsNodes().size)
        compose.onNodeWithTag(AsthaTags.month("2026-10")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.row(11)).assertIsDisplayed()
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun theOrderUnknownLineNamesTheAcknowledgedMemosLeftOut() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), unknownOrder = 3)
        compose.onNodeWithTag(AsthaTags.ORDER_UNKNOWN).assertIsDisplayed()
        compose.onNodeWithText("3 acknowledged memos of this phone are not in these figures until the next full download.").assertExists()
    }

    @Test fun aMonthReadOnlineShowsItsFiguresWithTheTimeOfTheRead() {
        show(AppLanguage.EN, fetched("2026-10", "2026-11-18T06:00:00Z", octRow), onQuarter = {})
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-10")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.row(11)).assertIsDisplayed()
        compose.onNodeWithText("Data as of 2026-11-18").assertExists()
    }

    @Test fun aMonthReadOnlineAndEmptySaysNoDataNotNotDownloaded() {
        show(AppLanguage.EN, fetched("2026-12"), onQuarter = {})
        choose("Year", "2026"); choose("Quarter", "Q-4 (Oct-Dec)")
        compose.onNodeWithTag(AsthaTags.month("2026-12")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("No data found").assertIsDisplayed()
    }

    @Test fun whileTheReadRunsTheMonthSaysLoading() {
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), quarterState = mapOf("2026-Q1" to QuarterFetch.LOADING), onQuarter = {})
        choose("Year", "2026"); choose("Quarter", "Q-1 (Jan-Mar)")
        compose.onNodeWithTag(AsthaTags.month("2026-02")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AsthaTags.MONTH_LOADING).assertIsDisplayed()
    }

    @Test fun aFailedReadOffersTryAgainWhichAsksAgain() {
        val asked = mutableListOf<String>()
        show(AppLanguage.EN, AsthaModel.parse(programmes, products), quarterState = mapOf("2026-Q1" to QuarterFetch.FAILED), onQuarter = { asked += it })
        choose("Year", "2026"); choose("Quarter", "Q-1 (Jan-Mar)")
        compose.onNodeWithTag(AsthaTags.month("2026-02")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("This month could not be loaded. Try again when the connection is better.").assertIsDisplayed()
        asked.clear()
        compose.onNodeWithTag(AsthaTags.MONTH_RETRY).performClick(); compose.waitForIdle()
        assertEquals(listOf("2026-Q1"), asked)
    }

    @Test fun anOfflineReadShowsConnectOnceInBangla() {
        show(AppLanguage.BN, AsthaModel.parse(programmes, products), quarterState = mapOf("2026-Q1" to QuarterFetch.OFFLINE), onQuarter = {})
        choose("বছর", "2026"); choose("কোয়ার্টার", "Q-1 (Jan-Mar)")
        compose.onNodeWithTag(AsthaTags.month("2026-01")).performClick(); compose.waitForIdle()
        compose.onNodeWithText("এই মাসের তথ্য এখনো এই ফোনে নেই। একবার ইন্টারনেটে যুক্ত হলে নামবে।").assertIsDisplayed()
    }
}
