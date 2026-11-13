package com.aktcl.aron.feature.sale.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P-SR2 (manuals 25, 26): the vendor's two QC pages over the same draft logic. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h1200dp")
class QcManualScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun draftWith(vararg e: QcEntry): com.aktcl.aron.feature.sale.domain.SaleDraft {
        var d = SaleDraftOps.setQuantity(com.aktcl.aron.feature.sale.domain.SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1"), 100, 4, QtyUnit.PACK)
        e.forEach { d = SaleDraftOps.setQc(d, it) }
        return d
    }

    private fun show(d: com.aktcl.aron.feature.sale.domain.SaleDraft, writes: MutableList<Triple<Long, String, Long>> = mutableListOf(), cleared: MutableList<Long> = mutableListOf(), done: () -> Unit = {}, locked: Boolean = false) {
        val review = SaleReviewCalculator.review(d, Fx.catalog)
        compose.setContent {
            AronTheme(AppLanguage.EN) {
                QcManualScreen(
                    listOf(Fx.maxr), review, { id, c -> review.qc.firstOrNull { it.entry.skuId == id && it.entry.faultTypeCode == c }?.entry?.defectQty ?: 0L },
                    { id, c, q -> writes += Triple(id, c, q) }, locked, done, capMtkOf = { 594_500L }, onClearSku = { cleared += it },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun emptySummaryShowsTheEmptyLineAndATileForEachSku() {
        show(draftWith())
        compose.onNodeWithText("No product for QC.").assertExists()
        compose.onAllNodesWithTag(QcTags.TILE).assertCountEquals(1)
    }

    @Test fun submitAsksFirstAndYesCompletes() {
        var done = 0
        show(draftWith(), done = { done++ })
        compose.onNodeWithText("Submit QC", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(0, done) // nothing completes before the answer
        compose.onNodeWithText("Yes").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun noKeepsQcOpenAndCompletesNothing() {
        var done = 0
        show(draftWith(), done = { done++ })
        compose.onNodeWithText("Submit QC", substring = true).performClick()
        compose.onNodeWithText("No").performClick()
        compose.waitForIdle()
        assertEquals(0, done)
    }

    @Test fun savedLinesAppearInTheSummaryWithKindAndSettlement() {
        show(draftWith(QcEntry(100, "damaged_crushed", "MFC", 2), QcEntry(100, "expired_stock", "MKT", 1)))
        compose.onAllNodesWithTag(QcTags.SUMMARY_ROW).assertCountEquals(1)
        compose.onNodeWithText("MFC Fault,MKT Fault").assertExists()
    }

    @Test fun cancelOnTheEntryPagePutsBackWhatItOpenedWith() {
        val writes = mutableListOf<Triple<Long, String, Long>>()
        show(draftWith(QcEntry(100, "damaged_crushed", "MFC", 2)), writes)
        compose.onNodeWithTag(QcTags.TILE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QcTags.CANCEL).performScrollTo().performClick()
        compose.waitForIdle()
        // every fault row is written back to its opening value; the page is the tiles again
        assertEquals(6, writes.size)
        assertEquals(2L, writes.first { it.second == "damaged_crushed" }.third)
        compose.onAllNodesWithTag(QcTags.TILE).assertCountEquals(1)
    }

    @Test fun deleteClearsTheSkuAndSaveKeepsAndReturns() {
        val cleared = mutableListOf<Long>()
        show(draftWith(QcEntry(100, "taste", "MKT", 1)), cleared = cleared)
        compose.onNodeWithTag(QcTags.TILE).performClick()
        compose.onNodeWithTag(QcTags.SAVE).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Long>(), cleared)
        compose.onNodeWithTag(QcTags.TILE).performClick()
        compose.onNodeWithTag(QcTags.DELETE).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(100L), cleared)
    }

    @Test fun lockedQcOpensNoEntryPage() {
        show(draftWith(), locked = true)
        compose.onNodeWithTag(QcTags.TILE).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(QcTags.SAVE).assertCountEquals(0)
    }
}
