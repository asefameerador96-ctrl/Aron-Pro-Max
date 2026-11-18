package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StarProgressTest {
    // Screenshot 62: Target 20.00, STD 2.833 (2833 of 1000 per unit), EWD 10, WD 26, LMS 12.25, LMWD 26.
    private val shot = StarProgress.Input(targetMilli = 20_000, stdBase = 2_833, lmsBase = 12_250, unitDivisor = 1_000, ewd = 10, wd = 26, lmwd = 26)

    @Test fun reproducesTheScreenshot() {
        val r = StarProgress.compute(shot)
        assertEquals(2_833L, r.stdMilli); assertEquals(7_366L, r.projectionMilli, "7.3658 -> 7.366, shown 7.37")
        assertEquals(3_683L, r.achievementBp, "36.83%"); assertEquals(283L, r.padsMilli, "0.2833 -> 0.283, shown 0.28")
        assertEquals(12_250L, r.lmsMilli); assertEquals(471L, r.lmadsMilli, "0.47115 -> 0.471, shown 0.47")
        assertEquals(-3_987L, r.growthBp, "-39.87%")
        assertEquals(1_417L, r.actualBp, "2.833 of 20 = 14.17%")
    }

    @Test fun displayedValuesAtTwoDecimalsMatchTheScreenshot() {
        val r = StarProgress.compute(shot)
        fun two(m: Long) = java.math.BigDecimal.valueOf(m, 3).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        assertEquals("7.37", two(r.projectionMilli!!)); assertEquals("0.28", two(r.padsMilli!!)); assertEquals("0.47", two(r.lmadsMilli!!)); assertEquals("2.83", two(r.stdMilli)); assertEquals("12.25", two(r.lmsMilli))
    }

    @Test fun zeroDivisorsAreNullNeverZero() {
        val day1 = StarProgress.compute(shot.copy(ewd = 0))
        assertNull(day1.projectionMilli); assertNull(day1.achievementBp); assertNull(day1.padsMilli); assertNull(day1.growthBp)
        assertNull(StarProgress.compute(shot.copy(targetMilli = 0)).achievementBp)
        val noLast = StarProgress.compute(shot.copy(lmsBase = 0))
        assertNull(noLast.growthBp); assertEquals(0L, noLast.lmadsMilli)
        assertNull(StarProgress.compute(shot.copy(lmwd = 0)).lmadsMilli)
    }

    @Test fun roundsHalfAwayFromZero() {
        assertEquals(java.math.BigInteger.valueOf(-2), StarProgress.roundDiv(java.math.BigInteger.valueOf(-3), java.math.BigInteger.valueOf(2)))
        assertEquals(java.math.BigInteger.valueOf(2), StarProgress.roundDiv(java.math.BigInteger.valueOf(3), java.math.BigInteger.valueOf(2)))
    }

    @Test fun growthIsPositiveWhenThisMonthBeatsLast() {
        assertEquals(10_000L, StarProgress.compute(StarProgress.Input(1_000, 2_000, 1_000, 1, 10, 26, 10)).growthBp, "PADS 200 vs LMADS 100 = +100%")
    }

    @Test fun slabAndStatusPickTheHighestReachedRung() {
        val rungs = listOf(StarProgress.Rung("S1", 5_000, 1_000_000), StarProgress.Rung("S2", 8_000, 2_500_000), StarProgress.Rung("S3", 10_000, 4_000_000))
        assertNull(StarProgress.rungFor(rungs, 4_999)); assertNull(StarProgress.rungFor(rungs, null))
        assertEquals("S2", StarProgress.rungFor(rungs, 9_999)!!.label); assertEquals("S3", StarProgress.rungFor(rungs, 25_000)!!.label)
        assertEquals("Keep Pushing", StarProgress.status(emptyList(), 3_683).en); assertEquals("On Track", StarProgress.status(emptyList(), 10_000).en)
        assertEquals("Keep Pushing", StarProgress.status(emptyList(), null).en)
    }

    @Test fun anAbsurdPercentageIsNullNeverAWrappedNumber() {
        // target 0.001 against huge sales: the basis points do not fit a Long. Null (not representable), never the wrapped value of BigInteger.toLong().
        val r = StarProgress.compute(StarProgress.Input(targetMilli = 1, stdBase = Long.MAX_VALUE / 2_000, lmsBase = 10, unitDivisor = 1, ewd = 1, wd = 26, lmwd = 26))
        assertNull(r.achievementBp); assertNull(r.growthBp)
    }

    @Test fun twoIsHalfARoundingStepOnTheOldestSupportedApi() {
        // roundDiv uses BigInteger.valueOf(2) (BigInteger.TWO does not exist on Android below API 33): half away from zero still holds
        assertEquals(java.math.BigInteger.valueOf(3), StarProgress.roundDiv(java.math.BigInteger.valueOf(5), java.math.BigInteger.valueOf(2)))
        assertEquals(java.math.BigInteger.valueOf(-3), StarProgress.roundDiv(java.math.BigInteger.valueOf(-5), java.math.BigInteger.valueOf(2)))
        assertEquals(java.math.BigInteger.valueOf(1), StarProgress.roundDiv(java.math.BigInteger.valueOf(4), java.math.BigInteger.valueOf(3)))
    }
}
