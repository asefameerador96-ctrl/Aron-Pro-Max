package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureFrameTest {
    private val month = "2026-11"; private val through = "2026-11-17"
    private fun memo(uuid: String, date: String, state: String, qty: Long = 10, supersedes: String? = null, voidOf: String? = null, kind: String = "sale") =
        LocalMemo(uuid, 1L, date, supersedes, state, null, if (voidOf != null) emptyList() else listOf(LocalMemoLine(5, "sale", qty, 20, qty * 100)), 7, kind, voidOf)
    private fun frame(memos: List<LocalMemo>, acked: Set<String>? = emptySet()) = FigureFrame.of(memos, month, through, "asof", acked)

    @Test fun todayOnThisPhoneIsOnlyLiveMemosAfterThroughDateAndNothingIsAddedForEarlierDays() {
        val f = frame(listOf(memo("t", "2026-11-18", "pending", 7), memo("t2", "2026-11-18", "acked", 3), memo("e", "2026-11-10", "pending", 99)))
        assertEquals(10L, f.today.qtyBase); assertEquals(2L, f.today.memoCount)
        assertEquals(1, f.unconfirmedEarlier) // the earlier-day memo is only COUNTED, never in a figure
    }

    @Test fun theCountLineHasUnconfirmedEarlierDaysAndNotConfirmedOnes() {
        val acked = setOf("c")
        val f = frame(listOf(memo("c", "2026-11-03", "acked"), memo("p", "2026-11-04", "pending"), memo("u", "2026-11-05", "uncertain"), memo("q", "2026-11-06", "quarantined"), memo("late", "2026-11-07", "acked"), memo("r", "2026-11-08", "rejected")), acked)
        assertEquals(4, f.unconfirmedEarlier) // p, u, q and "late" (acknowledged after the request left); c is held, r is refused
        assertTrue(f.today.isEmpty)
    }

    @Test fun withNoRecordedOrderAnAcknowledgedEarlierMemoIsNotCountedAsUnconfirmed() {
        assertEquals(0, frame(listOf(memo("c", "2026-11-03", "acked")), null).unconfirmedEarlier)
        assertEquals(1, frame(listOf(memo("p", "2026-11-03", "pending")), null).unconfirmedEarlier)
    }

    @Test fun anEditChainAllAfterThroughDateCountsItsTipOnceAndNothingIsCounted() {
        val a = memo("a", "2026-11-18", "acked", 1); val b = memo("b", "2026-11-18", "acked", 2, supersedes = "a"); val c = memo("c", "2026-11-18", "pending", 3, supersedes = "b")
        val f = frame(listOf(a, b, c))
        assertEquals(3L, f.today.qtyBase); assertEquals(1L, f.today.memoCount); assertEquals(0, f.unconfirmedEarlier)
    }

    // B2 (lead, slice 1 check): a live tip dated after through_date is today on this phone whatever its chain holds
    @Test fun b2_confirmedEditAfterThroughDateOfAnEarlierDayMemoIsTodayAndNotCounted() {
        val o = memo("o", "2026-11-10", "acked", 5); val e = memo("e", "2026-11-18", "acked", 6, supersedes = "o")
        val f = frame(listOf(o, e), setOf("o", "e")) // the server holds neither version in the figure it sent
        assertEquals(6L, f.today.qtyBase); assertEquals(1L, f.today.memoCount); assertEquals(0, f.unconfirmedEarlier)
    }

    @Test fun b2_unconfirmedEditAfterThroughDateOfAnEarlierDayMemoIsTodayAndTheEarlierMemoIsAnUnconfirmedChange() {
        val o = memo("o", "2026-11-10", "acked", 5); val e = memo("e", "2026-11-18", "pending", 6, supersedes = "o")
        val f = frame(listOf(o, e), setOf("o")) // the figure still holds o; the phone never subtracts it
        assertEquals(6L, f.today.qtyBase); assertEquals(1, f.unconfirmedEarlier)
    }

    @Test fun b2_crossMonthEditIsTodayAndTheEarlierMonthMemoIsNotThisFiguresChange() {
        val o = memo("o", "2026-10-30", "acked", 5); val e = memo("e", "2026-11-02", "acked", 6, supersedes = "o")
        val f = FigureFrame.of(listOf(o, e), "2026-11", "2026-11-01", null, setOf("o", "e"))
        assertEquals(6L, f.today.qtyBase); assertEquals(0, f.unconfirmedEarlier)
        val g = FigureFrame.of(listOf(o, memo("e2", "2026-11-02", "pending", 6, supersedes = "o")), "2026-11", "2026-11-01", null, setOf("o"))
        assertEquals(6L, g.today.qtyBase); assertEquals(0, g.unconfirmedEarlier) // o is of October: not in November's figure
    }

    // B1 (lead, slice 1 check): an unconfirmed earlier-day edit that removes the figure's lines is still counted
    private fun memoOfSku(uuid: String, date: String, state: String, sku: Long, qty: Long = 10, supersedes: String? = null, kind: String = "sale") =
        LocalMemo(uuid, 1L, date, supersedes, state, null, listOf(LocalMemoLine(sku, "sale", qty, 20, qty * 100)), 7, kind, null)

    @Test fun b1_unconfirmedEditThatMovesTheSaleToAnotherSkuIsCountedForTheFigureThatStillHoldsTheOriginal() {
        val o = memoOfSku("o", "2026-11-10", "acked", 5); val e = memoOfSku("e", "2026-11-10", "pending", 6, supersedes = "o")
        val sku5 = FigureFrame.of(listOf(o, e), month, through, null, setOf("o"), lineIn = { it.skuId == 5L })
        assertEquals(1, sku5.unconfirmedEarlier) // the figure of SKU 5 still holds o; e has no SKU 5 line
        val sku6 = FigureFrame.of(listOf(o, e), month, through, null, setOf("o"), lineIn = { it.skuId == 6L })
        assertEquals(1, sku6.unconfirmedEarlier)
    }

    @Test fun b1_unconfirmedEditThatBecameAZeroSaleIsCounted() {
        val o = memoOfSku("o", "2026-11-10", "acked", 5); val z = memoOfSku("z", "2026-11-10", "pending", 5, supersedes = "o", kind = "zero_sale")
        assertEquals(1, FigureFrame.of(listOf(o, z), month, through, null, setOf("o"), lineIn = { it.skuId == 5L }).unconfirmedEarlier)
    }

    @Test fun voidOfAnEarlierDayMemoDatedAfterThroughDateIsCountedUntilConfirmed() {
        val o = memo("o", "2026-11-10", "acked", 4); val v = memo("v", "2026-11-18", "pending", voidOf = "o")
        assertEquals(1, frame(listOf(o, v), setOf("o")).unconfirmedEarlier)
        assertEquals(0, frame(listOf(o, v.copy(state = "acked")), setOf("o", "v")).unconfirmedEarlier)
    }

    @Test fun voidInThisMonthOfAnEarlierMonthMemoIsNotCounted() {
        val o = memo("o", "2026-10-30", "acked", 4); val v = memo("v", "2026-11-02", "pending", voidOf = "o")
        assertEquals(0, FigureFrame.of(listOf(o, v), "2026-11", "2026-11-17", null, setOf("o")).unconfirmedEarlier)
    }

    @Test fun aForkOfTwoLiveEditsIsLeftOutOfTodayAndNamed() {
        val a = memo("a", "2026-11-18", "acked", 1)
        val e1 = memo("e1", "2026-11-18", "pending", 2, supersedes = "a"); val e2 = memo("e2", "2026-11-19", "pending", 3, supersedes = "a")
        val f = frame(listOf(a, e1, e2))
        assertTrue(f.today.isEmpty); assertEquals(2, f.forkLeftOut)
    }

    @Test fun aVoidedLaterDayMemoIsGoneAndAnUnconfirmedVoidOfAnEarlierDayIsCounted() {
        val t = memo("t", "2026-11-18", "pending", 4); val v = memo("v", "2026-11-18", "pending", voidOf = "t")
        assertTrue(frame(listOf(t, v)).today.isEmpty)
        val old = memo("o", "2026-11-10", "acked", 4); val v2 = memo("v2", "2026-11-10", "pending", voidOf = "o")
        assertEquals(1, frame(listOf(old, v2), setOf("o")).unconfirmedEarlier)
    }

    @Test fun theFirstOfTheMonthIsLabelledFromTheFirstAndOtherMonthsAndLinesOutsideTheFigureAreIgnored() {
        assertTrue(FigureFrame.of(emptyList(), "2026-12", "2026-11-30", null, emptySet()).fromFirst)
        assertEquals(false, frame(emptyList()).fromFirst)
        val f = FigureFrame.of(listOf(memo("t", "2026-11-18", "pending", 5), memo("x", "2026-10-30", "pending", 9)), month, through, null, emptySet(), lineIn = { it.skuId == 5L })
        assertEquals(5L, f.today.qtyBase); assertEquals(0, f.unconfirmedEarlier)
        assertEquals(0L, FigureFrame.of(listOf(memo("t", "2026-11-18", "pending", 5)), month, through, null, emptySet(), lineIn = { it.skuId == 6L }).today.memoCount)
    }

    @Test fun twoLaterDayEditsOfAMemoThePhoneNoLongerHasAreStillAForkAndNamed() {
        // the edited memo "gone" is not in the list: both edits point at it, so the root key is the missing uuid and they group
        val e1 = memo("e1", "2026-11-18", "pending", 2, supersedes = "gone"); val e2 = memo("e2", "2026-11-19", "pending", 3, supersedes = "gone")
        val f = frame(listOf(e1, e2))
        assertTrue(f.today.isEmpty); assertEquals(2, f.forkLeftOut)
    }

    @Test fun aVoidOfAMemoWhoseChainReachesTheWindowIsCounted() {
        // the voided tip e is dated after through_date but edits o (11-10, in the window, held by the figure): an unconfirmed void is an unconfirmed change of o's day
        val o = memo("o", "2026-11-10", "acked", 4); val e = memo("e", "2026-11-18", "acked", 5, supersedes = "o"); val v = memo("v", "2026-11-18", "pending", voidOf = "e")
        assertEquals(1, frame(listOf(o, e, v), setOf("o", "e")).unconfirmedEarlier)
    }
}
