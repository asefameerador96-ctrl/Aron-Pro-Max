package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** D-PAR-AMO-13: the AMO "Total sales" block keeps every figure at 7:1 on its solid surface: white on the green tab and the charcoal head, the discount red on the card, the ink on the grey total row. */
class SummarySalesBlockContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    private fun check(name: String, look: AronLookColors) {
        val green = look.primaryStart ?: look.purple
        val red = look.stopFill ?: look.red
        assertTrue("$name: white on the green tab", contrast(Color.White, green) >= 7.0)
        assertTrue("$name: white on the charcoal head", contrast(Color.White, Color(0xFF2B2B2B)) >= 7.0)
        assertTrue("$name: discount red on the card", contrast(red, look.card) >= 7.0)
        assertTrue("$name: ink on the grey total row", contrast(look.ink, look.rowAlt) >= 7.0)
        assertTrue("$name: ink on the card", contrast(look.ink, look.card) >= 7.0)
    }

    @Test fun amoLook() = check("AmoPurple", AronLook.AmoPurple)
    @Test fun amoSunlight() = check("AmoSunlight", AronLook.AmoSunlight)
}
