package com.aktcl.aron.feature.memo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySummaryTest {
    private val skus = mapOf(103L to SummarySku(103, "lighter"), 105L to SummarySku(105, "match"), 106L to SummarySku(106, "match"))

    private fun seeded(): List<SummaryMemo> {
        // 4 lighter memos (375 pieces at 12.50), discount 437.50 on the lighter; FB 3 dozen at 28.00; SL 3 dozen at 19.00 (docs/ui-reference/sr/sales-summary.md)
        val lighters = listOf(100L, 100L, 100L, 75L).mapIndexed { i, q ->
            SummaryMemo("m$i", null, listOf(SummaryLine(103, q, q * 12_500)), if (i == 0) listOf(SummaryDiscount(103, 437_500, "offer")) else emptyList(), 0, if (i == 0) 1_250_000 - 437_500 else q * 12_500)
        }
        return lighters + SummaryMemo("fb", null, listOf(SummaryLine(105, 3, 84_000)), emptyList(), 0, 84_000) +
            SummaryMemo("sl", null, listOf(SummaryLine(106, 3, 57_000)), emptyList(), 0, 57_000)
    }

    @Test fun seededDayGivesTheScreenNumbers() {
        val s = DaySummaryCalculator.compute(seeded(), skus, mapOf(103L to 400L, 105L to 200L, 106L to 200L))
        val aster = s.skus.first { it.skuId == 103L }
        assertEquals(4, aster.memoCount); assertEquals(375, aster.qtyBase); assertEquals(4_687_500, aster.valueMtk)
        assertEquals(437_500, aster.discountMtk); assertEquals(4_250_000, aster.discountedValueMtk); assertEquals(25, aster.returnQtyBase)
        assertEquals(197, s.skus.first { it.skuId == 105L }.returnQtyBase)
        val match = s.categories.first { it.categoryCode == "match" }
        assertEquals(6, match.qtyBase); assertEquals(141_000, match.valueMtk); assertEquals(394, match.returnQtyBase)
        assertEquals(4_828_500, s.grossMtk); assertEquals(437_500, s.discountAndOthersMtk); assertEquals(4_391_000, s.grandTotalMtk)
        assertEquals(6, s.memoCount)
    }

    @Test fun aSupersededMemoIsCountedOnce() {
        val old = SummaryMemo("old", null, listOf(SummaryLine(105, 3, 84_000)), emptyList(), 0, 84_000)
        val edited = SummaryMemo("new", "old", listOf(SummaryLine(105, 2, 56_000)), emptyList(), 0, 56_000)
        val s = DaySummaryCalculator.compute(listOf(old, edited), skus, mapOf(105L to 10L))
        assertEquals(1, s.memoCount); assertEquals(2, s.skus.single().qtyBase); assertEquals(8, s.skus.single().returnQtyBase); assertEquals(56_000, s.grandTotalMtk)
    }

    @Test fun qcReturnsLeaveStockAndNothingGoesNegative() {
        val m = SummaryMemo("a", null, listOf(SummaryLine(105, 3, 84_000)), emptyList(), 0, 84_000)
        assertEquals(4, DaySummaryCalculator.compute(listOf(m), skus, mapOf(105L to 10L), mapOf(105L to 3L)).skus.single().returnQtyBase)
        assertEquals(0, DaySummaryCalculator.compute(listOf(m), skus, mapOf(105L to 2L)).skus.single().returnQtyBase)
    }

    @Test fun drpAndQcCountInDiscountAndOthersButNotInThePerSkuDiscount() {
        val m = SummaryMemo("a", null, listOf(SummaryLine(105, 10, 280_000)), listOf(SummaryDiscount(105, 28_000, "drp")), 14_000, 238_000)
        val s = DaySummaryCalculator.compute(listOf(m), skus, mapOf(105L to 10L))
        assertEquals(0, s.skus.single().discountMtk); assertEquals(42_000, s.discountAndOthersMtk); assertEquals(238_000, s.grandTotalMtk)
    }

    // lead gate finding 3: the vendor's category order is for the SR screen only
    private val mixed = mapOf(1L to SummarySku(1, "cigarette"), 2L to SummarySku(2, "bidi"), 3L to SummarySku(3, "match"), 4L to SummarySku(4, "lighter"))
    private fun mixedMemo() = listOf(SummaryMemo("a", null, listOf(SummaryLine(1, 10, 10_000), SummaryLine(2, 20, 20_000), SummaryLine(3, 3, 3_000), SummaryLine(4, 4, 4_000)), emptyList(), 0, 37_000))

    // the AMO summary and the PRINTED summary read compute(): it keeps the original alphabetical listing
    @Test fun computeKeepsTheOriginalAlphabeticalCategoryOrderForTheAmoAndThePrint() {
        val s = DaySummaryCalculator.compute(mixedMemo(), mixed, mapOf(1L to 50L, 2L to 50L, 3L to 50L, 4L to 50L))
        assertEquals(listOf("bidi", "cigarette", "lighter", "match"), s.categories.map { it.categoryCode })
    }

    @Test fun thePrintedSummaryDoesNotFollowTheScreenOrder() {
        val s = DaySummaryCalculator.compute(mixedMemo(), mixed, mapOf(1L to 50L, 2L to 50L, 3L to 50L, 4L to 50L))
        val names = PrintNames({ "S$it" }, { "O$it" }, "sr", "r")
        val before = PrintMapping.daySummary(s, 0, 0, 1L, names)
        val after = PrintMapping.daySummary(DaySummaryCalculator.inCategoryOrder(s), 0, 0, 1L, names)
        assertEquals("only the screen's copy is reordered; the paper's lines are the same either way", before, after)
    }

    // the SR host alone asks for the vendor order, and only the listing moves
    @Test fun theSrScreenOrderIsCigaretteBidiLighterMatchAndNoFigureMoves() {
        val s = DaySummaryCalculator.compute(mixedMemo(), mixed, mapOf(1L to 50L, 2L to 50L, 3L to 50L, 4L to 50L))
        val sr = DaySummaryCalculator.inCategoryOrder(s)
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), sr.categories.map { it.categoryCode })
        assertEquals(s.categories.toSet(), sr.categories.toSet())
        assertEquals(s.copy(categories = sr.categories), sr)
    }
}
