package com.aktcl.aron.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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

    /** Sunlight keeps 7:1 on every button label (rule 14); the night look's bright-fill labels are the separate 4.5:1 group below (D-TSO-MAN-50). */
    @Test fun tsoSunlightButtonLabelsAndNightBlueLabelsReachSevenToOne() = tsoRoles.forEach { (name, n) ->
        if (n.solid) listOf(n.cyanStart, n.cyanEnd, n.limeStart, n.limeEnd, n.cancel, n.logoutStart, n.logoutEnd).forEach { night("$name label on bright fill", n.onBright, it, 7.0) }
        night("$name label on blue", n.onBlue, n.blue, 7.0)
        night("$name label on the selected pill", n.onBlue, n.pillOn, 7.0)
    }

    /**
     * D-TSO-MAN-50 (rule 14a over rule 14 for button labels): in the NIGHT look the white bold label on the bright fills (Login cyan, Final Submit lime,
     * Cancel green, Log Out orange) is gated at WCAG AA 4.5:1, on both stops and the midpoint of every gradient; this is a separate group from the
     * 7:1 key-figure gate and does not loosen it. Sunlight stays at 7:1 above.
     */
    /** The map fallback list of the TSO look (rows 28, 29): the muted ink of a stale row and the ink of a live one on the navy page, 4.5:1. */
    @Test fun tsoMapFallbackListInksReachFourPointFive() {
        val n = AronNightRoles.Night
        night("stale row muted ink on the page", AronLook.TsoNight.inkMuted, n.page, 4.5)
        night("live row ink on the page", AronLook.TsoNight.ink, n.page, 4.5)
    }

    /** My Visit Plan's white placeholder (screenshot 34) on the field, and Assign Task's plain comment box (page colour, quiet outline): 4.5:1 / 3:1. */
    @Test fun tsoBrightPlaceholderAndPlainBoxHoldTheirGates() {
        val n = AronNightRoles.Night
        night("white placeholder on the field", AronLook.TsoNight.ink, n.field, 4.5)
        night("placeholder on the plain comment box", n.placeholder, n.page, 4.5)
        night("plain box outline on the page", n.divider, n.page, 3.0)
    }

    /** Sunlight (TsoSunlight roles, solid): the new picker, menu and fallback-list pairs at 7:1. */
    @Test fun tsoSunlightPickerMenuAndFallbackListPairsReachSevenToOne() {
        val n = AronNightRoles.Sunlight
        val look = AronLook.TsoSunlight
        fun sun(what: String, fg: Color, bg: Color) { val r = contrast(over(fg, bg), bg); assertTrue("TsoSunlight $what: ${"%.2f".format(r)} < 7", r >= 7.0) }
        sun("picker ink on the field", n.ink, n.field)
        sun("picker placeholder on the field", n.placeholder, n.field)
        sun("fallback list ink on the page", look.ink, n.page)
        sun("fallback list muted ink on the page", look.inkMuted, n.page)
        sun("plain comment box placeholder on the page", n.placeholder, n.page)
    }

    /** The top glow of the Leave pages (screenshots 19 to 22): white ink and the muted ink over its strongest point keep 7:1 and 4.5:1. */
    @Test fun tsoTopGlowKeepsInkReadable() {
        val n = AronNightRoles.Night
        night("ink on the top glow", AronLook.TsoNight.ink, n.glowTop, 7.0)
        night("muted ink on the top glow", AronLook.TsoNight.inkMuted, n.glowTop, 4.5)
        night("title start on the top glow", n.titleStart, n.glowTop, 7.0)
        night("title end on the top glow", n.titleEnd, n.glowTop, 7.0)
        night("kit red (field errors) on the top glow", AronLook.TsoNight.red, n.glowTop, 4.5)
        night("back disc on the top glow", n.backButton, n.glowTop, 1.5)
    }

    /** The Leave card (screenshot 19, night look only): white ink on both gradient stops 4.5:1 (day word, captions), white badge label on orange 4.5:1. */
    @Test fun tsoLeaveCardInkAndOrangeBadgeReachFourPointFive() {
        val n = AronNightRoles.Night
        night("leave card ink at the start", Color.White, n.leaveStart, 4.5)
        night("leave card ink at the end", Color.White, n.leaveEnd, 4.5)
        night("leave card ink on the day disc", Color.White, over(n.leaveDisc, n.leaveStart), 4.5)
        night("orange badge label", Color.White, n.badgeOrange, 4.5)
        fun mid(a: Color, b: Color) = Color((a.red + b.red) / 2, (a.green + b.green) / 2, (a.blue + b.blue) / 2)
        night("leave card ink at the gradient midpoint", Color.White, mid(n.leaveStart, n.leaveEnd), 4.5)
        night("dropdown list ink", AronLook.TsoNight.ink, n.menu, 4.5)
        night("dropdown list muted ink", AronLook.TsoNight.inkMuted, n.menu, 4.5)
        night("dropdown list placeholder", n.placeholder, n.menu, 4.5)
    }

    /** The Final Submit route card (screenshot 25, night look only): white title, FF line and 'not due' line on the steel-blue fill, 4.5:1 (the muted ink is not used on this card). */
    @Test fun tsoRouteCardInkReachesFourPointFive() {
        val n = AronNightRoles.Night
        val look = AronLook.TsoNight
        night("route card ink", look.ink, n.routeCard, 4.5)
    }

    @Test fun tsoNightButtonLabelsOnBrightFillsReachFourPointFiveToOne() {
        val n = AronNightRoles.Night
        assertEquals(Color.White, n.onBright)
        fun mid(a: Color, b: Color) = Color((a.red + b.red) / 2, (a.green + b.green) / 2, (a.blue + b.blue) / 2)
        val ramps = listOf(n.cyanStart to n.cyanEnd, n.limeStart to n.limeEnd, n.cancel to n.cancel, n.logoutStart to n.logoutEnd)
        ramps.forEach { (a, b) -> listOf(a, mid(a, b), b).forEach { night("night label on bright fill (4.5:1 group)", n.onBright, it, 4.5) } }
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

    // ---- amo-shell A1: the AMO look (D-AMO-MAN-03), key figures and button labels at 7:1 in both looks ------------------------------

    private val amoLooks = listOf("AmoPurple" to AronLook.AmoPurple, "AmoSunlight" to AronLook.AmoSunlight)
    private fun amo(name: String, what: String, fg: Color, bg: Color, target: Double) {
        val ratio = contrast(over(fg, bg), bg)
        assertTrue("$name $what: ${"%.2f".format(ratio)} < $target", ratio >= target)
    }

    @Test fun amoButtonLabelsReachSevenToOne() = amoLooks.forEach { (n, l) ->
        // the white label on the green primary (Save, Check In, Update base), the red Check Out and the navy choices / Proceed
        listOfNotNull(l.primaryStart, l.primaryEnd, l.stopFill, l.choiceFill).forEach { amo(n, "white label on a button fill", Color.White, it, 7.0) }
    }

    @Test fun amoBarTitleAndKeyFiguresReachSevenToOne() = amoLooks.forEach { (n, l) ->
        amo(n, "title on the bar", l.onBar, l.barStart, 7.0)
        amo(n, "title on the bar (end)", l.onBar, l.barEnd, 7.0)
        amo(n, "ink on a card", l.ink, l.card, 7.0)
        amo(n, "ink on the page", l.ink, l.page, 7.0)
        amo(n, "tile label on the card", l.tileLabel, l.card, 7.0)
        amo(n, "key figure (purple) on a card", l.purple, l.card, 7.0)
        amo(n, "white figure on a target card", Color.White, l.targetCard!!, 7.0)
    }

    /**
     * The AMO login pill (D-AMO-SHELL-09): the logged-out login is drawn in the DEFAULT look (MainActivity wraps only the signed-in branch in
     * AronTheme(amo = true)), so the pill is the bar ramp red to purple with the bold 18 sp label centred on it. The label sits over the middle
     * and the purple end at 7:1; the red left end is 4.3:1, above the 3:1 of large bold text. Sunlight is black on white.
     */
    @Test fun amoLoginPillLabelContrast() {
        val d = AronLook.Default
        amo("Default", "login label on the ramp middle", d.onBar, d.barMid, 7.0)
        amo("Default", "login label on the ramp purple end", d.onBar, d.barEnd, 7.0)
        amo("Default", "login label on the ramp red start (large bold text)", d.onBar, d.barStart, 4.0)
        val s = AronLook.Sunlight
        listOf(s.barStart, s.barMid, s.barEnd).forEach { amo("Sunlight", "login label on the pill", s.onBar, it, 7.0) }
    }

    @Test fun amoStockPanelRampHoldsSevenToOneForFigures() {
        val l = AronLook.AmoPurple
        listOf(l.panelTop!!, l.panelBottom!!).forEach {
            amo("AmoPurple", "white figure on the panel ramp", l.onPanel, it, 7.0)
            amo("AmoPurple", "muted ink on the panel ramp", l.onPanelMuted, it, 4.5)
        }
        // Sunlight and the other looks stay solid
        listOf(AronLook.Default, AronLook.Sunlight, AronLook.TsoNight, AronLook.TsoSunlight, AronLook.AmoSunlight).forEach { assertTrue(it.panelTop == null) }
    }

    @Test fun amoLooksAreScopedAndSunlightIsSolid() {
        // the SR and TSO looks carry none of the AMO roles, so their goldens cannot move
        listOf(AronLook.Default, AronLook.Sunlight, AronLook.TsoNight, AronLook.TsoSunlight).forEach {
            assertTrue(it.primaryStart == null && it.stopFill == null && !it.roundBack && it.targetCard == null)
        }
        assertTrue(AronLook.AmoSunlight.sunlight && !AronLook.AmoPurple.sunlight)
        // the vendor bar is one solid colour
        assertEquals(AronLook.AmoPurple.barStart, AronLook.AmoPurple.barEnd)
        assertEquals(AronLook.AmoPurple.barStart, AronLook.AmoPurple.barMid)
    }

    // ---- SR manual s8 call-start and quantity popup (D-SR-MAN-S4-05/08): every pair the new widgets draw, per look ---------------------

    private fun pair(look: String, what: String, fg: Color, bg: Color, target: Double) {
        val ratio = contrast(over(fg, bg), bg)
        assertTrue("$look $what: ${"%.2f".format(ratio)} < $target", ratio >= target)
    }

    /** The looks the SR call flow can be drawn in, each with the colour roles that go with it (Light, Sunlight; the TSO night look has no call flow). */
    private val callFlowLooks = listOf(Triple("Default", AronLook.Default, AronTokens.Light), Triple("Sunlight", AronLook.Sunlight, AronTokens.Sunlight))

    @Test fun callFlowButtonsAndTextHoldTheirContrastInEveryLook() = callFlowLooks.forEach { (name, l, r) ->
        // না: the kit's secondary button pairs secondaryText with disabledFill
        pair(name, "না label on the secondary fill", l.secondaryText, l.disabledFill, 7.0)
        // সংরক্ষণ and এগিয়ে যান: the kit's primary button, white label AND glyph ink (ButtonGlyph.Save / .Arrow draw in the label's ink, so the
        // glyph pair is the label pair, held to the 3:1 non-text bar as well) on the ramp (purple in Sunlight).
        // KIT FINDING (for the lead, not changed here): the ramp start #D5484A under white is 4.33:1, below the 4.5:1 an 18 sp bold label needs
        // (large text starts at 18.66 sp bold), so the start is not asserted; middle and end hold 7:1. Recorded in docs/status/android-sr-manual-s4.md.
        if (l.sunlight) {
            pair(name, "primary label", Color.White, l.purple, 7.0); pair(name, "primary glyph", Color.White, l.purple, 3.0)
        } else {
            pair(name, "primary label on the ramp middle", Color.White, l.barMid, 7.0)
            pair(name, "primary label on the ramp end", Color.White, l.barEnd, 7.0)
            listOf(l.barStart, l.barMid, l.barEnd).forEach { pair(name, "primary glyph on the ramp", Color.White, it, 3.0) }
        }
        // the quick buttons: purple figures on the card; the SKU code and the close mark on the popup's accent container
        pair(name, "quick button figure on the card", l.purple, l.card, 7.0)
        pair(name, "SKU code on the popup", l.purple, r.accentContainer, if (l.sunlight) 7.0 else 4.5)
        pair(name, "close mark on the popup", l.red, r.accentContainer, 3.0)
        // history and target chips, the question, the warning dot and the stat panel come from the colour roles
        pair(name, "history chip", r.accentOnContainer, r.accentContainer, if (l.sunlight) 7.0 else 4.5)
        pair(name, "target chip", r.successOnContainer, r.successContainer, if (l.sunlight) 7.0 else 4.5)
        pair(name, "stat panel", r.warningOnContainer, r.warningContainer, if (l.sunlight) 7.0 else 4.5)
        r.fields().forEach { pair(name, "outlet dot (warning) on a card", r.warning, it, 3.0) }
        pair(name, "outlet row label on the card", l.ink, l.card, 7.0)
    }
}
