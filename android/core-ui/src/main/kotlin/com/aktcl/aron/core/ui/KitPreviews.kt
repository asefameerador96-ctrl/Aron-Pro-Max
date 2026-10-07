package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.aktcl.aron.core.common.AppLanguage

@Composable
private fun Gallery(dark: Boolean, tier: GlassTier, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false) =
    AronTheme(language, dark = dark, sunlight = sunlight, tier = tier) { KitGallery() }

@Preview(name = "Tier A light", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierALight() = Gallery(false, GlassTier.A)
@Preview(name = "Tier B light", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierBLight() = Gallery(false, GlassTier.B)
@Preview(name = "Tier C light", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierCLight() = Gallery(false, GlassTier.C)
@Preview(name = "Tier A dark", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierADark() = Gallery(true, GlassTier.A)
@Preview(name = "Tier B dark", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierBDark() = Gallery(true, GlassTier.B)
@Preview(name = "Tier C dark", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun TierCDark() = Gallery(true, GlassTier.C)
@Preview(name = "English tier B", widthDp = 360, heightDp = 640) @Composable private fun EnglishB() = Gallery(false, GlassTier.B, AppLanguage.EN)
@Preview(name = "Bangla tier B font 1.3", widthDp = 360, heightDp = 640, locale = "bn", fontScale = 1.3f) @Composable private fun BanglaScale() = Gallery(false, GlassTier.B)
@Preview(name = "Bangla tier C dark font 2.0", widthDp = 360, heightDp = 640, locale = "bn", fontScale = 2.0f) @Composable private fun BanglaScale2() = Gallery(true, GlassTier.C)
@Preview(name = "Sunlight Bangla", widthDp = 360, heightDp = 640, locale = "bn") @Composable private fun Sunlight() = Gallery(false, GlassTier.C, sunlight = true)
@Preview(name = "Sunlight English font 1.3", widthDp = 360, heightDp = 640, fontScale = 1.3f) @Composable private fun SunlightEn() = Gallery(false, GlassTier.C, AppLanguage.EN, sunlight = true)
