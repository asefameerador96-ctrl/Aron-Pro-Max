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
