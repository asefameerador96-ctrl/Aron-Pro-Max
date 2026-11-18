package com.aktcl.aron.core.printing.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** P-SR1b: the white sentence on the SR's green connected bar is 7:1 or better. */
class PrinterConnectedBarContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    @Test fun whiteOnTheGreen() = assertTrue((1.05) / (lum(PrinterBarGreen) + 0.05) >= 7.0)
}
