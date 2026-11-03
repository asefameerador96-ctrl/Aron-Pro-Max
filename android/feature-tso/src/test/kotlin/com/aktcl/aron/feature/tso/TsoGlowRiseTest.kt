package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.ui.aron.NightGlowRise
import com.aktcl.aron.core.ui.aron.nightDomeSquash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bright-blue bottom dome (white on it is 2.76:1) stays inside the band a TsoFrame keeps free of content, so no text scrolls over it:
 * the dome's drawn height (the squash maths of nightPage) is the rise, and the content's bottom padding on a Bottom-glow page
 * (TsoFabBand with a Home disc, or with no bar, see TsoFrame) is at least that rise.
 */
class TsoGlowRiseTest {
    @Test fun theDrawnDomeIsNoHigherThanTheContentPaddingOnGlowPages() {
        val density = 2.75f
        val w = 360f * density
        val drawnHeightDp = 0.43f * w * nightDomeSquash(w, NightGlowRise.value * density) / density
        assertEquals(NightGlowRise.value, drawnHeightDp, 0.01f)
        assertTrue("content padding ${TsoFabBand} must be at least the dome height ${drawnHeightDp} dp", TsoFabBand.value >= drawnHeightDp)
    }
}
