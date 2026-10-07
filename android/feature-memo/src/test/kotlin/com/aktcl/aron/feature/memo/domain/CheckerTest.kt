package com.aktcl.aron.feature.memo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckerTest {
    private val skus = mapOf(103L to SummarySku(103, "lighter"), 105L to SummarySku(105, "match"))

    private fun memo(
        uuid: String, lines: List<MemoItem>, discounts: List<MemoDiscountItem> = emptyList(), qc: List<MemoQcItem> = emptyList(),
        offer: Long = 0, drp: Long = 0, qcMtk: Long = 0,
    ): StoredMemo {
        val gross = lines.sumOf { it.grossMtk }
        return StoredMemo(uuid, uuid, 1, "2026-10-07", "2026-10-07T04:00:00.000Z", null, lines, discounts, qc, gross, offer, drp, qcMtk, gross - offer - drp - qcMtk, 0, 0)
    }

    /** Memo-level discount (skuId null) is part of the grand total but falls out of every category row. */
    @Test fun homeCategoryNetsAddUpToTheGrandTotalWithAMemoLevelDiscount() {
        val m = memo("a", listOf(MemoItem(103, 10, 12_500, 125_000)), listOf(MemoDiscountItem(null, 0, 12_500, "offer")), offer = 12_500)
        val h = HomeMoneyBuilder.build(listOf(m), skus)
        assertEquals(h.grandTotalMtk, h.categories.sumOf { it.netMtk })
    }

    /** A QC settlement on a category with no sale today vanishes from the per-category lines. */
    @Test fun homeQcOnACategoryWithoutSalesStillShows() {
        val m = memo("a", listOf(MemoItem(103, 10, 12_500, 125_000)), qc = listOf(MemoQcItem(105, 1, 5_000)), qcMtk = 5_000)
        val h = HomeMoneyBuilder.build(listOf(m), skus)
        assertEquals(h.totalQcMtk, h.categories.sumOf { it.qcMtk })
    }

    /** Day summary: category discount columns lose a memo-level offer, so discount + others no longer reconciles. */
    @Test fun daySummaryCategoryDiscountsCoverMemoLevelOffers() {
        val m = SummaryMemo("a", null, listOf(SummaryLine(103, 10, 125_000)), listOf(SummaryDiscount(null, 12_500, "offer")), 0, 112_500)
        val s = DaySummaryCalculator.compute(listOf(m), skus, mapOf(103L to 10L))
        assertEquals(s.discountAndOthersMtk, s.categories.sumOf { it.discountMtk })
    }
}
