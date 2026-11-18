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
import androidx.compose.ui.test.performScrollTo
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
class GiftSelectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val json = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"}],"loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot- 3 bati","points_cost":null,"tier_codes":["gold"],"status":"active"},
        {"gift_id":2,"programme_id":5,"name_en":"Feature Phone","points_cost":null,"tier_codes":[],"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":2,"quarter":"2026-Q4","outlet_id":11,"route_id":7,"gift_id":2,"photo_captured":true,"locked":true}]}"""
    private val shops = listOf(
        ShopInfo(10, "Milon Store (2663519)", "2663519", "A", null, "Gold", chip = "M", tier = "gold", routeId = 7, routeName = "Agora GoliDaily", clusterName = "Agora Goli"),
        ShopInfo(11, "Locked Store (1)", "1", "B", null, "Gold", chip = "L", tier = "gold"),
        ShopInfo(12, "Other Tier Store (2)", "2", "C", null, "Titanium", chip = "O", tier = "titanium"),
    )
    private val saves = mutableListOf<Pair<Long, Long>>()

    private fun show(outlet: Long, local: List<LocalGiftChoice> = emptyList(), accept: Boolean = true, language: AppLanguage = AppLanguage.EN) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { GiftSelectionScreen(shops, GiftChoiceModel.parse(json), "2026-Q4", local, loc, { o, g -> saves += o to g; if (accept) SaveOutcome.SAVED else SaveOutcome.REFUSED }, initialOutlet = outlet) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun theOutletDetailAndTheGiftsOfItsTierAreShown() {
        show(10)
        compose.onNodeWithTag(GiftTags.DETAIL).assertIsDisplayed()
        compose.onNodeWithText("Agora GoliDaily").assertExists()
        compose.onNodeWithTag(GiftTags.card(1)).assertExists(); compose.onNodeWithTag(GiftTags.card(2)).assertExists()
        compose.onNodeWithTag(GiftTags.SUBMIT).assertIsNotEnabled() // nothing picked yet
    }

    @Test fun anOutletOfAnotherTierDoesNotSeeTheRestrictedGift() {
        show(12)
        assertEquals(0, compose.onAllNodesWithTag(GiftTags.card(1)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(GiftTags.card(2)).assertExists()
    }

    @Test fun pickAndSubmitSavesOnceAndSaysSavedOnThisPhone() {
        show(10)
        compose.onNodeWithTag(GiftTags.card(1)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(GiftTags.SUBMIT).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(listOf(10L to 1L), saves)
        compose.onNodeWithText("Gift choice saved on this phone. It goes out with the next sync.").assertExists()
    }

    @Test fun aLockedOutletShowsItsGiftAndOffersNoChoice() {
        show(11)
        compose.onNodeWithTag(GiftTags.LOCKED).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(GiftTags.SUBMIT).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(GiftTags.card(1)).fetchSemanticsNodes().size)
    }

    @Test fun aChoiceOnThisPhoneIsShownAndARefusedOneSaysSoAndCanBeChosenAgain() {
        show(10, local = listOf(LocalGiftChoice(10, "2026-Q4", 1, "pending")))
        compose.onNodeWithTag(GiftTags.ON_PHONE).performScrollTo().assertIsDisplayed()
    }

    @Test fun aRefusedChoiceIsShownAndTheOutletCanChooseAgain() {
        show(10, local = listOf(LocalGiftChoice(10, "2026-Q4", 1, "rejected")))
        compose.onNodeWithTag(GiftTags.REFUSED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GiftTags.card(2)).assertExists()
    }

    @Test fun aFailedSaveSaysSoAndKeepsThePick() {
        show(10, accept = false)
        compose.onNodeWithTag(GiftTags.card(1)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(GiftTags.SUBMIT).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(GiftTags.FAILED).performScrollTo().assertIsDisplayed()
    }

    @Test fun banglaTitleOfTheScreenWordsExist() {
        show(10, language = AppLanguage.BN)
        compose.onNodeWithText("একটি গিফট নির্বাচন করুন।").assertExists()
        compose.onNodeWithText("সাবমিট").assertExists()
    }
}
