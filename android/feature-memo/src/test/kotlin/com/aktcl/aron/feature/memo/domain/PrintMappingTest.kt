package com.aktcl.aron.feature.memo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintMappingTest {
    private val names = PrintNames({ "SKU$it" }, { "Outlet $it (C1)" }, "sr334001", "Route 7")
    private fun memo(lines: List<MemoItem> = listOf(MemoItem(105, 3, 28_000, 84_000)), due: Long = 0, offer: Long = 0, drp: Long = 0, sup: String? = null) =
        StoredMemo("u", "sr1-261005-001", 1, "2026-10-05", "2026-10-05T04:35:00.000Z", sup, lines, emptyList(), emptyList(), 84_000, offer, drp, 0, 84_000 - offer - drp, 84_000 - offer - drp - due, due, null, 5, "sr1-261005-000")

    @Test fun amountsAreTheStoredColumnsAndTheKindFollowsTheMemo() {
        val p = PrintMapping.memo(memo(), names)
        assertEquals("cash_memo", p.kind); assertEquals(84_000, p.netMtk); assertEquals(5, p.roundAdjMtk); assertEquals("Outlet 1 (C1)", p.outlet)
        assertEquals(java.time.Instant.parse("2026-10-05T04:35:00.000Z").toEpochMilli(), p.committedAtEpochMs)
        assertEquals("credit_memo", PrintMapping.kindOf(memo(due = 30_000)))
        assertEquals("drp_memo", PrintMapping.kindOf(memo(drp = 8_000)))
        assertEquals("offer_memo", PrintMapping.kindOf(memo(offer = 8_000)))
        assertEquals("zero_memo", PrintMapping.kindOf(memo(lines = emptyList())))
        assertEquals("edited_memo", PrintMapping.kindOf(memo(sup = "old")))
    }

    @Test fun editedMemoCarriesTheOriginalNumberAndAReprintCarriesItsNumber() {
        val p = PrintMapping.memo(memo(sup = "old"), names, reprintNo = 2)
        assertEquals("sr1-261005-000", p.supersedesMemoNo); assertEquals(2, p.reprintNo)
    }

    @Test fun creditMemoPrintsPaidAndDue() {
        val p = PrintMapping.memo(memo(due = 30_000), names)
        assertEquals(30_000, p.dueMtk); assertEquals(54_000, p.paidMtk); assertEquals(true, p.isCredit)
    }

    @Test fun daySummaryPrintCarriesGrandTotal() {
        val m = SummaryMemo("a", null, listOf(SummaryLine(103, 375, 4_687_500)), listOf(SummaryDiscount(103, 437_500, "offer")), 0, 4_250_000)
        val s = DaySummaryCalculator.compute(listOf(m), mapOf(103L to SummarySku(103, "lighter")), mapOf(103L to 400L))
        val p = PrintMapping.daySummary(s, 0, 0, 1L, names)
        assertEquals(4_250_000, p.netMtk); assertEquals(437_500, p.discountMtk); assertEquals(1, p.memoCount)
    }
}
