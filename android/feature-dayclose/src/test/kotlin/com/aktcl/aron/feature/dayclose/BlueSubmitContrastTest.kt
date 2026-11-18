package com.aktcl.aron.feature.dayclose

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * D-PAR-AMO-22: the AMO Sales submit button (screenshot 65). Enabled: a white label on the AMO navy holds 7:1. Disabled: the grey is a "not yet possible"
 * cue, but the word must stay readable: the look's muted ink on the disabled grey holds 4.5:1 (the earlier 0xA5A5A5 label was about 2:1).
 */
class BlueSubmitContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    private fun check(name: String, look: AronLookColors) {
        assertTrue("$name: enabled label on the navy", contrast(Color.White, look.choiceFill ?: look.purple) >= 7.0)
        assertTrue("$name: disabled label on the disabled grey", contrast(look.inkMuted, look.disabledFill) >= 4.5)
    }
    @Test fun amoLook() = check("AmoPurple", AronLook.AmoPurple)
    @Test fun amoSunlight() = check("AmoSunlight", AronLook.AmoSunlight)
}
