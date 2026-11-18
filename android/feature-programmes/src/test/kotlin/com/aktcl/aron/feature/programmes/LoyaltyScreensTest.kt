package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
class LoyaltyScreensTest {
    @get:Rule val compose = createComposeRule()

    private val json = """{"programmes":[{"programme_id":3,"kind":"diamond_league","name_en":"Diamond League","period_label":"2026-11"}],
      "loyalty":[
        {"outlet_id":10,"programme_id":3,"league_label":"Diamond League (November)","balance_points":110,"overdrawn":false,"expiring_points":110,"expiry_date":"2026-12-07","as_of_date":"2026-11-17"},
        {"outlet_id":11,"programme_id":3,"league_label":"Diamond League (November)","balance_points":-20,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-11-17"}],"gifts":[]}"""
    private val outlets = listOf(ShopOutlet(10, "Bipu Store (6909084)", "6909084", "A", null, null), ShopOutlet(11, "Milon Store (2663519)", "2663519", "B", null, null), ShopOutlet(12, "No Points Store (1)", "1", "C", null, null))

    private fun show(language: AppLanguage, data: LoyaltyData?, outlet: Long? = null, onRedeem: ((Long) -> Unit)? = null, local: List<LocalRedemption> = emptyList()) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { OutletPointsScreen(outlets, data, loc, onRedeem = onRedeem, initialOutlet = outlet, balanceOf = PointsBalance.function(local, "2026-04-17T01:00:00.000Z")) } } }
        compose.waitForIdle()
    }

    @Test fun noProgrammeAndNoOutletsAreHonestEmptyStates() {
        show(AppLanguage.EN, null)
        compose.onNodeWithText("No loyalty programme is active for your routes.").assertIsDisplayed()
    }

    @Test fun balanceExpiringAndExpiryShowForTheOutlet() {
        show(AppLanguage.EN, LoyaltyModel.parse(json), outlet = 10)
        compose.onNodeWithTag(LoyaltyTags.card(3)).assertIsDisplayed()
        compose.onNodeWithText("Diamond League (November)").assertExists()
        compose.onNodeWithText("2026-12-07").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(LoyaltyTags.overdrawn(3)).fetchSemanticsNodes().size)
    }

    @Test fun anOverdrawnBalanceIsShownPlainlyInBangla() {
        show(AppLanguage.BN, LoyaltyModel.parse(json), outlet = 11)
        compose.onNodeWithTag(LoyaltyTags.overdrawn(3)).assertIsDisplayed()
        compose.onNodeWithText("-২০ পয়েন্ট").assertExists()
    }

    @Test fun anOutletWithoutPointsIsNotOffered() {
        show(AppLanguage.EN, LoyaltyModel.parse(json))
        compose.onNodeWithTag(LoyaltyTags.OUTLET).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Bipu Store (6909084)").assertExists()
        compose.onNodeWithText("Milon Store (2663519)").assertExists()
        assertEquals(0, compose.onAllNodesWithText("No Points Store (1)").fetchSemanticsNodes().size)
    }

    @Test fun redeemButtonOnlyWhenTheScreenExists() {
        var opened: Long? = null
        show(AppLanguage.EN, LoyaltyModel.parse(json), outlet = 10, onRedeem = { opened = it })
        compose.onNodeWithTag(LoyaltyTags.REDEEM).performClick()
        assertEquals(10L, opened)
    }

    @Test fun menuHidesTilesThatHaveNoScreen() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        var pts = 0
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { LoyaltyMenuScreen({ pts++ }, null) } } }
        compose.onNodeWithTag(LoyaltyTags.MENU_POINTS).performClick()
        assertEquals(1, pts)
        assertEquals(0, compose.onAllNodesWithTag(LoyaltyTags.MENU_REDEEM).fetchSemanticsNodes().size)
    }

    @Test fun anOfflineRedemptionReducesTheBalanceAndRaisesTheOverdrawnLineOnThePointsPage() {
        // the bundle says 110 and not overdrawn; this phone redeemed 120 that the bundle cannot hold: the page shows -10 and the overdrawn line
        show(AppLanguage.EN, LoyaltyModel.parse(json), outlet = 10, local = listOf(LocalRedemption(10, 3, 120, "2026-04-17T05:00:00.000Z", "pending")))
        compose.onNodeWithText("-10 Pts").assertExists()
        compose.onNodeWithTag(LoyaltyTags.overdrawn(3)).assertIsDisplayed()
    }
}
