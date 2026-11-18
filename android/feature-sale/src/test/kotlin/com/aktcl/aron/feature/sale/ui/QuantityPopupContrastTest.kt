package com.aktcl.aron.feature.sale.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** P-SR1b: the stats figures on the pop-up's amber panel meet 7:1 on both ends of its gradient. */
class QuantityPopupContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun statsInkOnTheTopOfTheAmber() = assertTrue(contrast(StatsInk, StatsTop) >= 7.0)
    @Test fun statsInkOnTheBottomOfTheAmber() = assertTrue(contrast(StatsInk, StatsBottom) >= 7.0)
    @Test fun purpleQuickLabelOnWhite() = assertTrue(contrast(com.aktcl.aron.core.ui.AronLook.Default.purple, Color.White) >= 7.0)
}
