package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/24 s4.14.2 and D24-68 defaults: 2 Tk a point, at most 199 cash points. */
class LoyaltyMathTest {
    @Test
    fun cashAndTotals() {
        assertEquals(398_000L, LoyaltyMath.cashMtk(199))
        assertEquals(1_000L, LoyaltyMath.cashMtk(1, 1_000))
        assertEquals(250L, LoyaltyMath.pointsTotal(50, listOf(100, 100)))
        assertTrue(LoyaltyMath.isConsistent(250, 100_000, 50, listOf(100, 100)))
        assertFalse(LoyaltyMath.isConsistent(251, 100_000, 50, listOf(100, 100)))
        assertFalse(LoyaltyMath.isConsistent(250, 100_001, 50, listOf(100, 100)))
        assertFalse(LoyaltyMath.isConsistent(200, 400_000, 200, emptyList()), "over cash_max_points")
    }
}
