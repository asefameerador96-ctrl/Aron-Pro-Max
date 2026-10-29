package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * tso-manual X4b (R-W-12 (3), (4)): the open list of [AronDropdown] is as wide as its field (screenshots 21 and 32), even when every
 * label is short (the menu used to wrap the longest label, at least 112 dp); and the kit has the picture glyph of Browse Gallery.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AronDropdownWidthTest {
    @get:Rule val compose = createComposeRule()

    private fun open(fieldWidthDp: Int, options: List<String>) {
        compose.setContent {
            AronTheme(AppLanguage.EN, dark = true, tso = true) {
                Box(Modifier.width(fieldWidthDp.dp)) { AronDropdown(options, null, {}, { it }, label = "Zone", placeholder = "Select Zone") }
            }
        }
        compose.onNodeWithTag(AronDropdownTags.FIELD).performClick()
        compose.waitForIdle()
    }

    @Test fun theOpenListIsAsWideAsItsFieldWithShortLabels() {
        open(240, listOf("A", "B", "C"))
        val field = compose.onNodeWithTag(AronDropdownTags.FIELD).fetchSemanticsNode().boundsInRoot.width
        val option = compose.onNodeWithTag(AronDropdownTags.option(0), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        assertTrue("field $field px, option $option px", abs(field - option) <= 1f)
    }

    @Test fun theOpenListIsAsWideAsItsFieldWithALongLabel() {
        open(240, listOf("Banani - Test", "Gulshan 1 - a long zone name that wraps the old menu"))
        val field = compose.onNodeWithTag(AronDropdownTags.FIELD).fetchSemanticsNode().boundsInRoot.width
        listOf(0, 1).forEach { i ->
            val option = compose.onNodeWithTag(AronDropdownTags.option(i), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
            assertTrue("option $i: field $field px, option $option px", abs(field - option) <= 1f)
        }
    }

    @Test fun thePictureGlyphIsAMaterialImageOn24dp() {
        val image = AronIcons.Image
        assertEquals("image", image.name)
        assertEquals(24f, image.viewportWidth); assertEquals(24.dp, image.defaultWidth)
    }
}
