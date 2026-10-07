package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.aktcl.aron.core.common.AppLanguage

@Composable
private fun Gallery(dark: Boolean, tier: GlassTier, language: AppLanguage = AppLanguage.BN) =
    AronTheme(language, dark = dark, tier = tier) { KitGallery() }

@Preview(name = "Tier A light", widthDp = 360, heightDp = 640) @Composable private fun TierALight() = Gallery(false, GlassTier.A)
@Preview(name = "Tier B light", widthDp = 360, heightDp = 640) @Composable private fun TierBLight() = Gallery(false, GlassTier.B)
@Preview(name = "Tier C light", widthDp = 360, heightDp = 640) @Composable private fun TierCLight() = Gallery(false, GlassTier.C)
@Preview(name = "Tier A dark", widthDp = 360, heightDp = 640) @Composable private fun TierADark() = Gallery(true, GlassTier.A)
@Preview(name = "Tier B dark", widthDp = 360, heightDp = 640) @Composable private fun TierBDark() = Gallery(true, GlassTier.B)
@Preview(name = "Tier C dark", widthDp = 360, heightDp = 640) @Composable private fun TierCDark() = Gallery(true, GlassTier.C)
@Preview(name = "English tier B", widthDp = 360, heightDp = 640) @Composable private fun EnglishB() = Gallery(false, GlassTier.B, AppLanguage.EN)
