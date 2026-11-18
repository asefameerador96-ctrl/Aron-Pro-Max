package com.aktcl.aron.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** P-SR1b: the charcoal "না" / "রিফ্রেশ" button keeps its white label at 7:1 or better (rule 14, key actions on solid surfaces). */
class DarkButtonContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun whiteOnTheCharcoalFill() = assertTrue(contrast(Color.White, AronLook.DarkButtonFill) >= 7.0)
}
