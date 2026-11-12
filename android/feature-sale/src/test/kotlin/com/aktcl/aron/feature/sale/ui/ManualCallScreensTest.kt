package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.OutletTarget
import com.aktcl.aron.feature.sale.domain.OutletTargetRow
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SkuStats
import com.aktcl.aron.feature.sale.domain.ZeroSaleReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SR manual s8-s10 screens (D-SR-MAN-S4-02/03/05/06): the quantity popup, the zero-sale reasons, the target popup, the QC pack equivalent; en and bn. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ManualCallScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    // ---- quantity popup ---------------------------------------------------------------------------------------------------------------

    private val cig = Fx.maxr.copy(entryUnitDefault = "pack", allowLoose = false) // a packet SKU: pack factor 10, the entry unit is the packet

    @Test fun quickButtonsAddAndSubtractInTheEntryUnitAndSaveReturnsTheTotal() {
        var saved: Long? = null
        show(AppLanguage.EN) { QuantityPopup(cig, 3, null, { saved = it }, {}) }
        compose.onNodeWithTag(QtyPopupTags.plus(20)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.plus(1)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.minus(5)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.SAVE).performScrollTo().performClick()
        assertEquals(19L, saved) // 3 + 20 + 1 - 5
    }

    @Test fun minusNeverGoesBelowZeroAndPlusStopsAtTheCeiling() {
        var saved: Long? = null
        show(AppLanguage.EN) { QuantityPopup(cig, 4, null, { saved = it }, {}) }
        compose.onNodeWithTag(QtyPopupTags.minus(100)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.SAVE).performScrollTo().performClick()
        assertEquals(0L, saved)
    }

    @Test fun plusStopsAtTheCeiling() {
        var big: Long? = null
        show(AppLanguage.EN) { QuantityPopup(cig, 999_990, null, { big = it }, {}) }
        compose.onNodeWithTag(QtyPopupTags.plus(100)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.SAVE).performScrollTo().performClick()
        assertEquals(MaxLineQty, big)
    }

    @Test fun closeDropsTheHalfTypedNumber() {
        var saved: Long? = null; var closed = 0
        show(AppLanguage.EN) { QuantityPopup(cig, 3, null, { saved = it }, { closed++ }) }
        compose.onNodeWithTag(QtyPopupTags.plus(50)).performScrollTo().performClick()
        compose.onNodeWithTag(QtyPopupTags.CLOSE).performClick()
        assertNull(saved); assertEquals(1, closed)
    }

    @Test fun unknownStatsReadNotAvailableAndKnownOnesShowWithTwoDecimalsForAds() {
        show(AppLanguage.EN) { QuantityPopup(cig, 0, null, {}, {}) }
        listOf(QtyPopupTags.PREV, QtyPopupTags.ADS, QtyPopupTags.SDLW).forEach { compose.onNodeWithTag(it).assertTextEquals("N/A") }
    }

    @Test fun statsInTheSkusUnitShowAndStatsInAnotherUnitAreHidden() {
        show(AppLanguage.EN) { QuantityPopup(cig, 0, SkuStats("pack", previousDay = 20, adsHundredths = 333, sdlw = 20), {}, {}) }
        compose.onNodeWithTag(QtyPopupTags.PREV).assertTextEquals("20")
        compose.onNodeWithTag(QtyPopupTags.ADS).assertTextEquals("3.33")
        compose.onNodeWithTag(QtyPopupTags.SDLW).assertTextEquals("20")
    }

    @Test @Config(sdk = [36], qualifiers = "bn-rBD") fun banglaLabelsAndDigits() {
        show(AppLanguage.BN) { QuantityPopup(cig, 0, SkuStats("pack", previousDay = 20, adsHundredths = 333, sdlw = null), {}, {}) }
        compose.onNodeWithText("আগের দিন").assertExists(); compose.onNodeWithText("ADS").assertExists(); compose.onNodeWithText("SDLW").assertExists()
        compose.onNodeWithText("সংরক্ষণ").assertExists()
        compose.onNodeWithTag(QtyPopupTags.PREV).assertTextEquals("২০")
        compose.onNodeWithTag(QtyPopupTags.ADS).assertTextEquals("৩.৩৩")
        compose.onNodeWithTag(QtyPopupTags.SDLW).assertTextEquals("N/A")
    }

    // ---- zero sale reasons ------------------------------------------------------------------------------------------------------------

    @Test fun zeroSaleYesOpensTheFourReasonsAndTheChoiceIsReported() {
        var plain = 0; var reason: ZeroSaleReason? = null
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        show(AppLanguage.EN) { SaleEntryScreen(listOf(Fx.maxr), review, { 0 }, { _, _ -> }, {}, { plain++ }, onZeroSaleReason = { reason = it }) }
        compose.onNodeWithText("Proceed").performClick()
        compose.onNodeWithText("Yes").performClick()
        compose.onNodeWithTag(ZeroReasonTags.DIALOG).assertExists()
        ZeroSaleReason.entries.forEach { compose.onNodeWithTag(ZeroReasonTags.row(it)).assertExists() }
        compose.onNodeWithText("Stock Available").assertExists(); compose.onNodeWithText("Outlet Closed during visit").assertExists()
        compose.onNodeWithText("Absence of owner").assertExists(); compose.onNodeWithText("Insufficient Cash").assertExists()
        compose.onNodeWithTag(ZeroReasonTags.OK).assertIsNotEnabled() // nothing is pre-selected: a reason is never guessed
        compose.onNodeWithTag(ZeroReasonTags.row(ZeroSaleReason.OUTLET_CLOSED)).performClick()
        compose.onNodeWithTag(ZeroReasonTags.OK).assertIsEnabled().performClick()
        assertEquals(ZeroSaleReason.OUTLET_CLOSED, reason); assertEquals(0, plain)
    }

    @Test fun withoutThePickerTheZeroSaleConfirmStaysOneStepForTheAmo() {
        var plain = 0
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        show(AppLanguage.EN) { SaleEntryScreen(listOf(Fx.maxr), review, { 0 }, { _, _ -> }, {}, { plain++ }) }
        compose.onNodeWithText("Proceed").performClick(); compose.onNodeWithText("Yes").performClick()
        assertEquals(1, plain); compose.onNodeWithTag(ZeroReasonTags.DIALOG).assertDoesNotExist()
    }

    @Test @Config(sdk = [36], qualifiers = "bn-rBD") fun banglaReasonLabels() {
        show(AppLanguage.BN) { ZeroSaleReasonDialog({}, {}) }
        listOf("স্টক আছে", "ভিজিটের সময় দোকান বন্ধ", "মালিক অনুপস্থিত", "পর্যাপ্ত নগদ নেই").forEach { compose.onNodeWithText(it).assertExists() }
    }

    // ---- today's target ---------------------------------------------------------------------------------------------------------------

    @Test fun targetPopupShowsTheSixFiguresAndOkayCloses() {
        var okay = 0
        val t = OutletTarget("Super Star Low", "November", 2240, 390, 18, 7)
        show(AppLanguage.EN) { OutletTargetPopup("Ruku Store (2681268-01778553683-Agora Goli)", t, { okay++ }) }
        compose.onNodeWithText("Super Star Low (November)").assertExists()
        listOf("Target STD" to "2,240", "Achievement STD" to "390", "Remaining STD" to "1,850", "Target Memo" to "18", "Achievement Memo" to "7", "Remaining Memo" to "11").forEach { (l, v) ->
            compose.onNodeWithText(l).assertExists(); compose.onNodeWithText(v).assertExists()
        }
        compose.onNodeWithTag(TargetTags.OKAY).performClick(); assertEquals(1, okay)
    }

    @Test fun targetPopupWithNoDataSaysSoInBothLanguages() {
        show(AppLanguage.BN) { OutletTargetPopup("X", null, {}) }
        compose.onNodeWithTag(TargetTags.EMPTY).assertExists()
    }

    @Test fun targetPageTotalsAreIntegerPercentOfTheirRows() {
        val t = OutletTarget(null, "November", 100, 40, 10, 4, listOf(OutletTargetRow("AVON-20S", "cigarette", 100, 40), OutletTargetRow("ASTER", "lighter", 3, 1)))
        show(AppLanguage.EN) { OutletTargetPage("Ruku Store", "Route: R", "Cluster: C", t) }
        compose.onNodeWithText("AVON-20S").assertExists(); compose.onAllNodesWithText("40").assertCountEquals(4)
    }

    // ---- QC pack equivalent -----------------------------------------------------------------------------------------------------------

    @Test fun qcPackEquivalentIsIntegerAndOnlyForStickSkus() {
        show(AppLanguage.EN) {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(qcPackEquivalent(cig, 25) ?: "none", androidx.compose.ui.Modifier.testTag("a"))
                androidx.compose.material3.Text(qcPackEquivalent(cig, 20) ?: "none", androidx.compose.ui.Modifier.testTag("b"))
                androidx.compose.material3.Text(qcPackEquivalent(cig, 4) ?: "none", androidx.compose.ui.Modifier.testTag("c"))
                androidx.compose.material3.Text(qcPackEquivalent(Fx.aster, 9) ?: "none", androidx.compose.ui.Modifier.testTag("d"))
            }
        }
        compose.onNodeWithTag("a").assertTextEquals("= 2 packets + 5 sticks")
        compose.onNodeWithTag("b").assertTextEquals("= 2 packets")
        compose.onNodeWithTag("c").assertTextEquals("none") // under one packet there is nothing to convert
        compose.onNodeWithTag("d").assertTextEquals("none") // pieces are not packed
    }
}
