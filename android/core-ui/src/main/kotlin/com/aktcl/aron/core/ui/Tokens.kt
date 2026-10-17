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
) {
    val dark: Boolean get() = mode == AronMode.Dark
    val sunlight: Boolean get() = mode == AronMode.Sunlight
}

/** A soft container colour with the ink that is read on it. */
@Immutable
data class AronHue(val container: Color, val ink: Color)

/** The tile hue palette per theme; sunlight uses the light set (its inks are far above 7:1). */
@Immutable
class AronHues(val light: List<AronHue>, val dark: List<AronHue>) {
    fun forMode(mode: AronMode): List<AronHue> = if (mode == AronMode.Dark) dark else light
}

/** Which hue the next tile takes; the tile grid sets it per tile, a lone tile falls back to its label. */
val LocalTileHue = androidx.compose.runtime.compositionLocalOf { -1 }

object AronTokens {
    // Vibrant palette (owner 2026-10-17): aurora page gradient (sky, lavender, blush), electric indigo accent, and a separate hue for
    // each status (emerald, orange, crimson, teal) with a matching tinted container. Content stays solid; every pair is gated by TokenContrastTest.
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
    )

    /**
     * Ten tile hues (owner 2026-10-17: the three field apps must be vibrant and colourful): indigo, emerald, orange, rose, teal, violet,
     * magenta, sky, lime, gold. Each is a soft container with a deep ink, at least 7:1 (TokenContrastTest). Home tiles take them in turn.
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
    object Radius { val Chip: Dp = 12.dp; val Card: Dp = 20.dp; val Sheet: Dp = 28.dp }
    /** Outdoor mode thickens strokes (docs/32 s2a item 5). */
    object Stroke { val Hairline: Dp = 1.dp; val Strong: Dp = 1.5.dp; val SunlightHairline: Dp = 2.dp; val Focus: Dp = 2.dp; val SunlightFocus: Dp = 3.dp }
    val Hairline: Dp = 1.dp

    /** Buttons are fully round (a percent-50 corner). */
    val ButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(50)

    /** Elevation levels: page, card, sheet, dialog (soft, low-opacity shadows only). */
    object Elevation { val Page: Dp = 0.dp; val Card: Dp = 4.dp; val Sheet: Dp = 12.dp; val Dialog: Dp = 24.dp }

    /** Glass and state alphas, so no component carries a literal. */
    object Alpha {
        const val SheenLight = 0.22f; const val SheenDark = 0.06f
        const val Shadow = 0.10f; const val SolidBorder = 0.35f; const val TierABoost = 0.08f
        
    }

    /** Spacing on a 4 dp grid. */
    object Space { val Xs: Dp = 4.dp; val S: Dp = 8.dp; val M: Dp = 12.dp; val L: Dp = 16.dp; val Xl: Dp = 24.dp; val Screen: Dp = 16.dp }

    /** The primary action label is at least this large and bold (docs/32 s2a item 4: the 3:1 glare exception applies only to it). */
    const val PrimaryLabelMinSp = 18

    /** Touch targets: 48 dp minimum, 56 dp for primary actions. */
    object Touch { val Min: Dp = 48.dp; val Primary: Dp = 56.dp }

    /** Motion in milliseconds. */
    object Motion { const val Fast = 150; const val Base = 220; const val Sheet = 320; const val Hold = 1200 }
}

val LocalAronColors = staticCompositionLocalOf { AronTokens.Light }
