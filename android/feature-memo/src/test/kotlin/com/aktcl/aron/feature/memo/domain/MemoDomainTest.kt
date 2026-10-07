package com.aktcl.aron.feature.memo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoDomainTest {
    private fun memo(id: String, outlet: Long = 1, date: String = "2026-10-05", at: String = "2026-10-05T04:00:00.000Z", net: Long = 84_000, due: Long = 0, sup: String? = null, offer: Long = 0, drp: Long = 0, qc: Long = 0, printed: String? = null) =
        StoredMemo(id, "sr1-261005-$id", outlet, date, at, sup, listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), net + offer + drp + qc, offer, drp, qc, net, net - due, due, printed)

    @Test fun menuListsLiveMemosNewestFirstAndHidesSuperseded() {
        val rows = MemoMenu.rows(listOf(memo("a", at = "2026-10-05T04:00:00.000Z"), memo("b", at = "2026-10-05T05:00:00.000Z", sup = "a"), memo("c", at = "2026-10-05T06:00:00.000Z")))
        assertEquals(listOf("c", "b"), rows.map { it.memoUuid })
    }

    @Test fun detailShowsTotalsAndDuplicateOnReprint() {
        val d = MemoMenu.detail(memo("a", net = 70_000, offer = 10_000, drp = 4_000, qc = 0, due = 30_000, printed = "x"))
        assertEquals(14_000, d.totalDiscountMtk); assertEquals(70_000, d.grandTotalMtk); assertTrue(d.canMarkPaid); assertTrue(d.reprintIsDuplicate)
        assertFalse(MemoMenu.detail(memo("b")).canMarkPaid); assertFalse(MemoMenu.detail(memo("b")).reprintIsDuplicate)
    }

    @Test fun markPaidSettlesTheWholeRemainingDueOnceAndOutletDueFollows() {
        val m = memo("a", due = 50_000, net = 80_000)
        val d = DueLedger.markPaid(m, emptyList(), 50_000)!!
        assertEquals(50_000, d.amountMtk); assertTrue(d.isFullSettlement)
        val after = listOf(StoredCollection("a", 50_000, "2026-10-05"))
        assertNull(DueLedger.markPaid(m, after, 0)) // a double tap records nothing
        assertEquals(0, DueLedger.remaining(m, after))
        assertEquals(50_000, DueLedger.outletOutstanding(listOf(m), emptyList(), 1))
        assertEquals(0, DueLedger.outletOutstanding(listOf(m), after, 1))
        assertEquals(0, DueLedger.outletOutstanding(listOf(m), emptyList(), 2))
    }

    @Test fun payingOneMemoWhileTheOutletOwesMoreIsAPartialSettlementOfTheOutlet() {
        val m = memo("a", due = 50_000, net = 80_000)
        val d = DueLedger.markPaid(m, emptyList(), 120_000)!!
        assertEquals(50_000, d.amountMtk); assertFalse(d.isFullSettlement); assertEquals(120_000, d.outstandingBeforeMtk)
    }

    @Test fun anEditMovesTheDueToTheNewMemo() {
        val old = memo("a", due = 50_000, net = 80_000); val new = memo("b", due = 40_000, net = 60_000, sup = "a")
        assertEquals(40_000, DueLedger.outletOutstanding(listOf(old, new), emptyList(), 1))
    }

    @Test fun historyFooterIsTheExactSumOfTheShownRows() {
        val ms = listOf(memo("a", net = 10_010), memo("b", net = 20_020, date = "2026-10-04"), memo("c", net = 30_030, date = "2026-10-04"), memo("x", outlet = 2, net = 99_990), memo("d", net = 5_000, sup = "a"))
        val h = SaleHistoryBuilder.build(ms, 1)
        assertEquals(listOf("2026-10-05", "2026-10-04"), h.days.map { it.businessDate })
        assertEquals(5_000 + 50_050, h.footerMtk)
        assertEquals(h.footerMtk, h.days.flatMap { it.memos }.sumOf { it.totalMtk })
        assertTrue(SaleHistoryBuilder.build(ms, 1, fromServer = true).fromServer)
    }
}
