package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.memo.domain.DaySummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-PAR-AMO-16: the AMO "Total sales" block's deduction row holds offer + DRP + QC deductions, so it carries the dock's own words
 * ("Discount and others (-)"), and on screen gross minus that row is the grand total exactly, in integer milli-taka (a QC return of 12.50 taka
 * is never printed as a programme discount).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h1200dp")
class SummarySalesBlockQcTest {
    @get:Rule val compose = createComposeRule()

    private fun show(s: DaySummary) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN, dark = false, sunlight = false, amo = true) { SummaryScreen(s, { "SKU" }, {}, printWhenEmpty = true, salesBlock = true) } } }
    }

    // gross 324.00, a QC deduction of 12.50 and no programme discount: grand total 311.50
    private val qcOnly = DaySummary(emptyList(), emptyList(), 324_000, 12_500, 311_500, 3)

    @Test fun grossMinusTheShownDeductionIsTheGrandTotalExactly() {
        assertEquals(qcOnly.grandTotalMtk, qcOnly.grossMtk - qcOnly.discountAndOthersMtk)
        show(qcOnly)
        compose.onNodeWithText("324.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("(-) 12.50").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("311.50").performScrollTo().assertIsDisplayed()
    }

    @Test fun theDeductionRowIsNamedForWhatItHolds() {
        show(qcOnly)
        compose.onNodeWithText("Discount and others (-)").performScrollTo().assertIsDisplayed()
    }
}
