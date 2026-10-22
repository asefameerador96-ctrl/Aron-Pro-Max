package com.aktcl.aron.feature.memo.domain

import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/34 s4.2: stock that is not whole packets keeps its base remainder (Summary return, Home KPI); the category row says one unit. */
class EntryUnitSummaryTest {
    private val pack20 = EntrySpec(QtyUnit.PACK, 20)

    @Test fun summaryReturnKeepsTheSticksLeftOverAfterWholePackets() {
        // 200 sticks issued, 3 QC sticks returned, 3 packets (60) sold: 137 sticks back = 6 packets + 17 sticks
        val memo = SummaryMemo("m", null, listOf(SummaryLine(1, 60, 475_000)), emptyList(), 0, 475_000)
        val s = DaySummaryCalculator.compute(listOf(memo), mapOf(1L to SummarySku(1, "cigarette")), mapOf(1L to 200L), mapOf(1L to 3L)) { pack20.takeIf { _ -> true } }
        val r = s.skus.single()
        assertEquals(3L, r.qtyEntered); assertEquals(0L, r.qtyRemBase)
        assertEquals(6L, r.returnEntered); assertEquals(17L, r.returnRemBase)
        val c = s.categories.single()
        assertEquals("packet", c.unitKey); assertEquals(6L, c.returnEntered); assertEquals(17L, c.returnRemBase); assertEquals(60L, c.qtyBase)
    }

    @Test fun kpiCategoryKeepsTheStockRemainderAndNeverAddsAcrossCategories() {
        val skus = mapOf(1L to SummarySku(1, "cigarette"), 2L to SummarySku(2, "lighter"))
        val k = KpiStripBuilder.build(1, 1, 1, mapOf(1L to 10L, 2L to 4L), mapOf(1L to 6L, 2L to 4L), skus, mapOf(1L to 0L, 2L to 0L), mapOf(1L to 17L, 2L to 0L)) { if (it == "lighter") "box" else "packet" }
        assertEquals(listOf("cigarette", "lighter"), k.categories.map { it.categoryCode })
        assertEquals(6L, k.categories[0].currentStockBase); assertEquals(17L, k.categories[0].currentRemBase)
        assertEquals(4L, k.categories[1].issueBase)
    }

    @Test fun aCategoryWithTwoEntryUnitsGetsOneRowPerUnit() {
        // one cigarette SKU counted in packets of 20, another with base_per_pack 1 counted in sticks: never added together
        val memo = SummaryMemo("m", null, listOf(SummaryLine(1, 60, 1), SummaryLine(2, 5, 1)), emptyList(), 0, 2)
        val specs = mapOf(1L to pack20, 2L to EntrySpec(QtyUnit.STICK, 1))
        val cats = mapOf(1L to SummarySku(1, "cigarette"), 2L to SummarySku(2, "cigarette"))
        val s = DaySummaryCalculator.compute(listOf(memo), cats, emptyMap()) { specs[it] }
        assertEquals(listOf("packet" to 3L, "stick" to 5L), s.categories.map { it.unitKey to it.qtyEntered })
    }
}
