package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Qty.text (build contract s4.1, s4.2; D-TSO-MAN-10): the Sales card fixtures, the K boundary, Bengali digits. */
class QtyTest {
    private val en = UiLocale.EN
    private val bn = UiLocale.BN

    @Test
    fun salesCardFixturesOfScreenshot6() {
        // Total Sales = Qty.text(A); Total Target = Qty.text(T, W) with W = 25 working days.
        assertEquals("40.0", Qty.text(40, en))
        assertEquals("1.7 K", Qty.text(42_000, 25, en)) // 1,680 a day
        assertEquals("3.0 K", Qty.text(2_991, en))
        assertEquals("1.0 K", Qty.text(26_000, 25, en)) // 1,040 a day
        assertEquals("0.0", Qty.text(0, en))
        assertEquals("4.0", Qty.text(100, 25, en))
        assertEquals("7.0", Qty.text(175, 25, en))
    }

    @Test
    fun missingTargetOrZeroDivisorPrintsADash() {
        assertEquals("—", Qty.text(null, en))
        assertEquals("—", Qty.text(null, 25, bn))
        assertEquals("—", Qty.text(175, 0, en))
        assertNull(Qty.tenths(175, 0))
    }

    @Test
    fun kBoundary() {
        assertEquals("999.0", Qty.text(999, en))
        assertEquals("999.9", Qty.text(9_999, 10, en)) // 999.9
        assertEquals("999.9", Qty.text(999_949, 1_000, en)) // 999.949
        assertEquals("1.0 K", Qty.text(19_999, 20, en)) // 999.95 rounds to 1,000.0, which prints in K
        assertEquals("1.0 K", Qty.text(1_000, en))
        assertEquals("1.0 K", Qty.text(1_049, en)) // 1.049 K
        assertEquals("1.1 K", Qty.text(1_050, en)) // 1.05 K, half up
        assertEquals("1,234.6 K", Qty.text(1_234_567, en))
        assertEquals("999.9 K", Qty.text(999_949, en))
        assertEquals("1,000.0 K", Qty.text(999_950, en)) // no M: thousands of K stay K, grouped
    }

    @Test
    fun kIsRoundedOnceFromTheQuantityNotFromTheRoundedTenths() {
        // 1,049.96 a day over 25 days: tenths 10500 (1,050.0) would round to 1.1 K; from n the value is 1.04996 K -> 1.0 K.
        assertEquals(10_500L, Qty.tenths(26_249, 25))
        assertEquals("1.0 K", Qty.text(26_249, 25, en))
    }

    @Test
    fun bengaliDigitsKeepLatinK() {
        assertEquals("৪০.০", Qty.text(40, bn))
        assertEquals("১.৭ K", Qty.text(42_000, 25, bn))
        assertEquals("১,২৩৪.৬ K", Qty.text(1_234_567, bn))
    }

    @Test
    fun formatsDelegateIsTheSameRule() {
        assertEquals(Qty.text(1_234_567, en), Formats.qty(1_234_567, en))
        assertEquals(Percent.DASH, Formats.qty(null, bn))
    }

    @Test
    fun negativeQuantityIsACallerBugAndOverflowThrows() {
        assertFailsWith<IllegalArgumentException> { Qty.text(-1, en) }
        assertFailsWith<IllegalArgumentException> { Qty.text(1, -1, en) }
        assertFailsWith<ArithmeticException> { Qty.text(Long.MAX_VALUE / 10L, en) }
        // The largest quantities a dashboard can carry (10^13 base units) print exactly.
        assertEquals("10,000,000,000.0 K", Qty.text(10_000_000_000_000L, en))
    }
}
