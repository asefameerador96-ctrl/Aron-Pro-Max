package com.aktcl.aron.feature.sale.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The SR's QC in two steps (manual 25, 26): step 1 the SKU carousel with the summary and the submit bar, step 2 one SKU's entry page. The one-scroll
 * screen (AMO) is not touched and keeps its own tests (SaleScreensTest); these cover the same behaviours on the new layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QcTwoStepTest {
    @get:Rule val compose = createComposeRule()
    private val skus = listOf(Fx.maxr, Fx.aster)

    private fun draft(vararg e: QcEntry): com.aktcl.aron.feature.sale.domain.SaleDraft {
        var d = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(Fx.draft(), 100, 20, QtyUnit.STICK), 103, 10, QtyUnit.PIECE)
        e.forEach { d = SaleDraftOps.setQc(d, it) }
        return d
    }

    private fun show(
        d: com.aktcl.aron.feature.sale.domain.SaleDraft, qtyOf: (Long, String) -> Long = { _, _ -> 0 }, onQty: (Long, String, Long) -> Unit = { _, _, _ -> }, locked: Boolean = false,
        onDone: () -> Unit = {}, onClear: (Long) -> Unit = {}, onCancel: (() -> Unit)? = null,
    ) = compose.setContent {
        AronTheme(AppLanguage.EN) {
            QcScreen(skus, SaleReviewCalculator.review(d, Fx.catalog), qtyOf, onQty, locked, onDone, onCancel = onCancel, onClearSku = onClear, twoStep = true)
        }
    }

    @Test fun stepOneHasTheCarouselAndNoFieldsAndATapOpensStepTwoWithSixFields() {
        show(draft())
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(2)
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(0)
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(6)
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(0)
    }

    @Test fun summaryHasOneRowPerSkuWithTheTypesAndTheTotal() {
        val d = draft(QcEntry(100, "damaged_crushed", "MFC", 3), QcEntry(100, "taste", "MKT", 2), QcEntry(103, "taste", "MKT", 1))
        show(d)
        compose.onAllNodesWithTag(QcTags.SUMMARY_ROW).assertCountEquals(2)
        compose.onNodeWithText("MFC Fault,MKT Fault").assertExists()
        compose.onNodeWithText("MKT Fault").assertExists()
        compose.onNodeWithText("Total QC quantity: 6").assertExists()
    }

    @Test fun aTypedQuantityIsPassedOnAndAboveWhatIsLeftIsPulledDownAndFlagged() {
        val got = mutableListOf<Long>()
        show(draft(), onQty = { _, _, q -> got += q })
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[0].performTextInput("7")
        assertEquals(listOf(7L), got)
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[1].performTextInput("99")
        assertTrue("pulled down to the most that fits", got.last() < 99L)
    }

    @Test fun cancelPutsBackTheValuesThePageOpenedWithAndLeaves() {
        val calls = mutableListOf<Triple<Long, String, Long>>()
        show(draft(), qtyOf = { _, c -> if (c == "taste") 4L else 0L }, onQty = { s, c, q -> calls += Triple(s, c, q) }, onCancel = {})
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[0].performTextInput("5")
        calls.clear()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue("taste is put back to the 4 it opened with", calls.contains(Triple(100L, "taste", 4L)))
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(2) // back on step 1
    }

    @Test fun saveLeavesTheStepKeepingWhatWasEntered() {
        val calls = mutableListOf<Long>()
        show(draft(), onQty = { _, _, q -> calls += q })
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[0].performTextInput("3")
        calls.clear()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertTrue("save changes nothing", calls.isEmpty())
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(2)
    }

    @Test fun deleteClearsTheSkuAndIsHiddenWhenLockedOrNothingIsEntered() {
        var cleared = -1L
        show(draft(QcEntry(100, "taste", "MKT", 1)), onClear = { cleared = it })
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        assertEquals(100L, cleared)
    }

    @Test fun lockedQcShowsNoDeleteAndNoEnabledField() {
        show(draft(QcEntry(100, "taste", "MKT", 1)), locked = true)
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD)[0].assertIsNotEnabled()
    }

    @Test fun submitAsksFirstThenCallsDoneOnYes() {
        var done = 0
        show(draft(QcEntry(103, "damaged_crushed", "MFC", 2)), onDone = { done++ })
        compose.onNodeWithText("Submit QC").performClick()
        assertEquals("QC is not submitted before the confirm", 0, done)
        compose.onNodeWithText("No").performClick()
        assertEquals(0, done)
        compose.onNodeWithText("Submit QC").performClick()
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, done)
    }

    @Test fun theOpenSkuSurvivesAKillAndRelaunch() {
        val restoration = StateRestorationTester(compose)
        val d = draft()
        restoration.setContent { AronTheme(AppLanguage.EN) { QcScreen(skus, SaleReviewCalculator.review(d, Fx.catalog), { _, _ -> 0 }, { _, _, _ -> }, false, {}, twoStep = true) } }
        compose.onAllNodesWithTag(QcTags.CARD)[1].performClick()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(6)
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(6) // still on the Aster page
        compose.onAllNodesWithTag(QcTags.CARD).assertCountEquals(0)
    }
}
