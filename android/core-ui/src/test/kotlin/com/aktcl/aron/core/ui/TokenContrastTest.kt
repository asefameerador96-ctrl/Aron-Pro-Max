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
    /** The 96 percent field surface over every page stop: every SR/AMO/TSO card, row and input (tokens.md s2.2). */
    private fun AronColorRoles.fields() = pageStops().map { over(surfaceField, it) }
    private fun AronColorRoles.fills() = listOf(accentHi, accentFill, accentPressed)
    /** Glass over every page stop (a card on the page, the worst case behind translucent content). */
    private fun AronColorRoles.glassOnPage() = pageStops().map { over(surfaceGlass, it) }
    /** Tier B bar: 92 percent strong glass over the worst backdrop (black or white content scrolling behind). */
    private fun AronColorRoles.barOnBackdrop() = listOf(Color.Black, Color.White).map { over(surfaceGlassStrong, it) }

    @Test fun keyFiguresReachAAAEverywhere() = modes.forEach { m ->
        (m.solids() + m.fields() + m.glassOnPage() + m.barOnBackdrop()).forEach { check(m, "textPrimary", m.textPrimary, it, 7.0) }
        // the primary button label is a key figure (docs/32 s2a item 3): 7:1 on every fill state, and on the destructive fill
        m.fills().forEach { check(m, "primary label on fill", m.textOnAccent, it, 7.0) }
        check(m, "label on dangerFill", m.textOnAccent, m.dangerFill, 7.0)
    }

    @Test fun sunlightKeyFiguresReachTenToOne() {
        val m = AronTokens.Sunlight
        m.solids().forEach { check(m, "textPrimary", m.textPrimary, it, 10.0) }
    }

    @Test fun bodyTextIsSevenToOneOnTierBSurfaces() = modes.forEach { m ->
        (m.glassOnPage() + m.barOnBackdrop() + m.solids() + m.fields()).forEach { check(m, "body (textPrimary)", m.textPrimary, it, 7.0) }
    }

    @Test fun secondaryTextIsAtLeastFourPointFive() = modes.forEach { m ->
        (m.solids() + m.fields() + m.glassOnPage() + m.barOnBackdrop()).forEach { check(m, "textSecondary", m.textSecondary, it, 4.5) }
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
        m.solids().forEach { check(m, "textPlaceholder", m.textPlaceholder, it, 4.5) }
    }

    @Test fun accentTextAndIconsHoldTheirGates() = modes.forEach { m ->
        (m.solids() + m.fields()).forEach {
            check(m, "accentText", m.accentText, it, 4.5); glareCheck(m, "accentText", m.accentText, it, 3.0)
            check(m, "accent icon", m.accent, it, 3.0)
        }
    }

    @Test fun inputBordersAndFocusRingAreVisible() = modes.forEach { m ->
        m.solids().forEach { check(m, "borderInput", m.borderInput, it, 3.0); check(m, "borderFocus", m.borderFocus, it, 3.0) }
    }

    @Test fun glareProxyKeyFiguresStayAboveFourPointFive() = modes.forEach { m ->
        (m.solids() + m.fields()).forEach { glareCheck(m, "key figure on card", m.textPrimary, it, 4.5) }
        (m.solids() + m.fields()).forEach { glareCheck(m, "secondary on card", m.textSecondary, it, 3.0) }
        // The ONLY 3:1 glare exception (docs/32 s2a item 4): the primary action label, large bold type (>= 18 sp, see PrimaryLabelRuleTest)
        // on a filled accent. Nothing else may use it; every other text keeps 4.5:1 key figures and 3:1 body under glare.
        m.fills().forEach { glareCheck(m, "primary label on fill", m.textOnAccent, it, 3.0) }
        // tokens.md s2.7 does better than the exception: the deep fills keep 4.5:1 under glare
        m.fills().forEach { glareCheck(m, "primary label on fill (ledger)", m.textOnAccent, it, 4.5) }
        glareCheck(m, "label on dangerFill", m.textOnAccent, m.dangerFill, 4.5)
    }

    @Test fun glareProxyBodyTextStaysAboveThree() = modes.forEach { m ->
        (m.solids() + m.fields() + m.glassOnPage() + m.barOnBackdrop()).forEach { glareCheck(m, "body", m.textPrimary, it, 3.0) }
    }

    @Test fun textOnTheBarePageKeepsItsGates() = modes.forEach { m ->
        // section labels and captions sit on the bare gradient (tokens.md s2.7): body 7:1, secondary 4.5:1, raw
        m.pageStops().forEach { check(m, "textPrimary on page", m.textPrimary, it, 7.0); check(m, "textSecondary on page", m.textSecondary, it, 4.5) }
    }

    // ---- owner 2026-10-17: vibrant palette, green yes and red no, ten tile hues --------------------------------------------------------

    @Test fun yesAndNoButtonsCarryASevenToOneLabelAndSurviveGlare() = modes.forEach { m ->
        listOf("yes" to m.yesFill, "no" to m.noFill).forEach { (name, fill) ->
            check(m, "label on $name fill", m.textOnAccent, fill, 7.0)
            glareCheck(m, "label on $name fill", m.textOnAccent, fill, 3.0)   // the documented 3:1 exception: 18 sp bold primary-action label (AronYesButton and AronNoButton use titleMedium)
        }
    }

    @Test fun yesIsGreenAndNoIsRed() = modes.forEach { m ->
        assertTrue("${m.mode} yes must be green", m.yesFill.green > m.yesFill.red && m.yesFill.green > m.yesFill.blue)
        assertTrue("${m.mode} no must be red", m.noFill.red > m.noFill.green && m.noFill.red > m.noFill.blue)
    }

    @Test fun everyTileHueReadsAtSevenToOneAndTilesCoverTenHues() = listOf(AronMode.Light, AronMode.Dark, AronMode.Sunlight).forEach { mode ->
        val m = AronTokens.forMode(mode)
        val hues = AronTokens.Hues.forMode(mode)
        assertTrue("$mode needs at least ten tile hues", hues.size >= 10)
        hues.forEachIndexed { i, h ->
            check(m, "tile hue $i ink on container", h.ink, h.container, 7.0)
            glareCheck(m, "tile hue $i ink on container", h.ink, h.container, 3.0)   // a tile label is body text: 3:1 under glare (docs/32 s2a item 4)
        }
        val buckets = hues.map { h ->
            val mx = maxOf(h.container.red, h.container.green, h.container.blue); val mn = minOf(h.container.red, h.container.green, h.container.blue)
            val d = mx - mn
            val hue = when {
                d == 0f -> 0f
                mx == h.container.red -> ((h.container.green - h.container.blue) / d + 6) % 6
                mx == h.container.green -> (h.container.blue - h.container.red) / d + 2
                else -> (h.container.red - h.container.green) / d + 4
            } * 60f
            (hue / 24f).toInt()
        }.toSet()
        assertTrue("$mode tile hues must span at least eight 24-degree hue buckets, got ${buckets.size}", buckets.size >= 8)
    }

    @Test fun theVibrantPaletteHasAtLeastTenDistinctColours() {
        val m = AronTokens.Light
        val colours = listOf(
            m.bgGradientTop, m.bgGradientMid, m.bgGradientBottom, m.accent, m.accentHi, m.accentContainer, m.success, m.successContainer,
            m.warning, m.warningContainer, m.danger, m.dangerContainer, m.offline, m.offlineContainer, m.yesFill, m.noFill,
        ).map { Triple((it.red * 255).toInt() / 16, (it.green * 255).toInt() / 16, (it.blue * 255).toInt() / 16) }.toSet()
        assertTrue("distinct colours: ${colours.size}", colours.size >= 10)
    }

    // ---- tso-manual A1: the TSO look (D-TSO-MAN-13), computed on the night and the Sunlight roles ---------------------------------

    private fun night(what: String, fg: Color, bg: Color, target: Double) {
        val ratio = contrast(over(fg, bg), bg)
        assertTrue("TsoNight $what: ${"%.2f".format(ratio)} < $target", ratio >= target)
    }
    private val tsoRoles = listOf("night" to AronNightRoles.Night, "sunlight" to AronNightRoles.Sunlight)

    @Test fun tsoKeyFiguresReachSevenToOne() = tsoRoles.forEach { (name, n) ->
        // the cyan figures (40.0, 1.7 K, 4) on both stops of the glow card and on the outline card; white ink everywhere it is drawn
        listOf(n.glowCardStart, n.glowCardEnd, n.outlineCard, n.page).forEach { night("$name figure", n.figure, it, 7.0) }
        listOf(n.page, n.glowCardStart, n.glowCardEnd, n.outlineCard, n.rowCard, n.field, n.drawer, n.dialogTop, n.dialogBottom).forEach {
            night("$name ink", n.ink, it, 7.0)
        }
        // the gradient title on the page (both stops)
        listOf(n.titleStart, n.titleEnd).forEach { night("$name title", it, n.page, 7.0) }
    }

    @Test fun tsoButtonLabelsReachSevenToOne() = tsoRoles.forEach { (name, n) ->
        listOf(n.cyanStart, n.cyanEnd, n.limeStart, n.limeEnd, n.cancel, n.logoutStart, n.logoutEnd).forEach { night("$name label on bright fill", n.onBright, it, 7.0) }
        night("$name label on blue", n.onBlue, n.blue, 7.0)
        night("$name label on the selected pill", n.onBlue, n.pillOn, 7.0)
    }

    @Test fun tsoBodyAndCaptionsReachFourPointFive() = tsoRoles.forEach { (name, n) ->
        listOf(n.page, n.outlineCard, n.rowCard, n.drawer, n.dialogBottom).forEach { night("$name inkMuted", n.inkMuted, it, 4.5) }
        listOf(n.glowCardStart, n.glowCardEnd).forEach { night("$name card label", n.label, it, 4.5) }
        listOf(n.positive, n.teal, n.success, n.warning, n.danger).forEach { night("$name status ink on the outline card", it, n.outlineCard, 4.5) }
        night("$name placeholder", n.placeholder, n.field, 4.5)
        night("$name chip", n.chipInk, n.chip, 4.5)
        night("$name segment", n.onSegment, n.segmentOn, 4.5)
        night("$name badge digits", n.onBadge, n.badge, 4.5)
    }

    @Test fun tsoGraphicsReachThreeToOne() = tsoRoles.forEach { (name, n) ->
        night("$name back chevron", n.backInk, n.backButton, 3.0)
        night("$name back button on the page", n.backButton, n.page, if (n.solid) 3.0 else 1.5)
        night("$name home glyph", n.onFab, n.fab, 3.0)
        night("$name drawer chevron", n.chevron, n.drawer, 3.0)
        night("$name field edge", n.fieldEdge, n.page, 3.0)
        night("$name ring fill on the glow card", n.ringFill, n.glowCardEnd, 3.0)
        night("$name ring fill on the outline card", n.ringFill, n.outlineCard, 3.0)
        night("$name bar fill on its track", n.barFillEnd, n.barTrack, 3.0)
        night("$name value line", n.valueLine, n.outlineCard, 3.0)
        night("$name volume line", n.volumeLine, n.outlineCard, 3.0)
    }

    @Test fun tsoSunlightIsSolidAndTenToOne() {
        val n = AronNightRoles.Sunlight
        assertTrue(n.solid)
        listOf(n.page, n.outlineCard, n.glowCardStart, n.field, n.drawer).forEach { night("sunlight ink", n.ink, it, 10.0); night("sunlight figure", n.figure, it, 10.0) }
        assertTrue(AronLook.TsoSunlight.sunlight && AronLook.TsoSunlight.night == AronNightRoles.Sunlight)
        assertTrue(!AronLook.TsoNight.sunlight && AronLook.TsoNight.night == AronNightRoles.Night)
    }

    @Test fun tsoSharedKitRolesHoldTheirGates() {
        val l = AronLook.TsoNight
        listOf(l.page, l.card, l.panel, l.tileMenu).forEach { night("kit ink", l.ink, it, 7.0) }
        listOf(l.page, l.card).forEach { night("kit inkMuted", l.inkMuted, it, 4.5); night("kit red", l.red, it, 4.5); night("kit purple text", l.purple, it, 4.5) }
        listOf(l.barStart, l.barMid, l.barEnd).forEach { night("kit label on the primary ramp", l.onBar, it, 7.0) }
        night("kit secondary button label", l.secondaryText, l.disabledFill, 7.0)
        night("kit notice", l.ink, l.noticeFill, 7.0)
    }

    // tso-manual A1 step 3: the Target Status bars. The printed percent carries the figure (danger, warning, success inks, 4.5:1 on
    // the card above); the band fill must stand out from the card at 3:1. On the night card the bright amber of screenshot 39 sits
    // on the light track at 1.4:1 (the screenshot's colours; the percent beside it is the figure); Sunlight also holds 3:1 on the track.
    @Test fun tsoTargetBandsReachThreeToOne() = tsoRoles.forEach { (name, n) ->
        listOf(n.bandRed, n.bandAmber, n.bandGreen).forEach { night("$name band fill on the card", it, n.outlineCard, 3.0) }
        if (n.solid) listOf(n.bandRed, n.bandAmber, n.bandGreen).forEach { night("$name band fill on its track", it, n.barTrack, 3.0) }
        assertTrue("$name bands distinct", setOf(n.bandRed, n.bandAmber, n.bandGreen).size == 3)
    }
}
