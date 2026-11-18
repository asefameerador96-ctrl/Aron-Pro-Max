package com.aktcl.aron.feature.amo

import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.AronLook
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * D-PAR-AMO-31 (lead's low on slices 10 to 16): the drawn update art. The ribbon letters are white read against their navy outline (the orange alone would give 2.6:1,
 * which is why the outline is there); the cloud's arrow is white on the cloud purple and the cloud itself stands on the page: all at 7:1 or better, from the tokens.
 * The Reports tile pictures are decoration under a caption and carry no information.
 */
class AmoDrawnArtContrastTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun ribbonLettersOnTheirOutline() = assertTrue(contrast(Color.White, UpdateRibbonNavy) >= 7.0)
    @Test fun cloudArrowOnTheCloud() = assertTrue(contrast(Color.White, UpdateCloudPurple) >= 7.0)
    @Test fun cloudOnThePage() {
        assertTrue(contrast(UpdateCloudPurple, AronLook.AmoPurple.page) >= 7.0)
        assertTrue(contrast(UpdateCloudPurple, AronLook.AmoSunlight.page) >= 7.0)
    }

    /** The selected letter of the task form's alphabet bar (D-PAR-AMO-32): black on the mint in the normal look, white on the deep green in sunlight; the others are white on the dark panel. */
    @Test fun alphabetBarLetters() {
        assertTrue(contrast(Color.Black, AronLook.AmoPurple.goEnd) >= 7.0)
        assertTrue(contrast(Color.White, AronLook.AmoSunlight.goEnd) >= 7.0)
        assertTrue(contrast(Color.White, Color(0xFF4F4F4F)) >= 7.0)
    }
}
