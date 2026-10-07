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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** N-023 acceptance: the gallery renders every component, Bangla and English, at font scale 1.3 on 360 x 640 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class KitGalleryTest {
    @get:Rule val rule = createComposeRule()

    private fun render(language: AppLanguage) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
            AronTheme(language, dark = false) { KitGallery() }
        }
    }

    @Test @Config(sdk = [34], qualifiers = "bn-w360dp-h640dp") fun bangla() {
        render(AppLanguage.BN)
        rule.onNodeWithText("কিট গ্যালারি").assertExists()
        rule.onNodeWithText("সংরক্ষণ").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("বিক্রয় জমা দিতে চেপে ধরুন").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("১২").assertExists()
    }

    @Test fun english() {
        render(AppLanguage.EN)
        rule.onNodeWithText("Kit gallery").assertExists()
        rule.onNodeWithText("Save").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("12").assertExists()
    }

    /** The accessibility path (long-click action) confirms without holding; the timed hold is a device check. */
    @Test fun holdButtonConfirmsThroughTheLongClickAction() {
        render(AppLanguage.EN)
        rule.onNodeWithText("Hold to submit sales").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onAllNodesWithText("Submit sales?").assertCountEquals(1)
    }
}
