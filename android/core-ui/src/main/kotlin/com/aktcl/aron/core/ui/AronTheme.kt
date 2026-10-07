package com.aktcl.aron.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
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
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        background = bgGradientBottom, onBackground = textPrimary, surface = surfaceSolid, onSurface = textPrimary,
        surfaceVariant = surfaceSolid, onSurfaceVariant = textSecondary, error = danger,
        errorContainer = danger.copy(alpha = AronTokens.Alpha.Container), onErrorContainer = textPrimary,
        secondaryContainer = accent.copy(alpha = AronTokens.Alpha.Container), onSecondaryContainer = textPrimary,
        tertiaryContainer = warning.copy(alpha = AronTokens.Alpha.ContainerWarm), onTertiaryContainer = textPrimary,
    )
}

/** Every Material text style in the bundled family of [language]. */
fun aronTypography(language: AppLanguage): Typography {
    val family = AronFonts.forLanguage(language)
    val base = Typography()
    val bn = language == AppLanguage.BN
    // docs/32 s3: Bangla needs taller lines (body 16/26, title 22/30, caption 13/18, display 34/40)
    fun TextStyle.f(lineHeight: Int) = if (bn) copy(fontFamily = family, lineHeight = lineHeight.sp) else copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.f(44), displayMedium = base.displayMedium.f(40), displaySmall = base.displaySmall.f(40),
        headlineLarge = base.headlineLarge.f(40), headlineMedium = base.headlineMedium.f(36), headlineSmall = base.headlineSmall.f(32),
        titleLarge = base.titleLarge.f(30), titleMedium = base.titleMedium.f(26), titleSmall = base.titleSmall.f(24),
        bodyLarge = base.bodyLarge.f(26), bodyMedium = base.bodyMedium.f(22), bodySmall = base.bodySmall.f(18),
        labelLarge = base.labelLarge.f(22), labelMedium = base.labelMedium.f(18), labelSmall = base.labelSmall.f(18),
    )
}

@Composable
fun AronTheme(
    language: AppLanguage,
    dark: Boolean = isSystemInDarkTheme(),
    tier: GlassTier = LocalGlassTier.current,
    content: @Composable () -> Unit,
) {
    val roles = if (dark) AronTokens.Dark else AronTokens.Light
    CompositionLocalProvider(LocalAppLanguage provides language, LocalAronColors provides roles, LocalGlassTier provides tier) {
        MaterialTheme(
            colorScheme = remember(dark) { roles.toMaterial() },
            typography = remember(language) { aronTypography(language) },
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
