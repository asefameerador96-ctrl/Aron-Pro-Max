package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.ui.AppLocale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
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
        compose.onNodeWithText("Proceed").assertIsEnabled().performClick()
        assertEquals(true, reviewed)
    }

    @Test fun zeroSaleAsksFirstAndOnlyThenWrites() {
        var zero = 0
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, review, { 0 }, { _, _ -> }, {}, { zero++ }) } }
        // with no line entered the primary opens the zero-sale confirm instead of Review (screens-sr s7)
        compose.onNodeWithText("Proceed").assertIsEnabled().performClick()
        assertEquals(0, zero)
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, zero)
    }

    @Test @Config(sdk = [36], qualifiers = "w360dp-h1000dp") fun recentSkuIsListedFirstOnceAndNotAgainInItsCategory() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, review, { id -> d.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {}, recentSkuIds = setOf(100L)) } }
        // one row and one stepper per SKU: the recent group owns the row, the category group does not repeat it (screens-sr s7)
        compose.onNodeWithText("Sold here before").assertExists()
        compose.onAllNodesWithText("MaxR 10S").assertCountEquals(1)
        compose.onAllNodesWithText("Aster").assertCountEquals(1)
    }

    @Test fun banglaDigitsAndMoney() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 40, QtyUnit.STICK)
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.BN) { SaleEntryScreen(skus, review, { 40 }, { _, _ -> }, {}, {}) } } }
        // MoneyText joins the amount and the sign with a no-break space (tokens s7.1); the line total and the bar total both show it
        compose.onAllNodesWithText("৩২০.০০ ৳").assertCountEquals(2)
        compose.onNodeWithText("= ৪ প্যাকেট + ০").assertExists()
    }

    @Test fun reviewShowsNonZeroComponentsAndBlocksSaveOnProblems() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setSlide(d, 100, 10)
        compose.setContent { AronTheme(AppLanguage.EN) { ReviewScreen(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Slide").assertIsDisplayed()
        compose.onNodeWithText("Offer discount").assertDoesNotExist()
        compose.onNodeWithText("Print").assertIsEnabled()
    }

    @Test fun reviewDisablesSaveWhenPaidIsNotBelowTheTotal() {
        val d = SaleDraftOps.setPaid(SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK), 160_000)
        compose.setContent { AronTheme(AppLanguage.EN) { ReviewScreen(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Print").assertIsNotEnabled()
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
        compose.onNodeWithTag(CreditTags.YES).assertIsNotEnabled()
        // the bar says what to do while the field is empty; the field is found by its label (placeholder while empty)
        compose.onNodeWithText("Enter the amount collected").assertExists()
        compose.onNodeWithText("Amount collected").performTextInput("30.5")
        compose.onNodeWithText("Due: 49.50 ৳").assertExists()
        compose.onNodeWithTag(CreditTags.YES).assertIsEnabled().performClick()
        assertEquals(30_500L, applied)
    }

    @Test fun creditRefusesPaidEqualToTheNet() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 10, QtyUnit.STICK)
        compose.setContent { AronTheme(AppLanguage.EN) { CreditPrompt(SaleReviewCalculator.review(d, Fx.catalog), {}) } }
        compose.onNodeWithText("Amount collected").performTextInput("80")
        compose.onNodeWithTag(CreditTags.YES).assertIsNotEnabled()
        // a wrong value gets the inline error once, not the same sentence twice (the bar reason is gone)
        compose.onAllNodesWithText("The amount collected must be less than the total.").assertCountEquals(1)
    }

    @Test fun slideListsOnlyOfferedSkusAndShowsTheEightyTakaReward() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setSlide(d, 100, 10)
        compose.setContent { AronTheme(AppLanguage.EN) { SlideScreen(listOf(Fx.maxr, Fx.aster), SaleReviewCalculator.review(d, Fx.catalog), { 10 }, { _, _ -> }) } }
        compose.onNodeWithText("Aster").assertDoesNotExist()
        compose.onNodeWithText("1 reward packs = − 80.00 ৳").assertExists()
    }

    private fun qcDraft(vararg e: com.aktcl.aron.feature.sale.domain.QcEntry): com.aktcl.aron.feature.sale.domain.SaleDraft {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        e.forEach { d = SaleDraftOps.setQc(d, it) }
        return d
    }

    @Test fun theQcStepperStopsAtWhatIsLeftUnderTheCap() {
        val d = qcDraft(com.aktcl.aron.feature.sale.domain.QcEntry(100, "damaged_crushed", "MFC", 2)) // 16.00 of a 16.00 cap used
        compose.setContent {
            AronTheme(AppLanguage.EN) {
                QcScreen(listOf(Fx.maxr), SaleReviewCalculator.review(d, Fx.catalog), { _, c -> if (c == "damaged_crushed") 2L else 0L }, { _, _, _ -> }, false, {}, capMtkOf = { 16_000L })
            }
        }
        compose.onNodeWithText("Remaining").assertExists()
        // every More button is disabled: nothing is left under the cap (the six rows)
        compose.onAllNodesWithContentDescription("More").assertCountEquals(6)
        for (i in 0 until 6) compose.onAllNodesWithContentDescription("More")[i].assertIsNotEnabled()
    }

    @Test fun deleteClearsTheSkuAndIsHiddenWhenLocked() {
        var cleared = -1L
        val d = qcDraft(com.aktcl.aron.feature.sale.domain.QcEntry(100, "taste", "MKT", 1))
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(listOf(Fx.maxr), SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, {}, onClearSku = { cleared = it }) } }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertEquals(100L, cleared)
    }

    @Test fun lockedQcShowsNoDeleteAndNoEnabledStepper() {
        val d = qcDraft(com.aktcl.aron.feature.sale.domain.QcEntry(100, "taste", "MKT", 1))
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(listOf(Fx.maxr), SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, true, {}) } }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        for (i in 0 until 6) compose.onAllNodesWithContentDescription("More")[i].assertIsNotEnabled()
    }

    @Test fun qcShowsTheDeductionAndLocksWhenDone() {
        var d = SaleDraftOps.setQuantity(Fx.draft(), 103, 10, QtyUnit.PIECE)
        d = SaleDraftOps.setQc(d, com.aktcl.aron.feature.sale.domain.QcEntry(103, "damaged_crushed", com.aktcl.aron.feature.sale.domain.QcEntryModel.PRODUCTION, 2))
        var done = 0
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(listOf(Fx.aster), SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, { done++ }) } }
        compose.onNodeWithText("Settlement amount: − 25.00 ৳").assertExists()
        compose.onNodeWithText("Submit QC").performClick()
        assertEquals("QC is not submitted before the confirm", 0, done)
        compose.onNodeWithText("No").performClick()
        assertEquals(0, done)
        compose.onNodeWithText("Submit QC").performClick()
        compose.onNodeWithText("Yes").performClick()
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QcOnlyScreensTest {
    @get:Rule val compose = createComposeRule()
    private val skus = listOf(Fx.maxr, Fx.aster)

    @Test fun qcOnlyIsOfferedOnlyWithoutALineAndOnlyAsksNothing() {
        var qcOnly = 0
        val none = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, none, { 0 }, { _, _ -> }, {}, {}, onQcOnly = { qcOnly++ }) } }
        compose.onNodeWithTag(SaleTags.QC_ONLY).assertIsEnabled().performClick()
        assertEquals(1, qcOnly)
    }

    @Test fun qcOnlyIsHiddenOnceAThereIsASaleLine() {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(skus, review, { id -> d.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {}, onQcOnly = {}) } }
        compose.onNodeWithTag(SaleTags.QC_ONLY).assertDoesNotExist()
    }

    @Test fun qcOnlyScreenOffersEverySkuClosedAndOpensOneOnATap() {
        val d = SaleDraftOps.startQcOnly(Fx.draft())
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(skus, SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, {}, collapsible = true) } }
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(2)
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(0) // closed cards hold no steppers: the list stays light
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(6)
    }

    @Test fun aTypedQuantityIsAcceptedAndANumberAboveWhatIsLeftIsPulledDownAndFlagged() {
        val got = mutableListOf<Long>()
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK) // gross 160.00: room for 20 sticks of QC at 8.00
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(listOf(Fx.maxr), SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, q -> got += q }, false, {}) } }
        val field = compose.onAllNodesWithTag(QcTags.QTY_FIELD)[0]
        field.performTextInput("7")
        assertEquals(listOf(7L), got)
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[1].performTextInput("99")
        assertEquals("pulled down to the most that fits", 20L, got.last())
        compose.onNodeWithText("Most you can enter: 20").assertExists()
    }

    @Test fun summaryHasOneRowPerSkuWithMfcMktAndTotal() {
        var d = SaleDraftOps.startQcOnly(Fx.draft())
        d = SaleDraftOps.setQc(d, com.aktcl.aron.feature.sale.domain.QcEntry(100, "damaged_crushed", "MFC", 3))
        d = SaleDraftOps.setQc(d, com.aktcl.aron.feature.sale.domain.QcEntry(100, "taste", "MKT", 2))
        d = SaleDraftOps.setQc(d, com.aktcl.aron.feature.sale.domain.QcEntry(103, "taste", "MKT", 1))
        compose.setContent { AronTheme(AppLanguage.EN) { QcScreen(skus, SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, {}, collapsible = true) } }
        compose.onAllNodesWithTag(QcTags.SUMMARY_ROW).assertCountEquals(2)
        compose.onNodeWithText("MFC Fault").assertExists(); compose.onNodeWithText("MKT Fault").assertExists()
    }
}
