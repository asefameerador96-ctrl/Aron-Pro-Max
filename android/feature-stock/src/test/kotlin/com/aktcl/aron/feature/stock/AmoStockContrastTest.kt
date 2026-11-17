package com.aktcl.aron.feature.stock

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * D-PAR-AMO-11: the AMO Stock screen (screenshot 41) follows the old colours (blue-to-indigo totals panel, purple count badge) only while every
 * key figure reaches 7:1 on its solid surface: the totals figures on both ends of the panel ramp, the badge number on its purple disc, the panel
 * column words at AA on both ends (7 requested from core-ui, R-PAR-AMO-06), in the AMO look and in its Sunlight twin.
 */
class AmoStockContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    private fun check(name: String, look: AronLookColors) {
        val ramp = listOf(look.panelTop ?: look.panel, look.panelBottom ?: look.panelTop ?: look.panel)
        ramp.forEach { surface ->
            assertTrue("$name: totals figures on the panel", contrast(look.onPanel, surface) >= 7.0)
            // the small column words are not key figures: AA (4.5) is pinned here; the AMO blue end measures 6.1 and R-PAR-AMO-06 asks core-ui for 7
            assertTrue("$name: panel column words on the panel", contrast(look.onPanelMuted, surface) >= 4.5)
        }
        assertTrue("$name: badge number on its disc", contrast(Color.White, look.purple) >= 7.0)
        // Sunlight draws the panel white with black figures: the badge number is then still white on the dark purple disc
    }

    @Test fun amoLook() = check("AmoPurple", AronLook.AmoPurple)
    @Test fun amoSunlight() = check("AmoSunlight", AronLook.AmoSunlight)
}
