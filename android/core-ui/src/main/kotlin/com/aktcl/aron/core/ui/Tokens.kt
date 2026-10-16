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

object AronTokens {
    val Light = AronColorRoles(
        AronMode.Light,
        hex(0xD8E7FB), hex(0xE8EFFA), hex(0xF3F5FB), hex(0xE8EFFA),
        hex(0xFFFFFF, 62), hex(0xFFFFFF, 92), hex(0xFFFFFF), hex(0xF2F5FA), hex(0x000000, 32),
        hex(0xFFFFFF, 75), hex(0x0B1B33, 10), hex(0x0B1B33, 8), hex(0x0B1B33, 30), hex(0x66768F), hex(0x0A58CC),
        hex(0x0B1B33), hex(0x303D52), hex(0x738097), hex(0xFFFFFF),
        hex(0x0A58CC), hex(0x052E6B), hex(0x031734), hex(0xDCE9FD), hex(0x0A3F94),
        hex(0x0B7A45), hex(0xD6F0E1), hex(0x0A5A33),
        hex(0x9A5200), hex(0xFFE9C7), hex(0x7A4300),
        hex(0xC0182D), hex(0xFDDDE1), hex(0x8E1224),
        hex(0x53627C), hex(0xE3E8F0), hex(0x3F4C63),
        hex(0x0B1B33, 8), hex(0xDCE2EB), hex(0x515E73),
        hex(0x5F6D84), hex(0x063B84), hex(0x041F49), hex(0x7A0E1C), hex(0xFFFFFF, 96), hex(0x0B1B33, 7),
        bgGlowA = hex(0x9CC2FF, 50), bgGlowB = hex(0xC9B8FF, 30), surfaceFieldBottom = hex(0xF6F9FE, 96), stateSkeletonSweep = hex(0xFFFFFF, 45),
    )
    val Dark = AronColorRoles(
        AronMode.Dark,
        hex(0x16233F), hex(0x0F1A2E), hex(0x0A1220), hex(0x0F1A2E),
        hex(0xFFFFFF, 12), hex(0x1A2438, 92), hex(0x141D30), hex(0x1C2740), hex(0x000000, 56),
        hex(0xFFFFFF, 22), hex(0xFFFFFF, 6), hex(0xFFFFFF, 8), hex(0xFFFFFF, 24), hex(0x8394B0), hex(0x84B6FF),
        hex(0xF8FAFD), hex(0xC2CCDD), hex(0x6B7A93), hex(0x010712),
        hex(0x84B6FF), hex(0xC9E1FF), hex(0xDDEBFF), hex(0x1B3568), hex(0xBBD6FF),
        hex(0x4CD38B), hex(0x12382A), hex(0x9BE7BF),
        hex(0xFFB84D), hex(0x45330F), hex(0xFFD58F),
        hex(0xFF7A8A), hex(0x4A1B25), hex(0xFFB3BE),
        hex(0x9FB0CB), hex(0x27324A), hex(0xC3D0E6),
        hex(0xFFFFFF, 10), hex(0x2A3550), hex(0x97A6BF),
        hex(0x8F9DB5), hex(0xA8CFFF), hex(0xB3D4FF), hex(0xFFB3BE), hex(0x141D30, 96), hex(0xFFFFFF, 10),
        bgGlowA = hex(0x1D4ED8, 18), bgGlowB = hex(0x5B35D5, 10), surfaceFieldBottom = hex(0x141D30, 96), stateSkeletonSweep = hex(0xFFFFFF, 12),
    )
    val Sunlight = AronColorRoles(
        AronMode.Sunlight,
        hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7),
        hex(0xFFFFFF), hex(0xFFFFFF), hex(0xFFFFFF), hex(0xEEF1F6), hex(0x000000, 56),
        hex(0x050A14, 72), hex(0x050A14, 72), hex(0x050A14, 40), hex(0x050A14, 72), hex(0x2B3648), hex(0x000000),
        hex(0x050A14), hex(0x2B3648), hex(0x5B6578), hex(0xFFFFFF),
        hex(0x0041B3), hex(0x002A73), hex(0x001B4D), hex(0xCFE0FF), hex(0x002A73),
        hex(0x005C2E), hex(0xCFEBD9), hex(0x00391C),
        hex(0x7A3E00), hex(0xFFE0A8), hex(0x4D2700),
        hex(0xA30018), hex(0xFAD0D6), hex(0x6B0010),
        hex(0x3B4658), hex(0xDDE2EA), hex(0x1F2937),
        hex(0x050A14, 12), hex(0xD9DEE7), hex(0x3B4658),
        hex(0x4A5568), hex(0x0041B3), hex(0x002A73), hex(0x6B0010), hex(0xFFFFFF), hex(0x050A14, 10),
        bgGlowA = hex(0xF1F3F7, 0), bgGlowB = hex(0xF1F3F7, 0), surfaceFieldBottom = hex(0xFFFFFF), stateSkeletonSweep = hex(0xFFFFFF, 0),
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
