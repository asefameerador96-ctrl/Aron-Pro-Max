package com.aktcl.aron.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** N-023 acceptance: the gallery renders every component, Bangla and English, at font scale 1.3 on 360 x 640 dp. */
@RunWith(RobolectricTestRunner::class)
// NATIVE graphics: the legacy Robolectric mode measures text at 1 px per character, which makes every overflow check meaningless.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class KitGalleryTest {
    @get:Rule val rule = createComposeRule()

    /** No text may overflow its box, and every clickable node is at least 48 dp (the two N-023 acceptance clauses). */
    private fun assertNoTruncationAndTouchTargets() {
        // the UNMERGED tree: clickable rows merge their children and keep only the first text's layout, hiding trailing and subtitle texts
        val textNodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(textNodes.size > 20)
        val overflowing = textNodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull() ?: return@mapNotNull null
            // hasVisualOverflow is not usable here (it is true for every node); test the three real truncations instead
            val len = r.layoutInput.text.text.trimEnd().length
            val cutOff = r.getLineEnd(r.lineCount - 1, visibleEnd = true) < len
            val tooWide = (0 until r.lineCount).any { r.layoutInput.constraints.hasBoundedWidth && r.getLineRight(it) > r.layoutInput.constraints.maxWidth + 1f }
            if (cutOff || tooWide || r.didOverflowHeight) "${node.config.getOrNull(SemanticsProperties.Text)} size=${node.size} cutOff=$cutOff tooWide=$tooWide overflowH=${r.didOverflowHeight}" else null
        }
        assertTrue("text overflows: $overflowing", overflowing.isEmpty())
        val clickable = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNodes()
        assertTrue(clickable.size >= 12)
        with(rule.density) {
            val small = clickable.filter { n -> n.size.height.toDp() < 47.5.dp || n.size.width.toDp() < 47.5.dp }
                .map { "${it.config.getOrNull(SemanticsProperties.Text)} size=${it.size}" }
            assertTrue("touch targets too small: $small", small.isEmpty())
        }
    }

    /** Dialogs are separate windows and closed in the gallery: open one and run the same sweeps (its text and its buttons). */
    private fun openInfoDialogAndSweep(openLabel: String) {
        rule.onNodeWithText(openLabel).performClick()
        rule.waitForIdle()
        assertNoTruncationAndTouchTargets()
    }

    private fun render(language: AppLanguage, sunlight: Boolean = false) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
            AronTheme(language, dark = false, sunlight = sunlight) { KitGallery() }
        }
    }

    @Test @Config(sdk = [34], qualifiers = "bn-w360dp-h640dp") fun bangla() {
        render(AppLanguage.BN)
        rule.onNodeWithText("কিট গ্যালারি").assertExists()
        rule.onNodeWithText("সংরক্ষণ").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("বিক্রয় জমা দিতে চেপে ধরুন").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("১২").assertExists()
        assertNoTruncationAndTouchTargets()
        openInfoDialogAndSweep("তথ্য দেখুন")
    }

    @Test fun englishInSunlightMode() {
        render(AppLanguage.EN, sunlight = true)
        rule.onNodeWithText("Kit gallery").assertExists()
        assertNoTruncationAndTouchTargets()
    }

    @Test @Config(sdk = [34], qualifiers = "bn-w360dp-h640dp") fun banglaInSunlightMode() {
        render(AppLanguage.BN, sunlight = true)
        rule.onNodeWithText("কিট গ্যালারি").assertExists()
        assertNoTruncationAndTouchTargets()
    }

    @Test fun english() {
        render(AppLanguage.EN)
        rule.onNodeWithText("Kit gallery").assertExists()
        rule.onNodeWithText("Save").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("12").assertExists()
        assertNoTruncationAndTouchTargets()
        openInfoDialogAndSweep("Show information")
    }

    /** The accessibility path (long-click action) confirms without holding; the timed hold is a device check. */
    @Test fun holdButtonConfirmsThroughTheLongClickAction() {
        render(AppLanguage.EN)
        rule.onNodeWithText("Hold to submit sales").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onAllNodesWithText("Submit sales?").assertCountEquals(1)
    }
}
