package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D-BRAND-01: the rules in the KDoc of [AronBrandMark] and [AronBrandPlate] that prose alone enforced. The plate is opaque in
 * every mode and is the parity look's own `card` white (docs/32 s2a: solid, never translucent, so the logo stays legible
 * outdoors); the logo is decorative (no semantics, so no string is needed); the sizes are the ones asked for. The logo itself
 * is the bundled artwork (supplied by the owner), so it may appear in a test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class AronBrandMarkTest {
    @get:Rule val rule = createComposeRule()

    private fun showPlate(dark: Boolean = false, sunlight: Boolean = false) {
        rule.setContent {
            AronTheme(AppLanguage.EN, dark = dark, sunlight = sunlight) {
                Box(Modifier.testTag("host")) { AronBrandPlate(size = 72.dp, markSize = 64.dp, cornerRadius = 22.dp) }
            }
        }
        rule.waitForIdle()
    }

    /**
     * At xhdpi a dp is 2 px. The four sample points are beside the glyph (it spans about 17 to 56 dp across the 72 dp plate) and
     * inside the corner arcs, so they are plate colour only. The glyph is proved by counting the pixels that are not plate
     * colour, not by one point, so a light ice shard on the sample point cannot fool the test.
     */
    private fun assertOpaqueCardPlate(card: androidx.compose.ui.graphics.Color, dark: Boolean = false, sunlight: Boolean = false) {
        showPlate(dark, sunlight)
        val image = rule.onNodeWithTag("host").captureToImage().toPixelMap()
        for ((xDp, yDp) in listOf(8 to 36, 64 to 36, 8 to 8, 64 to 64)) {
            val pixel = image[xDp * 2, yDp * 2].toArgb()
            assertEquals("plate pixel at $xDp,$yDp dp is the look's card colour", card.toArgb(), pixel)
            assertEquals("fully opaque at $xDp,$yDp dp", 0xFF, (pixel ushr 24) and 0xFF)
        }
        var drawn = 0
        for (y in 20 until 124) for (x in 36 until 112) if (image[x, y].toArgb() != card.toArgb()) drawn++
        assertTrue("the glyph is drawn on the plate ($drawn px differ from it)", drawn > 400)
        // the plate has rounded corners (the corner pixel is not plate)
        assertNotEquals("the corner is rounded off", card.toArgb(), image[1, 1].toArgb())
    }

    @Test fun thePlateIsTheOpaqueCardWhiteInTheStandardLook() = assertOpaqueCardPlate(AronLook.Default.card)

    @Test fun thePlateIsTheOpaqueCardWhiteInSunlightMode() = assertOpaqueCardPlate(AronLook.Sunlight.card, sunlight = true)

    @Test fun thePlateStaysSolidWhiteWhenTheThemeIsDark() = assertOpaqueCardPlate(AronLook.Default.card, dark = true)

    @Test fun thePlateIsAsBigAsAskedAndAddsNoSemantics() {
        showPlate()
        rule.onNodeWithTag("host").assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(72.dp)
        assertDecorative()
    }

    @Test fun theMarkAloneIsAsBigAsAskedAndDecorative() {
        rule.setContent { AronTheme(AppLanguage.EN) { Box(Modifier.testTag("host")) { AronBrandMark(size = 48.dp) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("host").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertDecorative()
    }

    /**
     * Nothing below the host says anything: whatever layout node Compose's `Image` leaves in the tree carries no semantic
     * property except the clip `Shape` the test framework records for any clipped layer, and nowhere in the unmerged tree is
     * there a content description or a text. TalkBack therefore skips the logo, and no string exists for it.
     */
    private fun assertDecorative() {
        rule.onNodeWithTag("host", useUnmergedTree = true).onChildren().fetchSemanticsNodes().forEach { child ->
            val said = child.config.map { it.key.name }.filter { it != "Shape" }
            assertTrue("a node below the logo carries semantics: $said", said.isEmpty())
        }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
    }
}
