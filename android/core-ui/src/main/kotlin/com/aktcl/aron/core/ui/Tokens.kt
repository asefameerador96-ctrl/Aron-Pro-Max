package com.aktcl.aron.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens v0 (docs/32 s3). Components read these names only; the design workflow's v1 tokens replace the
 * values here without touching any screen. Colour roles exist in a light and a dark set.
 */
@Immutable
data class AronColorRoles(
    val bgGradientTop: Color,
    val bgGradientBottom: Color,
    val surfaceGlass: Color,
    val surfaceSolid: Color,
    val borderHairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val offline: Color,
    val dark: Boolean,
)

object AronTokens {
    val Light = AronColorRoles(
        bgGradientTop = Color(0xFFEAF2FB), bgGradientBottom = Color(0xFFF7F9FC),
        surfaceGlass = Color.White.copy(alpha = 0.62f), surfaceSolid = Color.White,
        borderHairline = Color.White.copy(alpha = 0.45f),
        textPrimary = Color(0xFF111827), textSecondary = Color(0xFF4B5563),
        accent = Color(0xFF0A5BD8), onAccent = Color.White,
        success = Color(0xFF1B7F4B), warning = Color(0xFF9A5B00), danger = Color(0xFFB3261E), offline = Color(0xFF6B7280),
        dark = false,
    )
    val Dark = AronColorRoles(
        bgGradientTop = Color(0xFF0F172A), bgGradientBottom = Color(0xFF0B1220),
        surfaceGlass = Color.White.copy(alpha = 0.12f), surfaceSolid = Color(0xFF1E293B),
        borderHairline = Color.White.copy(alpha = 0.14f),
        textPrimary = Color(0xFFF3F4F6), textSecondary = Color(0xFFB6BDC9),
        accent = Color(0xFF7AB0FF), onAccent = Color(0xFF00255A),
        success = Color(0xFF6FD6A0), warning = Color(0xFFFFC266), danger = Color(0xFFFFB4AB), offline = Color(0xFF9CA3AF),
        dark = true,
    )

    /** Shape radii: chips 12, cards 20, sheets 28; buttons are fully round. */
    object Radius { val Chip: Dp = 12.dp; val Card: Dp = 20.dp; val Sheet: Dp = 28.dp }
    val Hairline: Dp = 1.dp

    /** Spacing on a 4 dp grid. */
    object Space { val Xs: Dp = 4.dp; val S: Dp = 8.dp; val M: Dp = 12.dp; val L: Dp = 16.dp; val Xl: Dp = 24.dp; val Screen: Dp = 16.dp }

    /** Touch targets: 48 dp minimum, 56 dp for primary actions. */
    object Touch { val Min: Dp = 48.dp; val Primary: Dp = 56.dp }

    /** Motion in milliseconds. */
    object Motion { const val Fast = 150; const val Base = 220; const val Sheet = 320 }
}

val LocalAronColors = staticCompositionLocalOf { AronTokens.Light }
