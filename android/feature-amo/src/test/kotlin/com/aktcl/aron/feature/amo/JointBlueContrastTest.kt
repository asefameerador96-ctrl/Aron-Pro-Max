package com.aktcl.aron.feature.amo

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** D-PAR-AMO-17: the SR call assessment's item titles, hints, stars and the Joint pick prompt are the AMO blue; it holds 7:1 on the white card and on the page, in the AMO look and in Sunlight. */
class JointBlueContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
    private fun check(name: String, look: AronLookColors) {
        val blue = look.choiceFill ?: look.purple
        assertTrue("$name on the card", contrast(blue, look.card) >= 7.0)
        assertTrue("$name on the page", contrast(blue, look.page) >= 7.0)
    }
    @Test fun amoLook() = check("AmoPurple", AronLook.AmoPurple)
    @Test fun amoSunlight() = check("AmoSunlight", AronLook.AmoSunlight)
}
