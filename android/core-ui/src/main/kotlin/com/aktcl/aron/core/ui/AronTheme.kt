package com.aktcl.aron.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits

/** The bundled fonts (docs/24 s5.6): Noto Sans Bengali for Bangla (it also covers Basic Latin), subset Noto Sans for English. */
object AronFonts {
    val Bengali: FontFamily = FontFamily(
        Font(R.font.noto_sans_bengali_regular, FontWeight.Normal),
        Font(R.font.noto_sans_bengali_medium, FontWeight.Medium),
        Font(R.font.noto_sans_bengali_bold, FontWeight.Bold),
    )
    val Latin: FontFamily = FontFamily(
        Font(R.font.noto_sans_latin_regular, FontWeight.Normal),
        Font(R.font.noto_sans_latin_bold, FontWeight.Bold),
    )

    fun forLanguage(language: AppLanguage): FontFamily = if (language == AppLanguage.BN) Bengali else Latin
}

/** The app language inside Compose; screens use it for digits ([localizedDigits]). */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.DEFAULT }

private fun AronColorRoles.toMaterial(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = textOnAccent, secondary = accent, onSecondary = textOnAccent,
        background = bgSolid, onBackground = textPrimary, surface = surfaceSolid, onSurface = textPrimary,
        surfaceVariant = surfaceSolidRaised, onSurfaceVariant = textSecondary, outline = borderInput, error = danger,
        errorContainer = dangerContainer, onErrorContainer = dangerOnContainer,
        secondaryContainer = accentContainer, onSecondaryContainer = accentOnContainer,
        tertiaryContainer = warningContainer, onTertiaryContainer = warningOnContainer,
    )
}

/**
 * The v1 type scale (docs/design/tokens.md s7): display 34/40, numeral 26/32, title 22/30, heading 18/26, body 16/24,
 * caption 13/18, label 13/18; Bangla line heights are taller (display 48, numeral 38, title 32, body 26, caption and
 * label 14/20). Only the bundled 400 and 700 weights exist. Sunlight lifts body one step (Latin 17 sp, Bangla 18/28) and
 * sets Bangla body in bold, the only heavier weight bundled (docs/32 s2a item 5 asks for 500; no such face exists).
 */
fun aronTypography(language: AppLanguage, sunlight: Boolean = false): Typography {
    val family = AronFonts.forLanguage(language)
    val bn = language == AppLanguage.BN
    fun st(size: Int, latinLine: Int, bnLine: Int = latinLine, bold: Boolean = false, tnum: Boolean = false, bnSize: Int = size, bnMedium: Boolean = false) = TextStyle(
        fontFamily = family,
        fontSize = (if (bn) bnSize else size).sp,
        lineHeight = (if (bn) bnLine else latinLine).sp,
        fontWeight = if (bold) FontWeight.Bold else if (bn && bnMedium) FontWeight.Medium else FontWeight.Normal,
        letterSpacing = 0.sp,
        fontFeatureSettings = if (tnum) "tnum" else null,
    )
    val display = st(34, 40, 48, bold = true, tnum = true)
    val numeral = st(26, 32, 38, bold = true, tnum = true)
    val title = st(22, 30, 32, bold = true)
    val heading = st(18, 26, 26, bold = true)
    val body = if (sunlight) st(17, 25, 28, bnSize = 18, bnMedium = true) else st(16, 24, 26)
    val bodyStrong = if (sunlight) st(17, 25, 28, bold = true, bnSize = 18) else st(16, 24, 26, bold = true)
    val caption = st(13, 18, 20, bnSize = 14, bnMedium = sunlight)
    val label = st(13, 18, 20, bold = true, bnSize = 14)
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = numeral,
        headlineLarge = numeral, headlineMedium = numeral, headlineSmall = title,
        titleLarge = title, titleMedium = heading, titleSmall = bodyStrong,
        bodyLarge = body, bodyMedium = body, bodySmall = caption,
        labelLarge = bodyStrong, labelMedium = label, labelSmall = label,
    )
}

/** True in sunlight mode: opaque surfaces, no gradients behind text, thicker strokes (docs/32 s2a). */
val LocalSunlight = staticCompositionLocalOf { false }

/**
 * [dark] is a user choice (light is the field default); [sunlight] is the outdoor switch and forces tier C.
 */
@Composable
fun AronTheme(
    language: AppLanguage,
    dark: Boolean = false,
    sunlight: Boolean = false,
    tier: GlassTier = LocalGlassTier.current,
    content: @Composable () -> Unit,
) {
    val mode = if (sunlight) AronMode.Sunlight else if (dark) AronMode.Dark else AronMode.Light
    val roles = AronTokens.forMode(mode)
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalAronColors provides roles,
        LocalSunlight provides sunlight,
        LocalGlassTier provides (if (sunlight) GlassTier.C else tier),
    ) {
        MaterialTheme(
            colorScheme = remember(mode) { roles.toMaterial() },
            typography = remember(language, sunlight) { aronTypography(language, sunlight) },
            content = content,
        )
    }
}

/** Digits of [text] in the current app language: Bengali digits in Bangla, ASCII in English. Never for identifiers. */
@Composable
@ReadOnlyComposable
fun localizedDigits(text: String): String = LocaleDigits.localize(text, LocalAppLanguage.current)

/** An integer with South Asian grouping in the current app language. */
@Composable
@ReadOnlyComposable
fun localizedNumber(value: Long): String = LocaleDigits.formatInteger(value, LocalAppLanguage.current)
