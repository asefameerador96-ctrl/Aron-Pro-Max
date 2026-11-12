package com.aktcl.aron.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** D-SR-MAN-S3-02: the green connected-printer badge keeps its contrast on the badge chip in every look that draws it. */
class PrinterBadgeContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun greenOnTheSrAndAmoChip() = assertTrue(contrast(AronLook.Default.printerOn, AronLook.Default.card) >= 4.5)
    @Test fun greenOnTheSunlightChip() = assertTrue(contrast(AronLook.Sunlight.printerOn, AronLook.Sunlight.card) >= 7.0)
    @Test fun theTsoNightBadgeIsUnchanged() {
        assertTrue(AronLook.TsoNight.printerOn == Color(0xFFB9A6FF))
        assertTrue(contrast(AronLook.TsoNight.printerOn, AronLook.TsoNight.card) >= 4.5)
    }
    @Test fun theBadgeIsGreenNotPurple() { val c = AronLook.Default.printerOn; assertTrue(c.green > c.red && c.green > c.blue) }
}
