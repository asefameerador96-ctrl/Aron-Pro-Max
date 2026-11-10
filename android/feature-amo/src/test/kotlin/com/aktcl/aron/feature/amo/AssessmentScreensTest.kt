package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Screenshots 27-28 and 31-32: the saved dialog, the POSM requirement's error line, the Joint Call v2 form. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AssessmentScreensTest {
    @get:Rule val compose = createComposeRule()
    private val brands = (1L..6L).map { AmoBrand(it, "Brand $it", null) }
    private val rubric = JointCall.rubric(
        """[{"rubric_id":9,"version":2,"kind":"joint_call","criteria":[
        {"criterion_id":1,"label_en":"Summarise the situation","answer_type":"score_1_5","enabled":true,"section":"5-step sales call assessment","guidance_en":"What to look for: OHS count"},
        {"criterion_id":2,"label_en":"Relationship","answer_type":"score_1_5","enabled":true,"section":"Relationship assessment","guidance_en":"g2"}]}]""",
    )!!

    private fun content(c: @androidx.compose.runtime.Composable () -> Unit) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.EN, amo = true) { c() } } }
    }

    @Test fun perfAssessmentShowsTheHeaderCardAndBothBandsAndTheSavedDialogOkReturns() {
        var ok = 0
        content { PerfAssessmentContent(brands, PerfState(posm = false), saved = true, saving = false, {}, {}, {}, {}, outletLabel = "Masba Store (2689479-01865967033-Jobbar)", routeName = "Gulshan-1Daily", onSavedOk = { ok++ }) }
        compose.onNodeWithTag(PerfTags.HEADER).assertIsDisplayed()
        compose.onNodeWithTag(AmoDialogTags.SAVED).assertIsDisplayed()
        compose.onNodeWithTag(PerfTags.SAVE).assertIsNotEnabled() // locked while the dialog is up
        compose.onNodeWithTag(AmoDialogTags.OK).performClick()
        assertEquals(1, ok)
    }

    @Test fun posmErrorLineShowsWhenRequiredAndMissing() {
        content { PerfAssessmentContent(brands, PerfState(), false, false, {}, {}, {}, {}, posmError = true) }
        compose.onNodeWithTag(PerfTags.POSM_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PerfTags.SAVE).assertIsEnabled() // Save stays tappable; the host refuses and the line explains
    }

    @Test fun jointCallShowsOneBandPerSectionWithGuidanceAndLowHighBadges() {
        var star = 0L to 0
        content { JointCallContent(rubric, null, JointState(), saved = false, saving = false, { id, n -> star = id to n }, {}, outletLabel = "Masba Store", routeName = "Gulshan-1Daily") }
        compose.onNodeWithTag(JointTags.section(0)).assertIsDisplayed(); compose.onNodeWithTag(JointTags.section(1)).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTagCount(JointTags.LOW) == 2 && compose.onAllNodesWithTagCount(JointTags.HIGH) == 2)
        compose.onNodeWithTag(JointTags.star(1, 4)).performScrollTo().performClick()
        assertEquals(1L to 4, star)
        compose.onNodeWithTag(JointTags.SAVE).assertIsEnabled() // star 1 preselected: Save is always enabled
    }

    @Test fun withNoDefaultStarSaveWaitsForEveryRating() {
        content { JointCallContent(rubric, null, JointState(), saved = false, saving = false, { _, _ -> }, {}, defaultStar = null) }
        compose.onNodeWithTag(JointTags.SAVE).assertIsNotEnabled()
    }

    @Test fun jointSavedDialogIsShownAndOkIsReported() {
        var ok = 0
        content { JointCallContent(rubric, null, JointState(), saved = true, saving = false, { _, _ -> }, {}, onSavedOk = { ok++ }) }
        compose.onNodeWithTag(AmoDialogTags.SAVED).assertIsDisplayed(); compose.onNodeWithTag(AmoDialogTags.OK).performClick(); assertEquals(1, ok)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
