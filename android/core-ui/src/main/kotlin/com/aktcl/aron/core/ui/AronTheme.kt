package com.aktcl.aron.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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

private val AronDarkColors = darkColorScheme(
    primary = Color(0xFF7BD6A4),
    onPrimary = Color(0xFF00391F),
    secondary = Color(0xFFFFB4AB),
    onSecondary = Color(0xFF690005),
    background = Color(0xFF111412),
    surface = Color(0xFF1A1D1B),
    error = Color(0xFFFFB4AB),
)

private val AronColors = lightColorScheme(
    primary = Color(0xFF0B5D3B),
    onPrimary = Color.White,
    secondary = Color(0xFF8C1D18),
    onSecondary = Color.White,
    background = Color(0xFFF7F8F6),
    surface = Color.White,
    error = Color(0xFFB3261E),
)

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
fun AronTheme(language: AppLanguage, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLanguage provides language) {
        MaterialTheme(colorScheme = if (dark) AronDarkColors else AronColors, typography = aronTypography(language), content = content)
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
