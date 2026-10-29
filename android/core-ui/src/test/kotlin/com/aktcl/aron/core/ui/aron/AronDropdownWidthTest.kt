package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * tso-manual X4b (R-W-12 (3), (4)): the open list of [AronDropdown] is as wide as its field (screenshots 21 and 32), even when every
 * label is short (the menu used to wrap the longest label, at least 112 dp); and the kit has the picture glyph of Browse Gallery.
 * Fix round: also on the field phone's real field (360 dp screen, 16 dp gutters: 328 dp, wider than a Material menu item's 280 dp
 * preferred maximum) and at 200 % font, the list and every option span the field.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // text measured as on a phone: a long label wraps, 200 % text is taller
class AronDropdownWidthTest {
    @get:Rule val compose = createComposeRule()

    private fun open(fieldWidthDp: Int?, options: List<String>) {
        compose.setContent {
            AronTheme(AppLanguage.EN, dark = true, tso = true) {
                // null: the screen's own field, the whole width less the 16 dp gutters (328 dp on the 360 dp field phone)
                val box = if (fieldWidthDp != null) Modifier.width(fieldWidthDp.dp) else Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                Box(box) { AronDropdown(options, null, {}, { it }, label = "Zone", placeholder = "Select Zone") }
            }
        }
        compose.onNodeWithTag(AronDropdownTags.FIELD).performClick()
        compose.waitForIdle()
    }

    private fun width(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    /** The open list and each of its [count] options are as wide as the field, within a pixel; returns the field width in px. */
    private fun assertListSpansTheField(count: Int): Float {
        val field = width(AronDropdownTags.FIELD)
        assertTrue("list: field $field px, list ${width(AronDropdownTags.MENU)} px", abs(field - width(AronDropdownTags.MENU)) <= 1f)
        repeat(count) { i ->
            val option = width(AronDropdownTags.option(i))
            assertTrue("option $i: field $field px, option $option px", abs(field - option) <= 1f)
        }
        return field
    }

    private val zones = listOf("Banani - Test", "Gulshan 1 - a long zone name that wraps the old menu", "B")

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

    @Test fun theOpenListSpansTheFieldPhonesRealField() {
        open(null, zones)
        val field = assertListSpansTheField(zones.size)
        val density = compose.density.density
        assertEquals("the field is 328 dp (360 dp less two 16 dp gutters)", 328f, field / density, 1f)
    }

    @Test @Config(fontScale = 2.0f)
    fun theOpenListSpansTheFieldAt200PercentFont() {
        assertEquals(2f, compose.density.fontScale, 0.001f)
        open(null, zones)
        val field = assertListSpansTheField(zones.size)
        assertEquals(328f, field / compose.density.density, 1f)
        // the 200 % text makes the options taller, never wider than the field
        val tall = compose.onNodeWithTag(AronDropdownTags.option(1), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height / compose.density.density
        assertTrue("the long option at 200 % is $tall dp tall", tall > 48f)
    }

    @Test fun thePictureGlyphIsAMaterialImageOn24dp() {
        val image = AronIcons.Image
        assertEquals("image", image.name)
        assertEquals(24f, image.viewportWidth); assertEquals(24.dp, image.defaultWidth)
    }
}
