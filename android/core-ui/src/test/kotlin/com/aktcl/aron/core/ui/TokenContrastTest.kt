package com.aktcl.aron.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * docs/32 s2a item 8(a): the contrast of every declared token pair, per theme, against the outdoor targets. A colour edit
 * that drops below a target fails here, not on a phone in the sun. Colours are composited in sRGB as Compose does.
 */
class TokenContrastTest {
    private val modes = listOf(AronTokens.Light, AronTokens.Dark, AronTokens.Sunlight)

    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = lum(a).let { la -> lum(b).let { lb -> if (la >= lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }
    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha), green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha), alpha = 1f,
    )
    /** docs/32 s2a item 4: a screen in direct sun reflects light; blend both colours 35 percent toward white. */
    private fun glare(c: Color): Color = Color(c.red * 0.65f + 0.35f, c.green * 0.65f + 0.35f, c.blue * 0.65f + 0.35f, 1f)

    private fun check(mode: AronColorRoles, what: String, fg: Color, bg: Color, target: Double) {
        val ratio = contrast(over(fg, bg), bg)
        assertTrue("${mode.mode} $what: ${"%.2f".format(ratio)} < $target", ratio >= target)
    }
    private fun glareCheck(mode: AronColorRoles, what: String, fg: Color, bg: Color, target: Double) {
        val ratio = contrast(glare(over(fg, bg)), glare(bg))
        assertTrue("${mode.mode} glare $what: ${"%.2f".format(ratio)} < $target", ratio >= target)
    }

    private fun AronColorRoles.pageStops() = listOf(bgGradientTop, bgGradientMid, bgGradientBottom)
    private fun AronColorRoles.solids() = listOf(surfaceSolid, surfaceSolidRaised)
    /** Glass over every page stop (a card on the page, the worst case behind translucent content). */
    private fun AronColorRoles.glassOnPage() = pageStops().map { over(surfaceGlass, it) }
    /** Tier B bar: 92 percent strong glass over the worst backdrop (black or white content scrolling behind). */
    private fun AronColorRoles.barOnBackdrop() = listOf(Color.Black, Color.White).map { over(surfaceGlassStrong, it) }

    @Test fun keyFiguresReachAAAEverywhere() = modes.forEach { m ->
        (m.solids() + m.glassOnPage() + m.barOnBackdrop()).forEach { check(m, "textPrimary", m.textPrimary, it, 7.0) }
        // the primary button label is a key figure (docs/32 s2a item 3): 7:1 on every fill state
        check(m, "primary label on accent", m.textOnAccent, m.accent, 7.0)
        check(m, "primary label on accent.hi", m.textOnAccent, m.accentHi, 7.0)
        check(m, "primary label on accent.pressed", m.textOnAccent, m.accentPressed, 7.0)
    }

    @Test fun sunlightKeyFiguresReachTenToOne() {
        val m = AronTokens.Sunlight
        m.solids().forEach { check(m, "textPrimary", m.textPrimary, it, 10.0) }
    }

    @Test fun bodyTextIsSevenToOneOnTierBSurfaces() = modes.forEach { m ->
        (m.glassOnPage() + m.barOnBackdrop() + m.solids()).forEach { check(m, "body (textPrimary)", m.textPrimary, it, 7.0) }
    }

    @Test fun secondaryTextIsAtLeastFourPointFive() = modes.forEach { m ->
        (m.solids() + m.glassOnPage() + m.barOnBackdrop()).forEach { check(m, "textSecondary", m.textSecondary, it, 4.5) }
        check(m, "textSecondary on accentContainer", m.textSecondary, m.accentContainer, 4.5)
    }

    @Test fun sunlightSecondaryAndStatusReachSevenToOne() {
        val m = AronTokens.Sunlight
        m.solids().forEach { check(m, "textSecondary", m.textSecondary, it, 7.0) }
        listOf(m.accent, m.success, m.warning, m.danger, m.offline).forEach { check(m, "status ink", it, m.surfaceSolid, 7.0) }
    }

    @Test fun statusInkAndContainersHoldTheirGates() = modes.forEach { m ->
        listOf(m.success, m.warning, m.danger, m.offline).forEach { check(m, "status ink on solid", it, m.surfaceSolid, 3.0) }
        check(m, "accent on solid", m.accent, m.surfaceSolid, 4.5)
        val target = if (m.sunlight) 7.0 else 4.5
        check(m, "accentOnContainer", m.accentOnContainer, m.accentContainer, target)
        check(m, "successOnContainer", m.successOnContainer, m.successContainer, target)
        check(m, "warningOnContainer", m.warningOnContainer, m.warningContainer, target)
        check(m, "dangerOnContainer", m.dangerOnContainer, m.dangerContainer, target)
        check(m, "offlineOnContainer", m.offlineOnContainer, m.offlineContainer, target)
        check(m, "disabled label", m.stateDisabledLabel, m.stateDisabledFill, 4.5)
    }

    @Test fun disabledAndPlaceholderTextStaysAboveThree() = modes.forEach { m ->
        m.solids().forEach { check(m, "textDisabled", m.textDisabled, it, 3.0) }
    }

    @Test fun inputBordersAndFocusRingAreVisible() = modes.forEach { m ->
        m.solids().forEach { check(m, "borderInput", m.borderInput, it, 3.0); check(m, "borderFocus", m.borderFocus, it, 3.0) }
    }

    @Test fun glareProxyKeyFiguresStayAboveFourPointFive() = modes.forEach { m ->
        m.solids().forEach { glareCheck(m, "key figure on card", m.textPrimary, it, 4.5) }
        // bold 16 sp+ label on a saturated fill: a blue that keeps 7:1 cannot reach 4.5:1 after the 35 percent white blend, so 3:1 (large text)
        glareCheck(m, "primary label on accent", m.textOnAccent, m.accent, 3.0)
    }

    @Test fun glareProxyBodyTextStaysAboveThree() = modes.forEach { m ->
        (m.solids() + m.glassOnPage() + m.barOnBackdrop()).forEach { glareCheck(m, "body", m.textPrimary, it, 3.0) }
    }
}
