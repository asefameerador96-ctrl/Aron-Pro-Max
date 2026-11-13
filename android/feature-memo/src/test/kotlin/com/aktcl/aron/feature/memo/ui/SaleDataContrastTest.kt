package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** P-SR2: the total band of "সেল ডাটা" keeps white text at 7:1 or better (a key figure sits on it). */
class SaleDataContrastTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun whiteOnTheLilacBandIsAtLeastSevenToOne() {
        assertTrue(contrast(Color.White, LilacBand) >= 7.0)
    }
}
