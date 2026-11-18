package com.aktcl.aron.feature.sale.ui

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** P-SR1b fix round: the partial-payment box's small text holds 7:1 on its lavender tint, and the SR permission title on the grey page. */
class CreditDialogContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun purpleLabelOnTheTint() = assertTrue(contrast(AronLook.Default.purple, PopupTint) >= 7.0)
    @Test fun errorInkOnTheTint() = assertTrue(contrast(CreditErrorInk, PopupTint) >= 7.0)
    @Test fun inkOnTheTint() = assertTrue(contrast(AronLook.Default.ink, PopupTint) >= 7.0)
    @Test fun purpleTitleOnThePage() = assertTrue(contrast(AronLook.Default.purple, AronLook.Default.page) >= 7.0)
    // Sunlight: the box is the solid surface and the error line keeps the look's red
    @Test fun sunlightErrorInkOnTheSolidSurface() = assertTrue(contrast(AronLook.Sunlight.red, Color.White) >= 7.0)
}
