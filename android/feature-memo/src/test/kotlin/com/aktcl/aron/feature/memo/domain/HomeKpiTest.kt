package com.aktcl.aron.feature.memo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeKpiTest {
    private val skus = mapOf(103L to SummarySku(103, "lighter"), 105L to SummarySku(105, "match"), 106L to SummarySku(106, "match"))

    @Test fun sixOfSixtyIsTenPercentAndNothingPlannedIsNull() {
        assertEquals(1000, KpiStripBuilder.strikeRateHundredths(6, 60))
        assertEquals(3333, KpiStripBuilder.strikeRateHundredths(1, 3))
        assertEquals(6667, KpiStripBuilder.strikeRateHundredths(2, 3))
        assertNull(KpiStripBuilder.strikeRateHundredths(0, 0))
    }

    @Test fun stripFromSeededDay() {
        val k = KpiStripBuilder.build(60, 6, 6, mapOf(103L to 400L, 105L to 200L, 106L to 200L), mapOf(103L to 25L, 105L to 197L, 106L to 197L), skus) { if (it == "match") "dozen" else "piece" }
        assertEquals(54, k.nonVisitOutlets); assertEquals(0, k.noSaleOutlets); assertEquals(1000, k.strikeRateHundredths)
        assertEquals(KpiCategory("lighter", 400, 25, "piece"), k.categories[0]); assertEquals(KpiCategory("match", 400, 394, "dozen"), k.categories[1])
    }

    private fun memo(id: String, lines: List<MemoItem>, discounts: List<MemoDiscountItem>, qc: List<MemoQcItem>, net: Long, drp: Long = 0, offer: Long = 0, qcTotal: Long = 0, sup: String? = null) =
        StoredMemo(id, id, 1, "2026-10-05", "t", sup, lines, discounts, qc, lines.sumOf { it.grossMtk }, offer, drp, qcTotal, net, net, 0)

    @Test fun moneyCardsGiveTheSeededTotals() {
        val aster = memo("a", listOf(MemoItem(103, 375, 12_500, 4_687_500)), listOf(MemoDiscountItem(103, 35, 437_500, "offer")), emptyList(), 4_250_000, offer = 437_500)
        val fb = memo("b", listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000)
        val sl = memo("c", listOf(MemoItem(106, 3, 19_000, 57_000)), emptyList(), emptyList(), 57_000)
        val m = HomeMoneyBuilder.build(listOf(aster, fb, sl), skus)
        assertEquals(4_828_500, m.grossMtk); assertEquals(437_500, m.totalDiscountMtk); assertEquals(4_391_000, m.grandTotalMtk)
        assertEquals(MoneyCategory("lighter", 4_687_500, 437_500, 0, 4_250_000), m.categories[0])
        assertEquals(MoneyCategory("match", 141_000, 0, 0, 141_000), m.categories[1])
    }

    @Test fun drpAndQcAppearInTheirOwnLinesAndAnEditIsCountedOnce() {
        val old = memo("o", listOf(MemoItem(105, 10, 28_000, 280_000)), emptyList(), emptyList(), 280_000)
        val new = memo("n", listOf(MemoItem(105, 10, 28_000, 280_000)), listOf(MemoDiscountItem(105, 1, 28_000, "drp")), listOf(MemoQcItem(105, 1, 14_000)), 238_000, drp = 28_000, qcTotal = 14_000, sup = "o")
        val m = HomeMoneyBuilder.build(listOf(old, new), skus)
        assertEquals(280_000, m.grossMtk); assertEquals(28_000, m.drpDiscountMtk); assertEquals(14_000, m.totalQcMtk); assertEquals(238_000, m.grandTotalMtk)
        assertEquals(MoneyCategory("match", 280_000, 0, 14_000, 266_000), m.categories.single())
    }
}
