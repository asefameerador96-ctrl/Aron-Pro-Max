package com.aktcl.aron.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-PAR-SR1-07: the white key figures on the second money panel hold 7:1 at BOTH ends of its gradient (the net `মোট টাকা` sits at the end). */
class DiscountPanelContrastTest {
    private fun ratio(bg: Color): Double = (Color.White.luminance() + 0.05).toDouble() / (bg.luminance() + 0.05).toDouble()

    @Test fun whiteOnTheStartOfTheRampHoldsSevenToOne() = assertTrue("start ${ratio(DiscountPanelStart)}", ratio(DiscountPanelStart) >= 7.0)
    @Test fun whiteOnTheEndOfTheRampHoldsSevenToOne() = assertTrue("end ${ratio(DiscountPanelEnd)}", ratio(DiscountPanelEnd) >= 7.0)
}
