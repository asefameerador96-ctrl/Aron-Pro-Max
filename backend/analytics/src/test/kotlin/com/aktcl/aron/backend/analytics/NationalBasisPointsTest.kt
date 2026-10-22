package com.aktcl.aron.backend.analytics

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Percentages on the dashboard contract are integer basis points (10000 = 100 %), half up, null on a zero denominator; the brief's sample figures (docs/ui-reference/web/dashboard.md). */
class NationalBasisPointsTest {
    @Test
    fun theBriefsSampleFiguresInBasisPoints() {
        assertEquals(9981, pctBp(6_920, 6_933))                    // Login 99.8 %
        assertEquals(9, pctBp(6, 6_920))                           // Submit 0.1 % (8.7 bp)
        assertEquals(3783, pctBp(174_000, 460_000))                // Live CPR 37.8 %
        assertEquals(9800, pctBp(196_000, 200_000))                // Geo Fencing 98 %
        assertEquals(7249, pctBp(26_400_000, 36_420_000))          // By Channel STD, GT 72.5 %
        assertEquals(0, pctBp(0, 1_046))                           // Final Submit 0.0 %
        assertEquals(4853L, ratioBp(BigDecimal(36_400_000), BigDecimal(75_000_000)))   // Sales (Cigarette) ring 48.5 %
        assertEquals(12_500L, ratioBp(BigDecimal(5), BigDecimal(4)))                  // a ring may pass 100 %
    }

    @Test
    fun roundsHalfUpAndRefusesAZeroDenominator() {
        assertEquals(1, pctBp(1, 20_000))                          // 0.5 bp rounds up
        assertEquals(0, pctBp(1, 20_001))
        assertEquals(3333, pctBp(1, 3)); assertEquals(6667, pctBp(2, 3))
        assertNull(pctBp(5, 0)); assertNull(pctBp(0, 0))
    }

    @Test
    fun pinColourThresholdsAre7000And9000BasisPoints() {
        assertEquals("amber", pinColor(7_000, 10_000)); assertEquals("red", pinColor(6_999, 10_000))
        assertEquals("amber", pinColor(9_000, 10_000)); assertEquals("green", pinColor(9_001, 10_000))
    }
}
