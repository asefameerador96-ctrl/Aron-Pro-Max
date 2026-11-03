package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.ui.aron.NightGlowRise
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bright-blue bottom dome (white on it is 2.76:1) stays inside the band a TsoFrame keeps free of content, so no text scrolls over it. */
class TsoGlowRiseTest {
    @Test fun theBottomDomeRisesNoHigherThanTheContentFreeBand() {
        assertTrue("dome ${NightGlowRise} must not exceed the band ${TsoFabBand}", NightGlowRise <= TsoFabBand)
    }
}
