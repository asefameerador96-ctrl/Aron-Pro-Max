package com.aktcl.aron.feature.outlet

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P-SR1b (lead's low): the survey dropdown's spoken description names its question, so TalkBack does not read a bare "Answer: Yes" for every row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SurveyStepViewTest {
    @get:Rule val rule = createComposeRule()
    private val texts = SurveyTexts("Survey", "Answer:", "Select", "Yes", "No", "Answer the starred ones", "Take photo", "Retake", "Saved", "Skip", "Submit")

    private fun show(answer: String?) = rule.setContent {
        AronTheme(AppLanguage.EN) {
            SurveyStepView(
                "POSM", texts, listOf(SurveyRowUi("posm", "1", true, "Is there POSM at your outlet?", true, answer, false, false)),
                submitEnabled = true, onPick = { _, _ -> }, onPhoto = {}, onSkip = {}, onSubmit = {},
            )
        }
    }
    private fun spoken() = rule.onNodeWithTag(CallContentTags.answer("posm")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

    @Test fun theDropdownSpeaksItsQuestionAndItsAnswer() { show("Yes"); assertEquals("Is there POSM at your outlet?, Answer: Yes", spoken()) }
    @Test fun anUnansweredDropdownSpeaksItsQuestionAndTheHint() { show(null); assertEquals("Is there POSM at your outlet?, Answer: Select", spoken()) }

    @Test fun theContentItemIsNamedForTalkBack() {
        rule.setContent { AronTheme(AppLanguage.EN) { ContentStepView("KV", "Close", {}, "Key visual (KV) picture") { } } }
        val d = rule.onNodeWithTag(CallContentTags.PICTURE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        assertEquals("Key visual (KV) picture", d)
    }
}
