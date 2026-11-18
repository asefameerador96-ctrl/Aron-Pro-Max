package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GiftPhotoScreenTest {
    @get:Rule val compose = createComposeRule()
    private val json = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"}],"loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot","points_cost":null,"tier_codes":[],"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":71,"quarter":"2026-Q4","outlet_id":10,"gift_id":1,"photo_captured":false,"locked":false},
                          {"gift_assignment_id":72,"quarter":"2026-Q4","outlet_id":11,"gift_id":1,"photo_captured":true,"locked":true}]}"""
    private val shops = listOf(ShopInfo(10, "Milon (1)", "1", "M", null, null, chip = "M"), ShopInfo(11, "Done (2)", "2", "D", null, null, chip = "D"))
    private val saves = mutableListOf<Triple<Long, Long, Long>>()

    private fun show(outlet: Long, canSubmit: Boolean) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    GiftPhotoScreen(shops, GiftChoiceModel.parse(json), "2026-Q4", emptyList(), UiLocale.EN, outlet, {}, photoBlock = {}, canSubmit = canSubmit, checking = false,
                        onSubmit = { o, g, a -> saves += Triple(o, g, a); SaveOutcome.SAVED })
                }
            }
        }
        compose.waitForIdle()
    }

    /** The host passes canSubmit = fix complete AND not mocked: a mocked fix arrives here as false. */
    @Test fun submitIsDisabledUntilTheCaptureIsCompleteAndNotMocked() { show(10, canSubmit = false); compose.onNodeWithTag(PhotoTags.SUBMIT).performScrollTo().assertIsNotEnabled() }

    @Test fun aCompleteCaptureSubmitsTheAssignmentOnce() {
        show(10, canSubmit = true)
        compose.onNodeWithTag(PhotoTags.SUBMIT).performScrollTo().assertIsEnabled().performClick(); compose.waitForIdle()
        assertEquals(listOf(Triple(10L, 1L, 71L)), saves)
    }

    @Test fun anAlreadyPhotographedOutletShowsNoCameraAndNoSubmit() {
        show(11, canSubmit = true)
        compose.onNodeWithTag(PhotoTags.CAPTURED).assertExists()
        assertEquals(0, compose.onAllNodesWithTagCount(PhotoTags.SUBMIT))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
