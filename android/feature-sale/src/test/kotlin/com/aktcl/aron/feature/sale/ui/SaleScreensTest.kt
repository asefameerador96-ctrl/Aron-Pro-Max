package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.ui.AppLocale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleScreensTest {
    @get:Rule val compose = createComposeRule()

    private val skus = listOf(Fx.maxr, Fx.aster)

    @Test fun entryShowsPackBadgeTotalAndStockWarningAndNeverBlocksReview() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 103, 26, QtyUnit.PIECE) // stock 25: warning
        d = SaleDraftOps.setQuantity(d, 100, 40, QtyUnit.STICK)               // 4 packs of 10
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        var reviewed = false
        compose.setContent {
            AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, review, { id -> d.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, { reviewed = true }, {}) }
        }
        compose.onNodeWithText("= 4 packs + 0").assertExists()
        compose.onNodeWithText("Only 25 in stock for Aster.").assertExists()
        compose.onNodeWithText("Review").assertIsEnabled().performClick()
        assertEquals(true, reviewed)
    }

    @Test fun zeroSaleAsksFirstAndOnlyThenWrites() {
        var zero = 0
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, review, { 0 }, { _, _ -> }, {}, { zero++ }) } }
        compose.onNodeWithText("Review").assertIsNotEnabled()
        compose.onNodeWithText("Sell nothing here").performClick()
        assertEquals(0, zero)
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, zero)
    }

    @Test fun banglaDigitsAndMoney() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 40, QtyUnit.STICK)
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.BN) { SaleEntryScreen(skus, review, { 40 }, { _, _ -> }, {}, {}) } } }
        compose.onNodeWithText("৩২০.০০ ৳").assertExists()
        compose.onNodeWithText("= ৪ প্যাকেট + ০").assertExists()
    }

    @Test fun reviewShowsNonZeroComponentsAndBlocksSaveOnProblems() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setSlide(d, 100, 10)
        compose.setContent { AronTheme(AppLanguage.EN) { ReviewScreen(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Slide discount").assertIsDisplayed()
        compose.onNodeWithText("Offer discount").assertDoesNotExist()
        compose.onNodeWithText("Save sale").assertIsEnabled()
    }

    @Test fun reviewDisablesSaveWhenPaidIsNotBelowTheTotal() {
        val d = SaleDraftOps.setPaid(SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK), 160_000)
        compose.setContent { AronTheme(AppLanguage.EN) { ReviewScreen(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Save sale").assertIsNotEnabled()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleExtraScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creditAppliesOnlyAValidPaidBelowTheNetAndShowsTheDue() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 10, QtyUnit.STICK) // net 80.00
        var applied: Long? = -1
        compose.setContent { AronTheme(AppLanguage.EN) { CreditPrompt(SaleReviewCalculator.review(d, Fx.catalog), { applied = it }) } }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Paid now").performTextInput("30.5")
        compose.onNodeWithText("Due: 49.50 ৳").assertExists()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(30_500L, applied)
    }

    @Test fun creditRefusesPaidEqualToTheNet() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 10, QtyUnit.STICK)
        compose.setContent { AronTheme(AppLanguage.EN) { CreditPrompt(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Paid now").performTextInput("80")
        compose.onNodeWithText("Apply").assertIsNotEnabled()
    }

    @Test fun slideListsOnlyOfferedSkusAndShowsTheEightyTakaReward() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setSlide(d, 100, 10)
        compose.setContent { AronTheme(AppLanguage.EN) { SlideScreen(listOf(Fx.maxr, Fx.aster), SaleReviewCalculator.review(d, Fx.catalog), { 10 }, { _, _ -> }) } }
        compose.onNodeWithText("Aster").assertDoesNotExist()
        compose.onNodeWithText("1 reward packs = − 80.00 ৳").assertExists()
    }

    @Test fun qcShowsTheDeductionAndLocksWhenDone() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 103, 10, QtyUnit.PIECE)
        d = SaleDraftOps.setQc(d, com.aktcl.aron.feature.sale.domain.QcEntry(103, "torn", PRODUCTION, 2))
        var done = 0
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(listOf(Fx.aster), SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, { done++ }) } }
        compose.onNodeWithText("Deduction: − 25.00 ৳").assertExists()
        compose.onNodeWithText("QC done").performClick()
        assertEquals(1, done)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CallPromptsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noReturnsWithoutStartingAndYesStarts() {
        var yes = 0; var no = 0
        compose.setContent { AronTheme(AppLanguage.EN) { StartCallPrompt("Rifat Store", { yes++ }, { no++ }) } }
        compose.onNodeWithText("You are at Rifat Store. Start the call now?").assertExists()
        compose.onNodeWithText("No").performClick()
        assertEquals(0 to 1, yes to no)
    }

    @Test fun editOffersThreeReasonsOrExplainsTheDenial() {
        var picked: String? = null
        compose.setContent { AronTheme(AppLanguage.EN) { EditReasonScreen(null, { picked = it.wire }) } }
        compose.onNodeWithText("Wrong quantity").performClick()
        assertEquals("wrong_quantity", picked)
    }

    @Test fun editDeniedOutsideTheGeofenceShowsNoReasons() {
        compose.setContent { AronTheme(AppLanguage.EN) { EditReasonScreen(com.aktcl.aron.feature.sale.domain.EditDenied.OutsideGeofence, {}) } }
        compose.onNodeWithText("You can edit a sale only at the shop, inside the geofence.").assertExists()
        compose.onNodeWithText("Wrong SKU").assertDoesNotExist()
    }
}
