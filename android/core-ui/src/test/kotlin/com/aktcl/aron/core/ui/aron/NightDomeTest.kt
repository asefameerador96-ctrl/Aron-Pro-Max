package com.aktcl.aron.core.ui.aron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom glow dome's geometry: its top is exactly NightGlowRise above the bottom edge on every phone width, never higher. */
class NightDomeTest {
    @Test fun theDomeTopIsExactlyTheRiseAboveTheBottomEdge() {
        for (widthDp in listOf(320f, 360f, 411f, 480f)) {
            val density = 3f
            val w = widthDp * density
            val rise = 72f * density
            val top = 0.43f * w * nightDomeSquash(w, rise) // height of the dome above the edge after the squash
            assertEquals("width $widthDp dp", rise, top, 0.01f)
        }
    }

    @Test fun aTinyWidthNeverRisesHigherThanTheUnsquashedCircle() {
        val w = 100f // 0.43 * 100 = 43 px is already below the 216 px rise: no stretching
        assertTrue(nightDomeSquash(w, 216f) <= 1f)
        assertEquals(1f, nightDomeSquash(w, 216f), 0f)
    }
}
