package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.aron.AronLookGallery
import com.aktcl.aron.core.ui.aron.LocalPackshotProvider
import com.aktcl.aron.core.ui.aron.PackCard
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.core.ui.aron.PackshotProvider
import com.aktcl.aron.core.ui.aron.aronTileColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The parity kit (OWNER DIRECTIVE 2026-10-18): every component renders in Bangla and English at font scale 1.0 and 1.3 on 360 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h3800dp")
class AronLookGalleryTest {
    @get:Rule val rule = createComposeRule()

    private fun render(language: AppLanguage, fontScale: Float, sunlight: Boolean = false) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
            AronTheme(language, sunlight = sunlight) { AronLookGallery() }
        }
    }

    /** No text is cut off or wider than its box; every clickable is 48 dp and has a name for TalkBack. */
    private fun sweep() {
        val textNodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(textNodes.size > 30)
        val overflowing = textNodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull() ?: return@mapNotNull null
            val len = r.layoutInput.text.text.trimEnd().length
            val cutOff = r.getLineEnd(r.lineCount - 1, visibleEnd = true) < len
            val tooWide = (0 until r.lineCount).any { r.layoutInput.constraints.hasBoundedWidth && r.getLineRight(it) > r.layoutInput.constraints.maxWidth + 1f }
            if (cutOff || tooWide || r.didOverflowHeight) "${node.config.getOrNull(SemanticsProperties.Text)} cutOff=$cutOff tooWide=$tooWide" else null
        }
        assertTrue("text overflows: $overflowing", overflowing.isEmpty())
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickable.size > 25)
        with(rule.density) {
            val small = clickable.filter { it.size.height.toDp() < 47.5.dp || it.size.width.toDp() < 47.5.dp }.map { "${it.config.getOrNull(SemanticsProperties.Text)} ${it.size}" }
            assertTrue("touch targets too small: $small", small.isEmpty())
        }
        val unnamed = clickable.filter { n ->
            n.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() && n.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
                n.config.getOrNull(SemanticsProperties.EditableText) == null
        }.map { "${it.boundsInRoot}" }
        assertTrue("controls without a name: $unnamed", unnamed.isEmpty())
    }

    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun bangla() {
        render(AppLanguage.BN, 1f)
        rule.onNodeWithText("সংরক্ষণ", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Apsis RouteDaily, ২০২৬-১০-০৫", useUnmergedTree = true).assertExists()
        sweep()
    }

    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun banglaLargeText() {
        render(AppLanguage.BN, 1.3f)
        sweep()
    }

    @Test @Config(qualifiers = "bn-w360dp-h6200dp") fun banglaFontScaleTwo() {
        render(AppLanguage.BN, 2f)
        sweep()
    }

    @Test @Config(qualifiers = "bn-w411dp-h3800dp") fun banglaWidePhone() {
        render(AppLanguage.BN, 1f)
        sweep()
    }

    @Test fun englishLargeText() {
        render(AppLanguage.EN, 1.3f)
        rule.onNodeWithText("Select retailer", useUnmergedTree = true).assertExists()
        sweep()
    }

    @Test @Config(qualifiers = "bn-w360dp-h3800dp") fun banglaSunlight() {
        render(AppLanguage.BN, 1f, sunlight = true)
        sweep()
    }

    @Test fun quantityFieldKeepsDigitsOnly() {
        var typed = ""
        rule.setContent {
            AronTheme(AppLanguage.EN) {
                PackCard("s1", "MaxR-10S", "", { typed = it }, {}, {}, "0", "Less", "More", "Quantity")
            }
        }
        rule.onNodeWithContentDescription("Quantity").performTextReplacement("1a2-৩")
        assertEquals("12৩", typed)
    }

    @Test fun minusAndPlusCallBack() {
        var minus = 0
        var plus = 0
        rule.setContent { AronTheme(AppLanguage.EN) { PackCard("s1", "MaxR-10S", "1", {}, { minus++ }, { plus++ }, "0", "Less", "More", "Quantity") } }
        rule.onNodeWithContentDescription("Less").performClick()
        rule.onNodeWithContentDescription("More").performClick()
        assertEquals(1, minus)
        assertEquals(1, plus)
    }

    @Test fun packshotUsesTheProviderAndFallsBackToTheSilhouette() {
        val asked = mutableListOf<String>()
        val provider = object : PackshotProvider {
            @Composable
            override fun painter(skuId: String): Painter? {
                asked += skuId
                return if (skuId == "has") BitmapPainter(ImageBitmap(4, 4)) else null
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalPackshotProvider provides provider) {
                AronTheme(AppLanguage.EN) {
                    PackshotImage("has", contentDescription = "pack has")
                    PackshotImage("none", contentDescription = "pack none")
                }
            }
        }
        rule.onNodeWithContentDescription("pack has").assertExists()
        rule.onNodeWithContentDescription("pack none").assertExists()
        assertTrue(asked.containsAll(listOf("has", "none")))
    }

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun dialogInBanglaAtLargeText() {
        var confirmed = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                AronTheme(AppLanguage.BN) {
                    com.aktcl.aron.core.ui.aron.AronDialog(
                        title = "বিক্রয় জমা দেবেন?", text = "জমা দেওয়ার পর আজকের বিক্রয় আর পরিবর্তন করা যাবে না।",
                        confirmText = "হ্যাঁ", onConfirm = { confirmed = true }, onDismiss = {}, dismissText = "না",
                    )
                }
            }
        }
        rule.onNodeWithText("হ্যাঁ").performClick()
        assertTrue(confirmed)
    }

    @Test fun fieldReportsItsError() {
        rule.setContent {
            AronTheme(AppLanguage.EN) { com.aktcl.aron.core.ui.aron.AronField("", {}, "Shop name", errorText = "Required") }
        }
        rule.onNodeWithContentDescription("Shop name").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Required"))
    }

    @Test fun tileColumns() {
        assertEquals(4, aronTileColumns(360f, 1f))
        assertEquals(4, aronTileColumns(411f, 1.15f))
        assertEquals(3, aronTileColumns(360f, 1.3f))
        assertEquals(2, aronTileColumns(360f, 2f))
    }
}
