package com.aktcl.aron.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Theme axis (docs/design/tokens.md s1). Sunlight is a user switch and always forces glass tier C. */
enum class AronMode { Light, Dark, Sunlight }

private fun hex(rgb: Long, alphaPercent: Int = 100) = Color(0xFF000000 or rgb).copy(alpha = alphaPercent / 100f)

/**
 * Design tokens (tokens.md v1 refined, "Calm Glass", docs/design/tokens.md s2) for one theme. Components read these names only; a token
 * edit never touches a screen. `TokenContrastTest` asserts the ledger pairs and the outdoor targets of docs/32 s2a.
 */
@Immutable
data class AronColorRoles(
    val mode: AronMode,
    val bgGradientTop: Color, val bgGradientMid: Color, val bgGradientBottom: Color, val bgSolid: Color,
    val surfaceGlass: Color, val surfaceGlassStrong: Color, val surfaceSolid: Color, val surfaceSolidRaised: Color, val surfaceScrim: Color,
    val borderHairlineTop: Color, val borderHairlineBottom: Color, val borderDivider: Color, val borderSolid: Color, val borderInput: Color, val borderFocus: Color,
    val textPrimary: Color, val textSecondary: Color, val textDisabled: Color, val textOnAccent: Color,
    val accent: Color, val accentHi: Color, val accentPressed: Color, val accentContainer: Color, val accentOnContainer: Color,
    val success: Color, val successContainer: Color, val successOnContainer: Color,
    val warning: Color, val warningContainer: Color, val warningOnContainer: Color,
    val danger: Color, val dangerContainer: Color, val dangerOnContainer: Color,
    val offline: Color, val offlineContainer: Color, val offlineOnContainer: Color,
    val statePressed: Color, val stateDisabledFill: Color, val stateDisabledLabel: Color,
    val textPlaceholder: Color, val accentText: Color, val accentFill: Color, val dangerFill: Color, val surfaceField: Color, val stateSkeleton: Color,
    /** Yes / confirm is green and No / cancel is red (owner 2026-10-17); the label on both is [textOnAccent] (7:1, TokenContrastTest). */
    val yesFill: Color, val noFill: Color,
    /** The two static page glows (tokens s2.1): A at (12% w, 6% h) radius 90% w, B at (95% w, 78% h) radius 80% w; none in sunlight. */
    val bgGlowA: Color = Color(0f, 0f, 0f, 0f), val bgGlowB: Color = Color(0f, 0f, 0f, 0f),
    /** The lower stop of the light field-card body (`#F6F9FE` 96%); dark cards are flat and shaded instead. */
    val surfaceFieldBottom: Color = surfaceField,
    /** `state.skeleton.sweep`: the moving band of a loading skeleton (none in sunlight: the band stays static). */
    val stateSkeletonSweep: Color = Color(1f, 1f, 1f, 0f),
) {
    val dark: Boolean get() = mode == AronMode.Dark
    val sunlight: Boolean get() = mode == AronMode.Sunlight
}

/** A soft container colour with the ink that is read on it. */
@Immutable
data class AronHue(val container: Color, val ink: Color)

/** The tile hue palette per theme. */
@Immutable
class AronHues(val light: List<AronHue>, val dark: List<AronHue>) {
    fun forMode(mode: AronMode): List<AronHue> = if (mode == AronMode.Dark) dark else light
}

/** Which hue the next tile takes; the tile grid sets it per tile, a lone tile falls back to its label. */
val LocalTileHue = androidx.compose.runtime.compositionLocalOf { -1 }

object AronTokens {
    val Light = AronColorRoles(
        AronMode.Light,
        hex(0xA8C6FF), hex(0xD0BCFF), hex(0xFFC4DC), hex(0xD0BCFF),
        hex(0xFFFFFF, 62), hex(0xFFFFFF, 92), hex(0xFFFFFF), hex(0xF3F0FF), hex(0x000000, 32),
        hex(0xFFFFFF, 75), hex(0x1B2A8F, 12), hex(0x1B2A8F, 10), hex(0x1B2A8F, 30), hex(0x5F6BB0), hex(0x3B57F0),
        hex(0x0B1B33), hex(0x303D52), hex(0x738097), hex(0xFFFFFF),
        hex(0x2230A8), hex(0x18248A), hex(0x0F1858), hex(0xDCE3FF), hex(0x1E2C9E),
        hex(0x0A9250), hex(0xBDF5D6), hex(0x04532B),
        hex(0xE06A00), hex(0xFFE2B0), hex(0x6B3500),
        hex(0xE0213F), hex(0xFFD0D8), hex(0x7F0F22),
        hex(0x0E8FA6), hex(0xC4F1F7), hex(0x0B4A57),
        hex(0x1B2A8F, 10), hex(0xE3E6F3), hex(0x4F5A78),
        hex(0x5F6D84), hex(0x1D2A9E), hex(0x16217A), hex(0x7A0E1C), hex(0xFFFFFF, 96), hex(0x1B2A8F, 8),
        hex(0x0A6434), hex(0xAE1128),
        bgGlowA = hex(0x6EA2FF, 55), bgGlowB = hex(0xFF7FC0, 40), surfaceFieldBottom = hex(0xF7F4FF, 96), stateSkeletonSweep = hex(0xFFFFFF, 45),
    )
    val Dark = AronColorRoles(
        AronMode.Dark,
        hex(0x14265C), hex(0x26175C), hex(0x3A1450), hex(0x26175C),
        hex(0xFFFFFF, 12), hex(0x1C1E42, 92), hex(0x17193A), hex(0x20245A), hex(0x000000, 56),
        hex(0xFFFFFF, 22), hex(0xFFFFFF, 6), hex(0xFFFFFF, 8), hex(0xFFFFFF, 24), hex(0x8D9AD6), hex(0x8FA3FF),
        hex(0xF8FAFD), hex(0xC9D0F0), hex(0x7580A8), hex(0x010712),
        hex(0xB9C6FF), hex(0xC9D3FF), hex(0xDDE3FF), hex(0x27337A), hex(0xC7D1FF),
        hex(0x5BE39B), hex(0x0F3D2A), hex(0xA6F0C8),
        hex(0xFFB060), hex(0x4A3010), hex(0xFFD9A8),
        hex(0xFF7A8A), hex(0x4F1830), hex(0xFFB8C2),
        hex(0x4FD8EE), hex(0x0F3B47), hex(0xA8EAF6),
        hex(0xFFFFFF, 10), hex(0x2A2F5E), hex(0xA3ADD6),
        hex(0x9AA5CF), hex(0xB3C1FF), hex(0xC9D3FF), hex(0xFFB3BE), hex(0x17193A, 96), hex(0xFFFFFF, 10),
        hex(0x5BE39B), hex(0xFF7A8A),
        bgGlowA = hex(0x4F6BFF, 26), bgGlowB = hex(0xD946EF, 16), surfaceFieldBottom = hex(0x17193A, 96), stateSkeletonSweep = hex(0xFFFFFF, 12),
    )
    val Sunlight = AronColorRoles(
        AronMode.Sunlight,
        hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7),
        hex(0xFFFFFF), hex(0xFFFFFF), hex(0xFFFFFF), hex(0xEEF1F6), hex(0x000000, 56),
        hex(0x050A14, 72), hex(0x050A14, 72), hex(0x050A14, 40), hex(0x050A14, 72), hex(0x2B3648), hex(0x000000),
        hex(0x050A14), hex(0x2B3648), hex(0x5B6578), hex(0xFFFFFF),
        hex(0x1A2AA8), hex(0x131D78), hex(0x0C1250), hex(0xD6DCFF), hex(0x131D78),
        hex(0x00642F), hex(0xC4F2D8), hex(0x00391C),
        hex(0x8A3F00), hex(0xFFD9A0), hex(0x4D2200),
        hex(0xB0001C), hex(0xFFCDD5), hex(0x6B0010),
        hex(0x085B6B), hex(0xC2EEF5), hex(0x053640),
        hex(0x050A14, 12), hex(0xD9DEE7), hex(0x3B4658),
        hex(0x4A5568), hex(0x1A2AA8), hex(0x131D78), hex(0x6B0010), hex(0xFFFFFF), hex(0x050A14, 10),
        hex(0x00522A), hex(0x960014),
        bgGlowA = hex(0xF1F3F7, 0), bgGlowB = hex(0xF1F3F7, 0), surfaceFieldBottom = hex(0xFFFFFF), stateSkeletonSweep = hex(0xFFFFFF, 0),
    )

    /**
     * Ten tile hues (owner 2026-10-17: the three field apps must be vibrant and colourful): indigo, emerald, orange, rose, teal, violet,
     * magenta, sky, lime, gold. Each is a soft container for the icon well with a deep ink for the glyph, at least 7:1 (TokenContrastTest).
     * Home tiles take them in turn (the tile grid sets [LocalTileHue]); sunlight uses the light set (its inks are far above 7:1).
     */
    val Hues = AronHues(
        light = listOf(
            AronHue(hex(0xDCE3FF), hex(0x18248A)), AronHue(hex(0xBDF5D6), hex(0x04532B)), AronHue(hex(0xFFE2B0), hex(0x5E3000)),
            AronHue(hex(0xFFD0D8), hex(0x7F0F22)), AronHue(hex(0xC4F1F7), hex(0x0B4A57)), AronHue(hex(0xE6D9FF), hex(0x3A1485)),
            AronHue(hex(0xFFD3EC), hex(0x7A0E52)), AronHue(hex(0xCDEEFF), hex(0x06384F)), AronHue(hex(0xE0F8A8), hex(0x283F00)),
            AronHue(hex(0xFFF0A0), hex(0x4D3700)),
        ),
        dark = listOf(
            AronHue(hex(0x27337A), hex(0xC7D1FF)), AronHue(hex(0x0F3D2A), hex(0xA6F0C8)), AronHue(hex(0x4A3010), hex(0xFFD9A8)),
            AronHue(hex(0x4F1830), hex(0xFFB8C2)), AronHue(hex(0x0F3B47), hex(0xA8EAF6)), AronHue(hex(0x33206B), hex(0xD9C8FF)),
            AronHue(hex(0x4A1540), hex(0xFFC2E6)), AronHue(hex(0x0F3550), hex(0xB5E0FF)), AronHue(hex(0x2B3B0F), hex(0xD4F08A)),
            AronHue(hex(0x4A3D0A), hex(0xFFE88A)),
        ),
    )

    fun forMode(mode: AronMode): AronColorRoles = when (mode) { AronMode.Light -> Light; AronMode.Dark -> Dark; AronMode.Sunlight -> Sunlight }

    /** Shape radii: chips 12, cards 20, sheets 28; buttons are fully round. */
    object Radius { val Chip: Dp = 12.dp; val Card: Dp = 20.dp; val Sheet: Dp = 28.dp; val Well: Dp = 14.dp; val WellCompact: Dp = 11.dp }
    /** Outdoor mode thickens strokes (docs/32 s2a item 5). */
    object Stroke { val Hairline: Dp = 1.dp; val Strong: Dp = 1.5.dp; val SunlightHairline: Dp = 2.dp; val Focus: Dp = 2.dp; val SunlightFocus: Dp = 3.dp }
    val Hairline: Dp = 1.dp

    /** Buttons are fully round (a percent-50 corner). */
    val ButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(50)

    /** Elevation levels: page, card, sheet, dialog (soft, low-opacity shadows only). */
    object Elevation { val Page: Dp = 0.dp; val Card: Dp = 4.dp; val Sheet: Dp = 12.dp; val Dialog: Dp = 24.dp }

    /**
     * Glass and state alphas of the Calm Glass v1 recipes (tokens s3, android-glass s2 and s3), so no component carries a
     * literal. [BarTierA] and [BarTintA] are the tier A top bar OVER A BLUR BACKDROP and are reserved until that backdrop
     * exists (android-glass s2 item 4); until then the top bar paints the tier B recipe on every tier.
     */
    object Alpha {
        const val BarTierA = 0.86f; const val BarTierB = 0.96f; const val BarTintA = 0.04f
        const val TileTop = 0.66f; const val TileBottom = 0.58f; const val TileSheen = 0.30f; const val TileDark = 0.12f
        const val DarkShade = 0.14f
        const val ButtonHighlight = 0.35f; const val BannerEdge = 0.20f
        const val SkeletonBand = 0.40f
    }

    /**
     * Elevation shadow tints (tokens s5). Android multiplies the colour's alpha by its own ambient (about 4 percent) and spot
     * (about 19 percent) factors, so the tint is passed opaque and the resulting opacity approximates the soft 6 and 10 percent
     * of the spec (and the primary button's accent spot comes out near 19 percent, not the 28 the spec names: a platform limit,
     * to be eyeballed on the A06).
     */
    object ShadowTint { val Light: Color = Color(0xFF0B1B33); val Dark: Color = Color(0xFF000000) }

    /** Fixed sizes in dp (tokens s6). */
    object Size {
        val TopBar: Dp = 64.dp; val BottomBar: Dp = 80.dp; val BottomBarWithReason: Dp = 104.dp
        val Tile: Dp = 96.dp; val TileCompact: Dp = 84.dp; val Well: Dp = 44.dp; val WellCompact: Dp = 36.dp
        /** The one-column tile of font scale 1.9 and up: a 64 dp row, the well at the start and the plate beside it. */
        val TileRow: Dp = 64.dp
        /** The label plate of a tile: 32 dp for one line of `type.label`. */
        val TilePlate: Dp = 32.dp
        val Meter: Dp = 6.dp; val MeterSunlight: Dp = 8.dp; val Chip: Dp = 28.dp; val Dot: Dp = 28.dp
        val Icon: Dp = 24.dp; val IconSmall: Dp = 20.dp; val IconLarge: Dp = 32.dp; val Illustration: Dp = 96.dp; val IllustrationGlyph: Dp = 40.dp
        /** Floors of the status-chip slots (android-glass s6.6); the chip measures its longest label and reserves the larger. */
        val ChipSlotCompact: Dp = 112.dp; val ChipSlotFull: Dp = 168.dp
        /** The widest a bottom-bar summary may be beside the buttons, and the least a primary may get (android-glass s6.7). */
        val BarSummaryMax: Dp = 96.dp; val BarPrimaryMin: Dp = 144.dp
        /** Below this window width a banner's action sits under its text (a 360 dp phone has no room for Bangla beside it). */
        val BannerInlineActionMinWidth: Dp = 400.dp
        /** Below this font scale tiles and bars keep their fixed heights; from it they reflow (tokens s6 `fontScale.tiles2`, `.stack`). */
        const val ReflowFontScale = 1.3f
        /** From this font scale the segmented control is a stack of radio rows and a banner's action sits under its text (tokens s6 `fontScale.vertical`). */
        const val VerticalFontScale = 1.5f
        /** From this font scale the tile grid is one column of 64 dp rows (tokens s6 `fontScale.tiles1`). */
        const val OneColumnFontScale = 1.9f
        /** Large-text roles (display, numeral, title, heading) stop growing here (tokens s7.3 `fontScale.cap`). */
        const val LargeTextCap = 1.5f
    }

    /** Spacing on a 4 dp grid. */
    object Space { val Xs: Dp = 4.dp; val S: Dp = 8.dp; val M: Dp = 12.dp; val L: Dp = 16.dp; val Xl: Dp = 24.dp; val Screen: Dp = 16.dp }

    /** The primary action label is at least this large and bold (docs/32 s2a item 4: the 3:1 glare exception applies only to it). */
    const val PrimaryLabelMinSp = 18

    /** Touch targets: 48 dp minimum, 56 dp for primary actions. */
    object Touch { val Min: Dp = 48.dp; val Primary: Dp = 56.dp }

    /** Motion in milliseconds. */
    object Motion {
        const val Fast = 150; const val Base = 220; const val Sheet = 320; const val Hold = 1200
        const val Press = 90; const val BackOut = 180; const val Reduced = 120
        /** The syncing arc: one turn per 1.2 s, at most 3 turns, then it holds (tokens s8). */
        const val SyncTurnMillis = 1200; const val SyncMaxTurns = 3
        /** Skeleton sweep: 1.2 s per cycle, at most 2 cycles, then static. */
        const val SkeletonCycleMillis = 1200; const val SkeletonMaxCycles = 2
        /** Pressed scales (tokens s8 `motion.press`). */
        const val ButtonPressScale = 0.97f; const val TilePressScale = 0.96f; const val StepperPressScale = 0.92f
    }
}

val LocalAronColors = staticCompositionLocalOf { AronTokens.Light }

/**
 * The existing-Aron look (OWNER DIRECTIVE 2026-10-18, CLAUDE.md rule 14a, docs/ui-reference/PARITY-SPEC.md s1): the exact values
 * sampled from the owner's screenshots in docs/ui-reference/sr/. The parity kit (`com.aktcl.aron.core.ui.aron`) reads only these;
 * no glass, no blur, solid surfaces. [AronLookColors.Sunlight] is the optional one-tap outdoor theme (solid white bars, black ink).
 */
@Immutable
data class AronLookColors(
    val sunlight: Boolean,
    /** `aron.gradient.bar` stops: 0 %, 50 %, 100 % (horizontal on bars and buttons, vertical on the selected letter). */
    val barStart: Color, val barMid: Color, val barEnd: Color,
    val onBar: Color,
    val red: Color, val minus: Color, val purple: Color, val panel: Color, val onPanel: Color, val onPanelMuted: Color,
    val page: Color, val pageHome: Color, val card: Color, val tileHairline: Color, val tileMenu: Color, val tileLabel: Color, val tileLabelOutlet: Color,
    val panelDark: Color, val tableHeader: Color, val rowAlt: Color,
    val targetTop: Color, val targetBottom: Color, val track: Color, val trackFill: Color,
    val disabledFill: Color, val disabledText: Color, val secondaryText: Color,
    val ink: Color, val inkMuted: Color, val columnHeader: Color, val underline: Color, val silhouette: Color, val silhouetteInk: Color,
    val badge: Color, val printerOff: Color, val printerOn: Color,
    /** sr/sales-submit.png: the pink notice card, the online/offline dot, the row rule of the submit table, the disabled primary. */
    val noticeFill: Color = hex(0xE6C8CA), val online: Color = hex(0x4CAF50), val offlineDot: Color = hex(0x9E9E9E),
    val rowRule: Color = hex(0xF5E6E8), val primaryDisabled: Color = hex(0xB8B8B8),
    /** sr/attendance.png (sampled): the avatar and pin squares, the location card, the green check-in and grey check-out gradients. */
    val avatarSquare: Color = hex(0xD7D3DF), val pinSquare: Color = hex(0xC7C3CF), val locationCard: Color = hex(0xD9D9D9),
    val goStart: Color = hex(0x02A652), val goEnd: Color = hex(0x56BA87), val neutralStart: Color = hex(0x515151), val neutralEnd: Color = hex(0xB8B8B8),
    /** Vision QA 2026-10-19 (sampled on stock, summary, sale-select): the disabled Print label, the picker hint and caret, the summary
     *  header ramp, the end of the Save gradient on the purple panel, and the Summary Print stops. */
    val secondaryDisabledText: Color = hex(0x595959), val hint: Color = hex(0x9E9E9E), val caret: Color = hex(0x616161),
    val tableHeaderStart: Color = hex(0x4F4F4F), val tableHeaderMid: Color = hex(0x3F3F3F), val tableHeaderEnd: Color = hex(0x2D2D2D),
    val panelButtonEnd: Color = hex(0x7D2B7B), val printStart: Color = hex(0x4A1C99), val printEnd: Color = hex(0xA73964),
    /** Lane kit requests 2026-10-19: amber (memo Edit), gold (rating stars), the quiet loading bars, the info-card border (summary.png). */
    val amber: Color = hex(0xE08A00), val gold: Color = hex(0xF5B301), val skeleton: Color = hex(0xE3E3E3), val infoBorder: Color = hex(0xC0C0C0),
    /** The TSO roles (D-TSO-MAN-13): set only by [AronLook.TsoNight] and [AronLook.TsoSunlight]; null in the SR and AMO looks. */
    val night: AronNightRoles? = null,
)

/**
 * The TSO look's own roles (D-TSO-MAN-13, build contract s5 A1), sampled from the owner's screenshots docs/ui-reference/tso/05-10, 17,
 * 24, 39 and 44 (JPEG, 5x5 averages; the contract's starting values where the JPEG blurs): navy page, purple-gradient glow cards, navy
 * outline cards with a teal edge, cyan figures, green-to-yellow gradient titles, the grey round back button, glow fields and gradient
 * buttons. [AronNightRoles.Sunlight] is the same set solid (white surfaces, black ink, no gradient or glow): Sunlight overrides the
 * night look. Contrast is computed by TokenContrastTest: key figures and button labels 7:1, body 4.5:1, graphics 3:1.
 *
 * Deviation from the screenshots, for legibility (rule 14): the bright Login (cyan), Final Submit (lime), Cancel (green) and Log Out
 * (orange) buttons carry a navy label ([onBright]) where the screenshots show white (white on those fills is about 2:1); the blue
 * button is a little deeper ([blue]) so its white label reaches 7:1; the cyan button's lower stop and the orange stop are lifted so the
 * navy label holds 7:1 on both stops.
 */
@Immutable
data class AronNightRoles(
    /** True in Sunlight: every surface is drawn solid (no gradient, glow or translucent layer). */
    val solid: Boolean,
    val page: Color,
    /** The purple glow card (Sales, Geo Fencing): gradient from [glowCardStart] (top start) to [glowCardEnd], halo [glow]. */
    val glowCardStart: Color, val glowCardEnd: Color, val glow: Color,
    /** The navy outline card (channel, CPR, Brand CPR, Login status, Segment, Target Status) and its teal edge. */
    val outlineCard: Color, val outlineEdge: Color,
    /** The row card of a list (screenshot 8) and its edge. */
    val rowCard: Color, val rowEdge: Color,
    /** Cyan key figures (40.0, 1.7 K) and the card labels on the glow card. */
    val figure: Color, val label: Color,
    /** Green and teal captions of the CPR card ("Target Outlet", "Successful Calls"). */
    val positive: Color, val teal: Color,
    /** The screen title gradient (green to yellow). */
    val titleStart: Color, val titleEnd: Color,
    val ink: Color, val inkMuted: Color,
    /** The grey round back button and its chevron. */
    val backButton: Color, val backInk: Color,
    /** The glow text field: fill, glowing edge, placeholder. */
    val field: Color, val fieldEdge: Color, val placeholder: Color,
    /** The drawer: surface, the rules between groups, the row chevrons. */
    val drawer: Color, val divider: Color, val chevron: Color,
    /** The bell's unread badge and its digits. */
    val badge: Color, val onBadge: Color,
    /** The Home FAB, its glow and its glyph. */
    val fab: Color, val fabGlow: Color, val onFab: Color,
    /** Rings: the track on a glow card, the track on an outline card, the fill. */
    val ringTrack: Color, val ringTrackOutline: Color, val ringFill: Color,
    /** The login and Bikroy Joma bars: light track, blue fill ramp. */
    val barTrack: Color, val barFillStart: Color, val barFillEnd: Color,
    /** The segment chart lines and the channel pie. */
    val valueLine: Color, val volumeLine: Color, val pie: Color, val gridLine: Color,
    /** Gradient buttons: cyan (Login), lime (Final Submit, plans), blue (Submit ticket); [onBright] on cyan and lime, [onBlue] on blue. */
    val cyanStart: Color, val cyanEnd: Color, val limeStart: Color, val limeEnd: Color, val blue: Color, val onBright: Color, val onBlue: Color,
    /** The logout dialog: green Cancel, orange Log Out, the dialog surface ramp. */
    val cancel: Color, val logoutStart: Color, val logoutEnd: Color, val dialogTop: Color, val dialogBottom: Color,
    /** The selected segment of a segmented tab (Monthly Target), the pill tabs and the status chips. */
    val segmentOn: Color, val onSegment: Color, val pillOn: Color, val pillOff: Color, val chip: Color, val chipInk: Color,
    /** The round logo plate of the login (mint to pink). */
    val plateTop: Color, val plateBottom: Color,
    /** Status inks readable on the navy cards. */
    val danger: Color, val warning: Color, val success: Color,
) {
    companion object {
        val Night = AronNightRoles(
            solid = false, page = hex(0x040C3A),
            glowCardStart = hex(0x3A1F6B), glowCardEnd = hex(0x24204F), glow = hex(0x7B3FE4),
            outlineCard = hex(0x101C42), outlineEdge = hex(0x1C6B80), rowCard = hex(0x0A1A3E), rowEdge = hex(0x1D3A60),
            figure = hex(0x2ED3C6), label = hex(0xD7CCF0), positive = hex(0x5BD16A), teal = hex(0x2ED3C6),
            titleStart = hex(0x6EE7A0), titleEnd = hex(0xE8E35A),
            ink = hex(0xFFFFFF), inkMuted = hex(0xB8C2E5), backButton = hex(0x47516B), backInk = hex(0xFFFFFF),
            field = hex(0x131C45), fieldEdge = hex(0x7B3FE4), placeholder = hex(0x8A93B8),
            drawer = hex(0x03103A), divider = hex(0x8890B0), chevron = hex(0x8A98B5),
            badge = hex(0xD32F2F), onBadge = hex(0xFFFFFF), fab = hex(0x2F80ED), fabGlow = hex(0x3DA0FE), onFab = hex(0xFFFFFF),
            ringTrack = hex(0x615180), ringTrackOutline = hex(0x374462), ringFill = hex(0x51DAA6),
            barTrack = hex(0xE1E6E1), barFillStart = hex(0x9BD0FF), barFillEnd = hex(0x2F80ED),
            valueLine = hex(0xF28C28), volumeLine = hex(0x2ED3C6), pie = hex(0xD81B87), gridLine = hex(0x1E2A55),
            cyanStart = hex(0x3BB8D8), cyanEnd = hex(0x78A8F2), limeStart = hex(0xA4C81A), limeEnd = hex(0x62B843), blue = hex(0x2244D0),
            onBright = hex(0x04122F), onBlue = hex(0xFFFFFF),
            cancel = hex(0x00C850), logoutStart = hex(0xF69703), logoutEnd = hex(0xE8930F), dialogTop = hex(0x04103A), dialogBottom = hex(0x0D1A55),
            segmentOn = hex(0x0F844F), onSegment = hex(0xFFFFFF), pillOn = hex(0x2244D0), pillOff = hex(0x1A2550), chip = hex(0x2A3556), chipInk = hex(0xC9D0E6),
            plateTop = hex(0xBFF5BC), plateBottom = hex(0xFFDCE0),
            danger = hex(0xFF7A7A), warning = hex(0xFFB74D), success = hex(0x5BD16A),
        )

        /** The TSO roles in Sunlight: solid white surfaces, black ink, deep solid fills with white labels (docs/32 s2a). */
        val Sunlight = AronNightRoles(
            solid = true, page = hex(0xF4F4F4),
            glowCardStart = hex(0xFFFFFF), glowCardEnd = hex(0xFFFFFF), glow = hex(0x202020),
            outlineCard = hex(0xFFFFFF), outlineEdge = hex(0x202020), rowCard = hex(0xFFFFFF), rowEdge = hex(0x202020),
            figure = hex(0x000000), label = hex(0x202020), positive = hex(0x00642F), teal = hex(0x085B6B),
            titleStart = hex(0x000000), titleEnd = hex(0x000000),
            ink = hex(0x000000), inkMuted = hex(0x202020), backButton = hex(0x202020), backInk = hex(0xFFFFFF),
            field = hex(0xFFFFFF), fieldEdge = hex(0x202020), placeholder = hex(0x505050),
            drawer = hex(0xFFFFFF), divider = hex(0x202020), chevron = hex(0x202020),
            badge = hex(0xB0001C), onBadge = hex(0xFFFFFF), fab = hex(0x1A2AA8), fabGlow = hex(0x1A2AA8), onFab = hex(0xFFFFFF),
            ringTrack = hex(0xC8C8C8), ringTrackOutline = hex(0xC8C8C8), ringFill = hex(0x00642F),
            barTrack = hex(0xC8C8C8), barFillStart = hex(0x1A2AA8), barFillEnd = hex(0x1A2AA8),
            valueLine = hex(0x8A3F00), volumeLine = hex(0x085B6B), pie = hex(0x7A0E52), gridLine = hex(0xC8C8C8),
            cyanStart = hex(0x1A2AA8), cyanEnd = hex(0x1A2AA8), limeStart = hex(0x00642F), limeEnd = hex(0x00642F), blue = hex(0x1A2AA8),
            onBright = hex(0xFFFFFF), onBlue = hex(0xFFFFFF),
            cancel = hex(0x00642F), logoutStart = hex(0x8A3F00), logoutEnd = hex(0x8A3F00), dialogTop = hex(0xFFFFFF), dialogBottom = hex(0xFFFFFF),
            segmentOn = hex(0x00642F), onSegment = hex(0xFFFFFF), pillOn = hex(0x1A2AA8), pillOff = hex(0xFFFFFF), chip = hex(0xEEEEEE), chipInk = hex(0x202020),
            plateTop = hex(0xFFFFFF), plateBottom = hex(0xFFFFFF),
            danger = hex(0xB0001C), warning = hex(0x8A3F00), success = hex(0x00642F),
        )
    }
}

object AronLook {
    val Default = AronLookColors(
        sunlight = false,
        barStart = hex(0xD5484A), barMid = hex(0x903171), barEnd = hex(0x4B1C97), onBar = hex(0xFFFFFF),
        red = hex(0xD5484A), minus = hex(0xD84A4A), purple = hex(0x4B1C97), panel = hex(0x481B9A), onPanel = hex(0xFFFFFF), onPanelMuted = hex(0xE6DDF7),
        page = hex(0xE8E8E8), pageHome = hex(0xFFFFFF), card = hex(0xFFFFFF), tileHairline = hex(0xEEEEEE), tileMenu = hex(0xF5F5F5),
        tileLabel = hex(0x3F2D9B), tileLabelOutlet = hex(0xD0434A),
        panelDark = hex(0x505050), tableHeader = hex(0x4D4D4D), rowAlt = hex(0xF0F0F0),
        targetTop = hex(0xC74350), targetBottom = hex(0x561F91), track = hex(0xEFEFEF), trackFill = hex(0xFF0100),
        disabledFill = hex(0xE8E8E8), disabledText = hex(0xA5A5A5), secondaryText = hex(0x303030),
        ink = hex(0x111111), inkMuted = hex(0x505050), columnHeader = hex(0x505050), underline = hex(0x9E9E9E),
        silhouette = hex(0xD9D9D9), silhouetteInk = hex(0x8A8A8A),
        badge = hex(0xD5484A), printerOff = hex(0xE53935), printerOn = hex(0x4B1C97),
    )

    /** Optional outdoor theme: solid white bars and panels with black ink (key figures far above 7:1); the accents stay. */
    val Sunlight = Default.copy(
        sunlight = true,
        barStart = hex(0xFFFFFF), barMid = hex(0xFFFFFF), barEnd = hex(0xFFFFFF), onBar = hex(0x000000),
        red = hex(0xB0001C), minus = hex(0xB0001C), purple = hex(0x2A0A6B), panel = hex(0xFFFFFF), onPanel = hex(0x000000), onPanelMuted = hex(0x202020),
        page = hex(0xF4F4F4), tileLabel = hex(0x1B0F5C), tileLabelOutlet = hex(0x8A0016), panelDark = hex(0x202020), tableHeader = hex(0x202020),
        targetTop = hex(0x2A0A6B), targetBottom = hex(0x2A0A6B), disabledText = hex(0x505050), secondaryText = hex(0x000000),
        ink = hex(0x000000), inkMuted = hex(0x202020), columnHeader = hex(0x202020), underline = hex(0x202020), silhouetteInk = hex(0x404040),
        noticeFill = hex(0xFFFFFF), online = hex(0x00642F), offlineDot = hex(0x404040), rowRule = hex(0x202020), primaryDisabled = hex(0x8A8A8A),
        locationCard = hex(0xFFFFFF), goStart = hex(0x00642F), goEnd = hex(0x00642F), neutralStart = hex(0x303030), neutralEnd = hex(0x303030),
        secondaryDisabledText = hex(0x303030), hint = hex(0x505050), caret = hex(0x202020),
        tableHeaderStart = hex(0x202020), tableHeaderMid = hex(0x202020), tableHeaderEnd = hex(0x202020),
        amber = hex(0x8A4B00), infoBorder = hex(0x202020),
    )

    /**
     * The TSO look (D-TSO-MAN-13; ruling 7): the login and every signed-in TSO screen. The shared kit roles are the navy versions
     * (navy page and cards, white ink, the cyan-to-blue bar and primary ramp with the navy label); the TSO-only roles are in
     * [AronLookColors.night] ([AronNightRoles.Night]). PARITY-SPEC lines 5 and 47 ("TSO = SR look") are superseded for TSO.
     */
    val TsoNight = Default.copy(
        barStart = hex(0x3BB8D8), barMid = hex(0x5AB0E5), barEnd = hex(0x78A8F2), onBar = hex(0x04122F),
        red = hex(0xFF7A7A), minus = hex(0xD32F2F), purple = hex(0xB9A6FF), panel = hex(0x3A1F6B), onPanel = hex(0xFFFFFF), onPanelMuted = hex(0xD7CCF0),
        page = hex(0x040C3A), pageHome = hex(0x040C3A), card = hex(0x101C42), tileHairline = hex(0x1D3A60), tileMenu = hex(0x101C42),
        tileLabel = hex(0xFFFFFF), tileLabelOutlet = hex(0xFFFFFF),
        panelDark = hex(0x0A1433), tableHeader = hex(0x1C2A5A), rowAlt = hex(0x0C1840),
        targetTop = hex(0x3A1F6B), targetBottom = hex(0x24204F), track = hex(0xE1E6E1), trackFill = hex(0x2F80ED),
        disabledFill = hex(0x2A3556), disabledText = hex(0xB8C2E5), secondaryText = hex(0xFFFFFF),
        ink = hex(0xFFFFFF), inkMuted = hex(0xB8C2E5), columnHeader = hex(0xB8C2E5), underline = hex(0x5A6690),
        silhouette = hex(0x1C2A5A), silhouetteInk = hex(0xB8C2E5),
        badge = hex(0xD32F2F), printerOff = hex(0xFF7A7A), printerOn = hex(0xB9A6FF),
        noticeFill = hex(0x3B1A2A), online = hex(0x5BD16A), offlineDot = hex(0x8A93B8), rowRule = hex(0x1D3A60), primaryDisabled = hex(0x2A3556),
        avatarSquare = hex(0x1C2A5A), pinSquare = hex(0x1C2A5A), locationCard = hex(0x101C42),
        goStart = hex(0xA4C81A), goEnd = hex(0x62B843), neutralStart = hex(0x2A3556), neutralEnd = hex(0x4A5680),
        secondaryDisabledText = hex(0xB8C2E5), hint = hex(0x8A93B8), caret = hex(0xB8C2E5),
        tableHeaderStart = hex(0x1C2A5A), tableHeaderMid = hex(0x1C2A5A), tableHeaderEnd = hex(0x1C2A5A),
        panelButtonEnd = hex(0x78A8F2), printStart = hex(0x3BB8D8), printEnd = hex(0x78A8F2),
        amber = hex(0xFFB74D), gold = hex(0xFCB203), skeleton = hex(0x1C2A5A), infoBorder = hex(0x2A3A6A),
        night = AronNightRoles.Night,
    )

    /** The TSO look in Sunlight: [Sunlight] with the solid TSO roles ([AronNightRoles.Sunlight]); Sunlight overrides the night look. */
    val TsoSunlight = Sunlight.copy(night = AronNightRoles.Sunlight)

    /** Card and tile radius 12 dp, button radius 8 dp, the plus/minus square 36 dp drawn inside a 48 dp hit area. */
    val CardRadius: Dp = 12.dp
    val ButtonRadius: Dp = 8.dp
    val StepRadius: Dp = 8.dp
    val Step: Dp = 36.dp
    val Touch: Dp = 48.dp
    val BarHeight: Dp = 56.dp
    val BarIcon: Dp = 36.dp
    val TileIcon: Dp = 56.dp
    val MenuTileIcon: Dp = 72.dp
    val ButtonHeight: Dp = 52.dp
    /**
     * Packshot in a [PackCard][com.aktcl.aron.core.ui.aron.PackCard]: measured on sr/stock.webp (145 x 235 px on a 1146 px, ~407 dp
     * screen) = 51 x 83 dp; PARITY-SPEC says "about 68 x 120". The screenshot wins (rule 14a), rounded up a little for legibility.
     */
    val PackshotWidth: Dp = 56.dp
    val PackshotHeight: Dp = 90.dp
    val PackBadge: Dp = 26.dp
}

/** The look in force; the parity kit reads it. `AronTheme(sunlight = true)` switches it to [AronLook.Sunlight]. */
val LocalAronLook = staticCompositionLocalOf { AronLook.Default }
