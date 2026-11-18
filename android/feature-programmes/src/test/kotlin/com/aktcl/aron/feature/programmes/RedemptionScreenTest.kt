package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RedemptionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val json = """{"programmes":[{"programme_id":3,"kind":"diamond_league","name_en":"Diamond League"}],
      "loyalty":[{"outlet_id":10,"programme_id":3,"league_label":"Diamond League (April)","balance_points":220,"overdrawn":false,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-04-17"}],
      "gifts":[{"gift_id":1,"programme_id":3,"name_en":"Chair","name_bn":"চেয়ার।","points_cost":200,"tier_codes":[],"status":"active"},
               {"gift_id":2,"programme_id":3,"name_en":"Fan","points_cost":400,"tier_codes":[],"status":"active"},
               {"gift_id":3,"programme_id":3,"name_en":"Phone","points_cost":null,"tier_codes":[],"status":"active"},
               {"gift_id":4,"programme_id":3,"name_en":"Retired","points_cost":50,"tier_codes":[],"status":"inactive"}]}"""
    private val outlets = listOf(ShopOutlet(10, "Bipu Store (6909084)", "6909084", "A", null, null, chip = "B", routeId = 7))
    private val confirmed = mutableListOf<Pair<Basket, Int>>()

    private fun show(language: AppLanguage = AppLanguage.EN, localRedeemed: Long = 0L, accept: Boolean = true) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) {
                    RedemptionScreen(outlets, LoyaltyModel.parse(json), loc, { _, _, bundle -> bundle - localRedeemed }, { b, _ -> confirmed += b to b.qty.size; accept }, initialOutlet = 10)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun onlyRedeemableActiveGiftsAreOfferedAndTheBannerShowsTheRemaining() {
        show()
        compose.onNodeWithTag(RedemptionTags.plus(1)).assertExists(); compose.onNodeWithTag(RedemptionTags.plus(2)).assertExists()
        assertEquals(0, compose.onAllNodesWithTag(RedemptionTags.plus(3)).fetchSemanticsNodes().size) // Astha gift: no cost
        assertEquals(0, compose.onAllNodesWithTag(RedemptionTags.plus(4)).fetchSemanticsNodes().size) // inactive
        compose.onNodeWithText("220 Pts Remaining").assertExists()
    }

    @Test fun steppingChangesTheRemainingAndConfirmNeedsAnItem() {
        show()
        compose.onNodeWithTag(RedemptionTags.CONFIRM).assertIsNotEnabled()
        compose.onNodeWithTag(RedemptionTags.plus(1)).performClick(); compose.waitForIdle()
        compose.onNodeWithText("20 Pts Remaining").assertExists()
        compose.onNodeWithTag(RedemptionTags.minus(1)).performClick(); compose.waitForIdle()
        compose.onNodeWithText("220 Pts Remaining").assertExists()
    }

    @Test fun anOverdrawIsShownPlainlyAndNotBlocked() {
        show()
        compose.onNodeWithTag(RedemptionTags.plus(2)).performClick(); compose.waitForIdle() // 400 against 220
        compose.onNodeWithTag(RedemptionTags.OVERDRAWN).assertIsDisplayed()
        compose.onNodeWithText("-180 Pts Remaining").assertExists()
        compose.onNodeWithTag(RedemptionTags.CONFIRM).performClick(); compose.waitForIdle() // still confirmable: the server decides
        compose.onNodeWithTag(RedemptionTags.DIALOG_OK).assertIsDisplayed()
    }

    @Test fun confirmSavesOnceAndTheSuccessSaysSavedOnThisPhone() {
        show()
        compose.onNodeWithTag(RedemptionTags.plus(1)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.CONFIRM).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.DIALOG_OK).performClick(); compose.waitForIdle()
        assertEquals(1, confirmed.size); assertEquals(1, confirmed[0].second)
        compose.onNodeWithText("Redemption saved on this phone. It goes out with the next sync.").assertExists()
        compose.onNodeWithTag(RedemptionTags.SUCCESS_OK).performClick(); compose.waitForIdle()
        compose.onNodeWithText("220 Pts Remaining").assertExists() // basket emptied
    }

    @Test fun cancelInTheDialogSavesNothing() {
        show()
        compose.onNodeWithTag(RedemptionTags.plus(1)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.CONFIRM).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.DIALOG_CANCEL).performClick(); compose.waitForIdle()
        assertTrue(confirmed.isEmpty())
        compose.onNodeWithText("20 Pts Remaining").assertExists() // the basket stays
    }

    @Test fun aFailedSaveSaysNothingWasDeductedAndKeepsTheBasket() {
        show(accept = false)
        compose.onNodeWithTag(RedemptionTags.plus(1)).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.CONFIRM).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.DIALOG_OK).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RedemptionTags.FAILED).assertIsDisplayed()
        compose.onNodeWithText("20 Pts Remaining").assertExists()
    }

    @Test fun whatThisPhoneRedeemedAlreadyIsTakenOffTheBalance() {
        show(localRedeemed = 120L)
        compose.onNodeWithText("100 Pts Remaining").assertExists()
    }

    @Test fun banglaDigitsAndWords() {
        show(AppLanguage.BN)
        compose.onNodeWithText("২২০ পয়েন্ট বাকি").assertExists()
        compose.onNodeWithText("চেয়ার।").assertExists()
    }
}
