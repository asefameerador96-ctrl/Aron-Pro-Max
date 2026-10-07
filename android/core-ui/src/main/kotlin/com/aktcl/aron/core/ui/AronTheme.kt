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
        errorContainer = danger.copy(alpha = 0.14f), onErrorContainer = textPrimary,
        secondaryContainer = accent.copy(alpha = 0.14f), onSecondaryContainer = textPrimary,
        tertiaryContainer = warning.copy(alpha = 0.16f), onTertiaryContainer = textPrimary,
    )
}

/** Every Material text style in the bundled family of [language]. */
fun aronTypography(language: AppLanguage): Typography {
    val family = AronFonts.forLanguage(language)
    val base = Typography()
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
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
        MaterialTheme(colorScheme = roles.toMaterial(), typography = aronTypography(language), content = content)
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
