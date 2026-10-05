package com.aktcl.aron.rules

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** N-003: golden vectors from docs/ui-reference/sr/ and the s7 equations. */
class MemoMathTest {
    private fun stated(t: MemoTotals, paid: Long = 0) =
        StatedMemo(t.grossMtk, t.offerDiscountMtk, t.drpDiscountMtk, t.qcDeductionMtk, t.netMtk, t.roundAdjMtk, paid, t.netMtk - paid,
            t.lineCount, t.discountLineCount, t.qcLineCount)

    @Test
    fun homeCardGoldenVector() {
        // 141.00 + 4,687.50 - 437.50 - 0.00 = 4,391.00
        val aster = MemoLine.priced(1, 375, 12_500)
        assertEquals(4_687_500L, aster.grossMtk)
        val lines = listOf(MemoLine(2, 0, 141_000), aster)
        val discounts = listOf(DiscountLine(1, 35, 437_500, DiscountKind.OFFER))
        val t = MemoMath.totals(lines, discounts, emptyList())
        assertEquals(4_391_000L, t.netMtk)
        assertEquals(0L, t.roundAdjMtk)
        assertEquals("4,391.00", Money.formatTaka(t.netMtk))
        assertEquals(437_500L, 35 * 12_500L)
    }

    @Test
    fun memoScreenGoldenVectors() {
        assertEquals(84_000L, Money.lineGrossMtk(3, 28_000))   // Flame Box 3 dozen at 28.00
        assertEquals(57_000L, Money.lineGrossMtk(3, 19_000))   // Salmon 3 dozen at 19.00
        val fb = MemoMath.totals(listOf(MemoLine.priced(10, 3, 28_000)), listOf(DiscountLine(11, 3, 0, DiscountKind.OFFER)), emptyList())
        assertEquals(84_000L, fb.netMtk)
        assertEquals("84.00", Money.formatTaka(fb.netMtk))
        assertEquals(57_000L, MemoMath.totals(listOf(MemoLine.priced(12, 3, 19_000)), emptyList(), emptyList()).netMtk)
    }

    @Test
    fun threeDecimalSeedPricesStayExact() {
        assertEquals(7_935L, Money.lineGrossMtk(1, 7_935))
        assertEquals(158_700L, Money.lineGrossMtk(20, 7_935))
        assertEquals("7.935", Money.formatTaka(7_935, 3))
        assertEquals("7.94", Money.formatTaka(7_935, 2))
    }

    @Test
    fun totalsAreSummedUnroundedAndRoundedOnce() {
        // docs/24 s7.4 fixture: five lines total 116,416 mtk -> net 116,420, adj +4
        val lines = listOf(80_000L, 20_000L, 12_500L, Money.lineGrossMtk(1, 28_000, 12), Money.lineGrossMtk(1, 19_000, 12)).mapIndexed { i, g -> MemoLine(i.toLong(), 1, g) }
        val t = MemoMath.totals(lines, emptyList(), emptyList())
        assertEquals(116_416L, t.rawMtk); assertEquals(116_420L, t.netMtk); assertEquals(4L, t.roundAdjMtk)
        // rounding each line first would give a different answer: 2,333 -> 2,330 and 1,583 -> 1,580
        assertTrue(lines.sumOf { Money.roundToPaisaHalfUp(it.grossMtk) } != t.netMtk)
        assertEquals(5, t.lineCount)
    }

    @Test
    fun halfPaisaRoundsAwayFromZeroBothSigns() {
        assertEquals(10L, MemoMath.totals(listOf(MemoLine(1, 1, 5)), emptyList(), emptyList()).netMtk)
        assertEquals(0L, MemoMath.totals(listOf(MemoLine(1, 1, 4)), emptyList(), emptyList()).netMtk)
        val neg = MemoMath.totals(emptyList(), emptyList(), listOf(QcLine(5, true)))
        assertEquals(-10L, neg.netMtk); assertEquals(-5L, neg.roundAdjMtk)
    }

    @Test
    fun discountKindsAndQcRouting() {
        val t = MemoMath.totals(
            listOf(MemoLine(1, 10, 100_000)),
            listOf(DiscountLine(1, 1, 10_000, DiscountKind.OFFER), DiscountLine(1, 1, 5_000, DiscountKind.FREE_GOODS), DiscountLine(null, 0, 7_000, DiscountKind.DRP)),
            listOf(QcLine(3_000, true), QcLine(9_999, false)),
        )
        assertEquals(15_000L, t.offerDiscountMtk); assertEquals(7_000L, t.drpDiscountMtk); assertEquals(3_000L, t.qcDeductionMtk)
        assertEquals(75_000L, t.netMtk); assertEquals(3, t.discountLineCount); assertEquals(2, t.qcLineCount)
    }

    @Test
    fun settlementAndVerify() {
        val lines = listOf(MemoLine(1, 3, 84_000)); val t = MemoMath.totals(lines, emptyList(), emptyList())
        assertEquals(MemoSettlement(50_000, 34_000), MemoMath.settle(t.netMtk, 50_000))
        assertTrue(MemoMath.settle(t.netMtk, 50_000).isCredit)
        assertTrue(!MemoMath.settle(t.netMtk, 84_000).isCredit)
        assertEquals(emptyList(), MemoMath.verify(stated(t, 50_000), lines, emptyList(), emptyList()))
        val bad = MemoMath.verify(stated(t).copy(netMtk = 84_010, lineCount = 2), lines, emptyList(), emptyList())
        assertEquals(setOf("net_mtk", "paid_plus_due_mtk", "line_count"), bad.map { it.field }.toSet())
        val credit = MemoMath.totals(emptyList(), emptyList(), listOf(QcLine(5_000, true)))
        assertEquals(emptyList(), MemoMath.verify(stated(credit), emptyList(), emptyList(), listOf(QcLine(5_000, true))))
        assertEquals(listOf("net_negative"), MemoMath.verify(stated(credit), emptyList(), emptyList(), listOf(QcLine(5_000, true)), allowNegativeNet = false).map { it.field })
        val sale = MemoMath.totals(listOf(MemoLine(1, 1, 1_000)), emptyList(), listOf(QcLine(5_000, true)))
        assertEquals(listOf("net_negative"), MemoMath.verify(stated(sale), listOf(MemoLine(1, 1, 1_000)), emptyList(), listOf(QcLine(5_000, true))).map { it.field })
    }

    @Test
    fun unitsAndPackBadge() {
        assertEquals(40L, Quantity.toBase(2, QtyUnit.PACK, 20))
        assertEquals(7L, Quantity.toBase(7, QtyUnit.STICK))
        assertEquals(3L, Quantity.toBase(3, QtyUnit.DOZEN))     // matches: the dozen is the base unit
        assertEquals(375L, Quantity.toBase(375, QtyUnit.PIECE))
        assertEquals(2L to 4L, Quantity.packBadge(44, 20))
        assertFailsWith<ArithmeticException> { Quantity.toBase(Long.MAX_VALUE, QtyUnit.PACK, 2) }
        assertFailsWith<IllegalArgumentException> { Quantity.toBase(-1, QtyUnit.STICK) }
    }

    @Test
    fun formatTaka() {
        assertEquals("1,164.20", Money.formatTaka(1_164_200))
        assertEquals("0.00", Money.formatTaka(0))
        assertEquals("-0.01", Money.formatTaka(-10))
        assertEquals("12,345,678.90", Money.formatTaka(12_345_678_900))
        assertEquals("1,000", Money.formatTaka(1_000_000, 0))
    }

    @Test
    fun lineOrderNeverChangesTheMemoTotal() {
        val rnd = Random(2026)
        repeat(500) { round ->
            val n = rnd.nextInt(0, 40)
            val lines = List(n) { MemoLine(rnd.nextLong(1, 50), rnd.nextLong(0, 2_000), Money.lineGrossMtk(rnd.nextLong(0, 2_000), rnd.nextLong(0, 90_000), listOf(1L, 1L, 12L, 20L).random(rnd))) }
            val discounts = List(rnd.nextInt(0, 8)) { DiscountLine(null, rnd.nextLong(0, 100), rnd.nextLong(0, 300_000), DiscountKind.entries.random(rnd)) }
            val qcs = List(rnd.nextInt(0, 4)) { QcLine(rnd.nextLong(0, 100_000), rnd.nextBoolean()) }
            val base = MemoMath.totals(lines, discounts, qcs)
            assertEquals(lines.sumOf { it.grossMtk }, base.grossMtk, "round $round")
            assertEquals(base.rawMtk + base.roundAdjMtk, base.netMtk)
            assertEquals(0L, base.netMtk % 10L)
            assertTrue(base.roundAdjMtk in -5L..5L)
            repeat(5) {
                val shuffled = MemoMath.totals(lines.shuffled(rnd), discounts.shuffled(rnd), qcs.shuffled(rnd))
                assertEquals(base, shuffled)
            }
        }
    }
}
