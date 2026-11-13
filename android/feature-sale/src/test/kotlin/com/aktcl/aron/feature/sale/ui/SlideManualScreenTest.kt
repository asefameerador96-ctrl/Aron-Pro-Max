package com.aktcl.aron.feature.sale.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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

/** P-SR2 (manuals 33, 34): the vendor's Slide page, the offer pop-up and the quick-add dialog over the same slide counts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h900dp")
class SlideManualScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(writes: MutableList<Pair<Long, Long>>, submitted: () -> Unit = {}) {
        val d = SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK)
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        compose.setContent {
            AronTheme(AppLanguage.EN) {
                SlideManualScreen(listOf(Fx.maxr, Fx.aster), review, { 10L }, { id, n -> writes += id to n }, submitted, listOf("Savar Metro (1618409)", "Route: R1"))
            }
        }
        compose.waitForIdle()
    }

    @Test fun onlyOfferedSkusGetARowAndARibbon() {
        show(mutableListOf())
        compose.onAllNodesWithTag(SlideTags.ROW).assertCountEquals(1) // Aster has no offer
        compose.onAllNodesWithTag(SlideTags.RIBBON).assertCountEquals(1)
        compose.onNodeWithText("Savar Metro (1618409)").assertExists()
    }

    @Test fun ribbonOpensThePopupWithTheRuleAndCloseDismissesIt() {
        show(mutableListOf())
        compose.onNodeWithTag(SlideTags.RIBBON).performClick()
        compose.onNodeWithText("Discount").assertExists()
        compose.onNodeWithText("Close", substring = false).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Discount").assertDoesNotExist()
    }

    @Test fun quickAddAddsStepsAndSavesOnce() {
        val writes = mutableListOf<Pair<Long, Long>>()
        show(writes)
        compose.onNodeWithTag(SlideTags.COUNT).performClick()
        compose.onNodeWithText("5").performClick()
        compose.onNodeWithText("20").performClick()
        compose.onNodeWithTag(SlideTags.SAVE).performClick()
        compose.waitForIdle()
        assertEquals(listOf(100L to 35L), writes) // 10 + 5 + 20, written once on Save
    }

    @Test fun submitGoesBackToTheReview() {
        var done = 0
        show(mutableListOf(), { done++ })
        compose.onNodeWithTag(SlideTags.SUBMIT).performClick()
        assertEquals(1, done)
    }
}
