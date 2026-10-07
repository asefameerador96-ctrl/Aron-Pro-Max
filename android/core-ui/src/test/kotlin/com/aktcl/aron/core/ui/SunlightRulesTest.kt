package com.aktcl.aron.core.ui

import androidx.compose.ui.text.font.FontWeight
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/32 s2a: the brightness suggestion threshold and the sunlight type step. */
class SunlightRulesTest {
    @Test fun suggestsAtEightyFivePercentOrMore() {
        assertFalse(SunlightSuggestion.shouldSuggest(216))   // 84.7 percent of 255
        assertTrue(SunlightSuggestion.shouldSuggest(217))    // 85.1 percent
        assertTrue(SunlightSuggestion.shouldSuggest(255))
        assertFalse(SunlightSuggestion.shouldSuggest(0))
        assertFalse(SunlightSuggestion.shouldSuggest(100, max = 0))
    }

    @Test fun sunlightRaisesBodyOneStep() {
        val normalEn = aronTypography(AppLanguage.EN).bodyMedium
        val sunEn = aronTypography(AppLanguage.EN, sunlight = true).bodyMedium
        assertEquals(16f, normalEn.fontSize.value, 0f); assertEquals(17f, sunEn.fontSize.value, 0f)
        val normalBn = aronTypography(AppLanguage.BN).bodyMedium
        val normBnWeight = normalBn.fontWeight
        val sunBn = aronTypography(AppLanguage.BN, sunlight = true).bodyMedium
        assertEquals(26f, normalBn.lineHeight.value, 0f)
        assertEquals(18f, sunBn.fontSize.value, 0f); assertEquals(28f, sunBn.lineHeight.value, 0f)
        assertEquals(FontWeight.Medium, sunBn.fontWeight)   // docs/32 s2a: Bangla body at least 500 in sunlight
        assertEquals(FontWeight.Normal, normBnWeight)
    }

    @Test fun neverAWeightThatIsNotBundled() {
        for (lang in AppLanguage.values()) for (sun in listOf(false, true)) {
            val t = aronTypography(lang, sun)
            listOf(t.displayLarge, t.titleLarge, t.bodyMedium, t.bodySmall, t.labelMedium).forEach {
                val medium = it.fontWeight == FontWeight.Medium && lang == AppLanguage.BN && sun   // Bangla sunlight body only
                assertTrue(it.fontWeight == FontWeight.Normal || it.fontWeight == FontWeight.Bold || medium)
            }
        }
    }

    @Test fun sunlightForcesTheSolidTier() {
        assertEquals(AronMode.Sunlight, AronTokens.forMode(AronMode.Sunlight).mode)
        assertTrue(AronTokens.Sunlight.surfaceGlass.alpha == 1f && AronTokens.Sunlight.surfaceGlassStrong.alpha == 1f)
    }
}
