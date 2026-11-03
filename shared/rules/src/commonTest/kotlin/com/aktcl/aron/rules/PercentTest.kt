package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Percent (build contract s4.1, s4.2, s4.4; D-TSO-MAN-10): every fixture of the contract plus the edges. */
class PercentTest {
    private val en = UiLocale.EN
    private val bn = UiLocale.BN

    @Test
    fun dashboardCardFixtures() {
        // Geo Fencing 4/4 (tenths), CPR 3/119, Login 2/5 and Bikroy Joma 1/5 (whole), Brand CPR 2/3 and 1/3 (whole).
        assertEquals("100.0%", Percent.tenthsText(4, 4, en))
        assertEquals("2.5%", Percent.tenthsText(3, 119, en))
        assertEquals("40%", Percent.wholeText(2, 5, en))
        assertEquals("20%", Percent.wholeText(1, 5, en))
        assertEquals(67L, Percent.whole(2, 3))
        assertEquals(33L, Percent.whole(1, 3))
        // By Channel STD with one channel: the pie label's percent; Segment 750/1000 mtk -> 7500 bp.
        assertEquals("100.00%", Percent.bpText(1_234, 1_234, en))
        assertEquals(7_500L, Percent.bp(750, 1_000))
    }

    @Test
    fun cprIsRoundedOnceNeverFromRoundedBasisPoints() {
        // 29 / 1186 = 2.4452%: 245 bp, and rounding 245 bp to tenths would print 2.5%. The rule rounds once: 2.4%.
        assertEquals(245L, Percent.bp(29, 1_186))
        assertEquals(25L, Money.divHalfUp(Percent.bp(29, 1_186)!!, 10)) // the double rounding the rule forbids
        assertEquals(24L, Percent.tenths(29, 1_186))
        assertEquals("2.4%", Percent.tenthsText(29, 1_186, en))
    }

    @Test
    fun targetDetailsFixturesInBasisPoints() {
        assertEquals("35.00%", Percent.bpText(350, 1_000, en))
        assertEquals("0.20%", Percent.bpText(20, 10_000, en))
        assertEquals("1.33%", Percent.bpText(40, 2_999, en))
        assertEquals("43.42%", Percent.bpText(350, 806, en))
        assertEquals("0.25%", Percent.bpText(20, 8_065, en))
        assertEquals("104.94%", Percent.bpText(85, 81, en)) // uncapped
    }

    @Test
    fun ticketFixtures() {
        assertEquals("2%", Percent.wholeText(18, 723, en))
        assertEquals("0%", Percent.wholeText(0, 723, en))
    }

    @Test
    fun halfRoundsUpAtEveryPrecision() {
        assertEquals(1L, Percent.whole(1, 200)) // 0.5%
        assertEquals(0L, Percent.whole(1, 201)) // 0.4975%
        assertEquals(1L, Percent.tenths(1, 2_000)) // 0.05%
        assertEquals(1L, Percent.bp(1, 20_000)) // 0.005%
        assertEquals(0L, Percent.bp(1, 20_001))
    }

    @Test
    fun zeroWholeGivesNullAndADash() {
        assertNull(Percent.whole(7, 0))
        assertNull(Percent.tenths(0, 0))
        assertNull(Percent.bp(7, 0))
        assertEquals("—", Percent.wholeText(7, 0, en))
        assertEquals("—", Percent.tenthsText(7, 0, bn))
        assertEquals("—", Percent.bpText(7, 0, en))
        assertEquals(Percent.DASH, Percent.text(null, 1, en))
        assertEquals("—", Percent.DASH)
    }

    @Test
    fun negativeInputIsACallerBug() {
        assertFailsWith<IllegalArgumentException> { Percent.whole(-1, 5) }
        assertFailsWith<IllegalArgumentException> { Percent.tenths(1, -5) }
    }

    @Test
    fun bengaliDigitsWithLatinPercentAndSeparators() {
        assertEquals("৪০%", Percent.wholeText(2, 5, bn))
        assertEquals("২.৪%", Percent.text(24, 1, bn))
        assertEquals("১০৪.৯৪%", Percent.text(10_494, 2, bn))
        assertEquals("১,২৩৪.৫%", Percent.text(12_345, 1, bn))
    }

    @Test
    fun textKeepsExactlyTheDecimalsAndGroupsTheWholePart() {
        assertEquals("0.0%", Percent.text(0, 1, en))
        assertEquals("0.05%", Percent.text(5, 2, en))
        assertEquals("287.6%", Percent.text(2_876, 1, en))
        assertEquals("1,234.5%", Percent.text(12_345, 1, en))
        assertEquals("12,345%", Percent.text(12_345, 0, en))
        assertEquals("-0.5%", Percent.text(-5, 1, en)) // not in the domain, but never printed as "0.5%"
        assertFailsWith<IllegalArgumentException> { Percent.text(1, 5, en) }
        assertFailsWith<IllegalArgumentException> { Percent.text(1, -1, en) }
    }

    @Test
    fun longBoundsOfTheContractFitAndOverflowThrows() {
        // The contract bound: a sales ring takes n = A x W with A <= 10^10 and W <= 31.
        val n = 10_000_000_000L * 31L
        assertEquals(31_000_000_000_000L, Percent.whole(n, 1))
        assertEquals(310_000_000_000_000L, Percent.tenths(n, 1))
        assertEquals(3_100_000_000_000_000L, Percent.bp(n, 1))
        assertEquals(1_000L, Percent.tenths(n, n))
        assertEquals(10_000L, Percent.bp(Long.MAX_VALUE / 20_001L, Long.MAX_VALUE / 20_001L)) // 20000 n + d just fits
        // Beyond Long: an exception, never a wrapped (negative or tiny) percentage.
        assertFailsWith<ArithmeticException> { Percent.bp(Long.MAX_VALUE / 10_000L, 1) }
        assertFailsWith<ArithmeticException> { Percent.whole(1, Long.MAX_VALUE) }
    }
}
