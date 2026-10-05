package com.aktcl.aron.core.ui

import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/** F-SYS-018: every Material text style uses the bundled font of the language (Noto Sans Bengali in Bangla). */
class TypographyTest {
    @Test
    fun banglaUsesTheBundledBengaliFontForEveryStyle() {
        val t = aronTypography(AppLanguage.BN)
        val styles = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall,
        )
        styles.forEach { assertEquals(AronFonts.Bengali, it.fontFamily) }
        assertEquals(AronFonts.Latin, aronTypography(AppLanguage.EN).bodyMedium.fontFamily)
    }
}
