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
 * Design tokens v1 ("Calm Glass", docs/design/tokens.md s2) for one theme. Components read these names only; a token
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
        hex(0x0B1B33), hex(0x475569), hex(0x8793A6), hex(0xFFFFFF),
        hex(0x0A58CC), hex(0x1B68DC), hex(0x0846A8), hex(0xDCE9FD), hex(0x0A3F94),
        hex(0x0B7A45), hex(0xD6F0E1), hex(0x0A5A33),
        hex(0x9A5200), hex(0xFFE9C7), hex(0x7A4300),
        hex(0xC0182D), hex(0xFDDDE1), hex(0x8E1224),
        hex(0x53627C), hex(0xE3E8F0), hex(0x3F4C63),
        hex(0x0B1B33, 8), hex(0xDCE2EB), hex(0x515E73),
    )
    val Dark = AronColorRoles(
        AronMode.Dark,
        hex(0x16233F), hex(0x0F1A2E), hex(0x0A1220), hex(0x0F1A2E),
        hex(0xFFFFFF, 12), hex(0x1A2438, 92), hex(0x141D30), hex(0x1C2740), hex(0x000000, 56),
        hex(0xFFFFFF, 22), hex(0xFFFFFF, 6), hex(0xFFFFFF, 8), hex(0xFFFFFF, 24), hex(0x8394B0), hex(0x84B6FF),
        hex(0xF1F5FB), hex(0xA9B6CB), hex(0x6B7A93), hex(0x05122B),
        hex(0x84B6FF), hex(0xA0C8FF), hex(0x68A2F8), hex(0x1B3568), hex(0xBBD6FF),
        hex(0x4CD38B), hex(0x12382A), hex(0x9BE7BF),
        hex(0xFFB84D), hex(0x45330F), hex(0xFFD58F),
        hex(0xFF7A8A), hex(0x4A1B25), hex(0xFFB3BE),
        hex(0x9FB0CB), hex(0x27324A), hex(0xC3D0E6),
        hex(0xFFFFFF, 10), hex(0x2A3550), hex(0x97A6BF),
    )
    val Sunlight = AronColorRoles(
        AronMode.Sunlight,
        hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7), hex(0xF1F3F7),
        hex(0xFFFFFF), hex(0xFFFFFF), hex(0xFFFFFF), hex(0xEEF1F6), hex(0x000000, 56),
        hex(0x050A14, 72), hex(0x050A14, 72), hex(0x050A14, 40), hex(0x050A14, 72), hex(0x2B3648), hex(0x000000),
        hex(0x050A14), hex(0x2B3648), hex(0x5B6578), hex(0xFFFFFF),
        hex(0x0041B3), hex(0x0041B3), hex(0x002F85), hex(0xCFE0FF), hex(0x002A73),
        hex(0x005C2E), hex(0xCFEBD9), hex(0x00391C),
        hex(0x7A3E00), hex(0xFFE0A8), hex(0x4D2700),
        hex(0xA30018), hex(0xFAD0D6), hex(0x6B0010),
        hex(0x3B4658), hex(0xDDE2EA), hex(0x1F2937),
        hex(0x050A14, 12), hex(0xD9DEE7), hex(0x3B4658),
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
        const val HoldFill = 0.5f
    }

    /** Spacing on a 4 dp grid. */
    object Space { val Xs: Dp = 4.dp; val S: Dp = 8.dp; val M: Dp = 12.dp; val L: Dp = 16.dp; val Xl: Dp = 24.dp; val Screen: Dp = 16.dp }

    /** Touch targets: 48 dp minimum, 56 dp for primary actions. */
    object Touch { val Min: Dp = 48.dp; val Primary: Dp = 56.dp }

    /** Motion in milliseconds. */
    object Motion { const val Fast = 150; const val Base = 220; const val Sheet = 320; const val Hold = 1200 }
}

val LocalAronColors = staticCompositionLocalOf { AronTokens.Light }
