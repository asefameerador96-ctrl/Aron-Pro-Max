package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** AMO build contract s4.3: today's target = monthly / working days, half up, at least 1. */
class CallTargetsTest {
    @Test fun unsetStaysUnset() = assertNull(CallTargets.todayTarget(null, 26))
    @Test fun zeroStaysZero() = assertEquals(0L, CallTargets.todayTarget(0L, 26))
    @Test fun exactDivision() = assertEquals(10L, CallTargets.todayTarget(260L, 26))
    @Test fun halfRoundsUp() {
        assertEquals(2L, CallTargets.todayTarget(39L, 26))   // 1.5 -> 2
        assertEquals(1L, CallTargets.todayTarget(38L, 26))   // 1.46 -> 1
        assertEquals(12L, CallTargets.todayTarget(300L, 26)) // 11.54 -> 12
    }
    @Test fun neverBelowOneForAPositiveTarget() {
        assertEquals(1L, CallTargets.todayTarget(1L, 26))
        assertEquals(1L, CallTargets.todayTarget(5L, 26))
    }
    @Test fun aMonthWithoutWorkingDaysCountsAsOne() = assertEquals(40L, CallTargets.todayTarget(40L, 0))
    @Test fun negativeIsRefused() { assertFailsWith<IllegalArgumentException> { CallTargets.todayTarget(-1L, 26) } }
}
